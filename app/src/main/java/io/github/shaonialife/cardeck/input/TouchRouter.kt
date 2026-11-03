package io.github.shaonialife.cardeck.input

import android.content.Context
import io.github.shaonialife.cardeck.launcher.CardGesture
import io.github.shaonialife.cardeck.launcher.LetterboxHintController
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.resolveCardGesture
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.util.Bg

/** 触点（字节解析在 Rust fw::airplay::hid——A6 下沉，本类只做路由注入）。 */
data class Contact(
    val slot: Int,
    val xPixels: Int,
    val yPixels: Int,
    val down: Boolean,
)

/**
 * 触摸路由：把车机 hidSendReport 触点注入到车机侧虚拟屏（隐形屏模式）。
 *
 * 按主画面 x 坐标分流：x < dock 宽 → dock 屏；其余 → 应用屏（x 平移 dock 宽）。
 * 注入经 Shizuku UserService（1:1 真实坐标与毫秒级注入）。
 *
 * （原「桌面」（Presentation 直投）与「镜像」（无障碍 continueStroke）两种模式
 *   已按需移除，2026-10-10。）
 */
object TouchRouter {
    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1000L) return
        diagAt = now
        logSink?.invoke("TOUCH: $message")
    }

    private var pressed = false
    private var pressedDisplay = -1
    private var failStreak = 0

    /** 叠加卡片手势状态（卡片命中后独占触摸，不再注入应用）。 */
    private var overlayPressed = false

    /** 分屏把手粘性路由状态（命中把手条后整条触摸流固定路由应用屏）。 */
    private var splitHandlePressed = false

    /**
     * MOVE 注入节流：0 = 关闭；>0 时距上次注入不足该毫秒数的 move
     * 直接合并丢弃（down/up 边界事件与跨屏切换永不节流，最终位置由 up 携带）。
     * 症状：交互滞后但帧率正常（事件派发堆积）。设置「显示」面板可调，默认 24ms。
     */
    @Volatile
    var moveThrottleMs: Long = 24L

    @Volatile
    private var throttleInited = false

    private var lastInjectAt = 0L
    private var throttledCount = 0
    private var lastThrottleLogAt = 0L

    // 车机触摸坐标（通告视图坐标系，如 1160×540）→ 流坐标（缩放后 1450×674）的换算系数。
    // 分辨率缩放 ≠100% 时流比视图大，不换算会导致点击整体向右下漂移（dock 分流也错位）。
    @Volatile
    private var coordScaleX = 1f

    @Volatile
    private var coordScaleY = 1f

    /** 会话建立时设置：视图（车机通告 display）尺寸 → 流（编码/虚拟屏）尺寸。 */
    fun setCoordinateScale(
        viewW: Int,
        viewH: Int,
        streamW: Int,
        streamH: Int,
    ) {
        coordScaleX = if (viewW > 0) streamW.toFloat() / viewW else 1f
        coordScaleY = if (viewH > 0) streamH.toFloat() / viewH else 1f
    }

    fun onContact(
        context: Context,
        contact: Contact,
    ) {
        // 空闲槽占位（未按下且 0,0）忽略
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        val px = (contact.xPixels * coordScaleX).toInt().coerceAtLeast(0)
        val py = (contact.yPixels * coordScaleY).toInt().coerceAtLeast(0)

        // 叠加卡片（「强制横屏未生效」提示）优先消费触摸：它画在应用区的黑边上，
        // 必须先于注入判定命中，否则触摸会穿透到下面的应用。
        run {
            val visible = LetterboxHintController.isVisible()
            val hit = visible && LetterboxHintController.contains(px.toFloat(), py.toFloat())
            when (resolveCardGesture(visible, overlayPressed, contact.down, hit, appGestureActive = pressed)) {
                CardGesture.NOT_HANDLED -> overlayPressed = false
                CardGesture.CONSUME -> {
                    overlayPressed = contact.down
                    return
                }
                CardGesture.TAP -> {
                    overlayPressed = false
                    LetterboxHintController.onTap()
                    return
                }
            }
        }

        val launcher = VirtualLauncher
        val dockW = launcher.dockWidthPx
        if (dockW <= 0 || launcher.appDisplayId < 0 || launcher.dockDisplayId < 0) return
        var svc = CarInputClient.service
        if (svc == null) {
            // 主动触发重绑（手机重启/Shizuku 重启后 binder 丢失）。
            // **必须在后台**：ensure 内部是 bindUserService 同步 binder 调用，而本条
            // 触摸来自 iAP2 事件线程；主线程更不行（Shizuku 回调也走主线程，会自锁）。
            Bg.run("touch-rebind") { CarInputClient.ensure(context) }
            svc = CarInputClient.service
        }
        if (svc == null) {
            failStreak++
            // 首次以及每 200 次报一条：真机上每秒几十次触摸，逐条刷会淹没日志
            if (failStreak == 1 || failStreak % 200 == 0) {
                diag(
                    "注入服务不可用（连续 $failStreak 次，后台重绑中；" +
                        "若持续失效请打开 Shizuku App 或在系统设置中允许其后台运行）",
                )
            }
            return
        }
        failStreak = 0
        // 主画面坐标 → 分流：
        //  横屏：dock 左侧竖条（x < dockW），app 在右（x 平移 dockW）
        //  竖屏：dock 底部横条（y > appHeight），app 在上
        //  地图屏（仪表盘/全屏，优先于应用区）：全屏=应用区全部；分屏=dock 右侧 tile 区域。
        //  地图屏与流 1:1 创建，坐标直接平移；其余走活动应用屏（含超采样倍率换算）。
        var inDock: Boolean
        var targetDisplay = -1
        var localX = 0
        var localY = 0
        var routedToMap = false
        // 分屏把手粘性路由：按下落在把手条内 → 整条触摸流固定路由应用屏（Grid 所在屏）
        // 直至抬起。快拖时把手落后手指、坐标会越出把手条（甚至越过地图 tile 边缘），
        // 按坐标逐帧改判会跨屏重注（pointerUp 应用屏 + pointerDown 地图屏），掐断拖拽
        // 并给地图误触一下（2026-10-21 方案评审定谳）。模式同上方 overlay 卡手势独占。
        val handlePin =
            splitHandlePressed ||
                (contact.down && !pressed && launcher.dashHandleHit(px.toFloat(), py.toFloat()))
        if (handlePin) {
            splitHandlePressed = contact.down
            inDock = false
            targetDisplay =
                if (launcher.gridDisplayId >= 0) launcher.gridDisplayId else launcher.activeAppDisplayId()
            if (launcher.dockAtBottom) {
                localX = px
                localY = py
            } else {
                val scale = launcher.gridScale().coerceAtLeast(1f)
                localX = ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        } else if (launcher.dockAtBottom) {
            val appH = launcher.appHeightPx
            inDock = py >= appH
            // 地图 tile（应用区上半；全屏=应用区全部）。触摸/流坐标顶部起算：
            // tile 矩形 py ∈ [CARD_MARGIN_PX, +mapTileHeight]，屏内 y = py-边距（顶边距扣除）
            if (!inDock &&
                launcher.mapDisplayId >= 0 &&
                (launcher.mapFullscreen || launcher.dashboardMode) &&
                launcher.tileSlideFraction <= 0.01f &&
                px < launcher.appAreaWidth &&
                (
                    launcher.mapFullscreen ||
                        (py >= CARD_MARGIN_PX && py < CARD_MARGIN_PX + launcher.mapTileHeight)
                )
            ) {
                inDock = false
                targetDisplay = launcher.mapDisplayId
                localX = px
                localY = if (launcher.mapFullscreen) py else py - CARD_MARGIN_PX
                routedToMap = true
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
                localX = px
                localY = if (inDock) py - appH else py
            }
        } else {
            inDock = px < dockW
            if (!inDock &&
                launcher.mapDisplayId >= 0 &&
                (launcher.mapFullscreen || launcher.dashboardMode) &&
                launcher.tileSlideFraction <= 0.01f
            ) {
                val tileX = px - dockW
                val tileVisibleW = if (launcher.mapFullscreen) Int.MAX_VALUE else launcher.mapTileWidth
                if (tileX in 0 until tileVisibleW) {
                    inDock = false
                    targetDisplay = launcher.mapDisplayId
                    // 地图屏按布局动态 resize（分屏=tile 尺寸、全屏=应用区尺寸），
                    // 屏内容即坐标域：相对应用区左缘的偏移就是屏内坐标，直接平移
                    localX = tileX
                    localY = py
                    routedToMap = true
                }
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        }
        if (targetDisplay < 0) {
            diag("目标屏无效 dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}")
            return
        }
        // MOVE 节流：同屏的连续 move 间隔不足时合并丢弃（状态机不动，最新位置
        // 由后续 move/up 携带）。down/up/跨屏切换不节流。
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (!throttleInited) {
            throttleInited = true
            moveThrottleMs =
                DisplayPrefs
                    .moveThrottleMs(context)
                    .toLong()
        }
        if (moveThrottleMs > 0 &&
            contact.down &&
            pressed &&
            pressedDisplay == targetDisplay &&
            nowMs - lastInjectAt < moveThrottleMs
        ) {
            throttledCount++
            if (nowMs - lastThrottleLogAt > 5_000) {
                logSink?.invoke(
                    "TOUCH: move 节流 ${moveThrottleMs}ms 生效——累计合并 $throttledCount 次",
                )
                throttledCount = 0
                lastThrottleLogAt = nowMs
            }
            return
        }
        try {
            val ok: Boolean =
                when {
                    contact.down && !pressed -> {
                        pressed = true
                        pressedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down && pressedDisplay != targetDisplay -> {
                        // 跨屏拖动：先在旧屏收笔，再在新屏按下
                        runCatching { svc.pointerUp(pressedDisplay, 0, 0) }
                        pressedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down -> svc.pointerMove(targetDisplay, localX, localY)

                    else -> {
                        pressed = false
                        svc.pointerUp(pressedDisplay, localX, localY)
                    }
                }
            if (ok) lastInjectAt = nowMs
            if (!ok) {
                diag(
                    "注入被拒 disp=$targetDisplay ($localX,$localY) " +
                        "down=${contact.down} pressed=$pressed pressedDisplay=$pressedDisplay",
                )
            } else if (contact.down) {
                diag("注入OK disp=$targetDisplay ($localX,$localY)")
            }
        } catch (e: Exception) {
            diag("注入异常：${e.message}")
        }

        // 仪表盘模式：地图 tile 的触摸同时喂给「点按 → 全屏地图」检测器
        // （注入逻辑不受影响；点按 = 快速轻点，平移/双击/双指等地图手势不触发）。
        // 横竖屏都启用：tileX 传 tile 内坐标（仅用于位移判定，起点取值不敏感）
        val tileHit =
            !inDock &&
                targetDisplay == launcher.mapDisplayId &&
                launcher.dashboardMode &&
                !launcher.mapFullscreen
        updateMapTileTap(
            launcher,
            contact,
            tileHit,
            if (launcher.dockAtBottom) px - CARD_MARGIN_PX else px - dockW,
        )
    }

    fun reset() {
        pressed = false
        pressedDisplay = -1
        overlayPressed = false
        splitHandlePressed = false
        cancelMapTileTap()
        tapDragSlot = -1
        coordScaleX = 1f
        coordScaleY = 1f
    }

    // ---- 仪表盘地图 tile「快速点按 → 全屏地图」检测 ----
    // 点按判定：按下→抬起 ≤400ms 且位移 ≤30px；抬起后 300ms 内无第二次按下
    // （确认窗规避地图双击缩放误触）。拖动/拖出 tile/双指等地图手势不触发。
    private const val TAP_MAX_MS = 400L
    private const val TAP_MAX_MOVE_PX = 30
    private const val TAP_CONFIRM_MS = 300L
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var tapArmSlot = -1

    /** 已判为拖动的按住手势（slot）：作废到抬起为止——否则长滑动的 cancel→re-arm
     *  会把抬起点当新起点（位移小、时长短），误判成 tap 直接触发全屏。 */
    private var tapDragSlot = -1
    private var tapDownAt = 0L
    private var tapX = 0
    private var tapY = 0
    private var tapConfirm: Runnable? = null

    private fun updateMapTileTap(
        launcher: VirtualLauncher,
        contact: Contact,
        inTile: Boolean,
        tileX: Int,
    ) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!launcher.dashboardMode || launcher.mapFullscreen) {
            cancelMapTileTap()
            return
        }
        if (contact.down) {
            if (inTile && tapConfirm != null) {
                cancelMapTileTap() // 确认窗内再次按下 = 双击缩放，取消全屏触发
                return
            }
            if (tapArmSlot < 0 && inTile && tapDragSlot < 0) {
                tapArmSlot = contact.slot
                tapDownAt = now
                tapX = tileX
                tapY = contact.yPixels
            } else if (tapArmSlot >= 0 &&
                (
                    !inTile ||
                        contact.slot != tapArmSlot ||
                        kotlin.math.abs(tileX - tapX) + kotlin.math.abs(contact.yPixels - tapY) > TAP_MAX_MOVE_PX
                )
            ) {
                // 拖动 / 拖出 tile / 双指：整个手势作废到抬起（见 tapDragSlot 注释）
                tapDragSlot = tapArmSlot
                cancelMapTileTap()
            }
        } else {
            if (tapDragSlot >= 0) {
                if (contact.slot == tapDragSlot) tapDragSlot = -1
                return
            }
            if (tapArmSlot >= 0 && contact.slot == tapArmSlot) {
                val ok =
                    inTile &&
                        now - tapDownAt <= TAP_MAX_MS &&
                        kotlin.math.abs(tileX - tapX) + kotlin.math.abs(contact.yPixels - tapY) <= TAP_MAX_MOVE_PX
                cancelMapTileTap()
                if (ok) {
                    tapConfirm =
                        Runnable {
                            tapConfirm = null
                            if (launcher.dashboardMode && !launcher.mapFullscreen) {
                                logSink?.invoke("LAUNCHER: 地图卡片点按 → 全屏地图")
                                launcher.setMapFullscreen(true)
                            }
                        }
                    tapHandler.postDelayed(tapConfirm!!, TAP_CONFIRM_MS)
                }
            }
        }
    }

    private fun cancelMapTileTap() {
        tapArmSlot = -1
        tapConfirm?.let { tapHandler.removeCallbacks(it) }
        tapConfirm = null
    }
}
