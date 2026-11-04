package io.github.shaonialife.cardeck.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import io.github.shaonialife.cardeck.protocol.NativeBridge

/**
 * PCM → AAC-LC 编码器（type=102 MainHighAudio 通路用）。
 *
 * 为什么需要它（2026-10-20 Honda HFT / Alpine 实测）：该车机 `/info` 里
 * `type=102`（MainHighAudio）的 `media` 档只声明 `0x400000` = **AAC-LC 44.1k 立体声**，
 * 没有任何 PCM 位；而 `type=100 media` 只声明 PCM 44.1k，往那里推 PCM 时车机
 * 收包却不消费（`/feedback` 里 `sampleTime` 恒 0），约 6.5s 后拆会话。
 * 真 iPhone 的媒体音频走的就是 102 + AAC-LC，所以必须自己编 AAC。
 *
 * 线格式（对照 catplay `aac_encoder.rs` + `rtp_recorder.rs`）：编码器输出的是
 * **裸 AAC 帧** —— 没有 ADTS 头、没有 RFC3640 的 AU header，RTP 负载就是该帧本身，
 * 一包一帧。Android `MediaCodec` 的 AAC 编码器同样输出裸帧，可直接使用。
 *
 * 帧长固定 1024 采样（AAC-LC 规格），因此 RTP 时间戳每包步进 1024。
 */
class AacLcEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int = 192_000,
    /** 是否给每帧补 7 字节 ADTS 头（见 [AudioTuning.ADTS]）。 */
    private val wrapAdts: Boolean = false,
    private val log: (String) -> Unit,
) {
    companion object {
        /** AAC-LC 一帧固定 1024 个采样（每声道）。 */
        const val FRAME_SAMPLES = 1024

        /** 一帧 PCM 的字节数（16bit）。 */
        fun pcmBytes(channels: Int): Int = FRAME_SAMPLES * channels * 2

        private const val MIME = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        /**
         * 自检：用平台解码器把一帧裸 AAC 解回 PCM，返回 `(峰值, 样本数)`；解不出返回 null。
         *
         * 为什么需要（2026-10-20 第三次实测）：车机「收包但不发声」时，最容易搞错的是
         * 归因——是编码器吐了坏流/静音，还是车机侧没播。本机解码一遍就能证伪：
         * 峰值 > 0 说明比特流有效且非静音，问题必在车机侧；峰值 = 0 说明编码器在出静音。
         *
         * 注意：这里**绝不能**对帧做任何字节换序（旧实现把所有负载统一按大端 s16 换序，
         * 连 AAC 比特流也换掉了——车机解码器直接判坏帧，这就是「有包无声」的根因）。
         */
        fun decodePeak(
            frame: ByteArray,
            sampleRate: Int,
            channels: Int,
        ): Pair<Int, Int>? {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
                val codec = MediaCodec.createDecoderByType(MIME)
                created = codec
                codec.configure(format, null, null, 0)
                codec.start()
                val inputIndex = codec.dequeueInputBuffer(200_000L)
                if (inputIndex < 0) return null
                val input = codec.getInputBuffer(inputIndex) ?: return null
                input.clear()
                input.put(frame)
                codec.queueInputBuffer(inputIndex, 0, frame.size, 0L, 0)
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 600_000_000L
                while (System.nanoTime() < deadline) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 60_000L)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                    ) {
                        continue
                    }
                    if (outputIndex < 0) continue
                    val output = codec.getOutputBuffer(outputIndex)
                    var peak = 0
                    var samples = 0
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        while (output.remaining() >= 2) {
                            val value = output.short.toInt()
                            val magnitude = if (value < 0) -value else value
                            if (magnitude > peak) peak = magnitude
                            samples++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (samples > 0) return peak to samples
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { created?.stop() }
                runCatching { created?.release() }
            }
        }
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    /** ADTS 剥离只提示一次，避免刷屏。 */
    private var adtsStripped = false

    /** ADTS 封装提示只打一次。 */
    private var adtsWrapped = false

    /** 首帧十六进制只打一次。 */
    private var firstFrameLogged = false

    /** 每帧 PCM 输入字节数。 */
    val inputFrameBytes: Int = pcmBytes(channels)

    var started: Boolean = false
        private set

    fun start(): Boolean {
        if (started) return true
        return try {
            val format =
                MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputFrameBytes)
                }
            val created = MediaCodec.createEncoderByType(MIME)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            presentationUs = 0L
            started = true
            log(
                "AUDIO: AAC-LC 编码器就绪（${sampleRate}Hz ${channels}ch " +
                    "${bitRate / 1000}kbps，帧=${FRAME_SAMPLES}采样/${inputFrameBytes}B）",
            )
            true
        } catch (error: Exception) {
            log("AUDIO: AAC-LC 编码器启动失败 - ${error.message}（本档位放弃）")
            started = false
            false
        }
    }

    /**
     * 喂一帧 PCM（[inputFrameBytes] 字节），返回本次编出的全部 AAC 帧。
     *
     * 编码器内部有延迟（首帧通常要喂 1~2 帧才吐第一包），所以返回值可能为空，
     * 调用方按「拿到的帧逐个发包」处理即可。
     */
    fun encode(
        pcm: ByteArray,
        size: Int,
    ): List<ByteArray> {
        val encoder = codec ?: return emptyList()
        val output = mutableListOf<ByteArray>()
        try {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = encoder.getInputBuffer(inputIndex)
                if (buffer == null) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationUs, 0)
                } else {
                    buffer.clear()
                    buffer.put(pcm, 0, size.coerceAtMost(buffer.capacity()))
                    encoder.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                    presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
                }
            }
            drain(encoder, output)
        } catch (error: Exception) {
            log("AUDIO: AAC 编码异常 - ${error.message}")
        }
        return output
    }

    private fun drain(
        encoder: MediaCodec,
        output: MutableList<ByteArray>,
    ) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> return
                else -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig) {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(data)
                        output += finalizeFrame(data)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /**
     * 去掉 ADTS 头（若存在）。
     *
     * 线上格式要求是**裸 AAC 帧**（catplay `rtp_recorder` 直接把编码器输出当负载）。
     * 多数 MediaCodec 实现输出裸帧，但个别硬件编码器会带 7/9 字节 ADTS 头——带上就是
     * 非法负载，车机解码器会整条流丢弃。同步字识别/剥头在 Rust
     * （fw::media_format::strip_adts，ISO 14496-3 域），这里只留宿主日志。
     */
    private fun stripAdts(frame: ByteArray): ByteArray {
        val stripped = NativeBridge.nativeMediaStripAdts(frame) ?: return frame
        if (!adtsStripped) {
            adtsStripped = true
            log("AUDIO: 编码器输出带 ADTS 头（${frame.size - stripped.size}B）——已剥离，改发裸 AAC 帧")
        }
        return stripped
    }

    /**
     * 按当前方案决定线上负载形态：裸帧（默认）或「ADTS 头 + 裸帧」。
     *
     * 先统一剥掉编码器可能自带的 ADTS（避免重复套头），再按方案决定是否重建。
     */
    private fun finalizeFrame(frame: ByteArray): ByteArray {
        val raw = stripAdts(frame)
        if (!adtsWrapped && wrapAdts) {
            adtsWrapped = true
            log("AUDIO: 已改为 ADTS 封装（每帧补 7 字节头，方案2）")
        }
        val out = if (wrapAdts) buildAdts(raw) else raw
        // 首帧留档：出问题时能直接从日志里核对负载是不是合法 AAC（而不是等用户导文件）
        if (!firstFrameLogged) {
            firstFrameLogged = true
            val head = out.take(16).joinToString(" ") { "%02X".format(it) }
            log(
                "AUDIO: 首帧 ${out.size}B（裸帧 ${raw.size}B）head=$head" +
                    (if (wrapAdts) "（ADTS，sync=FFF）" else ""),
            )
        }
        return out
    }

    /** 组 7 字节 ADTS 头（无 CRC）——fw::media_format::adts_header（ISO 域，
     *  车机解码链兼容开关路径，见 [AudioTuning.ADTS]）。 */
    private fun buildAdts(raw: ByteArray): ByteArray =
        (NativeBridge.nativeMediaAdtsHeader(raw.size, sampleRate, channels) ?: ByteArray(0)) + raw

    fun stop() {
        val encoder = codec ?: return
        codec = null
        started = false
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
    }
}
