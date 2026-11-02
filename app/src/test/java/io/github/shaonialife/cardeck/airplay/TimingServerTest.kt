package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 计时服务：NTP 编码往返（AudioSender RTP 时间戳垫片）。
 * 协议字节域（请求校验/应答构造/偏移公式）已移交 Rust 固件逆向实现
 * （`fw::airplay::ntp` / `fw::airplay::timing`，测试随 Rust 侧）。
 */
class TimingServerTest {
    @Test
    fun ntpEncodingRoundTrips() {
        val values = listOf(0L, 123_456_789L, 999_999_999L, 1_000_000_000L, 12_345_678_901_234L)
        for (nanos in values) {
            val packed = ByteBuffer.wrap(TimingServer.ntpFromNanos(nanos)).long
            val secs = packed shr 32
            val fracNanos = ((packed and 0xFFFF_FFFFL) * 1_000_000_000L) shr 32
            val back = secs * 1_000_000_000L + fracNanos
            // 分数位定点化有截断：允许 ±1ns（fw: 分数 = 余<<32/tps 截断语义）
            assertTrue("nanos=$nanos back=$back", Math.abs(back - nanos) <= 1)
        }
    }
}
