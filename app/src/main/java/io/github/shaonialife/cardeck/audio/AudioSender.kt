package io.github.shaonialife.cardeck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.TimingServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（对照 DiPlay `AudioStream.kt` 解密路径与 catplay `rtp_cipher_chacha.rs`）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（timestamp+ssrc 的 BE 表示，
 *    catplay 的 "modern AAD"；DiPlay 解密即取同一范围）
 *  - nonce（12B）= 4 字节零 + 尾 8 字节（小端计数，从 0 逐包递增）→ [AirPlayCrypto.nonce64]
 *  - RTP 字段：version=2、PT=流类型（100=PCM 通路 / 102=AAC-LC 通路；
 *    对照 catplay `payload_type = stream_type`）、
 *    SSRC=0（catplay 同款）、timestamp = 累计样本帧数、seq 逐包递增
 *  - **首个音频包置 Marker 位**（规范：RECORD/FLUSH 之后的首包要打标记，接收端据此
 *    切分播放边界；catplay 恒 false，但我们的会话是「RECORD 之后才加入音频流」，
 *    首包标记能帮接收端重新对齐）
 *
 * 旁路：
 *  - **同步包**：[AudioSyncPacket] 每 1 秒发往该流的 `controlPort`，建立 RTP↔NTP 锚点。
 *    （缺了它接收端只能「按到达时刻猜」，严格的实现干脆不排播——2026-10-20 实测）
 *
 * 采集：`AudioPlaybackCapture`（Android 10+）捕获手机端其他应用播放的媒体/导航音频
 * （需要 MediaProjection——与镜像模式的同一授权）。每包 [SAMPLES_PER_PACKET] 样本帧
 * （48000Hz 立体声 16bit = 2048B 负载，总包 ~2084B，不超接收端 4096B 收包缓冲）。
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 静音手机媒体音量（声音只从车机出）。null = 不做（测试用）。 */
    private val context: android.content.Context? = null,
    /**
     * 采集采样率（Hz）：必须与 SETUP 协商出的格式一致（48k 主选，车机只支持
     * 44.1k 时由 AirPlayClient 协商下发）。默认 48k。
     */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /**
     * 该音频流的控制端口（车机在 SETUP 应答里下发）。>0 时每 [SYNC_INTERVAL_MILLIS]
     * 发一个 [AudioSyncPacket] 建立 RTP↔NTP 锚点；0 = 车机没给（旧车机），只发音频。
     */
    private val controlPort: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /**
         * 每包样本帧数 = AirPlay 标准 SPF 352（catplay `MAX_FRAMES_PER_PACKET_PCM`，
         * 真车验证）。48000/352 ≈ 136 包/秒（7.33ms/包）。
         * 2026-10-17 实测：此前用 512 帧（10.67ms/包），奥迪接收端无声音——
         * 与真 iPhone 的包长不一致时接收端可能按固定 SPF 缓冲而丢弃。
         */
        const val SAMPLES_PER_PACKET = 352

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** 静音前媒体音量的持久化键（进程被杀也不丢恢复目标）。 */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null

    /**
     * 采集诊断发现全零时的嫌疑回调（宿主侧核「确有应用在播」后再断开蓝牙音频
     * 设备回收路由——采集约每 14s 一次诊断采样，宿主有自己的节流）。
     */
    var onRouteHijackSuspect: (() -> Unit)? = null

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /**
     * RTP 游标（原本是采集循环里的局部变量）。提到字段上是因为**同步包线程**要读
     * 「当前/下一包时间戳」，否则锚点里的时间戳永远对不上真实发出的包。
     */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /** 首个音频包标记（打完就清）；AAC 自检只跑一次。 */
    private var markerPending = true
    private var selfChecked = false

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /**
     * RTP SSRC：固定 0（catplay `prepare_header` 在真车上即用 0；DiPlay 收端不校验）。
     * 2026-10-17 实测：此前用随机值，奥迪接收端无声音——部分车机按 SSRC 关联 SETUP
     * 声明的流（非 0 视为未知流丢弃）。
     */
    private val ssrc = 0

    /** 采集+推流统计（日志限流用）。 */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var lastLogAt = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** 静音前的手机媒体音量 index（-1 = 未静音）。 */
    private var mutedOriginalVolume = -1

    /**
     * 静音手机媒体输出：音频通道激活后声音只从车机出（真 CarPlay 行为）。
     * 依据（实测 2026-10-10）：播放捕获取自 REMOTE_SUBMIX（音量应用点之前），
     * 手机流音量静音**不影响捕获数据**——手机不响、车机照常出声。
     *
     * 恢复值持久化（SharedPreferences）：进程被系统杀死/安装更新时来不及恢复，
     * 下次静音若读到 0 会把它当"原音量"，导致断开后永久静音（2026-10-10 踩过）。
     */
    private fun mutePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            if (mutedOriginalVolume >= 0) return
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            // 目标恢复值：优先上次未恢复成功的保存值；否则当前值（>0 才可信）
            val restoreTarget =
                when {
                    saved > 0 -> saved
                    current > 0 -> current
                    else -> -1
                }
            if (restoreTarget > 0) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, restoreTarget).apply()
            }
            mutedOriginalVolume = restoreTarget
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            // 回读校验：ROM 的音频策略可能拒绝/立刻回弹（实测见过 volume 仍为原值），
            // 此时手机继续外放——必须让日志能直接看出来
            val after = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            log(
                "AUDIO: 已静音手机媒体输出（原音量 ${if (restoreTarget > 0) restoreTarget else "未知"}，" +
                    "断开后恢复）——回读音量=$after" +
                    if (after != 0) "（⚠ 静音未生效，手机仍会外放）" else "",
            )
        } catch (error: Exception) {
            log("AUDIO: 静音手机媒体失败 - ${error.message}")
        }
    }

    private fun restorePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val target =
                when {
                    mutedOriginalVolume > 0 -> mutedOriginalVolume
                    else -> prefs.getInt(KEY_SAVED_VOLUME, -1)
                }
            if (target > 0) {
                manager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    target,
                    0,
                )
                log("AUDIO: 已恢复手机媒体音量（$target）")
            } else {
                log("AUDIO: 无有效原音量记录，手机媒体保持当前值（请按音量键调整）")
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        } catch (_: Exception) {
        }
        mutedOriginalVolume = -1
    }

    /**
     * 启动音频发送。需要 MediaProjection（AudioPlaybackCapture 的授权来源）。
     * 返回是否成功进入采集循环。
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = DatagramSocket()
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            // AAC 通路先把编码器立起来：编不出来就没必要采集（也避免「有包无声」的假象）
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            }

            val captureConfig = buildCaptureConfig(projection)
            val audioFormat =
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val recorder =
                AudioRecord
                    .Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(framePcmBytes * 8)
                    .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AUDIO: AudioRecord 初始化失败（state=${recorder.state}）")
                runCatching { recorder.release() }
                aacEncoder?.stop()
                aacEncoder = null
                client.close()
                socket = null
                return false
            }
            record = recorder
            running.set(true)
            mutePhoneMedia()
            log(
                "AUDIO: 已启动（播放捕获 ${sampleRate}Hz 立体声 → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，" +
                    if (codec == AudioCodec.AAC_LC) {
                        "每包 1 个 AAC 帧（${AacLcEncoder.FRAME_SAMPLES} 采样" +
                            (if (adtsFrames) "，ADTS 封装））" else "，裸帧））")
                    } else {
                        "每包 ${SAMPLES_PER_PACKET} 帧 PCM）"
                    },
            )

            // 同步包线程先起：锚点越早建立，接收端越早能排播
            startSyncLoop()

            Thread(
                {
                    try {
                        recorder.startRecording()
                    } catch (error: Exception) {
                        log("AUDIO: 启动采集失败 - ${error.message}")
                        stop()
                        return@Thread
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            true
        } catch (error: Exception) {
            log("AUDIO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 停止音频。
     *
     * 收尾路径不止一条（采集线程 finally、会话结束回调、手动断开），会重复调用；
     * 必须幂等——否则「已恢复手机媒体音量」会打两遍，且在用户于会话期间手动改过
     * 音量时，第二次恢复会用旧值把它覆盖回去。
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        aacEncoder?.stop()
        aacEncoder = null
        runCatching { socket?.close() }
        socket = null
        runCatching { syncSocket?.close() }
        syncSocket = null
        syncThread = null
        thread = null
        restorePhoneMedia()
        log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
    }

    /** 捕获配置：媒体/游戏/导航等常见播放用途（逐项添加，个别用途被系统拒绝时跳过）。 */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    private fun startSyncLoop() {
        if (controlPort <= 0) {
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            return
        }
        val client =
            try {
                DatagramSocket().apply { connect(InetAddress.getByName(host), controlPort) }
            } catch (error: Exception) {
                log("AUDIO: 同步包套接字创建失败 - ${error.message}")
                return
            }
        syncSocket = client
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        val worker =
            Thread(
                {
                    var syncSequence = 0
                    var first = true
                    try {
                        while (running.get() && !client.isClosed) {
                            val next = timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                            client.send(DatagramPacket(packet, packet.size))
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                first = false
                                log(
                                    "AUDIO: 首个同步包已发出（X 位=1）ts=$current→$next——" +
                                        "车机若仍无声，说明它没在等这个锚点",
                                )
                            }
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        }
                    } catch (error: Exception) {
                        if (running.get()) log("AUDIO: 同步包发送停止 - ${error.message}")
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    // ---- 采集循环 → 封包发送 ----

    private fun runCaptureLoop() {
        val recorder = record ?: return
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        // 诊断抽样节拍：PCM 约 125 包/s（每 20s 一次）；AAC 约 47 帧/s
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600 else 1875
        try {
            while (running.get()) {
                val read =
                    try {
                        recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    } catch (error: Exception) {
                        log("AUDIO: 采集读取异常 - ${error.message}")
                        break
                    }
                if (read < 0) {
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("AUDIO: 采集错误码 $read（连续 $readFailures 次）")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (read < frameBytes) continue // 残包（缓冲尾部）：丢弃，等下一个整包
                readFailures = 0

                // 内容诊断（2026-10-17）：播放捕获可能拿到全零（app 禁止捕获 / 音频路由
                // 到蓝牙 / 无音频播放）。全零时车机静音但手机仍在响，用户会报「声音在
                // 手机上」——必须能区分「没采到」与「采到了但车机没播」。
                if (sequence < 3 || sequence % diagnosticStride == 0) {
                    var nonZero = 0
                    var peak = 0
                    var index = 0
                    while (index + 1 < read) {
                        val value = ((pcm[index + 1].toInt() and 0xFF) shl 8) or (pcm[index].toInt() and 0xFF)
                        val magnitude = if (value > 32767) 65536 - value else value
                        if (magnitude > peak) peak = magnitude
                        if (magnitude > 0) nonZero++
                        index += 2
                    }
                    val percent = nonZero * 100 / (read / 2).coerceAtLeast(1)
                    log(
                        "AUDIO: 采集样本 seq=$sequence 非零$percent% 峰值=$peak" +
                            if (peak == 0) "（⚠ 全零：捕获不到音频——app 可能禁止播放捕获或音频走了蓝牙）" else "",
                    )
                    // 全零即通知宿主做路由抢占判定（宿主侧再核「确有应用在播」，
                    // 避免把「没放歌的正常静音」误判成抢占）
                    if (peak == 0) {
                        onRouteHijackSuspect?.invoke()
                    }
                }

                // PCM 通路：采一段 = 发一包；AAC 通路：喂一帧 PCM，编码器可能吐出 0~N 帧
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        encoder.encode(pcm, frameBytes)
                    } else {
                        listOf(pcm)
                    }
                if (payloads.isEmpty()) continue

                // 一次性自检：把编码结果自己解一遍，证明比特流有效且非静音。
                // 这是「车机无声」最需要的一条证据——如果自解码峰值是 0，问题在编码器，
                // 如果自解码有声音而车机静音，问题在车机侧（会话/音量/路由）。
                if (!selfChecked) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                var sendFailed = false
                for (payload in payloads) {
                    if (!sendPayload(payload)) {
                        sendFailed = true
                        break
                    }
                }
                if (sendFailed) break
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 30_000L) {
                    lastLogAt = now
                    log(
                        "AUDIO: 推流中（累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                            "seq=$sequence ts=$timestamp）",
                    )
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            stop()
        }
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            true
        } catch (error: IOException) {
            log("AUDIO: UDP 发送失败 - ${error.message}")
            false
        }
    }

    /** 用平台解码器把首帧解回 PCM，记录峰值（后台线程，不阻塞推流）。 */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, CHANNELS)
                    if (result == null) {
                        log("AUDIO: 自检 = 本机解码器没解出样本（首帧 ${payload.size}B）——无法证伪，继续推流")
                        return@Thread
                    }
                    val (peak, samples) = result
                    log(
                        "AUDIO: 自检 = 首帧 ${payload.size}B 本机解码 $samples 个样本，峰值 $peak" +
                            if (peak > 0) {
                                "（✓ 比特流有效且非静音：车机若无声，问题不在编码/加密）"
                            } else {
                                "（⚠ 解码为全零：AAC 编码器输出的是静音数据）"
                            },
                    )
                } catch (error: Exception) {
                    log("AUDIO: 自检解码异常 - ${error.message}")
                }
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 组一个 RTP 包：明文 PCM → ChaCha20 密封 → [头][密文+tag][nonce8]。（internal：测试直测） */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD = header 的 timestamp+ssrc 段（字节 4..12）——catplay "modern AAD" / DiPlay 解密同款
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // 关键：CarPlay 线格式的 LPCM 是**大端** s16 样本——接收端对收到的负载做
        // byteSwapS16 转回小端再喂 AudioTrack（DiPlay AndroidMediaSink.kt:1687）。
        // Android 采集是原生小端，因此这里必须换序；漏掉会得到「沙沙声」（2026-10-10 实测）。
        //
        // AAC 通路不做换序：AAC 比特流是字节流，不是 16bit 样本序列，换序会直接把
        // 比特流搅烂（车机解码失败 → 有包无声）。
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
