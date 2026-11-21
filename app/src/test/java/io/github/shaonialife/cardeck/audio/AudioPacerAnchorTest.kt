package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0xD4 同步锚（pacer 基）纯函数钉（2026-11-21 延迟台阶修复配套）。
 *  pacing 下 ts↔墙钟严格线性：锚 = lastSentNanos + Δts×(1e9/rate)。
 *  ⚠ 曾把 samplesPerPacket 双乘进公式（Δts 已是样本数），单测抓出。 */
class AudioPacerAnchorTest {
    private val spb = 1024L // AAC@48k
    private val rate = 48_000

    @Test
    fun `前向外推精确对齐标称 tick`() {
        // ts=1024 在 1_000_000_000 发出 → ts=2048 应在 +1024 样本（21_333_333ns）后
        val anchor =
            AudioSender.paceAnchorNanosOf(2048, 1024, 1_000_000_000L, rate)!!
        assertEquals(1_021_333_333L, anchor)
    }

    @Test
    fun `后向外推（current = next - spb）`() {
        val anchor =
            AudioSender.paceAnchorNanosOf(1024, 2048, 2_000_000_000L, rate)!!
        assertEquals(1_978_666_667L, anchor)
    }

    @Test
    fun `同 ts 锚 = 基点本身`() {
        assertEquals(
            1_500_000_000L,
            AudioSender.paceAnchorNanosOf(4096, 4096, 1_500_000_000L, rate)!!,
        )
    }

    @Test
    fun `无发送基点返回 null（回退 captureTimeOf）`() {
        assertNull(AudioSender.paceAnchorNanosOf(2048, -1, 0L, rate))
        assertNull(AudioSender.paceAnchorNanosOf(2048, 1024, 0L, rate))
    }

    @Test
    fun `PCM 通路 tick（352@48k = 7_333_333ns）`() {
        val anchor =
            AudioSender.paceAnchorNanosOf(704, 352, 1_000_000_000L, rate)!!
        assertEquals(1_007_333_333L, anchor)
    }
}
