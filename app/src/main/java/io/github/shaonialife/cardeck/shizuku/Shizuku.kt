package io.github.shaonialife.cardeck.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.shaonialife.cardeck.util.Bg
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 网关 + shell 执行 + CarInput UserService 绑定。
 * 激活（无线调试，Android 11+，免 root）：开发者选项 → 无线调试 → 配对 → Shizuku App 点启动。
 * （移植自参考项目 android-auto）
 *
 * 2026-11-25 起实现无关（binder-first + 通用发现）：api 13 的 binder 获取是纯推送
 * 协议（服务端扫描应用声明的 `<pkg>.shizuku_provider` 主动送 binder，客户端零实现
 * 清单），Shizuku 官方 / Sui（root 版）/ Shizuku+ 及一切忠实 fork 天然可用——本网关
 * 只按 binder 探活授权，包名仅用于引导（未装/未启动文案、打开按钮、解冻看门狗），
 * 经生态标准权限的定义方通用发现（见 [installed]）。
 */
object ShizukuGate {
    enum class State { NOT_INSTALLED, NOT_RUNNING, UNAUTHORIZED, READY }

    /** Shizuku 授权请求码（授权结果回调按它过滤）。 */
    const val PERMISSION_REQUEST_CODE = 101

    /** 生态标准权限：其**定义方**（packageName）即已安装的服务端实现（真机验证）。 */
    private const val MANAGER_PERMISSION = "moe.shizuku.manager.permission.MANAGER"

    /** 已安装的服务端实现（引导文案 /「打开」按钮 / 解冻看门狗用）。 */
    data class Implementation(
        val pkg: String,
        val label: String,
    )

    /**
     * 已知实现兜底清单（探测顺序 = 列表序）。[installed] 的权限定义发现是主路径，
     * 这里只兜「权限定义缺失/改名」的异常形态。官方包名同时是 Shizuku+ Drop-In 版 /
     * Compat Hub 转发器注册的包名，放最前。（internal：单测钉死清单内容）
     */
    internal val KNOWN_IMPLEMENTATIONS =
        listOf(
            "moe.shizuku.privileged.api", // Shizuku 官方（免 root）
            "moe.shizuku.privilegedapi", // Sui（root 版，Magisk 模块）
            "af.shizuku.plus.api", // Shizuku+（社区分支）
        )

    /**
     * 本机已安装的实现（null = 一个都没有）。
     *
     * **通用发现优先**：api 13 的 binder 获取是纯推送（服务端扫描 `<pkg>.shizuku_provider`
     * 主动推送，客户端无任何实现清单），任何忠实 fork 都能交付 binder——检测同样不
     * 应写死包名。生态标准权限 `moe.shizuku.manager.permission.MANAGER` 由服务端
     * App 声明（signature 级，真机实证 package= 定义方），fork 继承声明 → 定义方
     * 包名即「用户实际装的那个」，标签取应用名自动跟随。
     */
    fun installed(context: Context): Implementation? {
        val pm = context.packageManager
        runCatching {
            val perm = pm.getPermissionInfo(MANAGER_PERMISSION, 0)
            val pkg = perm.packageName
            if (!pkg.isNullOrBlank() && pkg != context.packageName) {
                val label =
                    runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault("Shizuku")
                return Implementation(pkg, label)
            }
        } // NameNotFoundException = 无实现定义过此权限，走兜底
        return KNOWN_IMPLEMENTATIONS.firstNotNullOfOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                val label =
                    runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault("Shizuku")
                Implementation(pkg, label)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    fun state(context: Context): State {
        // binder-first：binder 活着就说明有某个实现把服务跑起来了（授权事务直接走
        // binder）；binder 死了才按包名区分「装了没启动 / 根本没装」。
        val alive =
            try {
                rikka.shizuku.Shizuku.pingBinder()
            } catch (_: Throwable) {
                false
            }
        if (alive) {
            return try {
                if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    State.READY
                } else {
                    State.UNAUTHORIZED
                }
            } catch (_: Throwable) {
                State.NOT_RUNNING
            }
        }
        return if (installed(context) != null) State.NOT_RUNNING else State.NOT_INSTALLED
    }

    fun requestPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /**
     * 未就绪时的引导文案（纯函数，可单测）。
     *
     * 三种未就绪状态的**处置方式完全不同**（装/启动/授权），混成一句
     * 「Shizuku 未就绪」用户无从下手——这是共享给朋友后最容易被问住的地方。
     */
    internal fun guidanceOf(state: State): ShizukuGuidance =
        when (state) {
            State.NOT_INSTALLED ->
                ShizukuGuidance(
                    title = "需要先安装 Shizuku",
                    message =
                        "连接车机依赖 Shizuku 提供的 shell 权限：创建虚拟屏、注入触摸、" +
                            "读取窗口状态都要经过它。\n\n" +
                            "以下任一实现均可：Shizuku（官方，免 root，ADB / 无线调试启动）、" +
                            "Sui（官方 root 版，Magisk 模块）、Shizuku+（社区分支）。" +
                            "安装并启动服务后，回到这里点「重试检查」。",
                    primaryLabel = null,
                )

            State.NOT_RUNNING ->
                ShizukuGuidance(
                    title = "Shizuku 服务未运行",
                    message =
                        "已安装 Shizuku 系实现，但服务当前没有运行，此时连接会失败" +
                            "（车机没有画面、触摸也不可用）。\n\n" +
                            "请打开对应的 App 启动服务（官方 Shizuku 手机重启后需重新启动一次；" +
                            "Sui 走 Magisk 模块通常开机自启，未运行请检查模块是否启用），" +
                            "然后回来点「重试检查」。",
                    primaryLabel = "打开 Shizuku",
                )

            State.UNAUTHORIZED ->
                ShizukuGuidance(
                    title = "未授权本应用使用 Shizuku",
                    message =
                        "Shizuku 服务已在运行，但本应用还没有获得授权，此时连接会失败" +
                            "（车机没有画面、触摸也不可用）。\n\n" +
                            "点「请求授权」并在弹出的窗口中点「允许」；若之前拒绝过，" +
                            "请到 Shizuku App →「授权应用」里手动允许本应用。",
                    primaryLabel = "请求授权",
                )

            State.READY -> ShizukuGuidance("Shizuku 就绪", "", null)
        }
}

/** Shizuku 未就绪时的引导内容（见 [ShizukuGate.guidanceOf]）。 */
internal data class ShizukuGuidance(
    val title: String,
    val message: String,
    /** null = 本机没有可执行的主操作（未安装时无处可跳）。 */
    val primaryLabel: String?,
)

/** 以 shell 身份执行命令（等同 adb shell）。Shizuku api 13 需经 IShizukuService binder 调 newProcess。 */
object Shell {
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 主线程调用 `Shell.exec` 的累计次数（诊断用；见 [exec] 里的守卫）。 */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /**
     * 在主线程调用 shell 的告警出口（SessionController.appendLog）。
     * 单条 shell 通常 100ms 量级、超时可达数秒，串几条就够 ANR——
     * 这类问题只在真机上暴露（HKS 下 logcat 不可读），必须进文件日志。
     */
    @Volatile
    var violationSink: ((String) -> Unit)? = null

    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        // **UI 线程禁止 shell**：每次 exec 都是 Shizuku newProcess + 进程等待。
        // 这里不强行拦截（会让原本可用的功能失效），但记录并告警——历史上
        // 已有两次真机 ANR 源于此（detach 清理、横屏勾选框）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mainThreadViolations++
            violationSink?.invoke(
                "SHELL: ⚠ 主线程调用 shell（第 $mainThreadViolations 次）cmd=${cmd.take(60)}" +
                    "（$timeoutMs ms 上限）——应改用 Bg.run / 后台线程",
            )
        }
        val binder =
            rikka.shizuku.Shizuku.getBinder()
                ?: return Result(-1, "Shizuku 未连接")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)

            Bg.run("shell-stderr") {
                try {
                    process.errorStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes()
                    }
                } catch (_: Exception) {
                }
            }

            val output =
                try {
                    process.inputStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes().decodeToString().trim()
                    }
                } catch (_: Exception) {
                    ""
                }

            if (!process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())) {
                process.destroy()
            }
            val exit =
                try {
                    process.exitValue()
                } catch (_: Exception) {
                    -1
                }
            Result(exit, output)
        } catch (e: Exception) {
            Result(-1, "命令执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** CarInput UserService 的绑定管理：绑定一次后所有触摸注入直连常驻 shell 进程（毫秒级）。 */
object CarInputClient {
    @Volatile
    var service: ICarInput? = null
        private set

    /**
     * 等待 UserService 就绪（阻塞，**只能在后台线程调用**）。
     *
     * 会话刚激活时服务可能仍在 spawn——此时若立刻判定「受信屏不可用」而回退应用侧
     * PUBLIC 屏，会白白丢掉受信屏的 IME/触摸优势，且回退路径对 UserService 依赖更重
     * （2026-10-22 K60E：首次触摸就注入失败，日志里「三屏创建(回退)」）。
     *
     * @return 就绪的 service；超时返回 null（调用方按原有回退逻辑处理）。
     */
    fun awaitReady(
        context: Context,
        timeoutMs: Long = 3_000L,
    ): ICarInput? {
        service?.let { return it }
        ensure(context)
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            service?.let { return it }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return service
            }
            ensure(context)
        }
        return service
    }

    @Volatile
    private var binding = false

    @Volatile
    private var appContext: Context? = null

    /** UserService 参数（unbind 时需同一实例）。 */
    @Volatile
    private var userServiceArgs: rikka.shizuku.Shizuku.UserServiceArgs? = null

    /** 诊断日志出口（接入文件日志，HKS 加密下 logcat 不可读）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(
        message: String,
        force: Boolean = false,
    ) {
        Log.i(TAG, message)
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - diagAt < 1_000L) return
        diagAt = now
        logSink?.invoke("SHIZUKU: $message")
    }

    private var binderListenerAdded = false

    /**
     * 回收旧版本残留的 UserService 进程，返回实际 kill 的数量（不足条件时 0）。
     *
     * Shizuku 按「组件+进程名+版本(lastUpdateTime)」复用常驻 shell 进程——重装 APK 后
     * 旧版本进程既不受 force-stop 影响、Shizuku 也不回收；而虚拟屏只有创建它的进程能
     * 释放（releaseDisplay 只查本进程的 map），表现为「重启后检测到残留屏，点清理却
     * 释放 0/N」。这里枚举 :car_input 进程，杀掉除当前绑定进程外的全部——旧进程只持有
     * 残留屏，进程回收即屏随之释放（不打断触摸注入，当前会话屏属于绑定进程）。
     * 配合 UserService 侧的过期自清（CarInputServiceImpl 的 stale self-reap）双保险。
     *
     * 阻塞调用（含 shell ps/kill），须在后台线程执行；绑定成功时自动触发一次。
     */
    fun reapStaleUserServices(): Int {
        val bound = service ?: return 0
        val keepPid =
            try {
                bound.pid()
            } catch (t: Throwable) {
                // 旧 dex 无 pid() 事务（正常不会发生：Shizuku 按版本复用，绑定的必是当前版本）
                diag("reap 跳过：pid() 不可用（${t.javaClass.simpleName}）")
                return 0
            }
        val procName = (appContext?.packageName ?: return 0) + ":car_input"
        val ps = Shell.exec("ps -A -o PID,ARGS", 5_000L)
        if (!ps.ok) {
            diag("reap 跳过：ps 失败（${ps.output.take(40)}）")
            return 0
        }
        val stale =
            ps.output
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    if (parts.getOrNull(1) == procName && pid != keepPid) pid else null
                }.toList()
        if (stale.isEmpty()) return 0
        val killed = stale.count { pid -> Shell.exec("kill -9 $pid", 3_000L).ok }
        // force：紧随 bind 的 1s 节流窗口会吞掉这条关键结果（排查时必查）
        diag("已回收 $killed/${stale.size} 个旧版本 UserService 进程（其残留虚拟屏随之释放）", force = true)
        return killed
    }

    /**
     * 物理主屏断电/点亮（真息屏，同 scrcpy `-S`）。失败返回 false：
     * ROM 未给 shell 相应权限，或 SDK 34+ 取不到 display token。
     */
    fun setPhysicalScreenPower(on: Boolean): Boolean =
        try {
            service?.setPhysicalScreenPower(on) ?: false
        } catch (t: Throwable) {
            diag("setPhysicalScreenPower($on) 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }

    /**
     * 按键注入到指定 display（iDrive 旋钮 → 方向键/确认键）。
     * 走 UserService 的 injectInputEvent（毫秒级）；失败时调用方回退 shell `input -d`。
     */
    fun keyEvent(
        displayId: Int,
        keyCode: Int,
    ): Boolean =
        try {
            service?.keyEvent(displayId, keyCode) ?: false
        } catch (t: Throwable) {
            diag("keyEvent($displayId,$keyCode) 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }

    private val conn =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                // ⚠️ 本回调由 Shizuku 库 post 到**主线程**（ShizukuServiceConnection 用
                // new Handler(Looper.getMainLooper())）。因此这里**绝不能**做阻塞调用：
                // ping() 是跨进程 binder 调用，服务进程忙（建虚拟屏/注入）时可能阻塞
                // 数百毫秒甚至更久，一旦主线程被堵，后续所有回调排队、binding 永久为真
                // ——2026-10-22 K60E 真机定谳的「注入必然死锁」正是这条链。
                val bound = ICarInput.Stub.asInterface(binder)
                service = bound
                binding = false
                bindingSince = 0L
                Bg.run("shizuku-ping") {
                    val ok =
                        try {
                            bound?.ping() == true
                        } catch (_: Throwable) {
                            false
                        }
                    diag("connected ping=$ok", force = true)
                    if (!ok) {
                        // 只有仍是本次绑定的结果才清空（期间可能已重绑成功）
                        if (service === bound) {
                            service = null
                            binding = false
                        }
                    } else {
                        // 绑定成功后顺手回收旧版本残留进程（其持有的虚拟屏跨进程无法释放，
                        // 只有进程回收才能让屏消失）。后台线程执行：内部含 shell ps/kill。
                        reapStaleUserServices()
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                diag("service disconnected", force = true)
                service = null
                binding = false
                bindingSince = 0L
            }
        }

    /** 上次绑定尝试时间（防止高频重试）。 */
    @Volatile
    private var lastBindAttemptAt = 0L

    /** 本次绑定发起时刻（判「回调迟迟不来」用；0 = 当前无进行中的绑定）。 */
    @Volatile
    private var bindingSince = 0L

    /**
     * 确保 UserService 已绑定。可高频调用：service 为空且距上次尝试 >1.5s 时自动重绑
     * （手机重启/Shizuku 重启后 binder 可能丢失而进程残留，需主动重建连接）。
     *
     * **本方法可能在主线程被调用**（MainActivity 的启动探测），而 bindUserService 是
     * 同步 binder 调用——统一改投后台，主线程只做一次转手。
     */
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        addBinderListeners()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 不在主线程做 binder 调用：回调本身也走主 Looper，这里堵住等于自锁
            Bg.run("shizuku-bind") { ensureInternal(app) }
            return
        }
        ensureInternal(app)
    }

    private fun addBinderListeners() {
        if (binderListenerAdded) return
        binderListenerAdded = true
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListener {
                // Shizuku 服务端（重新）起来了：旧连接必然失效，允许重新绑定一次
                diag("Shizuku binder 已就绪——重新绑定 UserService", force = true)
                binding = false
                bindingSince = 0L
                lastBindAttemptAt = 0L
                appContext?.let { Bg.run("shizuku-bind") { ensureInternal(it) } }
            }
            rikka.shizuku.Shizuku.addBinderDeadListener {
                diag("Shizuku binder 断开（服务端退出/重启）——触摸注入暂不可用", force = true)
                service = null
                binding = false
                bindingSince = 0L
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureInternal(app: Context) {
        if (service != null) return
        val now = android.os.SystemClock.uptimeMillis()
        // 1.5s 节流：Shizuku 被系统冻结期间绑定会静默失败，解冻后需要尽快恢复
        if (now - lastBindAttemptAt < BIND_THROTTLE_MS) return
        // **只在「等得太久」时才重新发起**。旧实现固定 1.5s 就判卡死，且重绑前会
        // unbindUserService(remove=true)——那会把刚起来的服务进程**直接杀掉**：
        //   Shizuku 的 connected 回调 post 到主线程 → 主线程一忙回调就晚到 →
        //   1.5s 后被判「卡死」→ 杀进程 → 重新 spawn → 回调又晚到 → 再杀……
        // 这是自持抖动，永不收敛（2026-10-22 K60E 真机：注入失败 1700+ 次、
        // 每 2~3 秒刷一次「绑定卡死状态已解除」）。
        // 现在：等待窗口放宽到 8s，且**绝不在这里杀服务进程**（重绑是幂等的，
        // Shizuku 会把已在运行的服务 binder 再次回调过来）。
        if (binding) {
            val waited = if (bindingSince == 0L) 0L else now - bindingSince
            if (!shouldRebind(binding = true, waitedMs = waited, stuckTimeoutMs = BIND_STUCK_TIMEOUT_MS)) {
                // 仍在正常等待：不打日志、不打断
                return
            }
            binding = false
            diag(
                "绑定等待 ${waited}ms 仍未回调——重新发起（不杀服务进程）",
                force = true,
            )
        }
        lastBindAttemptAt = now
        val gate = ShizukuGate.state(app)
        if (gate != ShizukuGate.State.READY) {
            diag("绑定暂停：state=$gate")
            return
        }
        binding = true
        bindingSince = now
        try {
            val args =
                userServiceArgs ?: rikka.shizuku.Shizuku
                    .UserServiceArgs(
                        // 包名取自运行时（不要硬编码）：改名后这里会自动跟随
                        ComponentName(app.packageName, CarInputServiceImpl::class.java.name),
                    ).processNameSuffix("car_input")
                    .debuggable(true)
                    // 版本号取 APK lastUpdateTime（见 serviceVersion 注释）
                    .version(serviceVersion())
                    .also { userServiceArgs = it }
            // **绝不 unbindUserService(remove=true)**：那个参数会 kill 远端服务进程。
            // 旧实现在每次重试前都调用它 → 把刚 spawn 起来的进程杀掉 → 回调永远等不到
            // → 再重试再杀，形成自持抖动（2026-10-22 K60E 注入必然死锁的根因之一）。
            // bindUserService 本身幂等：服务已在运行时 Shizuku 会把现有 binder 再次
            // 回调到同一个 ServiceConnection（Set 去重），无需先解绑。
            rikka.shizuku.Shizuku.bindUserService(args, conn)
            diag("bindUserService requested（等待回调，超时 ${BIND_STUCK_TIMEOUT_MS}ms）")
        } catch (e: Throwable) {
            binding = false
            bindingSince = 0L
            diag("bindUserService failed: ${e.message}")
        }
    }

    /**
     * UserService 版本 = APK lastUpdateTime（秒）。Shizuku 按「组件+进程名+版本」复用
     * 常驻服务进程：硬编码版本号时重装 APK 不会换进程——复用的旧进程跑的还是旧 dex，
     * 新代码静默不生效（2026-10-15 实测：受信屏加 SUPPORTS_IME 后 flags 仍是旧值
     * 0x448 的根因；且 force-stop 杀不掉这些 shell 进程，会越积越多）。
     * lastUpdateTime 重装必变 → 必起新进程；APK 不变时恒定 → 正常复用常驻进程。
     */
    private fun serviceVersion(): Int =
        try {
            val pm = appContext?.packageManager ?: return 15

            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(appContext!!.packageName, 0)
            (info.lastUpdateTime / 1000L).toInt()
        } catch (_: Throwable) {
            15 // 兜底：与历史手写版本号序列衔接，保证不低于 14
        }

    private const val TAG = "CarInputClient"

    /** 两次绑定发起之间的最小间隔（Shizuku 被冻结时绑定会静默失败，解冻后需尽快恢复）。 */
    private const val BIND_THROTTLE_MS = 1_500L

    /**
     * 判定「本次绑定卡死」的等待上限。
     *
     * 必须远大于正常回调延迟：Shizuku 的 connected 回调 post 到主线程，而服务进程本身
     * 要 spawn + 加载 dex（调试包更慢）。旧值 1.5s 与节流窗等长，导致「发起 → 1.5s 后
     * 判死 → 杀进程重来」的自持抖动（2026-10-22 K60E 实测每 2~3 秒刷一次）。
     */
    private const val BIND_STUCK_TIMEOUT_MS = 8_000L
}

/**
 * 「这次绑定是否该判卡死并重发」的纯函数（[CarInputClient] 的绑定状态机核心）。
 *
 * 拆成纯函数是为了钉死一个真机定谳的回归（2026-10-22 Redmi K60E）：旧实现在
 * 「距上次发起 ≥1.5s」时就无条件判卡死，**而这个窗口与正常回调延迟同量级**——
 * Shizuku 的 connected 回调 post 到主线程，服务进程还要 spawn + 加载 dex，
 * 1.5s 常常不够。判死后旧代码还会 `unbindUserService(remove=true)` 把刚起来的
 * 服务进程杀掉，于是「发起 → 判死 → 杀 → 再发起」永不收敛，触摸注入从第一次
 * 触碰起就全灭（日志每 2~3 秒刷「绑定卡死状态已解除」）。
 *
 * @param binding 当前是否有进行中的绑定
 * @param waitedMs 该次绑定已等待多久（[binding] 为 false 时忽略）
 * @return true = 应重发；false = 继续等（或本就空闲可以发起）
 */
internal fun shouldRebind(
    binding: Boolean,
    waitedMs: Long,
    stuckTimeoutMs: Long,
): Boolean = !binding || waitedMs >= stuckTimeoutMs
