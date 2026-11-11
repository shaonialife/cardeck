package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.AppIcons
import io.github.shaonialife.cardeck.launcher.DashCardPrefs
import io.github.shaonialife.cardeck.launcher.DefaultMapPrefs
import io.github.shaonialife.cardeck.launcher.DesktopApps
import io.github.shaonialife.cardeck.launcher.DockPrefs
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppRowsAdapter.AppHolder
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * 车机设置内容面板（Fragment 化）：车机 SettingsActivity 双栏右栏、
 * 手机侧二级菜单页共用同一套面板实例，逻辑零拷贝。
 */
class DesktopPanelFragment : SettingsPanelFragment() {
    private var countText: TextView? = null
    private var selected: MutableSet<String> = mutableSetOf()
    private lateinit var adapter: DesktopAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_desktop, container, false)

    override fun onViewCreated(view: View) {
        countText = view.findViewById(R.id.settingsCount)
        adapter = DesktopAdapter()
        setupAppList(view, R.id.appList, R.id.appListIndex, adapter) {
            selected.clear()
            selected += DesktopApps.selected(requireContext(), apps)
            updateCount()
        }
    }

    private fun updateCount() {
        countText?.text = "已选 ${selected.size} 个"
    }

    private fun toggle(
        entry: VirtualLauncher.AppEntry,
        checked: Boolean,
    ) {
        val key = entry.component.flattenToString()
        if (checked) selected.add(key) else selected.remove(key)
        DesktopApps.save(requireContext(), selected)
        updateCount()
        hostToast(if (checked) "「${entry.label}」已显示到桌面" else "「${entry.label}」已从桌面移除")
        adapter.notifyDataSetChanged()
    }

    private inner class DesktopAdapter : AppRowsAdapter(this@DesktopPanelFragment, R.layout.item_app_pick, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = PickerHolder(row)
    }

    private inner class PickerHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.pickIcon)
        private val name: TextView = row.findViewById(R.id.pickName)
        private val check: ImageView = row.findViewById(R.id.pickCheck)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val key = entry.component.flattenToString()
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            check.setImageResource(
                if (key in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            row.setOnClickListener {
                val nowChecked = key !in selected
                toggle(entry, nowChecked)
                check.setImageResource(
                    if (nowChecked) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
                )
            }
        }
    }
}

class OrientationPanelFragment : SettingsPanelFragment() {
    /**
     * 系统侧真正开着覆盖的包集合（面板打开时后台查一次，行绑定只做内存查表——
     * `dumpsys platform_compat` 单次约 30ms/36KB，绝不可每行一次）。
     * 打开面板/清除残留后刷新；行内开关点击就地更新，避免重查。
     */
    private var systemOn: Set<String> = emptySet()
    private var systemStateLoaded = false
    private lateinit var listAdapter: OrientationAdapter
    private lateinit var panelView: View

    /**
     * 本机是否支持「强制横屏」——null = 探测中。
     *
     * 两个 ChangeId 是 OEM 定制项（AOSP 标准表里没有）：荣耀 Android 15 注册了，
     * 红米 Android 13 没注册。不支持时整列置灰并给出原因，否则用户会反复点开关、
     * 每次只得到失败提示（2026-10-18 实测）。
     */
    private var supported: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_orientation, container, false)

    override fun onViewCreated(view: View) {
        panelView = view
        listAdapter = OrientationAdapter()
        // 应用清单就绪后重算横幅（首帧 apps 为空，全部会被误判成「不在列表」）
        setupAppList(view, R.id.oriList, R.id.oriListIndex, listAdapter) { updateOrphanBanner() }
        // 能力探测：一次 dumpsys（约 30ms，结果进程内缓存），与系统状态查询并行
        Bg.run("orientation-capability") {
            val ok = AppOrientationCompat.changeIdsRegistered()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                supported = ok
                applyCapabilityState()
                listAdapter.notifyDataSetChanged()
            }
        }
        Bg.run("orientation-system-state") {
            val state = AppOrientationCompat.systemEnabled()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                systemOn = state
                systemStateLoaded = true
                listAdapter.notifyDataSetChanged()
                updateOrphanBanner()
            }
        }
    }

    /**
     * 按能力探测结果调整面板：不支持时显示横幅、整列变暗且不可点。
     *
     * 置灰而不是隐藏列表：用户能看到「这台机器做不到」，而不是面对一个消失的功能
     * 猜测原因。横幅文案说明是 ROM 缺项、换机型才能生效（避免误以为是 Shizuku 问题）。
     */
    private fun applyCapabilityState() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriUnsupportedBanner) ?: return
        val list = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.oriList)
        val index = panelView.findViewById<LinearLayout>(R.id.oriListIndex)
        if (supported != false) {
            banner.visibility = View.GONE
            list?.alpha = 1f
            index?.alpha = 1f
            return
        }
        banner.visibility = View.VISIBLE
        banner.text =
            "本机系统不支持该功能：需要 Android 14 及以上" +
            "（这两个横屏兼容项自 Android 14 起才进入平台）。\n" +
            "当前 Android ${android.os.Build.VERSION.RELEASE}" +
            "（SDK ${android.os.Build.VERSION.SDK_INT}），开关置灰不可用。"
        // 整列变暗表达「不可操作」；点击事件在 OriHolder 里按 supported 拦截
        list?.alpha = 0.38f
        index?.alpha = 0.38f
    }

    /**
     * 面板外残留（清单里点不到、系统侧却开着覆盖的键）横幅：点击弹窗确认后清理。
     * 包括两类：已卸载应用（覆盖只在 rawOverrides 可见）、以及历史脏数据里的
     * `包/组件` 键——这些在应用列表里都没有对应行，行内小字覆盖不到。
     */
    private fun updateOrphanBanner() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriOrphanBanner) ?: return
        // 应用清单未就绪时先不判定：apps 为空会让所有已开启覆盖都「看起来像列表外残留」
        // （列表真正装载后 onAppsReady 会再调一次）
        if (apps.isEmpty()) return
        val known = apps.map { it.component.packageName }.toSet()
        val orphans = systemOn.filter { it !in known }.sorted()
        if (orphans.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        // 组件键（含 /）很长：显示时截到包名部分，完整键留给弹窗
        val summary =
            orphans.take(3).joinToString("、") { it.substringBefore('/') } +
                if (orphans.size > 3) " 等 ${orphans.size} 个" else ""
        banner.text = "另有 ${orphans.size} 个列表外的横屏残留：$summary\n点击清除"
        banner.setOnClickListener {
            val host = activity ?: return@setOnClickListener
            DarkMessageDialog.show(
                activity = host,
                title = "清除横屏残留",
                message =
                    "以下条目已不在应用列表，但系统侧横屏覆盖仍开着：\n" +
                        orphans.joinToString("\n") { "· $it" } +
                        "\n\n清除会立即重启在运行的这些应用（未保存的界面状态会丢失）；" +
                        "已卸载的条目不受影响。是否继续？",
                actions =
                    listOf(
                        DarkMessageDialog.Action("取消") {},
                        DarkMessageDialog.Action("清除", DarkMessageDialog.Emphasis.DANGER) {
                            Bg.run("orientation-orphan-clear") {
                                val cleared = orphans.count { AppOrientationCompat.clearSystemOnly(it) }
                                activity?.runOnUiThread {
                                    if (!isAdded || isDetached) return@runOnUiThread
                                    hostToast("已清除 $cleared/${orphans.size} 个横屏残留")
                                    systemOn = systemOn - orphans.toSet()
                                    listAdapter.notifyDataSetChanged()
                                    updateOrphanBanner()
                                }
                            }
                        },
                    ),
            )
        }
    }

    private inner class OrientationAdapter :
        AppRowsAdapter(this@OrientationPanelFragment, R.layout.item_orientation_car, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = OriHolder(row)
    }

    private inner class OriHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.oriIcon)
        private val name: TextView = row.findViewById(R.id.oriName)
        private val hint: TextView = row.findViewById(R.id.oriHint)
        private val check: ImageView = row.findViewById(R.id.oriCheck)
        private lateinit var entry: VirtualLauncher.AppEntry
        private var shown = false

        override fun bind(entry: VirtualLauncher.AppEntry) {
            this.entry = entry
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            // 界面勾选状态即事实源：点击 = 取反当前显示状态（不重新读偏好，
            // 避免读值失真导致方向算反——实测「取消」会变成再次「启用」）
            shown = AppOrientationCompat.isEnabled(requireContext(), pkg())
            refresh(shown)

            row.setOnClickListener {
                // 面板顶部已探测到本机不支持（ROM 未注册 ChangeId）：直接忽略点击，
                // 不再发起注定失败的 shell 往返，也不用 toast 反复打扰。
                if (supported == false) return@setOnClickListener
                // **异步**：setEnabled 内部是 2 次 shell（各 4s 超时，最坏 8s），
                // 直接同步调用会把点击回调所在的主线程卡到 ANR。
                val target = !shown
                shown = target
                refresh(shown) // 立即回显（乐观更新），失败时回滚
                AppOrientationCompat.setEnabledAsync(requireContext(), pkg(), target) { result ->
                    if (!isAdded || isDetached) return@setEnabledAsync
                    when (result) {
                        AppOrientationCompat.WriteResult.OK -> {
                            // 就地更新系统侧缓存（写入已同步完成，无需重查 dumpsys）
                            systemOn = if (target) systemOn + pkg() else systemOn - pkg()
                            refresh(target)
                            // am compat 写入即生效：系统当场杀掉目标应用，返回时按新方向重启
                            // （2026-10-15 实测：enable/disable 后 pid 立即消失）。旧的「查活 +
                            // 询问是否结束」流程问的是已被系统杀掉的应用，pidof 又与击杀竞态、
                            // 提示时对时错（「未在运行」误导），整体移除、如实告知即可。
                            hostToast("「${entry.label}」横屏：${if (target) "开" else "关"}——应用将以新设置重启")
                        }

                        AppOrientationCompat.WriteResult.UNSUPPORTED -> {
                            shown = !target
                            refresh(shown)
                            supported = false
                            applyCapabilityState()
                            hostToast(
                                if (AppOrientationCompat.changeIdsRegistered()) {
                                    "系统限制：user 版本只能修改可调试(debuggable)应用的兼容设置"
                                } else {
                                    "本机系统不支持该功能（需 Android 14 及以上）"
                                },
                            )
                        }

                        AppOrientationCompat.WriteResult.FAILED -> {
                            shown = !target
                            refresh(shown)
                            hostToast("写入失败（Shizuku 未就绪或超时）——可稍后重试")
                        }
                    }
                }
            }
        }

        private fun pkg() = entry.component.packageName

        /**
         * 勾选框 + 系统侧状态小字。三种状态：
         *   勾选开 + 系统开 → 灰字「已开启横屏（会话期间生效）」（正常）
         *   勾选关 + 系统开 → 红字高亮「系统残留：横屏仍在生效，点击清除」（异常，可点清除）
         *   其它            → 隐藏（勾选框本身已表达偏好，不制造噪音）
         */
        private fun refresh(enabled: Boolean) {
            check.setImageResource(
                if (enabled) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            val onSystem = systemStateLoaded && pkg() in systemOn
            when {
                onSystem && enabled -> {
                    hint.text = "已开启横屏（会话期间生效）"
                    hint.setTextColor(0xFF8E8E93.toInt())
                    hint.visibility = View.VISIBLE
                    hint.setOnClickListener(null)
                    hint.isClickable = false
                }

                onSystem -> {
                    hint.text = "系统残留：横屏仍在生效 · 点击清除"
                    hint.setTextColor(0xFFFF6B6B.toInt())
                    hint.visibility = View.VISIBLE
                    hint.isClickable = true
                    // 只清系统侧覆盖、不动偏好（偏好本来就是关的）；弹窗提示会重启应用
                    hint.setOnClickListener {
                        val host = activity ?: return@setOnClickListener
                        DarkMessageDialog.show(
                            activity = host,
                            title = "清除横屏残留",
                            message =
                                "「${entry.label}」的系统侧横屏覆盖仍在生效（可能是卸载前的残留）。" +
                                    "\n\n清除会立即重启该应用（正在运行的应用会丢失未保存的界面状态）。是否继续？",
                            actions =
                                listOf(
                                    DarkMessageDialog.Action("取消") {},
                                    DarkMessageDialog.Action("清除", DarkMessageDialog.Emphasis.DANGER) {
                                        Bg.run("orientation-row-clear") {
                                            val ok = AppOrientationCompat.clearSystemOnly(pkg())
                                            activity?.runOnUiThread {
                                                if (!isAdded || isDetached) return@runOnUiThread
                                                if (ok) {
                                                    systemOn = systemOn - pkg()
                                                    refresh(shown)
                                                    hostToast("「${entry.label}」横屏残留已清除——应用将重启")
                                                } else {
                                                    hostToast("清除失败（Shizuku 异常）")
                                                }
                                            }
                                        }
                                    },
                                ),
                        )
                    }
                }

                else -> {
                    hint.visibility = View.GONE
                    hint.setOnClickListener(null)
                }
            }
        }
    }
}

class DockPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_dock, container, false)

    override fun onViewCreated(view: View) {
        val scrimSeek = view.findViewById<SeekBar>(R.id.dockScrimSeek)
        val scrimValue = view.findViewById<TextView>(R.id.dockScrimValue)
        val blurSeek = view.findViewById<SeekBar>(R.id.dockBlurSeek)
        val blurValue = view.findViewById<TextView>(R.id.dockBlurValue)
        // 栏宽度（50%–100%，步进 5；**下次连接起效**——与上方 scrim/blur 的
        // 实时生效不同：dock 虚拟屏的像素尺寸/密度按会话建立时的偏好创建）
        val widthSeek = view.findViewById<SeekBar>(R.id.dockWidthSeek)
        val widthValue = view.findViewById<TextView>(R.id.dockWidthValue)
        val savedWidth = DockPrefs.widthPercent(requireContext())
        widthSeek.progress = (savedWidth - DockPrefs.WIDTH_MIN) / DockPrefs.WIDTH_STEP
        widthValue.text = "$savedWidth%"
        widthSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    widthValue.text = "${DockPrefs.WIDTH_MIN + progress * DockPrefs.WIDTH_STEP}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent =
                        DockPrefs.WIDTH_MIN + widthSeek.progress * DockPrefs.WIDTH_STEP
                    DockPrefs.setWidthPercent(requireContext(), percent)
                    hostToast("Dock 栏宽度 $percent% 已保存，下次连接起效")
                }
            },
        )

        scrimSeek.progress =
            DockPrefs
                .scrimOpacity(requireContext())
        scrimValue.text = "${scrimSeek.progress}%"
        scrimSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scrimValue.text = "$value%"
                    DockPrefs
                        .setScrimOpacity(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        blurSeek.progress =
            DockPrefs
                .blur(requireContext())
        blurValue.text = "${blurSeek.progress}%"
        blurSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    blurValue.text = "$value%"
                    DockPrefs
                        .setBlur(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
    }
}

/**
 * 「仪表盘」面板（原「默认地图」，2026-11-07 扩展）：默认地图单选（现状不动）+
 * 右侧卡片模式——音乐卡片（默认）或「固定应用」（独立虚拟屏 CarPlayPinned 承载的
 * 指定应用，密度=手机侧）。固定应用切换实时生效（[VirtualLauncher.applyDashCardChanged]）；
 * 所选应用被卸载时解析失败 → 自然回退音乐卡片（面板显示音乐卡片选中，存储保留）。
 */
class DashboardPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_map, container, false)

    override fun onViewCreated(view: View) {
        buildMapSection(view.findViewById(R.id.mapOptions))
        buildCardSection(view.findViewById(R.id.cardOptions))
    }

    /** 默认地图单选（原 DefaultMapPanelFragment 逻辑原样搬移）。 */
    private fun buildMapSection(container: LinearLayout) {
        val installed =
            VirtualLauncher.MAP_PACKAGES_PRIORITY.mapNotNull { pkg ->
                val launch =
                    requireContext().packageManager.getLaunchIntentForPackage(pkg)
                        ?: return@mapNotNull null
                val label =
                    runCatching {
                        requireContext()
                            .packageManager
                            .getApplicationInfo(pkg, 0)
                            .loadLabel(requireContext().packageManager)
                            .toString()
                    }.getOrDefault(pkg)
                pkg to label
            }
        if (installed.isEmpty()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "未安装任何受支持的地图应用（高德/百度车机版、高德/百度/腾讯手机版）"
                    setTextColor(0xFF8E8E93.toInt())
                    textSize = 13f
                },
            )
            return
        }
        var current =
            DefaultMapPrefs.manualPackage(requireContext())
                ?: installed.firstOrNull()?.first
        installed.forEach { (pkg, label) ->
            val row = layoutInflater.inflate(R.layout.item_map_option, container, false)
            val iconPx = (34 * resources.displayMetrics.density).toInt()
            val appIcon =
                runCatching {
                    requireContext().packageManager.getApplicationIcon(pkg)
                }.getOrNull()
            if (appIcon != null) {
                row
                    .findViewById<ImageView>(R.id.mapOptionIcon)
                    .setImageBitmap(AppIcons.rounded(appIcon, iconPx))
            }
            row.findViewById<TextView>(R.id.mapOptionName).text = label
            val radio = row.findViewById<ImageView>(R.id.mapOptionRadio)

            fun refresh() {
                radio.setImageResource(
                    if (pkg == current) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
                )
            }
            refresh()
            row.setOnClickListener {
                current = pkg
                DefaultMapPrefs.set(requireContext(), pkg)
                for (i in 0 until container.childCount) {
                    (container.getChildAt(i).tag as? (() -> Unit))?.invoke()
                }
                hostToast("默认地图：$label")
            }
            row.tag = { refresh() }
            container.addView(row)
        }
    }

    /** 右侧卡片单选行刷新闭包（buildCardSection 内装配；选中态变化时逐行 invoke）。 */
    private var refreshCardRows: (() -> Unit)? = null

    private fun buildCardSection(container: LinearLayout) {
        val ctx = requireContext()
        val iconPx = (34 * resources.displayMetrics.density).toInt()

        /** 当前行数据：图标 Drawable（可空=占位）+ 名称。 */
        fun appIcon(pkg: String?) = pkg?.let { runCatching { ctx.packageManager.getApplicationIcon(it) }.getOrNull() }

        val rowMusic =
            layoutInflater.inflate(R.layout.item_map_option, container, false).apply {
                runCatching {
                    ctx.getDrawable(R.drawable.ic_music)?.let {
                        findViewById<ImageView>(R.id.mapOptionIcon)
                            .setImageBitmap(AppIcons.rounded(it, iconPx))
                    }
                }
                findViewById<TextView>(R.id.mapOptionName).text = "音乐卡片"
            }
        val rowApp =
            layoutInflater.inflate(R.layout.item_map_option, container, false).apply {
                runCatching {
                    ctx.getDrawable(R.drawable.ic_app_settings)?.let {
                        findViewById<ImageView>(R.id.mapOptionIcon)
                            .setImageBitmap(AppIcons.rounded(it, iconPx))
                    }
                }
                findViewById<TextView>(R.id.mapOptionName).text = "指定应用"
            }
        // 当前固定应用展示行：app 模式且应用可用时显示图标+名称，点击重开选择器
        val rowCurrent =
            layoutInflater.inflate(R.layout.item_map_option, container, false).apply {
                findViewById<ImageView>(R.id.mapOptionRadio).visibility = View.INVISIBLE
            }
        container.addView(rowMusic)
        container.addView(rowApp)
        container.addView(rowCurrent)

        fun applyMode(
            mode: String,
            pkg: String?,
            toastLabel: String,
        ) {
            DashCardPrefs.set(ctx, mode, pkg)
            VirtualLauncher.applyDashCardChanged()
            refreshCardRows?.invoke()
            hostToast("右侧卡片：$toastLabel")
        }

        fun refresh() {
            val resolved = DashCardPrefs.resolve(ctx)
            val mode = DashCardPrefs.mode(ctx)
            val appSelected = resolved != null
            // 存了 app 模式但应用不可启动（已卸载）→ 显示音乐卡片选中（存储保留）
            rowMusic.findViewById<ImageView>(R.id.mapOptionRadio).setImageResource(
                if (!appSelected) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
            )
            rowApp.findViewById<ImageView>(R.id.mapOptionRadio).setImageResource(
                if (appSelected) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
            )
            rowCurrent.visibility = if (appSelected) View.VISIBLE else View.GONE
            if (resolved != null) {
                val icon = appIcon(resolved.packageName)
                if (icon != null) {
                    rowCurrent
                        .findViewById<ImageView>(R.id.mapOptionIcon)
                        .setImageBitmap(AppIcons.rounded(icon, iconPx))
                }
                rowCurrent.findViewById<TextView>(R.id.mapOptionName).text =
                    runCatching {
                        ctx.packageManager
                            .getApplicationLabel(
                                ctx.packageManager.getApplicationInfo(resolved.packageName, 0),
                            ).toString()
                    }.getOrDefault(resolved.packageName)
            }
            if (mode == DashCardPrefs.MODE_APP && !appSelected) {
                // 提示存储的固定应用已不可用（不弹窗打断——回退是静默的）
                rowCurrent.findViewById<TextView>(R.id.mapOptionName).text = "所选应用不可用，已回退音乐卡片"
            }
        }
        refresh()

        rowMusic.setOnClickListener {
            applyMode(DashCardPrefs.MODE_MUSIC, DashCardPrefs.pinnedPackage(ctx), "音乐卡片")
        }
        rowApp.setOnClickListener {
            val resolved = DashCardPrefs.resolve(ctx)
            if (resolved != null) {
                val label =
                    runCatching {
                        ctx.packageManager
                            .getApplicationLabel(
                                ctx.packageManager.getApplicationInfo(resolved.packageName, 0),
                            ).toString()
                    }.getOrDefault(resolved.packageName)
                applyMode(DashCardPrefs.MODE_APP, resolved.packageName, label)
            } else {
                showAppPicker()
            }
        }
        rowCurrent.setOnClickListener { showAppPicker() }
        refreshCardRows = { refresh() }
    }

    /**
     * 全应用单选弹窗（车机/手机共用；强制深色主题——面板宿主在车机屏可能是
     * DayNight 浅色形态）。列表 = 全部可启动应用，排除地图应用（同包在固定屏与
     * 地图屏会互抢任务）。
     *
     * 交互：点行只**选中**（radio 高亮，2026-11-07 用户反馈「点应用即生效、却还要
     * 关弹窗」），点「确定」才落偏好并实时生效。
     */
    private fun showAppPicker() {
        loadApps {
            val ctx = requireContext()
            val candidates = apps.filter { !VirtualLauncher.isMapPackage(it.component.packageName) }
            if (candidates.isEmpty()) {
                hostToast("没有可选的应用")
                return@loadApps
            }
            val themed = android.view.ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat)
            var selectedPkg: String? = DashCardPrefs.resolve(ctx)?.packageName
            val list =
                LinearLayout(themed).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (10 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, pad)
                }
            val iconPx = (34 * resources.displayMetrics.density).toInt()
            val radios = mutableListOf<Pair<String, ImageView>>()

            fun refreshRadios() {
                radios.forEach { (pkg, radio) ->
                    radio.setImageResource(
                        if (pkg == selectedPkg) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
                    )
                }
            }
            val scroll = android.widget.ScrollView(themed)
            candidates.forEach { entry ->
                val pkg = entry.component.packageName
                val row = layoutInflater.inflate(R.layout.item_map_option, list, false)
                row
                    .findViewById<ImageView>(R.id.mapOptionIcon)
                    .setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
                row.findViewById<TextView>(R.id.mapOptionName).text = entry.label
                val radio = row.findViewById<ImageView>(R.id.mapOptionRadio)
                radios.add(pkg to radio)
                row.setOnClickListener {
                    selectedPkg = pkg
                    refreshRadios()
                }
                list.addView(row)
            }
            refreshRadios()
            scroll.addView(list)
            androidx.appcompat.app.AlertDialog
                .Builder(themed)
                .setTitle("选择固定应用")
                .setView(scroll)
                .setPositiveButton("确定") { _, _ ->
                    val pkg = selectedPkg ?: return@setPositiveButton
                    DashCardPrefs.set(ctx, DashCardPrefs.MODE_APP, pkg)
                    VirtualLauncher.applyDashCardChanged()
                    refreshCardRows?.invoke()
                    hostToast("右侧卡片：${candidates.firstOrNull { it.component.packageName == pkg }?.label ?: pkg}")
                }.setNegativeButton("取消", null)
                .show()
        }
    }
}

/**
 * 「显示」面板：流分辨率缩放（30%–160%，下次连接起效）+ 目标帧率（30/60，
 * 运行中实时切换）+ 真息屏 + 触摸节流。「上车自动连接」「HID 原始日志」与显示
 * 无关，2026-10-26 拆到 [MiscPanelFragment]。英雄卡展示的是会话运行时生效值
 * （SessionController 快照），与本页偏好值相区别。
 */
class DisplayPanelFragment : SettingsPanelFragment() {
    /**
     * 车机壁纸选择（Photo Picker，无需存储权限；2026-11-07 自首页「车机显示」迁入）。
     * Fragment 属性初始化期注册（生命周期要求：必须早于 STARTED）。
     */
    private val wallpaperPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .PickVisualMedia(),
        ) { uri ->
            if (uri == null) {
                hostToast("未选择图片")
                return@registerForActivityResult
            }
            val ok =
                WallpaperStore
                    .setFromUri(requireContext(), uri)
            hostToast(
                if (ok) "壁纸已更新（会话进行中约 1 秒内上车）" else "保存失败",
            )
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_display, container, false)

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val scaleSeek = view.findViewById<SeekBar>(R.id.displayScaleSeek)
        val scaleValue = view.findViewById<TextView>(R.id.displayScaleValue)
        val scaleNote = view.findViewById<TextView>(R.id.displayScaleNote)
        val fps30 = view.findViewById<TextView>(R.id.btnFps30)
        val fps60 = view.findViewById<TextView>(R.id.btnFps60)

        // 连接时真息屏（物理面板断电）：默认开；会话中切换立即生效
        val screenOffOn = view.findViewById<TextView>(R.id.btnScreenOffOn)
        val screenOffOff = view.findViewById<TextView>(R.id.btnScreenOffOff)
        var screenOffEnabled = DisplayPrefs.screenOffOnConnect(ctx)

        fun refreshScreenOff(enabled: Boolean) {
            screenOffOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            screenOffOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            screenOffOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            screenOffOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshScreenOff(screenOffEnabled)

        fun applyScreenOff(enabled: Boolean) {
            if (enabled == screenOffEnabled) return
            screenOffEnabled = enabled
            DisplayPrefs.setScreenOffOnConnect(ctx, enabled)
            refreshScreenOff(enabled)
            // 车机屏上就能操作：立即收放，不必等下次连接
            SessionController.applyScreenOffPreference(enabled)
            hostToast(if (enabled) "已开启：连接时物理息屏" else "已关闭：连接时只用暗幕")
        }
        screenOffOn.setOnClickListener { applyScreenOff(true) }
        screenOffOff.setOnClickListener { applyScreenOff(false) }

        // 滑条 progress（0..26）→ 百分比（30..160，步进 5）
        fun progressOf(percent: Int) = (percent - DisplayPrefs.SCALE_MIN) / DisplayPrefs.SCALE_STEP

        fun percentOf(progress: Int) = DisplayPrefs.SCALE_MIN + progress * DisplayPrefs.SCALE_STEP

        val saved = DisplayPrefs.scalePercent(ctx)
        scaleSeek.progress = progressOf(saved)
        scaleValue.text = "$saved%"

        fun refreshScaleNote() {
            val runningNow =
                SessionController.videoSender
                    ?.isRunning == true
            scaleNote.text =
                if (runningNow) {
                    "保存后下次连接起效；当前连接按 " +
                        "${SessionController.activeScalePercent}% 运行"
                } else {
                    "保存后下次连接起效"
                }
        }
        refreshScaleNote()

        scaleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scaleValue.text = "${percentOf(progress)}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = percentOf(scaleSeek.progress)
                    DisplayPrefs.setScalePercent(ctx, percent)
                    hostToast("分辨率 $percent% 已保存，下次连接起效")
                    refreshScaleNote()
                }
            },
        )

        fun refreshFps(active30: Boolean) {
            // 未选中片用暗色底（与容器同色）而非 null 背景，避免背景重置内边距
            fps30.setBackgroundResource(
                if (active30) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            fps30.setTextColor(
                if (active30) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt(),
            )
            fps60.setBackgroundResource(
                if (active30) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            fps60.setTextColor(
                if (active30) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt(),
            )
        }

        val savedFps = DisplayPrefs.fps(ctx)
        refreshFps(savedFps < 60)

        fun applyFps(fps: Int) {
            DisplayPrefs.setFps(ctx, fps)
            refreshFps(fps < 60)
            VirtualLauncher
                .setStreamFps(fps)
            val live =
                SessionController.videoSender
                    ?.isRunning == true
            hostToast("帧率已切换为 ${fps}fps${if (live) "（实时生效）" else ""}")
            if (live) SessionController.activeTargetFps = fps
        }
        fps30.setOnClickListener { applyFps(30) }
        fps60.setOnClickListener { applyFps(60) }

        // 触摸注入节流（0=关闭，步进 8ms）：缓解「交互滞后但帧率正常」的事件堆积
        val throttleSeek = view.findViewById<SeekBar>(R.id.throttleSeek)
        val throttleValue = view.findViewById<TextView>(R.id.throttleValue)

        fun throttleText(ms: Int) = if (ms <= 0) "关闭" else "${ms}ms"

        val savedThrottle = DisplayPrefs.moveThrottleMs(ctx)
        throttleSeek.progress = savedThrottle / 8
        throttleValue.text = throttleText(savedThrottle)
        TouchRouter.moveThrottleMs = savedThrottle.toLong()

        throttleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val ms = progress * 8
                    throttleValue.text = throttleText(ms)
                    DisplayPrefs.setMoveThrottleMs(ctx, ms)
                    TouchRouter.moveThrottleMs = ms.toLong()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    hostToast(
                        "触摸节流 " + throttleText(DisplayPrefs.moveThrottleMs(ctx)),
                    )
                }
            },
        )

        // 虚拟屏密度基准（2026-11-11）：应用/地图/固定屏各自 4 档（mdpi~xxhdpi），
        // 下次连接生效（attach 读偏好）。程序化构建：3 行 × 4 分段，省 12 个 XML id。
        val baselineSection = view.findViewById<LinearLayout>(R.id.baselineSection)
        val density = resources.displayMetrics.density
        val baselineRefreshers = mutableListOf<() -> Unit>()

        fun refreshBaselineRows() {
            baselineRefreshers.forEach { runCatching { it() } }
        }

        data class BaselineRow(
            val label: String,
            val getter: (Context) -> Int,
            val setter: (Context, Int) -> Unit,
        )
        val rows =
            listOf(
                BaselineRow("应用屏", DisplayPrefs::appDisplayBaseline, DisplayPrefs::setAppDisplayBaseline),
                BaselineRow("地图屏", DisplayPrefs::mapDisplayBaseline, DisplayPrefs::setMapDisplayBaseline),
                BaselineRow("固定应用屏", DisplayPrefs::pinnedDisplayBaseline, DisplayPrefs::setPinnedDisplayBaseline),
            )
        val tierLabels = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi")
        rows.forEach { rowDef ->
            val row =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    // 显式 LayoutParams（首版用默认 LP：segmentsBox 横向占满、label 被压
                    // 成 0 宽、spacer 顶走其余档位——只有第一档可见，2026-11-11 截图实测）
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                }
            row.addView(
                TextView(ctx).apply {
                    text = rowDef.label
                    setTextColor(0xFFF5F5F7.toInt())
                    textSize = 13f
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            val segmentsBox =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = ctx.getDrawable(R.drawable.bg_segment)
                    setPadding(
                        (3 * density).toInt(),
                        (3 * density).toInt(),
                        (3 * density).toInt(),
                        (3 * density).toInt(),
                    )
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                }
            val buttons = mutableListOf<Pair<Int, TextView>>()
            DisplayPrefs.BASELINE_TIERS.forEachIndexed { index, tier ->
                val seg =
                    TextView(ctx).apply {
                        text = tierLabels[index]
                        textSize = 12f
                        setPadding(
                            (10 * density).toInt(),
                            (5 * density).toInt(),
                            (10 * density).toInt(),
                            (5 * density).toInt(),
                        )
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    if (index > 0) leftMargin = (2 * density).toInt()
                                }
                    }
                buttons.add(tier to seg)
                segmentsBox.addView(seg)
                seg.setOnClickListener {
                    rowDef.setter(ctx, tier)
                    refreshBaselineRows()
                    hostToast("${rowDef.label}密度基准：${tierLabels[index]}（下次连接生效）")
                }
            }
            row.addView(segmentsBox)
            baselineSection.addView(row)

            val refreshRow = {
                val current = rowDef.getter(ctx)
                buttons.forEach { (tier, seg) ->
                    val selected = tier == current
                    seg.setBackgroundResource(
                        if (selected) R.drawable.bg_segment_sel else R.drawable.bg_segment,
                    )
                    seg.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
                }
            }
            refreshRow()
            baselineRefreshers.add(refreshRow)
        }
        refreshBaselineRows()

        view.findViewById<TextView>(R.id.btnWallpaperPick).setOnClickListener {
            wallpaperPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts
                        .PickVisualMedia.ImageOnly,
                ),
            )
        }
        view.findViewById<TextView>(R.id.btnWallpaperReset).setOnClickListener {
            val ok = WallpaperStore.resetToDefault(requireContext())
            hostToast(if (ok) "已恢复默认壁纸" else "恢复失败")
        }
    }
}
