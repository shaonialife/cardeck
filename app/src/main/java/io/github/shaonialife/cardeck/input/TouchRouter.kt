package io.github.shaonialife.cardeck.input

import android.content.Context
import io.github.shaonialife.cardeck.launcher.CardGesture
import io.github.shaonialife.cardeck.launcher.DashSplitPrefs
import io.github.shaonialife.cardeck.launcher.LetterboxHintController
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.resolveCardGesture
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.Executors

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

    @Volatile
    // 可见性（审查 P2）：retireSession 线程 reset() 与事件线程 onContact
    // 并发——@Volatile 保 reset 及时可见；多字段手势态的撕裂窗仍窄（单次
    // 误路由），不加热路径锁（注入路径 Shell.exec 至多 3s 会卡 retire）。
    private var pressed = false

    /** 主指（首指）slot：双指缩放时副指直通聚合注入，主指保留全部单指手势逻辑。 */
    private var pressedSlot = -1

    /** per-slot 会话表（slot → displayId）：每个槽位各自独立的拖拽会话。 */
    private val pressedSlots = HashMap<Int, Int>()

    @Volatile
    private var pressedDisplay = -1
    private var failStreak = 0

    /**
     * 完整触摸日志（车机设置→杂项，默认关）：开=逐条「注入OK 含坐标」
     * （1s 节流不变）；关=不逐条记录，只按 5s 汇总注入次数（无坐标）。
     */
    @Volatile
    var fullLogEnabled = false

    private var injectedCount = 0
    private var injectSummaryAt = 0L

    /** 叠加卡片手势状态（卡片命中后独占触摸，不再注入应用）。 */
    @Volatile
    private var overlayPressed = false

    /** 分屏把手粘性路由状态（命中把手条后整条触摸流固定路由应用屏）。 */
    @Volatile
    private var splitHandlePressed = false

    /**
     * 固定应用卡片冻结拉回手势状态：冻结态（应用被拉到手机侧）按下卡片即消费整条
     * 触摸流（不注入），改为触发 [VirtualLauncher.pullBackPinnedApp]。模式同
     * overlay 卡手势独占——只有「按下那一刻在卡片上」的整条手势才被消费。
     */
    @Volatile
    private var pinnedPullPressed = false

    /** 地图卡/全屏地图冻结拉回手势状态（与 [pinnedPullPressed] 完全同构，目标屏不同）。 */
    @Volatile
    private var mapPullPressed = false

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

    /**
     * 触点投递的专用串行队列（2026-11-20）：onContact 是**状态机**（pressed/pressedDisplay），
     * 且 injectInputEvent mode=WAIT_FOR_FINISHED 同一时间只允许一个 sync 注入在途——
     * 用 cached 池并发派发会乱序/竞态（UP 抢在 MOVE 前、双 DOWN、MOVE after UP），
     * 派发器直接拒收（「注入被拒」）。单线程队列保序，同时把注入的 binder 往返从
     * 事件读取线程解耦出去（滑动流畅的关键）。
     */
    private val touchExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "touch-inject") }

    private val queueLock = Any()
    private val queue = ArrayDeque<Contact>()
    private var draining = false

    /**
     * 异步投递触点（串行保序 + **MOVE 合并**；调用方线程立即返回）。
     *
     * 合并是拖拽滞后的根治：注入吞吐 < 事件到达率时旧 MOVE 排队只会让注入位置
     * 落后手指——同 slot 连续 MOVE 只保留最新（队列长度≈1），旧位置对拖拽无意义。
     * DOWN/UP 不合并（流完整性）；MOVE 注入走 ASYNC 模式（服务端），单条成本极低。
     */
    fun onContactAsync(
        context: Context,
        contact: Contact,
    ) {
        // 空闲槽占位在**入口**丢弃（DiPlay 每帧恒发 #0/#1 两槽，#1 恒 (0,0) 未按下）。
        // 注意 Contact.down=true 同时涵盖「按下」与「移动」——队列里该 slot 的**第一条
        // down 条目是 DOWN，其余是 MOVE**，合并规则必须按此区分（2026-11-21 教训：
        // 首版「UP 替换队尾 down 条目」把快速点击的 DOWN 也吞了——队列只剩孤立 UP
        // 被服务端吸收 → 点击全部丢失、滑动却正常，正是用户观察到的症状）。
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        val ctx = context.applicationContext
        synchronized(queueLock) {
            var firstDownIdx = -1
            var lastDownIdx = -1
            for ((i, c) in queue.withIndex()) {
                if (c.slot == contact.slot && c.down) {
                    if (firstDownIdx < 0) firstDownIdx = i
                    lastDownIdx = i
                }
            }
            if (contact.down) {
                if (firstDownIdx >= 0) {
                    // DOWN 已在队列：本条是 MOVE → 用最新坐标替换最后一条 down
                    queue[lastDownIdx] = contact
                } else {
                    queue.addLast(contact) // 该 slot 无 pending DOWN → 本条就是按下
                }
            } else {
                // UP：保留第一条 DOWN（点击位置），删除其后同 slot 的 MOVE，再追加 UP
                if (firstDownIdx >= 0) {
                    for (i in queue.size - 1 downTo firstDownIdx + 1) {
                        if (queue[i].slot == contact.slot && queue[i].down) queue.removeAt(i)
                    }
                }
                queue.addLast(contact)
            }
            if (!draining) {
                draining = true
                touchExecutor.execute { drain(ctx) }
            }
        }
    }

    private fun drain(ctx: Context) {
        while (true) {
            val c =
                synchronized(queueLock) {
                    queue.removeFirstOrNull() ?: run {
                        draining = false
                        null
                    }
                } ?: return
            try {
                onContact(ctx, c)
            } catch (t: Throwable) {
                logSink?.invoke("TOUCH: onContact 异常 ${t.message}")
            }
        }
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
        // dock 区判定（横屏=x<dock 宽；竖屏=y≥应用区高）——固定屏的冻结拉回与路由
        // 都必须以此为闸：全屏态的「应用区全部」命中域若不含此闸，dock 触摸会被
        // 送进固定屏，⌂ 键变死（2026-11-07 用户实测「home 回不了桌面」根因）。
        val inDockZone =
            if (launcher.dockAtBottom) py >= launcher.appHeightPx else px < dockW
        // 固定应用卡片冻结态：卡片区域（或固定屏全屏的应用区）触摸 = 拉回固定屏。
        // 消费整条手势不注入——卡片停在最后一帧，注入没有意义；健康态触摸照常
        // 注入固定屏（活交互面，应用内部自己响应）。
        run {
            if (launcher.pinnedFrozen && launcher.pinnedDisplayId >= 0) {
                val hit =
                    !inDockZone &&
                        (
                            launcher.pinnedFullscreen ||
                                (
                                    launcher.dashboardMode &&
                                        launcher.tileSlideFraction <= 0.01f &&
                                        !launcher.mapFullscreen &&
                                        launcher.pinnedCardHit(px.toFloat(), py.toFloat())
                                )
                        )
                if (hit) {
                    if (contact.down && !pressed && !pinnedPullPressed) {
                        pinnedPullPressed = true
                        Bg.run("pinned-pull") { VirtualLauncher.pullBackPinnedApp() }
                    }
                    if (pinnedPullPressed) {
                        if (!contact.down) pinnedPullPressed = false
                        return
                    }
                }
            } else {
                pinnedPullPressed = false
            }
        }
        // 地图冻结拉回（与固定应用同一套机制，目标屏=地图屏）：地图被拉到手机侧后
        // 点地图卡/全屏地图 = 拉回；健康态触摸照常注入地图屏（平移/缩放不受影响）。
        run {
            if (launcher.mapFrozen && launcher.mapDisplayId >= 0) {
                val hit =
                    !inDockZone &&
                        launcher.mapTileHit(px.toFloat(), py.toFloat()) &&
                        !launcher.dashHandleHit(px.toFloat(), py.toFloat())
                if (hit) {
                    if (contact.down && !pressed && !mapPullPressed) {
                        mapPullPressed = true
                        Bg.run("map-pull") { VirtualLauncher.pullBackMapApp() }
                    }
                    if (mapPullPressed) {
                        if (!contact.down) mapPullPressed = false
                        return
                    }
                }
            } else {
                mapPullPressed = false
            }
        }
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
        //  地图屏密度=手机侧（px=画布 tile × mapDisplayScale）——命中后按倍率换算屏内
        //  坐标；其余走应用屏（同倍率语义，见 gridScale/activeAppScale）。
        var inDock: Boolean
        var targetDisplay = -1
        var localX = 0
        var localY = 0
        var routedToMap = false
        var routedToPinned = false
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
                val mapScale = launcher.mapDisplayScale()
                localX = (px * mapScale).toInt()
                localY = ((if (launcher.mapFullscreen) py else py - CARD_MARGIN_PX) * mapScale).toInt()
                routedToMap = true
            }
            if (!routedToMap && !inDock) {
                // 固定应用卡片（仪表盘下半）/全屏（应用区全部）：优先于应用区回退。
                // ⚠️ 必须 !inDock 闸住——全屏命中域=应用区全部，不带闸会把 dock 触摸
                // 也送进固定屏（⌂ 失效，2026-11-07 实测）
                val origin = launcher.pinnedCardRouteOrigin(px, py)
                if (origin != null) {
                    inDock = false
                    targetDisplay = launcher.pinnedDisplayId
                    val scale = launcher.pinnedDisplayScale()
                    localX = ((px - origin.first) * scale).toInt()
                    localY = ((py - origin.second) * scale).toInt()
                    routedToPinned = true
                }
            }
            if (!routedToMap && !routedToPinned) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
                if (inDock) {
                    localX = px
                    localY = py - appH
                } else {
                    // ⚠️ 必须乘 activeAppScale（应用屏密度=手机侧后屏 px > 画布 app 区 px，
                    // 横屏分支同款倍率）——漏乘则车机触摸全部落在 ~1/scale 的左上区域
                    // （2026-11-19 竖屏 DiPlay 实测：点图标无反应/误翻页）
                    val scale = launcher.activeAppScale().coerceAtLeast(1f)
                    localX = (px * scale).toInt()
                    localY = (py * scale).toInt()
                }
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
                    // 地图屏按布局动态 resize（分屏=tile×倍率、全屏=应用区×倍率，密度=
                    // 手机侧）：屏内坐标 = 相对 tile 原点的画布偏移 × mapDisplayScale
                    val mapScale = launcher.mapDisplayScale()
                    localX = ((if (launcher.mapFullscreen) tileX else tileX - CARD_MARGIN_PX) * mapScale).toInt()
                    localY = ((if (launcher.mapFullscreen) py else py - CARD_MARGIN_PX) * mapScale).toInt()
                    routedToMap = true
                }
            }
            if (!routedToMap && !inDock) {
                // 固定应用卡片（把手右侧）/全屏（应用区全部）：优先于应用区回退。
                // ⚠️ 必须 !inDock 闸住——全屏命中域=应用区全部，不带闸会把 dock 触摸
                // 也送进固定屏（⌂ 失效，2026-11-07 实测）
                val origin = launcher.pinnedCardRouteOrigin(px, py)
                if (origin != null) {
                    inDock = false
                    targetDisplay = launcher.pinnedDisplayId
                    val scale = launcher.pinnedDisplayScale()
                    localX = ((px - origin.first) * scale).toInt()
                    localY = ((py - origin.second) * scale).toInt()
                    routedToPinned = true
                }
            }
            if (!routedToMap && !routedToPinned) {
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
        try {
            // ---- per-slot 分派（2026-11-21 v2）----
            // 每个 slot 独立会话（服务端 per-slot 拖拽会话 + 本地 pressedSlots 表）。
            // **首指**（按下时无任何活动 slot）保留 overlay 卡/把手/pinned 拉回等全部
            // 单指手势逻辑；已有活动指时的其它 slot 事件整段忽略（Float obtain 单指流
            // pointerId 固定 0，一次只支持一指；双指缩放待多指针构建修好后恢复）。
            val known = pressedSlots.containsKey(contact.slot)
            if (!known) {
                // 未知槽：UP 忽略；副指按下→直注（服务端多指分支聚合，2026-11-21 恢复）；
                // 副指异屏忽略（双指缩放两指必同屏）
                if (!contact.down) return
                if (pressedSlots.isEmpty()) { // 首指：落到下方主指逻辑
                } else {
                    val anchorDisplay = pressedSlots.values.first()
                    if (targetDisplay != anchorDisplay) return
                    val ok = svc.pointerDown(contact.slot, targetDisplay, localX, localY)
                    if (ok) pressedSlots[contact.slot] = targetDisplay
                    return
                }
            } else if (contact.slot != pressedSlot) {
                // 副指 MOVE/UP：直注本指流
                val prev = pressedSlots[contact.slot] ?: return
                if (!contact.down) {
                    svc.pointerUp(contact.slot, prev, localX, localY)
                    pressedSlots.remove(contact.slot)
                    return
                }
                if (targetDisplay != prev) return // 副指跨屏忽略
                svc.pointerMove(contact.slot, targetDisplay, localX, localY)
                return
            }
            val ok: Boolean =
                when {
                    contact.down && !pressed -> {
                        pressed = true
                        pressedSlot = contact.slot
                        pressedSlots[contact.slot] = targetDisplay
                        pressedDisplay = targetDisplay
                        svc.pointerDown(contact.slot, targetDisplay, localX, localY)
                    }

                    contact.down && pressedDisplay != targetDisplay -> {
                        // 跨屏拖动（该 slot 自己的流）：先在旧屏收笔，再在新屏按下
                        runCatching { svc.pointerUp(contact.slot, pressedDisplay, 0, 0) }
                        pressedSlots[contact.slot] = targetDisplay
                        pressedDisplay = targetDisplay
                        svc.pointerDown(contact.slot, targetDisplay, localX, localY)
                    }

                    contact.down -> svc.pointerMove(contact.slot, targetDisplay, localX, localY)

                    else -> {
                        pressed = false
                        pressedSlot = -1
                        val prev = pressedSlots.remove(contact.slot)
                        pressedDisplay = -1
                        if (prev != null) svc.pointerUp(contact.slot, prev, localX, localY) else true
                    }
                }
            if (!ok) {
                diag(
                    "注入被拒 disp=$targetDisplay ($localX,$localY) " +
                        "down=${contact.down} pressed=$pressed pressedDisplay=$pressedDisplay",
                )
            } else if (contact.down) {
                if (fullLogEnabled) {
                    diag("注入OK disp=$targetDisplay ($localX,$localY)")
                } else {
                    // 默认：不逐条记录，只按 5s 汇总注入次数（无坐标）——
                    // 连续触摸时「注入OK」与触摸日志一起刷屏，淹没其它日志
                    injectedCount++
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - injectSummaryAt >= 5000L) {
                        logSink?.invoke(
                            "TOUCH: 注入 ×$injectedCount（近5s 汇总；完整坐标日志可在 车机设置→杂项 开启）",
                        )
                        injectedCount = 0
                        injectSummaryAt = now
                    }
                }
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
            context.applicationContext,
            launcher,
            contact,
            tileHit,
            if (launcher.dockAtBottom) px - CARD_MARGIN_PX else px - dockW,
        )
    }

    fun reset() {
        pressed = false
        pressedSlot = -1
        pressedDisplay = -1
        pressedSlots.clear()
        overlayPressed = false
        splitHandlePressed = false
        pinnedPullPressed = false
        mapPullPressed = false
        cancelMapTileTap()
        tapDragSlot = -1
        coordScaleX = 1f
        coordScaleY = 1f
    }

    // ---- 仪表盘地图 tile「快速点按 → 全屏地图」检测 ----
    // 点按判定：按下→抬起 ≤400ms 且位移 ≤30px；抬起后 300ms 内无第二次按下
    // （确认窗规避地图双击缩放误触）。拖动/拖出 tile/双指等地图手势不触发。
    // 触发与否受 车机设置→仪表盘「点击地图卡片进全屏」开关控制（关=仅操作卡片内地图）。
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
        context: Context,
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
                            if (launcher.dashboardMode &&
                                !launcher.mapFullscreen &&
                                DashSplitPrefs.tapMapCardFullscreen(context)
                            ) {
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
