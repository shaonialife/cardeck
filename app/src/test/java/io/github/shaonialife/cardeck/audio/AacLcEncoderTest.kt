package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频方案单测（宿主侧只剩平台项）。
 *
 * ADTS 头拼装/采样率索引/ADTS 剥离已下沉 Rust `fw::media_format`
 * （ISO 规范域，字节钉在 Rust 测试；2026-11-04 跨平台下沉刀）。
 * 此前本文件的白盒向量（Honda HFT / Alpine 8A250-30A 兼容排查期固定）已
 * 随下沉迁入 `src/fw/media_format.rs` 测试。
 */
class AacLcEncoderTest {
    @Test
    fun tuningIsFixedToRawFrames() {
        // 定案：裸 AAC 帧 + spf=1024 + 到达时间戳，只有这一组，不再提供切换
        assertEquals(1, AudioTuning.entries.size)
        assertTrue(!AudioTuning.RAW_SPF.adts)
        assertEquals(1024, AudioTuning.RAW_SPF.spf)
        assertTrue(!AudioTuning.RAW_SPF.highAccuracyTimestamps)
    }
}
