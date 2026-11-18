package io.github.shaonialife.cardeck.launcher.settings

import androidx.fragment.app.Fragment
import io.github.shaonialife.cardeck.R

/** 车机设置菜单注册表：车机双栏与手机二级菜单共用（菜单项/标题/Fragment 工厂）。 */
object SettingsMenus {
    const val DESKTOP = 0
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

    // 应用横屏菜单已删（2026-11-18 并入「管理桌面应用」：同一应用列表双勾选框），
    // 其旧 id=2 不再复用，id 与历史值保持不连续也无妨（视图→id 是显式映射）。
    val ALL =
        listOf(
            Item(DESKTOP, "管理桌面应用", "勾选显示到车机桌面，并可单独开启应用横屏", R.drawable.ic_menu_grid),
            Item(DOCK, "dock 栏设置", "壁纸暗幕透明度与模糊程度", R.drawable.ic_menu_dock),
            Item(DEFAULT_MAP, "仪表盘", "仪表盘地图与右侧卡片", R.drawable.ic_menu_map),
            Item(DISPLAY, "显示", "流分辨率、帧率与虚拟屏密度", R.drawable.ic_menu_display),
            // 杂项：不属于任何主题域的开关（曾挤在「显示」里，2026-10-26 拆出）
            Item(MISC, "杂项设置", "自动连接、触摸节流与调试日志开关", R.drawable.ic_app_settings),
        )

    fun titleOf(id: Int): String = ALL.first { it.id == id }.title

    fun createFragment(id: Int): Fragment =
        when (id) {
            DESKTOP -> DesktopPanelFragment()
            DOCK -> DockPanelFragment()
            DISPLAY -> DisplayPanelFragment()
            MISC -> MiscPanelFragment()
            else -> DashboardPanelFragment()
        }
}
