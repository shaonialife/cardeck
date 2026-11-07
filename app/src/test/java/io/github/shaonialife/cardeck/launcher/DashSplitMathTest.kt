package io.github.shaonialife.cardeck.launcher

import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.DASH_GAP_PX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仪表盘分屏几何纯函数测试：分屏比例由把手拖动调整，几何推导（tile 尺寸/把手中心/
 * 音乐卡起始）必须四方一致（composer 绘制、地图屏 resize、音乐卡 margin、把手布局）。
 * 历史上四方各自裸写公式导致过静默错位（CARD_MARGIN_PX 契约注释），这里把
 * [VirtualLauncher.dashSplitGeom] 的推导钉死。
 */
class DashSplitMathTest {
    private val appLen = 1000

    @Test
    fun defaultFractionCentersHandle() {
        // 0.5 恰使把手中心=应用区中点（与旧版中线分屏视觉一致）
        val g = VirtualLauncher.dashSplitGeom(appLen, 0.5f)
        assertEquals(500f, g.splitCenter, 0.001f)
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.2f, VirtualLauncher.clampDashSplitFraction(0.05f), 0.001f)
        assertEquals(0.8f, VirtualLauncher.clampDashSplitFraction(0.95f), 0.001f)
        assertEquals(0.6f, VirtualLauncher.clampDashSplitFraction(0.6f), 0.001f)
        // geom 侧同样钳制（防「卡片拉到 0」回退的另一处入口）
        assertEquals(0.2f, VirtualLauncher.dashSplitGeom(appLen, 0.05f).fraction, 0.001f)
        assertEquals(0.8f, VirtualLauncher.dashSplitGeom(appLen, 0.99f).fraction, 0.001f)
    }

    @Test
    fun tileLenMatchesUsableShare() {
        val usable = appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()
        assertEquals((0.2f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.2f).tileLen)
        assertEquals((0.8f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.8f).tileLen)
    }

    @Test
    fun geometryRelationsHold() {
        // 把手中心=tile 边缘+半把手；音乐卡起始=tile 边缘+整把手（中缝=把手宽）
        for (f in listOf(0.2f, 0.35f, 0.5f, 0.66f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            assertEquals(DASH_GAP_PX / 2f, g.splitCenter - g.tileEdge, 0.001f)
            assertEquals(DASH_GAP_PX, g.otherStart - g.tileEdge, 0.001f)
        }
    }

    @Test
    fun commitRoundTripsFraction() {
        // commit 的反推路径（把手中心流像素 → 比例）与 geom 正推一致：
        // streamPos = 主轴偏移 + 边距 + tileLen + 半把手 → fraction 复原
        val offset = 126f // 假想 dock 宽
        for (f in listOf(0.2f, 0.5f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            val streamPos = offset + CARD_MARGIN_PX + g.tileLen + DASH_GAP_PX / 2f
            val derived =
                (streamPos - offset - CARD_MARGIN_PX - DASH_GAP_PX / 2f) /
                    (appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt())
            assertEquals(f, derived, 0.001f)
        }
    }

    /** 固定应用卡片矩形（应用区坐标系）：卡片区 = 对侧区域，从 otherStart 到末端留边距。 */
    private val crossLen = 600

    @Test
    fun pinnedCardRectLandscapeFollowsOtherStart() {
        // 横屏：主轴=横向（地图左、卡片右）；卡片 x 从 otherStart 到右端留边距
        val rect = VirtualLauncher.pinnedCardRect(appLen, crossLen, 0.5f, dockAtBottom = false)
        val geom = VirtualLauncher.dashSplitGeom(appLen, 0.5f)
        assertEquals(geom.otherStart.toInt(), rect.left)
        assertEquals(CARD_MARGIN_PX, rect.top)
        assertEquals(appLen - CARD_MARGIN_PX, rect.right)
        assertEquals(crossLen - CARD_MARGIN_PX, rect.bottom)
        // 卡片宽随分屏比例变化（拖到 0.8 → 地图变宽 → 卡片变窄）
        val wide = VirtualLauncher.pinnedCardRect(appLen, crossLen, 0.8f, dockAtBottom = false)
        val narrow = VirtualLauncher.pinnedCardRect(appLen, crossLen, 0.2f, dockAtBottom = false)
        assert(wide.width < narrow.width)
    }

    @Test
    fun pinnedCardRectPortraitStacksBelowTile() {
        // 竖屏：主轴=纵向（地图上、卡片下）；卡片 y 从 otherStart 到 dock 上方留边距
        val rect = VirtualLauncher.pinnedCardRect(appLen, crossLen, 0.5f, dockAtBottom = true)
        val geom = VirtualLauncher.dashSplitGeom(appLen, 0.5f)
        assertEquals(CARD_MARGIN_PX, rect.left)
        assertEquals(geom.otherStart.toInt(), rect.top)
        assertEquals(crossLen - CARD_MARGIN_PX, rect.right)
        assertEquals(appLen - CARD_MARGIN_PX, rect.bottom)
    }

    @Test
    fun pinnedCardRectRespectsClampDomain() {
        // 边界比例（0.2/0.8）不产生零尺寸/负尺寸卡片
        for (f in listOf(0.0f, 0.2f, 0.8f, 1.0f)) {
            val rect = VirtualLauncher.pinnedCardRect(appLen, crossLen, f, dockAtBottom = false)
            assert(rect.width > 0) { "width<=0 at $f" }
            assert(rect.height > 0) { "height<=0 at $f" }
        }
    }
}
