package io.github.shaonialife.cardeck.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Method

/**
 * Shizuku UserService 实现：运行在常驻 shell 进程（uid 2000），
 * 通过反射 InputManager.injectInputEvent 直接注入触摸事件到指定 display。
 *
 * injectInputEvent 的位置随版本变化：
 * - Android 13 及以下：InputManager 实例方法；
 * - Android 14+：迁移到 InputManagerGlobal.getInstance() 单例。
 * 部分版本签名带第三参 targetDisplayId，按形参类型自适应；
 * 老签名（两参）依赖 InputEvent.setDisplayId 指定目标屏。
 *
 * 反射失败（ROM hidden API 差异）由 ping()/diag() 暴露，调用方回退 `input -d`。
 * （移植自参考项目 android-auto 的 CarInputServiceImpl）
 */
class CarInputServiceImpl(
    private val context: Context,
) : ICarInput.Stub() {
    /**
     * 物理屏是否被我们置为 OFF。服务自尽（APK 升级导致的过期自清）前必须恢复，
     * 否则手机会停在一块「逻辑醒着、物理黑着」的屏上（按电源键两次可恢复）。
     */
    @Volatile
    private var physicalScreenOff = false

    /**
     * 僵尸自清（治本，2026-10-16）：Shizuku 按「组件+进程名+版本(APK lastUpdateTime)」复用
     * 常驻 UserService 进程。重装 APK 后旧版本进程既不受 force-stop 影响、Shizuku 也不回收；
     * 而它创建的虚拟屏**只有它自己能释放**（releaseDisplay 只查本进程的 map）——表现为
     * 「重启后检测到残留屏，点清理却释放 0/N」。本进程周期性比对「启动时读到的
     * lastUpdateTime」与当前安装值：不一致即自身 dex 已过期，自尽交系统回收进程，
     * 它持有的全部虚拟屏随之释放。
     */
    private val startupLastUpdateTime: Long =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(-1L)

    init {
        fileLog("service start: pkg=${context.packageName} lastUpdate=$startupLastUpdateTime pid=${android.os.Process.myPid()}")
        Thread({
            while (true) {
                try {
                    Thread.sleep(STALE_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now =
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                    }.getOrDefault(-1L)
                if (startupLastUpdateTime > 0 && now > 0 && now != startupLastUpdateTime) {
                    // 物理屏若被我们置为 OFF，自尽前必须恢复：否则新版本进程还没起来，
                    // 手机就停在一块「逻辑醒着、物理黑着」的屏上（按电源键两次才恢复）。
                    if (physicalScreenOff) {
                        fileLog("stale self-reap: 恢复物理屏点亮（避免升级后停在黑屏）")
                        runCatching { setPhysicalScreenPower(true) }
                    }
                    fileLog(
                        "stale self-reap: lastUpdateTime $startupLastUpdateTime -> $now，" +
                            "exiting pid=${android.os.Process.myPid()}（其持有的虚拟屏随进程回收）",
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Thread
                }
            }
        }, "carinput-stale-check").apply {
            isDaemon = true
            start()
        }
    }

    // ---- per-display 电源保活（2026-10-26，对照参考实现补齐）----
    // 「按电源键真休眠后虚拟屏窗口表面被剥离、唤醒后重挂不稳定」的对策：给每块
    // 虚拟屏持一把 **per-display 亮屏锁**（PowerManager 三参 newWakeLock，Android
    // 15/16+ hidden API，反射调用——只有 shell uid 拿得到 DEVICE_POWER 语义），
    // 并在非交互时对该 display 单独 wakeUp。表面剥离发生在 display 进 DOZE/OFF，
    // 把虚拟 display 钉在交互态即可阻断。旧系统无该 API 时整体降级（维持现状）。
    private val displayWakeLocks = HashMap<Int, android.os.PowerManager.WakeLock>()

    /** 反射缓存：newWakeLock(int,String,int) / isInteractive(int) / wakeUp(long,int,String,int)。 */
    private object DisplayPowerApi {
        val newWakeLock3: Method? =
            runCatching {
                android.os.PowerManager::class.java.getMethod(
                    "newWakeLock",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
        val isInteractiveInt: Method? =
            runCatching {
                android.os.PowerManager::class.java.getMethod("isInteractive", Int::class.javaPrimitiveType)
            }.getOrNull()
        val wakeUp4: Method? =
            runCatching {
                android.os.PowerManager::class.java.getMethod(
                    "wakeUp",
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
        val available: Boolean get() = newWakeLock3 != null
    }

    /** SCREEN_BRIGHT_WAKE_LOCK（deprecated 常量值 10，per-display 语义下是唯一可用档）。 */
    private val screenBrightLevel = 10

    private val powerManager: android.os.PowerManager? by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
    }

    init {
        if (DisplayPowerApi.available) {
            runCatching {
                val receiver =
                    object : android.content.BroadcastReceiver() {
                        override fun onReceive(
                            receiverContext: Context?,
                            intent: android.content.Intent?,
                        ) {
                            kickAllDisplaysAwake("SCREEN_OFF")
                            // Display-off token 在面板超时（实测锁屏后 ~6s）才落下，即时 kick
                            // 发早了会被后续 token「追平」——延迟补踢把 token 掀掉（2026-11-06）
                            scheduleDelayedKicks()
                        }
                    }
                try {
                    val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
                    context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
                } catch (_: Throwable) {
                    val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
                    context.registerReceiver(receiver, filter)
                }
                fileLog("per-display 保活就绪：SCREEN_OFF 补唤醒已注册")
            }.onFailure { fileLog("SCREEN_OFF 注册失败（锁仍生效）: $it") }
            // 看门狗与广播注册解耦：广播在本 ROM 收不到（实测只有注册行没有触发行），
            // 看门狗才是主力——注册失败不能连带跳过它。
            runCatching { startDisplayStateWatchdog() }
                .onFailure { fileLog("看门狗启动失败（仅缺补踢，锁仍生效）: $it") }
        } else {
            fileLog("per-display 保活不可用：无三参 newWakeLock（Android 15/16 前），维持现状")
        }
    }

    /**
     * 显示电源看门狗：持锁期间每 3s 检查各 display 的电源态，非 ON 立即补踢。
     * 不依赖 SCREEN_OFF 广播（部分 ROM 不送达 shell 进程的运行时接收器，2026-11-06
     * 实测「只有注册行、没有触发行」）；Display-off token 在面板超时后才落下，轮询
     * 天然覆盖该时序。
     *
     * 生命周期：本进程常驻（与虚拟屏/亮屏锁同寿），线程 daemon 随进程消亡——
     * 不是泄漏。[watchdogStarted] 做**进程级单飞**：Shizuku 若在同一进程重建服务
     * 实例，init 重跑也不会起第二个看门狗（双份补踢 + 日志重复）。
     */
    private fun startDisplayStateWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) {
            fileLog("看门狗已在本进程运行——跳过重复启动")
            return
        }
        Thread({
            val dm =
                runCatching {
                    shellContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                }.getOrNull()
            if (dm == null) {
                // 早退必须归还单飞标记：否则本进程内永远无法再启动看门狗
                watchdogStarted.set(false)
                fileLog("看门狗未启动：DisplayManager 不可用")
                return@Thread
            }
            fileLog("display 电源看门狗已启动（3s 轮询）")
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val ids = synchronized(displayWakeLocks) { displayWakeLocks.keys.toList() }
                if (ids.isEmpty()) continue
                for (id in ids) {
                    val state =
                        runCatching { dm.getDisplay(id)?.state }.getOrNull()
                    // UNKNOWN 是创建/切换瞬态，不踢；OFF/DOZE 必踢
                    if (state == android.view.Display.STATE_OFF || state == android.view.Display.STATE_DOZE) {
                        fileLog("看门狗 #$id state=$state——补踢")
                        runCatching { kickDisplayAwake(id, "watchdog state=$state") }
                    }
                }
            }
        }, "carinput-display-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /** 给虚拟 display 持锁并按需唤醒；幂等。失败返回 false（调用方仅记日志）。 */
    private fun keepDisplayAwake(
        displayId: Int,
        reason: String,
    ): Boolean {
        if (displayId < 0) return false
        val pm = powerManager ?: return false
        val newWakeLock = DisplayPowerApi.newWakeLock3 ?: return false
        synchronized(displayWakeLocks) {
            if (displayWakeLocks.containsKey(displayId)) return true
            val lock =
                try {
                    newWakeLock.invoke(pm, screenBrightLevel, "CarDeck:vd", displayId) as? android.os.PowerManager.WakeLock
                } catch (t: Throwable) {
                    fileLog("vd #$displayId per-display wakeLock 获取失败（$reason）: $t")
                    null
                } ?: return false
            lock.setReferenceCounted(false)
            try {
                lock.acquire()
            } catch (t: Throwable) {
                fileLog("vd #$displayId per-display wakeLock acquire 失败（$reason）: $t")
                return false
            }
            displayWakeLocks[displayId] = lock
        }
        fileLog("vd #$displayId per-display 亮屏锁已持有（$reason）")
        wakeDisplayIfNotInteractive(displayId, reason)
        return true
    }

    /** display 非交互时对其单独 wakeUp（三/四参 hidden API；缺 API 则只持锁不唤醒）。 */
    private fun wakeDisplayIfNotInteractive(
        displayId: Int,
        reason: String,
    ) {
        val pm = powerManager ?: return
        val wakeUp = DisplayPowerApi.wakeUp4 ?: return
        val interactive =
            DisplayPowerApi.isInteractiveInt?.let { probe ->
                runCatching { probe.invoke(pm, displayId) as? Boolean }.getOrNull()
            }
        if (interactive == true) return
        wakeDisplay(displayId, reason, interactive)
    }

    /** 无条件 wakeUp（SCREEN_OFF 补唤醒用——交互态在 Doze 下不可信）。 */
    private fun wakeDisplayUnconditionally(
        displayId: Int,
        reason: String,
    ) = wakeDisplay(displayId, reason, null)

    private fun wakeDisplay(
        displayId: Int,
        reason: String,
        interactive: Boolean?,
    ) {
        val pm = powerManager ?: return
        val wakeUp =
            DisplayPowerApi.wakeUp4 ?: run {
                fileLog("vd #$displayId 无法 wakeUp：无四参 wakeUp API")
                return
            }
        runCatching {
            wakeUp.invoke(pm, SystemClock.uptimeMillis(), 2, "CarDeck:vd", displayId)
        }.onSuccess {
            fileLog("vd #$displayId wakeUp 已发（$reason，interactive=$interactive）")
        }.onFailure {
            fileLog("vd #$displayId wakeUp 失败（$reason）: $it")
        }
    }

    private fun kickAllDisplaysAwake(reason: String) {
        val ids = synchronized(displayWakeLocks) { displayWakeLocks.keys.toList() }
        if (ids.isEmpty()) return
        ids.forEach { id -> kickDisplayAwake(id, reason) }
    }

    /**
     * 单屏补唤醒：优先 `IDisplayManager.requestDisplayPower(displayId, ON)`（经
     * DisplayPowerController 状态机，能驱动 WMS 释放 Display-off sleep token——
     * 2026-11-06 定谳：仅靠 wakeUp 只把 display 电源拉回 state=ON，token 仍挂着、
     * mSleeping=true、活动暂停、触摸无效），无该 API 再退 wakeUp。
     */
    private fun kickDisplayAwake(
        displayId: Int,
        reason: String,
    ) {
        val pm = powerManager
        val interactive =
            DisplayPowerApi.isInteractiveInt?.let { probe ->
                runCatching { probe.invoke(pm, displayId) }.getOrNull()
            }
        val viaDisplayManager =
            requestDisplayPower(displayId, REQUEST_POWER_STATE_ON)?.also {
                fileLog("补唤醒 #$displayId requestDisplayPower(ON) ok（$reason，interactive=$interactive）")
            }
        if (viaDisplayManager != true) {
            wakeDisplayUnconditionally(displayId, reason)
            fileLog("SCREEN_OFF 补唤醒 #$displayId（wakeUp 路径，$reason，interactive=$interactive）")
        }
    }

    /** 锁屏后延迟补踢：Display-off token 在面板超时（~6s）才落下，即时 kick 发早了。 */
    private fun scheduleDelayedKicks() {
        listOf(1_000L, 3_000L, 8_000L).forEach { delayMs ->
            android.os
                .Handler(android.os.Looper.getMainLooper())
                .postDelayed({
                    val ids = synchronized(displayWakeLocks) { displayWakeLocks.keys.toList() }
                    if (ids.isNotEmpty()) {
                        ids.forEach { id ->
                            runCatching { kickDisplayAwake(id, "SCREEN_OFF+$delayMs") }
                                .onFailure { fileLog("补唤醒 #$id 失败：${it.message}") }
                        }
                    }
                }, delayMs)
        }
    }

    /** IDisplayManager 代理（requestDisplayPower 反射探测；ROM 无此 API 时整体回退 wakeUp）。 */
    private val displayManagerProxy: Any? by lazy {
        runCatching {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "display") as IBinder
            Class
                .forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.onFailure { fileLog("IDisplayManager 代理创建失败：${it.message}") }.getOrNull()
    }

    private val requestDisplayPowerMethod: Method? by lazy {
        displayManagerProxy?.let { proxy ->
            proxy.javaClass.methods
                .firstOrNull { it.name == "requestDisplayPower" }
                ?.also { fileLog("requestDisplayPower 可用：${it.parameterTypes.joinToString()}") }
        }
    }

    /** requestDisplayPower(displayId, state)；ROM 无该 API 返回 null（调用方走 wakeUp 兜底）。 */
    private fun requestDisplayPower(
        displayId: Int,
        state: Int,
    ): Boolean? {
        val proxy = displayManagerProxy ?: return null
        val method = requestDisplayPowerMethod ?: return null
        return try {
            method.invoke(proxy, displayId, state)
            true
        } catch (t: Throwable) {
            fileLog("requestDisplayPower($displayId,$state) 失败：${t.message}")
            false
        }
    }

    private fun releaseDisplayKeepAwake(displayId: Int) {
        synchronized(displayWakeLocks) {
            displayWakeLocks.remove(displayId)?.let { lock ->
                runCatching { if (lock.isHeld) lock.release() }
                fileLog("vd #$displayId per-display 亮屏锁已释放")
            }
        }
    }

    // ---- VirtualDeviceManager 路线（锁屏推流的官方解，2026-11 落地）----
    //
    // legacy flags（TRUSTED/ALWAYS_UNLOCKED/OWN_DISPLAY_GROUP + per-display 亮屏锁）只挡住了
    // keyguard token 与 display-off token 的一部分；按电源键真休眠时窗口表面仍会被系统剥离
    // （AGENTS.md 2026-10-23）。参考实现走 VirtualDeviceManager：以 shell 包名与车机 BT MAC 建
    // COMPANION_DEVICE_APP_STREAMING 伴侣关联（`cmd companiondevice associate`，shell 权限免弹窗）
    // → 创建 LOCK_STATE_UNLOCKED 虚拟设备 → 建在其上的屏整台无视主机锁屏/休眠。
    // 任何一步失败逐屏回退 legacy DisplayManager 路径（下行代码一行不改）。

    @Volatile
    private var companionMac: String? = null

    /** 看门狗进程级单飞标记（防服务实例重建时起第二个轮询线程）。 */
    private val watchdogStarted =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 本会话 VDM 已失败（关联/设备/建屏任一环节）——余屏直接走 legacy，下轮 setCompanionDevice 重置重试。 */
    @Volatile
    private var vdmFailedThisSession = false

    private val vdmLock = Any()

    private var vdmSession: VdmSession? = null

    /** shell 包 Context（系统按 uid 2000 校验 packageName；VDM 与 legacy 建屏共用）。 */
    private val shellContext: Context by lazy {
        try {
            context.createPackageContext("com.android.shell", 0)
        } catch (t: Throwable) {
            fileLog("createPackageContext(com.android.shell) 失败：$t，回退本 app context")
            context
        }
    }

    override fun setCompanionDevice(btMac: String?) {
        val mac = btMac?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(vdmLock) {
            if (!mac.equals(companionMac, ignoreCase = true)) {
                // 目标车机变了：收掉旧会话（含 disassociate 本次创建的关联），下次建屏重建
                vdmSession?.let { old ->
                    runCatching { old.close() }
                        .onFailure { fileLog("VDM: 旧会话收口失败：${it.message}") }
                }
                vdmSession = null
            }
            companionMac = mac
            // 每次 attach 前都会重设 → 会话级重试（进程级禁用会掩盖偶发故障）
            vdmFailedThisSession = false
        }
        fileLog("setCompanionDevice(${mac ?: "<空——VDM 关闭，走 legacy>"})")
    }

    override fun closeVirtualDevice(): Boolean {
        synchronized(vdmLock) {
            val session = vdmSession ?: return true // 幂等：无会话即成功
            vdmSession = null
            vdmFailedThisSession = true // 会话已收口，本轮不再重建（下轮 attach 重置）
            return try {
                session.close()
                true
            } catch (t: Throwable) {
                fileLog("closeVirtualDevice 失败：${t.message}")
                false
            }
        }
    }

    /** gate 通过时取（或建）VDM 会话；失败置会话级禁用并返回 null（调用方落 legacy）。 */
    private fun obtainVdmSession(): VdmSession? {
        if (!VirtualDeviceRoute.shouldAttempt(Build.VERSION.SDK_INT, companionMac, vdmFailedThisSession)) {
            return null
        }
        synchronized(vdmLock) {
            if (vdmFailedThisSession) return null
            val mac = companionMac ?: return null
            vdmSession?.let { existing ->
                if (existing.mac.equals(mac, ignoreCase = true)) return existing
                runCatching { existing.close() }
                vdmSession = null
            }
            return try {
                VdmSession(mac).also { session ->
                    session.ensure()
                    vdmSession = session
                    fileLog("VDM: 会话就绪 ${session.describe()}")
                }
            } catch (t: Throwable) {
                vdmFailedThisSession = true
                fileLog("VDM: 会话建立失败——本会话走 legacy（${describeCause(t)}）")
                null
            }
        }
    }

    /** 反射失败的根因解包（InvocationTargetException.message 恒 null，直接打会吞根因）。 */
    private fun describeCause(t: Throwable): String {
        var cause = t
        var depth = 0
        while (cause.cause != null && cause.cause !== cause && depth < 5) {
            cause = cause.cause!!
            depth++
        }
        return "${cause.javaClass.name}: ${cause.message ?: "(无消息)"}"
    }

    /**
     * VDM 专用 Context 包装：
     * `VirtualDeviceManager` 构造/建设备时会取 `context.getAttributionSource()`，而
     * createPackageContext 得到的 shell 包 Context 在本 ROM 上该值为 null
     * （实测 NullPointerException）——这里显式给出 shell uid + shell 包名的
     * AttributionSource（真机实证必需）。
     */
    private class AttributionContext(
        base: Context,
    ) : android.content.ContextWrapper(base) {
        private val attributionSource: android.content.AttributionSource =
            android.content.AttributionSource
                .Builder(android.os.Process.myUid())
                .setPackageName(CDM_PACKAGE)
                .build()

        override fun getAttributionSource(): android.content.AttributionSource = attributionSource

        override fun getOpPackageName(): String = CDM_PACKAGE

        override fun getPackageName(): String = CDM_PACKAGE

        override fun getApplicationContext(): Context = this
    }

    /**
     * VDM 会话：关联（复用/创建）→ 虚拟设备（LOCK_STATE_UNLOCKED）→ 建屏。
     * 生命周期 = 一次 CarPlay 会话：VirtualLauncher.detach 末尾 closeVirtualDevice 收口
     * （参考实现 destroy() 同款）；进程死亡时系统侧虚拟设备与屏随进程回收，关联
     * （system 持久化）留待下次 ensure 复用。
     */
    private inner class VdmSession(
        val mac: String,
    ) {
        var associationId = -1
        var associationCreatedByUs = false
        var device: Any? = null
        private val displays = LinkedHashMap<Int, android.hardware.display.VirtualDisplay>()

        private val userId: Int = android.os.Process.myUid() / 100000

        /** ICompanionDeviceManager 代理（服务名/Stub 全反射，取不到时关联路径整体失败→legacy）。 */
        private val cdm: Any? by lazy {
            runCatching {
                val binder =
                    Class
                        .forName("android.os.ServiceManager")
                        .getMethod("getService", String::class.java)
                        .invoke(null, "companiondevice") as IBinder
                Class
                    .forName("android.companion.ICompanionDeviceManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            }.onFailure { fileLog("VDM: ICompanionDeviceManager 反射失败：${it.message}") }.getOrNull()
        }

        fun describe(): String =
            "mac=$mac assocId=$associationId createdByUs=$associationCreatedByUs device=${device != null} displays=${displays.size}"

        /** 关联（复用既有或 shell 命令创建）→ role 兜底 → 虚拟设备。任一步失败抛出。 */
        @Synchronized
        fun ensure() {
            janitor()
            val existing = findExistingAssociation()
            if (existing >= 0) {
                associationId = existing
                fileLog("VDM: 复用既有关联 id=$existing mac=$mac")
            } else {
                // 位置形命令（参考实现同款，荣耀 Magic7 Pro SDK 37 实测可用）：
                // associate USER_ID PACKAGE MAC PROFILE SELF_MANAGED
                // ⚠ 本 ROM 的**旗标形**（--mac-address/--profile）解析有 bug：抛
                // 「not a valid MAC address」却仍用**本机 MAC** 创建垃圾关联——旗标形
                // 只作位置形彻底无效时的兜底，且事后清道（janitor 同款规则）。
                runCatching {
                    runCmd(
                        "companiondevice",
                        "associate",
                        userId.toString(),
                        CDM_PACKAGE,
                        mac,
                        ROLE_APP_STREAMING,
                        "false",
                    )
                    fileLog("VDM: associate(位置形) 已执行")
                }.onFailure { fileLog("VDM: 位置形失败：${it.message}") }
                if (findExistingAssociation() < 0) {
                    runCatching {
                        runCmd(
                            "companiondevice",
                            "associate",
                            userId.toString(),
                            CDM_PACKAGE,
                            "--mac-address",
                            mac,
                            "--profile",
                            ROLE_APP_STREAMING,
                            "--self-managed",
                            "false",
                        )
                        fileLog("VDM: associate(旗标形兜底) 已执行")
                    }.onFailure { fileLog("VDM: 旗标形也失败：${it.message}") }
                }
                associationCreatedByUs = true
                // 命令成功 ≠ 关联立即可见（参考实现轮询 20×100ms 确认）
                for (i in 0 until 20) {
                    val id = findExistingAssociation()
                    if (id >= 0) {
                        associationId = id
                        break
                    }
                    Thread.sleep(100)
                }
                if (associationId < 0) {
                    throw java.io.IOException("associate 已执行但关联未生效（2s 内不可见）")
                }
                fileLog("VDM: 关联已创建 id=$associationId")
                janitor() // 兜底命令若产出垃圾关联（MAC 不符），立即清掉
            }
            ensureRoleHolder()
            ensureDevice()
        }

        /**
         * 清道夫：`com.android.shell` 名下 profile 匹配但 MAC 不是当前目标的关联只可能是
         * 本应用早期残留（换车遗留 / ROM 命令面 bug 产物）——该包名的关联只能由 shell uid
         * 创建（= 我们），按 id 清除是安全的。其它 profile 的条目只记日志不动。
         * 本 ROM 的 `cmd companiondevice disassociate` 命令面解析全废（实测任何格式都报
         * 「not a valid MAC address」），清理必须走 binder 反射（[disassociate]）。
         */
        private fun janitor() {
            val manager = cdm ?: return
            val associations =
                runCatching {
                    Class
                        .forName("android.companion.ICompanionDeviceManager")
                        .getMethod("getAssociations", String::class.java, Int::class.javaPrimitiveType)
                        .invoke(manager, CDM_PACKAGE, userId) as List<*>
                }.getOrNull() ?: return
            for (association in associations) {
                val assoc = association ?: continue
                val id =
                    runCatching { assoc.javaClass.getMethod("getId").invoke(assoc) as Int }.getOrNull()
                        ?: continue
                val assocMac =
                    runCatching {
                        assoc.javaClass
                            .getMethod("getDeviceMacAddress")
                            .invoke(assoc)
                            ?.toString()
                    }.getOrNull() ?: continue
                val profile =
                    runCatching {
                        assoc.javaClass.getMethod("getDeviceProfile").invoke(assoc) as? String
                    }.getOrNull()
                if (profile != ROLE_APP_STREAMING) {
                    fileLog("VDM: 跳过非本 profile 的关联 id=$id mac=$assocMac profile=$profile")
                    continue
                }
                if (assocMac.equals(mac, ignoreCase = true)) continue
                if (disassociate(id)) {
                    fileLog("VDM: 已清理残留关联 id=$id mac=$assocMac（非当前目标 $mac）")
                }
            }
        }

        /** binder 反射 disassociate：新版按 associationId，旧版按 (package, mac) 双参兜底。 */
        private fun disassociate(id: Int): Boolean {
            val manager = cdm ?: return false
            val cls = Class.forName("android.companion.ICompanionDeviceManager")
            return runCatching {
                cls.getMethod("disassociate", Int::class.javaPrimitiveType).invoke(manager, id)
                true
            }.getOrElse {
                runCatching {
                    // 旧签名：disassociate(String packageName, String macAddress)——id 关联
                    // 的 mac 需再查一次，查不到就放弃（残留无害，只是 CDM 列表里多一行）
                    cls.getMethod("disassociate", String::class.java, String::class.java)
                    val macOf =
                        (
                            Class
                                .forName("android.companion.ICompanionDeviceManager")
                                .getMethod("getAssociations", String::class.java, Int::class.javaPrimitiveType)
                                .invoke(manager, CDM_PACKAGE, userId) as List<*>
                        ).firstOrNull { assoc ->
                            assoc != null &&
                                runCatching {
                                    (assoc.javaClass.getMethod("getId").invoke(assoc) as Int) == id
                                }.getOrDefault(false)
                        }?.let { assoc ->
                            runCatching {
                                assoc.javaClass
                                    .getMethod("getDeviceMacAddress")
                                    .invoke(assoc)
                                    ?.toString()
                            }.getOrNull()
                        }
                    if (macOf == null) return false
                    cls
                        .getMethod("disassociate", String::class.java, String::class.java)
                        .invoke(manager, CDM_PACKAGE, macOf)
                    true
                }.getOrDefault(false)
            }
        }

        /** 按 MAC+profile 查既有关联，返回 associationId（无/不匹配 -1；profile 不同抛异常防误伤）。 */
        private fun findExistingAssociation(): Int {
            val manager = cdm ?: throw java.io.IOException("CompanionDeviceManager 不可用")
            val associations =
                Class
                    .forName("android.companion.ICompanionDeviceManager")
                    .getMethod(
                        "getAssociations",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                    ).invoke(manager, CDM_PACKAGE, userId) as List<*>
            for (association in associations) {
                val assoc = association ?: continue
                val assocMac =
                    runCatching {
                        assoc.javaClass
                            .getMethod("getDeviceMacAddress")
                            .invoke(assoc)
                            ?.toString()
                    }.getOrNull() ?: continue
                if (!assocMac.equals(mac, ignoreCase = true)) continue
                val profile =
                    runCatching {
                        assoc.javaClass.getMethod("getDeviceProfile").invoke(assoc) as? String
                    }.getOrNull()
                if (profile == ROLE_APP_STREAMING) {
                    return assoc.javaClass.getMethod("getId").invoke(assoc) as Int
                }
                // 与参考实现一致：profile 不同直接失败，绝不复用/改写用户的非流式关联
                throw java.io.IOException("已存在同 MAC 关联但 profile=$profile（期望 app-streaming）")
            }
            return -1
        }

        /** role 持有者兜底（disassociate 可能连带摘掉 role；参考实现 close 同款回填）。 */
        private fun ensureRoleHolder() {
            runCatching {
                val holders =
                    runCmd("role", "get-role-holders", "--user", userId.toString(), ROLE_APP_STREAMING)
                if (!VirtualDeviceRoute.roleHoldersContain(holders, CDM_PACKAGE)) {
                    runCmd(
                        "role",
                        "add-role-holder",
                        "--user",
                        userId.toString(),
                        ROLE_APP_STREAMING,
                        CDM_PACKAGE,
                    )
                    fileLog("VDM: 已补授 role $ROLE_APP_STREAMING")
                }
            }.onFailure { fileLog("VDM: role 检查/补授失败（继续，关联本身可能足够）：${it.message}") }
        }

        private fun ensureDevice() {
            val paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            // LOCK_STATE_UNLOCKED：AOSP 值 = 1。ROM 字段名可能不同（荣耀 SDK 37 实测
            // 无该名字）——枚举 int 常量按候选名匹配，匹配不到回退 1 并全量留痕供判读。
            val lockState = resolveLockStateUnlocked(paramsClass)
            builderClass.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(builder, lockState)
            builderClass.getMethod("setName", String::class.java).invoke(builder, "CarDeck")
            val params = builderClass.getMethod("build").invoke(builder)

            val vdmClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
            val ivdmClass = Class.forName("android.companion.virtual.IVirtualDeviceManager")
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "virtualdevice") as IBinder
            val proxy =
                Class
                    .forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            val manager =
                vdmClass
                    .getConstructor(ivdmClass, Context::class.java)
                    .newInstance(proxy, AttributionContext(shellContext))
            device =
                vdmClass
                    .getMethod("createVirtualDevice", Int::class.javaPrimitiveType, paramsClass)
                    .invoke(manager, associationId, params)
            fileLog("VDM: 虚拟设备已创建 lockState=$lockState（UNLOCKED=锁屏/休眠不影响其窗口）")
        }

        /** 枚举 VirtualDeviceParams 的 int 常量找 LOCK_STATE_UNLOCKED（找不到回退 AOSP 值 1）。 */
        private fun resolveLockStateUnlocked(paramsClass: Class<*>): Int {
            val fields =
                paramsClass.fields
                    .filter { it.type == Int::class.javaPrimitiveType }
                    .mapNotNull { field ->
                        runCatching { field.name to field.getInt(null) }.getOrNull()
                    }
            fileLog("VDM: VirtualDeviceParams 常量 ${fields.joinToString { "${it.first}=${it.second}" }}")
            val names = listOf("LOCK_STATE_UNLOCKED", "LOCK_STATE_ALWAYS_UNLOCKED", "UNLOCKED")
            for (candidate in names) {
                fields.firstOrNull { it.first == candidate }?.let { return it.second }
            }
            fileLog("VDM: 未找到 UNLOCKED 常量名，回退 AOSP 值 1")
            return 1
        }

        /** 在虚拟设备上建屏；flags 候选由调用方给定（trusted/dock 两套），失败抛给调用方降级。 */
        @Synchronized
        fun createDisplay(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface,
            flags: Int,
        ): android.hardware.display.VirtualDisplay {
            val dev = device ?: throw java.io.IOException("虚拟设备未就绪")
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder =
                builderClass
                    .getConstructor(
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).newInstance(name, width, height, dpi)
            builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
            val config = builderClass.getMethod("build").invoke(builder)
            val vd =
                dev.javaClass
                    .getMethod(
                        "createVirtualDisplay",
                        configClass,
                        java.util.concurrent.Executor::class.java,
                        android.hardware.display.VirtualDisplay.Callback::class.java,
                    ).invoke(dev, config, null, null) as? android.hardware.display.VirtualDisplay
                    ?: throw java.io.IOException("createVirtualDisplay 返回 null")
            val id = vd.display.displayId
            if (id <= 0) {
                runCatching { vd.release() }
                throw java.io.IOException("displayId 无效（$id）")
            }
            displays[id] = vd
            logDisplayGroup(id)
            return vd
        }

        /** 应用侧 releaseDisplay 时同步摘除跟踪（close 兜底释放时不重复 release）。 */
        @Synchronized
        fun discard(displayId: Int) {
            displays.remove(displayId)
        }

        /** 会话收口：余屏兜底释放 → device.close → disassociate（仅本次创建的）→ role 回填。 */
        @Synchronized
        fun close() {
            for ((id, vd) in displays) {
                runCatching { vd.release() }
                fileLog("VDM: 残余屏 #$id 已随会话收口释放")
            }
            displays.clear()
            device?.let { dev ->
                runCatching { dev.javaClass.getMethod("close").invoke(dev) }
                    .onFailure { fileLog("VDM: device.close 失败（屏已释放，影响有限）：${it.message}") }
            }
            device = null
            fileLog("VDM: 会话已收口（屏已释放、device 已关闭；复用关联保留 id=$associationId）")
            if (associationCreatedByUs && associationId >= 0) {
                val removed =
                    runCatching { disassociate(associationId) }.getOrDefault(false)
                fileLog(
                    if (removed) {
                        "VDM: 本次创建的关联已解除 id=$associationId"
                    } else {
                        "VDM: disassociate 失败（关联留存，下次 ensure 复用/清道）：id=$associationId"
                    },
                )
                associationCreatedByUs = false
                associationId = -1
                // 参考实现 close 语义：解除后 role 可能被连带摘掉，校验并回填
                runCatching {
                    val holders = runCmd("role", "get-role-holders", "--user", userId.toString(), ROLE_APP_STREAMING)
                    if (!VirtualDeviceRoute.roleHoldersContain(holders, CDM_PACKAGE)) {
                        runCmd(
                            "role",
                            "add-role-holder",
                            "--user",
                            userId.toString(),
                            ROLE_APP_STREAMING,
                            CDM_PACKAGE,
                        )
                        fileLog("VDM: role 已回填")
                    }
                }.onFailure { fileLog("VDM: role 回填校验失败：${it.message}") }
            }
        }

        /** 读回 displayGroupId 验证独立组（AGENTS.md：shell 写操作必须读回确认）。 */
        private fun logDisplayGroup(displayId: Int) {
            runCatching {
                val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val display = dm.getDisplay(displayId) ?: return@runCatching
                val infoClass = Class.forName("android.view.DisplayInfo")
                val info = infoClass.getConstructor().newInstance()
                val valid =
                    android.view.Display::class.java
                        .getMethod("getDisplayInfo", infoClass)
                        .invoke(display, info) as Boolean
                if (!valid) return@runCatching
                val group = infoClass.getField("displayGroupId").getInt(info)
                // DEFAULT_DISPLAY_GROUP=0（@hide 于本 compileSdk，写 AOSP 常量值）
                val independent = group != DEFAULT_DISPLAY_GROUP
                fileLog(
                    "VDM: #$displayId displayGroupId=$group " +
                        if (independent) "（独立组 ✓）" else "（⚠ 与主屏同组——锁屏可能拖累该屏）",
                )
            }.onFailure { fileLog("VDM: #$displayId displayGroupId 读回失败：${it.message}") }
        }
    }

    /**
     * UserService 内直接跑 /system/bin/cmd（本进程就是 shell uid，无需 Shizuku 转发）。
     * redirectErrorStream 合并 stderr——`cmd` 系参数校验失败只写 stderr，不读就是
     * 静默无效（AGENTS.md 踩坑记录）。
     */
    private fun runCmd(vararg args: String): String {
        val process = ProcessBuilder("/system/bin/cmd", *args).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes().decodeToString().trim() }
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw java.io.IOException("cmd 超时: ${args.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw java.io.IOException(
                "cmd 失败(exit=${process.exitValue()}): ${args.joinToString(" ")} -> ${output.take(200)}",
            )
        }
        return output
    }

    // ---- 车机音频采集（REMOTE_SUBMIX，免投屏链路）----

    /** 单活动采集实例（submix 输入激活期间系统把媒体策略整体路由走）。 */
    @Volatile
    private var submixCapture: SubmixCapture? = null

    private val submixLock = Any()

    override fun startSubmixAudio(sampleRate: Int): ParcelFileDescriptor? =
        try {
            synchronized(submixLock) {
                submixCapture?.close()
                SubmixCapture(sampleRate)
                    .also { capture ->
                        submixCapture = capture
                        fileLog("SUBMIX: 采集就绪 ${capture.describe()}")
                    }.openPipe()
            }
        } catch (t: Throwable) {
            synchronized(submixLock) {
                runCatching { submixCapture?.close() }
                submixCapture = null
            }
            fileLog("SUBMIX: 启动失败（应用侧将回退播放捕获）：${describeCause(t)}")
            null
        }

    override fun stopSubmixAudio() {
        synchronized(submixLock) {
            submixCapture?.close()
            submixCapture = null
        }
    }

    /**
     * REMOTE_SUBMIX 采集（audioSource=8，shell uid 持 CAPTURE_AUDIO_OUTPUT，免
     * MediaProjection/投屏授权链）：submix 输入激活时 AudioPolicy 把媒体策略整体
     * 路由走——手机本地自然静音（**不动音量条**），采样随 SETUP 协商值。
     * PCM16LE 立体声经管道回传应用侧；应用读端断开（断开/崩溃）时泵线程写管道
     * 失败自愈关闭，媒体路由随之恢复，不留孤儿采集。
     *
     * MissingPermission/WrongConstant 豁免：本类只运行在 Shizuku UserService
     * （shell uid 2000，持 CAPTURE_AUDIO_OUTPUT/RECORD_AUDIO 系统授权），不受应用
     * 运行时权限模型约束；REMOTE_SUBMIX=8 是 @hide 常量（公开 IntDef 不含），
     * 与文件内虚拟屏 flags 的 WrongConstant 同例——lint 按应用进程判定，此处豁免。
     */
    @android.annotation.SuppressLint("MissingPermission", "WrongConstant")
    private inner class SubmixCapture(
        sampleRate: Int,
    ) {
        private val record: android.media.AudioRecord
        private var pipeWrite: ParcelFileDescriptor? = null
        private var pump: Thread? = null
        private val closed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        init {
            val format =
                android.media.AudioFormat
                    .Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val minBuffer = android.media.AudioRecord.getMinBufferSize(sampleRate, 12, 2)
            if (minBuffer <= 0) throw java.io.IOException("submix buffer unavailable ($minBuffer)")
            val builder =
                android.media.AudioRecord
                    .Builder()
                    .setAudioSource(8) // MediaRecorder.AudioSource.REMOTE_SUBMIX
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer * 4, 16_384))
            // AudioRecord.Builder.setContext 是 hidden API（部分 ROM 可用）：带 shell
            // AttributionSource 提升初始化成功率；失败无妨（uid 已是 2000）
            runCatching {
                android.media.AudioRecord
                    .Builder::class.java
                    .getMethod("setContext", Context::class.java)
                    .invoke(builder, AttributionContext(shellContext))
            }
            val created =
                try {
                    builder.build()
                } catch (t: Throwable) {
                    throw java.io.IOException("submix AudioRecord build: ${t.message}")
                }
            if (created.state != android.media.AudioRecord.STATE_INITIALIZED) {
                runCatching { created.release() }
                throw java.io.IOException("submix not initialized (state=${created.state})")
            }
            record = created
        }

        fun describe(): String = "rate=${record.sampleRate} buffer=${record.bufferSizeInFrames}f state=${record.state}"

        /** 新建管道并确保采集在跑；返回读端给应用。 */
        @Synchronized
        fun openPipe(): ParcelFileDescriptor {
            if (closed.get()) throw java.io.IOException("submix capture closed")
            val pipe = ParcelFileDescriptor.createPipe()
            if (record.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                record.startRecording()
                if (record.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { pipe[0].close() }
                    runCatching { pipe[1].close() }
                    throw java.io.IOException("submix not recording (state=${record.recordingState})")
                }
            }
            pipeWrite = pipe[1]
            pump =
                Thread(
                    {
                        runCatching {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                        }
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                                val frameBytes = record.sampleRate / 50 * 4 // 20ms 一写（立体声 16bit）
                                val buffer = ByteArray(frameBytes)
                                while (!closed.get()) {
                                    val read = record.read(buffer, 0, frameBytes, android.media.AudioRecord.READ_BLOCKING)
                                    if (read <= 0) continue // 瞬态（无新数据），继续阻塞读
                                    // 整段写出：应用读端断开时抛 IOException → 自愈关闭
                                    out.write(buffer, 0, read)
                                }
                            }
                        } catch (_: Throwable) {
                            // 应用读端关闭（断开/崩溃）——自愈收口，媒体路由随之恢复
                            runCatching { close() }
                        }
                    },
                    "cardeck-submix",
                ).apply {
                    isDaemon = true
                    start()
                }
            return pipe[0]
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { record.stop() }
            runCatching { record.release() }
            // 管道写端随 close 关闭 → 泵线程 write 抛异常退出；AutoCloseOutputStream
            // 已托管写端 fd，此处只做幂等兜底
            runCatching { pipeWrite?.close() }
            pipeWrite = null
            pump = null
        }
    }

    private class Injector(
        val receiver: Any,
        val method: Method,
    )

    private val injector: Injector? by lazy {
        val im =
            try {
                context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            } catch (t: Throwable) {
                Log.d(TAG, "probe getSystemService(INPUT_SERVICE) 异常", t)
                null
            }
        if (im != null) {
            val m = findInjectMethod(im.javaClass.methods)
            if (m != null) return@lazy Injector(im, m)
        }
        try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val global = cls.getMethod("getInstance").invoke(null)
            val m = findInjectMethod(cls.methods)
            if (m != null) return@lazy Injector(global, m)
        } catch (t: Throwable) {
            Log.d(TAG, "probe InputManagerGlobal 异常", t)
        }
        null
    }

    private fun findInjectMethod(methods: Array<Method>): Method? =
        methods.firstOrNull {
            it.name == "injectInputEvent" &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[0] == InputEvent::class.java &&
                it.parameterTypes[1] == Integer.TYPE
        }

    private val setDisplayIdMethod: Method? by lazy {
        (InputEvent::class.java.methods + MotionEvent::class.java.methods)
            .firstOrNull { it.name == "setDisplayId" && it.parameterTypes.size == 1 }
    }

    private var injectFailLogged = false

    private fun inject(
        event: InputEvent,
        displayId: Int,
        // 注入模式（AOSP InputManager：ASYNC=0，WAIT_FOR_RESULT=1，WAIT_FOR_FINISHED=2）
        mode: Int = 2,
    ): Boolean {
        val inj = injector
        if (inj == null) {
            recycle(event)
            return false
        }
        return try {
            setDisplayIdMethod?.invoke(event, displayId)
            val m = inj.method
            val result: Boolean =
                when {
                    m.parameterTypes.size == 2 -> m.invoke(inj.receiver, event, mode) as Boolean
                    m.parameterTypes[2] == java.lang.Long.TYPE -> m.invoke(inj.receiver, event, mode, 0L) as Boolean
                    else -> m.invoke(inj.receiver, event, mode, displayId) as Boolean
                }
            if (!result && !injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "injectInputEvent 返回 false method=$m")
            }
            result
        } catch (t: Throwable) {
            if (!injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "inject 异常 method=${inj.method}", t)
            }
            false
        } finally {
            recycle(event)
        }
    }

    /** KeyEvent 没有可复用的池化语义（recycle 已废弃），只有 MotionEvent 需要回收。 */
    private fun recycle(event: InputEvent) {
        (event as? MotionEvent)?.recycle()
    }

    /**
     * 按键注入（iDrive 旋钮 → 方向键/确认键）：与触摸共用 injectInputEvent，
     * 差别只是 event 换成 KeyEvent 且同样 setDisplayId 到目标虚拟屏。
     */
    override fun keyEvent(
        displayId: Int,
        keyCode: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        if (!inject(down, displayId)) return false
        val up = KeyEvent(now, now + 24, KeyEvent.ACTION_UP, keyCode, 0)
        return inject(up, displayId)
    }

    @Suppress("DEPRECATION")
    private fun build(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent? =
        try {
            val e =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    1f,
                    1f,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                )
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            e
        } catch (t: Throwable) {
            Log.d(TAG, "build MotionEvent 异常", t)
            null
        }

    override fun tap(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val up = build(now, now + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    override fun swipe(
        displayId: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val duration = durationMs.coerceIn(50, 1500)
        val down = build(now, now, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val steps = 8
        for (i in 1 until steps) {
            val t = duration * i / steps
            val ix = startX + (endX - startX) * i / steps
            val iy = startY + (endY - startY) * i / steps
            val move = build(now, now + t, MotionEvent.ACTION_MOVE, ix.toFloat(), iy.toFloat()) ?: return false
            if (!inject(move, displayId)) return false
        }
        val up = build(now, now + duration, MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat()) ?: return false
        return inject(up, displayId)
    }

    // ---- 触摸注入：per-slot 独立会话（2026-11-21 v2）----
    // 每个 slot 各自一套拖拽会话（downTime/displayId），互不干扰；构建用 Float
    // obtain 单指形态（source=0）——多点改造前的已验证实现，滑动流畅的基线。
    // Float obtain 的 pointerId 固定 0 → 同时只支持一指：第二指的 DOWN 会被派发器
    // 按流一致性拒绝（返回 false），应用侧副指整段忽略（TouchRouter）——双指缩放
    // 待 buildMulti（多指针事件构建）修好后恢复。

    private class SlotDrag(
        val slot: Int,
        var downTime: Long, // 自愈重建流时会重置（MOVE 的 downTime 必须与 DOWN 一致）
        var displayId: Int,
        var x: Float,
        var y: Float,
    )

    private val slotDrags = java.util.HashMap<Int, SlotDrag>()

    /** 注入流断自愈计数（诊断用）。 */
    private var resyncCount = 0

    /**
     * 触摸注入诊断日志开关（默认关）：每次 DOWN/POINTER_DOWN/UP 打一条事件 dump
     * 到 carinput_svc.log——排查多点触控问题用，平时关闭避免日志膨胀。
     */
    private var touchDiagLog = false

    private var multiMoveCount = 0

    /** 本流曾包含 ≥2 指针（多指流中抬指必须用 buildMulti——Float 单指 UP 会卡死其它指针）。 */
    private var multiStreamActive = false

    override fun pointerDown(
        slot: Int,
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        // 双 DOWN 吸收（该 slot 已在拖拽中——状态脱节/跨队列边界）
        if (slotDrags.containsKey(slot)) return true
        val now = SystemClock.uptimeMillis()
        val secondFinger = slotDrags.isNotEmpty()
        if (secondFinger) {
            // 第二指按下 → 切多指流：注入「全部指针 + POINTER_DOWN(slot)」事件
            // （应用识别缩放手势的前提 = 同一事件流携带多个指针；两个独立单指流不行）
            slotDrags[slot] = SlotDrag(slot, now, displayId, x.toFloat(), y.toFloat())
            multiStreamActive = true
            val ev =
                buildMulti(displayId, now, MotionEvent.ACTION_POINTER_DOWN, slot) ?: run {
                    fileLog("TOUCH-DIAG: slot=$slot P_DOWN buildMulti=null")
                    return false
                }
            val ok = inject(ev, displayId, 2)
            if (touchDiagLog) fileLog("TOUCH-DIAG: slot=$slot P_DOWN disp=$displayId n=${slotDrags.size} ok=$ok ev=$ev")
            return ok
        }
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        val ok = inject(down, displayId, 2)
        if (ok) slotDrags[slot] = SlotDrag(slot, now, displayId, x.toFloat(), y.toFloat())
        if (touchDiagLog) fileLog("TOUCH-DIAG: slot=$slot DOWN disp=$displayId ok=$ok")
        return ok
    }

    override fun pointerMove(
        slot: Int,
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val drag = slotDrags[slot] ?: return true // 吸收：无会话的 MOVE 是过期残留
        if (drag.displayId != displayId) return true // 异屏 MOVE：吸收
        val now = SystemClock.uptimeMillis()
        drag.x = x.toFloat()
        drag.y = y.toFloat()
        if (slotDrags.size >= 2) {
            // 多指流：MOVE 事件携带全部活动指针的最新坐标
            val ev = buildMulti(displayId, now, MotionEvent.ACTION_MOVE, slot) ?: return false
            val ok = inject(ev, displayId, 0)
            multiMoveCount += 1
            if (multiMoveCount % 120 == 1) {
                fileLog("TOUCH-DIAG: slot=$slot MOVE-MULTI disp=$displayId n=${slotDrags.size} ok=$ok cnt=$multiMoveCount ev=$ev")
            }
            return ok
        }
        val move = build(drag.downTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat()) ?: return false
        // MOVE 走 ASYNC 模式（2026-11-20）：WAIT_FOR_FINISHED 每条阻塞到被完整消费，
        // 高频拖拽时注入耗时 > 事件到达间隔 → 队列积压 → 注入位置落后手指（滞后根因）。
        val ok = inject(move, displayId, 0)
        if (ok) return true
        // 流断自愈：显示配置变更（地图 resize/letterbox 重启等）会重置派发器内该屏的
        // 注入指针跟踪——之后的 MOVE 因「无 DOWN 可配对」被一致性拒绝，整段断流。
        // 自动重发 DOWN（当前位置）重建流再补 MOVE，用户无感、拖拽继续。
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        if (!inject(down, displayId, 2)) return false
        drag.downTime = now
        val move2 = build(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat()) ?: return false
        inject(move2, displayId, 0)
        resyncCount += 1
        fileLog("TOUCH: 注入流断——已自动重发 DOWN 重建（第 $resyncCount 次）")
        return true
    }

    override fun pointerUp(
        slot: Int,
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        // ⚠ **先取不移除**：UP 事件必须携带全部指针（含本指）——remove 先行会让事件
        // 缺本指（主指抬起变成其它指的 UP、本指卡死 → 后续点击全失效，2026-11-21 实测）。
        val drag = slotDrags[slot] ?: return true // 吸收：无会话的 UP
        if (drag.displayId != displayId) return true // 异屏 UP：吸收
        val now = SystemClock.uptimeMillis()
        drag.x = x.toFloat()
        drag.y = y.toFloat()
        val remaining = slotDrags.size - 1
        // ⚠ 抬指构建器按流形态选：**multiStreamActive 时必须走 buildMulti**（事件携带
        // 表内全部指针）——Float 单指 UP 只带 pointerId=0，多指流中会卡死其它指针
        // （id1 永不抬起 → 流被污染 → 缩放全废，2026-11-21 诊断实锤）。表清空后退出
        // 多指流形态。
        val up =
            if (multiStreamActive || remaining >= 1) {
                buildMulti(displayId, now, if (remaining >= 1) MotionEvent.ACTION_POINTER_UP else MotionEvent.ACTION_UP, slot)
                    ?: return false
            } else {
                build(drag.downTime, now, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
            }
        val ok = inject(up, displayId, 2)
        if (touchDiagLog) fileLog("TOUCH-DIAG: slot=$slot UP disp=$displayId remaining=$remaining ok=$ok multi=$multiStreamActive")
        if (ok) slotDrags.remove(slot)
        if (slotDrags.isEmpty()) multiStreamActive = false
        return ok
    }

    /**
     * 构建多指针 MotionEvent（slotDrags 全表，slot 排序 → pointerIndex 稳定）。
     * PointerCoords 补齐 toolMajor/contactMajor 等接触面积字段（全 0 会被部分应用
     * 忽略）；toolType=FINGER（IntArray 版 obtain 的 UNKNOWN toolType 是上版
     * 「注入 OK 但应用无响应」根因）；pointerId=slot；displayId 走重载尾参。
     */
    private fun buildMulti(
        displayId: Int,
        now: Long,
        action: Int,
        actionSlot: Int,
    ): MotionEvent? {
        val entries = slotDrags.entries.sortedBy { it.key }
        val n = entries.size
        if (n == 0) return null
        val props = Array(n) { MotionEvent.PointerProperties() }
        val coords = Array(n) { MotionEvent.PointerCoords() }
        var slotIndex = -1
        entries.forEachIndexed { i, (slot, drag) ->
            props[i].id = slot
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].x = drag.x
            coords[i].y = drag.y
            coords[i].pressure = 1f
            coords[i].size = 1f
            coords[i].toolMajor = 40f
            coords[i].toolMinor = 40f
            coords[i].touchMajor = 40f
            coords[i].touchMinor = 40f
            if (slot == actionSlot) slotIndex = i
        }
        val downTime = entries.minOf { it.value.downTime }
        val actionFinal =
            when {
                action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP ->
                    action or (slotIndex.coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                else -> action
            }
        // ⚠ obtain 位置参数在这台 ROM 上 edgeFlags/source/flags 区段不可信（实机 dump：
        // source=0x0、edgeFlags=0x1002——恰好是我传的 source 值错位进了 edgeFlags 槽）。
        // 有效的旧 Float 版用 **setSource setter**——这里 obtain 后同样显式补设。
        return MotionEvent
            .obtain(
                downTime,
                now,
                actionFinal,
                n,
                props,
                coords,
                0, // metaState
                0, // buttonState
                0f,
                0f, // xPrecision / yPrecision
                0, // edgeFlags
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0, // flags
                displayId, // displayId 重载尾参
            ).apply { setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) }
    }

    // ---- 受信虚拟屏 ----

    // VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 30（@hide/@SystemApi，编译期不可引用，写原始值）
    private val virtualDisplays = HashMap<Int, android.hardware.display.VirtualDisplay>()

    // ---- IME 展示策略 ----

    /**
     * 受信虚拟屏的 IME 策略默认是 FALLBACK——键盘被系统路由到手机默认屏，车机侧永远
     * 看不到（2026-10-17 定谳，车机「设置」搜索框点击无键盘根因）。创建受信屏后显式
     * 设 LOCAL 让键盘画进本屏、随合成流推给车机。
     *
     * 枚举值反射自 SystemApi 常量而非硬编码 0：厂商 ROM 有魔改枚举值的风险（2026-10-17
     * 排查时按此防御；本机实测与 AOSP 一致 = 0）。读不到时回落 0 并记日志。
     */
    private val imePolicyLocal: Int by lazy {
        val value =
            try {
                Class
                    .forName("android.view.WindowManager")
                    .getField("DISPLAY_IME_POLICY_LOCAL")
                    .getInt(null)
            } catch (t: Throwable) {
                fileLog("DISPLAY_IME_POLICY_LOCAL 反射失败，回退 AOSP 值 0：$t")
                0
            }
        fileLog("DISPLAY_IME_POLICY_LOCAL=$value")
        value
    }

    /**
     * IWindowManager 代理（按方法名反射，不依赖 AIDL 事务号）。
     * 该 ROM 上走 ServiceManager+Stub.asInterface；WindowManagerGlobal.getWindowManagerService
     * 路径会致进程被杀（2026-10-17 实测），勿用。
     */
    private val windowManagerProxy: Any? by lazy {
        try {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "window")
            Class
                .forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (t: Throwable) {
            fileLog("IWindowManager 反射失败（IME 策略将不生效）：$t")
            null
        }
    }

    /**
     * 把受信虚拟屏的 IME 策略设为 LOCAL（键盘画进本屏）。
     * 失败不影响主链路：最坏情况与改动前一致——键盘留在手机主屏、车机侧不可见。
     */
    private fun applyImePolicyLocal(displayId: Int) {
        val proxy = windowManagerProxy ?: return
        try {
            proxy.javaClass
                .getMethod("setDisplayImePolicy", Integer.TYPE, Integer.TYPE)
                .invoke(proxy, displayId, imePolicyLocal)
            val readBack =
                runCatching {
                    proxy.javaClass
                        .getMethod("getDisplayImePolicy", Integer.TYPE)
                        .invoke(proxy, displayId)
                }.getOrNull()
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) ok, 读回=$readBack")
        } catch (t: Throwable) {
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) 失败：$t")
        }
    }

    /** 文件日志：shell 进程 logcat 被 HKS 加密，写 /data/local/tmp 供 adb 读取。 */
    private fun fileLog(message: String) {
        try {
            java.io.FileWriter("/data/local/tmp/carinput_svc.log", true).use { w ->
                w
                    .append(
                        android.text.format.DateFormat
                            .format("MM-dd HH:mm:ss.SSS", System.currentTimeMillis())
                            .toString(),
                    ).append(' ')
                    .append(message)
                    .append('\n')
            }
        } catch (_: Throwable) {
        }
    }

    // WrongConstant：flags 里的 TRUSTED/ALWAYS_UNLOCKED/OWN_DISPLAY_GROUP 是 @hide 常量
    // （运行时可行性已真机实证），lint 的 IntDef 模型只认公开 API。
    @android.annotation.SuppressLint("WrongConstant")
    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        trusted: Boolean,
    ): Int {
        return try {
            fileLog("createDisplay $name ${width}x$height@$dpi start")
            // 关键：必须用「shell 包的 Context」——系统检查 packageName 与调用 uid(2000) 匹配，
            // 用本 app 的 Context 会报 SecurityException('packageName must match the owner uid')
            fileLog("ctx package=${shellContext.packageName}")
            // VDM 路线优先（锁屏推流官方解）：gate（SDK/MAC/会话失败标记）通过 → 关联+虚拟设备
            // 就绪 → 按 flags 候选表建屏。任何失败逐级落回下方 legacy DisplayManager 路径。
            val vdm = obtainVdmSession()
            if (vdm != null) {
                val candidates =
                    if (trusted) VirtualDeviceRoute.trustedFlagCandidates else VirtualDeviceRoute.dockFlagCandidates
                var created: android.hardware.display.VirtualDisplay? = null
                for (flags in candidates) {
                    created =
                        runCatching {
                            vdm.createDisplay(name, width, height, dpi, surface, flags)
                        }.onFailure {
                            fileLog(
                                "VDM: $name flags=0x${flags.toString(16)} 建屏失败：" +
                                    describeCause(it),
                            )
                        }.getOrNull()
                    if (created != null) break
                }
                val vd = created
                if (vd != null) {
                    val id = vd.display.displayId
                    synchronized(virtualDisplays) { virtualDisplays[id] = vd }
                    if (!keepDisplayAwake(id, "vdm create $name")) {
                        fileLog("$name(id=$id) VDM 屏未获得 per-display 保活（依赖虚拟设备自身语义）")
                    }
                    if (trusted) applyImePolicyLocal(id)
                    Log.i(TAG, "createDisplay $name id=$id (VDM) size=${width}x$height@$dpi")
                    return id
                }
                // flags 全灭：本会话不再尝试 VDM（余屏避免重复失败），直接 legacy
                vdmFailedThisSession = true
                fileLog("VDM: $name 全部 flags 候选失败——本会话改走 legacy")
            }
            val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6（@hide）：
            // 不声明时应用看到 "-touch" 配置（无触摸设备）→ 部分应用（如头条）
            // 切 TV/桌面式大头部布局（2026-10-11 实测：搜索区占满半屏的根因）。
            //
            // VIRTUAL_DISPLAY_FLAG_PUBLIC（公开 API = 1）必须随受信屏一起声明（2026-10-17 定谳）：
            // 不声明时 DisplayDeviceInfo 带 FLAG_PRIVATE（owner=shell 2000），而 WMS.addWindow
            // 的访问闸 displayContent.hasAccess(session.mUid) → Display.hasAccess 对私有屏只放行
            // owner/system/root/UID-present——第三方输入法（uid 10xxx）的键盘窗口会被
            // ADD_INVALID_DISPLAY(-9) 拒绝，永远加不进该屏（车机侧「设置」点搜索框无键盘的
            // 最终根因：策略已 LOCAL、令牌已迁屏，IME 服务也 showWindow 了，但窗口加不进）。
            // 公开化只放宽窗口访问；ROM 的「公开屏默认自动镜像」仅在未声明
            // OWN_CONTENT_ONLY 时生效，我们已声明，不受影响。dock 屏不受信、无输入场景，
            // 维持私有（也避免无关窗口挂上去）。
            val baseFlags =
                (if (trusted) FLAG_TRUSTED or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0) or
                    FLAG_SUPPORTS_TOUCH or
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            // 受信屏额外要「独立显示组 + 永远已解锁」：
            //  · OWN_DISPLAY_GROUP：手机主屏休眠时虚拟屏不跟着休眠（物理屏断电后仍出帧）；
            //  · ALWAYS_UNLOCKED：设备锁屏时该屏不画 Keyguard（否则车机看到锁屏/黑屏）。
            // OWN_DISPLAY_GROUP 复用 ADD_TRUSTED_DISPLAY（受信屏本来就要）；ALWAYS_UNLOCKED
            // 另需 ADD_ALWAYS_UNLOCKED_DISPLAY——部分 ROM 未授予 shell，带上会抛
            // SecurityException 把整块屏打挂，因此逐级降级重试。
            var vd: android.hardware.display.VirtualDisplay? = null
            if (trusted) {
                vd =
                    createVirtualDisplayAttempt(
                        dm,
                        name,
                        width,
                        height,
                        dpi,
                        surface,
                        baseFlags or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
                        "受信+独立组+ALWAYS_UNLOCKED",
                    )
                if (vd == null) {
                    fileLog("$name 带 ALWAYS_UNLOCKED 失败——降级为独立组（ROM 可能未给 shell 该权限）")
                    vd =
                        createVirtualDisplayAttempt(
                            dm,
                            name,
                            width,
                            height,
                            dpi,
                            surface,
                            baseFlags or FLAG_OWN_DISPLAY_GROUP,
                            "受信+独立组",
                        )
                }
                if (vd == null) {
                    fileLog("$name 带 OWN_DISPLAY_GROUP 失败——回退基础 flags")
                    vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "受信基础")
                }
            } else {
                // dock 屏（不受信）默认落在**默认显示组**：物理主屏断电/锁屏时整组跟着灭，
                // 车机侧表现为「app 屏正常、dock 栏全黑」，解锁后恢复（2026-10-23 实测）。
                // 与受信屏同款三级降级：组+解锁 → 组 → 基础。
                //  · AOSP 只在 AUTO_MIRROR 时清掉 OWN_DISPLAY_GROUP，而 AUTO_MIRROR 只随
                //    PUBLIC 自动置上；dock 没声明 PUBLIC，所以该 flag 会保留；
                //  · 两者都只要求权限（ADD_TRUSTED_DISPLAY / ADD_ALWAYS_UNLOCKED_DISPLAY），
                //    不改变 dock「不受信」的语义（第三方任务仍然上不去）。
                vd =
                    createVirtualDisplayAttempt(
                        dm,
                        name,
                        width,
                        height,
                        dpi,
                        surface,
                        baseFlags or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
                        "非受信+独立组+ALWAYS_UNLOCKED",
                    )
                if (vd == null) {
                    fileLog("$name 带 ALWAYS_UNLOCKED 失败——降级为独立组（ROM 可能未给 shell 该权限）")
                    vd =
                        createVirtualDisplayAttempt(
                            dm,
                            name,
                            width,
                            height,
                            dpi,
                            surface,
                            baseFlags or FLAG_OWN_DISPLAY_GROUP,
                            "非受信+独立组",
                        )
                }
                if (vd == null) {
                    fileLog("$name 带 OWN_DISPLAY_GROUP 失败——回退基础 flags（主屏断电时该屏会跟着休眠）")
                    vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "非受信")
                }
            }
            val created = vd
            val id = created?.display?.displayId ?: -1
            fileLog("createVirtualDisplay -> vd=$created id=$id")
            // 读回校验（2026-10-23 荣耀实测）：flag 生效时 Display 层 flags 应带
            // ALWAYS_UNLOCKED（1 << 9）——不带 OWN_DISPLAY_GROUP 时系统会静默剥掉
            // ALWAYS_UNLOCKED（创建成功无异常但读回没有），必须靠读回才能发现。
            if (id >= 0) {
                val realFlags =
                    try {
                        dm.getDisplay(id)?.flags ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                val effective = (realFlags and 0x200) != 0
                fileLog(
                    "ALWAYS_UNLOCKED 读回 display.flags=0x${realFlags.toString(16)} -> " +
                        if (effective) "生效" else "未生效（锁屏仍会影响该屏）",
                )
            }
            if (created == null || id < 0) {
                Log.w(TAG, "createDisplay $name -> null")
                return -1
            }
            synchronized(virtualDisplays) { virtualDisplays[id] = created }
            // 只有受信屏能设 IME 策略（WMS 对非受信屏抛 SecurityException），且正需要它：
            // dock 屏只承载自有 DockActivity，无输入场景
            if (trusted) applyImePolicyLocal(id)
            // per-display 亮屏锁（真休眠表面剥离的对策，见 keepDisplayAwake 注释）：
            // 旧系统无该 API 时静默降级为现状
            if (!keepDisplayAwake(id, "create $name")) {
                fileLog("$name(id=$id) 未获得 per-display 保活（降级，主屏休眠可能拖累该屏）")
            }
            Log.i(TAG, "createDisplay $name id=$id size=${width}x$height@$dpi")
            id
        } catch (t: Throwable) {
            fileLog("createDisplay $name failed: $t")
            Log.w(TAG, "createDisplay $name failed: $t")
            -1
        }
    }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        dpi: Int,
    ): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return false
        return try {
            vd.resize(width, height, dpi)
            Log.i(TAG, "resizeDisplay id=$displayId -> ${width}x$height@$dpi")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "resizeDisplay $displayId failed: $t")
            false
        }
    }

    override fun setDisplaySurface(
        displayId: Int,
        surface: Surface,
    ): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return false
        return try {
            vd.setSurface(surface)
            Log.i(TAG, "setDisplaySurface id=$displayId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDisplaySurface $displayId failed: $t")
            false
        }
    }

    override fun releaseDisplay(displayId: Int): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) }
        if (vd == null) {
            // 不在本进程 map：该屏由其它（旧版本）UserService 进程创建，只有创建者能释放。
            // 应用侧兜底见 CarInputClient.reapStaleUserServices（杀持有进程）。
            fileLog("releaseDisplay id=$displayId -> 非本进程持有（跨进程无法释放）")
            return false
        }
        releaseDisplayKeepAwake(displayId)
        synchronized(vdmLock) { vdmSession?.discard(displayId) }
        return try {
            vd.release()
            Log.i(TAG, "releaseDisplay id=$displayId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "releaseDisplay $displayId failed: $t")
            false
        }
    }

    /** 创建一次虚拟屏；失败（含 SecurityException / ROM 静默返回 null）返回 null 并留痕。 */
    private fun createVirtualDisplayAttempt(
        dm: android.hardware.display.DisplayManager,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        label: String,
    ): android.hardware.display.VirtualDisplay? =
        try {
            fileLog("createDisplay($name) $label flags=0x${flags.toString(16)}")
            dm.createVirtualDisplay(name, width, height, dpi, surface, flags, null, null)
        } catch (t: Throwable) {
            fileLog("createDisplay($name) $label 异常：$t")
            null
        }

    // ---- 物理主屏电源（真息屏；系统保持唤醒） ----

    /**
     * 对**物理主屏**做硬件级断电/点亮（同 scrcpy `-S/--turn-screen-off`）：
     * 不按电源键（那会锁屏），而是直接调 SurfaceFlinger 的 setDisplayPowerMode。
     * 系统仍认为屏幕亮着 → 不锁屏、不进 Doze，虚拟屏继续出帧。
     */
    override fun setPhysicalScreenPower(on: Boolean): Boolean {
        val ok = setPanelPower(on)
        if (ok) physicalScreenOff = !on
        fileLog("setPhysicalScreenPower($on) ok=$ok")
        return ok
    }

    /**
     * 面板电源统一核心（两套实现的合并点，2026-10-24）：
     * token 三路枚举按「实测通过率」排序（任何 ROM 只要有一路可用就能工作）；
     * 断电/点亮统一走 SurfaceControl.setDisplayPowerMode 反射。
     */
    private fun setPanelPower(on: Boolean): Boolean {
        val token =
            physicalDisplayToken() ?: run {
                fileLog("setPanelPower($on) 失败：各入口都拿不到内置屏 token")
                return false
            }
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        return try {
            Class
                .forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", IBinder::class.java, Integer.TYPE)
                .invoke(null, token, mode)
            true
        } catch (t: Throwable) {
            fileLog("setPanelPower($on) 失败：$t")
            false
        }
    }

    /**
     * 物理主屏 token，三路按序回退（不同 ROM 删改面不同，全试最稳）：
     * ① SurfaceControl.getInternalDisplayToken——荣耀 A17（SDK 37）实测唯一可用，
     *    放首位（MagicOS 保留了这条「更老」API）；
     * ② DisplayControl 类加载器——SDK 34+ 的正统入口（scrcpy 同款：services.jar 里的
     *    类要 ClassLoaderFactory + android_servers JNI 才可见）；
     * ③ SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken——Android 11~14 旧入口。
     * 首个物理屏 id 即内置屏（AOSP 约定数组首元素为默认屏）。
     */
    // BlockedPrivateApi/DEPRECATION：本方法刻意走隐藏/废弃 API（UserService 跑在 shell
    // 域不受应用层 hidden API 强制约束；三路回退的运行时行为已真机实证）。
    @android.annotation.SuppressLint("BlockedPrivateApi")
    @Suppress("DEPRECATION")
    private fun physicalDisplayToken(): IBinder? {
        // ① getInternalDisplayToken（部分 ROM 的 SurfaceControl 仍保留）
        runCatching {
            val sc = SurfaceControl::class.java
            (sc.getDeclaredMethod("getInternalDisplayToken").invoke(null) as? IBinder)?.let {
                fileLog("token via getInternalDisplayToken")
                return it
            }
        }.onFailure { fileLog("getInternalDisplayToken 入口失败：${it.message}") }
        // ② DisplayControl（新系统：方法已从 SurfaceControl 迁出）
        displayControlClass?.let { cls ->
            runCatching {
                val ids = cls.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                val id = ids.firstOrNull() ?: return@runCatching
                (cls.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!).invoke(null, id) as? IBinder)?.let {
                    fileLog("token via DisplayControl id=$id")
                    return it
                }
            }.onFailure { fileLog("DisplayControl token 入口失败：${it.message}") }
        }
        // ③ getPhysicalDisplayIds/getPhysicalDisplayToken（Android 11~14）
        runCatching {
            val sc = SurfaceControl::class.java
            val ids = sc.getDeclaredMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val id = ids.firstOrNull() ?: return@runCatching
            (sc.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id) as? IBinder)?.let {
                fileLog("token via SurfaceControl.getPhysicalDisplayToken id=$id")
                return it
            }
        }.onFailure { fileLog("getPhysicalDisplayToken 入口失败：${it.message}") }
        return null
    }

    /** DisplayControl 类加载器（真息屏用）：只运行在 Shizuku UserService（shell uid）里。 */
    private val displayControlClass: Class<*>? by lazy {
        try {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create =
                factory.getDeclaredMethod(
                    "createClassLoader",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                    Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    String::class.java,
                )
            val classpath = System.getenv("SYSTEMSERVERCLASSPATH")
            if (classpath.isNullOrEmpty()) {
                fileLog("SYSTEMSERVERCLASSPATH 为空——无法加载 DisplayControl")
                return@lazy null
            }
            val loader =
                create.invoke(
                    null,
                    classpath,
                    null,
                    null,
                    ClassLoader.getSystemClassLoader(),
                    0,
                    true,
                    null,
                ) as ClassLoader
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            // 静态 JNI 方法在 libandroid_servers.so 里注册；必须加载进这个 ClassLoader
            registerAndroidServersLibrary(cls)
            fileLog("DisplayControl 已就绪")
            cls
        } catch (t: Throwable) {
            fileLog("DisplayControl 加载失败（SDK ${Build.VERSION.SDK_INT}）：$t")
            null
        }
    }

    /**
     * 把 libandroid_servers.so 的 JNI 注册进 [cls] 所在的 ClassLoader（scrcpy 同款路径）。
     *
     * 本类只运行在 Shizuku UserService（shell uid 2000 的 app_process），不受应用进程的
     * hidden-API 限制；lint 的 BlockedPrivateApi 按 targetSdk 应用进程判定，故此处豁免。
     */
    @SuppressLint("BlockedPrivateApi")
    private fun registerAndroidServersLibrary(cls: Class<*>) {
        val loadLibrary0 =
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary0.isAccessible = true
        loadLibrary0.invoke(Runtime.getRuntime(), cls, "android_servers")
    }

    override fun ping(): Boolean = injector != null

    override fun setScreenPower(on: Boolean): Boolean {
        val ok = setPanelPower(on)
        fileLog("setScreenPower($on) ok=$ok")
        return ok
    }

    override fun diag(): String =
        "injector=" + (injector?.let { "${it.receiver.javaClass.name} ${it.method}" } ?: "null") +
            " setDisplayId=${setDisplayIdMethod != null}" +
            " vdm=" + (vdmSession?.describe() ?: if (vdmFailedThisSession) "failed-this-session" else "off") +
            " companionMac=${companionMac ?: "-"}"

    override fun pid(): Int = android.os.Process.myPid()

    companion object {
        private const val TAG = "CarInputSvc"

        /** 自清巡检周期：重装 APK 后旧进程最迟约 15s 内自行退出（成本仅一次 PM 查询）。 */
        private const val STALE_CHECK_INTERVAL_MS = 15_000L

        /** VIRTUAL_DISPLAY_FLAG_TRUSTED（@hide；AOSP 定义 = 1 << 10 = 0x400）。 */
        private const val FLAG_TRUSTED = 1 shl 10

        /** VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH（@hide；AOSP 定义 = 1 << 6 = 0x40）。 */
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

        /**
         * VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP（@hide；AOSP 定义 = 1 << 11 = 0x800）。
         * 虚拟屏进自己的 DisplayGroup：手机主屏休眠/断电时，它不跟着进入 OFF。
         * 需要 ADD_TRUSTED_DISPLAY（受信屏本来就要）——见 DisplayManagerService 的校验。
         */
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

        /**
         * VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED（@hide；AOSP 定义 = 1 << 12 = 0x1000）。
         * 锁屏状态下该屏不显示 Keyguard。**另需 ADD_ALWAYS_UNLOCKED_DISPLAY**，
         * 部分 ROM 未授予 shell——createDisplay 里必须能降级重试，否则整块屏创建失败。
         */
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

        /** SurfaceControl 显示电源模式：OFF=硬件断电，NORMAL=正常点亮。 */
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2

        /** SurfaceControl 显示电源模式（@hide；AOSP 定义 OFF=0 / NORMAL=2，反射失败时兜底用）。 */

        /** 伴侣关联的 shell 包名 + App Streaming 角色/画像（同一字符串，AOSP 定义）。 */
        private const val CDM_PACKAGE = "com.android.shell"
        private const val ROLE_APP_STREAMING = "android.app.role.COMPANION_DEVICE_APP_STREAMING"

        /** Display.DEFAULT_DISPLAY_GROUP（@hide；AOSP 定义 = 0，主屏所在显示组）。 */
        private const val DEFAULT_DISPLAY_GROUP = 0

        /** IDisplayManager.requestDisplayPower 的 state 参数：2 = STATE_ON。 */
        private const val REQUEST_POWER_STATE_ON = 2

        /** 显示电源看门狗轮询周期。 */
        private const val WATCHDOG_INTERVAL_MS = 3_000L
    }
}
