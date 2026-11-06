package io.github.shaonialife.cardeck.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.shaonialife.cardeck.bt.BtSessionGate
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.media.NotificationAccess
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.DASH_GAP_PX
import io.github.shaonialife.cardeck.render.DASH_HANDLE_TOUCH_PX
import io.github.shaonialife.cardeck.render.DualDisplayComposer
import io.github.shaonialife.cardeck.render.OverlayCard
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.util.Bg

/**
 * 虚拟屏启动器（双屏 + GL 合成架构）：
 *
 *   dock 屏（窄条，DockActivity 常驻）──┐
 *                                      ├─ DualDisplayComposer ─→ 编码器 Surface
 *   应用屏（GridActivity / 第三方应用）──┘
 *
 * - 应用启动到应用屏（shell am start --display 应用屏）；同一组件再次启动带回既有任务（续运行）。
 * - 回桌面 = 把 GridActivity 带回前台，后台应用任务不杀。
 * - 触摸由 TouchRouter 按主画面 x 坐标分流：x < dockWidth → dock 屏，否则 → 应用屏（平移）。
 */
object VirtualLauncher {
    private const val TAG = "VirtualLauncher"

    /**
     * dock 宽度（dp；应用屏可用宽度 = 总宽 - 本值换算的像素）。CarPlay 化后含时钟/电量。
     * 密度基准 420dpi（↔ 奥迪设计画布 1160×540），由 SessionController 按画布短边
     * 等比下发——高分辨率车机下 dock 条与文字/图标同步放大，占画布短边恒定 23.3%
     * （= 设计 126/540；2026-10-17 修复「高分辨率下 dock 显小」）。
     */
    const val DOCK_WIDTH_DP = 48

    /**
     * 竖屏锁定应用的 letterbox 宽高比（宽/高，会话建立时经 Shizuku 写入系统）。
     * 默认（约 1.78）下窗口只有 ~303px≈188dp@240dpi；1.05 放宽到约 514px。
     */
    private const val LETTERBOX_ASPECT_RATIO = 1.05f

    /** letterbox 窗口的目标逻辑宽度（dp）：≈ 手机屏宽，触发电商类应用的紧凑手机布局。 */
    private const val LETTERBOX_TARGET_DP = 410f

    /**
     * 横屏应用屏超采样倍率（固定像素占位缩小的唯一杠杆，见 attach 注释）。
     * 2.0 时像素开销 4x（用户实测明显变卡）；1.5 时 2.25x，顶部固定区占比
     * 12%（原始 18%）——画质/流畅的折中点（2026-10-11 用户反馈调参）。
     */
    private const val APP_SUPERSAMPLE = 1.5f

    /** 超采样后的应用屏高度上限（px；控制 GPU/内存开销）。 */
    private const val SUPERSAMPLE_MAX_HEIGHT = 1620

    // ---- 仪表盘分屏（拖拽把手）----
    // 比例 = tile 主轴长占「可用长度」（应用区主轴 − 两端边距 − 把手宽）的比例。

    /** 默认比例：0.5 恰使把手中心落在应用区正中（见 dashSplitGeom 注释）。 */
    const val DASH_SPLIT_DEFAULT_FRACTION = 0.5f

    /** 比例钳制域：任一卡最小占 20%，防拖成一条线（按钮/圆角全挤没）。 */
    const val DASH_SPLIT_MIN_FRACTION = 0.2f

    const val DASH_SPLIT_MAX_FRACTION = 0.8f

    private const val PREF_KEY_DASH_SPLIT = "dashSplitFraction"

    fun clampDashSplitFraction(fraction: Float): Float = fraction.coerceIn(DASH_SPLIT_MIN_FRACTION, DASH_SPLIT_MAX_FRACTION)

    /** 分屏几何（应用区坐标系：横屏原点=dock 右缘，竖屏原点=流顶缘）。 */
    data class DashSplitGeom(
        val fraction: Float,
        /** tile 主轴长（横=mapTileWidth、竖=mapTileHeight）。 */
        val tileLen: Int,
        /** tile 把手侧边缘（composer override 同语义）。 */
        val tileEdge: Float,
        /** 把手中心。 */
        val splitCenter: Float,
        /** 对侧卡（音乐卡）内容侧起始。 */
        val otherStart: Float,
    )

    /**
     * 分屏几何纯函数（单测钉死）：可用长度 = 主轴 − 边距×2 − 把手宽；
     * tileLen = 比例×可用；把手中心 = 边距 + tileLen + 半把手（0.5 时恰在
     * 应用区正中，与旧行为的中线分屏视觉一致）；音乐卡起始 = 边距 + tileLen + 把手宽。
     */
    fun dashSplitGeom(
        appMainLen: Int,
        fraction: Float,
    ): DashSplitGeom {
        val usable = (appMainLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()).coerceAtLeast(1)
        val clamped = clampDashSplitFraction(fraction)
        val tileLen = (clamped * usable).toInt()
        val edge = CARD_MARGIN_PX + tileLen
        return DashSplitGeom(
            fraction = clamped,
            tileLen = tileLen,
            tileEdge = edge.toFloat(),
            splitCenter = edge + DASH_GAP_PX / 2f,
            otherStart = edge + DASH_GAP_PX,
        )
    }

    /** 分屏主轴长度（应用区坐标系）：横=appAreaWidth、竖=appAreaHeight。 */
    private fun splitMainLen(): Int = if (dockAtBottom) appAreaHeight else appAreaWidth

    val runningApps = LinkedHashSet<ComponentName>()

    private val runningAppsListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** dock 注册回调：运行列表变化时刷新（dock 独占一屏，永远 resumed，onResume 不再触发）。 */
    fun addRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.add(listener)
    }

    fun removeRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.remove(listener)
    }

    private fun notifyRunningAppsChanged() {
        runningAppsListeners.forEach { runCatching { it() } }
    }

    // ---- OEM「返回车机」块（/info oemIcons 域；会话层 publishOemBlock 写入）----

    /** OEM 块数据：车机图标标签（如「BYD」「我的奥迪」）+ 预渲染 PNG 图标。 */
    data class OemBlock(
        val label: String,
        val icon: android.graphics.Bitmap?,
    )

    @Volatile
    var oemBlock: OemBlock? = null
        private set

    private val oemListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 桌面注册回调：OEM 块出现/变化时刷新网格（info 事件到达晚于桌面首帧）。 */
    fun addOemListener(listener: () -> Unit) {
        oemListeners.add(listener)
    }

    fun removeOemListener(listener: () -> Unit) {
        oemListeners.remove(listener)
    }

    fun setOemBlock(block: OemBlock?) {
        oemBlock = block
        oemListeners.forEach { runCatching { it() } }
    }

    /**
     * 桌面图标缓存（DockActivity 显示运行中图标用）。桌面扫描线程整表换新、dock 的
     * 主线程并发读——volatile 引用替换，读方拿到的旧表也完整自洽（此前 clear+逐条
     * 写入同一个 HashMap，跨屏读写无同步，并发下可能损坏）。
     */
    @Volatile
    private var iconCacheRef: Map<ComponentName, Drawable> = emptyMap()

    val iconCache: Map<ComponentName, Drawable>
        get() = iconCacheRef

    /** 桌面扫描完成后整表替换图标缓存。 */
    fun replaceIconCache(fresh: Map<ComponentName, Drawable>) {
        iconCacheRef = fresh
    }

    @Volatile
    var dockDisplayId: Int = -1
        private set

    @Volatile
    var appDisplayId: Int = -1
        private set

    /** dock 在主合成画面中占据的像素宽度（TouchRouter 分流阈值；横屏=左侧竖条宽）。 */
    @Volatile
    var dockWidthPx: Int = 0
        private set

    /** 竖屏车机（屏幕高>宽）：dock 改为底部横条。 */
    @Volatile
    var dockAtBottom: Boolean = false
        private set

    /** dock 横条高度（竖屏；TouchRouter 按 y 分流）。 */
    @Volatile
    var dockHeightPx: Int = 0
        private set

    /** 应用区高度（竖屏；y < appHeightPx 属于应用屏）。 */
    @Volatile
    var appHeightPx: Int = 0
        private set

    /**
     * 应用屏超采样倍率（超采样应用屏的渲染倍率；1=未创建超采样屏）。
     * 触摸坐标按当前**活动应用屏**的倍率换算（见 [activeAppScale]）。
     */
    @Volatile
    var hiScale: Float = 1f
        private set

    /** 超采样应用屏（横屏车机时存在；竖屏车机为 -1，全部走原生）。 */
    @Volatile
    var appHiDisplayId: Int = -1
        private set

    /** 主区桌面（GridActivity）所在屏：横屏=超采样屏（保持既有观感），竖屏=原生屏。 */
    @Volatile
    var gridDisplayId: Int = -1
        private set

    /** 视频流画布尺寸（桌面壁纸切片的 aspectFill 映射基准）。 */
    @Volatile
    var streamWidthPx: Int = 0
        private set

    @Volatile
    var streamHeightPx: Int = 0
        private set

    // ---- 地图屏（仪表盘分屏）：地图应用专属屏，分屏=tile 尺寸，全屏=应用区尺寸 ----

    /** 地图屏 displayId（横屏车机且受信屏模式创建；-1 = 无）。 */
    @Volatile
    var mapDisplayId: Int = -1
        private set

    /** 仪表盘↔桌面滑动进度（0=tile 原位，1=完全滑出）：滑动期间抑制 tile 事件注入。 */
    @Volatile
    var tileSlideFraction: Float = 0f
        private set

    /** 由 GridActivity 随 pager 滑动驱动 tile 视觉位移。 */
    fun setTileSlide(fraction: Float) {
        tileSlideFraction = fraction
        composer?.tileSlideFraction = fraction
    }

    /** 「显示」设置实时切换目标帧率（合成器未创建时静默——attach 时会从偏好读取）。 */
    fun setStreamFps(fps: Int) {
        composer?.targetFps = fps
    }

    /** 地图屏 tile 尺寸（分屏形态的实际显示尺寸；与 DualDisplayComposer 的 SPLIT 绘制矩形一致）。 */
    @Volatile
    var mapTileWidth: Int = 0
        private set

    @Volatile
    var mapTileHeight: Int = 0
        private set

    /**
     * 仪表盘分屏把手中心（流像素，顶部原点；横屏=x、竖屏=y）。<0 = 未 attach。
     * 拖动中由 [previewDashSplit] 连续预览，松手 [commitDashSplit] 落定并派生
     * [mapTileWidth]/[mapTileHeight]。几何推导全部经 [dashSplitGeom] 纯函数。
     */
    @Volatile
    var dashSplitPos: Float = -1f
        private set

    /** 当前生效的分屏比例（attach 从偏好恢复，commit 写回）。 */
    @Volatile
    private var dashSplitFraction = DASH_SPLIT_DEFAULT_FRACTION

    /** 应用区尺寸（全屏形态的地图屏目标尺寸）。 */
    @Volatile
    var appAreaWidth: Int = 0
        private set

    @Volatile
    var appAreaHeight: Int = 0
        private set

    @Volatile
    var mapFullscreen: Boolean = false
        private set

    /** 地图屏当前逻辑尺寸（resize 幂等基线；0 = 未创建/已释放）。 */
    @Volatile
    private var mapDisplayW = 0

    @Volatile
    private var mapDisplayH = 0

    /** 原生应用屏逻辑尺寸（letterbox 黑边换算的屏尺寸事实源；创建后不变）。 */
    @Volatile
    private var appDisplayW = 0

    @Volatile
    private var appDisplayH = 0

    /** 超采样应用屏逻辑尺寸（同上）。 */
    @Volatile
    private var appHiDisplayW = 0

    @Volatile
    private var appHiDisplayH = 0

    /** 地图屏密度（创建时固定 200dpi，resize 不变——逻辑尺寸随像素缩放）。 */
    private const val MAP_DISPLAY_DPI = 200

    /** 仪表盘（分屏）模式是否激活。 */
    @Volatile
    var dashboardMode: Boolean = false
        private set

    /** 仪表盘模式变化监听（GridActivity 切换布局）。 */
    private val dashboardListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.add(listener)
    }

    fun removeDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.remove(listener)
    }

    private fun notifyDashboard() {
        dashboardListeners.forEach { runCatching { it(dashboardMode) } }
    }

    /** 地图 tile 布局变化监听（隐藏/分屏/全屏）。 */
    private val mapLayoutListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.add(listener)
    }

    fun removeMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.remove(listener)
    }

    private fun notifyMapLayout() {
        mapLayoutListeners.forEach { runCatching { it(mapFullscreen) } }
    }

    /** 地图应用白名单（优先序：车机版高德 → 车机版百度 → 手机高德 → 手机百度 → 腾讯）。 */
    val MAP_PACKAGES_PRIORITY =
        listOf(
            "com.autonavi.amapauto",
            "com.baidu.naviauto",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.tencent.map",
        )

    fun isMapPackage(packageName: String): Boolean = packageName in MAP_PACKAGES_PRIORITY

    /**
     * 已安装的默认地图（用户显式设置优先——DefaultMapPrefs；否则按优先序取
     * 第一个已安装者；null = 无任何地图应用）。
     *
     * ⚠️ 本函数**只是**对 [DefaultMapPrefs.resolve] 的转调，而后者不得反过来调用
     * 本函数（会无限递归 → StackOverflowError）。优先序扫描已在 resolve 内部就地
     * 实现，正确性由它负责；此处保留仅为对外 API 语义（VirtualLauncher 是调用方
     * 的主要入口）。2026-10-18 定谳。
     */
    fun defaultMapComponent(context: Context): ComponentName? = DefaultMapPrefs.resolve(context)

    /**
     * 桌面所在屏相对视频流的渲染倍率（原生屏=1，超采样屏=hiScale）。
     * 桌面内绘制「全流画布壁纸切片」时的尺寸/偏移换算用。
     */
    fun gridScale(): Float = if (gridDisplayId >= 0 && gridDisplayId == appHiDisplayId) hiScale else 1f

    private var dockDisplay: VirtualDisplay? = null
    private var appDisplay: VirtualDisplay? = null
    private var appHiDisplay: VirtualDisplay? = null

    /** 回退形态的地图屏句柄（受信形态经 shell 创建，无本进程句柄）。 */
    private var mapDisplay: VirtualDisplay? = null
    private var composer: DualDisplayComposer? = null

    /** 当前活动应用屏 ID（TouchRouter 注入目标）。 */
    fun activeAppDisplayId(): Int = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) appHiDisplayId else appDisplayId

    /** 当前活动应用屏的坐标倍率（超采样屏=hiScale，原生屏=1）。 */
    fun activeAppScale(): Float = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) hiScale else 1f

    /** 应用区在输出画面中的矩形（顶部原点）——叠加卡片定位用。 */
    fun appAreaRect(): RectF? {
        if (streamWidthPx <= 0 || streamHeightPx <= 0) return null
        return if (dockAtBottom) {
            RectF(0f, 0f, streamWidthPx.toFloat(), appHeightPx.toFloat())
        } else {
            RectF(dockWidthPx.toFloat(), 0f, streamWidthPx.toFloat(), streamHeightPx.toFloat())
        }
    }

    /** 画布 UI 密度**缩放**（dpi/160；与 dock 屏同一基准：420dpi @ 540 短边）。 */
    fun uiDensity(): Float = ((420f * minOf(streamWidthPx, streamHeightPx) / 540f).coerceIn(120f, 1680f)) / 160f

    /** 指定虚拟屏的逻辑像素尺寸（letterbox 探测缺 mMaxBounds 时的兜底事实源）。 */
    fun hintDisplaySize(displayId: Int): Pair<Int, Int>? =
        when (displayId) {
            appDisplayId -> if (appDisplayW > 0) appDisplayW to appDisplayH else null
            appHiDisplayId -> if (appHiDisplayW > 0) appHiDisplayW to appHiDisplayH else null
            mapDisplayId -> if (mapDisplayW > 0) mapDisplayW to mapDisplayH else null
            else -> null
        }

    /** 挂/摘合成器上的叠加卡片。 */
    fun setOverlayCard(card: OverlayCard?) {
        composer?.overlayCard = card
    }

    /** 会话上下文（letterbox 重启后复探要用；未 attach 时为 null）。 */
    fun appContext(): Context? = appContextRef

    /**
     * 挂载：建 GL 合成器 → 就绪回调里建两块虚拟屏 → 拉起 Dock/Grid。
     * [encoderSurface] 为编码器输入 Surface；返回是否全部成功。
     */
    @SuppressLint("WrongConstant")
    fun attach(
        context: Context,
        encoderSurface: Surface,
        totalWidth: Int,
        totalHeight: Int,
        densityDpi: Int,
        log: (String) -> Unit,
    ): Boolean {
        detach()
        // 新会话：「本次会话不再提示」等用户级决定作废
        LetterboxHintController.resetSession()
        // 「强制应用横屏」覆盖**必须先于任何应用启动**写入（2026-10-23 奥迪 3315 真机
        // 定谳）：`am compat enable` 会立即杀掉目标应用（覆盖只能在新进程生效）——
        // 若写在应用拉起之后，桌面上刚 spawn 的地图/桌面进程会被当场杀死，虚拟屏
        // 只剩静态壁纸，车机收到的画面永远不变、不切换 CarPlay UI（表现为「推流中
        // 但车机还在原界面」）。写入在配对起步（SessionController 端点处理）就入队，
        // attach 这里只 drain——通常已完成（~0ms）。注意别在这里同步跑 applyAll：
        // 屏幕口 keepAlive 帧只从 drainEncoder 循环发出，而 drain 线程在本方法返回
        // 后才启动——这里每阻塞 1s 都直接吃掉「RECORD→首条 keepAlive」的 ~10s
        // 拆链预算（奥迪实测阈值）。
        if (!AppOrientationCompat.awaitPending()) {
            log("ORIENTATION: ⚠ 覆盖写入未在 6s 内排空——应用可能带着旧方向配置启动（后台写完后首个启动的应用会被杀一次）")
        }
        val density = densityDpi / 160f
        // dock 栏宽度缩放（「显示」面板 50%–100%，下次连接起效）：厚度像素与 dock 屏
        // 密度**同乘**比例——内容 dp 布局不变、渲染进更少像素，整条 dock（图标/时钟/
        // 文字）等比变窄；只缩厚度不缩密度会让内容溢出或留白。下限 80px 同乘缩放防
        // 极端画布下压没。密度下限 120 防 ROM 拒绝异常密度。
        val dockScale = DockPrefs.widthPercent(context) / 100f
        val thicknessPx =
            (DOCK_WIDTH_DP * density * dockScale)
                .toInt()
                .coerceAtLeast((80 * dockScale).toInt().coerceAtLeast(1))
        val dockDensityDpi = (densityDpi * dockScale).toInt().coerceAtLeast(120)
        // 车机方向决定 dock 位置：横屏 → 左侧竖条；竖屏 → 底部横条（2026-10-11 用户要求）
        dockAtBottom = totalHeight > totalWidth
        streamWidthPx = totalWidth
        streamHeightPx = totalHeight
        val appWidth: Int
        if (dockAtBottom) {
            dockWidthPx = totalWidth
            dockHeightPx = thicknessPx
            appHeightPx = (totalHeight - thicknessPx).coerceAtLeast(200)
            appWidth = totalWidth
        } else {
            dockWidthPx = thicknessPx
            dockHeightPx = totalHeight
            appHeightPx = totalHeight
            appWidth = (totalWidth - thicknessPx).coerceAtLeast(200)
        }
        appAreaWidth = appWidth
        appAreaHeight = appHeightPx
        // 竖屏应用屏密度：窗口逻辑宽度目标 ≈ 手机竖屏宽度（LETTERBOX_TARGET_DP）。
        // 错用 dock 的 densityDpi（随画布分辨率等比、非手机密度）会把窗口压到 ~206dp——文字观感大一倍
        // （2026-10-14 竖屏实测：应用与桌面字号明显偏大的根因）。
        val appPortraitDensity =
            (appWidth / LETTERBOX_TARGET_DP * 160f).toInt().coerceIn(160, 640)
        // 地图屏：按应用区全尺寸创建，分屏/全屏间动态 resize（见 resizeMapDisplay）。
        // tile 尺寸 = SPLIT 绘制矩形，边距统一用契约值 CARD_MARGIN_PX（勿再裸写数字）。
        // 分屏比例由用户拖动把手调整（持久化偏好）；几何推导集中在 [dashSplitGeom]
        // 纯函数（单测钉死四方一致性）。tile 主轴长（横=宽、竖=高）=比例×可用长度。
        dashSplitFraction =
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getFloat(PREF_KEY_DASH_SPLIT, DASH_SPLIT_DEFAULT_FRACTION)
                .let(::clampDashSplitFraction)
        if (dockAtBottom) {
            val geom = dashSplitGeom(appHeightPx, dashSplitFraction)
            mapTileWidth = appWidth - CARD_MARGIN_PX * 2
            mapTileHeight = geom.tileLen
            dashSplitPos = geom.splitCenter
        } else {
            val geom = dashSplitGeom(appWidth, dashSplitFraction)
            mapTileWidth = geom.tileLen
            mapTileHeight = appHeightPx - CARD_MARGIN_PX * 2
            // 横屏应用区从 dock 右缘起（竖屏 x 原点即流原点，无偏移）
            dashSplitPos = geom.splitCenter + dockWidthPx
        }
        // 横屏应用屏的密度选择（2026-10-11 实测标定）：
        // 竖屏锁定应用（如头条）在横屏屏上被系统 letterbox 成窄条窗口，窗口逻辑宽度
        // = 屏高px/letterbox宽高比，再由屏幕密度换算 dp。密度偏高 → 窗口只有 ~343dp，
        // 应用呈现「大字大搜索框」的窄屏布局；密度 ~200 → 窗口 ~411dp ≈ 手机宽度，
        // 应用呈现紧凑手机布局（截图验证）。目标：letterbox 窗口 ≈ LETTERBOX_TARGET_DP。
        val landscapeApp = appWidth > appHeightPx && !dockAtBottom
        // 超采样：部分应用（头条实测）按「手机状态栏物理像素」预留顶部区域
        // （28dp@560dpi=98px 原始像素，与显示密度无关），在 540px 高的流里占 18%、
        // 手机上仅 3.5%——无法去除（应用内部绘制），只能靠「渲染更多像素再缩回」
        // 把固定像素的占比压小。×2：98px→49px（9%）；超采样高度上限 1620px。
        val appScaleFactor =
            if (landscapeApp) {
                minOf(APP_SUPERSAMPLE, SUPERSAMPLE_MAX_HEIGHT.toFloat() / appHeightPx)
            } else {
                1f
            }
        val appHiWidth = (appWidth * appScaleFactor).toInt()
        val appHiHeight = (appHeightPx * appScaleFactor).toInt()
        this.hiScale = appScaleFactor
        val appHiDensity =
            if (landscapeApp) {
                val letterboxWindowPx = appHiHeight / LETTERBOX_ASPECT_RATIO
                (letterboxWindowPx / LETTERBOX_TARGET_DP * 160f).toInt().coerceIn(180, 640)
            } else {
                appPortraitDensity
            }
        // 原生应用屏密度：与超采样屏保持**相同逻辑尺寸**（应用布局不受所在屏影响，
        // 超采样只是渲染分辨率差异）。横屏：原生 1034×540 @~200dpi = 827×432dp，
        // 与超采样屏（1551×810 @301dpi）的 824×431dp 等值；竖屏车机：直接用流密度。
        val appNativeDensity =
            if (landscapeApp) {
                val hiLogicalHeightDp = appHiHeight * 160f / appHiDensity
                (appHeightPx * 160f / hiLogicalHeightDp).toInt().coerceIn(160, 640)
            } else {
                appPortraitDensity
            }

        // 等待 Shizuku UserService 绑定（受信虚拟屏需要 shell 权限创建）
        var svc = CarInputClient.service
        if (svc == null &&
            ShizukuGate
                .state(context) ==
            ShizukuGate.State.READY
        ) {
            val deadline = SystemClock.uptimeMillis() + 2_500L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                CarInputClient
                    .ensure(context)
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val useTrusted = svc != null
        log("LAUNCHER: 虚拟屏模式=${if (useTrusted) "受信屏(shell)" else "应用屏(回退)"}")

        val composer = DualDisplayComposer(encoderSurface, totalWidth, totalHeight, dockAtBottom)
        composer.targetFps =
            DisplayPrefs
                .fps(context)
        this.composer = composer
        val displaysCreated = arrayOf(false)
        composer.onSurfacesReady = { dockSurface, appSurface, appHiSurface, mapSurface ->
            // 优先：Shizuku shell 创建受信虚拟屏（VIRTUAL_DISPLAY_FLAG_TRUSTED）——
            // 允许第三方应用的新任务落在该屏；解决「应用内跳转的任务被重定向到手机主屏」。
            var viaService = false
            if (useTrusted) {
                runCatching {
                    // VirtualDeviceManager 路线的目标车机：本次链路对端 BT MAC（自动模式下
                    // 无既选目标，实际对端是唯一可信来源）。空 = VDM 关闭，全部走 legacy。
                    svc!!.setCompanionDevice(BtSessionGate.lastPeerAddress ?: "")
                    // 残留清理：进程重启/上轮会话遗留的同名虚拟屏不释放会一直占位
                    // （新宽度/新 surface 不生效——2026-10-11 dock 改宽后视觉无变化的根因）
                    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    dm.displays
                        .filter {
                            it.name == "CarPlayDock" ||
                                it.name == "CarPlayApp" ||
                                it.name == "CarPlayAppHi" ||
                                it.name == "CarPlayMap"
                        }.forEach { stale ->
                            runCatching { svc!!.releaseDisplay(stale.displayId) }
                                .onSuccess { log("LAUNCHER: 已释放残留虚拟屏 ${stale.name}#${stale.displayId}") }
                        }
                    Thread.sleep(150) // 等系统完成注销
                    // dock 屏不声明受信：只承载自有 DockActivity，杜绝第三方任务逃逸上来
                    val dockId =
                        svc!!.createDisplay(
                            "CarPlayDock",
                            dockWidthPx,
                            dockHeightPx,
                            dockDensityDpi,
                            dockSurface,
                            false,
                        )
                    val appId =
                        svc!!.createDisplay(
                            "CarPlayApp",
                            appWidth,
                            appHeightPx,
                            appNativeDensity,
                            appSurface,
                            true,
                        )
                    // 超采样屏（竖屏车机无、应用侧回退时也无）
                    val appHiId =
                        if (appHiSurface != null) {
                            svc!!.createDisplay(
                                "CarPlayAppHi",
                                appHiWidth,
                                appHiHeight,
                                appHiDensity,
                                appHiSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    // 地图屏（横竖屏都创建；按应用区全尺寸创建，分屏/全屏间动态 resize）
                    val mapId =
                        if (mapSurface != null) {
                            svc!!.createDisplay(
                                "CarPlayMap",
                                appWidth,
                                appHeightPx,
                                200,
                                mapSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    if (dockId >= 0 && appId >= 0) {
                        dockDisplayId = dockId
                        appDisplayId = appId
                        appDisplayW = appWidth
                        appDisplayH = appHeightPx
                        if (appHiId >= 0) {
                            appHiDisplayId = appHiId
                            appHiDisplayW = appHiWidth
                            appHiDisplayH = appHiHeight
                        } else if (appHiSurface != null) {
                            log("LAUNCHER: 超采样屏创建失败——本次会话全部走原生屏")
                        }
                        if (mapId >= 0) {
                            mapDisplayId = mapId
                            // 创建尺寸=应用区全尺寸（resizeMapDisplay 的幂等基线）
                            mapDisplayW = appWidth
                            mapDisplayH = appHeightPx
                        } else if (mapSurface != null) {
                            log("LAUNCHER: 地图屏创建失败——仪表盘无地图 tile")
                        }
                        viaService = true
                    } else {
                        // 逐屏留痕：EMUI 等 ROM 对 shell 侧 createVirtualDisplay 是**静默
                        // 返回 null**（不抛异常）——整体回退时不打这条，日志里只剩
                        // 「回退」两个字，无法判断是哪块屏被拒（华为 Mate30Pro/EMUI12
                        // 2026-10-20 实测定位）
                        if (dockId < 0) {
                            log("LAUNCHER: 受信屏 CarPlayDock 创建失败（createVirtualDisplay 返回 null——ROM 限制 shell 侧虚拟屏？）")
                        }
                        if (appId < 0) {
                            log("LAUNCHER: 受信屏 CarPlayApp 创建失败（同上）")
                        }
                        // 半成功：释放已建的
                        if (dockId >= 0) runCatching { svc!!.releaseDisplay(dockId) }
                        if (appId >= 0) runCatching { svc!!.releaseDisplay(appId) }
                        if (appHiId >= 0) runCatching { svc!!.releaseDisplay(appHiId) }
                        if (mapId >= 0) runCatching { svc!!.releaseDisplay(mapId) }
                        // 本次整体回退应用侧屏：VDM 会话不留到下轮（下轮 attach 会重设）
                        runCatching { svc!!.closeVirtualDevice() }
                    }
                }.onFailure { log("LAUNCHER: 受信屏创建异常 ${it.message}") }
            }
            if (viaService) {
                displaysViaService = true
                log(
                    "LAUNCHER: 三屏创建(受信) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor)",
                )
                // 会话建立：为「强制应用横屏」名单写入兼容覆盖——**已提前到 attach 开头**
                // （先写覆盖再拉应用，见 attach 开头注释；此处不再重复入队）。
                // 音乐卡数据源前置：会话建立是「Shizuku 一定可用」的可靠时机，
                // 补一次通知使用权（幂等；已授权直接返回）。
                // 覆盖「App 启动时 Shizuku 未就绪、之后才就绪」的整条路径。
                Bg.run("notification-access-session") {
                    NotificationAccess.ensureGranted(context.applicationContext, log)
                }
                // 覆盖写入是异步的（probe 内部会 awaitPending 等它排空）；
                // 应用若已在运行且此前被当成竖窗恢复，这里就能发现并给出提示。
                LetterboxHintController.scheduleProbe(context, "会话建立")
            } else {
                // 回退：应用侧 PUBLIC 屏（应用内跳转任务会逃逸到手机主屏，尽量可用的降级形态）
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dockDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayDock",
                        dockWidthPx,
                        dockHeightPx,
                        dockDensityDpi,
                        dockSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                appDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayApp",
                        appWidth,
                        appHeightPx,
                        appNativeDensity,
                        appSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                if (appHiSurface != null) {
                    appHiDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayAppHi",
                            appHiWidth,
                            appHiHeight,
                            appHiDensity,
                            appHiSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    appHiDisplayId = appHiDisplay?.display?.displayId ?: -1
                    appHiDisplayW = appHiWidth
                    appHiDisplayH = appHiHeight
                }
                // 地图屏同样建（应用侧 PUBLIC）：地图启动走 shell `am start --display`，
                // 不需要受信；resize 用应用侧公开 API（见 resizeMapDisplay）。
                // 回退形态曾完全不建地图屏——受信创建在 EMUI12 上静默失败后，仪表盘
                // 就永远没有地图 tile（2026-10-20 华为 Mate30Pro 实测）。
                if (mapSurface != null) {
                    mapDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayMap",
                            appWidth,
                            appHeightPx,
                            MAP_DISPLAY_DPI,
                            mapSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    val fallbackMapId = mapDisplay?.display?.displayId ?: -1
                    if (fallbackMapId >= 0) {
                        mapDisplayId = fallbackMapId
                        mapDisplayW = appWidth
                        mapDisplayH = appHeightPx
                    } else {
                        log("LAUNCHER: 地图屏（回退）创建失败——仪表盘无地图 tile")
                    }
                }
                dockDisplayId = dockDisplay?.display?.displayId ?: -1
                appDisplayId = appDisplay?.display?.displayId ?: -1
                appDisplayW = appWidth
                appDisplayH = appHeightPx
                log(
                    "LAUNCHER: 三屏创建(回退) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor) " +
                        "map=$mapDisplayId(${mapDisplayW}x$mapDisplayH)——受信屏不可用，已回退应用侧 PUBLIC 屏",
                )
            }
            displaysCreated[0] = dockDisplayId >= 0 && appDisplayId >= 0
        }
        return try {
            composer.start(
                dockWidthPx,
                dockHeightPx,
                appWidth,
                appHeightPx,
                if (landscapeApp) appHiWidth else 0,
                if (landscapeApp) appHiHeight else 0,
                appWidth,
                appHeightPx,
            )
            // 分屏把手位置推给合成器（覆盖默认中线公式；拖动时 previewDashSplit 持续更新）
            composer.setTileEdgeOverride(dashSplitPos - DASH_GAP_PX / 2f)
            if (!displaysCreated[0]) {
                log("LAUNCHER: 虚拟屏创建失败")
                detach()
                return false
            }
            // 应用屏方向策略：**忽略应用请求的方向**，虚拟屏保持创建时的形态。
            // 竖屏应用（头条等）在横屏车机上会请求 portrait，系统若照办会把整个
            // 虚拟屏旋转（dumpsys 里 rotation 0→3、逻辑尺寸 2251×1152→1152×2251），
            // 而合成器按整幅映射、触摸按未旋转坐标换算 —— 表现为「分辨率变了 +
            // 点击漂移、点不到目标」，退回桌面也仍是旋转态（2026-10-18 实测）。
            // 缩在 letterbox 里（见下方 aspectRatio）才是车机该有的形态。
            applyIgnoreOrientationRequest(appDisplayId, appHiDisplayId, mapDisplayId)
            // 放宽竖屏应用的 letterbox 窗口（默认窄条 → 手机宽度）；断开时还原（detach）
            Shell.exec("cmd window set-letterbox-style --aspectRatio $LETTERBOX_ASPECT_RATIO")
            log(
                "LAUNCHER: letterbox 宽高比=$LETTERBOX_ASPECT_RATIO " +
                    "appDensity=${appNativeDensity}dpi appHiDensity=${appHiDensity}dpi",
            )
            // 自启组件必须写成「applicationId/绝对类名」：`am -n` 只认带斜杠的规范，
            // 且相对类名按 applicationId 展开——.rd 调试包（applicationId 后缀）下
            // `pkg/.launcher.DockActivity` 会指向不存在的类、纯类名直接被拒
            // （Bad component name），两种写法都让 dock/grid 启动失败、车机黑屏
            // 0fps（2026-10-21 .rd 包实测）。绝对类名取自 Class 引用，随重构自动跟随。
            val selfPkg = context.packageName
            val dockOk = startOn(dockDisplayId, "$selfPkg/${DockActivity::class.java.name}")
            gridDisplayId = if (appHiDisplayId >= 0) appHiDisplayId else appDisplayId
            val gridOk = startOn(gridDisplayId, "$selfPkg/${GridActivity::class.java.name}")
            appContextRef = context.applicationContext
            log("LAUNCHER: dock=$dockOk grid=$gridOk gridDisplay=$gridDisplayId")
            // 桌面在超采样屏时默认活动屏 = 超采样屏（启动即显示桌面）
            if (appHiDisplayId >= 0) composer.requestActiveApp(true)
            startShizukuHealer()
            // 暗幕组件就绪（常亮+暗幕功能；主界面开关控制启停）
            ScreenDimmer
                .attach(context)
            dockOk && gridOk
        } catch (error: IllegalStateException) {
            log("LAUNCHER: GL 合成器启动失败 - ${error.message}")
            detach()
            false
        }
    }

    /** 受信屏标记：true 时虚拟屏由 shell UserService 持有，须经服务释放。 */
    @Volatile
    private var displaysViaService = false

    fun detach() {
        ScreenDimmer
            .detach()
        // 还原方向策略（false = 跟随应用请求，系统默认）。
        //
        // **必须异步且不读回**：detach 由 disconnect() 在 UI 线程触发，而这里的每一次
        // `Shell.exec` 都是 Shizuku newProcess + 进程等待（单条 100ms 量级、异常时可达
        // 数秒超时）。三块屏 × (set+get) = 6 次往返足以触发 ANR（2026-10-18 实测：
        // 手动断开后连续弹出「应用无响应」）。改为一次性投递到后台，不再读回——
        // 这里是**清理**路径，失败也只是残留一个方向策略，不影响会话正确性；
        // 建立路径（applyIgnoreOrientationRequest）才需要读回校验。
        //
        // 执行时机：投递后立即释放虚拟屏，但后台任务与释放是并发的——为保证命令
        // 命中的是**尚存活的 displayId**，这里先同步取好 id 字符串并在投递时即排队；
        // 若已被释放（id 失效）命令只会报错，不影响后续会话（下轮创建时会重新设置）。
        val orientationIds = intArrayOf(appDisplayId, appHiDisplayId, mapDisplayId).filter { it >= 0 }
        if (orientationIds.isNotEmpty()) {
            Bg.run("orientation-cleanup") {
                orientationIds.forEach { runCatching { setIgnoreOrientationRequest(it, false, verify = false) } }
            }
        }
        if (displaysViaService) {
            val svc = CarInputClient.service
            if (svc != null) {
                if (dockDisplayId >= 0) runCatching { svc.releaseDisplay(dockDisplayId) }
                if (appDisplayId >= 0) runCatching { svc.releaseDisplay(appDisplayId) }
                if (appHiDisplayId >= 0) runCatching { svc.releaseDisplay(appHiDisplayId) }
                // 地图屏同样由 shell 持有：不释放会在断开后残留（重装后由启动检测兜底发现）
                if (mapDisplayId >= 0) runCatching { svc.releaseDisplay(mapDisplayId) }
                // VDM 会话收口：余屏兜底释放 → device.close → 解除本次创建的伴侣关联
                // （既有关联复用后原样保留）。幂等：无会话时返回 true。
                runCatching { svc.closeVirtualDevice() }
            }
            displaysViaService = false
        }
        appContextRef = null
        watchdogActive = false
        // 还原 letterbox 宽高比为系统默认（不把会话调参留在系统里）。
        // **必须后台执行**：这是一条 shell 命令（超时上限 6s），而 detach 可能被主线程
        // 或 Shizuku 回调线程触发；2026-10-22 K60E 日志里就有「SHELL: ⚠ 主线程调用
        // shell（第 1 次）cmd=cmd window reset-letterbox-style aspectRatio」。
        Bg.run("letterbox-reset") {
            runCatching { Shell.exec("cmd window reset-letterbox-style aspectRatio") }
        }
        runCatching { composer?.stop() }
        composer = null
        runCatching { dockDisplay?.release() }
        dockDisplay = null
        runCatching { appDisplay?.release() }
        appDisplay = null
        runCatching { appHiDisplay?.release() }
        appHiDisplay = null
        runCatching { mapDisplay?.release() }
        mapDisplay = null
        dockDisplayId = -1
        appDisplayId = -1
        appHiDisplayId = -1
        gridDisplayId = -1
        mapDisplayId = -1
        mapTileWidth = 0
        mapTileHeight = 0
        dashSplitPos = -1f
        mapDisplayW = 0
        mapDisplayH = 0
        appDisplayW = 0
        appDisplayH = 0
        appHiDisplayW = 0
        appHiDisplayH = 0
        appAreaWidth = 0
        appAreaHeight = 0
        mapFullscreen = false
        // 提示卡片属于会话状态：拆屏时一并清掉（合成器已停，卡片纹理随 GL 上下文销毁）
        LetterboxHintController.clear()
        if (dashboardMode) {
            dashboardMode = false
            notifyDashboard()
        }
        dockWidthPx = 0
        dockHeightPx = 0
        appHeightPx = 0
        dockAtBottom = false
        hiScale = 1f
        streamWidthPx = 0
        streamHeightPx = 0
    }

    private fun startOn(
        displayId: Int,
        component: String,
    ): Boolean {
        if (displayId < 0) return false
        val result =
            Shell.exec(
                "am start -n $component --display $displayId --windowingMode 1",
                timeoutMs = 8000,
            )
        return result.ok && result.output.contains("Starting:", ignoreCase = true)
    }

    /** 回桌面：GridActivity 任务带回前台（后台应用保留）；合成器切回桌面所在屏。
     *
     *  shell 启动必须异步：调用方（dock 回桌面/桌面 pager 落定）都在主线程，
     *  同步等 shell 最坏 8s 超时足以 ANR（2026-10-21 实测触发主线程调用告警）。 */
    fun goGrid() {
        val grid = if (gridDisplayId >= 0) gridDisplayId else appDisplayId
        val pkg = appContextRef?.packageName ?: return
        Bg.run("go-grid") {
            if (!startOn(grid, "$pkg/${GridActivity::class.java.name}")) Log.w(TAG, "回桌面失败")
        }
        composer?.requestActiveApp(appHiDisplayId >= 0 && grid == appHiDisplayId)
        // 桌面上的提示没有意义（顶层不是第三方应用），直接收起
        LetterboxHintController.clear()
    }

    // ---- 仪表盘（分屏）与 home 键模式循环 ----
    //
    // home 键语义（用户定义）：应用 → 仪表盘；桌面 → 仪表盘；仪表盘 → 桌面。
    // 仪表盘 = 应用区左半为地图 tile（地图屏 resize 至 tile 尺寸）、右半为
    // GridActivity 的音乐卡；地图任务常驻地图屏，全屏 ⇄ 分屏只 resize 不迁移，
    // 导航等应用状态不丢。

    /**
     * dock 的 ⌂ 键：模式循环（仪表盘 ⇄ 桌面；应用/全屏地图 → 仪表盘）。
     * 注意必须先复位 mapFullscreen——路由闸门依赖它，漏复位会把桌面触摸
     * 继续送进已隐藏的地图屏（实测：桌面无法点击而日志注入成功的根因）。
     */
    fun onHomePressed() {
        when {
            mapFullscreen -> {
                setMapFullscreen(false)
                showDashboard()
            }

            dashboardMode -> showDesktop()
            else -> showDashboard()
        }
    }

    /** 进入仪表盘：地图屏缩回 tile、默认地图占屏（缺则启动）、桌面切分屏布局。 */
    fun showDashboard() {
        syncDashboard(true)
        goGrid()
        composer?.requestActiveApp(appHiDisplayId >= 0 && gridDisplayId == appHiDisplayId)
        logSink?.invoke("LAUNCHER: 仪表盘模式（地图 tile + 音乐卡）")
    }

    /**
     * 仪表盘模式唯一对账口：置 dashboardMode → 通知（dock 图标/GridActivity pager）
     * → 进入时补地图任务并切 SPLIT，退出时隐藏 tile。
     * pager 滑动过程只做 tile 位移预览（showMapTile），状态一律在落定（IDLE）时经此对账。
     */
    fun syncDashboard(dashboard: Boolean) {
        val changed = dashboardMode != dashboard
        dashboardMode = dashboard
        if (changed) notifyDashboard()
        if (dashboard) {
            Bg.run("dashboard-enter") {
                ensureMapTask()
                if (mapFullscreen) setMapFullscreen(false)
                // 期间用户可能已点开普通应用（launchApp 会置 HIDDEN 并退出仪表盘）：
                // 复查模式，别把地图层又盖回去
                if (dashboardMode) {
                    resizeMapDisplay(fullscreen = false)
                    composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
                }
            }
        } else {
            composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
        }
    }

    /** 滑动预览：仅切 tile 可见（不动 dashboardMode/监听），落定后由 syncDashboard 对账。 */
    fun showMapTile() {
        composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
    }

    /** 回普通桌面：隐藏地图层并退出仪表盘，桌面恢复全宽图标网格（地图任务保留）。 */
    fun showDesktop() {
        syncDashboard(false)
        goGrid()
        logSink?.invoke("LAUNCHER: 桌面模式（图标网格）")
    }

    /**
     * 地图屏全屏 ⇄ 分屏：动态 resize 地图屏到目标尺寸（全屏=应用区、分屏=tile 显示尺寸），
     * 屏上地图应用由系统派发配置变更并 relayout（任务不迁移，导航等状态天然连续）。
     * 合成器同步切换布局：resize 后地图屏内容即目标区域内容，整幅映射、不再裁剪采样。
     */
    fun setMapFullscreen(fullscreen: Boolean) {
        if (mapDisplayId < 0 || mapFullscreen == fullscreen) return
        mapFullscreen = fullscreen
        resizeMapDisplay(fullscreen)
        composer?.setTileLayout(
            if (fullscreen) {
                DualDisplayComposer.TileLayout.FULLSCREEN
            } else {
                DualDisplayComposer.TileLayout.SPLIT
            },
        )
        notifyMapLayout()
        logSink?.invoke("LAUNCHER: 地图屏 ${if (fullscreen) "全屏" else "分屏"}")
        // 地图屏 resize 后 letterbox 内外框变了：全屏时重探，分屏 tile 太小不提示
        if (fullscreen) {
            appContextRef?.let {
                // resize 已生效，目标就是地图屏（不必等合成器切布局）
                LetterboxHintController.scheduleProbe(it, "地图全屏", displayIdHint = mapDisplayId)
            }
        } else {
            LetterboxHintController.clear()
        }
    }

    /**
     * 拖动中预览分屏位置：**只改合成器绘制矩形**（地图纹理整幅拉伸渲染，不动
     * buffer、不动虚拟屏）——拖动流畅、不触发地图应用配置变更。松手才
     * [commitDashSplit] 真实 resize。
     *
     * @param streamPos 把手中心（流像素，顶部原点；横屏 x / 竖屏 y），内部钳制
     */
    fun previewDashSplit(streamPos: Float) {
        val clamped = clampDashSplitStream(streamPos) ?: return
        composer?.setTileEdgeOverride(clamped - DASH_GAP_PX / 2f)
    }

    /**
     * 松手落定：钳制 → 更新把手位置与 tile 派生尺寸 → 真实 resize 地图屏（含
     * 消费端 buffer 同步）→ 持久化比例 → 通知（音乐卡壁纸切片重切）。
     *
     * @return 钳制后的把手中心（流像素）——UI 据此校正 margin，防显示与状态漂移
     */
    fun commitDashSplit(streamPos: Float): Float {
        val clamped = clampDashSplitStream(streamPos) ?: return dashSplitPos
        val geom =
            dashSplitGeom(
                splitMainLen(),
                (clamped - splitMainOffset() - CARD_MARGIN_PX - DASH_GAP_PX / 2f) / splitUsableLen(),
            )
        dashSplitPos = clamped
        dashSplitFraction = geom.fraction
        if (dockAtBottom) mapTileHeight = geom.tileLen else mapTileWidth = geom.tileLen
        composer?.setTileEdgeOverride(clamped - DASH_GAP_PX / 2f)
        if (!mapFullscreen) resizeMapDisplay(false)
        notifyMapLayout()
        appContextRef
            ?.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(PREF_KEY_DASH_SPLIT, dashSplitFraction)
            ?.apply()
        logSink?.invoke(
            "LAUNCHER: 仪表盘分屏落定 ${if (dockAtBottom) "地图高" else "地图宽"}=" +
                "${if (dockAtBottom) mapTileHeight else mapTileWidth}px（比例 " +
                String.format("%.2f", dashSplitFraction) + "）",
        )
        return clamped
    }

    /** 当前把手中心换算到应用屏像素（GridActivity 布局用；未 attach 返回 -1）。 */
    fun dashSplitScreenPos(): Float = if (dashSplitPos < 0) -1f else dashSplitPos * gridScale()

    /** 把手中心·应用屏像素（应用区坐标系，已扣 dock 偏移；把手 DOWN 起点，<0=未 attach）。 */
    fun dashHandleCenterScreen(): Float = if (dashSplitPos < 0) -1f else (dashSplitPos - splitMainOffset()) * gridScale()

    /** 音乐卡内容侧起始·应用屏像素（横=左缘、竖=顶缘；<0=未 attach）。 */
    fun dashMusicStartScreen(): Int =
        if (dashSplitPos < 0) {
            -1
        } else {
            ((dashSplitPos - splitMainOffset() + DASH_GAP_PX / 2f) * gridScale()).toInt()
        }

    /** [previewDashSplit] 的应用屏像素入参版（把手拖动直调，内部换回流像素）。 */
    fun previewDashSplitScreen(centerScreen: Float) {
        previewDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f))
    }

    /** [commitDashSplit] 的应用屏像素入参版；返回钳制后的把手中心（应用屏像素）。 */
    fun commitDashSplitScreen(centerScreen: Float): Float =
        (
            commitDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f)) -
                splitMainOffset()
        ) * gridScale()

    /**
     * 触点是否落在分屏把手条内（流像素坐标）——TouchRouter 粘性路由判定用：
     * 按下命中即整条触摸流固定路由应用屏（拖动中把手落后手指、坐标越界也不改投，
     * 否则跨屏重注会掐断拖拽并误触地图，2026-10-21 方案评审定谳）。
     */
    fun dashHandleHit(
        x: Float,
        y: Float,
    ): Boolean {
        if (dashSplitPos < 0 || !dashboardMode || mapFullscreen) return false
        val half = DASH_HANDLE_TOUCH_PX / 2f
        return if (dockAtBottom) {
            y in dashSplitPos - half..dashSplitPos + half
        } else {
            x in dashSplitPos - half..dashSplitPos + half
        }
    }

    /** 流坐标把手中心 → 应用区坐标系偏移（横屏减 dock 宽；竖屏 y 原点即流原点）。 */
    private fun splitMainOffset(): Float = if (dockAtBottom) 0f else dockWidthPx.toFloat()

    /** 主轴可用长度 = 应用区主轴 − 两端边距 − 中缝宽（与 dashSplitGeom 同式）。 */
    private fun splitUsableLen(): Int = (if (dockAtBottom) appAreaHeight else appAreaWidth) - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()

    /**
     * 钳制流坐标把手中心；未 attach 返回 null。
     * 边界必须按**比例钳制域**（20%~80%）换算——按 [边距, 边距+可用] 钳等于允许
     * 比例 [0,1]，卡片能被拉成一条线（2026-10-22 用户实测「拉到 0」根因）。
     */
    private fun clampDashSplitStream(streamPos: Float): Float? {
        val usable = splitUsableLen()
        if (usable <= 0) return null
        val base = splitMainOffset() + CARD_MARGIN_PX + DASH_GAP_PX / 2f
        val min = base + DASH_SPLIT_MIN_FRACTION * usable
        val max = base + DASH_SPLIT_MAX_FRACTION * usable
        return streamPos.coerceIn(min, max)
    }

    /**
     * resize 地图屏到目标布局尺寸（幂等：尺寸未变不调——每次 resize 都会触发屏上
     * 应用的配置变更/重布局，能免则免）。Shizuku 未绑定或调用失败时保持现尺寸，
     * 合成器整幅映射依旧成立（只是地图 UI 密度与可视区域暂不匹配）。
     */
    private fun resizeMapDisplay(fullscreen: Boolean) {
        val id = mapDisplayId
        if (id < 0) return
        val w = if (fullscreen) appAreaWidth else mapTileWidth
        val h = if (fullscreen) appAreaHeight else mapTileHeight
        if (w <= 0 || h <= 0 || (w == mapDisplayW && h == mapDisplayH)) return
        runCatching {
            val applied =
                if (displaysViaService) {
                    // 受信形态：shell 侧 resize
                    val svc = CarInputClient.service ?: return@runCatching
                    svc.resizeDisplay(id, w, h, MAP_DISPLAY_DPI)
                } else {
                    // 回退形态：屏由本应用创建，直接用应用侧公开 API——shell 对
                    // 他人创建的 display 没有 resize 权限（token 属主校验）
                    val display = mapDisplay ?: return@runCatching
                    display.resize(w, h, MAP_DISPLAY_DPI)
                    true
                }
            if (applied) {
                mapDisplayW = w
                mapDisplayH = h
                // 消费端 buffer 尺寸必须同步——否则新尺寸内容画进旧 buffer 左上区域
                // （tile 内容偏左、宽度只剩比例值、右侧全黑）
                composer?.resizeMapInput(w, h)
                logSink?.invoke(
                    "LAUNCHER: 地图屏 resize → ${w}x$h@${MAP_DISPLAY_DPI}dpi（${if (fullscreen) "全屏" else "分屏"}）",
                )
            }
        }
    }

    /**
     * 地图应用启动到地图屏（全屏形态）：resize 地图屏 → 启动 → 合成器全屏布局。
     * 地图应用之间的切换（高德 ↔ 百度）也在同一块屏上进行，旧任务被清（互斥）。
     */
    private fun launchMapOnMapDisplay(component: ComponentName): Boolean {
        dashboardMode = false
        notifyDashboard()
        setMapFullscreen(true)
        // 同屏已有其他地图任务时清掉（白名单互斥；默认地图任务保留复用）
        val ctx = appContextRef
        val defaultPkg = ctx?.let { defaultMapComponent(it)?.packageName }
        if (component.packageName != defaultPkg) {
            taskIdsOnDisplayPkg(component.packageName, mapDisplayId).forEach { taskId ->
                Shell.exec("am stack remove $taskId", timeoutMs = 4000)
            }
        }
        val result = startMapApp(component, mapDisplayId, forceNewTask = true)
        val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        logSink?.invoke(
            "LAUNCH(${component.packageName}): → 地图屏全屏 ok=$ok out=${result.output.take(80)}",
        )
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
            appContextRef?.let {
                LetterboxHintController.scheduleProbe(it, "地图启动", displayIdHint = mapDisplayId)
            }
        }
        return ok
    }

    /** 指定屏上指定包的任务 ID 列表（清屏用）。 */
    private fun taskIdsOnDisplayPkg(
        pkg: String,
        displayId: Int,
    ): List<String> = tasksOf(taskIdsByDisplayPkg(), pkg, displayId)

    /**
     * 在指定屏启动地图应用：按 [mapLaunchCandidates] 逐个候选尝试 `am start -n`。
     *
     * 车机版地图的显式主界面（高德 `MainMapActivity`、百度 `NaviAutoActivity`）在
     * 部分定制固件/版本上可能不存在，写死单点会让地图屏黑着且日志只有一句 `Starting:` 缺失。
     * 因此首个候选失败即回退到 launcher 解析出的 component，并把每次尝试记进日志。
     */
    private fun startMapApp(
        component: ComponentName,
        displayId: Int,
        forceNewTask: Boolean,
        extraArgs: String = "",
    ): Shell.Result {
        val suffix =
            (if (forceNewTask) " -f 0x18000000" else "") +
                (if (extraArgs.isEmpty()) "" else " $extraArgs")
        var last: Shell.Result? = null
        for (target in mapLaunchCandidates(component)) {
            val result = Shell.exec("am start -n $target --display $displayId$suffix", timeoutMs = 8000)
            val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (ok) return result
            logSink?.invoke(
                "LAUNCH(${component.packageName}): 入口 $target 启动失败（${result.output.take(80)}）——尝试下一个候选",
            )
            last = result
        }
        return last ?: Shell.exec("am start -n ${component.flattenToShortString()} --display $displayId$suffix", timeoutMs = 8000)
    }

    /**
     * 一次 `dumpsys activity activities` 解析出「displayId → 包 → 任务 ID 列表」。
     * 此前三个查询函数各自 dump 一遍（launchApp 一次启动最多跑 3 遍、每遍 4s 超时
     * 的 shell 命令）——统一从这里取快照复用。
     */
    private fun taskIdsByDisplayPkg(): Map<Int, Map<String, List<String>>> {
        val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
        var current = -1
        val out = HashMap<Int, HashMap<String, MutableList<String>>>()
        for (line in dump.lineSequence()) {
            displayRegex.find(line)?.let { current = it.groupValues[1].toInt() }
            val task = taskRegex.find(line) ?: continue
            out
                .getOrPut(current) { HashMap() }
                .getOrPut(task.groupValues[2]) { ArrayList() }
                .add(task.groupValues[1])
        }
        return out
    }

    private fun tasksOf(
        snapshot: Map<Int, Map<String, List<String>>>,
        pkg: String,
        displayId: Int,
    ): List<String> = snapshot[displayId]?.get(pkg).orEmpty()

    /**
     * 确保默认地图在地图屏：am start 对既有任务=带回前台（状态保留，导航不断），
     * 无任务=新启动。后台线程调用。
     */
    private fun ensureMapTask() {
        if (mapDisplayId < 0) {
            // 诊断（2026-10-18「重装后地图卡不渲染」）：此前静默 return，无法区分
            // 「没创建地图屏」与「地图屏已创建但没启动 app」
            logSink?.invoke("LAUNCHER: 地图屏未创建（mapDisplayId=-1）——跳过默认地图启动")
            return
        }
        val ctx =
            appContextRef ?: run {
                logSink?.invoke("LAUNCHER: appContext 未就绪——跳过默认地图启动")
                return
            }
        val component =
            defaultMapComponent(ctx) ?: run {
                logSink?.invoke("LAUNCHER: 未找到任何地图应用（白名单内）——地图 tile 空")
                return
            }
        // 不带 MULTIPLE_TASK：任务已在地图屏时等价于带回前台
        val result = startMapApp(component, mapDisplayId, forceNewTask = false)
        logSink?.invoke(
            "LAUNCHER: 默认地图 ${component.packageName} → 地图屏（display=$mapDisplayId）ok=" +
                (result.ok && result.output.contains("Starting:", ignoreCase = true)) +
                (if (!result.ok) " err=${result.output.take(120)}" else ""),
        )
    }

    /**
     * 启动/切换应用。
     * 目标屏按每应用超采样偏好路由（SupersamplePrefs）：
     *  - 超采样开启（或自动判定为竖屏应用）→ 超采样屏（1551×810 缩回，固定像素顶带占比小）
     *  - 超采样关闭（横屏/地图类）→ 原生屏（1:1 渲染，更清晰、GPU 开销 1/2.25）
     *
     * 本 ROM（MagicOS）实测行为：`am start --display X` 对已有任务只做"带到前台"，
     * 不落到 X 屏；`am task move` 静默无效；`am task remove` 不存在，清任务用
     * `am stack remove <taskId>`（本版本 RootTask id == taskId）。可靠做法：
     *  1) 目标屏已有该包任务 → 普通启动带回前台（复用，保留任务栈）
     *  2) 目标屏没有 → 先清掉该包在**另一个应用屏**上的任务（偏好切换后避免双实例），
     *     再 `-f 0x18000000`（NEW_TASK|MULTIPLE_TASK）在目标屏新建实例
     *
     * 已知局限（2026-10-10 实测）：应用在手机侧也有实例时，进程基准密度停留在
     * 手机屏（560dpi），车机屏渲染文字偏大（≈2.2 倍）。唯一根治是强制重启该进程，
     * 但会杀掉用户手机侧正在用的实例（不可接受）——故维持现状不处理。
     */
    fun launchApp(component: ComponentName): Boolean {
        if (appDisplayId < 0) return false
        // 会话建立时的「强制横屏」覆盖是异步写的，而 am compat 会立即杀掉目标应用：
        // 必须等队列排空再启动，否则应用刚起来就被覆盖写入杀掉（表现为闪退/落回手机屏）。
        // 执行器单线程 FIFO，正常情况下覆盖早已写完，这里立即返回。
        AppOrientationCompat.awaitPending()
        // 地图应用 → 地图屏全屏（任务常驻地图屏；从桌面 grid 点击同样去地图屏，
        // 与仪表盘共用同一任务，导航状态不丢）
        if (isMapPackage(component.packageName) && mapDisplayId >= 0) {
            return launchMapOnMapDisplay(component)
        }
        val ctx = appContextRef
        val wantHi =
            ctx != null &&
                appHiDisplayId >= 0 &&
                SupersamplePrefs.shouldSupersample(ctx, component)
        val targetDisplay = if (wantHi) appHiDisplayId else appDisplayId
        // 任务查询共用一次 dumpsys 快照（此前 onTarget + 清理两块屏最多连跑 3 遍 4s 超时命令）
        val tasks = taskIdsByDisplayPkg()
        val onTarget = tasksOf(tasks, component.packageName, targetDisplay).isNotEmpty()
        if (!onTarget) {
            // 偏好切换后清理另一块应用屏上的旧任务（不同屏双实例会各自维护状态，易混淆）
            val otherDisplay = if (targetDisplay == appHiDisplayId) appDisplayId else appHiDisplayId
            if (otherDisplay >= 0) {
                tasksOf(tasks, component.packageName, otherDisplay).forEach { taskId ->
                    Shell.exec("am stack remove $taskId", timeoutMs = 4000)
                    Log.i(TAG, "清理旧屏任务 pkg=${component.packageName} task=$taskId display=$otherDisplay")
                }
            }
        }
        var ok: Boolean
        val result: Shell.Result
        if (isMapPackage(component.packageName)) {
            // 地图屏不可用（mapDisplayId < 0）时的回退：仍按车机版主界面启动，
            // 但落到应用屏并强制全屏窗口模式。
            result = startMapApp(component, targetDisplay, forceNewTask = !onTarget, extraArgs = "--windowingMode 1")
            ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (!ok && onTarget) {
                val retry =
                    startMapApp(component, targetDisplay, forceNewTask = true, extraArgs = "--windowingMode 1")
                ok = retry.ok && retry.output.contains("Starting:", ignoreCase = true)
            }
        } else {
            val target = component.flattenToShortString()
            result =
                if (onTarget) {
                    Shell.exec("am start -n $target --display $targetDisplay", timeoutMs = 8000)
                } else {
                    // MULTIPLE_TASK：即使该应用在其他屏有任务，也强制在目标屏新建实例
                    Shell.exec("am start -n $target --display $targetDisplay -f 0x18000000", timeoutMs = 8000)
                }
            ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (!ok && onTarget) {
                // 复用路径失败（任务可能已被系统移走）：退回 MULTIPLE_TASK 新建
                val retry = Shell.exec("am start -n $target --display $targetDisplay -f 0x18000000", timeoutMs = 8000)
                ok = retry.ok && retry.output.contains("Starting:", ignoreCase = true)
            }
        }
        logSink?.invoke(
            "LAUNCH(${component.packageName}): hi=$wantHi disp=$targetDisplay onTarget=$onTarget ok=$ok " +
                "out=${result.output.take(80)}",
        )
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
            // 仪表盘/全屏地图中打开普通应用：退出仪表盘并隐藏地图层，
            // 否则 SPLIT 带区继续画在应用上面（实测地图 tile 盖住打开的应用）
            if (dashboardMode || mapFullscreen) {
                dashboardMode = false
                mapFullscreen = false
                notifyDashboard()
                composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
                logSink?.invoke("LAUNCHER: 退出仪表盘（普通应用已打开）")
            }
            // 合成器切到目标屏（超采样屏等其出帧再切；原生屏即时切换）
            composer?.requestActiveApp(targetDisplay == appHiDisplayId)
            // 应用已在运行、被系统按旧配置带回前台的情况：启动后探测一次是否仍
            // letterbox（提示卡只对「应用横屏」名单内的应用生效，其它应用不计）
            val ctx = appContextRef
            if (ctx != null) {
                LetterboxHintController.scheduleProbe(ctx, "应用启动", displayIdHint = targetDisplay)
            }
        } else {
            Log.w(TAG, "启动失败：${result.output.take(120)}")
        }
        return ok
    }

    // ICU 汉字→拼音转写（Android 内置，无需第三方拼音库）。
    // "Han-Latin" 输出带声调，追加 Latin-ASCII 去音调（gāo → gao）。
    // Transliterator 非线程安全，统一加锁访问。
    private val hanLatinLock = Any()
    private val hanLatin by lazy {
        android.icu.text.Transliterator
            .getInstance("Han-Latin; Latin-ASCII")
    }

    private fun toLatin(text: String): String =
        synchronized(hanLatinLock) {
            runCatching { hanLatin.transliterate(text) }.getOrDefault(text)
        }

    /** 排序键：全名转拼音小写、仅保留字母数字（数字 < 字母，即 # 组自然在前）。 */
    private fun pinyinKey(label: String): String = toLatin(label).lowercase().filter { it.isLetterOrDigit() }

    /** 拼音排序键（设置页等外部列表按字母分组排序用）。 */
    fun sortKeyOf(label: String): String = pinyinKey(label)

    @Volatile
    private var appsCache: List<AppEntry>? = null

    /** 应用清单缓存（桌面与车机设置共享；安装/卸载广播时失效）。 */
    fun cachedApps(context: Context): List<AppEntry> = appsCache ?: scanLaunchableApps(context).also { appsCache = it }

    /**
     * 仅读取共享缓存，**绝不扫描**：设置页等必须在主线程取列表的界面用这个，
     * 缓存未命中时自行异步扫描（cachedApps 的同步兜底会把整页应用扫描压上 UI 线程）。
     */
    fun peekCachedApps(): List<AppEntry>? = appsCache

    /** 把外部线程扫描的结果纳入共享缓存（桌面扫描后设置页即可秒开）。 */
    fun adoptAppsCache(apps: List<AppEntry>) {
        if (appsCache == null) appsCache = apps
    }

    fun invalidateAppsCache() {
        appsCache = null
    }

    fun launchableApps(context: Context): List<AppEntry> = scanLaunchableApps(context)

    /**
     * 扫描所有可启动应用（桌面网格与设置页共用）。
     *
     * 三处必须容错（2026-10-23 用户反馈「部分 app 没被扫描出来」后复查）：
     *  1. **不再 `take(200)`**：旧实现把上限写在 `sortedBy` **之前**，取的是
     *     `queryIntentActivities` 返回顺序的前 200 个——应用装得多时后面的直接丢失，
     *     且丢哪些取决于系统返回顺序（看起来像「随机漏几个」）。现在全部保留。
     *  2. **逐应用 try/catch**：`loadLabel`/`loadIcon` 对资源异常的 APK 会抛
     *     `Resources.NotFoundException` 等；旧实现是 Sequence 链，一个坏应用就让
     *     `toList()` 整体抛出 → 桌面**一个应用都不显示**。现在单个失败就跳过并计数。
     *  3. **同包去重**：一个包可以有多个 launcher activity（实测 K40 上 83 个
     *     activity 只对应 82 个包，com.android.contacts 有 4 个），旧实现会重复显示。
     *
     * 仍然查不到的应用属于 Android 包可见性/多用户层面，不在本函数的控制范围：
     *  - 工作资料（work profile）、应用分身/双开运行在别的 userId 下，
     *    `queryIntentActivities` 只返回当前用户的；
     *  - ROM 的「应用隐藏」功能会让应用对本进程不可见；
     *  - 只有 LEANBACK/自定义 category 入口的应用不属于 LAUNCHER 语义。
     */
    private fun scanLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent =
            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
        val resolved =
            try {
                pm.queryIntentActivities(intent, 0)
            } catch (error: Throwable) {
                logSink?.invoke("LAUNCHER: 应用扫描失败 - ${error.message}（桌面将为空）")
                return emptyList()
            }
        var skipped = 0
        val infos = resolved.mapNotNull { it.activityInfo }
        // 同包多入口去重（策略见 dedupeLauncherPackages，有单测）：保留每个包的首个入口
        val keepOrder = dedupeLauncherPackages(infos.map { it.packageName }, context.packageName)
        val firstInfoByPackage = LinkedHashMap<String, android.content.pm.ActivityInfo>(keepOrder.size)
        infos.forEach { info -> firstInfoByPackage.putIfAbsent(info.packageName, info) }
        // 拼音键每应用只算一次（此前在比较器里逐对重复转写，150 应用 ≈ 上千次 ICU 调用）
        val apps =
            keepOrder
                .mapNotNull { firstInfoByPackage[it] }
                .mapNotNull { info ->
                    // 单个应用 loadLabel/loadIcon 抛异常不能拖垮整张列表
                    runCatching {
                        val label = info.loadLabel(pm).toString().ifBlank { info.packageName }
                        AppEntry(
                            component = ComponentName(info.packageName, info.name),
                            label = label,
                            icon = info.loadIcon(pm),
                        ) to pinyinKey(label)
                    }.onFailure {
                        skipped++
                    }.getOrNull()
                }
        if (skipped > 0 || resolved.size != apps.size) {
            // 诊断留痕：用户反馈「少应用」时，凭这一行就能区分
            // 「系统只给了这么多 / 同包多入口被合并」与「标签/图标加载失败被跳过」
            logSink?.invoke(
                "LAUNCHER: 应用扫描 系统返回 ${resolved.size} 个 activity → ${apps.size} 个应用" +
                    if (skipped > 0) "（跳过 $skipped 个：标签/图标加载失败）" else "（同包多入口已合并）",
            )
        }
        return apps.sortedBy { it.second }.map { it.first }
    }

    /** 字母导航条分组键：数字→"#"，ASCII 字母→大写字母，汉字→拼音首字母。 */
    fun indexLetterOf(label: String): String {
        val first = label.firstOrNull() ?: return "#"
        return when {
            first.isDigit() -> "#"
            first.isLetter() && first.code < 128 -> first.uppercaseChar().toString()
            else -> {
                val latin = toLatin(first.toString()).lowercase()
                latin.firstOrNull { it in 'a'..'z' }?.uppercaseChar()?.toString() ?: "#"
            }
        }
    }

    // ---- Shizuku 自愈 ----

    @Volatile
    private var watchdogActive = false
    private var watchdogThread: Thread? = null

    val taskRegex = Regex("""Task\{[0-9a-f]+ #(\d+) type=standard A=\d+:(\S+)""")
    private val displayRegex = Regex("""Display: mDisplayId=(\d+)""")

    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** 应用上下文（Shizuku 自愈需要）。 */
    @Volatile
    private var appContextRef: android.content.Context? = null

    /**
     * Shizuku 触摸注入自愈：服务断开时尝试解冻并重绑（每 4s 一次；健康时
     * service 非空直接跳过、零开销）。解决「必须强行停止 App 重开」问题。
     *
     * 注：原「任务看护」（应用逃逸到主屏后自动拉回）已于 2026-10-10 实机验证后移除：
     * 受信虚拟屏下应用内跳转天然留在车机屏、手机侧打开自然跟随到手机、从车机 dock
     * 重开又回到车机侧——不再需要任何拉回干预。
     */
    private fun startShizukuHealer() {
        if (watchdogActive) return
        watchdogActive = true
        watchdogThread =
            Thread({
                while (watchdogActive && appDisplayId >= 0) {
                    try {
                        val ctx = appContextRef
                        if (ctx != null && CarInputClient.service == null) {
                            runCatching {
                                Shell.exec("am unfreeze moe.shizuku.privileged.api", timeoutMs = 2500)
                            }
                            CarInputClient
                                .ensure(ctx)
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(4000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "shizuku-healer").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * 让应用屏**忽略应用请求的方向**（保持虚拟屏创建时的横/竖形态）。
     *
     * 命令只接受 true/1/false/0 —— 传 `reset` 会报
     * `Error: expecting true, 1, false, 0, but we get reset` 且**只写 stderr**，
     * 而 `Shell.exec` 不读 stderr，于是静默无效（本方法的前身就是这么写的：
     * 方向策略从未生效，头条把 AppHi 屏整块旋转成竖屏）。因此这里写完必须读回校验。
     */
    private fun applyIgnoreOrientationRequest(vararg ids: Int) {
        val targets = ids.filter { it >= 0 }
        if (targets.isEmpty()) return
        val failed = targets.filter { id -> !setIgnoreOrientationRequest(id, true) }
        if (failed.isEmpty()) {
            logSink?.invoke("LAUNCHER: 应用屏已忽略方向请求 ${targets.joinToString("/")}（防竖屏应用旋转虚拟屏）")
        } else {
            logSink?.invoke(
                "LAUNCHER: ⚠ 应用屏忽略方向请求失败 $failed（读回非 true）" +
                    "——竖屏应用可能旋转虚拟屏，导致画面比例异常与触摸漂移",
            )
        }
    }

    /**
     * 设置单个屏的忽略方向策略。
     *
     * @param verify true 时读回校验（**仅用于会话建立路径**——那条路径本就在后台线程，
     *   且策略不生效会导致整屏旋转、必须确认）。清理路径传 false，避免触发一连串
     *   shell 往返（那是 ANR 根因，2026-10-18）。
     * @return verify=false 时恒为 true（只表示命令已投递）
     */
    private fun setIgnoreOrientationRequest(
        displayId: Int,
        enabled: Boolean,
        verify: Boolean = true,
    ): Boolean {
        if (displayId < 0) return true
        val value = if (enabled) "true" else "false"
        Shell.exec("cmd window set-ignore-orientation-request -d $displayId $value", timeoutMs = 3_000)
        if (!verify) return true
        val readBack =
            Shell
                .exec("cmd window get-ignore-orientation-request -d $displayId", timeoutMs = 3_000)
                .output
        return readBack.contains(value)
    }

    data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )
}

/**
 * 应用扫描结果的**去重与过滤**（[VirtualLauncher.scanLaunchableApps] 的纯函数部分）。
 *
 * 抽出来是为了可单测：旧实现用 `take(200)` 且**写在排序之前**，应用多的机型上
 * 会按系统返回顺序砍掉尾部、表现为「偶尔漏几个 app」（2026-10-23 用户反馈）。
 *
 * @param candidates 系统 `queryIntentActivities` 的 packageName 序列（保持原顺序）
 * @param selfPackage 本应用包名（不出现在桌面）
 */
internal fun dedupeLauncherPackages(
    candidates: List<String>,
    selfPackage: String,
): List<String> {
    val seen = HashSet<String>(candidates.size)
    return candidates.filter { pkg -> pkg != selfPackage && seen.add(pkg) }
}
