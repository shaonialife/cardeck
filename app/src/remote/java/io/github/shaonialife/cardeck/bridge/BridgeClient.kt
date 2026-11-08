package io.github.shaonialife.cardeck.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.BuildConfig
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * 远程调试桥客户端（remote 变体专属）。
 *
 * 职责：与调试服务器保持一条 WSS/WS 长连接——配对、日志（批量回补 + 实时流）、
 * 命令白名单、提示下发、新版本推送。会话与业务代码的耦合点只有两处：
 * [DebugFileLog.tap]（读日志）与 [BridgeCommands]（执行命令，走既有公开 API）。
 *
 * 线程：自持一根「bridge-client」连接线程（长驻循环线程不进 Bg 池的项目约定）；
 * WS 收到的消息在连接线程解析后按需切主线程（悬浮窗）或 Bg（命令）。
 */
object BridgeClient {
    /** 连接状态（设置页展示；主线程读）。 */
    @Volatile
    var stateText: String = "未连接"
        private set

    /** 下次自动重连的时刻（elapsedRealtime；0 = 无排定）。倒计时显示用。 */
    @Volatile
    var nextRetryAtElapsed: Long = 0
        private set

    /** 最近一次断连/失败原因（状态行倒计时附带展示）。 */
    @Volatile
    private var lastDisconnectReason: String? = null

    /** 本进程已安装 APK 的 sha256（差分基线，hello 上报；版本键控缓存避免每次重算） */
    @Volatile
    private var cachedBaseSha: String? = null

    /**
     * 设置页状态行：处于退避重连时附加「Ns 后自动重试」倒计时——断连对用户必须是
     * 可见且带明确预期的（2026-10-22 用户要求），不是停在一句「连接断开」。
     */
    fun statusLine(): String {
        val next = nextRetryAtElapsed
        if (next > 0) {
            val remain = ((next - android.os.SystemClock.elapsedRealtime()) / 1000 + 1).coerceAtLeast(0)
            val reason = lastDisconnectReason
            return "连接断开${if (reason != null) "：$reason" else ""}（${remain}s 后自动重试）"
        }
        return stateText
    }

    /**
     * 连接状态跃迁通知（true=连上；false=此前已连上的连接断开）。
     * App 层用它弹用户提示；回调在连接线程触发，实现方自行切主线程。
     */
    @Volatile
    var connectionEvents: ((connected: Boolean) -> Unit)? = null

    private var wasConnected = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    @Volatile
    private var ws: WsClient? = null

    /** 蜂窝互联网通道：车机会话期间进程网络被绑到车机热点，调试桥必须走它。 */
    @Volatile
    private var route: InternetRoute? = null

    /** 当前连接尝试走的是否默认网络（welcome 成功时用于复位路由失败判定）。 */
    @Volatile
    private var connUsedDefaultNetwork = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 环形日志缓冲（断线重连后按 seq 回补给服务端）。 */
    private val ringLock = Any()
    private val ring = ArrayDeque<Pair<Long, String>>()
    private const val RING_CAP = 5_000

    /** 待实时发送的日志（tap 线程入队，连接线程按批冲刷）。 */
    private val pendingLock = Any()
    private val pending = ArrayDeque<Pair<Long, String>>()

    private var tapInstalled = false

    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        installTap()
        if (running) {
            DebugFileLog.append(context, "BRIDGE: 重复 start 被忽略（已有连接循环）")
            return
        }
        running = true
        // 蜂窝通道必须先就位：首次连接就可能发生在车机会话里（进程网络已被绑走）
        route = InternetRoute(context.applicationContext).also { it.start() }
        Thread({ runLoop() }, "bridge-client").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        route?.stop()
        route = null
        ws?.close(1000, "客户端停止")
        setState("已断开")
    }

    /** 蜂窝互联网通道（供 APK 下载等复用）：车机会话期间进程默认网络被绑到
     *  车机热点（无互联网），不走它的一切联网操作都会失败。 */
    fun internetNetwork(): android.net.Network? = route?.current()

    private fun setState(text: String) {
        stateText = text
        DebugFileLog.tap?.let {} // no-op：保持对象引用稳定（状态不进文件日志，避免回声）
    }

    private fun installTap() {
        if (tapInstalled) return
        tapInstalled = true
        DebugFileLog.tap = { seq, line ->
            synchronized(ringLock) {
                ring.addLast(seq to line)
                while (ring.size > RING_CAP) ring.removeFirst()
            }
            synchronized(pendingLock) { pending.addLast(seq to line) }
        }
    }

    private fun runLoop() {
        var backoffMs = 2_000L
        var attempt = 0
        while (running) {
            val ctx = appContext ?: return
            val url = BridgePrefs.serverUrl(ctx)
            if (url.isBlank()) {
                setState("未配置服务器地址")
                sleepQuiet(3_000)
                continue
            }
            // 路由选择必须进日志流：车机会话期间进程网络被绑到热点上，连接走蜂窝
            // 还是默认、失败原因是什么——这些不看日志就等于盲调（2026-10-22 实测：
            // WS 入热点即断、4 分钟后才自愈，期间日志流停更却无任何痕迹可查）
            val network = route?.current()
            attempt++
            val client =
                WsClient(
                    url,
                    object : WsClient.Listener {
                        override fun onOpen() {
                            setState("已连接，配对中…")
                            // 协议由设备侧驱动：先发 hello（含配对码），服务端校验后才回
                            // welcome——若等 welcome 再发 hello 会与服务端互等死锁，
                            // 状态永远停在「配对中…」（首台真机实测踩到）
                            sendHello()
                        }

                        override fun onText(text: String) = onServerMessage(text)

                        override fun onClosed(reason: String) {
                            setState("连接断开：$reason")
                            lastDisconnectReason = reason.take(40)
                            debugLog("连接断开：$reason")
                        }
                    },
                    network,
                )
            ws = client
            setState("连接中…")
            nextRetryAtElapsed = 0
            connUsedDefaultNetwork = network == null
            debugLog(
                "连接尝试 #$attempt 路由=${if (network != null) "蜂窝" else "默认网络"} " +
                    "退避=${backoffMs}ms（backoff 重置说明上一轮曾成功）",
            )
            try {
                client.connect()
                backoffMs = 2_000L
            } catch (interrupted: InterruptedException) {
                return
            } catch (error: Exception) {
                // 连接失败/断开：退避重连（失败原因写日志流——重试风暴是否在发生
                // 必须在流内可见，否则远程侧只看到「日志停更」猜不出原因）。
                // 默认网络失败要上报路由器：假 VALIDATED（缓存判定）下每轮都撞
                // 同一条死路，noteFailure 后下一轮切蜂窝。
                debugLog("连接失败（${error.message ?: error.javaClass.simpleName}）")
                lastDisconnectReason = (error.message ?: error.javaClass.simpleName).take(40)
                route?.noteFailure(connUsedDefaultNetwork)
            }
            ws = null
            // 已连上过的连接走到这里 = 断连；告知 App 层弹用户提示（倒计时在状态行）
            if (wasConnected) {
                wasConnected = false
                runCatching { connectionEvents?.invoke(false) }
            }
            if (!running) break
            nextRetryAtElapsed = android.os.SystemClock.elapsedRealtime() + backoffMs
            sleepQuiet(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** 写进主日志流（与业务日志同管道）：桥自身的连接事件对远程排障可见。 */
    private fun debugLog(message: String) {
        appContext?.let { DebugFileLog.append(it, "BRIDGE: $message") }
    }

    private fun sleepQuiet(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- 连接建立后的握手与消息 ----

    private fun onServerMessage(text: String) {
        val msg =
            runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "welcome" -> {
                if (msg.optBoolean("ok")) {
                    setState("已连接（${msg.optLong("pairedAt")}）")
                    nextRetryAtElapsed = 0
                    debugLog("已连上调试服务器（配对通过），日志流恢复")
                    // 本轮连接走默认网络且真通了：清除「假 VALIDATED」失败判定
                    route?.noteSuccess(connUsedDefaultNetwork)
                    if (!wasConnected) {
                        wasConnected = true
                        runCatching { connectionEvents?.invoke(true) }
                    }
                    // 全量回补：服务端按 seq 去重并留环形缓冲
                    sendLogBatch(since = 0)
                } else {
                    setState("配对被拒：${msg.optString("error")}")
                    ws?.close(1008, "配对被拒")
                }
            }

            "log.sync" -> sendLogBatch(msg.optLong("since"))

            "cmd" -> {
                val id = msg.optString("id")
                val name = msg.optString("name")
                val args = msg.optJSONObject("args") ?: JSONObject()
                Bg.run("bridge-cmd") {
                    val result = BridgeCommands.handle(appContext, name, args)
                    send(
                        JSONObject()
                            .put("t", "cmd.result")
                            .put("id", id)
                            .put("ok", result.optBoolean("ok"))
                            .put("detail", result.optString("detail")),
                    )
                }
            }

            "notify" -> {
                val message = msg.optString("text")
                mainHandler.post { BridgeOverlay.show(appContext, message, dismissMillis = 8_000) }
            }

            "apk.offer" -> {
                val delta = msg.optJSONObject("delta")
                ReinstallFlow.offer(
                    appContext,
                    msg.optString("url"),
                    msg.optString("sha256"),
                    msg.optString("version"),
                    deltaUrl = delta?.optString("url").takeIf { !it.isNullOrBlank() },
                    deltaSha256 = delta?.optString("sha256").takeIf { !it.isNullOrBlank() },
                    baseSha256 = delta?.optString("baseSha256").takeIf { !it.isNullOrBlank() },
                    deltaSizeKB = delta?.optInt("sizeKB", -1)?.takeIf { it > 0 },
                )
            }

            "ping" -> send(JSONObject().put("t", "pong").put("id", msg.optString("id")))

            // 文件日志上传（差分 ring 之外的历史事实源）：读 main_debug.log 尾部
            // → gzip → 单消息 base64 回传。只读自身日志文件，无副作用。
            "log_file.req" -> {
                val reqId = msg.optString("id")
                val maxKB = msg.optInt("maxKB", 512).coerceIn(16, 4096)
                Bg.run("bridge-log-file") {
                    val ctx = appContext ?: return@run
                    val raw =
                        io.github.shaonialife.cardeck.util.DebugFileLog
                            .readTail(ctx, maxKB * 1024)
                            ?: ByteArray(0)
                    val gz =
                        java.io.ByteArrayOutputStream().use { bos ->
                            java.util.zip
                                .GZIPOutputStream(bos)
                                .use { it.write(raw) }
                            bos.toByteArray()
                        }
                    val payload =
                        JSONObject()
                            .put("t", "log_file.data")
                            .put("id", reqId)
                            .put("rawSize", raw.size)
                            .put("truncated", raw.size >= maxKB * 1024)
                            .put("gzB64", android.util.Base64.encodeToString(gz, android.util.Base64.NO_WRAP))
                    send(payload)
                    debugLog("文件日志已回传（原始 ${raw.size / 1024}KB → gzip ${gz.size / 1024}KB）")
                }
            }
        }
    }

    /**
     * 已安装 APK 的 sha256（差分基线）。15MB 哈希 ~50ms，只在进程首个 hello 时算一次
     * （结果按版本号落 prefs，重连直接读缓存）；失败返回 null（服务器只走全量）。
     */
    private fun baseSha256(ctx: Context): String? =
        runCatching {
            BridgePrefs.baseApkSha(ctx, BuildConfig.VERSION_NAME)?.let { return it }
            val source = File(ctx.applicationInfo.sourceDir)
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            BridgePrefs.setBaseApkSha(ctx, BuildConfig.VERSION_NAME, sha)
            sha
        }.getOrNull()

    private fun sendHello() {
        val ctx = appContext ?: return
        val hello =
            JSONObject()
                .put("t", "hello")
                .put("deviceId", BridgePrefs.deviceId(ctx))
                .put("deviceModel", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("code", BridgePrefs.pairCode(ctx))
                .put("lastSeq", DebugFileLog.lastSeq)
                .put("baseSha256", cachedBaseSha ?: baseSha256(ctx)?.also { cachedBaseSha = it } ?: "")
        send(hello)
    }

    /** 回补环形缓冲中 seq > since 的日志（since=0 全量）。 */
    private fun sendLogBatch(since: Long) {
        val snapshot: List<Pair<Long, String>> =
            synchronized(ringLock) {
                ring.filter { it.first > since }
            }
        if (snapshot.isEmpty()) {
            send(JSONObject().put("t", "log.batch").put("lines", JSONArray()))
            return
        }
        // 分片发送：单条 JSON 控制在 ~256KB 内（服务端 ws 消息上限友好）
        snapshot.chunked(200).forEach { chunk ->
            val lines = JSONArray()
            chunk.forEach { (seq, line) ->
                lines.put(JSONObject().put("seq", seq).put("text", line))
            }
            send(JSONObject().put("t", "log.batch").put("lines", lines))
        }
    }

    /** 冲刷实时日志队列（连接线程周期调用）。 */
    fun flushPendingLogs() {
        val batch =
            synchronized(pendingLock) {
                if (pending.isEmpty()) return
                val taken = ArrayList<Pair<Long, String>>(pending.size)
                while (pending.isNotEmpty()) taken.add(pending.removeFirst())
                taken
            }
        val lines = JSONArray()
        batch.forEach { (seq, line) -> lines.put(JSONObject().put("seq", seq).put("text", line)) }
        send(JSONObject().put("t", "log.batch").put("lines", lines))
    }

    fun send(json: JSONObject) {
        ws?.let { client ->
            if (!client.sendText(json.toString())) {
                synchronized(pendingLock) {} // 发送失败由重连补发（环形缓冲回补路径）
            }
        }
    }

    /** 供设置页手动触发一次状态广播。 */
    fun requestStatus() {
        send(JSONObject().put("t", "status").put("state", stateText))
    }

    init {
        // 实时日志冲刷节拍：独立 daemon 线程，500ms 一批
        Thread(
            {
                while (true) {
                    sleepQuiet(500)
                    if (running) flushPendingLogs()
                }
            },
            "bridge-log-flush",
        ).apply {
            isDaemon = true
            start()
        }
    }
}
