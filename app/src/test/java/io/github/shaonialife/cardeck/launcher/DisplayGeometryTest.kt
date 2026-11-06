package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用屏/地图屏几何（密度=手机侧，字号跨屏一致）：公式与保护上限钉死。 */
class DisplayGeometryTest {
    private val phoneDensity = 560

    @Test
    fun `landscape app display uses phone density and letterbox dp height`() {
        // 1152x648 应用区（横屏画布去 dock），荣耀 560dpi：
        // 高 = 410×1.05×560/160 = 1507；宽 = 1507×1152/648 ≈ 2679
        val g = VirtualLauncher.appDisplayGeometry(1152, 648, dockAtBottom = false, phoneDensityDpi = phoneDensity)
        assertEquals(PHONE_DENSITY, g.densityDpi)
        assertEquals(1507, g.heightPx)
        val expectedW = Math.round(1507f * 1152f / 648f)
        assertEquals(expectedW, g.widthPx)
        // dp 窗口 ≈ 762×430（letterbox 高度 430.5dp）
        assertEquals(430, g.dpHeight.toInt())
    }

    @Test
    fun `portrait app display uses 410dp window width`() {
        // 1080x1920 应用区（竖屏画布）：宽 = 410×560/160 = 1435；高按纵横比
        val g = VirtualLauncher.appDisplayGeometry(1080, 1920, dockAtBottom = true, phoneDensityDpi = phoneDensity)
        assertEquals(PHONE_DENSITY, g.densityDpi)
        assertEquals(1435, g.widthPx)
        val expectedH = Math.round(1435f * 1920f / 1080f)
        assertEquals(expectedH, g.heightPx)
    }

    @Test
    fun `density is never coerced - consistency is the whole point`() {
        // 极端密度也不改（px 超限等比回缩，密度原样）
        val g = VirtualLauncher.appDisplayGeometry(1152, 648, dockAtBottom = false, phoneDensityDpi = 640)
        assertEquals(640, g.densityDpi)
    }

    @Test
    fun `px capped at max display px preserving aspect`() {
        // 超大画布 × 高密度：最长边压到 4096
        val g = VirtualLauncher.appDisplayGeometry(2400, 1600, dockAtBottom = false, phoneDensityDpi = 560)
        assertTrue(maxOf(g.widthPx, g.heightPx) <= VirtualLauncher.MAX_DISPLAY_PX)
        val aspect = g.widthPx.toFloat() / g.heightPx
        assertEquals(2400f / 1600f, aspect, 0.02f)
    }

    @Test
    fun `map display scales canvas area by phone density over legacy 200`() {
        // dp = 应用区@200dpi 不变；px = dp×560/160 = 应用区×2.8
        val g = VirtualLauncher.mapDisplayGeometry(1152, 648, phoneDensityDpi = phoneDensity)
        assertEquals(PHONE_DENSITY, g.densityDpi)
        assertEquals(Math.round(1152 * 2.8f), g.widthPx)
        assertEquals(Math.round(648 * 2.8f), g.heightPx)
        // dp 与旧密度下的窗口一致
        assertEquals(921.6f, g.dpWidth, 0.5f)
    }

    @Test
    fun `map display capped at max px`() {
        val g = VirtualLauncher.mapDisplayGeometry(2251, 1152, phoneDensityDpi = 560)
        assertTrue(maxOf(g.widthPx, g.heightPx) <= VirtualLauncher.MAX_DISPLAY_PX)
    }
}
