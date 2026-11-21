package io.github.shaonialife.cardeck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.shaonialife.cardeck.protocol.NativeBridge
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（A1/A2/A8 起字节构造全在 Rust fw::airplay::rtp；本壳 = 采集 +
 * UDP socket + 游标泵）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（catplay "modern AAD"
 *    对照域，hose 适配器固件锚集成期补）；nonce = 8B LE 计数（fw: cryptor
 *    mode 1——与 Kotlin 旧 RFC 12B 形态 16 字态逐字等价）
 *  - RTP 字段：version=2、PT=流类型（fw: getRTPPayloadType @0x2276cb930
 *    按通道名分派，main=100；102=AAC 通路真车实证）、SSRC=0（真车实证
 *    非 0 被丢）、timestamp = 累计样本帧数、seq 逐包递增
 *  - 首个音频包置 Marker 位（catplay 恒 false 对照域——「RECORD 之后才
 *    加入」形态以首包标记对齐）
 *
 * 旁路：
 *  - **同步包**：每 1 秒发往该流的 `controlPort`（Rust 组包），建立 RTP↔NTP 锚点。
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
     * 44.1k 时由 AirPlayLink 协商下发）。默认 48k。
     */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** 会话墙钟锚（NTP-1900 秒——同步包 NTP 秒域同基化，见 AirPlayLink）。 */
    private val ntpEpochAnchorSecs: Long = 0L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /**
     * 该音频流的控制端口（车机在 SETUP 应答里下发）。>0 时每 [SYNC_INTERVAL_MILLIS]
     * 发一个同步包（Rust 组包）建立 RTP↔NTP 锚点；0 = 车机没给（旧车机），只发音频。
     */
    private val controlPort: Int = 0,
    /**
     * 车机相位锚定的当前 RTP 时基查询（样本数；0 = 无锚）。fw: feedback 200
     * 体 streams[]{sampleTime,timestamp,timestampRawNs} 读回 →
     * updateZeroTimeFromPhaseValues @0x22775b46c → SetZeroTimeStamp = 打包器
     * 时基源——RTP ts 必须落在车机相位帧（锚间插值 Rust 侧完成），否则车机
     * 排播引擎对不上帧不播（2026-11-13 真车无声根因）。
     */
    private val phaseTsProvider: () -> Long = { 0L },
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

        /** 重传缓存窗口（包数）——fw: 接收端 seq 9-bit 环绕窗口
         *  @0x24552112c 对齐，超出窗口的请求必然 miss（PLC 兜底）。 */
        const val RESEND_CACHE_SIZE = 512

        /** pacer 发送队列深度上限（包）。正常稳态 ∈ [0..2]（突发缓冲用）；
         *  超限丢最旧（车机重传/PLC 兜底）——生产持续快于标称不应发生。 */
        const val SEND_QUEUE_CAP = 16

        /** pacer 队列目标水位（包）：超此深度每 tick 多排一包衰减回来
         *  （深度衰减，2026-11-21）——深队列吃车机 jitter 余量。 */
        const val QUEUE_TARGET = 2

        /** pacer 自身停顿的 resync 阈值（落后时长）：超过即跳过积压对齐
         *  重启——迟到包被接收端 R3 discardLate 丢弃，补发无意义。 */
        const val RESYNC_BEHIND_NANOS = 1_000_000_000L

        /** resync 跳包数（纯函数，JVM 可测）：落后 ≤ 阈值 → 0（走追赶补发）；
         *  超 → 跳过的包数（seq/ts/nonce 同步前进，队列内容重获新 ts）。 */
        internal fun resyncSkipPackets(
            behindNanos: Long,
            tickNanos: Long,
            thresholdNanos: Long,
        ): Long = if (behindNanos <= thresholdNanos || tickNanos <= 0) 0 else behindNanos / tickNanos

        /**
         * 0xD4 同步锚（纯函数，JVM 可测）：pacing 下 ts↔墙钟严格线性——
         * 锚 = 最近一次实际发送的 (ts, 墙钟) + 按标称采样率外推
         * （Δts 是**样本数**，每样本 = 1e9/sampleRate ns；勿乘
         * samplesPerPacket——曾双乘，单测钉住）。无发送基点（会话刚开始）
         * → null（调用方回退 captureTimeOf/now）。
         */
        internal fun paceAnchorNanosOf(
            sampleTs: Long,
            lastSentTs: Long,
            lastSentNanos: Long,
            sampleRate: Int,
        ): Long? =
            if (lastSentTs >= 0 && lastSentNanos > 0) {
                lastSentNanos + (sampleTs - lastSentTs) * 1_000_000_000L / sampleRate
            } else {
                null
            }

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** 静音前媒体音量的持久化键（进程被杀也不丢恢复目标）。 */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** 从流里读满一个整帧；EOF 返回 false（残包/断流都视为结束）。internal：单测直测。 */
        internal fun readFully(
            stream: InputStream,
            buffer: ByteArray,
        ): Boolean {
            var offset = 0
            while (offset < buffer.size) {
                val n = stream.read(buffer, offset, buffer.size - offset)
                if (n < 0) return false
                offset += n
            }
            return true
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

    // ---- 重传响应（2026-11-19 落码，挂账 #1）----
    // fw: 车机丢包 → RTCP 通道发 8B 重传请求 `80 D5|D6 00 01 [seq BE16] 00 01`
    // （AirPlayReceiver retransmitsSendRequest @0x245521698-718 组包直切，
    // 发往我方 SETUP 声明的 controlPort）；重传包走接收端同一 processPacket
    // 路径（R14.3）⇒ **缓存包原样重发**（原 seq/ts 不改，不重新封包）。
    // 缓存窗口 = 512 包（fw: 接收端 9-bit 环绕窗口 @0x24552112c 对齐）。
    // ⚠ 0.1.28.171 曾用裸 sequence 做数组索引（键域 0..65535 vs 容量
    // 512）→ sequence=512 越界，AAC ~47pps 下 11 秒必崩（音频静默死亡
    // 五轮复现）——0.1.28.172 换 ResendCache（环形 + seq 精确匹配，
    // JVM 单测 ResendCacheTest 回归钉）。
    private val resendCache = ResendCache(RESEND_CACHE_SIZE)

    private var retransmitHits = 0

    private var retransmitMisses = 0

    // ---- 发送端恒定节拍（pacer）——延迟台阶修复（2026-11-21，待办#2 兑现）----
    // 病根（真车日志逐窗口定谳）：采集/编码 stall（亚秒级，REMOTE_SUBMIX
    // 管道源/编码器/系统负载均可触发）期间无内容产出，ts 按内容计数推进
    // → ts 永久落后墙钟一个 stall 时长 → 接收端按标称速率排播 → 延迟
    // 台阶式累积（11-21 实测：5 分钟会话内一次 0.4s 内容洞，稳态窗口
    // 逐窗 48002/s 零漂移——亏速集中在孤立 stall，非稳态速率偏差）。
    // 修复 = 采集/编码只入队，pacer 按墙钟 tick（samplesPerPacket/
    // sampleRate，AAC@48k = 21.33ms）发送：ts↔墙钟严格线性；队列空
    // （stall）补一帧编码静音——stall 表现为瞬时静音而非永久延迟台阶。
    // fw 对照：AirPlaySender 的 sendOneAudioDataMessage 由音频管线时钟
    // 驱动（管线为实时域）；Android 采集管线非实时 → pacer = 固件形的
    // 平台等价实现（发送侧 jitter buffer，代价 = 固定 1-2 包队列延迟）。
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    /** pacer 最近一次实际发送的 (ts, 墙钟)——0xD4 同步锚的线性基
     *  （pacing 下锚 = lastSentNanos + (ts−lastSentTs)×tick，见
     *  [paceAnchorNanosOf]）。 */
    @Volatile private var lastSentTs = -1L

    @Volatile private var lastSentNanos = 0L

    private var pacerThread: Thread? = null

    private var silenceFilledPackets = 0

    private var queueDroppedPackets = 0

    private var resyncCount = 0

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

    /** 相位锚可用性只记录一次（诊断；**不影响 ts**——ts 零基自增）。 */
    @Volatile private var phaseAnchorChecked = false

    /** 距上次发包时刻毫秒钟（fw: sendOne 块流对象 +0x208 同构——每包更新）。 */
    private var lastPacketAt = 0L
    private var selfChecked = false

    /**
     * 已进入采集缓冲的采样点数（每声道样本帧，与 RTP ts 同单位）。
     *
     * **必须用采集计数、不能用 RTP 游标 [timestamp]**：两者之差正是管线滞后
     * （采集缓冲 + REMOTE_SUBMIX 管道 + AAC 编码），也就是本次要消除的 d。
     * RTP ts 从 0 起逐包 +spf，故 **ts 就等于采样点序号**，可与本计数直接对表。
     */
    @Volatile private var capturedSamples = 0L

    /**
     * 采集锚点：第 [syncAnchorTs] 个采样点是在 [syncAnchorNanos] 到达采集缓冲的。
     *
     * **0xD4 同步包时间轴的唯一来源（2026-11-18 音画不同步修正）**：
     * 采集循环每读满一帧就成对更新 —— read 返回意味着该帧全部样本已到齐，
     * 此刻即「最后一个已到样本」的采集时刻。
     *
     * 此前同步包锚的是 `System.nanoTime()`（**发包瞬间**），而这一包样本是
     * d 毫秒之前采集的，车机照此排播就把每个样本推迟 d；视频帧头带的是
     * MediaCodec PTS（内容时刻），两路相差 d = **音频落后**。
     */
    @Volatile private var syncAnchorTs = -1L

    @Volatile private var syncAnchorNanos = 0L

    /** 上一个采集锚（线性外推需要相邻两点，单点会随编码器批量出帧抖动）。 */
    @Volatile private var prevAnchorTs = -1L

    @Volatile private var prevAnchorNanos = 0L

    /** 单测注入口（internal）：直接摆放采集锚，供 [captureTimeOf] 时间轴钉测试。 */
    internal fun setCaptureAnchorForTest(
        prevTs: Long,
        prevNanos: Long,
        ts: Long,
        nanos: Long,
    ) {
        prevAnchorTs = prevTs
        prevAnchorNanos = prevNanos
        syncAnchorTs = ts
        syncAnchorNanos = nanos
    }

    /** 单测注入口（internal）：读回当前锚（验证采集循环是否成对推进）。 */
    internal fun captureAnchorForTest(): LongArray = longArrayOf(prevAnchorTs, prevAnchorNanos, syncAnchorTs, syncAnchorNanos)

    /**
     * 采样点序号 → 采集时刻（nanoTime）。用最近两个采集锚线性外推。
     *
     * 外推而非直取：同步包通常落在两个采集锚之间（AAC 一帧 21ms，同步包 1s 一次），
     * 用相邻两锚的实测斜率可吸收采集时钟与标称采样率的微小偏差。
     * 返回 null = 尚无锚点，调用方退化用当前时刻（此时也还没包可锚）。
     *
     * internal：单测直测（时间轴换算，音画同步的唯一定量点）。
     */
    internal fun captureTimeOf(sampleTs: Long): Long? {
        val t1 = syncAnchorTs
        val n1 = syncAnchorNanos
        if (t1 < 0 || n1 == 0L) return null
        val t0 = prevAnchorTs
        val n0 = prevAnchorNanos
        return if (t0 >= 0 && n0 > 0 && t1 > t0) {
            n1 + (sampleTs - t1) * (n1 - n0) / (t1 - t0)
        } else {
            n1 + (sampleTs - t1) * 1_000_000_000L / sampleRate
        }
    }

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /** 采集+推流统计（pacer 线程独占写）。 */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** 静音前的手机媒体音量 index（-1 = 未静音）。 */
    private var mutedOriginalVolume = -1

    /**
     * REMOTE_SUBMIX 采集流（shell UserService 经管道回传的 PCM16LE 立体声）。
     * 非 null 时走 submix 路径：**不劫持手机音量条**（submix 输入激活时系统把媒体
     * 策略整体路由走，手机本地自然静音），也不需要 MediaProjection。null = 投影
     * 播放捕获路径（回退）。
     */
    @Volatile
    private var submixStream: InputStream? = null

    /**
     * 停止回调（宿主收口用）：submix 模式下宿主借此调用 stopSubmixAudio 释放
     * UserService 侧采集；即使回调丢失，应用读端关闭也会让泵线程自愈。
     */
    var onStopped: (() -> Unit)? = null

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
     * 启动音频发送（投影播放捕获路径——**回退**）：需要 MediaProjection。
     * 首选路径见 [start]（REMOTE_SUBMIX）。返回是否成功进入采集循环。
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        stopped.set(false)
        val captureConfig = buildCaptureConfig(projection)
        val audioFormat =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
        val recorder =
            try {
                AudioRecord
                    .Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(framePcmBytes * 8)
                    .build()
            } catch (error: Exception) {
                log("AUDIO: 播放捕获初始化失败 - ${error.message}")
                return false
            }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            log("AUDIO: AudioRecord 初始化失败（state=${recorder.state}）")
            runCatching { recorder.release() }
            return false
        }
        return startCommon(recorder)
    }

    /**
     * 启动音频发送（**首选**：REMOTE_SUBMIX 流——shell UserService 经管道回传的
     * PCM16LE 立体声）。不依赖 MediaProjection，也**不劫持手机音量条**：submix
     * 输入激活时系统把媒体策略整体路由走，手机本地自然静音。
     */
    fun start(stream: InputStream): Boolean = startCommon(submixStream = stream)

    /** 共用启动：socket/AAC 编码器/同步包/采集线程。record 与 submixStream 二选一。 */
    private fun startCommon(
        record: AudioRecord? = null,
        submixStream: InputStream? = null,
    ): Boolean {
        if (running.get()) return true
        stopped.set(false)
        // 重传缓存/计数复位（新会话旧包无意义）
        resendCache.clear()
        retransmitHits = 0
        retransmitMisses = 0
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

            if (record != null) {
                this.record = record
                this.submixStream = null
                running.set(true)
                // 音频通道激活后声音只从车机出（真 CarPlay 行为）：播放捕获取自
                // REMOTE_SUBMIX（音量应用点之前），静音手机不影响捕获数据。
                // （2026-11-18 验证：禁用静音后采集仍时有时无 → 静音与捕获无关，
                //   恢复静音；采集跳变是音乐本身静音段/路由抖动，非本逻辑所致。）
                mutePhoneMedia()
            } else {
                this.submixStream = submixStream
                running.set(true)
            }
            val sourceDesc =
                if (record != null) "播放捕获 ${sampleRate}Hz 立体声" else "REMOTE_SUBMIX ${sampleRate}Hz（音量条不动）"
            log(
                "AUDIO: 已启动（$sourceDesc → $host:$dataPort，" +
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

            // pacer 线程（墙钟节拍）先于采集起：采集入队即可，无初始积压
            Thread(
                {
                    // 音频高优先级：pacer 卡顿 = 到达空窗（发送者停了没人能替）
                    runCatching {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
                        )
                    }
                    try {
                        runPacerLoop(samplesPerPacket * 1_000_000_000L / sampleRate)
                    } catch (error: Throwable) {
                        // 后台线程未捕获异常会杀死整个进程
                        log("AUDIO: 发送节拍线程异常退出 - ${error.message}")
                        stop()
                    }
                },
                "audio-pacer",
            ).apply {
                isDaemon = true
                start()
                pacerThread = this
            }

            Thread(
                {
                    if (record != null) {
                        try {
                            record.startRecording()
                        } catch (error: Exception) {
                            log("AUDIO: 启动采集失败 - ${error.message}")
                            stop()
                            return@Thread
                        }
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
        val wasSubmix = submixStream != null
        runCatching { submixStream?.close() }
        submixStream = null
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
        pacerThread = null
        sendQueue.clear()
        resendCache.clear()
        if (!wasSubmix) restorePhoneMedia()
        // 宿主收口（submix 模式释放 UserService 侧 AudioRecord，媒体路由随之恢复）；
        // 即使回调丢失，泵线程检测到读端关闭也会自愈
        runCatching { onStopped?.invoke() }
        onStopped = null
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
                    // fw 对照（2026-11-12）：fw 同步锚 = SetTimeAnnounce 走 hose 适配器
                    // 内部分发（必达）；UDP ECONNREFUSED = 平台 ICMP 伪影（真车奥迪
                    // controlPort 绑定晚于首包——2026-11-12 实录：首包被拒→停发→
                    // 无锚→车机照收音频不排播，正是 2026-10-20 记载的真车症状）。
                    // 持续每秒重试 = 平台面等价，非协议发明。
                    var refusedStreak = 0
                    while (running.get() && !client.isClosed) {
                        try {
                            // **锚 = 该 RTP ts 的采集时刻**（cfa36f2 音画同步
                            // 语义恢复——2026-11-21 真机测量定谳：车机按
                            // ts/锚排播（车机缓冲 289ms 恒定、与队列深度无关），
                            // 锚基准错多少延迟就多多少。pacer 化时误改成
                            // 「发送时刻」基准 → 我方管线深度（队列+lookahead
                            // ≈350ms）被整体带进车机排播时间表 = 体感 +1s）。
                            // pacer 下 ts↔墙钟严格线性 → 采集锚的标称外推
                            // **跨静音洞也精确**（洞时长 = ts 空档 = 墙钟空档），
                            // 当初「跨洞失真」的顾虑已被 pacing 消除。
                            // [paceAnchorNanosOf]（发送时刻）降为回退。
                            val next = timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val anchorNanos =
                                captureTimeOf(next)
                                    ?: paceAnchorNanosOf(
                                        next,
                                        lastSentTs,
                                        lastSentNanos,
                                        sampleRate,
                                    )
                                    ?: System.nanoTime()
                            val packet =
                                NativeBridge.nativeAirplayAudioSyncPacket(
                                    syncSequence,
                                    current.toInt(),
                                    next.toInt(),
                                    anchorNanos,
                                    first = if (first) 1 else 0,
                                    epochNtpSecs = ntpEpochAnchorSecs.toInt(),
                                ) ?: continue
                            client.send(DatagramPacket(packet, packet.size))
                            refusedStreak = 0
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                first = false
                                // 诊断：把锚与「现在」的差打出来 = 本次修掉的音频落后量
                                val lagMs = (System.nanoTime() - anchorNanos) / 1_000_000
                                log(
                                    "AUDIO: 首个同步包已发出（X 位=1）ts=$current→$next " +
                                        "锚=发送节拍（pacer 基，滞后 ${lagMs}ms）" +
                                        "——车机若仍无声，说明它没在等这个锚点",
                                )
                            }
                        } catch (ioe: java.io.IOException) {
                            // **ECONNREFUSED 即停发**（2026-11-18 回溯 0.1.10 有声版）：
                            // 0.1.10 的 sync 循环是 `try { while(...) {...} } catch
                            // (Exception) { if (running) log("同步包发送停止 - ...") }`
                            // —— 异常直接**跳出整个循环**、线程结束，**不重试**。
                            // 我方此前「持续重试」是发明：车机 controlPort 6011 在
                            // 首次 ECONNREFUSED 后就不再接受，持续往它发包既无用
                            // 又可能干扰车机音频通路初始化（140 实测：每 2s 一条
                            // 「同步包暂被拒」刷满日志，而 0.1.10 同场景出声）。
                            log("AUDIO: 同步包发送停止 - ${ioe.message}（0.1.10 形态：不重试）")
                            break
                        }
                        Thread.sleep(SYNC_INTERVAL_MILLIS)
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
        val recorder = record
        val stream = submixStream
        if (recorder == null && stream == null) return
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        // 诊断抽样节拍：PCM 约 125 包/s（每 20s 一次）；AAC 约 47 帧/s
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600 else 1875
        try {
            while (running.get()) {
                val read =
                    when {
                        stream != null ->
                            // 管道流：读满一帧才发（EOF = 采集端关闭，正常结束）
                            if (readFully(stream, pcm)) pcm.size else Int.MIN_VALUE
                        recorder != null ->
                            try {
                                recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                            } catch (error: Exception) {
                                log("AUDIO: 采集读取异常 - ${error.message}")
                                break
                            }
                        else -> break
                    }
                if (read == Int.MIN_VALUE) {
                    log("AUDIO: submix 流已结束（读端关闭/采集停止）")
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
                // **采集锚更新（0xD4 同步包用，见 [capturedSamples]/[syncAnchorTs]）**：
                // read 返回 = 这 read/4 个样本帧全部到齐，此刻即「最后一个已到样本」
                // 的采集时刻。用采集计数（不是 RTP 游标）——两者的差就是待消除的
                // 管线滞后。
                run {
                    capturedSamples += read / (CHANNELS * 2)
                    prevAnchorTs = syncAnchorTs
                    prevAnchorNanos = syncAnchorNanos
                    syncAnchorTs = capturedSamples
                    syncAnchorNanos = System.nanoTime()
                }

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
                // 仅 AAC 比特流有意义（PCM 裸帧喂 AAC 解码器必然失败——
                // 0.1.28.114 真车日志「没解出样本」即此噪声）。
                if (!selfChecked && codec == AudioCodec.AAC_LC) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                for (payload in payloads) {
                    // 只入队不发送——节拍由 pacer 按墙钟统一驱动（延迟台阶
                    // 修复，见 [sendQueue] 注）；超限丢最旧
                    if (sendQueue.size >= SEND_QUEUE_CAP) {
                        sendQueue.poll()
                        queueDroppedPackets++
                    }
                    sendQueue.offer(payload)
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            stop()
        }
    }

    /** pacer 补位用的一帧静音载荷：AAC = 编码全零帧（保编码器状态连续），
     *  PCM = 全零帧。编码器不可用 → null（调用方按链路故障收口）。 */

    /** 最近一次编出的静音帧缓存——编码器 lookahead 期（喂了零输入但尚未
     *  吐帧）回退复用：AAC 对全零输入的输出帧恒定，复用即合法静音。 */
    private var lastSilenceFrame: ByteArray? = null

    /**
     * pacer 补位用的一帧静音载荷。AAC = 编码全零帧（保编码器状态连续），
     * **空输出 ≠ 故障**——MediaCodec 有 1~2 帧 lookahead（AacLcEncoder.encode
     * 文档明载），lookahead 期回退复用 [lastSilenceFrame]；PCM = 全零帧。
     * 编码器不可用且无缓存 → null（调用方按未就绪处理，不收口）。
     */
    private fun silencePayload(): ByteArray? {
        if (codec != AudioCodec.AAC_LC) return ByteArray(framePcmBytes)
        val frames = aacEncoder?.encode(ByteArray(framePcmBytes), framePcmBytes)
        frames?.firstOrNull()?.let {
            lastSilenceFrame = it
            return it
        }
        return lastSilenceFrame
    }

    /**
     * 发送节拍线程（墙钟 tick 驱动）：每 tick 发一包——队列有真内容发真
     * 内容，空（采集 stall）则补静音，ts↔墙钟严格线性；自身滞后（GC/
     * 挂起）按追赶上限补发，不快进基点（快进 = 重新引入 ts 落后）。
     * 发送失败/异常 → [stop] 收口（与旧采集循环同契约）。
     */
    private fun runPacerLoop(tickNanos: Long) {
        // 时间表从**首个可发帧**起算（预热门）：MediaCodec AAC 有 1~2 帧
        // lookahead，start 后首批 encode() 返回空属正常（AacLcEncoder.encode
        // 文档明载）。旧版首帧未就绪即到点、把「空输出」当编码器不可用
        // → 开机 ~90ms 即收口 → 无声（0.1.28.181 真机实测）。
        var nextTick = -1L
        var lastLogAt = 0L
        var warmupSkips = 0
        while (running.get()) {
            if (nextTick == -1L) {
                // 预热门：等首个可发帧（队列内容或静音帧）再建立时间表
                val first = sendQueue.poll() ?: silencePayload()
                if (first == null) {
                    warmupSkips++
                    if (warmupSkips == 1 || warmupSkips % 250 == 0) {
                        log("AUDIO: pacer 预热等待编码器首帧（$warmupSkips 轮）")
                    }
                    java.util.concurrent.locks.LockSupport
                        .parkNanos(2_000_000)
                    continue
                }
                if (!sendPayload(first)) break
                nextTick = System.nanoTime() + tickNanos
                continue
            }
            val now = System.nanoTime()
            if (now < nextTick) {
                // 睡到 tick（parkNanos 精度优于 Thread.sleep 的 ms 取整；
                // 上限留出 stop 响应窗）
                java.util.concurrent.locks.LockSupport
                    .parkNanos(nextTick - now)
                continue
            }
            // 到点（或滞后）：**长卡顿 resync 前置**——落后超阈值时跳过积压
            // 对齐重启。理由：迟到包会被接收端 R3 discardLate 丢弃（迟到补发
            // ≈ 丢弃但更脏——车机还要消化迟到包、可能空转重传请求）；发送端
            // 跳过 = 车机见 seq/ts 空洞 → PLC 填充，ts↔墙钟立即重对齐，
            // 不产生永久延迟台阶。resendCache 不补洞（miss→PLC 兜底）。
            val behind = now - nextTick
            val skip = resyncSkipPackets(behind, tickNanos, RESYNC_BEHIND_NANOS)
            if (skip > 0) {
                sequence = (sequence + skip.toInt()) and 0xFFFF
                timestamp += skip * samplesPerPacket
                nonceCounter += skip
                nextTick += skip * tickNanos
                resyncCount++
                log(
                    "AUDIO: pacer 停顿 ${behind / 1_000_000}ms 超阈——resync 跳过 " +
                        "$skip 包（seq=$sequence ts=$timestamp，累计 resync=$resyncCount）",
                )
            }
            // 到点（或滞后）：真内容优先，空则补静音；追赶上限 12 包
            var fatal = false
            var burst = 0
            while (running.get() && System.nanoTime() >= nextTick && burst < 12) {
                val payload = sendQueue.poll()
                val actual =
                    if (payload != null) {
                        payload
                    } else {
                        val silence = silencePayload()
                        if (silence == null) {
                            // 编码器吐不出静音且无缓存：不收口——重试（时间表
                            // 与 ts 均不推进；编码器真损坏时采集线程会先异常
                            // 退出 → stop 兜底）。旧版在此 fatal = 开机秒死根因。
                            java.util.concurrent.locks.LockSupport
                                .parkNanos(2_000_000)
                            break
                        }
                        silenceFilledPackets++
                        silence
                    }
                if (!sendPayload(actual)) {
                    fatal = true
                    break
                }
                nextTick += tickNanos
                burst++
            }
            if (fatal || !running.get()) break
            // **深度衰减**：队列超目标水位时每 tick 多排一包真内容，把突发
            // 堆积拉回目标。pacer 恰好按标称排水 → 突发堆上的深度是随机
            // 游走、永不回落（11-21 实测恒挂 12 包）→ 到达时刻 ≈ 车机排播
            // 时刻，吃光车机 jitter 余量（实测 290ms 缓冲中 ~40ms 即此类
            // 自适应）。车机按 ts/锚排播，早到只是提前进它的 jitter buffer。
            var drained = 0
            while (running.get() && sendQueue.size > QUEUE_TARGET && drained < 2) {
                val extra = sendQueue.poll() ?: break
                if (!sendPayload(extra)) {
                    fatal = true
                    break
                }
                drained++
            }
            if (fatal || !running.get()) break
            val now2 = System.nanoTime()
            if (now2 - lastLogAt >= 30_000_000_000L) {
                lastLogAt = now2
                // 车机排播缓冲直接测量：我们已发的 ts − 车机相位（feedback
                // sampleTime 读回，R7 相位锚同源）= 在途样本数。发送管线
                // 队列恒 ≤4 包已排除，延迟若在此值 = 车机侧缓冲/锚偏移；
                // ≈0 则延迟在车机排播决策内部（不进 wire、我方不可控）。
                val carPhase = phaseTsProvider()
                val bufferTail =
                    if (carPhase > 0) {
                        "，车机相位=$carPhase " +
                            "车机缓冲≈${(timestamp - carPhase) * 1000L / sampleRate}ms"
                    } else {
                        ""
                    }
                log(
                    "AUDIO: 推流中（累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                        "seq=$sequence ts=$timestamp，队列=${sendQueue.size} " +
                        "补静音=$silenceFilledPackets 丢帧=$queueDroppedPackets" +
                        "$bufferTail）",
                )
            }
        }
    }

    /**
     * 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。
     *
     * RTP 打标触发序（fw: `carAudioStream_sendOneAudioDataMessage_block_
     * invoke` @0x2276cbff0 直切，2026-11-12 审查 P1-4 兑现）：type1 =
     * 首包（[markerPending]）；type2 = **发包间隙 >50ms**（0x32，
     * @23276-23277；流对象 +0x208 每包无条件更新 @23280 = 包间隙检测，
     * 旧注「距上次打标」系误读）——正常连续流（~21ms/帧）永不重标，
     * 卡顿恢复首包打标（接收端 jitter 适配域）。判定发生在发送本包前
     * （同固件 sendOne 时序）。
     */
    private fun sendPayload(payload: ByteArray): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        // **相位锚已废弃（2026-11-18 回溯 0.1.10 有声版）**：0.1.10 的
        // `AudioSender` 里 `timestamp` 初值 0L、每包 `+= samplesPerPacket`，
        // **完全没有锚定机制**——ts 纯自 RECORD 零基自增（真车出声实证）。
        // 我方 115 起引入「相位锚一次性起点对齐」，使首包 ts = 车机 sampleTime
        // 外推值（实测各轮起点 6432/18480/38880 等非零），与 0.1.10 形态
        // 根本不同。且接收端相位锚路径
        // `_carAudioStream_updateZeroTimeFromPhaseValues` @0x22775b46c
        // **无门控、无条件覆盖时基**（写 +0x110/+0x118/+0x148=3）——
        // 若两侧时基不同源会把整个排播时基搬走。按有声版恢复纯零基自增。
        if (!phaseAnchorChecked) {
            phaseAnchorChecked = true
            // 仅记录一次锚可用性（诊断用，不改变 ts）
            val anchored = phaseTsProvider()
            if (anchored > 0) {
                log("AUDIO: 车机相位锚可用（$anchored）但**不采用**——ts 按 0.1.10 有声形态零基自增（首包 ts=0）")
            }
        }
        // **marker 只在首包置位**（2026-11-18 回溯 0.1.10 有声版定谳）：
        // 0.1.10 的 `sendPayload` 只有 `markerPending`（初值 true，发一次后
        // false，**再也不置**），完全没有 gapBurst 逻辑。
        // 我方此前每包判断「距上一包 >50ms 即重打 marker」——采集抖动时会
        // 反复打标，接收端每见一次 marker 就当作帧不连续、重置 jitter buffer
        // → 可能永不进入稳定排播。按有声版恢复「仅首包打标」。
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        lastPacketAt = now
        // pacer 基点记账（0xD4 同步锚用）：本包 ts ↔ 实际发送完成时刻
        lastSentTs = timestamp
        // 重传缓存入窗（键 = 本包 seq；[resend] 原样重发）
        resendCache.store(sequence, wire)
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        // 前 5 包完整 hex 落盘（2026-11-18 诊断：与 0.1.10 算法本地重算结果
        // 逐字节对照用——这是唯一能排除「组包/加密隐藏差异」的手段）
        if (packetsSent < 5) {
            log(
                "AUDIO-PKT[$packetsSent]: seq=$sequence ts=$timestamp nonce=$nonceCounter " +
                    "len=${wire.size} hex=" + wire.joinToString(" ") { "%02x".format(it) },
            )
        }
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            lastSentNanos = System.nanoTime()
            true
        } catch (error: IOException) {
            log("AUDIO: UDP 发送失败 - ${error.message}")
            false
        }
    }

    /** 车机重传请求响应：缓存窗口查原包 → **原样重发**（fw: 接收端重传包
     *  走同一 processPacket 路径 R14.3——原 seq/ts 不改、不重新封包）。
     *  窗口外 miss 只记账（fw 接收端对「要不到的包」有 PLC 兜底渐弱帧，
     *  R13.3——不发占位包，那不是固件形）。 */
    fun resend(seq: Int) {
        val wire = resendCache.get(seq)
        if (wire == null) {
            retransmitMisses++
            if (retransmitMisses == 1 || retransmitMisses % 50 == 0) {
                log(
                    "AUDIO: 重传请求 seq=$seq 窗口外 miss（累计 miss=$retransmitMisses " +
                        "hit=$retransmitHits）——PLC 兜底",
                )
            }
            return
        }
        val client = socket ?: return
        retransmitHits++
        try {
            client.send(DatagramPacket(wire, wire.size))
            if (retransmitHits == 1 || retransmitHits % 20 == 0) {
                log(
                    "AUDIO: 重传响应 seq=$seq（${wire.size}B 原包重发，累计 " +
                        "hit=$retransmitHits miss=$retransmitMisses）",
                )
            }
        } catch (error: IOException) {
            log("AUDIO: 重传发送失败 seq=$seq - ${error.message}")
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

    /** 组一个 RTP 包（A1/A8 下沉：头/密封/换序全在 Rust——fw: cryptor
     *  mode 1 @0x227969f68；本壳只泵游标）。（internal：测试直测）
     *
     *  pcmSwapBe=1（2026-11-13 深挖三轮终审）：固件流描述 ASBD 对 lpcm
     *  **显式置 IsBigEndian**——repackage @0x2277d492c 对 ToASBD(0x0C)
     *  输出 ORR 0x2，该描述 = 音频管线契约 → RTP 载荷 = BE；Info+0x38
     *  HAL 描述 &= ~2 = LE（固件双 ASBD：流面 BE / 本地面 LE）。DiPlay
     *  台架收 BE 出声互证；catplay LE = 其自身选择（真车音频面未听验）。
     *  Android 采集原生小端 → 换序 BE 上 wire。 */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray =
        NativeBridge.nativeAirplayAudioSealPacket(
            streamKey,
            sequence,
            timestamp.toInt(),
            streamType.toInt(),
            marker = if (marker) 1 else 0,
            nonceCounter,
            pcm,
            // **PCM 线上 = 大端**（2026-11-18 回溯 0.1.10 有声版定谳）：
            // 用户提供的已知出声版本 3d23b3bf（0.1.10，纯 Kotlin 音频）里
            // `sealPacket` 明确 `wirePayload = bigEndianS16(pcm)`，注释记
            // 「接收端对收到的负载做 byteSwapS16 转回小端再喂 AudioTrack」。
            // 直切接收端互证：certplay `AndroidMediaSink.kt:1000`
            // `AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12,...)))`
            // ——接收端逐样本换序 = **线上必为大端**。
            // 116 那次按 catplay `fill_pcm`=NATIVE_ENDIAN 改成 LE 是错读
            // （catplay 的 ASBD 是本地面，不代表 wire）。改回 BE（= 0.1.10 形态）。
            //
            // **换序只许作用于 PCM**（0.1.10 注释记载的真车根因：对所有负载
            // 无条件 s16 换序会把 AAC 比特流搅烂 → 车机解码器判坏帧 → 包在流
            // 里、sampleTime 一路推进、喇叭全静音）。
            pcmSwapBe = if (codec == AudioCodec.PCM) 1 else 0,
        ) ?: ByteArray(0)
}
