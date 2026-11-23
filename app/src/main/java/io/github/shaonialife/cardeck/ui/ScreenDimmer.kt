package io.github.shaonialife.cardeck.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.Bg

/**
 * 常亮 + 暗幕：替代息屏的锁屏规避方案。
 *
 * 车机场景下手机锁屏会冻结非默认屏的输入（系统硬边界），且安全锁无法被命令解除。
 * 本方案让屏幕永不自动息屏（屏幕超时设为极大值，断开时恢复原值），
 * 无操作 45 秒后盖一层近黑全屏 View（模拟息屏观感），触摸手机任意位置即移除并重新计时。
 *
 * 触摸探针：1px 窗口 + FLAG_WATCH_OUTSIDE_TOUCH，捕获屏幕任意处的真实触摸（含第三方 app），
 * 仅用于重置计时，不拦截、不消费任何事件。
 */
object ScreenDimmer {
    private const val TAG = "ScreenDimmer"
    private const val IDLE_TIMEOUT_MS = 45_000L
    private const val TIMEOUT_MAX = 2_147_483_647

    /**
     * 由设置项控制（默认关，2026-11-23；控制入口在 车机设置→显示）。
     * MainActivity 启动时会用 DisplayPrefs.alwaysOnDim 覆盖为本机偏好。
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * 自动暗幕总开关（45s 无操作假息屏/盖暗幕）。remote 调试变体在 Application
     * 启动时置 false——排障需要实时看手机屏幕，「过一会儿黑屏」恰好挡住正在排查的
     * 现场（2026-10-24 用户要求）；常亮不受影响，手动假息屏（[enterDimNow] 的
     * force 路径）也不受影响。
     */
    @Volatile
    var autoDimAllowed: Boolean = true

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var handler: Handler? = null
    private var dimView: View? = null
    private var watchView: View? = null

    @Volatile
    private var dimShown = false

    /**
     * 真·假息屏：物理面板已由我们断电（SurfaceControl POWER_MODE_OFF）。
     * 只在本对象置位时才会在收口路径恢复供电；系统真休眠（按电源键）时由
     * SCREEN_OFF 广播清零——此后面板归 DisplayPowerController 管，不要去抢。
     */
    @Volatile
    private var panelOffByUs = false

    private var screenOffReceiver: BroadcastReceiver? = null

    /**
     * 当前是否有活跃的投屏会话。
     *
     * **自动暗幕与常亮只在会话期间生效**（2026-10-19 用户实测反馈）：
     * 应用启动时就会 attach（为了让「手动假息屏」随时可用），但车机侧断开
     * （`VideoSender.onEnded`）不会释放 VirtualLauncher，暗幕的 45s 定时器仍在——
     * 表现为「会话早就断了，过一会儿手机屏突然变黑」。所以自动调度必须由会话驱动，
     * 手动 [enterDimNow] 不受此限制（用户显式操作）。
     */
    @Volatile
    private var sessionActive = false

    private val showRunnable = Runnable { showDim() }

    /** 会话开始/结束（由 SessionController 在推流起停时调用）。 */
    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        val ctx = appContext ?: return
        val h = handler
        if (active) {
            if (!enabled) return
            enableStayAwake(ctx)
            h?.post { schedule() }
            Log.i("CarPlaySender", "DIMMER: 会话开始——常亮/自动暗幕生效（${IDLE_TIMEOUT_MS / 1000}s 无操作后变暗）")
        } else {
            h?.removeCallbacks(showRunnable)
            // 会话结束时如果暗幕正盖着（车机自己断开），立即撤掉，让用户看到手机正常界面
            h?.post { hideDim() }
            restorePanelIfNeeded()
            restoreTimeout(ctx)
            Log.i("CarPlaySender", "DIMMER: 会话结束——自动暗幕已停、屏幕超时已恢复")
        }
    }

    fun attach(context: Context) {
        if (appContext != null) {
            // 已附着：本次若为「启用」切换，补齐常亮与自动计时（触摸探针此前已挂）
            if (enabled && sessionActive) {
                enableStayAwake(context)
                handler?.post { schedule() }
            }
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少悬浮窗权限，暗幕/触摸探针不可用（常亮不可用）")
            appContext = context.applicationContext
            windowManager = null
            handler = Handler(Looper.getMainLooper())
            return
        }
        appContext = context.applicationContext
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        // 常亮只在会话期间打开：应用启动时 attach 不把屏幕超时改成「永不」，
        // 否则未连接时手机也永不熄屏（车机断开后同样收不回来）。
        if (enabled && sessionActive) enableStayAwake(context)
        handler?.post {
            addWatcher()
            registerScreenOffReceiver(context)
            if (enabled && sessionActive) schedule()
        }
        if (!enabled) {
            Log.i("CarPlaySender", "DIMMER: 触摸探针已启动（常亮/暗幕关闭）")
        } else if (sessionActive) {
            Log.i("CarPlaySender", "DIMMER: 已启动（常亮 + ${IDLE_TIMEOUT_MS / 1000}s 无操作后暗幕，点击恢复）")
        } else {
            Log.i("CarPlaySender", "DIMMER: 触摸探针已启动（未在投屏——常亮/自动暗幕待会话开始后生效）")
        }
    }

    /** 关闭常亮/暗幕但保留触摸探针（重新开启时无需重建窗口）。 */
    fun disableDim() {
        enabled = false
        val ctx = appContext ?: return
        restoreTimeout(ctx)
        handler?.removeCallbacks(showRunnable)
        handler?.post { hideDim() }
        restorePanelIfNeeded()
        Log.i("CarPlaySender", "DIMMER: 常亮/暗幕已关闭（触摸探针保留）")
    }

    /**
     * 自动暗幕是否应当生效（纯函数，见 [shouldAutoDim]）。
     *
     * 拆出来是为了单测：这三者任一为假都必须**不**弹暗幕。用户实测的 bug 正是
     * 「会话已结束（sessionActive=false）但定时器还在」——手机屏无故变黑。
     */
    fun shouldAutoDim(
        enabled: Boolean,
        sessionActive: Boolean,
        attached: Boolean,
    ): Boolean = enabled && sessionActive && attached

    fun detach() {
        val h = handler
        handler = null
        // 会话标记必须一并清零：否则下次 attach（未连接时启动 App）会沿用上一轮的
        // true 而立即排程，又变回「没会话也自动变暗」的老问题。
        sessionActive = false
        restorePanelIfNeeded()
        appContext?.let { unregisterScreenOffReceiver(it) }
        h?.removeCallbacksAndMessages(null)
        val wm = windowManager
        val dim = dimView
        val watch = watchView
        dimView = null
        watchView = null
        dimShown = false
        windowManager = null
        h?.post {
            dim?.let { v -> runCatching { wm?.removeView(v) } }
            watch?.let { v -> runCatching { wm?.removeView(v) } }
        }
        val ctx = appContext
        appContext = null
        if (ctx != null) restoreTimeout(ctx)
        Log.i("CarPlaySender", "DIMMER: 已停止（屏幕超时恢复）")
    }

    /** 暗幕是否已就绪（attach 完成且具备悬浮窗能力）。 */
    fun isAttached(): Boolean = handler != null && windowManager != null

    /** 手动进入假息屏（主页面按钮触发；点击暗幕或手机屏幕任意处恢复）。 */
    fun enterDimNow() {
        val h = handler ?: return
        // attach 时缺权限、之后才授予的：此处补建窗口管理器与触摸探针
        if (windowManager == null) {
            val ctx = appContext ?: return
            if (!android.provider.Settings.canDrawOverlays(ctx)) return
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            h.post { addWatcher() }
        }
        // 手动假息屏要有意义，屏幕必须保持常亮（否则真睡下去就没法「点击恢复」）
        appContext?.let { if (enabled) enableStayAwake(it) }
        h.post { showDim(force = true) }
    }

    /** 物理触摸（任意 app/界面）或暗幕点击时调用：重置自动暗幕计时。 */
    fun onUserInteraction() {
        val h = handler ?: return
        if (panelOffByUs) {
            // 真·假息屏下的唤醒：先把面板电供上，再走暗幕移除（否则用户看到的是黑屏）
            restorePanelIfNeeded()
        }
        if (dimShown) hideDim()
        if (enabled && sessionActive) schedule()
    }

    private fun schedule() {
        if (!sessionActive) return
        handler?.removeCallbacks(showRunnable)
        handler?.postDelayed(showRunnable, IDLE_TIMEOUT_MS)
    }

    private fun showDim(force: Boolean = false) {
        // 自动路径必须有活跃会话：会话已结束（尤其车机侧断开）时绝不弹暗幕
        if (!force && !shouldAutoDim(enabled, sessionActive, isAttached())) return
        // remote 调试变体：自动假息屏/暗幕一律不排程（排障要看屏幕），手动路径不受限
        if (!force && !autoDimAllowed) return
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.w("ScreenDimmer", "showDim: appContext 为空（attach 未执行）")
            return
        }
        val wm = windowManager
        if (wm == null) {
            android.util.Log.w("ScreenDimmer", "showDim: windowManager 为空（悬浮窗权限缺失？）")
            return
        }
        if (dimShown) return
        val view =
            View(ctx).apply {
                setBackgroundColor(Color.argb(0xF8, 0, 0, 0))
                isClickable = true
                setOnClickListener { onUserInteraction() }
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
        runCatching { wm.addView(view, params) }
            .onSuccess {
                dimView = view
                dimShown = true
                Log.i("CarPlaySender", "DIMMER: 暗幕已显示")
                // 真·假息屏：暗幕上好后直接断物理屏电源。系统保持交互态（常亮超时已设
                // 为永不，不会真睡下去），虚拟屏完全不受影响；触摸经探针唤醒。
                setPanelPower(false)
            }.onFailure { Log.w(TAG, "暗幕添加失败：${it.message}") }
    }

    private fun hideDim() {
        val view = dimView ?: return
        dimView = null
        dimShown = false
        runCatching { windowManager?.removeView(view) }
    }

    /** 1px 触摸探针：捕获屏幕上任意处的 DOWN 事件用于重置计时，不消费事件。 */
    private fun addWatcher() {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val view =
            View(ctx).apply {
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN ||
                        event.action == MotionEvent.ACTION_OUTSIDE
                    ) {
                        onUserInteraction()
                    }
                    false
                }
            }
        val params =
            WindowManager
                .LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
        runCatching { wm.addView(view, params) }
            .onSuccess { watchView = view }
            .onFailure { Log.w(TAG, "触摸探针添加失败：${it.message}") }
    }

    // ---- 真·假息屏：SurfaceControl 层断/上物理屏电源（经 Shizuku UserService） ----

    /** 断电/恢复面板电。AIDL 是阻塞 binder 调用，一律后台执行；失败时留在暗幕态（旧行为）。 */
    private fun setPanelPower(on: Boolean) {
        Bg.run("dimmer-panel") {
            val svc = CarInputClient.service
            if (svc == null) {
                Log.i(TAG, "面板电源跳过（UserService 未就绪，仅暗幕）")
                return@run
            }
            val ok =
                runCatching { svc.setScreenPower(on) }
                    .onFailure { Log.w(TAG, "setScreenPower($on) 调用失败：$it") }
                    .getOrDefault(false)
            if (ok) {
                panelOffByUs = !on
                Log.i("CarPlaySender", "DIMMER: 面板电源 ${if (on) "恢复" else "断开"}（真·假息屏）")
            } else {
                Log.i(TAG, "面板电源 $on 失败（ROM 不支持？保持暗幕态）")
            }
        }
    }

    /** 收口路径统一出口：只有「我们断的电」才恢复，避免与系统电源管理抢面板。 */
    private fun restorePanelIfNeeded() {
        if (panelOffByUs) setPanelPower(true)
    }

    /**
     * 「连接时真息屏」的收口出口（SessionController 恢复路径调用）：
     * 撤暗幕 + 恢复面板供电 + 清断电标记（幂等——没断过电时只是无害空跑）。
     * 与 [onUserInteraction] 的区别：这里是**程序化收口**（关开关/会话退役/启动恢复），
     * 不重排自动计时；断电期间暗幕承载触摸检测（点击暗幕即唤醒，用户实测触摸
     * IC 在面板断电后仍工作），所以撤暗幕必须与恢复供电同步做。
     */
    fun exitPanelOff() {
        val h = handler ?: return
        panelOffByUs = false
        h.post {
            if (dimShown) hideDim()
            setPanelPower(true)
        }
    }

    /**
     * 真休眠（按电源键）与解锁的生命周期收口：
     * - SCREEN_OFF：面板交还系统电源管理（清标记）、撤暗幕、停计时——锁屏界面自己就是
     *   「遮蔽 + 防误触」，暗幕留着只会在解锁后变成要多点一下的残留（2026-10-23 用户实测）。
     * - USER_PRESENT：解锁后重排自动假息屏计时——指纹/人脸解锁不产生屏幕触摸事件，
     *   探针收不到，不在这里补排的话解锁后计时器是停着的。
     */
    private fun registerScreenOffReceiver(context: Context) {
        if (screenOffReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            if (panelOffByUs) {
                                panelOffByUs = false
                                Log.i(TAG, "系统真休眠——面板交还系统电源管理（假息屏标记已清）")
                            }
                            if (dimShown) {
                                handler?.removeCallbacks(showRunnable)
                                hideDim()
                                Log.i(TAG, "真休眠——暗幕已撤（锁屏接管遮蔽职责）")
                            }
                        }

                        Intent.ACTION_USER_PRESENT -> {
                            if (enabled && sessionActive && isAttached()) {
                                schedule()
                                Log.i(TAG, "解锁——重新排程自动假息屏计时")
                            }
                        }
                    }
                }
            }
        screenOffReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
    }

    private fun unregisterScreenOffReceiver(context: Context) {
        screenOffReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    // ---- 屏幕常亮：改系统屏幕超时为永不，断开时恢复 ----

    private fun enableStayAwake(ctx: Context) {
        Bg.run("dimmer-stayawake") {
            val current =
                Shell
                    .exec("settings get system screen_off_timeout")
                    .output
                    .trim()
                    .toIntOrNull()
            if (current == null) {
                Log.w(TAG, "读取屏幕超时失败（Shizuku 未就绪？）")
                return@run
            }
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (current < TIMEOUT_MAX && !prefs.contains("screen_off_timeout_backup")) {
                prefs.edit().putInt("screen_off_timeout_backup", current).apply()
            }
            Shell.exec("settings put system screen_off_timeout $TIMEOUT_MAX")
            Log.i("CarPlaySender", "DIMMER: 屏幕超时设为永不（原值 $current）")
        }
    }

    private fun restoreTimeout(ctx: Context) {
        Bg.run("dimmer-restore") {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.contains("screen_off_timeout_backup")) return@run
            val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
            prefs.edit().remove("screen_off_timeout_backup").apply()
            Shell.exec("settings put system screen_off_timeout $backup")
            Log.i("CarPlaySender", "DIMMER: 屏幕超时恢复为 $backup")
        }
    }
}
