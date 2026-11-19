package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context
import android.os.Build
import io.github.shaonialife.cardeck.shizuku.Shell
import java.util.concurrent.Executors

/**
 * 「强制应用横屏」兼容覆盖的唯一管理口：
 *   - 用户偏好持久化在本应用 SharedPreferences（页面开关读写）；
 *   - 系统侧覆盖（am compat，持久）只在**会话期间**保持：
 *     会话建立时 [applyAllAsync] 逐应用写入，
 *     会话结束（断开/推流终止）时 [resetAllAsync] 统一复原——
 *     避免覆盖残留影响应用在手机侧的正常竖屏使用。
 *
 * **必须在连接关键路径之外执行**：每个应用两次 `am compat` 都是 shell 往返，
 * 名单 6 个应用实测 ~1.6-2.0s。此前复原在配对前同步跑、写入在虚拟屏 attach 里
 * 同步跑，直接把奥迪连接时间拉长约 3.5s（2026-10-18 实测）。两者用同一个单线程
 * 执行器串行，保证「先复原上一会话、再写入本会话」的顺序（跨线程并发会互相覆盖）。
 *
 * 生效组合（头条/抖音实测）：
 *   OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR + OVERRIDE_ANY_ORIENTATION
 */
object AppOrientationCompat {
    const val CHANGE_UNDEFINED_TO_NOSENSOR = 265451093
    const val CHANGE_ANY_ORIENTATION = 265464455

    /**
     * 支持强制横屏的最低 SDK（Android 14）。这两个 ChangeId 是 AOSP 标准项，
     * 自 Android 14 起定义在 `android.content.pm.ActivityInfo`
     * （`@ChangeId @Disabled @Overridable`），由 `LetterboxUiController` 消费；
     * Android 13 及更早完全没有 orientation 类 ChangeId。
     */
    const val MIN_SUPPORTED_SDK = 34
    private const val PREFS = "app_orientation"

    /** 串行执行器：复原与写入严格按调用顺序执行，且不占用连接/UI 线程。 */
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orientation-compat").apply { isDaemon = true }
        }

    /** 偏好键统一按「包名」存取（am compat 也以包名为对象；读取侧统一 removePrefix 兼容旧键）。 */
    private fun key(pkg: String) = "force_$pkg"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(
        context: Context,
        pkg: String,
    ): Boolean = prefs(context).getBoolean(key(pkg), false)

    /**
     * 系统侧写入结果。区分「永久不支持」与「临时失败」——两者对偏好的处置相反：
     * 永久不支持不该留下偏好（否则下次连接白跑、面板显示与系统状态脱节），
     * 临时失败应保留用户意愿（下次连接/重试仍有机会生效）。
     */
    enum class WriteResult {
        /** 两个 ChangeId 都写入成功。 */
        OK,

        /**
         * 本机 **平台不具备该能力**，重试永远不会成功。按 SDK 版本可直接判定：
         *
         * **这两个 ChangeId 是 AOSP 标准项，自 Android 14 (SDK 34) 起引入**
         * （`android.content.pm.ActivityInfo`，带 `@ChangeId @Disabled @Overridable`），
         * 在 `LetterboxUiController` 里生效；Android 16 起重构到
         * `AppCompatOrientationOverrides`。Android 13 及更早**一个 orientation 类
         * ChangeId 都没有**（实测 A13 的 ActivityInfo 里 0 个，A14 有 9 个）。
         *
         * 因此本机不支持的成因按可能性排序：
         * 1. **Android 13 及更早**（SDK < 34）：平台根本没定义 → `dumpsys
         *    platform_compat` 里无该 ChangeId 的 name，`am compat` 先警告
         *    `Change ... is not known yet`。实例：红米 K40（Android 13 / SDK 33）。
         * 2. **OEM 裁掉了这些 ChangeId**：理论上 A14+ 都有，但个别 ROM 可能移除；
         *    判据同上（dumpsys 无 name）。
         * 3. **user build + 非 debuggable 应用**：即使 ChangeId 已注册，
         *    `PlatformCompat` 也只允许改 debuggable 应用，输出
         *    `Cannot override a change on a non-debuggable app and user build.`
         *
         * 都**不是 Shizuku 的问题**（shell 与 UserService 同为 uid 2000，结果一致）。
         * 用户可操作的建议：升级到 Android 14+ 的机型。
         */
        UNSUPPORTED,

        /** 临时失败（Shizuku 未就绪/超时/命令异常）——可重试。 */
        FAILED,
    }

    /**
     * 用户开关的**异步包装**：UI 线程唯一入口。
     *
     * `setEnabled` 内部是 2 次 `Shell.exec`（各 4s 超时），最坏 8s——直接放在
     * 勾选框的点击回调里会远超 ANR 阈值（2026-10-18 同批修复）。这里改为投递到
     * 串行执行器，结果回主线程回调；**执行器串行**保证同一应用连续点击时
     * 写入/复原按点击顺序落地，不会互相覆盖。
     */
    fun setEnabledAsync(
        context: Context,
        pkg: String,
        enable: Boolean,
        onResult: (WriteResult) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { setEnabled(app, pkg, enable) }.getOrElse { WriteResult.FAILED }
            android.os
                .Handler(android.os.Looper.getMainLooper())
                .post { onResult(result) }
        }
    }

    /**
     * 用户开关：写系统覆盖（设置页用）。**成功后才落偏好**。
     *
     * **只能在后台线程调用**（内部含 2 次 shell 往返，最坏 8s）。UI 用 [setEnabledAsync]。
     *
     * 失败分类见 [WriteResult]：`UNSUPPORTED` 时偏好保持原样（用户意愿不落盘，
     * 因为落盘只会让下次连接再白跑一次 applyAll）；`FAILED` 保留用户意愿。
     */
    fun setEnabled(
        context: Context,
        pkg: String,
        enable: Boolean,
    ): WriteResult {
        val action = if (enable) "enable" else "disable"
        val first = Shell.exec("am compat $action $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
        val second =
            if (first.ok) {
                Shell.exec("am compat $action $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            } else {
                // 第一条就被拒时不再写第二条（同一限制，省一次无谓的 shell 往返）
                first
            }
        if (first.ok && second.ok) {
            prefs(context).edit().putBoolean(key(pkg), enable).apply()
            return WriteResult.OK
        }
        val unsupported = isPermanentRejection(first.output) || isPermanentRejection(second.output)
        if (!unsupported) {
            // 临时故障：保留用户意愿，下次连接 applyAll 会再试
            prefs(context).edit().putBoolean(key(pkg), enable).apply()
        }
        // 诊断要能区分两种永久性成因（ChangeId 未注册 / user build 非 debuggable）
        diagSink?.invoke(
            "ORIENTATION: compat $action " +
                (if (unsupported) "本机不支持" else "失败") +
                " pkg=$pkg change=$CHANGE_UNDEFINED_TO_NOSENSOR exit=${first.exitCode} out=${first.output.take(160)}" +
                if (unsupported) "（${describeUnsupported()}）" else "",
        )
        return if (unsupported) WriteResult.UNSUPPORTED else WriteResult.FAILED
    }

    /**
     * 本机**是否具备该能力**（平台事实）。
     *
     * 判定分两步，快慢结合：
     * 1. **SDK 版本门槛**（零成本）：这两个 ChangeId 是 AOSP 标准项，自
     *    **Android 14 / SDK 34** 起在 `android.content.pm.ActivityInfo` 中定义
     *    （`@ChangeId @Disabled @Overridable`，由 `LetterboxUiController` 消费；
     *    Android 16 起重构到 `AppCompatOrientationOverrides`）。
     *    Android 13 及更早的 ActivityInfo 里**一个 orientation 类 ChangeId 都没有**
     *    （实测 A13=0 个、A14=9 个）→ 直接判不支持，不必跑 shell。
     * 2. **dumpsys 复核**（SDK ≥ 34 时才跑）：确认该 ROM 真的注册了这两个 ID
     *    （个别 OEM 可能裁掉）。已注册条目带 `name=...`，未注册只有裸
     *    `ChangeId(...; rawOverrides={...})`。
     *
     * 结果进程内缓存（同一次安装内不会变）。
     */
    fun changeIdsRegistered(): Boolean {
        cachedRegistered?.let { return it }
        // 1) 版本门槛：Android 14 以下平台根本没有这些 ChangeId
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) {
            cachedRegistered = false
            return false
        }
        // 2) dumpsys 复核：两个 ID 分别取行校验——不能对整段输出查一次 `name=`
        //    （只注册了其中一个时会被误判成两个都可用，随后第二个写入才失败）。
        val first = changeIdLine(CHANGE_UNDEFINED_TO_NOSENSOR)
        val second = changeIdLine(CHANGE_ANY_ORIENTATION)
        val value = first.contains("name=") && second.contains("name=")
        // dumpsys 不可用（命令失败）时不缓存，留待下次重试
        if (first.isNotBlank() || second.isNotBlank()) cachedRegistered = value
        return value
    }

    /** 取某 ChangeId 在 `dumpsys platform_compat` 里的那一行（未注册时可能为空）。 */
    private fun changeIdLine(changeId: Int): String =
        Shell
            .exec(
                "dumpsys platform_compat | grep -F 'ChangeId($changeId;' | head -1",
                timeoutMs = 6_000L,
            ).output

    @Volatile
    private var cachedRegistered: Boolean? = null

    /** 永久失败的具体成因（写进日志，便于跨机型排查）。 */
    private fun describeUnsupported(): String =
        when {
            Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK ->
                "Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）低于 14" +
                    "——这两个 ChangeId 自 Android 14 起才进平台"

            !changeIdsRegistered() ->
                "SDK ${Build.VERSION.SDK_INT} 但该 ROM 未注册这两个 ChangeId（疑似 OEM 裁剪）"

            else ->
                "ChangeId 已注册，但 user build 不允许修改非 debuggable 应用"
        }

    /**
     * 该命令输出是否为「永久失败」（不可重试）。
     *
     * 覆盖两类措辞：ChangeId 未注册时 `am compat` 先警告 `is not known yet` 再拒绝；
     * 已注册但受 user build 限制时给出 `non-debuggable app and user build`。
     * 两者都与 Shizuku 无关，重试无效。
     */
    private fun isPermanentRejection(output: String): Boolean = classifyCompatFailure(output) == WriteResult.UNSUPPORTED

    /** 诊断日志出口（SessionController.appendLog 接到文件日志）。 */
    @Volatile
    var diagSink: ((String) -> Unit)? = null

    /** 异步版 [applyAll]：连接关键路径调用（log 在完成后回调，便于排查）。 */
    fun applyAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val count = runCatching { applyAll(app) }.getOrDefault(0)
            if (count > 0) log("ORIENTATION: 已为 $count 个应用启用强制横屏（会话期间生效）")
        }
    }

    /** 异步版 [resetAll]：会话退役时调用，不阻塞后续配对。 */
    fun resetAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val count = runCatching { resetAll(app) }.getOrDefault(0)
            if (count > 0) log("ORIENTATION: 已复原 $count 个应用的显示设置")
        }
    }

    /**
     * 等排队中的覆盖写入/复原执行完（最多 [timeoutMs]）。
     *
     * **应用启动前必须调用**：`am compat enable` 会立即杀掉目标应用。覆盖写入改成
     * 异步后，用户在写入完成前点桌面图标，就会出现「应用刚起来就被覆盖写入杀掉」。
     * 执行器是单线程 FIFO，投一个空任务并等它完成即等价于 drain。
     *
     * @return 是否在时限内排空；false = 超时（调用方必须留痕——静默失效会让
     * 「先写覆盖再拉应用」的保证悄悄失效，应用照样被杀，2026-10-23 审阅定谳）。
     */
    fun awaitPending(timeoutMs: Long = 6_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute { done.countDown() }
        return try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 会话建立：名单内应用逐个写入覆盖（幂等）。返回**成功**写入的应用数。 */
    fun applyAll(context: Context): Int {
        val pkgs = enabledPkgs(context)
        if (pkgs.isEmpty()) return 0
        // 先做一次平台能力探测：ROM 未注册这两个 ChangeId 时（红米 Android 13 实测），
        // 名单里每个应用都要白跑两次 4s 超时的 shell 往返。一次 dumpsys 就能判定，
        // 成本约 30ms、结果进程内缓存。
        if (!changeIdsRegistered()) {
            diagSink?.invoke(
                "ORIENTATION: 本机 ROM 未注册横屏兼容 ChangeId——跳过本次全部覆盖" +
                    "（名单 ${pkgs.size} 个应用；该机型不支持强制横屏）",
            )
            return 0
        }
        // 按需写入（2026-11-12）：系统侧已开着覆盖的应用**跳过**——am compat enable
        // 对已生效的覆盖同样会杀掉目标应用进程；保留重连轮次上名单应用正带着覆盖
        // 运行（保留路径刻意不复原），逐个重写=杀掉要保状态的应用（复用后地图/
        // 固定应用卡黑屏的根因）。一次 systemEnabled() 查询（~30ms dumpsys）即可判定。
        // 半残状态（只开其一，写入失败的罕见残留）同样跳过：与面板「取或」清理语义
        // 一致，避免为罕见残留杀一次运行中的应用；可在横屏面板开关一次补齐。
        val alreadyEnabled = systemEnabled()
        val todo = pkgs.filter { it !in alreadyEnabled }
        if (todo.size < pkgs.size) {
            diagSink?.invoke(
                "ORIENTATION: ${pkgs.size - todo.size}/${pkgs.size} 个应用覆盖已生效——跳过写入" +
                    "（不杀运行中应用）${if (todo.isEmpty()) "，本次无需写入" else ""}",
            )
        }
        if (todo.isEmpty()) return 0
        var count = 0
        for (pkg in todo) {
            val first = Shell.exec("am compat enable $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            if (!first.ok) {
                // ChangeId 已注册仍被拒 → user build 不允许改非 debuggable 应用；
                // 同样整体停止，不逐应用空跑。
                if (isPermanentRejection(first.output)) {
                    diagSink?.invoke(
                        "ORIENTATION: 系统限制——user 版本只允许修改可调试(debuggable)应用的" +
                            "兼容设置，跳过本次全部横屏覆盖（名单 ${pkgs.size} 个应用）",
                    )
                    return 0
                }
                continue
            }
            val second = Shell.exec("am compat enable $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            if (second.ok) count++
        }
        return count
    }

    /**
     * 会话结束：名单内应用统一复原（偏好保留，仅撤销系统覆盖）。返回实际撤销的应用数。
     *
     * 「断开后保留活动应用横屏」（DisplayPrefs.keepOrientationForAliveApps，默认开）：
     * 仍在运行的应用**跳过撤销**——`am compat disable` 会立即杀进程，正在导航的应用
     * 会被打断、页面状态全丢（2026-11-19 用户实测痛点）。活着的不动：下轮 applyAll
     * 走 systemEnabled() 过滤天然幂等；面板里显式关掉该应用横屏时仍会立即写入（那是
     * 用户明确意图，杀进程代价照旧告知）。已退出的应用照常复原（无进程可杀，顺手清
     * 系统侧，避免卸载残留）。
     */
    fun resetAll(context: Context): Int {
        var count = 0
        var skippedAlive = 0
        val keepAlive = DisplayPrefs.keepOrientationForAliveApps(context)
        enabledPkgs(context).forEach { pkg ->
            if (keepAlive && isProcessAlive(pkg)) {
                skippedAlive++
                return@forEach
            }
            Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            count++
        }
        if (skippedAlive > 0) {
            diagSink?.invoke(
                "ORIENTATION: $skippedAlive 个应用仍在运行——跳过横屏复原（保留覆盖，避免杀进程打断使用）",
            )
        }
        return count
    }

    /** 进程是否存活（`pidof`：exit 0 且输出非空 = 活着；单次 ~几十 ms，名单很小逐个查）。 */
    private fun isProcessAlive(pkg: String): Boolean {
        val out = Shell.exec("pidof $pkg", timeoutMs = 2000)
        return out.ok && out.output.isNotBlank()
    }

    /**
     * 系统侧当前真正开着覆盖的「覆盖键」集合（事实源；键可能是包名，也可能是
     * 历史脏数据里的 `包/组件` 串）。
     *
     * 偏好文件读不到「系统实际状态」：**卸载会删掉偏好，系统侧 am compat 覆盖却会留下**
     * （2026-10-16 实测：设备上有飞书/大众点评/荣耀云音乐三个残留）。查询只能走
     * `dumpsys platform_compat`（该 ROM 无 `cmd platform_compat`），实测全量 30ms/36KB，
     * 过滤到关心的两个 ChangeId 后约 3KB——**面板打开时查一次**，行绑定只做内存查表，
     * 绝不可每行一次（40+ 应用 × ~100ms = 卡顿）。
     *
     * **必须同时解析 packageOverrides 与 rawOverrides**：已卸载应用的覆盖只存在于
     * rawOverrides——packageOverrides 是平台按「已安装包」解析后的映射，包不存在时条目
     * 消失（2026-10-16 实测：给未安装包写的覆盖只在 rawOverrides 可见）。「其它系统
     * 残留」汇总正是为卸载残留设计的，漏读会让横幅永远空着。
     *
     * 两个 ChangeId 取或：只开其一的半残状态同样视为「已开启」（清理时会一并写两个）。
     */
    fun systemEnabled(): Set<String> {
        val out =
            Shell
                .exec(
                    "dumpsys platform_compat | grep -E 'ChangeId\\(${CHANGE_UNDEFINED_TO_NOSENSOR};|ChangeId\\(${CHANGE_ANY_ORIENTATION};'",
                    timeoutMs = 6_000L,
                ).output
        if (out.isBlank()) return emptySet()
        return out
            .lineSequence()
            .flatMap { line ->
                // packageOverrides={com.a=true, ...}；rawOverrides 里还可能有 包/组件 键
                Regex("(?:packageOverrides|rawOverrides)=\\{([^}]*)\\}")
                    .findAll(line)
                    .flatMap { m ->
                        // 键容许多数可打印字符（含 / 的组件键）；只取 =true
                        Regex("([^=,{}\\s]+)=true").findAll(m.groupValues[1]).map { it.groupValues[1] }
                    }
            }.toSet()
    }

    /**
     * 指定包的系统侧覆盖是否真的开着（letterbox 提示的触发前置条件）。
     *
     * 光看偏好不够：`am compat` 临时失败时偏好会保留（用户意愿），但系统侧没有覆盖，
     * 此时提示「强制重启」注定无效。探测只在候选应用上跑一次，成本≈一次 grep。
     */
    fun isSystemEnabledFor(pkg: String): Boolean = pkg in systemEnabled()

    /**
     * 清除单个「覆盖键」的系统侧覆盖（两个 ChangeId 都写 disable）。
     * 仅动系统覆盖、**不动偏好**——用于清理「偏好已不在（卸载残留）但覆盖还开着」的条目；
     * 名单内的应用请用 [setEnabled]（会同步偏好，避免 UI 与系统状态脱节）。
     * 注意：am compat disable 会立即杀掉目标应用进程（新设置需重启应用生效）。
     */
    fun clearSystemOnly(key: String): Boolean {
        val ok1 = Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $key", timeoutMs = 4000).ok
        val ok2 = Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $key", timeoutMs = 4000).ok
        return ok1 && ok2
    }

    private fun enabledPkgs(context: Context): List<String> =
        prefs(context)
            .all
            .filterValues { it == true }
            .keys
            .map { it.removePrefix("force_") }

    /** 「应用横屏」名单（包名集合）：letterbox 提示探测用，只读。 */
    fun enabledPackages(context: Context): Set<String> = enabledPkgs(context).toSet()
}

/**
 * 把 `am compat` 的失败输出分类（纯函数，可单测）。
 *
 * 判为**永久失败**的三种措辞（都与 Shizuku 无关，重试无效）：
 *  1. `Cannot override a change on a non-debuggable app and user build.`
 *  2. `Cannot override a default enabled/disabled change on a user build.`
 *  3. `Change <id> is not known yet.` —— 本机 ROM 未注册该 ChangeId
 *     （这两个 ID 是 OEM 定制，AOSP 标准表里没有；红米 Android 13 实测缺失）
 *
 * 实测输出（2026-10-18）：
 *  - 红米（未注册）：
 *      `Warning! Change 265451093 is not known yet. Enabling/disabling it could have no effect.`
 *      `Cannot override a change on a non-debuggable app and user build.`
 *  - 荣耀（已注册，但目标是非 debuggable 应用时的同类拒绝）：同上第二条措辞
 *  - Shizuku 未连接：`Shizuku 未连接`（临时，可重试）
 */
internal fun classifyCompatFailure(output: String): AppOrientationCompat.WriteResult =
    if (output.contains("non-debuggable app and user build") ||
        output.contains("default enabled/disabled change on a user build") ||
        output.contains("is not known yet")
    ) {
        AppOrientationCompat.WriteResult.UNSUPPORTED
    } else {
        AppOrientationCompat.WriteResult.FAILED
    }
