package io.github.shaonialife.cardeck.launcher

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.settings.DashboardPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DesktopPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DisplayPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DockPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.MiscPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.OrientationPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.SettingsMenus
import io.github.shaonialife.cardeck.launcher.settings.SettingsToastHost

/**
 * 设置宿主（**单 Activity 响应式**，2026-11-07 统一）：
 *
 *  - 宽屏（车机横屏应用屏，宽 ≥600dp 且高 ≥400dp）：双栏 [R.layout.activity_settings]
 *    ——左菜单列 + 右内容 Fragment。
 *  - 窄屏（手机竖/横屏、竖屏车机）：[R.layout.activity_settings_narrow]——列表态 ⇄
 *    详情态两层叠放（原 PhoneSettingsActivity + SettingsContentActivity 两级页合一）。
 *
 * 为什么要统一：此前车机/手机是两个 Activity，菜单 → 面板的映射是唯一的分叉点，
 * 超采样菜单删除后车机侧按「位置」取面板曾整体错位一档（2026-11-07 用户实测）。
 * 现在两个形态共用 [SettingsMenus] 注册表与同一套面板 Fragment，映射零拷贝。
 *
 * 形态判定用**宿主屏的 configuration**（Activity 落在哪个屏，resources 就解析哪个
 * 屏的配置）：车机应用屏 ≈767×430dp → wide；手机 ≈385×830dp → narrow；手机横屏
 * 高约 364dp < 400 → narrow（宽高双条件，防低密度平板手机误判）。
 */
class SettingsActivity :
    AppCompatActivity(),
    SettingsToastHost {
    private var toastView: TextView? = null
    private var isWide = false

    /** 当前菜单（[SettingsMenus] 菜单 id）：wide=右栏选中项；narrow=-1=列表态、≥0=详情态。 */
    private var currentMenu = -1

    /** wide 双栏的菜单条目（视图 + 菜单 id）。 */
    private data class MenuEntry(
        val view: LinearLayout,
        val menuId: Int,
    )

    private val menus = mutableListOf<MenuEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = resources.configuration
        isWide = cfg.screenWidthDp >= WIDE_MIN_WIDTH_DP && cfg.screenHeightDp >= WIDE_MIN_HEIGHT_DP
        if (savedInstanceState != null) {
            isWide = savedInstanceState.getBoolean(KEY_WIDE, isWide)
        }
        if (isWide) {
            setupWide()
            if (savedInstanceState == null) showMenu(SettingsMenus.DESKTOP) else restoreCurrentMenu()
        } else {
            setupNarrow(savedInstanceState)
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋转重建保持形态与详情态（面板无状态，重建即恢复）
        outState.putBoolean(KEY_WIDE, isWide)
        if (!isWide) outState.putInt(KEY_MENU, currentMenu)
    }

    // ---- wide：车机横屏双栏 ----

    private fun setupWide() {
        setContentView(R.layout.activity_settings)
        toastView = findViewById(R.id.settingsToast)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { close() }
        collectMenus()
    }

    /**
     * 从 XML 菜单条目收集，**视图 → 菜单 id 显式映射**。
     *
     * ⚠️ 此前按「收集顺序 index」直接喂 [SettingsMenus.createFragment]——超采样菜单
     * 删除后 [SettingsMenus.ALL] 的 id 不再连续（0,2,3,4,5,6），位置 1~5 全部错位
     * （仪表盘显示 dock 内容、显示显示仪表盘内容……0.1.28.62 引入，2026-11-07 用户
     * 实测定谳）。
     */
    private fun collectMenus() {
        val defs =
            listOf(
                R.id.menuDesktopApps to SettingsMenus.DESKTOP,
                R.id.menuOrientation to SettingsMenus.ORIENTATION,
                R.id.menuDock to SettingsMenus.DOCK,
                R.id.menuDefaultMap to SettingsMenus.DEFAULT_MAP,
                R.id.menuDisplay to SettingsMenus.DISPLAY,
                R.id.menuMisc to SettingsMenus.MISC,
            )
        defs.forEach { (viewId, menuId) ->
            menus.add(MenuEntry(findViewById(viewId), menuId))
        }
        menus.forEach { entry ->
            entry.view.setOnClickListener { showMenu(entry.menuId) }
        }
    }

    /** 菜单切换（参数 = [SettingsMenus] 菜单 id）：选中态样式 + 替换右栏 Fragment。 */
    private fun showMenu(menuId: Int) {
        if (menuId == currentMenu) {
            applyMenuStyle(menuId)
            return
        }
        currentMenu = menuId
        applyMenuStyle(menuId)
        replaceContainer(SettingsMenus.createFragment(menuId))
    }

    private fun restoreCurrentMenu() {
        currentMenu = -1
        val restored = supportFragmentManager.findFragmentById(R.id.settingsContainer)
        val menuId =
            when (restored) {
                is DesktopPanelFragment -> SettingsMenus.DESKTOP
                is OrientationPanelFragment -> SettingsMenus.ORIENTATION
                is DockPanelFragment -> SettingsMenus.DOCK
                is DashboardPanelFragment -> SettingsMenus.DEFAULT_MAP
                is DisplayPanelFragment -> SettingsMenus.DISPLAY
                is MiscPanelFragment -> SettingsMenus.MISC
                else -> SettingsMenus.DESKTOP
            }
        showMenu(menuId)
    }

    private fun applyMenuStyle(menuId: Int) {
        val ripple =
            runCatching {
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                getDrawable(out.resourceId)
            }.getOrNull()
        menus.forEach { entry ->
            val selected = entry.menuId == menuId
            entry.view.background = if (selected) getDrawable(R.drawable.bg_menu_item) else ripple
            for (c in 0 until entry.view.childCount) {
                val child = entry.view.getChildAt(c)
                if (child is TextView) {
                    child.setTextColor(if (selected) 0xFFF5F5F7.toInt() else 0xFF8E8E93.toInt())
                    child.paint.isFakeBoldText = selected
                }
            }
        }
    }

    // ---- narrow：列表 ⇄ 详情（手机与竖屏车机） ----

    private fun setupNarrow(state: Bundle?) {
        setContentView(R.layout.activity_settings_narrow)
        toastView = findViewById(R.id.settingsToast)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { close() }
        findViewById<ImageView>(R.id.btnBackDetail).setOnClickListener { backToList() }
        val list = findViewById<RecyclerView>(R.id.menuList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = MenuAdapter()
        state?.getInt(KEY_MENU, -1)?.takeIf { it >= 0 }?.let { menuId -> showDetail(menuId) }
    }

    /** 详情态：列表隐藏、头部标题换为菜单名、容器装面板 Fragment。 */
    private fun showDetail(menuId: Int) {
        currentMenu = menuId
        findViewById<TextView>(R.id.contentTitle).text = SettingsMenus.titleOf(menuId)
        findViewById<View>(R.id.narrowList).visibility = View.GONE
        findViewById<View>(R.id.narrowDetail).visibility = View.VISIBLE
        replaceContainer(SettingsMenus.createFragment(menuId))
    }

    /** 回列表态：移除面板 Fragment（面板无状态，重进重建）。 */
    private fun backToList() {
        currentMenu = -1
        findViewById<View>(R.id.narrowDetail).visibility = View.GONE
        findViewById<View>(R.id.narrowList).visibility = View.VISIBLE
        supportFragmentManager.findFragmentById(R.id.settingsContainer)?.let { fragment ->
            supportFragmentManager.beginTransaction().remove(fragment).commit()
        }
    }

    private fun replaceContainer(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settingsContainer, fragment)
            .commit()
    }

    /** 列表态条目（原手机侧 MenuAdapter 原样搬移；行布局复用 item_phone_settings_menu）。 */
    private inner class MenuAdapter : RecyclerView.Adapter<MenuHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MenuHolder {
            val view = layoutInflater.inflate(R.layout.item_phone_settings_menu, parent, false)
            return MenuHolder(view)
        }

        override fun getItemCount() = SettingsMenus.ALL.size

        override fun onBindViewHolder(
            holder: MenuHolder,
            position: Int,
        ) {
            val item = SettingsMenus.ALL[position]
            holder.icon.setImageResource(item.iconRes)
            holder.title.text = item.title
            holder.desc.text = item.desc
            holder.itemView.setOnClickListener { showDetail(item.id) }
        }
    }

    private class MenuHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
        val desc: TextView = view.findViewById(R.id.menuDesc)
    }

    // ---- 公共 ----

    private fun close() {
        finish()
    }

    override fun onBackPressed() {
        // narrow 详情态：返回 = 回列表（再按一次才退出）；其余 = 关页
        if (!isWide && currentMenu >= 0) backToList() else close()
    }

    /** 页内轻提示（不用系统 Toast：虚拟屏上显示位置不可控）。两种形态都有 settingsToast 视图。 */
    override fun showToast(text: String) {
        val view = toastView ?: return
        view.text = text
        view.animate().cancel()
        view.alpha = 0f
        view
            .animate()
            .alpha(1f)
            .setDuration(160)
            .withEndAction {
                view.postDelayed({
                    view
                        .animate()
                        .alpha(0f)
                        .setDuration(220)
                        .start()
                }, 1_250)
            }.start()
    }

    private companion object {
        /** 双栏形态的最小屏（dp）：宽 ≥600 且高 ≥400（车机应用屏 ≈767×430 命中）。 */
        const val WIDE_MIN_WIDTH_DP = 600

        const val WIDE_MIN_HEIGHT_DP = 400

        const val KEY_WIDE = "wide"

        const val KEY_MENU = "menu"
    }
}
