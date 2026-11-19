package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.media.MediaSessionsState
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.DASH_GAP_PX
import io.github.shaonialife.cardeck.render.DASH_HANDLE_TOUCH_PX
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * CarPlay 主区桌面：纯黑背景 + 图标分页网格（横屏 4×3 CarPlay 规格；竖屏按格子
 * 高宽比自适应、最多 6 行，见 computeCellMetrics）+ 底部页点。
 * 布局声明在 res/layout（activity_grid / item_grid_page / item_app_cell），
 * 代码只做动态格子尺寸计算与数据绑定。无字母导航（对照 CarPlay 主屏）。
 *
 * 默认只显示地图类应用（DesktopApps 默认选择），第一格固定「车机设置」入口；
 * 用户可在设置页勾选要显示的应用，返回桌面（onResume）即时刷新。
 */
class GridActivity : AppCompatActivity() {
    /** 桌面格子：仪表盘页（第 0 页）/ 固定设置入口 / OEM「返回车机」/ 普通应用。 */
    private sealed interface Cell {
        object Dashboard : Cell

        object Pinned : Cell

        /** OEM「返回车机」块（/info oemIcons；点击 = 归还/收回主屏，会话保持）。 */
        object Oem : Cell

        data class App(
            val entry: VirtualLauncher.AppEntry,
        ) : Cell
    }

    private var pages: List<List<Cell>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    /** 当前行数（横屏 3；竖屏按格子高宽比自适应，分页容量 = COLUMNS × rows）。 */
    private var rows = ROWS_MIN

    /** 应用清单缓存（每次 resume 重扫 packageManager 会拖慢回桌面——仅在首次载入时扫描）。 */
    private var appsCache: List<VirtualLauncher.AppEntry>? = null

    /** 首次冷缓存扫描是否在后台进行中（主线程只置位/清位）。 */
    private var appsScanInFlight = false

    /** 壁纸背景层（对齐全流画布的切片；版本变化时后台换图）。 */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** 壁纸变更监听（WallpaperStore 主线程回调；解码在本类自行切后台线程）。 */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** 仪表盘模式面板（分屏时显示在应用区右半；左半由地图 tile 覆盖）。 */
    private var dashboardView: android.view.View? = null

    /** 仪表盘模式变化（VirtualLauncher 通知）。 */
    private val dashboardListener = { dashboard: Boolean ->
        runOnUiThread { applyDashboardMode(dashboard) }
    }

    /** 媒体状态监听（MediaSessionsState 主线程回调）。 */
    private val mediaStateListener = {
        runOnUiThread { renderMediaCard() }
    }

    /** 全画布位图就绪（WallpaperStore 首次构建/重建后回调）。 */
    private val canvasReadyListener = { _: Long -> runOnUiThread { refreshDashWallpaper() } }

    /** 卡片视觉（暗幕透明度/模糊）——与 dock 一致（DockPrefs）。 */
    private val dockPrefsListener = {
        runOnUiThread {
            applyDashCardChrome()
            refreshDashWallpaper()
        }
    }

    /** 地图布局变化（全屏/分屏切换改变音乐卡壁纸切片区域）。
     *  具名字段（勿内联匿名 lambda）：onDestroy 才能对称移除，否则桌面重建累积泄漏。 */
    private val mapLayoutListener: (Boolean) -> Unit =
        { _ -> runOnUiThread { refreshDashWallpaper() } }

    /** 右侧卡片模式变化（音乐 ⇄ 固定应用；VirtualLauncher 在 Bg 线程回调）。
     *  具名字段：onDestroy 对称移除（同 mapLayoutListener 的泄漏教训）。 */
    private val pinnedCardListener: () -> Unit =
        { runOnUiThread { applyPinnedCardMode() } }

    /** OEM「返回车机」块出现/变化（info 事件晚于桌面首帧到达——会话线程 fire）。 */
    private val oemListener: () -> Unit = { runOnUiThread { reload() } }

    /**
     * 应用卡片视觉：暗幕 alpha 与壁纸模糊半径（RenderEffect，半径随网格屏
     * 密度放大 1.5 倍以与 dock 视觉一致）。
     */
    private fun applyDashCardChrome() {
        val panel = dashboardView ?: return
        val scrim = panel.findViewById<View>(R.id.dashScrim)
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        scrim.background =
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(alpha, 0, 0, 0),
            )
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper)
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * resources.displayMetrics.density
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /**
     * 音乐卡壁纸：全画布切片中「音乐卡区域」的裁剪（与全屏壁纸同映射，
     * 区域 = 地图卡右侧至卡片右边距）。
     */
    private fun refreshDashWallpaper() {
        val panel = dashboardView ?: return
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper) ?: return
        val params = wallpaper.layoutParams ?: return
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        Bg.run("wallpaper-dash") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        canvasWidth,
                        canvasHeight,
                    )
                }.getOrNull() ?: return@run
            val margin = (CARD_MARGIN_PX * scale).toInt()
            // 裁出音乐卡对应区域的壁纸切片（与卡片实际位置一致，随分屏把手位置走）：
            //  横屏：右侧（左缘=把手中心+半把手）；竖屏：下方（顶缘同式）
            val musicStart = VirtualLauncher.dashMusicStartScreen()
            val cropX: Int
            val cropY: Int
            if (VirtualLauncher.dockAtBottom) {
                cropX = margin
                cropY =
                    (if (musicStart >= 0) musicStart else margin * 2 + (VirtualLauncher.mapTileHeight * scale).toInt())
                        .coerceAtMost(bitmap.height - 2)
            } else {
                cropX =
                    (
                        if (musicStart >= 0) {
                            musicStart + (VirtualLauncher.dockWidthPx * scale).toInt()
                        } else {
                            (VirtualLauncher.dockWidthPx + VirtualLauncher.appAreaWidth / 2 + CARD_MARGIN_PX / 2) * scale
                        }.toInt()
                    ).coerceAtMost(bitmap.width - 2)
                cropY = margin.coerceAtMost(bitmap.height - 2)
            }
            val cropW = (bitmap.width - cropX - margin).coerceAtLeast(2)
            val cropH = (bitmap.height - cropY - margin).coerceAtLeast(2)
            runOnUiThread {
                wallpaper.setImageBitmap(
                    Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH),
                )
                // 位图就绪后重申 RenderEffect（视图特效在位图更新后仍需生效）
                applyDashCardChrome()
            }
        }
    }

    /** 音乐卡进度自刷新（1s；渲染尾部续调度，位置由锚点+速度推算）。
     *  只做渲染、不自行续排——续排在 renderMediaCard 尾部按「播放中」门控
     *  （2026-11-10 排查耗电：暂停/无会话不再每秒唤醒主线程；旧实现 tick 自己
     *  postDelayed + renderMediaCard 尾部也 post，同一 Runnable 双重调度）。 */
    private val mediaTick =
        object : Runnable {
            override fun run() {
                renderMediaCard()
            }
        }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** mm:ss（超 1 小时 h:mm:ss）。 */
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1_000
        val h = totalSec / 3_600
        val m = totalSec % 3_600 / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * 音乐卡渲染：封面/标题/歌手/进度/播放暂停按钮（播放中=暂停图标，暂停=播放图标）。
     *
     * 文案要区分三种「没有歌名」的成因，否则会误导：
     *  1. 有会话在播、但**没拿到通知使用权** → metadata 恒为 null（会话与播放状态
     *     仍然可见）。此时显示「无法读取播放信息」并提示去哪里开；
     *  2. 有会话但暂停/无元数据 → 「未在播放」；
     *  3. 完全没有会话 → 同样「未在播放」。
     *
     * 标题与副标题必须成对决定：曾出现标题「正在播放」（布局静态文案）配副标题
     * 「未在播放」的自相矛盾显示（截图实测），静态文案已改为「媒体」，这里再按
     * 状态统一写死两行，避免二者来自不同来源。
     */
    private fun renderMediaCard() {
        // 固定应用模式：卡片区域归 CarPlayPinned 虚拟屏（合成器绘制），音乐卡 View
        // 已隐藏——不写内容也不再续排 1s tick（模式切回时 applyPinnedCardMode 重启）
        if (VirtualLauncher.pinnedCardActive) return
        val panel = dashboardView ?: return
        val now = MediaSessionsState.nowPlaying
        val title = now?.title?.takeIf { it.isNotBlank() }
        val artist = now?.artist?.takeIf { it.isNotBlank() }
        // 有会话但读不到任何元数据 + 未授权 → 权限问题，不是「没在播放」
        val missingDueToPermission =
            now != null && title == null && !MediaSessionsState.accessGranted
        panel.findViewById<TextView>(R.id.dashMediaTitle).text =
            title ?: if (missingDueToPermission) "无法读取播放信息" else "未在播放"
        panel.findViewById<TextView>(R.id.dashMediaArtist).text =
            artist
                ?: when {
                    missingDueToPermission -> "未授予「通知使用权」——请在系统设置中开启"
                    now?.playing == true -> "正在播放"
                    else -> ""
                }
        panel.findViewById<ImageView>(R.id.dashPlayPause).setImageResource(
            if (now?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_media_play,
        )
        panel.findViewById<TextView>(R.id.dashMediaTime)?.let { time ->
            if (now != null && now.durationMs > 0) {
                time.visibility = View.VISIBLE
                time.text =
                    "${formatTime(now.displayPositionAt(android.os.SystemClock.elapsedRealtime()))} / " +
                    formatTime(now.durationMs)
            } else {
                time.visibility = View.GONE
            }
        }
        panel.findViewById<ProgressBar>(R.id.dashMediaProgress)?.let { bar ->
            if (now != null && now.durationMs > 0) {
                bar.visibility = View.VISIBLE
                bar.progress =
                    (now.displayPositionAt(android.os.SystemClock.elapsedRealtime()) * 1000 / now.durationMs)
                        .toInt()
                        .coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
        }
        panel.findViewById<ImageView>(R.id.dashCover)?.let { cover ->
            val art = now?.art
            if (art != null) {
                cover.setImageBitmap(art)
                cover.visibility = View.VISIBLE
                // 封面圆角裁剪（outline 一次性设置）
                if (cover.clipToOutline != true) {
                    cover.clipToOutline = true
                    val dp = resources.displayMetrics.density
                    cover.outlineProvider =
                        object : android.view.ViewOutlineProvider() {
                            override fun getOutline(
                                view: View,
                                outline: android.graphics.Outline,
                            ) {
                                outline.setRoundRect(
                                    0,
                                    0,
                                    view.width,
                                    view.height,
                                    10 * dp,
                                )
                            }
                        }
                }
                // 点击封面 → 拉回正在播放的应用（任务复用：回到其播放界面）
                cover.setOnClickListener {
                    val pkg = now.pkg ?: return@setOnClickListener
                    val component =
                        packageManager.getLaunchIntentForPackage(pkg)?.component
                            ?: run {
                                VirtualLauncher.logSink
                                    ?.invoke("MEDIA: $pkg 无可启动入口，忽略封面点击")
                                return@setOnClickListener
                            }
                    Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                }
            } else {
                // 占位不消失（INVISIBLE）：封面异步加载/切歌间隙内容不上下跳动
                cover.visibility = View.INVISIBLE
                cover.setOnClickListener(null)
            }
        }
        // 进度显示需要持续走秒：播放中才续排（防重：先移除）；暂停/无会话不排，
        // 恢复播放由状态监听器触发 renderMediaCard 重新拉起（2026-11-10 排查耗电）
        if (MediaSessionsState.nowPlaying?.playing == true) {
            tickHandler.removeCallbacks(mediaTick)
            tickHandler.postDelayed(mediaTick, 1_000)
        } else {
            tickHandler.removeCallbacks(mediaTick)
        }
    }

    /** 仪表盘模式 → pager 位置同步（0=仪表盘，1+=桌面；已在目标位则不动）。 */
    private fun applyDashboardMode(dashboard: Boolean) {
        val pager = findViewById<ViewPager2>(R.id.gridPager)
        if (dashboard && pager.currentItem != 0) pager.setCurrentItem(0, false)
        if (!dashboard && pager.currentItem == 0) pager.setCurrentItem(1, false)
    }

    /**
     * 右侧卡片=固定应用：隐藏音乐卡面板（区域由合成器画 CarPlayPinned，触摸经
     * TouchRouter 直达固定屏）；切回音乐模式恢复显示并重启媒体卡渲染。
     */
    private fun applyPinnedCardMode() {
        val panel = dashboardView ?: return
        val pinned = VirtualLauncher.pinnedCardActive
        panel.visibility = if (pinned) View.GONE else View.VISIBLE
        if (pinned) {
            tickHandler.removeCallbacks(mediaTick)
        } else {
            renderMediaCard()
        }
    }

    /** 音乐控制：系统媒体键（AudioManager 全局路由给活跃媒体会话，无需权限）。 */
    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setContentView(R.layout.activity_grid)

        // 格子尺寸/行数按屏幕动态计算（布局关系在 XML，尺寸测量在代码）
        computeCellMetrics()
        // 虚拟屏重建竞态：dm 短暂为 0 时 cell/icon 尺寸会算出 0，首帧绑定即崩
        // （Bitmap.createBitmap 0 宽高）。夹最小值保底，并在 pager 首帧布局后重算+重绑。
        if (iconSizePx <= 0) {
            iconSizePx = 48
            findViewById<ViewPager2>(R.id.gridPager).post {
                computeCellMetrics()
                iconSizePx = iconSizePx.coerceAtLeast(48)
                if (cellWidthPx > 0) reload()
            }
        }

        // 壁纸背景（index 0 = 最底）：窗口必须不透明——透明窗口压在应用窗口前面时
        // 会把应用内容透出来（点 home 后桌面图标叠在头条上的根因）。
        // 切片映射：把「整个视频流画布」的 aspectFill 壁纸按 dock 位置偏移，
        // 本窗口正好露出应用区的切片，与 dock 处的切片逐像素连续（同一渲染路径）。
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth > 0 && canvasHeight > 0) {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            wallpaperView =
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams =
                        android.widget.FrameLayout
                            .LayoutParams(
                                canvasWidth,
                                canvasHeight,
                            ).apply {
                                if (VirtualLauncher.dockAtBottom) {
                                    // 竖屏车机：dock 在底部，桌面切片 = 画布顶部
                                    leftMargin = 0
                                    topMargin = 0
                                } else {
                                    // 横屏车机：dock 在左侧，桌面切片 = 画布右部
                                    leftMargin = -(VirtualLauncher.dockWidthPx * scale).toInt()
                                    topMargin = 0
                                }
                            }
                }
            root.addView(wallpaperView, 0)
            // 注册变更通知 + 立即对账一次（兜底注册前发生的更换——事件不补发）
            WallpaperStore
                .addListener(wallpaperListener)
            // OEM 块（info 事件晚于桌面首帧——出现即刷新网格）
            VirtualLauncher.addOemListener(oemListener)
            refreshWallpaperIfChanged()

            // 仪表盘面板已改为 ViewPager2 首页（PageAdapter 绑定时创建），
            // 此处仅注册全局监听：卡片视觉/媒体状态/壁纸切片/仪表盘模式。
            DockPrefs.addListener(dockPrefsListener)
            MediaSessionsState
                .addListener(mediaStateListener)
            WallpaperStore
                .addCanvasReadyListener(canvasReadyListener)
            VirtualLauncher.addDashboardListener(dashboardListener)
            VirtualLauncher.addMapLayoutListener(mapLayoutListener)
            VirtualLauncher.addPinnedCardListener(pinnedCardListener)
            applyDashboardMode(VirtualLauncher.dashboardMode)
        }

        pager =
            findViewById<ViewPager2>(R.id.gridPager).apply {
                offscreenPageLimit = 2
                adapter = PageAdapter()
            }
        dotsRow = findViewById(R.id.dotsRow)
        pager?.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                    // 用户滑动切换仪表盘/桌面：回写 launcher（含地图任务与 tile 布局）
                    if (position == 0 && !VirtualLauncher.dashboardMode) VirtualLauncher.showDashboard()
                    if (position != 0 && VirtualLauncher.dashboardMode) VirtualLauncher.showDesktop()
                }

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    offsetPixels: Int,
                ) {
                    // 滑动过程中 tile 跟随手指平移（合成器采样位置随进度左移/右移）；
                    // 回滑进仪表盘时提前显示 tile（纯视觉，状态在落定 IDLE 时统一对账）
                    if (position == 0) {
                        VirtualLauncher.setTileSlide(positionOffset)
                        if (positionOffset < 1f) VirtualLauncher.showMapTile()
                    }
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                        VirtualLauncher.setTileSlide(0f)
                        // 落定对账：仪表盘模式与所在页一致（含异常中断的对账恢复）
                        VirtualLauncher.syncDashboard(pager?.currentItem == 0)
                    }
                }
            },
        )
    }

    /** 壁纸版本变化时后台重解码并换图（手机侧换壁纸后 ≤2s 桌面更新）。 */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams
        Bg.run("wallpaper-grid") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        params?.width ?: 0,
                        params?.height ?: 0,
                    )
                }.getOrNull()
            if (bitmap != null) {
                // 诊断：bitmap 三点采样（中线上 25%/50%/75% 宽）——区分「bitmap 内容缺失」
                // 与「视图/合成问题」（本机 logcat 受抑制，走 logSink 落文件日志）
                val samples =
                    listOf(0.25f, 0.5f, 0.75f).joinToString(",") {
                        "%06x".format(bitmap.getPixel((bitmap.width * it).toInt(), bitmap.height / 2))
                    }
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-GRID: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params?.width}x${params?.height} left=${params?.leftMargin} " +
                        "samples=[$samples]",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-GRID: 解码失败")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 也覆盖从设置页返回：勾选变化即时反映
        reload()
        refreshWallpaperIfChanged()
    }

    /**
     * 计算格子尺寸与行数：
     * 横屏（CarPlay 规格）：固定 4 列 × 3 行，行为与此前一致。
     * 竖屏：固定 3 行会把格子拉成 ~1:2.5 的瘦高条（图标只占三成、满屏空框，
     * 2026-10-17 用户开「显示布局边界」截图暴露）；按「格子高 ≈ 宽 × 1.25」
     * 自适应加行（上限 6，对齐 iPhone 竖屏主屏 4×6 的观感），图标尺寸同步
     * 跟随格子宽，避免格子过大/图标过小的比例失衡。
     */
    private fun computeCellMetrics() {
        val dm = resources.displayMetrics
        val density = dm.density
        val chromeHorizontalPx = (2 * 10 * density).toInt() // item_grid_page 左右 padding
        val chromeVerticalPx = ((8 + 26 + 16) * density).toInt() // 上/下 padding + 页点区
        val usableW = dm.widthPixels - chromeHorizontalPx
        val usableH = dm.heightPixels - chromeVerticalPx
        if (usableW <= 0 || usableH <= 0) {
            cellWidthPx = 0
            cellHeightPx = 0
            iconSizePx = 0
            return
        }
        cellWidthPx = usableW / COLUMNS
        // 横竖屏统一：先按 3 行试算，格子过高（竖屏）时加行收敛到目标比例
        var rowCount = ROWS_MIN
        if (cellWidthPx > 0) {
            rowCount = (usableH / (cellWidthPx * CELL_HEIGHT_RATIO)).toInt().coerceIn(ROWS_MIN, ROWS_MAX)
        }
        rows = rowCount
        cellHeightPx = usableH / rowCount
        iconSizePx =
            (cellHeightPx * 0.63f)
                .toInt()
                .coerceAtMost((cellWidthPx * 0.72f).toInt())
    }

    /** 重新读取应用清单与桌面选择（首帧与每次回到桌面时调用）。
     *
     *  **主线程禁止扫 PackageManager**：132 个应用的 queryIntentActivities +
     *  loadLabel/loadIcon + 拼音排序在荣耀实测 2.7s（连接成功瞬间与虚拟屏首帧
     *  重叠，手机页面卡死 3s 的根因）。冷缓存时先用「固定格 + 仪表盘」出首帧，
     *  扫描切后台，完成后回主线程换表。 */
    private fun reload() {
        val cached =
            appsCache
                ?: VirtualLauncher.peekCachedApps()?.also { appsCache = it }
        if (cached == null && !appsScanInFlight) {
            appsScanInFlight = true
            Bg.run("apps-scan") {
                val scanned = VirtualLauncher.cachedApps(this@GridActivity)
                // 纳入进程级共享缓存：桌面扫过之后，车机设置首次打开即秒开
                VirtualLauncher.adoptAppsCache(scanned)
                runOnUiThread {
                    // iconCache 供 dock 运行中图标使用：整表换新——dock 在另一块屏的
                    // 主线程并发读，必须回主线程做（坏味道审查 P1）。
                    VirtualLauncher.replaceIconCache(
                        scanned.associate { it.component to it.icon },
                    )
                    appsCache = scanned
                    appsScanInFlight = false
                    reload()
                }
            }
        }
        val apps = cached ?: emptyList()

        val selected = DesktopApps.selected(this, apps)
        val cells =
            buildList {
                add(Cell.Pinned) // 桌面第一格：车机设置（固定，不可取消）
                // OEM「返回车机」块（/info oemIcons；会话建立后由 publishOemBlock 发布）
                if (VirtualLauncher.oemBlock != null) add(Cell.Oem)
                apps.filter { it.component.flattenToString() in selected }.forEach { add(Cell.App(it)) }
            }
        // 第 0 页 = 仪表盘，其后为桌面网格页：左右滑动即互相切换
        pages = listOf(listOf<Cell>(Cell.Dashboard)) + cells.chunked(COLUMNS * rows)

        val current = pager?.currentItem ?: 0
        pager?.adapter?.notifyDataSetChanged()
        rebuildDots()
        // 位置与仪表盘模式对账（home 键循环/应用启动会改 dashboardMode）：
        // 仅纠偏方向，不把停在桌面第 N 页的用户拽回第 1 页
        if (VirtualLauncher.dashboardMode && current != 0) pager?.setCurrentItem(0, false)
        if (!VirtualLauncher.dashboardMode && current == 0) pager?.setCurrentItem(1, false)
        if ((pager?.currentItem ?: 0) >= pages.size) pager?.setCurrentItem(pages.size - 1, false)
        updateDots(pager?.currentItem ?: 0)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages.getOrNull(position)?.firstOrNull() is Cell.Dashboard) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                // 仪表盘页 = 无边距容器：音乐卡面板 + 分屏拖拽把手（把手落在加宽的
                // 中缝内、不被合成器叠画的地图 tile 遮挡，触摸走应用屏正常派发）
                val container = android.widget.FrameLayout(parent.context)
                container.layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                val panel = layoutInflater.inflate(R.layout.view_dashboard, container, false)
                container.addView(panel)
                container.addView(DashSplitHandle(parent.context))
                DashboardHolder(container, panel)
            } else {
                val grid = layoutInflater.inflate(R.layout.item_grid_page, parent, false) as GridLayout
                grid.columnCount = COLUMNS
                // 行数由 computeCellMetrics 按横竖屏决定（横屏 3、竖屏最多 6）
                grid.rowCount = rows
                PageHolder(grid)
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is DashboardHolder -> holder.bind()
                is PageHolder -> holder.bind(position)
            }
        }
    }

    /** 仪表盘页 Holder：无边距容器（音乐卡面板 + 分屏把手）随页面创建。 */
    private inner class DashboardHolder(
        container: View,
        val panel: View,
    ) : RecyclerView.ViewHolder(container) {
        private var handle: DashSplitHandle? = null

        fun bind() {
            dashboardView = panel
            // 右侧卡片=固定应用时面板整块隐藏（区域由合成器画固定屏）；把手/壁纸
            // 切片照常工作（拖拽仍可用），媒体卡函数按 pinnedCardActive 短路
            panel.visibility =
                if (VirtualLauncher.pinnedCardActive) View.GONE else View.VISIBLE
            val scale = VirtualLauncher.gridScale()
            val cardMargin = (CARD_MARGIN_PX * scale).toInt()
            panel.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        if (VirtualLauncher.dockAtBottom) {
                            leftMargin = cardMargin
                            rightMargin = cardMargin
                            topMargin = cardMargin
                            bottomMargin = cardMargin
                        } else {
                            topMargin = cardMargin
                            rightMargin = cardMargin
                            bottomMargin = cardMargin
                        }
                    }
            panel.outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline,
                    ) {
                        outline.setRoundRect(0, 0, view.width, view.height, cardMargin * 1.4f)
                    }
                }
            panel.clipToOutline = true
            panel.findViewById<ImageView>(R.id.dashPrev).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            panel.findViewById<ImageView>(R.id.dashNext).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            panel.findViewById<ImageView>(R.id.dashPlayPause).setOnClickListener {
                // 按当前状态显式发播放/暂停；乐观翻转图标（状态回调可能滞后），
                // 400ms 后请求监听服务重新同步真实状态
                val state = MediaSessionsState.nowPlaying
                val playing = state?.playing == true
                dispatchMediaKey(
                    if (playing) {
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    } else {
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    },
                )
                // 乐观更新保留封面等其它字段（copy）；400ms 后重同步真实状态
                MediaSessionsState.publish(
                    state
                        ?.copy(playing = !playing)
                        ?: MediaSessionsState.NowPlaying(
                            null,
                            null,
                            !playing,
                        ),
                )
                panel.postDelayed(
                    {
                        MediaSessionsState
                            .requestResync()
                    },
                    400,
                )
            }
            // 全屏地图的退出走 dock 的 home 按钮（showDashboard 路由闸门会自动
            // setMapFullscreen(false)），仪表盘卡上不再放「全屏地图」按钮
            bindSplitHandle(scale)
            applyDashCardChrome()
            renderMediaCard()
            refreshDashWallpaper()
        }

        /** 配置分屏把手（方向/尺寸/拖拽回调）并按当前分屏摆放面板与把手。 */
        private fun bindSplitHandle(scale: Float) {
            val child = (itemView as? android.widget.FrameLayout)?.getChildAt(1) as? DashSplitHandle
            handle = child ?: return
            val portrait = VirtualLauncher.dockAtBottom
            // 触达条比视觉中缝宽（DASH_HANDLE_TOUCH_PX > DASH_GAP_PX）：两侧各溢出
            // 一段压在卡片上扩大触控目标——地图侧溢出段被 tile 盖住不可见但可触摸
            val thickness = (DASH_HANDLE_TOUCH_PX * scale).toInt()
            child.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else thickness,
                        if (portrait) thickness else ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            child.configure(portrait) { centerScreen, final ->
                if (final) {
                    // commit 内部钳制并真实 resize 地图屏；notifyMapLayout 会触发
                    // 壁纸切片重切。commit 返回钳后值，据此校正 margin 防漂移。
                    layoutSplit(VirtualLauncher.commitDashSplitScreen(centerScreen), scale)
                } else {
                    layoutSplit(centerScreen, scale)
                    VirtualLauncher.previewDashSplitScreen(centerScreen)
                }
            }
            layoutSplit(VirtualLauncher.dashHandleCenterScreen(), scale)
        }

        /** 把手中心（应用屏像素）→ 音乐卡与把手的 margin。未 attach（<0）时兜底中线。 */
        private fun layoutSplit(
            centerScreen: Float,
            scale: Float,
        ) {
            val portrait = VirtualLauncher.dockAtBottom
            val center =
                if (centerScreen >= 0) {
                    centerScreen
                } else {
                    // 兜底：应用区中线（与旧版固定分屏一致）
                    ((if (portrait) VirtualLauncher.appAreaHeight else VirtualLauncher.appAreaWidth) / 2f) * scale
                }
            // 音乐卡让位按视觉缝；把手按触达宽居中（比缝宽、压在两侧卡片上）
            val halfGap = DASH_GAP_PX / 2f * scale
            val halfTouch = DASH_HANDLE_TOUCH_PX / 2f * scale
            (panel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                if (portrait) {
                    lp.topMargin = (center + halfGap).toInt()
                } else {
                    lp.leftMargin = (center + halfGap).toInt()
                }
            }
            handle?.let { h ->
                (h.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                    if (portrait) {
                        lp.topMargin = (center - halfTouch).toInt()
                    } else {
                        lp.leftMargin = (center - halfTouch).toInt()
                    }
                }
                h.requestLayout()
            }
            panel.requestLayout()
        }
    }

    private inner class PageHolder(
        private val grid: GridLayout,
    ) : RecyclerView.ViewHolder(grid) {
        fun bind(page: Int) {
            grid.removeAllViews()
            pages[page].forEach { cell ->
                val view = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                val icon =
                    view.findViewById<ImageView>(R.id.cellIcon).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                val label = view.findViewById<TextView>(R.id.cellLabel)
                when (cell) {
                    is Cell.Dashboard -> Unit // 仪表盘独立成页，不进网格
                    is Cell.Pinned -> {
                        icon.setImageResource(R.drawable.ic_app_settings)
                        label.text = getString(R.string.car_settings)
                        view.setOnClickListener { openSettings() }
                    }

                    is Cell.Oem -> {
                        val block = VirtualLauncher.oemBlock
                        if (block?.icon != null) {
                            icon.setImageBitmap(
                                AppIcons.rounded(
                                    android.graphics.drawable.BitmapDrawable(resources, block.icon),
                                    iconSizePx,
                                ),
                            )
                        } else {
                            icon.setImageResource(R.drawable.ic_menu_grid)
                        }
                        label.text = block?.label ?: ""
                        view.setOnClickListener {
                            // requestUI（会话保持）：车机切回原界面即反馈；
                            // 内部转 Bg（事件通道出站是 socket 写）
                            io.github.shaonialife.cardeck.SessionController
                                .requestCarUi()
                        }
                    }

                    is Cell.App -> {
                        icon.setImageBitmap(AppIcons.rounded(cell.entry.icon, iconSizePx))
                        label.text = cell.entry.label
                        view.setOnClickListener {
                            // 不用系统 Toast：虚拟屏上显示位置不可控（SettingsToastHost 的由来）；
                            // 应用拉起本身就是反馈
                            Bg.run("launch-app") { VirtualLauncher.launchApp(cell.entry.component) }
                        }
                    }
                }
                // 焦点：iDrive 旋钮/方向盘方向键注入的 DPAD 事件靠焦点系统落地，
                // 网格单元必须可聚焦 + 有可见高亮框（按键会自动退出 touch mode）。
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.foreground =
                    androidx.core.content.ContextCompat
                        .getDrawable(this@GridActivity, R.drawable.focus_ring)
                grid.addView(
                    view,
                    GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = cellHeightPx
                    },
                )
            }
        }
    }

    private fun openSettings() {
        // 滑动转场由 SettingsActivity 主题的 windowAnimationStyle 提供
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        if (pages.size < 2) return // 单页不显示页点（对照原型）
        val density = resources.displayMetrics.density
        repeat(pages.size) {
            row.addView(
                android.view.View(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(0x66FFFFFF)
                        }
                },
                LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                },
            )
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val row = dotsRow ?: return
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (i == selected) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt())
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        tickHandler.removeCallbacks(mediaTick)
        WallpaperStore
            .removeListener(wallpaperListener)
        VirtualLauncher.removeDashboardListener(dashboardListener)
        VirtualLauncher.removeMapLayoutListener(mapLayoutListener)
        VirtualLauncher.removePinnedCardListener(pinnedCardListener)
        VirtualLauncher.removeOemListener(oemListener)
        MediaSessionsState
            .removeListener(mediaStateListener)
        DockPrefs.removeListener(dockPrefsListener)
        WallpaperStore
            .removeCanvasReadyListener(canvasReadyListener)
        super.onDestroy()
    }

    override fun onBackPressed() = Unit // 桌面不退出

    companion object {
        /**
         * 车机桌面实例：VirtualLauncher.detach 释放屏前 finishAndRemoveTask——
         * 虚拟屏不带 DESTROY_CONTENT_ON_REMOVAL 时任务会被系统 reparent 回主屏，
         * 自有桌面不能被搬上手机。
         */
        @Volatile
        var current: GridActivity? = null

        const val COLUMNS = 4 // CarPlay 规格：强制 4 列

        /** 横屏 CarPlay 规格行数（竖屏自适应时用作下限）。 */
        const val ROWS_MIN = 3

        /** 竖屏自适应行数上限（对齐 iPhone 竖屏主屏 4×6 的观感）。 */
        const val ROWS_MAX = 6

        /** 格子目标高宽比（高/宽）：竖屏加行的收敛目标。 */
        const val CELL_HEIGHT_RATIO = 1.25f
    }
}
