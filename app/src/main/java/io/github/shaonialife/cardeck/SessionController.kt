package io.github.shaonialife.cardeck

import android.annotation.SuppressLint
import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import io.github.shaonialife.cardeck.airplay.AirPlayLink
import io.github.shaonialife.cardeck.audio.AudioProfile
import io.github.shaonialife.cardeck.audio.AudioSender
import io.github.shaonialife.cardeck.audio.AudioTuning
import io.github.shaonialife.cardeck.bt.BtProfiles
import io.github.shaonialife.cardeck.bt.BtSessionGate
import io.github.shaonialife.cardeck.bt.Iap2BluetoothClient
import io.github.shaonialife.cardeck.bt.Iap2BluetoothServer
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.capture.HiddenDisplaySource
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.Contact
import io.github.shaonialife.cardeck.input.HidNavigation
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.media.MediaSessionsState
import io.github.shaonialife.cardeck.net.AirPlayFeatures
import io.github.shaonialife.cardeck.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.cardeck.net.CarPlayServiceBrowser
import io.github.shaonialife.cardeck.net.EndpointHosts
import io.github.shaonialife.cardeck.net.WifiCarPlayConnector
import io.github.shaonialife.cardeck.net.WifiConnectCommand
import io.github.shaonialife.cardeck.net.WifiSavedNetworks
import io.github.shaonialife.cardeck.protocol.NativeBridge
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import io.github.shaonialife.cardeck.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级会话中枢：**日志缓冲**与**会话管线所有权**的唯一持有者。
 *
 * 背景（2026-10-11 用户实测发现三个连带问题）：投屏管线运行在 MainActivity 派生线程里，
 * 划掉界面（Activity 销毁）后进程被前台服务保活、管线继续跑；但重进应用创建的是**新
 * Activity 实例**——
 *  1. 老线程的日志写进老实例（已销毁）的 UI → 新界面"日志断联"（文件日志实际未断）
 *  2. 「断开连接」操作新实例的新对象 → 老管线无人持有，断不掉
 *  3. 重开连接会建第二套对象 → 双会话风险
 *
 * 本对象把这些状态提升为进程级单例：Activity 只是"挂屏"（订阅日志、转发按钮），
 * 销毁/重建对其无影响。会话逻辑线程通过 [appContext] 运行，不依赖任何 Activity。
 */
object SessionController {
    // ---- 日志缓冲（进程级；Activity 订阅渲染）----

    /** WiFi 重加入专用单线程池（审查 P1-15 后续，用户令线程池化）：join 窗
     *  5s+8s+9s 不得占 retryTick/flow 线程；命名线程便于 logcat 诊断；
     *  daemon 不阻进程退出。两处提交点（retryTick 活性失效 / onConnectFailed
     *  网络类失败）共用此池 + rejoinInFlight CAS 闸（≤1 个在途/排队任务）。 */
    private val wifiRejoinExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "wifi-rejoin").apply { isDaemon = true }
        }

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当前挂屏 Activity 的渲染回调（收到**本条新增日志行**，含时间戳前缀）；Activity onDestroy 时清空。 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 「监听日志」开关（由 Activity 从偏好读取后同步；关闭时不采集不渲染）。 */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** WiFi 等待用户操作的窗口（打开 WLAN 开关 / 手动加入热点的轮询上限）。 */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** 加入确认弹窗的应答等待上限（无人应答按中止处理）。 */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /** 用户取消连接后，「上车自动连接」的冷却窗（见 [autoConnectSuppressedUntil]）。 */
    private const val AUTO_CONNECT_COOLDOWN_MS = 10 * 60_000L

    /** 连接编排窗口（fw: 决策在 fw::airplay::flow；窗口 = 车机 AirPlay 窗口
     *  「用户点车机图标/收到 ctrl 邀请才开」的等待上界，宿主连接策略层。
     *  决策机内部自带各阶段退避节奏：403→5s / 其余→0.5s / 明文 SETUP→2s）。
     *  加密升级等分支由决策机管理，此处不再单设子窗口。 */
    private const val CONNECTION_WINDOW_MS = 120_000L

    /**
     * 端点已到但手机还没切到车机热点时的等待上限。
     * 覆盖「自动加入（几秒）」与「用户手动加入（常见 10-30s）」两种路径；
     * 超时后明确提示并复位 sessionStarting，不留「永久正在连接」。
     */
    private const val CAR_WIFI_BIND_WAIT_MS = 45_000L

    /**
     * `requestNetwork(WIFI)` 等 onAvailable 的上限（秒）。
     * 用户正在系统设置里手动加入热点时，回调要等关联+DHCP 完成才来，实测可达 10s+。
     * 旧值 5s 会在成功前 116ms 误判失败并放弃整轮会话（见 bindCarWifiAndRegister）。
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * `cmd wifi connect-network` 之后等系统真正关联上热点的时间窗。
     * 命令本身只把「连接」投给 WifiService 就返回（exit=0 不代表已连上），
     * 断开旧网 → 扫描 → 关联 → DHCP 实测 2~10s，5GHz 热点偏慢时更久。
     */
    private const val CAR_WIFI_JOIN_WAIT_MS = 25_000L

    /** `cmd wifi set-wifi-enabled enabled` 之后等 WLAN 真正开启的时间窗。 */
    private const val WIFI_ENABLE_WAIT_MS = 10_000L

    /**
     * 等用户确认「连接车机热点」系统弹窗的上限（应用专属网络）。
     * 系统确认框可能停留很久；用户取消连接时 [connectCancelled] 会即时退出。
     */
    // 40s 总窗 = 20s 首段 + 20s 重发段（连接器内部分段）。车机 15s 拆 BT 看门狗
    // 决定了等待必须远短于旧值 120s——旧值下首轮卡满 120s 时车机早已拆链重邀
    // 并被僵尸轮次堵门（2026-10-22 奥迪 3315 真机定谳）。
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 40_000L

    /** Activity onCreate 时调用：登记 applicationContext 并创建会话对象（幂等）。 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // 横屏兼容写入失败的原因（user build 限制 / Shizuku 故障）只有这里能落盘：
        // 车机侧设置面板的 toast 只给用户看，排查需要日志。
        AppOrientationCompat.diagSink = ::appendLog
        // 主线程调用 shell 的告警（历史上两次真机 ANR 都源于此；HKS 下 logcat 不可读）
        Shell.violationSink = ::appendLog
        // HID 原始日志开关（键位校准）：静态标志从偏好初始化，进程冷启动也要生效
        HidNavigation.rawLogEnabled = DisplayPrefs.hidRawLog(appContext!!)
        // 完整触摸日志开关（默认关=5s 汇总）：静态标志从偏好初始化，进程冷启动也要生效
        // （单一事实源 = TouchRouter.fullLogEnabled——车机面板开关由 setter 实时更新）
        TouchRouter.fullLogEnabled = DisplayPrefs.touchFullLog(appContext!!)
    }

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染（增量行直送）。任意线程可调。 */
    fun appendLog(message: String): Unit = appendLog(message, System.currentTimeMillis())

    /** [atMs] = 行的原始发生时刻——Rust 日志桥批次排空时按 Rust 侧时刻渲染
     *  （批次内各行时间不同是常态，重打时间戳会让整批时间重复）。 */
    fun appendLog(
        message: String,
        atMs: Long,
    ) {
        // NUL 会写进日志文件：文本编辑器/文件管理器打不开（Read 工具也会判为二进制），
        // 用户反馈日志时等于白导。0x4301 的定长地址字段曾原样打印出多个 NUL
        // （2026-10-18 实测 216 个），这里替换成可见占位符——顺带让「字符串里有脏字节」
        // 在日志上直接可见，而不是隐形成 InetSocketAddress 的解析失败。
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean, atMs)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date(atMs)) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * 派发系统媒体键（AudioManager 全局路由给**当前活跃媒体会话**，无需权限）。
     * 桌面音乐卡的按钮与车机/方向盘媒体键（Consumer HID）共用这一路径。
     */
    fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * 旋钮/四向拨杆 → 方向键：注入**应用屏**虚拟显示（DPAD 需要焦点，不能全局分发）。
     *
     * 走 UserService 的 `injectInputEvent`（毫秒级）；失败回退 `input -d <id> keyevent`
     * （多 ~30ms 但不会丢键）。dок 屏不参与——跨屏焦点需要 Launcher 侧改造，见
     * docs/HID-KNOB.md。
     */
    private fun injectNavigationKey(keyCode: Int) {
        // 优先注入「当前可见的应用屏」（桌面网格 / 打开中的应用屏），否则退回主应用屏
        val displayId =
            VirtualLauncher.activeAppDisplayId().takeIf { it > 0 }
                ?: VirtualLauncher.appDisplayId
        if (displayId <= 0) {
            appendLog("HID: 应用屏未就绪（displayId=$displayId）——导航键忽略")
            return
        }
        if (CarInputClient.keyEvent(displayId, keyCode)) return
        Shell.exec("input -d $displayId keyevent $keyCode", 3_000L)
    }

    /**
     * 清空进程级日志缓冲（UI 刷新是调用方的事：显式 `renderConsole(logText())`）。
     * 历史上这里靠 `logListener?.invoke("")` 通知 UI——那是「每行全量 setText」时代的
     * 语义；改为增量 appendConsole 后空串等于追加空行，UI 原地不动（「清除按钮点了
     * 没反应」根因，2026-10-16）。不要恢复该调用：空串与真实日志行无法区分。
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    private var wifiConnector: WifiCarPlayConnector? = null

    /**
     * 当前已绑定到哪个车机热点（bindCarWifiAndRegister 成功后写入）。
     * 端点交接时若已绑定到同一 SSID，跳过二次 bindCurrentWifi——它内部先
     * release() 解绑，会打断刚注册好的 ctrl mDNS 广播并白等系统重连。
     */
    @Volatile
    private var boundCarSsid: String? = null

    /** 本次进程内已就「是否清除系统保存热点」询问过的 SSID（避免重试时重复弹窗）。 */
    private val forgetPromptHandled =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /**
     * 用户在「清除/保留」弹窗里明确选过「保留」的 SSID（持久化）。
     * connect-network 兜底的「不作会话末清除」例外**只认这个**（2026-10-25 定谳：
     * 旧判据「系统里已存在」会把我们自己遗留的保存当成用户意愿——毒化自延续，
     * 荣耀实测「specifier 永远不出场」的直接根因）。
     */
    private fun isUserKept(ssid: String): Boolean =
        appContext
            ?.getSharedPreferences("wifi_net", android.content.Context.MODE_PRIVATE)
            ?.getStringSet("user_kept", emptySet())
            ?.contains(ssid) == true

    private fun markUserKept(
        ssid: String,
        kept: Boolean,
    ) {
        val prefs =
            appContext?.getSharedPreferences("wifi_net", android.content.Context.MODE_PRIVATE)
                ?: return
        val set = prefs.getStringSet("user_kept", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (kept) set.add(ssid) else set.remove(ssid)
        prefs.edit().putStringSet("user_kept", set).apply()
    }

    /** WiFi 绑定互斥：auto-join 与端点交接两条路径可能同时绑定同一热点。 */
    private val wifiBindLock = Any()

    /** DCL 双检锁的可发布字段（无 volatile 的双检锁是坏发布——虽然当前仅主线程调用，别留隐患）。 */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * 常驻 `_airplay._tcp` 发现器（对照 catplay 的常驻 Bonjour cache）。
     * 加入热点即开始后台发现，交接端点时地址多半已就绪，不再现查现等。
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- 「显示」运行时生效值（会话建立时快照；英雄卡展示运行时值而非偏好值）----

    /** 本会话流分辨率缩放百分比（按车机分辨率的百分比生成；100=原尺寸）。 */
    @Volatile
    var activeScalePercent: Int = 100

    /** 本会话目标帧率（合成节奏上限；「显示」设置运行中切换可实时生效）。 */
    @Volatile
    var activeTargetFps: Int = 30

    @Volatile
    var audioSender: AudioSender? = null

    @Volatile
    var currentClient: AirPlayLink? = null

    /**
     * 车机桌面「OEM 图标」块点击（用户令 2026-11-04）：发 requestUI——
     * **会话保持不断**，车机切回原界面（用户在原界面的 CarPlay 入口点回来，
     * 屏幕流全程保持）。fw 真身 = _carEndpoint_RequestCarUI @0x2277e8ab8
     * → requestUI 命令；modesChanged 资源释放路线已撤（真机实测与资源
     * 重申调度器互相打架，且非固件真身）。任意线程可调（内部转 Bg——
     * 事件通道出站是 socket 写）。
     */
    fun requestCarUi() {
        val client =
            currentClient ?: run {
                appendLog("TOUCH: 无活动会话——OEM 块忽略")
                return
            }
        Bg.run("request-car-ui") {
            client.requestCarUi()
        }
    }

    @Volatile
    var currentHiddenSource: HiddenDisplaySource? = null

    /**
     * 当前会话的「已配对」标记（配对共享密钥原文）：投影授权晚于连接时，
     * 动态补启音频的闸。A3 起钥本体不在此派生（流钥随 Rust streamSetup
     * 应答交付），本字段只剩配对态判定用途。
     */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** 本次会话是否在车机侧播放声音（由「启动连接」弹窗选择）。 */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /** 会话建立互斥（端点交接 → 配对 → 推流全程只允许一条）。 */
    val sessionStarting = AtomicBoolean(false)

    /** 用户取消连接：配对线程在关键检查点看到后中止（mDNS/配对等待无法中途打断，粒度秒级）。 */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * 当前连接阶段的一句话提示（英雄卡副标题用，详细过程看日志）。
     * 由各阶段显式设置；null 时 UI 显示默认文案。
     */
    @Volatile
    var phaseHint: String? = null

    /** 横屏覆盖复原闸：会话起步装填（applyAll 前），两处收尾（disconnect 用户终态 /
     *  VideoSender.onEnded 车机拆链）谁先执行谁复原，后到静默跳过——手动断开时
     *  两路都走，无闸会连打两条「已复原 N 个应用的显示设置」（2026-11-05 用户报告）。 */
    private val orientationRestorePending =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * 用户取消后的自动连接冷却期（uptimeMillis）。取消只杀当轮会话，车机侧会因
     * RFCOMM 断开拆掉整轮无线栈（ACL 随之抖动），每次 ACL 重连都会再次触发
     * 「上车自动连接」——没有冷却的话用户点多少次取消都会被自动重连（2026-10-24
     * 用户实测）。10 分钟后自然过期（下车再上车属新意愿）；进程重启同样清零。
     */
    @Volatile
    var autoConnectSuppressedUntil: Long = 0L
        private set

    /** 取消「连接中」：停链路 + 置取消标志，卡在错误网络/等待回连时用户可主动脱身。 */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        autoConnectSuppressedUntil = SystemClock.uptimeMillis() + AUTO_CONNECT_COOLDOWN_MS
        appendLog("SESSION: 用户取消连接——正在停止蓝牙/WiFi 链路（自动重连冷却 ${AUTO_CONNECT_COOLDOWN_MS / 60_000} 分钟）")
        // 用户取消 = 用户终态：必须带 userInitiated=true（默认 false 是 AUTO_STOP
        // 语义，会让保留特性误进保留态保屏——2026-11-12 保留特性接线时定谳）；
        // 冷却上面已自行装填同值，重复装填无害。
        disconnect(context, userInitiated = true)
        // 强制释放互斥：配对流程线程可能阻塞在不可中断的连接/mDNS 等待上（如手机
        // 不在热点网段时连热点 IP），finally 遥遥无期——用户显式取消即强制解锁，
        // 否则英雄卡永久「正在连接」（旧流程产出已被 retireSession 清理，无冲突）
        sessionStarting.set(false)
        phaseHint = "已取消连接"
    }

    /**
     * 发送端 BT 链路断开（保活泵退出）。配对进行中（未推流）时本轮已注定失败——
     * 立即释放「会话处理中」互斥并唤醒卡住的等待（车机 15s 拆链 + 我们 120s 加入
     * 等待的窗口错配曾让僵尸轮次堵门：车机重连带来的新端点被「已有会话在处理中」
     * 拒绝，只剩 keep-alive 干等到用户放弃，2026-10-22 奥迪 3315 真机定谳）。
     *
     * 置 [connectCancelled] 让卡在 WiFi 加入等分片检查的配对线程在 ≤200ms 内退场；
     **不停 BT 组件**（车机大概率立刻回连监听）。新轮次起步处会清掉本标志——
     * 能走到那里说明 BT 组件仍在运行（用户取消路径 disconnect 会停组件、被端点
     * 守卫拦截），清除是安全的。
     */
    fun onSenderLinkClosed() {
        if (videoSender?.isRunning == true) return
        if (sessionStarting.compareAndSet(true, false)) {
            connectCancelled = true
            appendLog(">>> 本轮蓝牙链路断开——释放「会话处理中」，等待车机重连端点接手")
        }
    }

    /**
     * 最近一次 0x5703 的热点密码（仅内存，不落盘）。
     * 「车机热点 → 去连接」按钮复制到剪贴板用（手动加入兜底场景）。
     */
    @Volatile
    var lastWifiPsk: String? = null

    /**
     * 最近一次从车机拿到的热点 SSID（0x5703 或 0x4301，两条路径都写）。
     *
     * 与 [boundCarSsid] 的区别：boundCarSsid 只在**绑定成功**后才有值，而这里在
     * 「拿到凭据」时就写——用于无新凭据时判断「这台车机应该连哪个热点」。
     * 奥迪日志里手动兜底按钮误报「尚未拿到热点密码」，也是因为 0x4301 路径没写 psk。
     */
    @Volatile
    var lastCarWifiSsid: String? = null

    /** 挂屏 UI 钩子：SSID 变化时通知当前 Activity 回填输入框（可空）。 */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** 挂屏 UI 钩子：重要连接提示（WiFi 未开启/热点加入限制等）弹给用户看。 */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** 弹窗规格（加入热点确认等两按钮询问）。 */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
    )

    /**
     * 挂屏 UI 钩子：两按钮确认弹窗。
     * onChoice：true=positive；false=negative；null=无人应答/被关闭（调用方兜底）。
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // 应用专属网络被系统断开（车机熄火/离开范围）：明确告知用户，
                    // 而不是让英雄卡在「已连接」与失败之间静默摇摆
                    connector.onNetworkLost = { ssid ->
                        boundCarSsid = null
                        phaseHint = "车机热点已断开（$ssid）"
                        uiHintSink?.invoke("车机热点 \"$ssid\" 已断开——请重新启动连接")
                        appendLog(">>> 车机热点 \"$ssid\" 已断开——本次会话需重新连接")
                    }
                }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // 目标车机动态读取：选择后下一轮连接尝试/下一次连入即生效，无需重启链路
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
                    onWifiCredentials = wifiCredentialHook,
                    onLinkClosed = ::onSenderLinkClosed,
                    targetProvider = targetProvider,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
                    onWifiCredentials = wifiCredentialHook,
                    onLinkClosed = ::onSenderLinkClosed,
                    targetProvider = targetProvider,
                )
            ctrlAdvertiser =
                CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }.also { adv ->
                    adv.onCarInvite = { peerIp -> onCarInvite(peerIp) }
                }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // 手机双归属（连车机热点 + 自己开热点共享给电脑）时 mDNS 会跨网
                        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把
                        // MacBook 当成车机连了 120s。非本网段一律不算车机。
                        // 地址判定统一走 EndpointHosts：IPv4 按 /24 前缀，链路本地 IPv6
                        // 天然只在本链路，全局 IPv6 按 /64 比对。旧实现只认 IPv4，会把
                        // 只广播 fe80:: 的奥迪 4xxx 直接丢掉（2026-10-19）。
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }

        // 上次进程若在物理息屏期间崩溃/被杀，面板会停在 OFF（UserService 是常驻 shell 进程，
        // 它持有的电源状态不随应用进程消失）。新进程启动时恢复一次：此刻还没有会话，
        // 恢复是安全的——否则用户拿到的是一块按不亮的黑屏（可按电源键两次救回）。
        Bg.run("screen-power-startup-restore") {
            if (ShizukuGate.state(context) != ShizukuGate.State.READY) return@run
            val deadline = android.os.SystemClock.uptimeMillis() + 3_000L
            while (android.os.SystemClock.uptimeMillis() < deadline && CarInputClient.service == null) {
                CarInputClient.ensure(context)
                Thread.sleep(150)
            }
            // 只恢复「已绑定服务」的情况：Shizuku 没起来时不必打扰用户（面板也不会是我们关的）
            if (CarInputClient.service != null && !CarInputClient.setPhysicalScreenPower(true)) {
                appendLog("SCREEN: 启动恢复物理屏失败（ROM 未授权 shell 电源控制）")
            }
        }
    }

    /** 状态行文本（Activity 渲染 statusText 用）。 */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** 会话是否活跃（推出流进程或 BT 任一在跑）。 */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /**
     * 「启动连接」主体（权限检查由 Activity 完成后调用）：前台保活服务 + 双向蓝牙。
     * 幂等：已有会话时 BT 组件的 start() 自行短路，不会双开。
     */
    fun startConnection(context: Context) {
        connectCancelled = false
        phaseHint = "蓝牙就绪，等待车机回连…"
        when (
            ShizukuGate
                .state(context)
        ) {
            ShizukuGate.State.READY ->
                appendLog("SHIZUKU: 就绪（触摸注入可用）")

            ShizukuGate.State.UNAUTHORIZED -> {
                appendLog("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗点允许后重连")
                ShizukuGate
                    .requestPermission()
            }

            ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」")

            ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: 未安装（隐形屏模式需要）")
        }
        CarPlayService
            .start(context)
        // 应用清单预热（2026-11-09「连上车机后桌面 app 慢半拍」）：全部连接路径
        // （手动/上车自动/回连）都经这里——配对空窗几十秒足够冷缓存的 PackageManager
        // 全量扫描（荣耀 132 应用实测 2.7s）自然完成，车机侧 GridActivity 绑定时
        // 缓存已就绪、图标即出。已扫过/扫描进行中 → 本调用零开销或等既有扫描合并。
        VirtualLauncher.warmUpAppsCache(context.applicationContext)
        // 能力发布先于连接（catplay 的做法：CarPlayCtrlServer 在会话一开始就常驻广播
        // `_carplay-ctrl._tcp`，之后每 1s force_announce，收到车机邀请才建 AirPlay）。
        // 我们此前要等 0x5703 才注册，导致「车机发现手机」这一步总落在热点绑定之后，
        // 每轮多花 1~3 秒；现在启动连接即发布，换网时 ensureForNetwork 会自动重注册。
        ctrlAdvertiser?.resetInviteCount()
        // 后台发布：start() 首次要向 Shizuku 解析真实蓝牙 MAC（shell），不能在主线程做
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // 真车路径：手机主动连车机（SDP …caff 配件服务）
        // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
        bluetoothClient?.start()
        bluetoothServer?.start()
        // 诊断（奥迪实测：手机与车机「蓝牙已连接」时车机不开放 CarPlay）：
        // 连之前把目标设备的蓝牙配置文件状态打出来，失败时一眼看出是不是这个原因。
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: 连接前「${target.name}」的蓝牙配置文件状态=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "未连接"),
            )
        }
    }

    /** 「断开连接」（可在任意 Activity 实例上调用——操作的是真管线对象）。
     *  [userInitiated]=用户手动断开（断开按钮/放弃使用）→ 装填自动重连冷却
     *  （与 [cancelConnecting] 同理：RFCOMM 断开必引 ACL 抖动，不装冷却用户
     *  点多少次断开都会被「上车自动启动」重连，2026-11-05 装机实测）；
     *  「下车自动结束」（AUTO_STOP_SESSION）不装——下次上车本就要自动连。 */
    fun disconnect(
        context: Context,
        userInitiated: Boolean = false,
    ) {
        val hadProjection = CarPlayService.mediaProjection != null
        if (userInitiated) {
            autoConnectSuppressedUntil = SystemClock.uptimeMillis() + AUTO_CONNECT_COOLDOWN_MS
            appendLog("SESSION: 手动断开——自动重连冷却 ${AUTO_CONNECT_COOLDOWN_MS / 60_000} 分钟（ACL 抖动不再自动拉起）")
            // 手动终态：抑制 stop 拆卸触发的 onEnded 误进保留态（见 suppressRetentionOnce
            // 注释），并清掉可能残留的保留态/计时器（此前意外断开保了屏、用户又手动断开的
            // 场景——destroy 照旧执行，保留态必须一起收掉，否则泄漏到下一轮）
            suppressRetentionOnce.set(true)
            cancelRetentionTimer()
            displayRetentionActive.set(false)
            retentionFailures = 0
            // 清复原挂起标记：下方已无条件复原一次，retireSession→stop 触发的
            // onEnded（非保留分支）再走 CAS 复原就是重复执行（2026-11-12 用户
            // 实测「已复原 x 个应用」出现两次的根因）
            orientationRestorePending.set(false)
        }
        // 熄火/出范围（AUTO_STOP，userInitiated=false）= 意外断开：进保留态保屏，
        // 下次上车 ACL 拉起连接后复用。手动断开（userInitiated=true）始终销毁。
        // 已在保留态（onEnded 先触发过）→ 幂等返回 true，不再重复进。
        val keepDisplaysOnAutoStop = !userInitiated && enterDisplayRetention()
        retireSession(keepDisplays = keepDisplaysOnAutoStop)
        // BT 控制会话只在这里（用户终态）退役，socket close 让保活读循环立刻退出：
        // 否则它阻塞到对端超时（实测 ~24s）才结束，用户取消后英雄卡持续转圈。
        // 配对中绝不能断——DiPlay 在隧道 iAP2 就绪前看到 RFCOMM 断开即判
        // bring-up failed 并拆整轮无线栈（含推流中的 AirPlay 会话，2026-10-16 实测）。
        detachActiveBtSessions()
        phaseHint = null
        CarPlayService
            .stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        boundCarSsid = null
        // 「强制应用横屏」覆盖的用户终态复原（原先在 retireSession 里——那里每轮
        // 配对起步也执行，会杀掉新一轮刚拉起的应用，2026-10-23 移出到真实终态）。
        // **仅手动断开执行**：复用重连轮次跳过了 applyAll（保屏语义），pending 未
        // 置位——无条件执行曾让每轮断连复原两次（2026-11-12 用户实测）；AUTO_STOP
        // /保留态不复原（覆盖要留着供重连复用）。pending 已在上方清零，同轮
        // onEnded 的 CAS 复原落空、不会重复。
        if (userInitiated) {
            appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
        }
        // shell 兜底存下的系统网络（connect-network）自动 forget 还原：无互联网热点
        // 被系统当默认网会挤掉移动数据。失败只记日志（用户可到 WLAN 手动删）。
        cleanupShellFallbackNetwork()
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
            appendLog("已手动断开并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
        } else {
            appendLog("已手动断开并停止保活服务（常亮已恢复）")
        }
    }

    // ---- 物理屏真息屏（同 scrcpy -S/--turn-screen-off）----

    /** 物理主屏是否被我们置为断电（只用于「要不要恢复」的判断，避免无谓的 AIDL 调用）。 */
    @Volatile
    private var phoneScreenOff = false

    /**
     * 电源切换串行化。enter/restore 都是 Bg 任务（cached 池不保证顺序），
     * 不加锁会出现「restore 先跑完、enter 后跑」→ 会话都结束了面板还被关掉。
     */
    private val screenPowerLock = Any()

    /**
     * 会话进入「真息屏」：把**物理主屏**硬件断电，系统仍保持唤醒，虚拟屏照常出帧。
     *
     * 与暗幕的区别：暗幕只是一层黑 View，触摸层仍通电（口袋误触、耗电）；
     * 这里是 SurfaceFlinger 级断电（触摸 IC 同步熄），观感等同锁屏，但**不锁屏**——
     * 车机侧的高德/QQ 音乐不会被 Keyguard 挂起。
     *
     * 失败只记日志：ROM 没给 shell 权限或取不到 display token 时，最坏退回暗幕。
     */
    private fun enterPhoneScreenOff() {
        val ctx = appContext ?: return
        // 没有在推流就不必关屏（设置面板无会话时切换也会走到这里）
        if (videoSender?.isRunning != true) return
        Bg.run("screen-power-off") {
            synchronized(screenPowerLock) {
                if (phoneScreenOff) return@synchronized
                // Bg 任务可能晚于会话结束才被执行：会话没了就绝不能再关屏
                if (videoSender?.isRunning != true) {
                    appendLog("SCREEN: 会话已结束——跳过物理息屏")
                    return@synchronized
                }
                // 手机若正在休眠：先唤醒让框架把物理屏置 ON；否则我们设 OFF 之后，框架的
                // 唤醒流程又会把面板点亮（两者抢同一个 SurfaceFlinger 电源状态）。
                val interactive =
                    runCatching {
                        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                    }.getOrDefault(true)
                if (!interactive) {
                    appendLog("SCREEN: 手机处于休眠——先唤醒再物理息屏（避免框架把面板重新点亮）")
                    Shell.exec("input keyevent KEYCODE_WAKEUP", 4_000L)
                    Thread.sleep(400)
                    // 无密码 / SmartLock 场景顺手划开锁屏；安全锁会失败，忽略即可
                    Shell.exec("wm dismiss-keyguard", 3_000L)
                }
                // 断电由暗幕承载（2026-10-24 用户实测定谳：面板断电后触摸 IC 仍工作，
                // 点击暗幕即唤醒；暗幕同时是口袋误触盾——没有它，兜里触摸会点到前台应用）。
                // enterDimNow = 上暗幕 + 断面板电 + 记账（panelOffByUs），与手动假息屏同路。
                if (ScreenDimmer.isAttached()) {
                    ScreenDimmer.enterDimNow()
                    phoneScreenOff = true
                    appendLog("SCREEN: 真息屏已进入（暗幕承载触摸检测——点暗幕唤醒，无需按电源键）")
                } else {
                    // 暗幕不可用（缺悬浮窗权限）降级裸断电：无触摸唤醒手段，只能按电源键
                    val ok = CarInputClient.setPhysicalScreenPower(false)
                    phoneScreenOff = ok
                    appendLog(
                        if (ok) {
                            "SCREEN: 物理屏已断电（暗幕不可用——无触摸唤醒手段，按电源键两次可恢复）"
                        } else {
                            "SCREEN: 物理屏断电失败（ROM 未给 shell 权限 / 取不到 display token）"
                        },
                    )
                }
            }
        }
    }

    /**
     * 设置面板切「连接时真息屏」：会话中立即收放（没有会话时什么都不做）。
     *
     * 车机屏上就能操作，所以手机面板黑着也不影响切换；关掉会立刻把面板点亮。
     */
    fun applyScreenOffPreference(enabled: Boolean) {
        if (enabled) {
            enterPhoneScreenOff()
        } else {
            restorePhoneScreen(sleepIfCarSide = false)
        }
    }

    /**
     * 恢复物理屏点亮。幂等：没关过就什么都不做。
     *
     * @param sleepIfCarSide 车机侧断开（人在车外、手机在口袋）为 true：直接让手机休眠，
     *   不先点亮面板（否则口袋里的手机会先亮一下再熄）；用户手动断开走 false。
     */
    private fun restorePhoneScreen(sleepIfCarSide: Boolean) {
        Bg.run("screen-power-restore") {
            synchronized(screenPowerLock) {
                if (!phoneScreenOff) return@synchronized
                phoneScreenOff = false
                if (sleepIfCarSide) {
                    // 保持 SurfaceFlinger 的 OFF 状态，让框架正常休眠；下次唤醒时框架会把面板置 ON
                    Shell.exec("input keyevent KEYCODE_SLEEP", 4_000L)
                    appendLog("SCREEN: 车机侧断开——已让手机休眠（面板保持黑）")
                } else {
                    // 暗幕承载的断电态在这里收口：撤暗幕 + 恢复面板电（幂等）
                    ScreenDimmer.exitPanelOff()
                    val ok = CarInputClient.setPhysicalScreenPower(true)
                    appendLog("SCREEN: 物理屏已恢复点亮 ok=$ok")
                }
            }
        }
    }

    /** 保留期时间窗（60s）：每窗算一次「重试」，连续 N 窗未恢复即销毁保留虚拟屏。 */
    private const val RETENTION_TICK_MS = 60_000L

    /** 保留期计数纯函数：第 failures 个窗口 ≥ maxRetries → 销毁（单测 RetentionCounterTest）。 */
    internal fun retentionShouldDestroy(
        failures: Int,
        maxRetries: Int,
    ): Boolean = failures >= maxRetries.coerceAtLeast(1)

    // ---- 意外断开保留虚拟屏（2026-11-10）----
    // 语义：车机软件拆链/WiFi 断（onEnded）与熄火 AUTO_STOP（disconnect(userInitiated=false)）
    // → 虚拟屏不动，进入保留态等自动重连复用；每 60s 一个重试窗口，连续 N 窗
    // （设置→显示）未恢复即销毁。手动断开/取消连接不进保留态，始终立即销毁。

    private val displayRetentionActive = AtomicBoolean(false)

    /**
     * 用户主动终态（手动断开/取消连接）对 onEnded 的一次性保留抑制。
     *
     * **VideoSender.stop() 的拆卸也会触发 onEnded**（2026-11-12 日志定谳：手动断开
     * → retireSession 先 stop 视频 → onEnded 在 detach 清 id 前的窗口里跑进来，
     * 看到屏 id ≥0 就误进保留态——保留标记/计时器泄漏到下一轮：
     * `retireSession(keep=true)` 误保 + attach 因 sessionRetained 已被 detach 清掉
     * 而重建，正好是用户报的两个症状）。disconnect(userInitiated=true) 与
     * cancelConnecting 在 retireSession 之前置位，onEnded 消费后走非保留分支。
     */
    private val suppressRetentionOnce = AtomicBoolean(false)

    @Volatile
    private var retentionFailures = 0

    private val retentionHandler = Handler(Looper.getMainLooper())

    private var retentionTick: Runnable? = null

    /**
     * 进入保留态（幂等——onEnded 与 AUTO_STOP 可能对同一会话双触发）：屏在 +
     * 偏好开才进入；返回 true = 当前处于保留态（调用方据此跳过虚拟屏销毁）。
     */
    private fun enterDisplayRetention(): Boolean {
        val ctx = appContext ?: return false
        if (!DisplayPrefs.keepDisplaysOnUnexpectedDisconnect(ctx)) return false
        if (VirtualLauncher.dockDisplayId < 0 || VirtualLauncher.appDisplayId < 0) return false
        if (!displayRetentionActive.compareAndSet(false, true)) return true // 已在保留态
        retentionFailures = 0
        VirtualLauncher.markRetained()
        // 断开即回桌面（2026-11-12 用户要求）：重连复用后车机先显示桌面，用户点回
        // 仪表盘时走 syncDashboard(true) 的完整对账（地图/固定应用拉回、卡片尺寸、
        // 合成布局逐项恢复）
        VirtualLauncher.resetUiToDesktopForRetention()
        appendLog(
            "SESSION: 意外断开——虚拟屏进入保留态（等待自动重连复用；" +
                "${DisplayPrefs.displayRetentionRetries(ctx)} 个重试窗口未恢复则销毁）",
        )
        startRetentionTimer()
        return true
    }

    /** 复用完成/新会话建立：退出保留态（清计时器与计数）。 */
    private fun exitDisplayRetention() {
        cancelRetentionTimer()
        if (displayRetentionActive.compareAndSet(true, false)) {
            retentionFailures = 0
            appendLog("SESSION: 保留虚拟屏已复用——退出保留态")
        }
    }

    private fun startRetentionTimer() {
        cancelRetentionTimer()
        val tick =
            object : Runnable {
                override fun run() {
                    if (!displayRetentionActive.get()) return
                    // 兜底防御：会话又活了（推流成功处已有 exitDisplayRetention，此为
                    // 双保险）——绝不能让保留期销毁打到活会话的虚拟屏上
                    if (videoSender?.isRunning == true) {
                        exitDisplayRetention()
                        return
                    }
                    val ctx = appContext
                    if (ctx == null) {
                        displayRetentionActive.set(false)
                        return
                    }
                    retentionFailures++
                    val max = DisplayPrefs.displayRetentionRetries(ctx)
                    if (retentionShouldDestroy(retentionFailures, max)) {
                        displayRetentionActive.set(false)
                        appendLog("SESSION: 保留期结束（连续 $max 个窗口未恢复）——销毁保留虚拟屏")
                        Bg.run("retention-destroy") { VirtualLauncher.detach() }
                        return
                    }
                    appendLog("SESSION: 保留期重试窗口 $retentionFailures/$max 未恢复，继续等待")
                    retentionHandler.postDelayed(this, RETENTION_TICK_MS)
                }
            }
        retentionTick = tick
        retentionHandler.postDelayed(tick, RETENTION_TICK_MS)
    }

    private fun cancelRetentionTimer() {
        retentionTick?.let { retentionHandler.removeCallbacks(it) }
        retentionTick = null
    }

    /** 会话结束/新周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。
     *  [keepDisplays]=保留虚拟屏（意外断开的保留态）：跳过 [VirtualLauncher.detach]——
     *  屏与屏上任务原样挂着，下轮 attach 走复用分支（setDisplaySurface 接回新编码
     *  surface）；其余管线（编码器/音频/控制通道）照常收口。 */
    fun retireSession(keepDisplays: Boolean = false) {
        videoSender?.stop()
        videoSender = null
        // 暗幕/常亮随会话收掉（本方法在每轮配对起步也会执行，新一轮推流起来后会重新置位）
        ScreenDimmer.setSessionActive(false)
        // 物理屏兜底恢复：崩溃/异常路径若把面板留在 OFF，这里不会让它烂在黑屏里
        restorePhoneScreen(sleepIfCarSide = false)
        audioSender?.stop()
        audioSender = null
        currentSharedSecret = null
        currentAudioHost = null
        if (keepDisplays) {
            appendLog("SESSION: 保留虚拟屏（意外断开）——跳过销毁，等待重连复用")
        } else {
            currentHiddenSource?.release()
        }
        currentHiddenSource = null
        TouchRouter
            .reset()
        currentClient?.cancelled = true
        currentClient?.close()
        currentClient = null
        // 注意：这里绝不能 detach BT 控制会话。本方法在每轮配对起步时也会执行，
        // 而 DiPlay 在 WiFi 隧道就绪前看到 RFCOMM 断开即判 bring-up failed、
        // closeWirelessStack 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），把已建立
        // 甚至正在推流的 AirPlay 会话一起拆掉——「连上约 3 秒即断」循环的根因
        // （2026-10-16 实测）。BT 退役只在用户终态做，见 disconnect()。
        // 解除会话静默：出站探测恢复（下次会话建立时会再次置位）
        BtSessionGate.sessionActive = false
        // 音频路由抢占自愈的节流状态随会话复位
        audioRouteReclaimLastAtMs = 0L
        audioRouteReclaimAttempts = 0
        // OEM 块随会话复位（新一轮 info 到达后重新发布）
        VirtualLauncher.setOemBlock(null)
        // 「强制应用横屏」覆盖的复原**不在这里做**（2026-10-23 奥迪 3315 真机日志定谳）：
        // 本方法在每轮配对起步时也会执行，此时距离上一轮会话结束只有几秒——上一轮的
        // 桌面/地图应用（与新一轮是同一批包）多半还活着；而本会话的应用在 attach 里
        // 随即拉起。resetAll 的 am compat disable 若在此窗口执行，杀掉的正是**新一轮
        // 刚拉起的应用** → 虚拟屏只剩静态壁纸 → 车机收到的画面永远不变 → 不切换
        // CarPlay UI（用户看到「推流中但车机还在原界面」）。
        // 复原只在真实终态执行：disconnect()（用户断开）与推流 onEnded（车机拆链），
        // 两处各自调用 resetAllAsync。新一轮的 applyAll 与旧覆盖幂等（enable 覆盖
        // enable），无需先复原再写入。
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
                appendLog("MODE: 投屏已就绪（当前无会话——下次连接时自动启用音频）")
                return
            }
        val secret = currentSharedSecret
        if (secret == null) {
            appendLog("MODE: 明文会话（未配对）无共享密钥——不补启音频")
            return
        }
        if (audioSender?.isRunning == true) return
        appendLog("MODE: 会话进行中，动态补启音频通道…")
        val profile = client.audioCandidates().firstOrNull()
        val audioControlPort = client.ensureAudioControlSocket()
        val planJson = client.endpointAudioPlanJsonFromInfo(controlPort = audioControlPort)
        if (profile == null || planJson == null) {
            appendLog("AUDIO: 动态音频 plan 不可用——跳过（不再走旧 SETUP 编排）")
            return
        }
        val plan = client.runFirmwareSetupPlan(planJson)
        val ev = plan?.streams?.get(profile.streamType.toInt())
        val info = ev?.let { client.audioStreamInfoFromEvent(it, profile) }
        if (info == null) {
            appendLog("AUDIO: 动态音频 plan 无流密钥——跳过")
            return
        }
        startAudioChannel(context, client, secret != null, currentAudioHost, profile to info)
    }

    /**
     * 0x5703 到达（拿到车机热点凭据）立即执行——真车 0x4301 的前置条件：
     * 车机在 WiFi 网络上发现手机的 `_carplay-ctrl._tcp`（TXT id=真实蓝牙 MAC）后
     * 主动发 `GET /ctrl-int/1/connect` 邀请，邀请被接受才发 0x4301。
     * 因此这里必须：绑车机 WiFi → 广播 ctrl；晚于 0x4300 则双方互等死锁（Audi 实测）。
     */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // 会话推流中收到的 0x5703 是冗余凭据（DiPlay 控制器周期重连会重发一轮
        // Phase 1，2026-10-15 实测「连上后自动断开」循环的推手之一）：此时再走绑定
        // 流程会 release+重绑进程网络（扰动正在推流的 socket）、force 重注册 ctrl
        // mDNS（DiPlay 视为手机重新上线、再拉一轮 RFCOMM）。只刷新密码缓存。
        if (videoSender?.isRunning == true) {
            lastWifiPsk = wifi.passphrase
            lastCarWifiSsid = wifi.ssid
            appendLog("0x5703 冗余凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
        lastCarWifiSsid = wifi.ssid
        phaseHint = "拿到车机热点凭据，绑定 WiFi…"
        appendLog(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // ctrl 广播已在 startConnection 时发布（catplay：连接前保持发布能力在线）。
        // 这里登记 SSID 并让注册对齐一次（手机若已手动连在车机热点上、Shizuku 不可用
        // 时这是唯一一次重注册机会）；加入热点后 bindCarWifiAndRegister 还会 force
        // 重注册，把组播落到车机所在网络（真车实测必需）。
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: 广播已在启动连接时发布，此处对齐到热点 ${wifi.ssid}")
        // 绑定与切换统一走自动加入流程：bindCurrentWifi 是「任意 WiFi 即成功」的盲绑，
        // 手机连着公司 WiFi 时会误判已在车机热点（ctrl 广播落在错误网络 → 双方互等死锁），
        // 必须先核对真实 SSID 再决定绑定或切换
        autoJoinCarWifi(wifi)
    }

    /**
     * 校对真实 WiFi 并加入车机热点。**静默路径优先**（2026-10-22 起）：
     *  1. `cmd wifi set-wifi-enabled` + `cmd wifi connect-network`（Shizuku）：系统直接
     *     连上热点，**0 弹窗、0 用户操作**；热点被存进系统配置，下次进入范围自动重连；
     *  2. `requestNetwork(TRANSPORT_WIFI)`（**不带 specifier**）拿到 WiFi 的 `Network`
     *     句柄并绑定进程网络——没有 specifier 就不会弹系统确认框；
     *  3. 各环节检查 [connectCancelled]——用户取消后不得继续跑（否则 phaseHint 被覆盖、
     *     ctrl 广播被重新拉起，表现为「取消不了」）。
     * 静默路径失败（Shizuku 不可用/命令行连网失败）才退回应用专属网络
     * （`WifiNetworkSpecifier`，首次加入会弹一次系统确认框）。
     * 全失败时回退「系统 WLAN 手动加入」指引（密码可经「去连接」复制）。
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            val shellReady =
                appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
            // WLAN 关着时先开（specifier 对关闭的无线电只会 onUnavailable/挂起）：
            // shell 可用走免打扰开启，否则走带指引的等待
            if (!connectCancelled && shellWifiDisabled()) {
                if (shellReady) {
                    if (!ensureWifiEnabledSilently()) return@run
                } else if (!waitForWifiEnabled()) {
                    return@run
                }
            }
            if (connectCancelled) return@run
            // 1) specifier 优先（方案 C，2026-10-25）：应用专属网络不进系统、不动默认网、
            //    其它应用照常 5G；内部自带快速/深度兜底（specifierQuick/DeepFallback）。
            //    静默 connect-network 只覆盖两类场景：用户拒了 specifier 系统弹窗
            //    （拒绝弹窗≠不想连，cmd wifi 不经弹窗）、WEP（specifier 表达不了）。
            when (val result = joinCarWifiSpecifier(wifi)) {
                is WifiCarPlayConnector.JoinResult.Joined -> return@run

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                    if (shellReady &&
                        !connectCancelled &&
                        joinCarWifiSilently(wifi) is WifiCarPlayConnector.JoinResult.Joined
                    ) {
                        return@run
                    }
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> {
                    // 热点已连上、只是 Network 通道没绑成：不再走任何入网路径
                    // （同款短路判断），直接中止并提示重连
                    if (connectCancelled) return@run
                    if (shellWifiStatus().second == wifi.ssid) {
                        phaseHint = "已连上车机热点但未能绑定网络——请重新连接"
                        appendLog(
                            "WIFI: 已连在 \"${wifi.ssid}\" 但 requestNetwork 未回调——本轮中止" +
                                "（不退回会弹窗的 specifier 路径）",
                        )
                        return@run
                    }
                    // 用户拒了 specifier 弹窗（onUnavailable）：静默直连兜底
                    if (shellReady &&
                        !connectCancelled &&
                        joinCarWifiSilently(wifi) is WifiCarPlayConnector.JoinResult.Joined
                    ) {
                        return@run
                    }
                    promptSpecifierFailed(wifi, shellReady)
                }
            }
        }
    }

    /**
     * 端点先于 0x5703 到达（或上一轮换网失败）时的入网入口。
     *
     * **specifier 优先（方案 C，2026-10-25 用户定夺）**：应用专属网络不进系统、
     * 不动默认网、其它应用照常 5G——这些是 connect-network 拿不到的。静默
     * connect-network 降为兜底：它把热点**存成系统网络**，无互联网时会被 ROM
     * 自动断开/切回有网网络（荣耀实测「WiFi 自动断开」的直接来源），且存过的
     * SSID 会毒化之后所有 specifier 请求（永久 pending，2026-10-22 定谳）。
     * specifier 内部已有快速/深度兜底（specifierQuickFallback/DeepFallback），
     * 这里补走静默直连覆盖的场景：用户拒了 specifier 弹窗（onUnavailable，内部
     * 兜底不跑）、WEP（specifier 不支持但 cmd wifi 支持）。
     */
    private fun joinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): WifiCarPlayConnector.JoinResult {
        val viaSpecifier = joinCarWifiSpecifier(wifi)
        if (viaSpecifier is WifiCarPlayConnector.JoinResult.Joined) return viaSpecifier
        // 热点已连上但通道没绑成：不再走任何入网路径（specifier 同款短路判断）
        if (!connectCancelled && shellWifiStatus().second == wifi.ssid) return viaSpecifier
        if (!shellReady || connectCancelled) return viaSpecifier
        val silent = joinCarWifiSilently(wifi)
        // 静默也失败时回传 specifier 的结果：WEP（Unsupported）要让调用方转
        // legacyJoinCarWifi，不能被静默的 Unavailable 顶掉
        return if (silent is WifiCarPlayConnector.JoinResult.Joined) silent else viaSpecifier
    }

    /**
     * 静默加入车机热点（**两步式，全程 0 弹窗**）。
     *
     * 第一步：Shizuku（uid 2000）执行 `cmd wifi connect-network "<SSID>" wpa2 "<密码>"`，
     * 系统直接关联热点并把它存成普通网络——不经过 `WifiNetworkSpecifier`，
     * 因此**不触发系统确认框**（specifier 的弹窗是框架层强制行为，应用关不掉）。
     *
     * 第二步：用**不带 specifier** 的 `requestNetwork(TRANSPORT_WIFI)` 捕获刚连上的
     * WiFi 的 `Network` 句柄并绑定进程网络（[bindCarWifiAndRegister]）。没有 specifier，
     * 系统不会弹窗，也能立刻把车机 WiFi 的 `Network` 回调给我们。
     *
     * 为什么第二步仍要 `bindProcessToNetwork`（而不是只把 network 传给 NsdManager）：
     *  - mDNS 广播 `_carplay-ctrl._tcp` 的 `NsdManager.registerService` **没有 Network
     *    参数**（API 36 的 `AdvertisingRequest` 才有，本项目 compileSdk 34），注册跟随
     *    **进程默认网络**。不绑 → 广播从蜂窝出去 → 车机发现不了手机 → 0x4301 永不到达；
     *  - AirPlay 控制/事件/视频/音频的多处裸 `Socket()`/`DatagramSocket()` 都依赖进程绑定；
     *  - 绑定只作用于**本进程的 socket**：虚拟屏里的导航/音乐是独立进程，不受影响
     *    （`bindProcessToNetwork` 不是全局/系统级操作）。
     */
    private fun joinCarWifiSilently(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        // 与 specifier 兜底路径共用同一把锁：两条路径都会 release + 重绑进程网络。
        // 持锁实现见 [joinCarWifiSilentlyLocked]。
        return synchronized(wifiBindLock) { joinCarWifiSilentlyLocked(wifi) }
    }

    /** [joinCarWifiSilently] 的持锁实现（调用方必须持有 [wifiBindLock]）。 */
    private fun joinCarWifiSilentlyLocked(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val existing = connector.currentNetwork
        if (boundCarSsid == wifi.ssid && existing != null && !connector.isBoundNetworkAlive()) {
            appendLog("WIFI: 记录的车机热点网络已失效——重新建立网络通道")
            boundCarSsid = null
        }
        val existingAlive = connector.currentNetwork
        if (boundCarSsid == wifi.ssid && existingAlive != null && connector.isBoundNetworkAlive()) {
            appendLog("WIFI: 车机热点已就绪且网络通道已绑定——跳过重复加入")
            return WifiCarPlayConnector.JoinResult.Joined(existingAlive)
        }
        if (!ensureWifiEnabledSilently()) return WifiCarPlayConnector.JoinResult.Unavailable
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已连在车机热点 \"${wifi.ssid}\"——直接建立网络通道并绑定")
        } else {
            phaseHint = "正在静默连接车机热点 ${wifi.ssid}…"
            appendLog(
                ">>> 静默加入车机热点 \"${wifi.ssid}\"（cmd wifi connect-network，0 弹窗；" +
                    "当前 ${current ?: "未连接"}）",
            )
            // 与 specifierDeepFallback 同款收口（方案 C，2026-10-25）：connect-network
            // 返回 ok 的瞬间热点已进系统配置，必须立即登记会话末清除——遗留的系统
            // 网络会被 ROM 因无互联网自动断开/切回，且毒化之后所有 specifier 请求
            // （同 SSID 永久 pending）。例外只认「用户选过保留」（isUserKept）：
            // 旧判据「系统里已存在」会把自己遗留的保存当用户意愿，毒化自延续。
            val preExisting = savedNetworkIds(wifi.ssid).isNotEmpty()
            // 命令把连接投给 WifiService 就返回，exit=0 不代表已连上 —— 必须轮询 SSID
            val result =
                Shell.exec(
                    WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                    20_000L,
                )
            if (!result.ok) {
                appendLog(
                    "WIFI: connect-network 失败（exit=${result.exitCode} " +
                        "${result.output.take(80)}）",
                )
                return WifiCarPlayConnector.JoinResult.Unavailable
            }
            when {
                isUserKept(wifi.ssid) ->
                    appendLog("WIFI: \"${wifi.ssid}\" 用户选过保留——不作会话末清除")
                preExisting ->
                    appendLog(
                        "WIFI: \"${wifi.ssid}\" 此前已保存但来历不明——保守不自动清除" +
                            "（连接弹窗里选「清除」可解除对 specifier 的毒化）",
                    )
                else -> {
                    shellFallbackCleanupSsid = wifi.ssid
                    appendLog("WIFI: connect-network 已执行（新存系统网络，已登记会话末自动清除）")
                }
            }
            if (!waitForCarSsid(wifi.ssid, CAR_WIFI_JOIN_WAIT_MS)) {
                return WifiCarPlayConnector.JoinResult.Unavailable
            }
        }
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val bound = bindCarWifiAndRegister(wifi.ssid)
        val network = connector.currentNetwork
        return if (bound && network != null) {
            WifiCarPlayConnector.JoinResult.Joined(network)
        } else {
            WifiCarPlayConnector.JoinResult.Unavailable
        }
    }

    /** WLAN 关着时经 Shizuku 直接打开（`cmd wifi set-wifi-enabled`），返回是否已开启。 */
    private fun ensureWifiEnabledSilently(): Boolean {
        if (!shellWifiDisabled()) return true
        appendLog("WIFI: WLAN 未开启——经 Shizuku 打开（cmd wifi set-wifi-enabled enabled，免手动）")
        val result = Shell.exec("cmd wifi set-wifi-enabled enabled", 8_000L)
        if (!result.ok) {
            appendLog("WIFI: 打开 WLAN 失败（exit=${result.exitCode} ${result.output.take(60)}）")
            return false
        }
        val deadline = android.os.SystemClock.uptimeMillis() + WIFI_ENABLE_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            if (!shellWifiDisabled()) return true
            Thread.sleep(500)
        }
        if (shellWifiDisabled()) {
            appendLog("WIFI: 等待 WLAN 开启超时（${WIFI_ENABLE_WAIT_MS / 1000}s）")
            return false
        }
        return true
    }

    /** 轮询 `cmd wifi status` 直到关联到 [ssid]（可取消）。 */
    private fun waitForCarSsid(
        ssid: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var lastLogAt = 0L
        var current: String? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            current = shellWifiStatus().second
            if (current == ssid) {
                appendLog("WIFI: 已切到车机热点 \"$ssid\"")
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogAt >= 3_000L) {
                lastLogAt = now
                phaseHint = "正在切换到车机热点 $ssid…"
                appendLog("WIFI: 等待切换到 \"$ssid\"（当前 ${current ?: "未连接"}）…")
            }
            Thread.sleep(500)
        }
        appendLog("WIFI: 等待切换到 \"$ssid\" 超时（当前 ${current ?: "未连接"}）")
        return false
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点，成功后进程网络已绑定。
     *
     * **specifier 优先（方案 C，2026-10-25）**：该网络只对本进程可见、不保存、
     * 不成为默认网络、其它应用照常 5G，代价是首次加入弹一次系统确认框。
     * 系统里存过同 SSID 时入口先问「清除/保留」——存过的 SSID 会让 specifier
     * 永久挂起，且「保留」的选择是 connect-network 兜底免清理例外的唯一来源。
     */
    private fun joinCarWifiSpecifier(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        // 注意：**不得在此处弹「清除/保留」询问**——它在连接关键路径上会阻塞等用户
        // 应答（6318 实测 3.4s，车机 15s 窗口经不起这种消耗；2026-10-25 用户实测回归）。
        // 「已保存 SSID → specifier 挂起」也只是部分 ROM 的形态（本机存过 netId=69
        // 依然 0.8s 入网）——毒化由深度兜底 + 会话末清理解；用户选择只在
        // [promptSpecifierFailed]（连接已失败、无关键路径）里收集。
        phaseHint = "正在加入车机热点 ${wifi.ssid}…（不影响移动数据）"
        appendLog(
            ">>> 以应用专属网络加入 \"${wifi.ssid}\"（不保存为系统 WiFi，其它应用继续走 4G/5G）",
        )
        val result =
            synchronized(wifiBindLock) {
                val existing = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existing != null && !connector.isBoundNetworkAlive()) {
                    // 网络失效检测（2026-11-04 ENONET 案）：系统把进程切回家庭
                    // WiFi / 热点网络被回收——记录还在但网络已死，跳过=整轮废
                    appendLog("WIFI: 记录的车机热点网络已失效（系统切回其它网络/网络被回收）——重新加入")
                    boundCarSsid = null
                }
                val existingAlive = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existingAlive != null && connector.isBoundNetworkAlive()) {
                    appendLog("WIFI: 应用专属网络已在位——跳过重复加入")
                    WifiCarPlayConnector.JoinResult.Joined(existingAlive)
                } else {
                    // 前置探测（2026-11-04 DiPlay 实测）：华为保存过热点 SSID →
                    // specifier 馪窗 5s 零回调（部分 ROM 对已保存 SSID 永久 pending），
                    // 每轮都靠快速兜底——系统已连着车机热点时直接绑定，省掉必输的
                    // 5s（车机入网预算 ~9-14s 经不起白等）。探测内部有 shellWifiReady
                    // 守卫（Shizuku 不可用/系统未连着 → null，落回 specifier 正常流程）。
                    specifierQuickFallback(wifi)?.let { bound ->
                        ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
                        appendLog(">>> WiFi 已绑定现有连接（系统已连着热点），ctrl 广播已重新注册")
                        return WifiCarPlayConnector.JoinResult.Joined(bound)
                    }
                    connector.joinCarPlayNetwork(
                        ssid = wifi.ssid,
                        passphrase = wifi.passphrase,
                        securityType = wifi.securityType,
                        timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                        isCancelled = { connectCancelled },
                        quickFallback = { specifierQuickFallback(wifi) },
                        onPendingHint = {
                            phaseHint = "请在系统弹窗中确认连接车机热点"
                            appendLog(">>> 请在系统弹窗中点「连接」以加入 \"${wifi.ssid}\"（弹框等待中）")
                        },
                        deepFallback = { joinViaConnectNetwork(wifi) },
                    )
                }
            }
        if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog(">>> WiFi 已加入车机热点（应用专属网络），ctrl 广播已重新注册，等待车机 ctrl 邀请")
        } else if (!connectCancelled) {
            appendLog("WIFI: 应用专属网络加入未成功（${result.javaClass.simpleName}）")
            // 兜底：系统里保存过该热点时，系统可能已自动全局连上它——specifier 挂起
            // 的形态下这条近路反而最稳（2026-10-22 真机：用户选「保留系统网络」+
            // Android 16 specifier 永久 pending）。校验当前 SSID 后直接绑定现有连接。
            val currentSsid = shellWifiStatus().second
            if (currentSsid == wifi.ssid) {
                appendLog("WIFI: 系统当前已连着 \"$currentSsid\"——直接绑定现有连接（保存网络自动连上的近路）")
                // bindCurrentWifi 异步回调：短等绑定完成（回调很快，系统已在网）
                if (bindExistingCarWifi(connector, wifi.ssid) != null) {
                    boundCarSsid = wifi.ssid
                    phaseHint = "已加入车机热点"
                    ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
                    appendLog(">>> WiFi 已绑定现有 \"$currentSsid\" 连接，ctrl 广播已重新注册")
                    return WifiCarPlayConnector.JoinResult.Joined(connector.currentNetwork!!)
                }
                appendLog("WIFI: 绑定现有连接未成功——按失败处理")
            }
        }
        return result
    }

    /**
     * 本轮 shell 兜底把热点存成了系统网络时记录的 SSID——会话终态（disconnect 与
     * 推流 onEnded）自动 `forget` 还原。系统级保存无互联网热点会被当成默认网、
     * 部分 ROM 连带挤掉移动数据。
     */
    @Volatile
    private var shellFallbackCleanupSsid: String? = null

    /**
     * specifier 挂起时的快速兜底（首窗 5s 零回调后调用，运行于连接流程线程）。
     *
     * 探测：`forget` 旧网络后系统可能已自动重连同 SSID——是则直接绑定现有连接。
     * 必须保持毫秒级 shell 往返 + ≤3s 绑定等待：车机从 0x5703 起算的入网预算只有
     * ~9-14s（0x4301 后 ~15s 拆 BT），这里每多 1s 都在挤后面「重发 specifier」
     * （实测 1.6s 入网、被验证有效的恢复手段）的窗口。
     *
     * @return 已绑定并可直接使用的网络；null = 未命中（调用方继续重发 specifier）。
     */
    private fun specifierQuickFallback(wifi: Iap2SenderSession.WifiConfiguration): Network? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        val current = shellWifiStatus().second
        if (current != wifi.ssid) return null
        appendLog("WIFI: 系统已连着 \"$current\"——直接绑定（快速兜底）")
        val network = bindExistingCarWifi(connector, wifi.ssid)
        if (network != null) boundCarSsid = wifi.ssid
        return network
    }

    /**
     * shell 直连热点：`cmd wifi connect-network`（**兜底**——specifier 核心价值 =
     * 不进系统全局网络列表，本函数保存的全局网络只在最后关头使用且会话末清除；
     * DiPlay 实测 specifier 双条件下两窗 25s 全挂而本函数 0.6s 入网——作兜底时
     * 本轮窗口必然已过，价值是为下一轮占位 + 弹框应答失败后的确定性收口）。
     *
     * 清理登记的时机是**关键**：connect-network 返回 ok 的瞬间该热点已进系统配置，
     * 必须立即登记 [shellFallbackCleanupSsid]——若等「轮询到切网 + bind 成功」才
     * 登记，取消/超时/bind 失败三条路径会在登记前退出，系统网络就此遗留（全局
     * 自动连无互联网热点、挤掉移动数据，2026-10-23 审阅定谳）。forget 一个没保存过
     * 的 SSID 是无害 no-op，宁多勿漏。
     *
     * 例外**只认用户选择**（isUserKept，2026-10-25 定谳）：「系统里已存在」不是用户
     * 意愿的证据——可能正是我们自己上一轮遗留的保存；来历不明（存在但非用户保留）
     * 保守不删，由 specifier 入口的「清除/保留」询问流程正当解决。
     */
    private fun joinViaConnectNetwork(wifi: Iap2SenderSession.WifiConfiguration): Network? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        appendLog(
            "WIFI: specifier 两窗均无回调——深度兜底 connect-network 直连 \"${wifi.ssid}\"" +
                "（本轮窗口大概率已过，为下一轮占位）",
        )
        val preExisting = savedNetworkIds(wifi.ssid).isNotEmpty()
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val r = Shell.exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!r.ok) {
            appendLog("WIFI: connect-network 失败（${r.output.take(80)}）")
            return null
        }
        // 例外只认「用户选过保留」（isUserKept）——「系统里已存在」不是用户意愿的
        // 证据（可能正是我们上一轮遗留的保存，2026-10-25 定谳毒化自延续根因）
        when {
            isUserKept(wifi.ssid) ->
                appendLog("WIFI: \"${wifi.ssid}\" 用户选过保留——不作会话末清除")
            preExisting ->
                appendLog(
                    "WIFI: \"${wifi.ssid}\" 此前已保存但来历不明——保守不自动清除" +
                        "（连接弹窗里选「清除」可解除对 specifier 的毒化）",
                )
            else -> {
                shellFallbackCleanupSsid = wifi.ssid
                appendLog("WIFI: connect-network 已执行（新存系统网络，已登记会话末自动清除）")
            }
        }
        // 轮询切网完成（断旧网/关联/DHCP，实测 2-10s）
        repeat(30) {
            if (connectCancelled) return null
            Thread.sleep(300)
            if (shellWifiStatus().second == wifi.ssid) {
                val network = bindExistingCarWifi(connector, wifi.ssid)
                if (network != null) {
                    boundCarSsid = wifi.ssid
                    appendLog("WIFI: connect-network 已连上 \"${wifi.ssid}\"")
                }
                return network
            }
        }
        appendLog("WIFI: connect-network 后 9s 未切到车机热点——按失败返回（系统网络已登记，会话末清除）")
        return null
    }

    /** 绑定当前已连接的 WiFi 并短等绑定完成（兜底路径共用）。返回绑定的网络或 null。 */
    private fun bindExistingCarWifi(
        connector: WifiCarPlayConnector,
        ssid: String,
    ): Network? {
        var boundOk = false
        // ssidHint：绑定断开（熄火）时经 onNetworkLost 通知会话层——兜底入网是主路径，
        // 不通知的话英雄卡停在「已连接」、会话拖到心跳超时才死
        connector.bindCurrentWifi(ssid) { boundOk = it }
        val waitUntil = SystemClock.uptimeMillis() + 3_000
        while (!boundOk &&
            connector.currentNetwork == null &&
            SystemClock.uptimeMillis() < waitUntil &&
            !connectCancelled
        ) {
            Thread.sleep(100)
        }
        return connector.currentNetwork
    }

    /** 会话终态清理 shell 兜底存的系统网络（[disconnect] 与推流 onEnded 共用）。 */
    private fun cleanupShellFallbackNetwork() {
        val ssid = shellFallbackCleanupSsid ?: return
        shellFallbackCleanupSsid = null
        Bg.run("wifi-fallback-cleanup") {
            // netId 形态优先（2026-11-04 DiPlay 实测：华为对 SSID 形态 forget
            // 返回失败——cmd wifi list-networks 解析 netId 后按 id 删）
            val ids = savedNetworkIds(ssid)
            val r =
                if (ids.isNotEmpty()) {
                    val results = ids.map { Shell.exec("cmd wifi forget-network $it", 5_000L) }
                    Shell.Result(if (results.all { it.ok }) 0 else 1, results.joinToString(";") { it.output.take(40) })
                } else {
                    Shell.exec("cmd wifi forget-network ${shellQuote(ssid)}", 5_000L)
                }
            appendLog(
                "WIFI: 会话末清除 shell 兜底保存的 \"$ssid\" ok=" + r.ok +
                    (if (!r.ok) "（请到 系统设置>WLAN 手动删除）" else ""),
            )
        }
    }

    /** Shizuku 就绪才可跑 `cmd wifi`（与其它 shell 调用同一守卫）。 */
    private fun shellWifiReady(): Boolean = appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true

    /**
     * 系统里已保存同名热点时，询问用户是否清除（**不擅自删除**）。
     *
     * 为什么要问而不是直接删：保留也有正当用途——用户可能就想让系统自动连车机、
     * 或车机热点确实带互联网（部分车型提供流量）。删除只影响「系统会不会全局
     * 自动连它」，与本轮连接无关；选择「保留」时继续正常连接。
     *
     * 每个 SSID 每个进程只问一次：配对失败重试、端点交接重入都会再次走到这里，
     * 重复弹窗会挡住连接（2026-10-18 实测）。用户的选择在本次进程内记忆。
     *
     * 阻塞直到用户选择（[JOIN_DIALOG_TIMEOUT_S] 超时按「保留」处理——不做破坏性动作）。
     */
    private fun promptForgetSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: 系统里已保存热点 \"$ssid\"（netId=${ids.joinToString(",")}）" +
                "——询问用户是否清除（保留也能正常连接）",
        )
        val forget =
            askWifiJoinChoice(
                DialogSpec(
                    title = "已保存该车机热点",
                    message =
                        "系统里保存过 \"$ssid\"。保留会让手机在进入范围时自动连接它，" +
                            "若该热点没有互联网，可能导致手机无法使用移动数据。\n\n" +
                            "「清除」只是不再自动连接，不影响本次使用；" +
                            "如果车机热点本身提供网络，选「保留」。",
                    positive = "清除保存",
                    negative = "保留",
                ),
            )
        if (connectCancelled) return
        when (forget) {
            true -> {
                ids.forEach { id ->
                    val result = Shell.exec("cmd wifi forget-network $id", 5_000L)
                    appendLog(
                        "WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=$id ok=${result.ok}）",
                    )
                }
                // 下次 specifier 才能正常工作（存过的 SSID 请求永久挂起）
                markUserKept(ssid, false)
            }

            // 「保留」持久化：这是 connect-network 兜底「不作会话末清除」例外的
            // 唯一合法来源（2026-10-25 前例外按「系统里已存在」判定，被我们自己
            // 遗留的保存冒名顶替，毒化自延续）
            false -> {
                markUserKept(ssid, true)
                appendLog("WIFI: 用户选择保留系统保存的 \"$ssid\"——继续以应用专属网络连接")
            }

            // 无人应答（UI 不在前台）：按「保留」处理，绝不在用户未确认时删网络
            null -> {
                markUserKept(ssid, true)
                appendLog("WIFI: 无人应答是否清除保存的网络——按保留处理（不改用户配置）")
            }
        }
    }

    /** 查系统已保存的、SSID 与目标完全一致的网络 id（`cmd wifi list-networks`）。 */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = Shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: 无法读取系统已保存网络（exit=${listing.exitCode}）——跳过检查")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** WiFi 未开启时的引导与等待；true = 已开启可继续。 */
    private fun waitForWifiEnabled(): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "WiFi 未开启",
                    message = "连接车机热点需要打开手机 WLAN。是否打开 WLAN 设置？",
                    positive = "打开 WLAN 设置",
                    negative = "取消",
                ),
            )
        if (connectCancelled) return false
        when (open) {
            true -> {
                openWlanSettings()
                uiHintSink?.invoke("请在 WLAN 设置中打开 WiFi 开关")
            }

            false -> {
                phaseHint = "已取消——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 用户取消——自动加入中止（WiFi 未开启）")
                return false
            }

            null -> {
                phaseHint = "WiFi 未开启——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !connectCancelled) {
            if (waited == 0) {
                phaseHint = "等待打开 WLAN（最长 120 秒，取消连接可中止）"
                appendLog("WIFI: 等待用户打开 WLAN（最长 120 秒，取消连接可中止）")
            }
            Thread.sleep(2_000)
            waited += 2_000
        }
        if (connectCancelled) {
            phaseHint = "已取消连接"
            return false
        }
        if (shellWifiDisabled()) {
            phaseHint = "WiFi 未开启——无法连接车机热点"
            appendLog("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接")
            return false
        }
        return true
    }

    /** 静默路径与应用专属网络都失败：让用户重试或转手动连接。 */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (connectCancelled) return
        // 已存同 SSID 是 specifier 挂起的常见形态：这里已是失败分支（无关键路径），
        // 顺势问一次「清除/保留」——「清除」解除毒化让重试走通专属网络路径，
        // 「保留」持久化为例外依据（见 markUserKept）。连接路径上绝不弹（6318 定谳）。
        promptForgetSavedNetworkIfAny(wifi.ssid)
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "未能加入车机热点",
                    message =
                        "自动加入 \"${wifi.ssid}\" 没有成功（热点可能不在范围内，或系统拒绝了连接）。\n\n" +
                            "「重试」会再自动加入一次（先走免弹窗的静默路径，仍不行才会弹系统确认框）；" +
                            "也可以到 WLAN 设置手动连接（密码已复制到剪贴板）。",
                    positive = "重试",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        when (choice) {
            true -> {
                when (val retry = joinCarWifi(wifi, shellReady)) {
                    is WifiCarPlayConnector.JoinResult.Joined -> Unit
                    WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady)
                    WifiCarPlayConnector.JoinResult.Unavailable -> {
                        phaseHint = "未能加入车机热点——请到 WLAN 设置手动连接"
                        appendLog("WIFI: 重试仍未加入 \"${wifi.ssid}\"——请到系统 WLAN 手动连接")
                    }
                }
            }

            false -> manualJoinCarWifi(wifi)

            null -> {
                phaseHint = "未能加入车机热点——请重新连接"
                appendLog("WIFI: 弹窗无人应答——自动加入中止")
            }
        }
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        phaseHint = "请在 WLAN 设置中连接 ${wifi.ssid}…"
        appendLog(
            "WIFI: 用户选择手动连接——等待 WLAN 连上 \"${wifi.ssid}\"（最长 120 秒）；" +
                "注意：手动连接会把该热点保存为系统网络，若它没有互联网可能影响移动数据",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (connectCancelled) return
            Thread.sleep(5_000)
            if (shellWifiStatus().second == wifi.ssid) {
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
        }
        phaseHint = "尚未连接 ${wifi.ssid}——自动加入已中止"
        appendLog("WIFI: 手动连接等待超时——自动加入中止")
    }

    /**
     * 旧路径（WEP 等 specifier 无法表达的安全类型）：`cmd wifi connect-network`
     * 系统级保存并切换。注意这会保存热点为系统网络——无互联网时可能影响移动数据，
     * 只作为兼容兜底。
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且 Shizuku 未就绪（无法自动加入）——" +
                    "请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已在车机热点 \"$current\"——直接绑定")
            bindCarWifiAndRegister(wifi.ssid)
            return
        }
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "连接车机热点",
                    message =
                        "将自动加入 \"${wifi.ssid}\" 热点。该热点无互联网，系统可能自动切回原 WiFi。" +
                            "\n\n出现断连时，请到 WLAN 设置中主动连接 \"${wifi.ssid}\"（手动连接不会被切回）。",
                    positive = "继续自动加入",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        if (choice == false) {
            manualJoinCarWifi(wifi)
            return
        }
        if (choice == null) {
            phaseHint = "未能加入车机热点——请重新连接"
            appendLog("WIFI: 弹窗无人应答——自动加入中止")
            return
        }
        phaseHint = "正在自动加入车机热点 ${wifi.ssid}…"
        appendLog(
            ">>> 手机当前 WiFi: ${current ?: "未连接"}（非车机热点）——" +
                "经 Shizuku 自动切换到 \"${wifi.ssid}\"",
        )
        if (connectCancelled) return
        val result =
            Shell.exec(
                WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                10_000L,
            )
        if (!result.ok) {
            phaseHint = "自动加入热点失败——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                "WIFI: connect-network 失败（${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…若被切回原 WiFi，请在 WLAN 中手动连接"
        appendLog("WIFI: connect-network 已发起（自动保存网络，等待切换…）")
        // 系统切换是异步的（断开当前网/扫描/关联/DHCP，2-10 秒）：轮询 SSID 确认。
        repeat(20) {
            if (connectCancelled) {
                appendLog("WIFI: 自动加入已取消（用户断开）")
                return
            }
            val ssid = shellWifiStatus().second
            if (ssid == null) {
                // status 命令不可用（非 AOSP 系统）：退化为直接尝试绑定（无 SSID 校验）
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            Thread.sleep(800)
        }
        phaseHint = "未能在热点驻留——请到 WLAN 设置手动连接 ${wifi.ssid}"
        uiHintSink?.invoke("未能在热点驻留——请到 WLAN 设置中主动连接 ${wifi.ssid}")
        appendLog(
            ">>> WiFi 自动加入超时（可能被系统切回原 WiFi）——" +
                "请到 系统设置>WLAN 手动连接 \"${wifi.ssid}\"（手动连接不会被自动切回）",
        )
    }

    /**
     * 绑定当前 WiFi（应为车机热点）并重注册 ctrl 广播；绑定超时给出指引。
     *
     * 绑定动作用 [wifiBindLock] 串行化：auto-join 线程与端点交接线程都可能触发，
     * 并发调用 bindCurrentWifi 会互相 release（解绑瞬间 mDNS 广播直接失效）。
     *
     * 等待窗口 [WIFI_BIND_WAIT_S] 必须够长：`requestNetwork(WIFI)` 的 onAvailable
     * 在「手机还没连上任何 WiFi、用户正在系统设置里手动加入热点」时，要等到真正
     * 关联+DCHP 完成才回调。旧值 5s 在奥迪实测中是致命竞态——20:02:09.352 判定
     * 「不在热点」放弃整轮会话，20:02:09.467（**116ms 后**）onAvailable 就到了并
     * 绑定成功；用户被迫多等一轮 BT 重连（+13s）。宁可可中断地多等，也不要假失败。
     *
     * @return 进程网络是否绑定成功
     */
    private fun bindCarWifiAndRegister(ssid: String): Boolean {
        var bound = false
        synchronized(wifiBindLock) {
            // 另一个路径已经绑定同一热点：别再走一遍 release+requestNetwork
            if (boundCarSsid == ssid && wifiConnector?.currentNetwork != null) {
                bound = true
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                wifiConnector?.bindCurrentWifi(ssid) { ok ->
                    bound = ok
                    latch.countDown()
                }
                // 分片等待：期间用户取消要能立刻退出，不必等满窗口
                val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
                while (!connectCancelled &&
                    bound.not() &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
                if (bound) boundCarSsid = ssid
            }
        }
        if (bound && !connectCancelled) {
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            appendLog(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
        } else if (!connectCancelled) {
            phaseHint = "未能绑定车机热点 $ssid 的网络通道"
            appendLog(
                ">>> 等待 ${WIFI_BIND_WAIT_S}s 仍未拿到 \"$ssid\" 的 WiFi 网络句柄——" +
                    "请确认 WLAN 已连上该热点后重新连接",
            )
        }
        return bound
    }

    /**
     * 弹窗询问用户（阻塞调用线程直到选择/关闭/90 秒超时）：
     * true=positive；false=negative；null=无人应答（调用方决定兜底）。
     */
    private fun askWifiJoinChoice(spec: DialogSpec): Boolean? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice: Boolean? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sink = dialogSink
            if (sink == null) {
                // 无 UI 宿主（Activity 销毁窗口）：宁可中止也不默认替用户决定加入
                latch.countDown()
                return@post
            }
            sink(spec) { result ->
                choice = result
                latch.countDown()
            }
        }
        latch.await(JOIN_DIALOG_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        return choice
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** 热点密码复制到剪贴板（手动到 WLAN 页连接时粘贴），并提示用户。 */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("热点密码已复制（${passphrase.length} 位）——粘贴到密码框即可")
                }
            }
        }
    }

    /**
     * WiFi 开关与当前连接的 SSID（一次 `cmd wifi status` 查询）。
     * 输出形如：`Wifi is enabled` + `Wifi is connected to "Redmi K40"`（未连接无此行）。
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            Shell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\''") + "'"

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /** 音频路由抢占自愈的节流状态（每会话最多 5 次、间隔 ≥30s；retireSession 复位）。 */
    private var audioRouteReclaimLastAtMs = 0L

    private var audioRouteReclaimAttempts = 0

    /**
     * 会话中音频路由被抢占的自愈（2026-10-26 荣耀+6318 定谳）：耳机/车机 A2DP
     * 回连后手机媒体输出切去蓝牙，AudioPlaybackCapture 采不到（全零），推给车机的
     * 就是静音流。判定「采集全零（回调已含）+ 确有应用在播」即断开全部音频类蓝牙
     * 设备——路由回落本地后采集自动恢复。会话期间音频本就该走 WiFi 的 AirPlay，
     * 任何蓝牙音频接收端都是抢占方。
     */
    @SuppressLint("MissingPermission")
    private fun maybeReclaimAudioRoute() {
        // 杂项设置开关（默认开）：不想被自动断开耳机的用户可关
        if (appContext?.let { DisplayPrefs.audioRouteReclaim(it) } != true) return
        if (MediaSessionsState.nowPlaying?.playing != true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - audioRouteReclaimLastAtMs < 30_000L) return
        if (audioRouteReclaimAttempts >= 5) return
        audioRouteReclaimLastAtMs = now
        audioRouteReclaimAttempts++
        Bg.run("audio-route-reclaim") {
            val ctx = appContext ?: return@run
            val devices = BtProfiles.connectedAudioDevices(ctx)
            if (devices.isEmpty()) {
                appendLog("AUDIO: 采集全零且媒体在播，但无蓝牙音频设备在线——非路由抢占（查捕获权限/媒体音量）")
                return@run
            }
            val names =
                devices.joinToString("、") {
                    runCatching { it.name }.getOrNull() ?: "?"
                }
            appendLog("AUDIO: ⚠ 音频路由被蓝牙设备抢占（$names）——断开以恢复车机侧声音")
            devices.forEach { device -> BtProfiles.disconnect(ctx, device) }
            appendLog("AUDIO: 蓝牙音频设备已断开——路由回落本地，采集自动恢复")
        }
    }

    /**
     * 无线移交拆 BT（WiFi 隧道 iAP2 会话建成后、RECORD 之前触发）：真 iPhone 的 BT 在车机 SYN 与首帧视频之前就已落下
     * （sysdiagnose 2026-10-16：50.015 拆 BT → 50.023 车 SYN → 50.098 首帧）。
     * 我们此前 BT 全程保持——车机 HMI 卡在「半移交」态：会话健康但永不切屏、连接
     * 入口置灰直到重启车机。仅控制通道虚拟链形态做（TCP 隧道形态的 DiPlay 见
     * RFCOMM 断开即拆整轮无线栈，2026-10-16 实测，不可拆）。
     * 服务端 accept 一并停掉：iPhone 拆 BT 后 90s+ 车机无 RFCOMM 回连，但停监听是
     * 防御性正确（防车机回连挂上第二条链）；onEnded/下一轮 startConnection 会重启。
     */

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：SETUP type=100 协商 → 播放捕获 → ChaCha 封包推流。
     *
     * 依赖 MediaProjection（AudioPlaybackCapture 的授权来源），且需用户在「启动连接」
     * 弹窗中选择「开启声音」——音视频同时推流显著增加负载（实测会卡顿/断连）。
     *
     * @return 音频通道是否真正启动（调用方据此决定是否补发 RECORD 激活新加入的流）
     */
    fun startAudioChannel(
        context: Context,
        client: AirPlayLink,
        paired: Boolean,
        host: String?,
        preNegotiated: Pair<AudioProfile, AirPlayLink.AudioStreamInfo>? = null,
    ): Boolean {
        if (!audioEnabledForSession) {
            appendLog("AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频")
            return false
        }
        if (!paired) {
            // 明文会话（et=NONE）没有 DataStream 输出密钥域（fw:
            // createCryptors 属性缺失域 @0x2276c5fd0）：推出去的 RTP 车机
            // 无法解密。宁可不推，也不要制造「有包无声」的假象。
            appendLog("AUDIO: 明文会话（未配对）——跳过音频通道")
            return false
        }
        if (host == null) {
            appendLog("AUDIO: 无接收端可用地址，跳过音频通道")
            return false
        }
        return try {
            // 音频封装参数已定案（见 AudioTuning），不再提供切换
            val tuning = AudioTuning.RAW_SPF
            // 候选序 = Rust D7 选择（R3 起 AudioProfileMemory 记忆退役——
            // 候选位图即车机自报声明，按序重试已覆盖「某档不被接受」场景）
            // SetupPlan 已预协商：不得再发一次 SETUP；旧候选循环已删除。
            val profile = preNegotiated?.first
            val audio = preNegotiated?.second
            if (profile == null || audio == null) {
                appendLog("AUDIO: 无预协商音频流——本次仅推视频")
                return false
            }
            // 首选 REMOTE_SUBMIX 采集（shell UserService，免投屏授权链）：submix 输入
            // 激活时系统把媒体策略整体路由走——手机本地自然静音，**音量条不动**；
            // 不可用（服务未绑/ROM 拒绝）回退投影播放捕获（旧行为：需 MediaProjection
            // + 会话中静音手机媒体音量）
            val submixService = CarInputClient.service
            val submixFd =
                submixService?.let { service ->
                    runCatching { service.startSubmixAudio(audio.sampleRate) }
                        .onFailure { appendLog("AUDIO: REMOTE_SUBMIX 启动异常：${it.message}") }
                        .getOrNull()
                }
            if (submixFd != null) {
                appendLog("AUDIO: 采集走 REMOTE_SUBMIX（免投屏链路，手机音量条不动）")
            }
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    ntpEpochAnchorSecs = client.ntpEpochAnchorSecs,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                    codec = profile.codec,
                    streamType = profile.streamType,
                    adtsFrames = tuning.adts,
                    // 同步包目标端口：车机在 SETUP 应答里下发。缺了它就没有 RTP↔NTP 锚点，
                    // 接收端只能「按到达时刻猜」，严格的实现干脆不排播（2026-10-20 实测）。
                    controlPort = audio.controlPort,
                    // 车机相位锚（fw: feedback 200 体相位读回 → SetZeroTimeStamp
                    // @0x22775b73c = 打包器时基源——RTP ts 落车机相位帧）
                    phaseTsProvider = { client.audioPhaseTs(audio.sampleRate) },
                )
            audioSender = sender
            // 6011 监听探针（2026-11-12 真车诊断：controlPort 语义 = 手机 RTCP
            // 收包口——绑上等车机发，替代旧「发同步包到 car:6011」死方向）
            if (audio.controlPort > 0) {
                client.startControlPortProbe(audio.controlPort)
                // 音量域（fw: 取初始音量→RECORD→set-volume 回写激活；
                // 0.1.10 probeAndFixVolume 同源——车机 AirPlay 混音器条目
                // 静音位 ≠ 用户手调的总音量）
                client.fetchVolume()
            }
            // 音频路由抢占自愈（2026-10-26 荣耀实测：耳机 X7e 抢占 A2DP 后，
            // 采集全零、推给车机的是静音流——用户视角「车机没声音了」）
            sender.onRouteHijackSuspect = { maybeReclaimAudioRoute() }
            // submix 模式收口：释放 UserService 侧 AudioRecord（幂等；即使本回调
            // 丢失，泵线程检测到读端关闭也会自愈）
            sender.onStopped = { runCatching { CarInputClient.service?.stopSubmixAudio() } }
            val started =
                if (submixFd != null) {
                    sender.start(android.os.ParcelFileDescriptor.AutoCloseInputStream(submixFd))
                } else {
                    val projection = CarPlayService.mediaProjection
                    if (projection == null) {
                        appendLog(
                            "AUDIO: REMOTE_SUBMIX 不可用且缺少投屏权限（播放捕获依赖 MediaProjection）" +
                                "——音频通道未启动。现在点「申请投屏权限」也可即时补启（无需重连）；" +
                                "注意「断开连接」会停服务并连带失效投屏授权",
                        )
                        false
                    } else {
                        appendLog("AUDIO: REMOTE_SUBMIX 不可用——回退投影播放捕获（会话中静音手机媒体音量）")
                        sender.start(projection)
                    }
                }
            if (!started) {
                audioSender = null
                // 未成功进入采集循环也显式收口（stop 已触发 onStopped，此处幂等兜底）
                runCatching { submixService?.stopSubmixAudio() }
                false
            } else {
                true
            }
        } catch (error: Exception) {
            appendLog("AUDIO: 通道启动异常 - ${error.message}")
            audioSender = null
            runCatching { CarInputClient.service?.stopSubmixAudio() }
            false
        }
    }

    /**
     * 配对循环的端点刷新器（非阻塞）：只读浏览器缓存的 `_airplay._tcp`，
     * 有则并入 IP/端口/公钥等字段。返回 null = 无新信息（配对循环继续用当前端点）。
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] 配对期间 mDNS 刷新端点 → " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /**
     * 车机 ctrl 邀请到达（CarPlayCtrlAdvertiser.onCarInvite 转交，peerIp =
     * 邀请源 IP）——真车形态的 AirPlay 启动触发器：MMI 6318 实车不发
     * 0x4301（2026-11-05 实车日志定谳），邀请源 IP 即车机热点网段地址。
     * 合成端点走 0x4301 同一交接点（onCarPlayEndpoint：冗余端点闸/
     * sessionStarting 互斥/凭据合并/绑网直连全在里面复用）；RTSP 端口
     * 7000 = 固件 AirPlaySender 常量（0x4301 实测同值）。热点凭据用
     * 0x5703 缓存（真车时序：5703 先于邀请到达）。
     */
    private fun onCarInvite(peerIp: String) {
        if (videoSender?.isRunning == true) {
            appendLog(">>> [车机 ctrl 邀请] 推流中——忽略（同 0x4301 冗余端点口径）")
            return
        }
        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = lastCarWifiSsid ?: "",
                passphrase = lastWifiPsk ?: "",
                channel = 0,
                securityType = 0,
                airPlayPort = 7000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf(peerIp),
            )
        onCarPlayEndpoint(endpoint, "车机 ctrl 邀请")
    }

    /** BT 会话交接点（0x4301 → AirPlay 连接）：绑 WiFi → 配对/SETUP → 推流。
     *  R1 定形（2026-11-03）：端点即 Rust EV_ENDPOINT 透传（port/IP/wifi 域
     *  一体，fw/endpoint.rs `_iap2_CarPlayStartSession` @0x10015633c 消化），
     *  直连不预解析——地址族选择委托系统评估器（B11 定谳），mDNS 缺口由
     *  runFlow 连接循环的 endpointRefresher 补齐，决策机退避吸收「窗口未开」。 */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        val context = appContext ?: return
        // 冗余端点防护：接收端控制器（DiPlay 实测）在我们配对推流后才完成既有的
        // RFCOMM 重连并重发 0x4301。此时重启配对会 retireSession 掉健康会话
        // （编码器 stop 竞态 = 「连上后自动断开」循环的根因）。推流中的端点一律忽略；
        // 真断连时推流自行终止（isRunning=false），下一个端点自然接续重连。
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 会话推流中——忽略冗余端点（接收端周期重连的重复 0x4301）")
            return
        }
        if (!sessionStarting.compareAndSet(false, true)) {
            appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
            return
        }
        // 新轮次起步清取消标志：上一轮可能因「BT 链路断开」置位退出（见
        // onSenderLinkClosed）——能走到这里说明 BT 组件仍在运行（用户取消路径会
        // 停组件、被上方守卫拦截），该标志属于已退场的旧轮次，不清会误伤本轮。
        connectCancelled = false
        // 端点 wifi 域空 = 车机 0x4301 未带组1 wifi 参数（固件只保证组1 存在
        // IP 时才可连）。已绑定车机网络则沿用绑定网直连；否则本轮放行不了
        // （没有热点可连）——记日志释放互斥，等 BT 重连/重发的 0x4301。
        var effective = endpoint
        if (effective.ssid.isEmpty()) {
            val bound = boundCarSsid
            if (bound != null && wifiConnector?.currentNetwork != null) {
                appendLog(">>> [$source] 端点无 wifi 域——沿用已绑定车机网络 $bound 直连")
                effective = effective.copy(ssid = bound)
            } else {
                appendLog(">>> [$source] 端点无 wifi 域且未绑定车机网络——无法加入热点，本轮放弃（等 0x5703/重发 0x4301）")
                phaseHint = "车机端点缺少热点信息（0x4301 组1），等待重发…"
                sessionStarting.set(false)
                return
            }
        }
        appendLog(
            ">>> [$source] 拿到接收端热点：SSID=${effective.ssid}；" +
                "密码=${if (effective.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        phaseHint = "蓝牙已连上车机，解析热点地址…"
        ssidSink?.invoke(effective.ssid)
        // 凭据缓存对两条路径都要写：0x5703 走 onWifiCredentials，0x4301 只到这里。
        // 漏写会让「车机热点 → 去连接」手动兜底误报「尚未拿到热点密码（需 0x5703）」
        // ——2026-10-22 奥迪日志里就是这么把排查方向带偏的。
        if (effective.ssid.isNotBlank()) {
            lastCarWifiSsid = effective.ssid
            if (effective.passphrase.isNotEmpty()) lastWifiPsk = effective.passphrase
        }
        // 诊断（老车机排查必看）：地址是 IPv4 还是只有 IPv6、TXT features 是否声明配对能力。
        // 奥迪 4xxx 两种「连不上」都源于这里——旧版日志里完全没有这两项。
        val addresses = effective.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        val pairingFlag = AirPlayFeatures.supportsPairing(effective.advertisedFeatures)
        appendLog(
            ">>> [$source] 端点诊断：地址=${addresses.ifEmpty { listOf("(无)") }}" +
                "（${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "无地址"
                } else {
                    "仅 IPv6"
                }}）" +
                " features=" + (effective.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知") +
                " HomeKit配对=" +
                when (pairingFlag) {
                    true -> "声明支持"
                    false -> "未声明（按 pre-HomeKit 明文会话处理）"
                    null -> "未知（按需配对）"
                },
        )
        // 先绑网络再连接（mDNS 组播跟随 bindProcessToNetwork 的网络，注册/发现
        // 必须在绑定后）。R1 定形：绑定完成即直连——原「等 ctrl 邀请 + 前置
        // mDNS 解析 + 晚到端点合并」三段编排退役：车机 AirPlay 窗口未开时的
        // Connection refused 由决策机（fw::airplay::flow）退避重试吸收，
        // mDNS 地址缺口由 runFlow 连接循环的 endpointRefresher 补齐。
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${effective.ssid}"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${effective.ssid}\" 后断开重连")
                sessionStarting.set(false)
                return@proceed
            }
            // 网络绑定完成：ctrl 广播重新注册（落到车机所在网络），同时开始后台 mDNS 发现
            // （发现结果由配对循环的 refresher/retryTick 消费）。
            ctrlAdvertiser?.ensureForNetwork(effective.ssid, force = true, network = wifiConnector?.currentNetwork)
            endpointBrowser?.resetCache()
            endpointBrowser?.start()
            Bg.run("airplay-pairing") {
                try {
                    if (connectCancelled) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }

                    // 后台重加入 in-flight 闸（审查 P1-15：join 窗 5s+8s+9s
                    // 不得在 retryTick 内同步跑；防 10s 重试节拍叠加并发 join）
                    val rejoinInFlight =
                        java.util.concurrent.atomic
                            .AtomicBoolean(false)

                    fun newClient() =
                        AirPlayLink(
                            effective,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                            retryTick = { attempt ->
                                // 网络失效检测（2026-11-04 ENONET 案）：重试回路里
                                // 绑定网络若已死（系统切回家庭 WiFi/热点被回收），
                                // 后续重试注定全败——检测到即重走加入流程，下一轮
                                // 重试用新网络
                                val connectorNow = wifiConnector
                                // 当前 SSID 校验（2026-11-12 手动断开→重连偶现）：
                                // 网络对象 capabilities 可能还在（skip 闸被陈旧
                                // 记录骗过）但手机默认网已回家庭——shell 查实际
                                // SSID 不符即触发重加入（用户建议）。
                                val ssidMismatch =
                                    connectorNow != null &&
                                        boundCarSsid != null &&
                                        shellWifiStatus().second.let { it != null && it != effective.ssid }
                                if (connectorNow != null &&
                                    boundCarSsid != null &&
                                    (!connectorNow.isBoundNetworkAlive() || ssidMismatch)
                                ) {
                                    appendLog(
                                        "WIFI: 检测到网络失效/不符（绑定网络存活=" +
                                            (connectorNow?.isBoundNetworkAlive() ?: false) +
                                            " 当前 SSID=" + (shellWifiStatus().second ?: "null") +
                                            " 目标=" + effective.ssid + "）——重试回路内重新加入",
                                    )
                                    boundCarSsid = null
                                    // 审查 P1-15：join 窗（5s+8s+9s）不得在 retryTick
                                    // 内同步跑——阻塞 flow 决策机最长 ~22s（违反
                                    // 「retryTick 必须非阻塞」契约）。改后台执行 +
                                    // in-flight 闸（本 tick 立即返回，下一 tick 见
                                    // in-flight 跳过——防 10s 重试节拍叠加并发 join）
                                    if (rejoinInFlight.compareAndSet(false, true)) {
                                        wifiRejoinExecutor.execute {
                                            try {
                                                joinCarWifiSpecifier(
                                                    Iap2SenderSession.WifiConfiguration(
                                                        effective.ssid,
                                                        effective.passphrase,
                                                        effective.channel,
                                                        effective.securityType,
                                                    ),
                                                )
                                            } finally {
                                                rejoinInFlight.set(false)
                                            }
                                        }
                                    }
                                }
                                // 配对重试期间车机仍可能没探测到 ctrl（Bonjour 漏掉首次公告）：
                                // 周期性强制重播一次，并把 mDNS 现状写进日志——否则排查时
                                // 只看得到「端口未开」的重试刷屏，无法判断是车机没邀请还是
                                // 地址不对（2026-10-19 奥迪 0.1.7 日志就缺这两条信息）。
                                ctrlAdvertiser?.ensureForNetwork(
                                    effective.ssid,
                                    force = true,
                                    network = wifiConnector?.currentNetwork,
                                )
                                val invites = ctrlAdvertiser?.inviteCount ?: 0
                                val found = endpointBrowser?.awaitEndpoint(0)
                                appendLog(
                                    "AIRPLAY: 重试 #$attempt —— 已重新公告 ctrl；" +
                                        "车机邀请=$invites 次，mDNS _airplay._tcp=" +
                                        (found?.let { "${it.host?.hostAddress}:${it.port}" } ?: "未发现"),
                                )
                            },
                        ).also { created ->
                            // 连接失败回执：socket 失败 = "网络不对"的直接证据
                            // （热点无上行被系统秒级切回家庭 WiFi 时，网络对象
                            // 存活类校验查不出，只有 connect 失败查得出）。收到
                            // 网络类失败 → 清记录重走加入（≥15s 节流，防止与
                            // tcp-retry 循环互相踩）。
                            var lastRejoinAt = 0L
                            created.onConnectFailed = { failures ->
                                val networkish =
                                    failures.any {
                                        it.contains("ENONET") ||
                                            it.contains("ENETUNREACH") ||
                                            it.contains("EHOSTUNREACH") ||
                                            it.contains("ETIMEDOUT") ||
                                            it.contains("after ")
                                    }
                                val now = android.os.SystemClock.uptimeMillis()
                                if (networkish && now - lastRejoinAt >= 15_000L) {
                                    lastRejoinAt = now
                                    appendLog("WIFI: 连接失败=网络不对（系统可能已切回其它 WiFi）——清记录重新加入热点")
                                    boundCarSsid = null
                                    // 同走线程池 + CAS 闸（审查 P1-15 后续）：失败
                                    // 收尾径也不同步跑 join 窗，与 retryTick 提交点
                                    // 共池串行、互不叠加
                                    if (rejoinInFlight.compareAndSet(false, true)) {
                                        wifiRejoinExecutor.execute {
                                            try {
                                                joinCarWifiSpecifier(
                                                    Iap2SenderSession.WifiConfiguration(
                                                        effective.ssid,
                                                        effective.passphrase,
                                                        effective.channel,
                                                        effective.securityType,
                                                    ),
                                                )
                                            } finally {
                                                rejoinInFlight.set(false)
                                            }
                                        }
                                    }
                                }
                            }
                            // 连续 403 钩（审查家庭网段 403 偶现改进）：
                            // DiPlay 双网时 invite 给家庭地址 → pair-verify M1
                            // 即拒 403 循环——同网络类失败径（清记录+池重加入），
                            // 省掉 30s 失败循环（阈值 3 在 AirPlayLink 侧）。
                            // 每条 403 信号（审查二轮：重连场景 403 走配对泵，
                            // 10 轮 retryTick 节拍太慢）——立即查当前 SSID，
                            // 不符即重加入；与阈值钩/池闸共用一条重加入路。
                            created.onForbiddenSignal = { message ->
                                if (message.contains("403") && boundCarSsid != null) {
                                    val current = shellWifiStatus().second
                                    if (current != null && current != effective.ssid) {
                                        appendLog(
                                            "WIFI: 403 且当前网络不符（$current ≠ ${effective.ssid}）" +
                                                "——清记录重新加入热点",
                                        )
                                        boundCarSsid = null
                                        if (rejoinInFlight.compareAndSet(false, true)) {
                                            wifiRejoinExecutor.execute {
                                                try {
                                                    joinCarWifiSpecifier(
                                                        Iap2SenderSession.WifiConfiguration(
                                                            effective.ssid,
                                                            effective.passphrase,
                                                            effective.channel,
                                                            effective.securityType,
                                                        ),
                                                    )
                                                } finally {
                                                    rejoinInFlight.set(false)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            created.onForbiddenStreak = {
                                appendLog("WIFI: 连续 403=疑似网络不对（双网直连）——清记录重新加入热点")
                                boundCarSsid = null
                                if (rejoinInFlight.compareAndSet(false, true)) {
                                    wifiRejoinExecutor.execute {
                                        try {
                                            joinCarWifiSpecifier(
                                                Iap2SenderSession.WifiConfiguration(
                                                    effective.ssid,
                                                    effective.passphrase,
                                                    effective.channel,
                                                    effective.securityType,
                                                ),
                                            )
                                        } finally {
                                            rejoinInFlight.set(false)
                                        }
                                    }
                                }
                            }
                            // 车机媒体键（iAP2 0x6802 通路）：方向盘/面板的上一曲、下一曲。
                            // 放在这里而不是调用点，保证重连重建 client 时也挂得上。
                            created.onMediaKey = { keyCode ->
                                dispatchMediaKey(keyCode)
                                appendLog("EVENT: 车机媒体键（iAP2 0x6802）→ keyCode=$keyCode 已注入")
                            }
                            // 旋钮/四向拨杆（iAP2 0x6802 通路）：注入虚拟屏的 DPAD
                            created.onNavKey = { keyCode -> injectNavigationKey(keyCode) }
                            // modesChanged 触发 = 固件忠实形态（D9 定谳 2026-11-04：固件
                            // modes 域=事件驱动单发，Stark 控制器状态变更驱动；无重申循环
                            // ——iPhone→DiPlay sysdiagnose 实录：连接后 ~70s 零 modes 帧
                            // 而画面照切，xcertplay 切屏由流驱动、忽略 modesChanged）。
                            // ① 起流 = CarPlay 接入的模式状态变更（Stark 等价事件）
                            created.onStreamingStarted = {
                                Bg.run("modes-assert") {
                                    if (!created.cancelled && created.assertModes()) {
                                        appendLog("AIRPLAY: 起流——modesChanged 宣告完成（固件事件驱动）")
                                    }
                                }
                            }
                            // ② 车机归还资源（UNBORROW/UNTAKE）= 模式状态变更事件，即时补发
                            created.onModesRestored = {
                                Bg.run("modes-restored") {
                                    if (!created.cancelled && created.assertModes()) {
                                        appendLog("AIRPLAY: 资源归还——modesChanged 补发完成（固件事件驱动）")
                                    }
                                }
                            }
                        }
                    // 「强制应用横屏」覆盖写入在配对起步就入队：`am compat enable` 会立即
                    // 杀名单内应用，必须赶在 attach 拉起任何应用之前完成。配对阶段有几十秒
                    // 空窗，写入在此期间自然排空，attach 只需 drain（~0ms）——原先在 attach
                    // 里同步跑（1.6s，串行时 3.2s）会侵占「RECORD→屏幕口 keepAlive」的
                    // ~10s 拆链预算（keepAlive 帧自 attach 返回后才起发，2026-10-23 定谳）。
                    // 强制横屏覆盖为**按需写入**（AppOrientationCompat.applyAll 内跳过
                    // 系统侧已生效的应用，不杀运行中进程）——保留重连轮次上名单应用
                    // 已带覆盖运行，本批全部跳过、零杀进程
                    orientationRestorePending.set(true)
                    appContext?.let { ctx -> AppOrientationCompat.applyAllAsync(ctx, ::appendLog) }
                    // 保留态回连：上一轮意外断开的屏还在——retireSession 跳过销毁，
                    // attach 复用分支会把新编码 surface 接回旧屏（应用状态连续）
                    retireSession(keepDisplays = displayRetentionActive.get())
                    // 会话静默降噪（2026-10-10）：从交接点起暂停其它接收端的出站连接探测
                    // （典型：连着 DiPlay 测试台时不再噪音式探测 Audi）。会话结束/断开时解除。
                    BtSessionGate.sessionActive = true
                    // 连接编排 = Rust fw::airplay::flow 决策机（固件骨架
                    // ensureConnectedInternal @0x22775f8a0 + Stage1StartTransaction
                    // @0x2276ce288）。配对路径/明文降级/加密升级/403 退避/放弃
                    // 全部决策在 Rust，本处只泵（连接窗口 120s——车机 AirPlay
                    // 窗口「用户点车机图标/收到 ctrl 邀请才开」的等待上界）。
                    phaseHint = "AirPlay 连接中…"
                    val client = newClient()
                    currentClient = client
                    // pre-HomeKit 老车机（CarPlay 210.81 时代，奥迪 4xxx 固件）在
                    // `_airplay._tcp` 里**不声明** HK_PAIRING_AND_ENCRYPT——决策机
                    // 据此跳过注定失败的 pair-setup（兼容层初值；判错由明文 SETUP
                    // 被拒后的加密升级路径兜底，决策在 flow.rs）。
                    val noPairingHint =
                        AirPlayFeatures.supportsPairing(client.currentEndpoint.advertisedFeatures) == false
                    val setup = client.runFlow(CONNECTION_WINDOW_MS, noPairingHint)
                    if (connectCancelled) {
                        retireSession()
                        appendLog("AIRPLAY: 用户已取消——中止连接")
                        sessionStarting.set(false)
                        return@run
                    }
                    val pairedKeys = client.lastKeys
                    if (setup != null) {
                        phaseHint = "建立 CarPlay 会话…"
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ 配对完成（flow 决策机）writeKey=" +
                                    pairedKeys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                        } else {
                            appendLog("AIRPLAY: 明文会话建立（et=NONE + AES-CTR 屏幕流）")
                        }
                        // 已配对 → Events 双钥；sessionKey 仅作「已配对」闸标记
                        // （A3 起流钥本体随 Rust streamSetup 应答交付，宿主不持钥）
                        val sessionKey = pairedKeys?.sharedSecret
                        val eventsWrite = pairedKeys?.eventsWriteKey
                        val eventsRead = pairedKeys?.eventsReadKey
                        // 记录会话凭据：投影授权晚于连接时用于动态补启音频。
                        // 用 client.currentEndpoint（而非本轮透传端点）：配对期间
                        // mDNS 刷新过端点时，真正连上的是 client 里的地址（2026-10-18：
                        // 0x4301 的定长 IP 字段带 NUL，mDNS 给的是干净地址，取旧值
                        // 会让视频端口/keepAlive 用脏地址 → 一律解析失败）。
                        currentSharedSecret = sessionKey
                        currentAudioHost =
                            client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                        client.connectEventChannel(eventsWrite, eventsRead, setup) { command ->
                            when (command["type"]) {
                                "hidSendReport" -> {
                                    val uuid = command["uuid"]?.toString()
                                    val report = command["hidReport"] as? ByteArray ?: return@connectEventChannel
                                    // A6 下沉：判定树全在 Rust（fw::airplay::hid——媒体键 →
                                    // 导航 → Consumer 释放 → 触摸，**以设备描述符为准**，
                                    // 不按 uuid/车型写死；fw: APHIDClientHandleHIDReport
                                    // @0x2276b7e4c 报告格式由 hidDescriptor 定义）。
                                    // Kotlin 只剩动作面：媒体键派发/DPAD 注入/触点注入。
                                    val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                    val dispatch = NativeBridge.nativeAirplayHidDispatch(descriptor, report)
                                    if (dispatch != null && HidNavigation.rawLogEnabled) {
                                        appendLog(
                                            "HID-RAW: uuid=$uuid bytes=" +
                                                report.joinToString(" ") { "%02X".format(it) } +
                                                " → $dispatch",
                                        )
                                    }
                                    if (dispatch == null) return@connectEventChannel
                                    val obj = org.json.JSONObject(dispatch)
                                    when (obj.getString("kind")) {
                                        "nav" -> {
                                            val mask = obj.getInt("mask")
                                            val delta =
                                                if (obj.has("delta") && !obj.isNull("delta")) obj.getInt("delta") else null
                                            val hat = if (!obj.isNull("hat")) obj.getInt("hat") else null
                                            val navKeys = HidNavigation.handleRaw(uuid.orEmpty(), mask, delta, hat)
                                            if (navKeys.isNotEmpty()) {
                                                navKeys.forEach(::injectNavigationKey)
                                                appendLog(
                                                    "EVENT: 旋钮/导航键 " +
                                                        navKeys.joinToString("+") { HidNavigation.nameOf(it) } +
                                                        "（uuid=$uuid）",
                                                )
                                            }
                                        }

                                        "media" -> {
                                            val usages = obj.getJSONArray("usages")
                                            for (i in 0 until usages.length()) {
                                                val usage = usages.getInt(i)
                                                val keyCode = ConsumerKeys.keyCodeFor(usage)
                                                if (keyCode != 0) {
                                                    dispatchMediaKey(keyCode)
                                                    appendLog(
                                                        "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(usage)}" +
                                                            "（uuid=$uuid usage=0x%02x → keyCode=$keyCode）".format(usage),
                                                    )
                                                } else {
                                                    appendLog(
                                                        "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(usage)}" +
                                                            "（uuid=$uuid usage=0x%02x）无对应 Android 键，忽略".format(usage),
                                                    )
                                                }
                                            }
                                        }

                                        else -> {
                                            val contacts = obj.getJSONArray("contacts")
                                            if (TouchRouter.fullLogEnabled) {
                                                // 完整触摸日志（车机设置→杂项，即开即生效）：逐条坐标
                                                if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                    appendLog(
                                                        "EVENT: 触摸 " +
                                                            (0 until contacts.length())
                                                                .joinToString { i ->
                                                                    val c = contacts.getJSONObject(i)
                                                                    "#${c.getInt("slot")}(${c.getInt("x")},${c.getInt("y")})" +
                                                                        if (c.getBoolean("down")) "↓" else "↑"
                                                                },
                                                    )
                                                }
                                                touchLogCount++
                                            } else {
                                                // 默认：5s 汇总计数（无坐标），连续触摸不刷屏
                                                touchSummaryPending++
                                                val now = android.os.SystemClock.uptimeMillis()
                                                if (touchSummaryAt == 0L) touchSummaryAt = now
                                                if (now - touchSummaryAt >= 5000L) {
                                                    appendLog(
                                                        "EVENT: 触摸 ×$touchSummaryPending（近5s 汇总；" +
                                                            "完整坐标日志可在 车机设置→杂项 开启）",
                                                    )
                                                    touchSummaryPending = 0
                                                    touchSummaryAt = now
                                                }
                                            }
                                            for (i in 0 until contacts.length()) {
                                                val c = contacts.getJSONObject(i)
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    Contact(
                                                        slot = c.getInt("slot"),
                                                        xPixels = c.getInt("x"),
                                                        yPixels = c.getInt("y"),
                                                        down = c.getBoolean("down"),
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }

                                "iAPSendMessage" -> {
                                    // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                                    // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                                    val params = command["params"] as? Map<*, *>
                                    val data = params?.get("data") as? ByteArray
                                    if (data == null) {
                                        appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                                    } else {
                                        iapEventMessageCount++
                                        client.onIapEventBytes(data)
                                    }
                                }

                                "forceKeyFrame" -> {
                                    // 车机解不出画面时会要 I 帧。必须立即响应：
                                    // 拖到下一个周期 I 帧（1s）车机可能一直卡在等帧状态。
                                    val forced = videoSender?.requestKeyFrame() == true
                                    appendLog(
                                        "EVENT: 车机请求关键帧（forceKeyFrame）→ " +
                                            if (forced) "已强制编码 I 帧" else "编码器未就绪，等周期 I 帧",
                                    )
                                }

                                "changeModes" -> {
                                    // 车机请求资源仲裁：状态更新与带 status+params 的 200 应答
                                    // 已在 AirPlayLink 事件循环完成（空 200 曾让奥迪黑屏拆链）；
                                    // 归还类事件（UNBORROW/UNTAKE）的补发由 onModesRestored 驱动。
                                }

                                else ->
                                    appendLog(
                                        // 未识别的命令必须带参数：车机的私有命令（如 requestUI）
                                        // 到底要什么，全在 params 里——只打 type 等于没打。
                                        "EVENT: 命令 type=${command["type"]} " +
                                            command.entries
                                                .filter { it.key?.toString() != "type" }
                                                .joinToString(" ") { (key, value) ->
                                                    "$key=" +
                                                        when (value) {
                                                            is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                                            else -> value.toString().take(240)
                                                        }
                                                }.take(600),
                                    )
                            }
                        }
                        // 资源声明（must）：真车对未声明资源的屏幕流 SETUP 直接 400
                        client.assertModes()
                        // HID 输入模式武装：/info hidDevices（奥迪字符串 "0"/"1"，触摸="1"）。
                        // display features=0x08（高保真触摸）已声明，缺这步车机不发 hidSendReport。
                        setup.hidDeviceUuids.forEach { uuid ->
                            client.sendHidSetInputMode(uuid, mode = 0)
                        }
                        // 接收端能力声明（诊断）：iAPChannel=1 表示车机请求 type-130
                        // 隧道形态——该形态已随 MIB3 隧道退役，触摸统一走事件通道
                        // iAP 虚链路（nativeSessionFeed 薄层）。
                        appendLog(
                            "TUNNEL: 接收端 enabledFeatures=${setup.enabledFeatures}",
                        )
                        val audioProfile = if (audioEnabledForSession) client.audioCandidates().firstOrNull() else null
                        // 本端音频 RTCP 控制口（fw: SETUP caller dict controlPort——
                        // 接收端 → RTCPPortRemote，0xD6 重传请求/CPROBE 发往此口；
                        // 2026-11-13 深挖二轮出土）。SETUP 前 bind + 接收线程。
                        val audioControlPort = client.ensureAudioControlSocket()
                        if (audioControlPort > 0) {
                            client.onAudioControlPacket = { n, bytes, from ->
                                appendLog("AUDIO-CTRL: 车机→控制口 #$n ${bytes}B from $from")
                            }
                        }
                        // 真车序（2026-11-08 固件直切 + iPhone transcript）：隧道
                        // 130 → 屏 110 → 音频；DiPlay/xcertplay 接收端支持 130。
                        // 车机拒隧道（200 无 dataPort）时事件通道 fallback 自动保持。
                        val planJson =
                            client.endpointPlanJsonFromInfo(withTunnel = true, controlPort = audioControlPort)
                                ?: client.setupPlanJson(setup.displayUuid, audioProfile)
                        val plan = client.runFirmwareSetupPlan(planJson)
                        val screen = plan?.streams?.get(110)?.let { client.screenStreamInfoFromEvent(it) }
                        var preAudio: Pair<AudioProfile, AirPlayLink.AudioStreamInfo>? = null
                        if (audioProfile != null) {
                            val audioEv = plan?.streams?.get(audioProfile.streamType.toInt())
                            val audioInfo = audioEv?.let { client.audioStreamInfoFromEvent(it, audioProfile) }
                            if (audioInfo != null) preAudio = audioProfile to audioInfo
                        }
                        if (screen != null) {
                            // 地址取 client 真正连通的那个（配对期间可能被 mDNS 刷新过）：
                            // 视频数据端口与 keepAlive 都按它建 socket；IPv6 端点也算
                            // 可用地址（EndpointHosts 统一排序：IPv4 → 全局 IPv6 → 链路本地 IPv6）。
                            val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            // 心跳双保险（缺失任一都会被车机 ~10s 看门狗拆会话，2026-10-11 实测）：
                            //   keepAlive UDP ping（车机 SETUP 返回的 keepAlivePort，10s 周期）
                            //   + RTSP POST /feedback（1s 周期，catplay FEEDBACK_INTERVAL）
                            if (host != null && setup.keepAlivePort != null) {
                                client.startKeepAlive(host, setup.keepAlivePort!!)
                            }
                            client.startFeedbackLoop()
                            // 关键时序（2026-10-17 奥迪定谳）：屏幕 SETUP 后必须尽快连上
                            // dataPort——车机有 ~2s 看门狗。音频 SETUP 实测要 ~2.1s，
                            // 若排在前面，端口连接迟到即触发车机 reset 控制通道
                            // （日志特征：`音频 SETUP 往返 2091ms` 与 `EVENT 读取结束
                            // - Connection reset` 同一毫秒）。故先建 VideoSender 并
                            // openDataPort()，再协商音频，最后 RECORD + 启动编码器。
                            var pendingVideoW = 0
                            var pendingVideoH = 0
                            var pendingVideoDensity = 0
                            if (host != null) {
                                // 「显示」偏好：流画布/虚拟屏按车机分辨率百分比生成。
                                // 只在会话建立时生效——编码器与虚拟屏尺寸此时定型；
                                // 密度同步缩放，逻辑布局（dp）与 100% 完全一致。
                                val scalePct =
                                    appContext
                                        ?.let {
                                            DisplayPrefs
                                                .scalePercent(it)
                                        } ?: 100
                                val scale = scalePct / 100f
                                // 缩放后向下对齐偶数（H.264 要求）：虚拟屏/合成器/编码器
                                // 三方必须同一尺寸，故在源头对齐（VideoSender 内另有兜底）
                                val streamW =
                                    ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                val streamH =
                                    ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                val fps =
                                    appContext
                                        ?.let {
                                            DisplayPrefs
                                                .fps(it)
                                        } ?: 30
                                activeScalePercent = scalePct
                                activeTargetFps = fps
                                // 车机触摸在通告视图坐标系（如 1160×540），流已按比例
                                // 缩放（如 1450×674）——注入前需映射，否则点击漂移
                                TouchRouter
                                    .setCoordinateScale(
                                        setup.displayWidth,
                                        setup.displayHeight,
                                        streamW,
                                        streamH,
                                    )
                                appendLog(
                                    "DISPLAY: 流 ${setup.displayWidth}x${setup.displayHeight} → " +
                                        "${streamW}x$streamH（$scalePct%）@${fps}fps",
                                )
                                pendingVideoW = streamW
                                pendingVideoH = streamH
                                // dock 屏密度随画布短边等比（设计基准 1160×540@420dpi，
                                // 见 design/launcher-proto.html：dock 48dp=126px）：高分辨率
                                // 车机（测试台 2320×1080=2×）下 dock 文字/图标与奥迪同比例
                                // 放大——此前固定 420dpi，dock 相对画布减半显小（2026-10-17）。
                                // 上限 4×（4K 级画布）。
                                pendingVideoDensity =
                                    (420f * minOf(streamW, streamH) / 540f)
                                        .toInt()
                                        .coerceIn(120, 1680)
                                videoSender =
                                    VideoSender(
                                        host = host,
                                        dataPort = screen.dataPort,
                                        sessionHandle = client.sessionPumpHandle,
                                        ntpEpochAnchorSecs = client.ntpEpochAnchorSecs,
                                        width = streamW,
                                        height = streamH,
                                        targetFps = fps,
                                        log = { line -> appendLog(line) },
                                        keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                        onEnded = {
                                            // 会话真实结束：解除静默降噪（客户端恢复探测/重连）
                                            appendLog(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
                                            BtSessionGate.sessionActive = false
                                            // 车机掉线类结束（无用户断开操作）：暗幕/常亮必须一起收掉。
                                            // 这条路径不经过 retireSession（VirtualLauncher 仍挂着），
                                            // 漏掉它就会出现「会话早就断了、45s 后手机屏突然变黑」
                                            // （2026-10-19 用户实测）。
                                            ScreenDimmer.setSessionActive(false)
                                            // 车机侧断开：人在车外，让手机直接休眠（面板保持黑），
                                            // 而不是先点亮再等 60s 超时
                                            restorePhoneScreen(sleepIfCarSide = true)
                                            // 保留态判定（2026-11-10）：屏在 + 偏好开 → 虚拟屏不动，
                                            // 等自动重连后 attach 复用（屏上应用/导航状态连续）。
                                            // 用户主动终态（手动断开/取消）先消费抑制旗标：
                                            // stop 的拆卸也走本回调，误进保留态会把标记/计时器
                                            // 泄漏到下一轮（2026-11-12 日志定谳，见
                                            // suppressRetentionOnce 注释）。
                                            val userTornDown = suppressRetentionOnce.compareAndSet(true, false)
                                            val keepDisplays = !userTornDown && enterDisplayRetention()
                                            if (keepDisplays) {
                                                // 保留分支补做 retireSession 才会做的两件收口
                                                // （复用路径回连后 retireSession(keep=true) 不碰屏）：
                                                // ① 停音频采集——submix 挂着 = 手机媒体持续静音；
                                                // ② 关控制通道——keepAlive/事件 socket 已死，挂着无意义。
                                                audioSender?.stop()
                                                audioSender = null
                                                currentClient?.cancelled = true
                                                currentClient?.close()
                                                currentClient = null
                                                appendLog("SESSION: 保留分支收口音频/控制通道（虚拟屏不动）")
                                                // ⚠ 跳过横屏复原 resetAllAsync：am compat disable 会
                                                // 杀名单内应用——保留的意义就是恢复应用状态，杀了等于
                                                // 白留（下轮 applyAll 与旧覆盖幂等，无需先复原）。
                                            } else if (orientationRestorePending.compareAndSet(true, false)) {
                                                appContext?.let { ctx ->
                                                    AppOrientationCompat.resetAllAsync(ctx, ::appendLog)
                                                }
                                            }
                                            // shell 兜底存的系统网络也要在这里还原：车机拆链是
                                            // 最常见的离场方式（熄火），用户未必点「断开」——只挂
                                            // disconnect 一端必遗留（2026-10-23 审阅定谳）。下一轮
                                            // 若走深度兜底会重新登记，语义自洽。
                                            cleanupShellFallbackNetwork()
                                        },
                                    )
                                // 先连端口（车机看门狗），编码器等 RECORD 后再启动
                                videoSender?.openDataPort()
                            }
                            // 先 RECORD 激活会话并起视频，再协商音频（2026-10-17 奥迪定谳）：
                            // 车机在「屏幕流 SETUP 之后」有约 2s 看门狗，等不到 RECORD/数据流即
                            // 拆掉整轮会话。音频 SETUP 实测本身要 ~2.05-2.16s（车机侧音频通路
                            // 初始化），排在 RECORD 之前必然超时——日志里 reset 与音频 SETUP
                            // 返回同一毫秒、且 5 次实测全部复现。故顺序改为：
                            //   屏幕 SETUP → 连数据端口 → RECORD → 起编码器（出帧）→ 音频 SETUP
                            // 音频 SETUP 失败只丢声音，视频会话保持。
                            if (host != null) {
                                val videoW = pendingVideoW
                                val videoH = pendingVideoH
                                val videoDensity = pendingVideoDensity
                                videoSender?.start { surface ->
                                    // 唯一模式：隐形屏（双屏启动器：dock + 应用屏）
                                    val source =
                                        HiddenDisplaySource(
                                            context.applicationContext,
                                            log = { line -> appendLog(line) },
                                        )
                                    currentHiddenSource = source
                                    val ok =
                                        source.attach(
                                            surface,
                                            videoW,
                                            videoH,
                                            videoDensity,
                                        )
                                    appendLog(
                                        "MODE: 隐形屏 ${if (ok) "已启动（高德车机版优先）display=${source.displayId}" else "启动失败（Shizuku 就绪？）"}",
                                    )
                                    // 暗幕/常亮只在真正开始投屏后生效（见 ScreenDimmer.sessionActive）：
                                    // 应用启动时只挂触摸探针，避免「会话早断了、手机过一会儿还是变暗」。
                                    if (ok) {
                                        // 保留屏复用完成（或全新建立）——退出保留态（清计时器）
                                        exitDisplayRetention()
                                        ScreenDimmer.setSessionActive(true)
                                        // 视频起流（虚拟屏已就绪）——实验模式下重申的触发源
                                        client.onStreamingStarted?.invoke()
                                        // 虚拟屏已建好 → 按「连接时真息屏」开关决定是否硬件断电
                                        if (DisplayPrefs.screenOffOnConnect(context)) {
                                            enterPhoneScreenOff()
                                        } else {
                                            appendLog("SCREEN: 「连接时真息屏」已关闭——本次仅用暗幕")
                                        }
                                    }
                                }
                            }
                            // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                            // 会话已激活（RECORD 完成）后再协商；失败不影响视频主链路。
                            val audioStarted =
                                if (audioProfile != null && preAudio == null) {
                                    appendLog("AUDIO: SetupPlan 音频流未成功（缺 streamKey/端口）——不再二次发 SETUP")
                                    false
                                } else {
                                    startAudioChannel(context, client, sessionKey != null, host, preAudio)
                                }
                            if (audioStarted && preAudio == null) {
                                // 流在 RECORD 之后加入：AirPlay 语义要求再发一次 RECORD
                                // 把新流纳入已激活的会话。缺这一步时车机对 SETUP 回 200
                                // 并给 dataPort，但不会真正播放（2026-10-17 奥迪实测：
                                // 音频包持续发送、采集内容非零，车机无声）。
                                appendLog("AUDIO: 音频流已加入——补发 RECORD 激活（流后加入会话）")
                                client.record()
                            }
                        }
                    } else {
                        // 会话级 SETUP 被拒（典型：接收端按设备过滤回 403——
                        // flow 已按决策机在窗口内重试，走到这里是结构失败）。
                        // 主动关闭控制通道：否则接收端要等约 45s 视频超时才
                        // 拆 BT 链路，用户只看到「连上之后自动断开」。
                        phaseHint = "接收端拒绝了会话建立（SETUP 未通过）"
                        appendLog("AIRPLAY: 会话建立失败——主动关闭控制通道，本轮结束")
                        uiHintSink?.invoke("接收端拒绝了会话建立（SETUP）——请查看日志中的 deviceID 提示")
                        runCatching { client.close() }
                    }
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    appendLog("AIRPLAY: 配对线程异常 - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    sessionStarting.set(false)
                    // 配对未成功建立推流（fail/异常/start 失败提前退出）时解除静默降噪，
                    // 否则 BT-C 会永久停在门闸外、连真车路径失效。
                    // 推流活跃时保持关闭，由 VideoSender.onEnded（推流终止）负责解除。
                    if (videoSender?.isRunning != true) {
                        BtSessionGate.sessionActive = false
                    }
                }
            }
        }
        // 已绑定过网络（autoJoinCarWifi 刚绑好并重注册过）就不要再走一遍加入流程：
        // WifiCarPlayConnector 内部先 release() → bindProcessToNetwork(null)，
        // 会把刚注册好的 ctrl mDNS 广播打断，再等系统重建绑定。
        val wifiConfig =
            Iap2SenderSession.WifiConfiguration(
                effective.ssid,
                effective.passphrase,
                effective.channel,
                effective.securityType,
            )
        // 跳过闸必须过「当前实际连接的 SSID」校验（2026-11-12 手动断开→
        // 重连偶现：ROM 在会话结束后回收应用专属网络，手机回家庭 WiFi，
        // 但 boundCarSsid 记录还在 → 旧判「已绑定跳过」= 不触发加入，
        // 靠 4 轮 ECONNREFUSED 才自愈——用户建议拿当前网络校验）。
        val currentSsidAtInvite = shellWifiStatus().second
        if (boundCarSsid == effective.ssid &&
            wifiConnector?.currentNetwork != null &&
            currentSsidAtInvite == effective.ssid
        ) {
            appendLog(">>> [$source] 已绑定车机热点 ${effective.ssid}（当前 SSID 校验一致），跳过重复绑定")
            continueWithNetwork(true)
        } else {
            if (boundCarSsid == effective.ssid && currentSsidAtInvite != effective.ssid) {
                appendLog(
                    ">>> [$source] 绑定记录与当前网络不符（记录=${effective.ssid} 当前=" +
                        "$currentSsidAtInvite）——不走跳过，直接加入",
                )
            }
            // 端点先于 0x5703 到达（或上一轮换网失败）：同样的入口——静默路径优先，
            // 失败再退回应用专属网络。不要按 TRANSPORT_WIFI 盲绑当前任意 WiFi：
            // 切网途中会绑到旧网络，AirPlay 必然连不上。
            Bg.run("wifi-join-on-endpoint") {
                while (!connectCancelled) {
                    val shellReady =
                        appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
                    when (val result = joinCarWifi(wifiConfig, shellReady)) {
                        is WifiCarPlayConnector.JoinResult.Joined -> {
                            continueWithNetwork(true)
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unsupported -> {
                            // WEP 等无法用 specifier 表达：等用户在系统 WLAN 里连上后绑定
                            waitForManualWifiThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            promptSpecifierFailed(wifiConfig, shellReady)
                            // promptSpecifierFailed 内部可能已重试/转手动；两者都会
                            // 通过 joinCarWifiSpecifier → continueWithNetwork 续上流程，
                            // 这里只在彻底失败时结束本轮
                            if (boundCarSsid == wifiConfig.ssid && wifiConnector?.currentNetwork != null) {
                                continueWithNetwork(true)
                            } else {
                                sessionStarting.set(false)
                            }
                            return@run
                        }
                    }
                }
                sessionStarting.set(false)
            }
        }
    }

    /** specifier 不可用时的手动兜底：轮询系统 WLAN 是否连上目标热点，而后绑定。 */
    private fun waitForManualWifiThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            val current = shellWifiStatus().second
            if (current == wifi.ssid) {
                onBound(bindCarWifiAndRegister(wifi.ssid))
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = android.os.SystemClock.uptimeMillis()
                phaseHint = "等待手机加入车机热点 ${wifi.ssid}…"
                appendLog("WIFI: 等待用户在系统 WLAN 中加入车机热点（当前 ${current ?: "未连接"}）…")
                openWlanSettings()
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(">>> 等待车机热点超时——请到 设置>WLAN 加入 \"${wifi.ssid}\" 后重新连接")
            uiHintSink?.invoke("未连上车机热点 \"${wifi.ssid}\"——请到 WLAN 设置中主动连接")
        }
        onBound(false)
    }

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0

    /** 默认模式的触摸 5s 汇总计数器（窗口沿事件推进，无独立定时器）。 */
    @Volatile
    private var touchSummaryPending = 0

    @Volatile
    private var touchSummaryAt = 0L

    @Volatile
    private var iapEventMessageCount = 0
}
