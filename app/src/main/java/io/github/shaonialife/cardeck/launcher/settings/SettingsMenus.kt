package io.github.shaonialife.cardeck.launcher.settings

import androidx.fragment.app.Fragment
import io.github.shaonialife.cardeck.R

/** 车机设置菜单注册表：车机双栏与手机二级菜单共用（菜单项/标题/Fragment 工厂）。 */
object SettingsMenus {
    const val DESKTOP = 0
    const val SUPERSAMPLE = 1
    const val ORIENTATION = 2
    const val DOCK = 3
    const val DEFAULT_MAP = 4
    const val DISPLAY = 5
    const val MISC = 6

    data class Item(
        val id: Int,
        val title: String,
        val desc: String,
        val iconRes: Int,
    )

    val ALL =
        listOf(
            Item(DESKTOP, "管理桌面应用", "勾选的应用显示在车机桌面", R.drawable.ic_menu_grid),
            Item(SUPERSAMPLE, "应用超采样", "竖屏应用渲染更精细，可三态调节", R.drawable.ic_menu_zoom),
            Item(ORIENTATION, "应用横屏", "竖屏应用在车机横屏全屏显示", R.drawable.ic_screen_rotation),
            Item(DOCK, "dock 栏设置", "壁纸暗幕透明度与模糊程度", R.drawable.ic_menu_dock),
            Item(DEFAULT_MAP, "默认地图", "仪表盘地图屏使用的地图应用", R.drawable.ic_menu_map),
            Item(DISPLAY, "显示", "流分辨率、帧率与触摸节流", R.drawable.ic_menu_display),
            // 杂项：不属于任何主题域的开关（曾挤在「显示」里，2026-10-26 拆出）
            Item(MISC, "杂项设置", "上车自动连接与调试日志开关", R.drawable.ic_app_settings),
        )

    fun titleOf(id: Int): String = ALL.first { it.id == id }.title

    fun createFragment(id: Int): Fragment =
        when (id) {
            DESKTOP -> DesktopPanelFragment()
            SUPERSAMPLE -> SupersamplePanelFragment()
            ORIENTATION -> OrientationPanelFragment()
            DOCK -> DockPanelFragment()
            DISPLAY -> DisplayPanelFragment()
            MISC -> MiscPanelFragment()
            else -> DefaultMapPanelFragment()
        }
}
