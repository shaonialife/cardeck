package io.github.shaonialife.cardeck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保留期计数纯函数回归（意外断开保留虚拟屏，2026-11-10）：每 60s 一个重试窗口，
 * 连续 N 窗未恢复即销毁保留虚拟屏；自动重连成功在计数中任意时刻清零退出（由
 * SessionController.exitDisplayRetention 负责，纯函数只管「该不该销毁」的判定）。
 */
class RetentionCounterTest {
    /** 达到/超过阈值：销毁（默认 3 窗 = 约 3 分钟）。 */
    @Test
    fun destroysAtAndBeyondThreshold() {
        assertTrue(SessionController.retentionShouldDestroy(failures = 3, maxRetries = 3))
        assertTrue(SessionController.retentionShouldDestroy(failures = 4, maxRetries = 3))
        assertTrue(SessionController.retentionShouldDestroy(failures = 10, maxRetries = 10))
    }

    /** 未达阈值：继续保留等待重连。 */
    @Test
    fun survivesBelowThreshold() {
        assertFalse(SessionController.retentionShouldDestroy(failures = 1, maxRetries = 3))
        assertFalse(SessionController.retentionShouldDestroy(failures = 2, maxRetries = 3))
        assertFalse(SessionController.retentionShouldDestroy(failures = 9, maxRetries = 10))
    }

    /** 防呆：maxRetries 被钳到下限 1（首窗未恢复即销毁，不抛异常不悬挂）。 */
    @Test
    fun maxRetriesFlooredAtOne() {
        assertTrue(SessionController.retentionShouldDestroy(failures = 1, maxRetries = 0))
        assertTrue(SessionController.retentionShouldDestroy(failures = 1, maxRetries = -5))
    }
}
