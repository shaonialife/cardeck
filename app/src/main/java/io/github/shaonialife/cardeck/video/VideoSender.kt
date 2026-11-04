package io.github.shaonialife.cardeck.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import io.github.shaonialife.cardeck.net.TcpLiveness
import io.github.shaonialife.cardeck.protocol.NativeBridge
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主屏视频发送端（Phase 2-C2）。
 *
 * 线格式（帧头/密封/格式域全在 Rust——fw:airplay::screen + fw::media_format：
 * AnnexB→NAL 切分/SPS level 补丁/avcC 封装均为下沉的原生调用，本类只剩
 * MediaCodec 泵 + socket 泵）：
 *  - 每条消息 = 128 字节头 + body；头部 = [0..3] u32LE body 长度、[4] opcode、
 *    [8..15] NTP 32.32 LE 发送时间（fw: sbpd 域 @0x2276b70ac）
 *  - opcode 1 = VideoConfig：明文 avcC 记录（H.264 标准封装）
 *  - opcode 0 = VideoFrame：密封的「4 字节 BE 长度前缀 NAL 序列」——
 *    ChaCha（nonce=8B LE 帧计数，fw: cryptor+0x110）/ AES-CTR（跨帧密钥流）
 *
 * 视频源：MediaCodec Surface 输入（H.264 硬编码），内容由画面源渲染
 * （隐形屏模式的 DualDisplayComposer 输入 Surface）。
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    /**
     * AirPlay 会话泵句柄（帧密封态驻留 Rust 会话——fw: cryptor 驻留流存储
     * 域；streamSetup 应答时按车机 `et` 形态建立，宿主零钥面）。
     */
    private val sessionHandle: Long,
    /** 会话墙钟锚（NTP-1900 秒——帧头/config 时间戳同基化，见 AirPlayLink）。 */
    private val ntpEpochAnchorSecs: Long = 0L,
    /** H.264 4:2:0 要求偶数尺寸——奇数高度会让编码器在首帧输入时进错误态（125% 缩放实测踩坑）。 */
    width: Int,
    height: Int,
    /** 目标帧率（30/60）：编码器元数据 + 60fps 提码率；实际节奏由合成器驱动。 */
    private val targetFps: Int = 30,
    private val log: (String) -> Unit,
    /** 推流自然结束（对端断开等）时回调——CarPlay 会话真实结束的信号。 */
    private val onEnded: (() -> Unit)? = null,
    /**
     * 屏幕流内 keepAlive 帧是否携带统计体（车机 /info 的 `keepAliveSendStatsAsBody`）。
     * 奥迪 MMI 3315 声明 true → opcode=5 + bplist 统计体；否则 opcode=2 空帧。
     * 真 iPhone/catplay 在视频持续推流时**仍然每秒发一次**——它不是视频流量的备份，
     * 而是接收端屏幕上独立的存活信号（2026-10-19 奥迪 3315 两轮实测：缺它时
     * RECORD 后 10.2s 整轮被 reset，视频/feedback/事件通道全都健康）。
     */
    private val keepAliveWithStats: Boolean = false,
) {
    private val width: Int = (width / 2) * 2
    private val height: Int = (height / 2) * 2
    private var socket: Socket? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private var configSent = false

    /** 上一次屏幕流 keepAlive 的时刻（uptimeMillis；0 = 尚未发过）。 */
    private var lastKeepAliveAt = 0L

    /** 两次 keepAlive 之间的帧数/字节数（统计体用，catplay ScreenSenderStats 同义）。 */
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L

    @Volatile
    private var framesSent = 0L

    /** 推流是否活跃（静默降噪守卫判断用：start 失败时保持 false）。 */
    val isRunning: Boolean get() = running.get()

    /** 已发送帧总数（英雄卡实时帧率 = 1s 轮询取差值）。 */
    val framesSentTotal: Long get() = framesSent

    /**
     * 提前建立屏幕数据端口 TCP 连接（幂等）。
     *
     * 车机看门狗（2026-10-17 奥迪实测）：屏幕流 SETUP 之后 ~2s 内数据端口必须连上，
     * 否则直接 reset 控制通道拆掉整轮会话。音频 SETUP 本身要 ~2.1s，若排在它后面，
     * 端口连接必然迟到——所以协商音频前先调本方法。catplay 亦然（视频 SETUP 后立即
     * finish_tx 连流，再协商音频）。
     */
    fun openDataPort(): Boolean {
        val existing = socket
        if (existing != null && !existing.isClosed) return true
        return try {
            val client = Socket()
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            // 推流是长连接：车机熄火/走出热点时链路常不发 RST，写入会阻塞在满窗口上，
            // 编码器线程随之卡死（表现为「画面停在最后一帧」）。
            TcpLiveness.configure(client, "视频数据口", log)
            socket = client
            log("VIDEO: 数据端口已连通 $host:$dataPort（等 RECORD 后启动编码器）")
            true
        } catch (error: Exception) {
            log("VIDEO: 数据端口连接失败 - ${error.message}")
            false
        }
    }

    /**
     * 启动编码与推流。编码器输入 Surface 通过 [onSurfaceReady] 交给调用方渲染
     * （隐形屏模式下由 DualDisplayComposer 的合成画布填充）。
     */
    fun start(onSurfaceReady: (Surface) -> Unit): Boolean {
        if (running.get()) return true
        return try {
            if (!openDataPort()) {
                stop()
                return false
            }
            val bitrate =
                (
                    ((width * height * 6L).toInt()).coerceIn(2_000_000, 10_000_000) *
                        if (targetFps >= 60) 1.5f else 1f
                ).toInt().coerceAtMost(15_000_000)
            log("VIDEO: 启动编码器 ${width}x$height@$targetFps ${bitrate / 1_000_000}Mbps（端口已连通）")

            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // 固定 H.264 level 4.0（SPS level_idc=40，真 iPhone 同款流形态）。
                    // 不固定时编码器按「分辨率+帧率」自动选：默认 30fps 下 1412x654
                    // 合法落入 level 3.2——奥迪 DELPHI 解码器对该 level 静默不渲染
                    // （TCP 照收、会话活着、车机黑屏，2026-10-22 奥迪 0628 真机日志
                    // 定谳：同机 level=40 全部成功、level=32 首例黑屏）。60fps 时编码
                    // 器本来就选 4.0，此设置对它无变化。
                    runCatching {
                        setInteger(
                            MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                        )
                    }
                }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // 个别编码器不接受 level 指定（configure 抛异常=视频全灭），降级为不指定重试
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (levelRejected: Exception) {
                log("VIDEO: 指定 level 4.0 被编码器拒绝（${levelRejected.message}）——回退自动选择（部分车机可能黑屏）")
                val fallback =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                codec.configure(fallback, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            inputSurface = surface
            running.set(true)
            lastKeepAliveAt = android.os.SystemClock.uptimeMillis()

            onSurfaceReady(surface)

            Thread({ drainEncoder() }, "video-drain").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("VIDEO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { socket?.close() }
        socket = null
    }

    /**
     * 立即编码一个关键帧（车机 `/command` 的 `forceKeyFrame` 请求）。
     *
     * 车机解不出画面时会反复要 I 帧；我们本身每秒一个，但**要帧请求必须立即响应**——
     * 否则车机可能一直卡在「等下一个 I 帧」的状态，表现就是「连上了但没画面」
     * （宝马 iD9 2026-10-20 实测：会话刚起时收到一次 forceKeyFrame）。
     */
    fun requestKeyFrame(): Boolean {
        val codec = encoder ?: return false
        return try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            true
        } catch (error: Exception) {
            log("VIDEO: 请求关键帧失败 - ${error.message}")
            false
        }
    }

    // ---- 编码输出 → 协议封装发送 ----

    private fun drainEncoder() {
        val codec = encoder ?: return
        val output = socket?.getOutputStream() ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index =
                    try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (error: Exception) {
                        // 编码器进错误态（如非法尺寸首帧）：必须留痕——静默 break 曾让
                        // 「零帧推流 4 秒后断开」无从排查（接收端只见 peer EOF）
                        log("VIDEO: 编码器取帧异常，停止推流 - ${error.message}")
                        break
                    }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        maybeSendKeepAlive(output)
                        continue
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        val flags = bufferInfo.flags
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sendVideoConfig(buffer, bufferInfo.size, output)
                        } else if (bufferInfo.size > 0) {
                            sendVideoFrame(
                                buffer,
                                bufferInfo.size,
                                bufferInfo.presentationTimeUs,
                                flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                                output,
                            )
                        }
                        codec.releaseOutputBuffer(index, false)
                        maybeSendKeepAlive(output)
                    }
                }
            }
        } catch (error: Exception) {
            // 视频数据口断开（如接收端屏幕关闭收摊会话）：只终止推流，绝不冒泡——
            // 后台线程未捕获异常会杀死整个进程（曾导致锁屏后 app 消失）
            log("VIDEO: 推流中止 - ${error.message}（等待接收端恢复后重连）")
        } finally {
            stop()
            // 会话真实结束信号（静默降噪解除 / 状态收尾）
            runCatching { onEnded?.invoke() }
        }
    }

    /**
     * 屏幕流 keepAlive 帧（catplay `ScreenTransmitSession::create_keep_alive`，1s 间隔）。
     *
     * 与 UDP keepAlive / RTSP feedback 是三条相互独立的存活信号；接收端（奥迪 3315）
     * 只看视频帧不够——缺这条时 RECORD 后约 10s 会 reset 整轮会话（2026-10-19 两轮
     * 实测）。帧是**明文**的（catplay 仅对 opcode=VideoFrame 加密），因此不消耗 ChaCha
     * 帧计数；带统计体时 opcode=5，并把 body 长度写进头部 params[14] 的后 4 字节
     * （f32 LE），与 catplay 逐字节一致。
     */
    private fun maybeSendKeepAlive(output: java.io.OutputStream) {
        if (!running.get()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastKeepAliveAt < KEEPALIVE_INTERVAL_MS) return
        val elapsedNanos = (now - lastKeepAliveAt).coerceAtLeast(1L) * 1_000_000.0
        val frames = framesSinceKeepAlive
        val bytes = bytesSinceKeepAlive
        val framesPerSecond = (frames * 1_000_000_000.0 / elapsedNanos).toInt()
        val bytesPerSecond = bytes * 1_000_000_000.0 / elapsedNanos
        framesSinceKeepAlive = 0
        bytesSinceKeepAlive = 0
        lastKeepAliveAt = now
        keepAliveCount++
        val frame =
            NativeBridge.nativeAirplayScreenKeepAliveFrame(
                keepAliveWithStats,
                frames,
                bytesPerSecond,
                framesPerSecond,
            ) ?: ByteArray(0)
        if (frame.isEmpty()) return
        output.write(frame)
        output.flush()
        if (keepAliveCount <= 3 || keepAliveCount % 30 == 0L) {
            log(
                "VIDEO: keepAlive #$keepAliveCount " +
                    "opcode=${if (keepAliveWithStats) OP_KEEP_ALIVE_WITH_BODY else OP_KEEP_ALIVE} " +
                    "body=${frame.size - HEADER_LEN}B fps=$framesPerSecond",
            )
        }
    }

    private fun sendVideoConfig(
        buffer: java.nio.ByteBuffer,
        size: Int,
        output: java.io.OutputStream,
    ) {
        val annexB = ByteArray(size)
        buffer.get(annexB)
        // csd → avcC（SPS 补丁 + ISO 14496-15 封装）在 Rust
        // fw::media_format::build_avcc；null = 缺 SPS/PPS
        val body = NativeBridge.nativeMediaAvcC(annexB)
        if (body == null) {
            log("VIDEO: csd 缺少 SPS/PPS，跳过 config")
            return
        }
        // 头部必须与 catplay `ScreenFrame::config` 一致，否则部分车机（Audi/DELPHI 实测：
        // 连接与触摸都正常、车机屏全黑）会把后续加密帧按明文 NAL 解析 → 解码失败：
        //  - small_param[1] |= ScreenFlag::Encrypted(0x04)：声明「视频帧是加密的」
        //  - params[1] = (width, height) 两个 f32 LE：车机据此建立解码器画布
        //  - params[0] 归零（catplay 的 config 不带 PTS；只有 VideoFrame 才写 params[0]）
        // 旧实现三项都没写——DiPlay/WinPlay 只读 body、不校验头，所以测试台一直正常，
        // 真车（Audi 4851）却黑屏（2026-10-19 用户日志）。
        val header =
            NativeBridge.nativeAirplayScreenBuildConfigHeader(
                sessionHandle,
                body.size,
                width,
                height,
                System.nanoTime(),
                ntpEpochAnchorSecs.toInt(),
            ) ?: return
        output.write(header)
        output.write(body)
        output.flush()
        configSent = true
        // avcC 前 4 字节 = version/profile/compat/level（与 SPS 同位——level 已补 4.0）
        log(
            "VIDEO: ✓ VideoConfig 已发送 avcC=${body.size}B，" +
                "profile=${body[1].toInt() and 0xFF} level=${body[3].toInt() and 0xFF} " +
                "flags=0x%02x ${width}x$height）".format(header[6].toInt() and 0xFF) +
                "\n  ↳ 头部前 24 字节=" + header.take(24).joinToString(" ") { "%02x".format(it) },
        )
    }

    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        ptsUs: Long,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        if (!configSent) {
            log("VIDEO: 丢弃一帧（config 尚未发送）")
            return
        }
        val annexB = ByteArray(size)
        buffer.get(annexB)
        // 帧体打包（4B BE 长度前缀 NAL 序列 + 帧内 SPS 同步补丁——车机会用
        // 帧内 SPS 更新解码器参数）在 Rust fw::media_format::pack_nals；
        // null = 空流
        val plain = NativeBridge.nativeMediaPackNals(annexB) ?: return

        // 帧头构造 + 密封全在 Rust（A4 全量形态——fw: cryptor 变换
        // @0x227969f68）：ChaCha nonce = 8B LE 帧计数（会话驻留态）/ CTR
        // 密钥流跨帧连续；头只构造一次且作 AAD（0x2276b7300 指令级定谳），
        // 时间戳 = convert(帧 PTS + 屏流延迟)。null = 无封包态。
        val frame =
            NativeBridge.nativeAirplayScreenSealFrame(sessionHandle, plain, ptsUs * 1000, ntpEpochAnchorSecs.toInt())
                ?: run {
                    log("VIDEO: 封包态不可用（无 streamSetup 密态）——跳帧")
                    return
                }
        output.write(frame)
        output.flush()
        framesSent++
        framesSinceKeepAlive++
        bytesSinceKeepAlive += plain.size.toLong()
        // 不能按 keyFrame 无条件打——编码器 I 帧 1 秒一个，等于每秒刷屏（且 2MB 日志
        // 轮转会冲掉关键证据，2026-10-26 两次实测）。前 3 帧 + 每 300 帧（30fps 下
        // 每 10s）打一条
        if (framesSent <= 3 || framesSent % 300 == 0L) {
            log("VIDEO: 帧 #$framesSent ${plain.size}B${if (keyFrame) " (key)" else ""} 已发送")
        }
    }

    /** 按 00 00 00 01 / 00 00 01 起始码切分 AnnexB 码流为 NAL 列表。 */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
            return units
        }
        for (unit in 0 until starts.size) {
            val start = starts[unit]
            val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
            val bodyStart = start + prefix
            val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
        }
        return units
    }

    companion object {
        private const val OP_FRAME = 0
        private const val OP_KEEP_ALIVE = 2
        private const val OP_KEEP_ALIVE_WITH_BODY = 5

        /** 屏幕流 keepAlive 间隔（catplay `DEFAULT_KEEP_ALIVE` = 1s）。 */
        private const val KEEPALIVE_INTERVAL_MS = 1_000L

        /** 128B 消息头（fw: `calloc(1, 0x80)` @0x2276b6964——布局/构造全在 Rust）。 */
        internal const val HEADER_LEN = 128
    }
}
