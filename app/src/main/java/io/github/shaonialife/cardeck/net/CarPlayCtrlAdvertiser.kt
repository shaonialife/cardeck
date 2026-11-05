package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import io.github.shaonialife.cardeck.protocol.NativeBridge
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DeviceIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * 广播手机侧（iPhone 角色）的 _carplay-ctrl._tcp 控制服务并应答接收端探测。
 *
 * 无线 CarPlay 中接收端发现该服务后会反连此端口，先发
 * `GET /ctrl-int/1/connect`（携带 AirPlay-Receiver-Device-ID）探测手机是否在线，
 * 期待 HTTP 200（对照 xcertplay CarPlayBonjour.probeOnce：只读状态行）。
 * 探测通过后，iAP2 控制会话经 AirPlay 事件通道的 type-130 隧道接管（Phase 2 后续）。
 *
 * 本类同时把每个连接的请求行打进日志——DiPlay 后续发什么会直接可见。
 */
class CarPlayCtrlAdvertiser(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /**
     * 车机 ctrl 邀请（GET /ctrl-int/1/connect）回调——参数 = 邀请源 IP
     * （车机热点网段地址）。
     *
     * **固件锚（2026-11-05 直切 APTransport 域补课）**：固件手机侧真实运行
     * ctrl-int 服务器——`_APCarPlayControlServerCreate` @0x243d75528 建
     * "com.apple.carplaycontrolserver" tcppackage（= 本类 CTRL 监听的固件
     * 真身）；`__APCarPlayControlServerHandleMessage` @0x243da8714 处理
     * GET /ctrl-int/1/connect：路径段校验 → 回 AirPlay-Receiver-Device-ID
     * 头 → connect 命令体经连接槽（+0x58）分发 → 200/400；`___carSession
     * Browser_handleCarPlayHelperEvent_block_invoke` @0x243d61674 侧手机
     * 发现车机后也经 `_APCarPlayControlServerInjectCommand` @0x243d75f20
     * 注入同一 "connect" 命令——**connect = 连接会话的固件启动命令**，
     * 车机邀请/手机自注入双方向汇入。实车形态差：MMI 3315 邀请后下发
     * 0x4301（2026-10-19 锚），MMI 6318 只邀请不发 0x4301（2026-11-05
     * 实车日志：邀请 ×3、0x4301 零命中）——两形态同一命令源，无车型分支。
     * 宿主落法 = 回调交 SessionController 合成端点走 0x4301 同一交接点
     * （onCarPlayEndpoint）；RTSP 连接序本体 = fw::airplay::flow（固件
     * Stage2StartTransaction 锚）。
     */
    var onCarInvite: ((peerIp: String) -> Unit)? = null

    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"

        /**
         * 发送端在 ctrl TXT 里自报的版本。
         *
         * **必须保持 950.7.1**：2026-10-19 奥迪 MMI 3315 实车对照定谳——改成 catplay 的
         * 566.25.21（发送端 SDK 版本）后，车机**连 ctrl-int 探测都不再发**、0x4301 永不到达；
         * 用 950.7.1 时同一台车 0.65 秒内就下发 0x4301。车机在 WiFi 上只看得到这个 TXT，
         * 它显然是按「接收端/系统广播常见版本」来判断对方形态的。
         */
        private const val SENDER_SRCVERS = "950.7.1"

        /**
         * ctrl 服务实例名。同理保持 0.1.6 的 `sender`：改名为 `iPhone` 与上一条同时生效，
         * 无法从单次日志区分归因，故一并回退到已验证可用的组合（2026-10-19）。
         */
        private const val SERVICE_NAME = "sender"

        /** 测试可见：这两个值经真车验证，改动需先复测（见 CarPlayCtrlAdvertiserTest）。 */
        internal fun advertisedSrcversForTest(): String = SENDER_SRCVERS

        internal fun advertisedServiceNameForTest(): String = SERVICE_NAME

        /** 测试可见：本类实际广播的服务类型全集（目前只有 ctrl；_device-info 已移除）。 */
        internal fun advertisedServiceTypesForTest(): List<String> = listOf(SERVICE_TYPE)

        private const val TAG = "CTRL"
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** 同一 SSID 的强制重注册冷却（见 ensureForNetwork）。 */
        private const val FORCE_COOLDOWN_MILLIS = 2_000L
    }

    private var serverSocket: ServerSocket? = null
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** accept 线程是否继续（只有 [stop] 清零；重注册不碰它，端口与线程都保持）。 */
    @Volatile
    private var acceptRunning = false

    @Volatile
    private var registered = false

    /**
     * 车机 ctrl 邀请计数（每次 `GET /ctrl-int/1/connect` 加一）。
     *
     * catplay 发送端的连接触发点就是这条邀请：它代表「车机已发现我们、并打开了
     * AirPlay 窗口」，收到后立即连 AirPlay，不做固定 sleep。主控据此替代
     * 原先「交接端点后睡 1.6s」的时序猜测。
     */
    @Volatile
    var inviteCount: Int = 0
        private set

    /** 新一轮连接开始时清零（避免把上一轮的邀请当成本轮信号）。 */
    fun resetInviteCount() {
        inviteCount = 0
    }

    /** 注册时的 WiFi SSID：换网（2.4G→5G 或车机热点切换）后旧注册不可见，需重新注册。 */
    private var registeredSsid: String? = null

    /** 当前生效的注册监听器（每次 start 换新实例，避免 already-in-use）。 */
    private var activeListener: NsdManager.RegistrationListener? = null

    /** 本次注册时刻（force 冷却判定用）。 */
    @Volatile
    private var registeredAtMillis: Long = 0L

    /** 本次注册写入 TXT 的 MAC（占位值 = 车机不会邀请，需尽快重注册）。 */
    @Volatile
    private var registeredWithMac: String? = null

    /** 本次注册时进程所处的网络（用于判断「绑定后是否需要重注册」）。 */
    private var registeredNetwork: android.net.Network? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    /** 当前广播的 TXT id 是否是占位 MAC（车机据此匹配手机，占位则永不邀请）。 */
    val macPlaceholder: Boolean
        get() = registeredWithMac == DeviceIdentity.PLACEHOLDER_MAC

    /**
     * 确保广播在指定 SSID 的网络上可见（每次会话拿到 0x5703 时调用）。
     * 真车实测（2026-10-11）：手机从 2.4G 换到 5G 热点后，旧 mDNS 注册
     * 不再被车机看到（车机不再发 ctrl-int 探测 → 不开 AirPlay 窗口）。
     * SSID 变化时先注销再重新注册，让广播落到当前网络。
     *
     * @param force true = 无条件重新注册（用于「绑定进程网络后」的时机修正：
     *   mDNS 组播跟随 bindProcessToNetwork 的网络，注册必须发生在绑定之后）。
     * @param network 绑定的进程网络；与上次注册时的网络不同则必须重注册（不受冷却限制）——
     *   否则「刚注册完就切到车机热点」时那 2s 冷却会把关键的绑定后重注册吞掉。
     */
    @Synchronized
    fun ensureForNetwork(
        ssid: String,
        force: Boolean = false,
        network: android.net.Network? = null,
    ): Boolean {
        if (registered && registeredSsid == ssid && !force && !macPlaceholder) return true
        // force 冷却：绑定后的一连串「确保」调用（bindCarWifiAndRegister → 端点交接）
        // 常在同一秒内发生，逐次 unregister+register 会撞 NsdManager 的
        // listener-already-in-use 并吃 1.2s 重试，反而拖慢连接。
        // 例外：广播里的 MAC 是占位值、或进程网络已变都不能放过。
        val sameNetwork = network == null || network == registeredNetwork
        if (force &&
            registered &&
            registeredSsid == ssid &&
            !macPlaceholder &&
            sameNetwork &&
            SystemClock.uptimeMillis() - registeredAtMillis < FORCE_COOLDOWN_MILLIS
        ) {
            log("$TAG: 跳过重复的强制重注册（${SystemClock.uptimeMillis() - registeredAtMillis}ms 前刚注册）")
            return true
        }
        if (registered) {
            log("$TAG: 重新注册 mDNS 广播（${registeredSsid ?: "?"}${if (force) "，绑定后时机修正" else " → $ssid"}）")
            // 只注销服务、**保留监听端口**：Android NSD 没有 catplay/JmDNS 的
            // reannounce()，重发公告只能注销+重注册；若同时换端口，车机按缓存端口
            // 探测就会连到已关闭的 socket（xcertplay 车机侧每会话 7 次 ×1.5s 探测，
            // 中途换端口整轮作废）。2026-10-22 奥迪日志里端口在 6 次重注册中一路跳
            // （43857→33383→33125→38151→40697→38015），每次都有最长 1.2s 空窗。
            unregisterServiceOnly()
        }
        registeredSsid = ssid
        registeredNetwork = network ?: registeredNetwork
        var ok = start()
        if (!ok) {
            // 注销是异步的：极端情况下 registerService 会遇到 "listener already in use"，
            // 稍候重试一次（真车实测 10-11：300ms 不够，需要更长）
            try {
                Thread.sleep(1_200)
            } catch (_: InterruptedException) {
            }
            ok = start()
        }
        return ok
    }

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        return try {
            // 端口在整个 advertiser 生命周期内固定：重注册只换 mDNS 注册、复用同一个
            // socket 与 accept 线程（见 ensureForNetwork 的注释）。
            val socket =
                serverSocket ?: ServerSocket(0).also { created ->
                    serverSocket = created
                    acceptRunning = true
                    Thread(::acceptLoop, "ctrl-accept").apply {
                        isDaemon = true
                        start()
                    }
                }
            // 占位 MAC 时强制重试一次（启动连接即发布广播时，Shizuku 可能还没就绪；
            // 广播内容错了车机就永远不会邀请我们）
            var mac = DeviceIdentity.bluetoothMac(context)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
                log("$TAG: 蓝牙 MAC 解析为占位值，已强制重试${if (mac == DeviceIdentity.PLACEHOLDER_MAC) "（仍未成功——车机可能不会邀请，请确认 Shizuku 已启动）" else "成功"}")
            }
            val info =
                NsdServiceInfo().apply {
                    serviceName = SERVICE_NAME
                    serviceType = SERVICE_TYPE
                    setPort(socket.localPort)
                    // TXT id = 本机真实蓝牙 MAC（catplay CarPlayInviter 实证：车机按此
                    // 匹配待邀请的手机，占位值会导致车机永远不邀请 → 0x4301 永不到达）。
                    setAttribute("id", mac)
                    setAttribute("srcvers", SENDER_SRCVERS)
                }
            // 每次注册用全新 listener：unregisterService 是异步的，复用同一个 listener
            // 会撞上 "listener already in use"（真车实测 10-11 卡住 ctrl 广播）
            val freshListener = createListener()
            activeListener = freshListener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, freshListener)
            registered = true
            registeredAtMillis = SystemClock.uptimeMillis()
            registeredWithMac = mac
            // 车机在 WiFi 上唯一可见的手机身份就是这几个 TXT 字段：占位 MAC / 版本不符都会
            // 直接导致「不发 ctrl 探测、0x4301 永不到达」，而此前日志完全看不到它们
            // （2026-10-19 奥迪 0.1.7 排查时无法归因），故每次注册都打一行。
            log(
                "$TAG: 已监听 :${socket.localPort}，广播 $SERVICE_TYPE " +
                    "name=$SERVICE_NAME id=$mac srcvers=$SENDER_SRCVERS" +
                    if (mac == DeviceIdentity.PLACEHOLDER_MAC) "（⚠ 占位 MAC：车机不会邀请）" else "",
            )
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                // 占位 id 车机不会邀请：后台等 Shizuku/MAC 就绪后立刻重注册，
                // 别让「广播提前到启动连接」反而把 TXT id 写错（2026-10-18）。
                Bg.run("ctrl-mac-repair") { repairPlaceholderMac() }
            }
            true
        } catch (error: Exception) {
            log("$TAG: 启动失败 - ${error.message}")
            false
        }
    }

    /** 占位 MAC 的自我修复：每 2s 重试解析，成功后按当前 SSID 重注册一次。 */
    private fun repairPlaceholderMac() {
        repeat(15) {
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return
            }
            if (!registered || !macPlaceholder) return
            val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) return@repeat
            log("$TAG: 蓝牙 MAC 已解析为 $mac——重新注册 ctrl 广播（修复占位 id）")
            ensureForNetwork(registeredSsid ?: "", force = true)
            return
        }
        log("$TAG: 30s 内未能解析真实蓝牙 MAC——ctrl 广播 id 仍是占位值，车机可能不会邀请（请确认 Shizuku 已启动）")
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (acceptRunning) {
            val client =
                try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
            Bg.run("ctrl-client") { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readUntilHeaderEnd(input)
            if (head.isEmpty()) {
                log("$TAG: $peer 连入但无请求（EOF）")
                return
            }
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val headers =
                head
                    .substringAfter("\r\n", "")
                    .lineSequence()
                    .filter { it.contains(':') }
                    .joinToString("; ") { it.trim() }
            log("$TAG: $peer -> [$requestLine] $headers")

            // 分类 + 应答字节面 100% Rust（fw: __APCarPlayControlServerHandleMessage
            // @0x243da8714 直切——fw::airplay::ctrl + nativeAirplayCtrl{Kind,Respond}，
            // TimingServer 同构：Kotlin 只剩 socket 泵）。
            val kind = NativeBridge.nativeAirplayCtrlKind(head.toByteArray(Charsets.US_ASCII))
            val response = kind?.let { NativeBridge.nativeAirplayCtrlRespond(head.toByteArray(Charsets.US_ASCII)) }
            if (kind == "connect") {
                // 车机 ctrl 邀请 = 会话启动命令（fw: connect 命令域；MMI 6318
                // 实车形态——邀请 ×3 零 0x4301，2026-11-05 定谳）。
                inviteCount++
                log("$TAG: ✓ 车机 ctrl 邀请（第 $inviteCount 次）——AirPlay 窗口已开启")
                onCarInvite?.invoke(peer)
            }
            val output = client.getOutputStream()
            if (response != null) {
                output.write(response)
                output.flush()
            }

            // 对端 Connection: close 会自行断开；等待期间若还有字节（隧道/事件），转储出来
            client.soTimeout = 3_000
            val rest = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                    if (rest.size() > 64 * 1024) break
                }
            } catch (_: IOException) {
                // 读超时即停止转储
            }
            if (rest.size() > 0) {
                log(
                    "$TAG: 应答后收到 ${rest.size()} 字节：" +
                        rest.toByteArray().take(64).joinToString(" ") {
                            "%02x".format(it.toInt() and 0xFF)
                        },
                )
            }
            log("$TAG: $peer 连接结束")
        } catch (error: IOException) {
            log("$TAG: $peer 连接异常 - ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val count =
                try {
                    input.read(chunk)
                } catch (_: IOException) {
                    break
                }
            if (count < 0) break
            buffer.write(chunk, 0, count)
            val text = buffer.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.toString("ISO-8859-1")
    }

    /** 只注销 mDNS 注册，保留监听 socket 与 accept 线程（重注册用，端口不变）。 */
    @Synchronized
    private fun unregisterServiceOnly() {
        registered = false
        registeredWithMac = null
        activeListener?.let { runCatching { nsdManager.unregisterService(it) } }
        activeListener = null
    }

    @Synchronized
    fun stop() {
        unregisterServiceOnly()
        registeredSsid = null
        registeredNetwork = null
        acceptRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("$TAG: 已停止")
    }

    /** 每次注册新建 listener（见 start() 注释；复用同一实例会触发 already in use）。 */
    private fun createListener(): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log("$TAG: mDNS 注册成功 ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                // 重置状态：下一次 ensureForNetwork（新会话的 0x5703）会重新注册
                registered = false
                log("$TAG: mDNS 注册失败 code=$errorCode（下次会话自动重试）")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) = Unit
        }
}
