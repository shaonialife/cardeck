package io.github.shaonialife.cardeck.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VirtualDeviceManager 路线的判定逻辑（flags 候选 / gate / role 输出解析）。 */
class VirtualDeviceRouteTest {
    @Test
    fun `trusted candidates carry reference flags plus public fallback`() {
        val candidates = VirtualDeviceRoute.trustedFlagCandidates
        assertEquals(2, candidates.size)
        // 主值 1098 = PRESENTATION|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|TRUSTED
        // （原参考值 1354 含 DESTROY_CONTENT_ON_REMOVAL=1<<8，2026-11-19 移除——
        // 屏移除时任务要 reparent 回主屏保应用存活，不能销毁内容）
        assertEquals(1098, candidates[1])
        // 首选多带 PUBLIC（私有屏拒绝第三方 IME 窗口——2026-10-17 定谳的键盘坑）
        assertEquals(1098 or VirtualDeviceRoute.FLAG_PUBLIC, candidates[0])
        // 1098 各位都不含 256（DESTROY 位）——防回归
        assertEquals(0, candidates[1] and 256)
        assertEquals(0, candidates[0] and 256)
    }

    @Test
    fun `dock candidate joins device group via trusted first`() {
        // 首选带 TRUSTED 进虚拟设备 display group：本 ROM 上非设备组 VDM 屏锁屏后
        // 合成渲染冻结（2026-11-06 定谳）， dock 必须与受信屏同待遇
        val primary = VirtualDeviceRoute.dockFlagCandidates.first()
        assertTrue(primary and VirtualDeviceRoute.FLAG_TRUSTED != 0)
        assertEquals(0, primary and VirtualDeviceRoute.FLAG_PUBLIC)
        assertTrue(primary and VirtualDeviceRoute.FLAG_SUPPORTS_TOUCH != 0)
        assertTrue(primary and VirtualDeviceRoute.FLAG_OWN_CONTENT_ONLY != 0)
        // 降级候选：ROM 拒绝受信 dock 时保留非受信组合（显式锁屏豁免 + 独立组）
        val fallback = VirtualDeviceRoute.dockFlagCandidates.last()
        assertEquals(0, fallback and VirtualDeviceRoute.FLAG_TRUSTED)
        assertTrue(fallback and VirtualDeviceRoute.FLAG_ALWAYS_UNLOCKED != 0)
        assertTrue(fallback and VirtualDeviceRoute.FLAG_OWN_DISPLAY_GROUP != 0)
    }

    @Test
    fun `gate requires sdk mac and enabled session`() {
        assertTrue(VirtualDeviceRoute.shouldAttempt(34, "AA:BB:CC:DD:EE:FF", disabledThisSession = false))
        assertTrue(VirtualDeviceRoute.shouldAttempt(37, "aa:bb", disabledThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(33, "AA:BB:CC:DD:EE:FF", disabledThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(34, null, disabledThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(34, "  ", disabledThisSession = false))
        assertFalse(VirtualDeviceRoute.shouldAttempt(34, "AA:BB:CC:DD:EE:FF", disabledThisSession = true))
    }

    @Test
    fun `role holder parsing matches token exactly`() {
        // `cmd role get-role-holders <role>` 的真实输出 = 该角色的持有者列表（每行一个）
        val output = "com.android.shell"
        assertTrue(VirtualDeviceRoute.roleHoldersContain(output, "com.android.shell"))
        // 同前缀包名不得误判（contains(pkg) 会挂在这里）
        assertFalse(VirtualDeviceRoute.roleHoldersContain("com.android.shellx", "com.android.shell"))
        assertFalse(VirtualDeviceRoute.roleHoldersContain("", "com.android.shell"))
        assertFalse(VirtualDeviceRoute.roleHoldersContain("  \n ", "com.android.shell"))
    }
}
