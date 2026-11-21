package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** pacer 长卡顿 resync 判定钉（2026-11-21）：落后 ≤ 阈值走追赶补发（0），
 *  超阈值跳过积压对齐重启（late ≈ 丢弃，R3 discardLate）。 */
class AudioPacerResyncTest {
    private val tick = 21_333_333L // AAC@48k
    private val threshold = AudioSender.RESYNC_BEHIND_NANOS // 1s

    @Test
    fun `追赶区间内不跳包`() {
        assertEquals(0L, AudioSender.resyncSkipPackets(0, tick, threshold))
        assertEquals(0L, AudioSender.resyncSkipPackets(threshold, tick, threshold))
        assertEquals(0L, AudioSender.resyncSkipPackets(500_000_000L, tick, threshold))
    }

    @Test
    fun `超阈跳包数 = 落后时长折整包`() {
        // 落后 2s → 2s/tick ≈ 93.75 → 93 包
        assertEquals(93L, AudioSender.resyncSkipPackets(2_000_000_000L, tick, threshold))
        // 落后 1s+1ms → 46.9 → 46 包
        assertEquals(46L, AudioSender.resyncSkipPackets(1_001_000_000L, tick, threshold))
    }

    @Test
    fun `tick 非法防御`() {
        assertEquals(0L, AudioSender.resyncSkipPackets(2_000_000_000L, 0, threshold))
    }
}
