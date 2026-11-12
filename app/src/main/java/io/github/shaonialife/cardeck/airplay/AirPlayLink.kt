package io.github.shaonialife.cardeck.airplay

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.shaonialife.cardeck.audio.AudioCodec
import io.github.shaonialife.cardeck.audio.AudioProfile
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.net.EndpointHosts
import io.github.shaonialife.cardeck.net.TcpLiveness
import io.github.shaonialife.cardeck.protocol.NativeBridge
import io.github.shaonialife.cardeck.util.DeviceIdentity
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * AirPlay 链路壳（R2 平台层定形 2026-11-04，替代 AirPlayClient）：
 * **零协议逻辑**——TCP socket + nativeAirplay* 泵搬运 + keepalive UDP +
 * Timing socket + 事件通道命令；连接序/配对/流 SETUP/加密全在 Rust
 * （cardeck-protocol fw::airplay，协议决策不过 Kotlin 边界）。
 * 壳只做三件事：搬字节（OnBytes/TakeOutbound）、执行平台动作（socket/UDP/
 * 线程）、把 Rust 事件 JSON 分发给媒体硬件与主控。
 */
class AirPlayLink(
    initialEndpoint: Iap2SenderSession.CarPlayEndpoint,
    private val log: (String) -> Unit,
    private val context: Context? = null,
    /**
     * 配对窗口内的端点刷新器（每轮重试前调用，必须非阻塞）：返回并入 mDNS 新结果后的
     * 端点，null = 无新信息。Audi MIB3 的 `_airplay._tcp` 常在首次解析之后才上线，
     * 早期实现只在配对前查一次，失败后只能对着网关兜底地址空转到 120s 窗口耗尽。
     */
    private val endpointRefresher: ((Iap2SenderSession.CarPlayEndpoint) -> Iap2SenderSession.CarPlayEndpoint?)? = null,
    /**
     * 配对重试期间的低频钩子（每 ~10s 一次，必须非阻塞）：主控用它重新公告 ctrl mDNS
     * 并报告 mDNS 发现状态。
     *
     * 为什么需要：2026-10-19 奥迪 0.1.7 日志里车机**始终没有探测 ctrl、也没广播
     * `_airplay._tcp`**，而我们在 4s 邀请窗口超时后只对着网关 `:7000` 空转 54s
     * （`ECONNREFUSED`），期间既不再公告、也不报告「mDNS 一条都没发现」。
     * 车机的 Bonjour 可能错过首次公告；周期性重播给它是真实 iPhone 的做法
     * （catplay 每 1s force_announce）。
     */
    private val retryTick: ((attempt: Int) -> Unit)? = null,
) {
    /**
     * 当前使用的接收端端点。0x4301 晚到 / mDNS 后上线时由主控热更新；
     * 配对循环每轮重试都重新读取，改动在下一轮即生效。
     */
    @Volatile
    private var endpoint: Iap2SenderSession.CarPlayEndpoint = initialEndpoint

    /** 端点代数：ensureConnected 用它检测「连接途中端点被换掉」，避免把新端点的请求发到旧地址。 */
    @Volatile
    private var endpointGeneration = 0

    private var socket: Socket? = null

    /**
     * 最近一次成功连上接收端时使用的地址（字符串形式，IPv6 链路本地地址带 `%<iface>`）。
     *
     * 车机（尤其奥迪）的 `_airplay._tcp` / 0x4301 常常只给 **IPv6 link-local**；
     * 旧实现只认 IPv4（`contains('.')`），这类端点会被当成「无地址」，退回
     * 网关:7000 兜底——4xxx 固件的车机网关/端口未必是 .1:7000，于是永远连不上。
     * 现在按 catplay 的做法直接使用 Bonjour/0x4301 给的地址，IPv4 优先、IPv6 兜底，
     * 并把**真正连通的地址**交给视频/音频/事件通道（它们必须与配对走同一地址）。
     */
    @Volatile
    var activeHost: String? = null
        private set

    /** 连通地址的解析结果（视频/音频端口复用同一 InetAddress，避免二次解析失败）。 */
    @Volatile
    private var activeAddress: InetAddress? = null

    /**
     * 计时服务（初始 SETUP 通告端口；车机 NTP ping 它同步媒体时钟）。
     * 懒启动、跨重试常驻（close() 不停它——UDP socket 无会话状态）。
     */
    private val timingServer = TimingServer(log)

    // ---- 对外流程 ----

    /**
     * 配对期间**任意阶段**出现 403（pair-setup / pair-verify / 认证段）。
     *
     * 真机抓包定谳（2026-10-21 奥迪 MMI 3315_5GHz sysdiagnose）：真 iPhone 首次
     * 连接在 pair-verify 通过后被车机 403 拒（MFi 认证段），车机 **18~20s 自行重发
     * ctrl 邀请、第二次即成**，全程不断蓝牙。所以任何 403 都意味着「iAP2 信任已
     * 建立的车机在等自己的状态机就绪」——此时断 BT 或降级明文会话都是反向操作，
     * 正确姿势是窗口内保持原流程重试（主控按本标志避开两条歧路）。
     */
    @Volatile
    var pairingForbidden: Boolean = false

    /** pair-setup M6 取得的接收端静态身份（fw: 配对库源，verify FindPeerEx 用）。 */
    var setupReceiverIdentity: ReceiverIdentity? = null

    /** 配对库持久化的接收端身份（fw: SavePeer——跨进程/重连的 verify 验签源）。 */
    private var storedReceiverIdentity: ReceiverIdentity? = null

    /** pair-setup SRP 密码（车机侧展示的 PIN；null = Rust 回落 "3939"）。 */
    var setupCodeOverride: String? = null

    /**
     * M5.2 Rust 会话句柄（runPairing 成功后存活——runSetup 沿用同一加密会话，
     * fw: apsession 生命周期跨 control setup）。0 = 无 Rust 会话（走旧路径）。
     */
    private var controlSessionHandle: Long = 0L

    /** Rust info 事件全量（runSetup 的 displays/HID/features 源）。 */
    private var rustInfoEvent: org.json.JSONObject? = null
        private set

    /**
     * 跳过 HomeKit 配对，直接走 `et=NONE` 明文会话（pre-HomeKit 老车机）。
     *
     * 依据是 `_airplay._tcp` TXT features 未声明 bit38 HK_PAIRING_AND_ENCRYPT
     * （catplay 的接收端分类 1：「pre-HomeKit: has no /pair-setup and no
     * HK_PAIRING_AND_ENCRYPT Bonjour flag (v210 era +/-)」）。
     * 不试 pair-setup 可以省掉注定失败的请求；若 TXT 判断有误，配对仍会在
     * SETUP 被拒后由调用方回退（不会比旧行为更差）。
     */
    /**
     * 配对：先试 pair-setup（注册长期公钥）→ pair-verify（派生控制密钥）；
     * 若 pair-setup 被 **403** 拒绝（真车 Audi MIB3 实测：iAP2 阶段已完成 MFi 信任，
     * 不接受匿名 SRP 配对窗口），则改走「已配对设备快路径」直接 pair-verify。
     * 每次重试都换全新 TCP 连接（部分接收端按连接维护状态机）。
     *
     * 不预热 /info：真车实测（2026-10-11）车机对未配对连接上的 /info 应答 455
     * 且此后同连接上的 pair-setup 被 403；Apple 客户端与 catplay 均直接进配对。
     * 配对请求头按 catplay PairingHelperTx 逐字对齐（X-Apple-HKP / X-Apple-PD）。
     */

    /** 取消标志（会话退役时置位）：配对重试循环立即退出，不再跑满等待窗口。 */
    @Volatile
    var cancelled: Boolean = false

    /** G2 实验：SDP 序的本端 control 端口（仅 cardeck.sdp=1 时分配）。 */
    private var sdpLocalPorts: SdpLocalPorts? = null

    /** 配对重试循环是否仍在跑（主控据此判断「晚到端点」应热更新还是忽略）。 */
    @Volatile
    var pairingInProgress: Boolean = false
        private set

    /** 当前端点（主控合并晚到端点时用作兜底，避免 0x4301 不带 IP 时把可用地址抹掉）。 */
    val currentEndpoint: Iap2SenderSession.CarPlayEndpoint get() = endpoint

    /**
     * 热更新接收端端点（0x4301 晚到 / 配对期间 mDNS 新结果）。
     * 地址或端口变化时关闭当前 TCP，让配对循环下一轮按新地址重连。
     *
     * @return true = 端点确有变化（已切换）；false = 与当前一致，未做任何事
     */
    fun updateEndpoint(
        newEndpoint: Iap2SenderSession.CarPlayEndpoint,
        reason: String,
    ): Boolean {
        val old = endpoint
        val unchanged =
            old.ipAddresses == newEndpoint.ipAddresses &&
                old.airPlayPort == newEndpoint.airPlayPort &&
                old.publicKey == newEndpoint.publicKey &&
                old.deviceIdentifier == newEndpoint.deviceIdentifier
        if (unchanged) return false
        endpoint = newEndpoint
        endpointGeneration++
        log(
            "AIRPLAY: 端点更新（$reason）" +
                "${old.ipAddresses.firstOrNull()}:${old.airPlayPort} → " +
                "${newEndpoint.ipAddresses.firstOrNull()}:${newEndpoint.airPlayPort}" +
                "（配对循环下一轮使用新地址）",
        )
        close()
        return true
    }

    /** 配对泵交付物（flow 完成后宿主数据面消费——sharedSecret/Events 双钥）。 */
    var lastKeys: ControlKeys? = null
        private set

    /** 连接序完成时由 [setupRound] 装配（flow done 动作的返回载荷）。 */
    private var pendingSetupInfo: SetupInfo? = null

    /** flow connect 动作计数（低频 retryTick 钩子节拍）。 */
    private var flowConnectTick = 0

    /**
     * 连接编排泵（**决策机 = Rust fw::airplay::flow**——固件骨架
     * `_apsession_ensureConnectedInternal` @0x22775f8a0 +
     * `_apsession_ensureStartedInternalStage1StartTransaction` @0x2276ce288）。
     * 本循环只**执行动作、上报结果**，全部决策（配对路径/明文降级/加密升级/
     * 403 退避/放弃）由决策机给出：
     * - connect → 建 TCP（平台 IO）
     * - pairing → 单连接配对轮（fw: tryAPPairSetupAndVerify 单连接单次）
     * - setup   → 连接序自动链（会话机内自驱 stup→100→101→annc→rcrd）
     * - wait    → 退避（值由决策机给，宿主执行+检查取消）
     * - done    → 连接序完成，返回 [SetupInfo]
     * - abort   → 结构失败放弃
     */
    fun runFlow(
        windowMs: Long,
        noPairingHint: Boolean,
    ): SetupInfo? {
        pairingInProgress = true
        lastKeys = null
        pendingSetupInfo = null
        flowConnectTick = 0
        val identity = loadOrCreateIdentity()
        log("AIRPLAY: 控制器身份 pairingId=${identity.pairingId}")
        // 会话墙钟锚（fw: NTPClockSetEpoch @0x19293f7a0 锚定语义——时钟
        // 纪元 = 锚定时刻的 NTP-1900 秒，之后线性流逝）。会话内恒定：
        // 单调平滑不受墙钟步进影响；与 timing 应答秒域同基（timing.rs
        // respond 同加 NTP_EPOCH_OFFSET）。差值消费端无感（Audi 实测形态），
        // 绝对锚消费端修复（Honda/Alpine「无锚不排播」口径）。
        ntpEpochAnchorSecs =
            System.currentTimeMillis() / 1000 +
            NTP_EPOCH_OFFSET_SECS -
            System.nanoTime() / 1_000_000_000
        val flowHandle = NativeBridge.nativeAirplayFlowBegin(if (noPairingHint) 1 else 0)
        if (flowHandle == 0L) {
            log("AIRPLAY: ⚠ flow 决策机创建失败（so 未加载？）")
            pairingInProgress = false
            return null
        }
        try {
            val deadline = System.currentTimeMillis() + windowMs
            var report = "{\"op\":\"begin\"}"
            while (!cancelled && System.currentTimeMillis() < deadline) {
                val action =
                    NativeBridge.nativeAirplayFlowStep(flowHandle, report.toByteArray())
                        ?: return null
                val json = org.json.JSONObject(action)
                when (json.optString("action")) {
                    "connect" -> {
                        // 平台节奏辅助（非决策）：吸收 mDNS 新结果 + 低频 ctrl 重公告
                        endpointRefresher?.invoke(endpoint)?.let { refreshed ->
                            updateEndpoint(refreshed, "配对期间 mDNS 刷新")
                        }
                        flowConnectTick++
                        if (flowConnectTick == 1 || flowConnectTick % RETRY_TICK_ATTEMPTS == 0) {
                            runCatching { retryTick?.invoke(flowConnectTick) }
                        }
                        close()
                        report =
                            if (ensureConnected(2_000)) {
                                "{\"op\":\"ok\"}"
                            } else {
                                "{\"op\":\"connectFail\"}"
                            }
                    }
                    "pairing" -> report = pairingRound(identity)
                    "setup" -> report = setupRound()
                    "wait" -> {
                        val ms = json.optLong("ms")
                        log("AIRPLAY: flow 退避 ${ms}ms（${json.optString("reason")}）")
                        var left = ms
                        while (left > 0 && !cancelled) {
                            Thread.sleep(minOf(left, 200))
                            left -= 200
                        }
                        report = if (cancelled) "{\"op\":\"cancelled\"}" else "{\"op\":\"ok\"}"
                    }
                    "done" -> return pendingSetupInfo
                    "abort" -> {
                        log("AIRPLAY: flow 放弃 - ${json.optString("reason")}")
                        return null
                    }
                }
            }
            log("AIRPLAY: flow 窗口耗尽（${windowMs}ms）")
            return null
        } finally {
            NativeBridge.nativeAirplayFlowDestroy(flowHandle)
            pairingInProgress = false
        }
    }

    /**
     * 单连接配对轮（fw: tryAPPairSetupAndVerify 单连接单次——每轮新连接新会话
     * 句柄，接收端按连接维护状态机）。返回 flow 上报 JSON。
     */
    private fun pairingRound(identity: ControllerIdentity): String {
        // 配对库持久化身份先加载（peerKnown 判定必须看到它；否则进程重启后
        // 明明存过 SavePeer 仍走 setup，且 verify 无钥可验）。
        if (storedReceiverIdentity == null) storedReceiverIdentity = loadReceiverIdentity()
        // peerKnown = fw: isPeerKnown——**我方配对库存有对端 LTPK** 的分支
        // 语义：本会话 setup 完成（done）或 403（车机视 iAP2 信任为已配对）
        // 才算已知；endpoint.publicKey 是车机自己的身份，不是「车机已配对
        // 我们」的证据——误当 peerKnown 会跳过 pair-setup，车机验 M3 签名
        // 无记录 → M4 空 type5（16:40 真机 121 次循环实证）。
        val peerKnown =
            if (storedReceiverIdentity != null || setupReceiverIdentity != null || pairingForbidden) 1 else 0
        // 配对码注入（§10.49：PIN 流接收端）。纯平台 IO：files/pair_pin.txt
        // 存在即读，每次尝试重读（adb 写入 → 下一次尝试携带）。
        if (setupCodeOverride == null) {
            runCatching {
                val dir = context?.filesDir ?: return@runCatching
                val pinFile = java.io.File(dir, "pair_pin.txt")
                if (pinFile.isFile) {
                    val pin = pinFile.readText().trim()
                    if (pin.isNotEmpty()) {
                        setupCodeOverride = pin
                        log("AIRPLAY: 已载入配对码注入（files/pair_pin.txt，${pin.length} 位）")
                    }
                }
            }
        }
        val handle =
            NativeBridge.nativeAirplayCreate(
                FLAGS_AIRPLAY_SENDER,
                identity.pairingId.toByteArray(),
                identity.privateKey,
                (android.os.Build.MODEL ?: "CarDeck").toByteArray(),
                peerKnown,
                setupCodeOverride?.toByteArray(),
            )
        if (handle == 0L) {
            log("AIRPLAY: ⚠ 会话创建失败（so 未加载？）")
            return "{\"op\":\"pairFail\"}"
        }
        applyPlatformIdentity(handle)
        val outcome = pumpPairingSession(handle)
        // 失败路径才销毁（成功路径句柄移交 controlSessionHandle）
        if (controlSessionHandle != handle) {
            NativeBridge.nativeAirplayDestroy(handle)
        }
        return outcome
    }

    /**
     * 单连接上的会话泵：发 info/配对 M1，读循环喂 Rust，事件回调收尾。
     * Rust 侧决策（fw: orchestrator）：403 → reconnectRequired（返回后由外层
     * 重连重试）；完成 → ControlKeys（Control 双钥，fw: CreateEncryptionKeysForContext）。
     */
    private fun pumpPairingSession(handle: Long): String {
        val client = socket ?: return "{\"op\":\"pairFail\"}"
        client.soTimeout = 10_000
        val input = client.getInputStream()
        val output = client.getOutputStream()

        fun drainAndWrite() {
            // 泵出站 = 裸 RTSP 字节（Rust 已组好），直写 socket 不走 post() 的
            // 协议组包路径（fw: HTTPConnection 写循环）
            NativeBridge.nativeAirplayTakeOutbound(handle)?.let { bytes ->
                synchronized(rtspWriteLock) {
                    output.write(bytes)
                    output.flush()
                }
                log("AIRPLAY: pump tx ${bytes.size}B")
            }
        }
        // fw: fetchInitialInfo 先行——配对 M1 等 info 事件后再发（Rust 会话机
        // 按 ensureConnectedInternal 顺序推进，Kotlin 泵只搬字节）
        NativeBridge.nativeAirplayInfo(handle)
        drainAndWrite()
        var pairingStarted = false
        // §10.49 PIN 流：/pair-pin-start 车机受理后本连接进入「等人输码」
        // 静默期——读超时（SO_TIMEOUT）不是失败，继续等；期间轮询
        // pair_pin.txt，文件出现即经 providePin 在**同一连接**上续推 setup
        // M1（真机 2026-11-02：车机对 /pair-pin-start 5ms 内回 200 空体；
        // 重连反而丢配对上下文）。
        var awaitingPin = false
        var pinWaitLogged = false
        // §10.50（2026-11-02）：PIN 流 dormant——iPhone 真机 sysdiagnose 实证
        // DiPlay 走 type 0 常量码流（见 cardeck-protocol §10.50），本等待分支
        // 仅在 Rust 回 pinRequired 时激活；激活时轮询 files/pair_pin.txt，
        // 文件出现即经 providePin 在同一连接上续推 setup M1。
        val buf = ByteArray(16 * 1024)
        while (!cancelled) {
            val n =
                try {
                    input.read(buf)
                } catch (error: java.net.SocketTimeoutException) {
                    if (awaitingPin) {
                        val pin =
                            context
                                ?.filesDir
                                ?.let { dir -> java.io.File(dir, "pair_pin.txt") }
                                ?.takeIf { f -> f.isFile }
                                ?.readText()
                                ?.trim()
                        if (!pin.isNullOrEmpty()) {
                            log("AIRPLAY: ⌨ 配对码已注入（${pin.length} 位）——同一连接续推 setup M1")
                            NativeBridge.nativeAirplayProvidePin(handle, pin.toByteArray())
                            drainAndWrite()
                            awaitingPin = false
                            pinWaitLogged = false
                        } else {
                            if (!pinWaitLogged) {
                                log("AIRPLAY: ⌨ /pair-pin-start 已受理（车机 200）——等待配对码注入（files/pair_pin.txt）")
                                pinWaitLogged = true
                            }
                        }
                        continue
                    }
                    log("AIRPLAY: 配对读超时 - ${error.message}")
                    return "{\"op\":\"pairFail\"}"
                } catch (error: IOException) {
                    log("AIRPLAY: 配对读断开 - ${error.message}")
                    return "{\"op\":\"pairFail\"}"
                }
            if (n < 0) {
                log("AIRPLAY: 配对连接被对端关闭")
                return "{\"op\":\"pairFail\"}"
            }
            val event = NativeBridge.nativeAirplayOnBytes(handle, buf.copyOf(n)) ?: continue
            if (event.isEmpty()) continue
            log("AIRPLAY: pump ev=$event")
            drainAndWrite()
            val json = org.json.JSONObject(event)
            when (json.optString("type")) {
                "info" -> {
                    // info 已回复 → 顺序流转配对（fw: ensureConnectedInternal）
                    if (!pairingStarted) {
                        pairingStarted = true
                        rustInfoEvent = json
                        runCatching {
                            val map = mutableMapOf<String, Any?>()
                            val keys = json.keys()
                            while (keys.hasNext()) map[keys.next()] = json.opt(keys.next())
                            logInfoSummary(map)
                        }
                        log("AIRPLAY: info deviceID=${json.optString("deviceID")}")
                        publishOemBlock(json)
                        // startPairing 返回事件（pinRequired = PIN 流已起，
                        // setup M1 未发——§10.49）：此前被丢弃，凭它进入等码期
                        val startEv = NativeBridge.nativeAirplayStartPairing(handle)
                        if (startEv?.contains("pinRequired") == true) {
                            awaitingPin = true
                            log("AIRPLAY: ⌨ PIN 流启动——等待车机屏配对码 / pair_pin.txt 注入")
                        }
                        drainAndWrite()
                    }
                }
                "reconnectRequired" -> {
                    // fw: 403 = 信任已建（SavePeer）→ 外层换新连接重试
                    pairingForbidden = true
                    log("AIRPLAY: pair-setup 被 403 拒绝（车机不接受匿名配对——iAP2 已完成信任）→ 转已配对快路径")
                    return "{\"op\":\"pairForbidden\"}"
                }
                "done" -> {
                    // fw: setup M6 产物（SavePeer 内容）+ Control 双钥
                    json.optString("identifier", "").takeIf { it.isNotEmpty() }?.let { ident ->
                        val pub = json.optString("pubkey", "")
                        setupReceiverIdentity =
                            ReceiverIdentity(
                                ident,
                                if (pub.isEmpty()) ByteArray(0) else hexToBytes(pub) ?: ByteArray(0),
                            )
                        setupReceiverIdentity?.let { saveReceiverIdentity(it) }
                    }
                    val write = hexToBytes(json.getString("writeKey")) ?: ByteArray(0)
                    val read = hexToBytes(json.getString("readKey")) ?: ByteArray(0)
                    // §10.53：X25519 共享密钥原文（事件通道密钥派生的 ikm——
                    // Rust Done 交付；此前空数组 → Kotlin 事件加密密钥错 → 接收端
                    // BAD_DECRYPT，真机 22:04:46 实证）
                    val shared = hexToBytes(json.optString("sharedSecret")) ?: ByteArray(0)
                    // Events 双钥（Rust 会话 verify M4 同域派生）——事件通道直用
                    val eventsWrite = hexToBytes(json.optString("eventsWriteKey")) ?: ByteArray(0)
                    val eventsRead = hexToBytes(json.optString("eventsReadKey")) ?: ByteArray(0)
                    // pair-verify 之后双方立即启用加密（接收端在 M4 应答后开启）——
                    // 加密封装全在 Rust 会话泵内，Kotlin 无密文面
                    log("AIRPLAY: 配对完成，控制通道加密已启用")
                    // M5.2：会话句柄存活跨连接序（fw: apsession 生命周期——
                    // 控制建立/流 SETUP/事件通道全走同一加密会话）
                    controlSessionHandle = handle
                    lastKeys = ControlKeys(write, read, shared, eventsWrite, eventsRead)
                    return "{\"op\":\"pairDone\"}"
                }
                "error" -> {
                    log("AIRPLAY: 会话事件错误 - ${json.optString("message")}")
                    return "{\"op\":\"pairFail\"}"
                }
            }
        }
        return "{\"op\":\"cancelled\"}"
    }

    // 单连接上的 pair-verify 尝试（原 runVerifyAttempt）：**已整体移入 Rust 会话泵**
    // （M5.2：M1-M4/头集/密钥派生 = fw::airplay::session，Kotlin 只剩 TCP 泵）。
    // 行为见 [pumpPairingSession] / [handleEvent]。

    /**
     * KeepAlive UDP 心跳（'kplv' 流，fw: ensureLowPowerKeepAliveStreamSetup
     * @0x22784de34 → APTransportConnectionUDPCreate——UDP 到 control-setup
     * 应答的 keepAlivePort）：载荷 = 固件帧 `nativeAirplayKplvPingFrame(4)`
     * （4B {0,4,0,0}，fw: APTPackageLowPowerKeepAliveCreateWithBBuf
     * @0x243d442e8；keepAliveType=4=LowPowerOnIdle 真机日志实证）。
     * **缺失时车机 10s 看门狗拆整轮会话**（2026-10-19 奥迪 3315 实证 =
     * kplv 真车必需性已证）。
     *
     * 间隔取 **5s** 而不是 catplay 的 10s：10s 与车机看门狗等长，`Thread.sleep` 的
     * 毫秒级抖动会让每次心跳都晚到 deadline 之后 1ms（2026-10-19 奥迪 3315 两轮实测：
     * ping #2 距 #1 恰好 10.001s，随后 213~247ms 内整轮被 reset，RECORD 后 10.2s）。
     * catplay 的 10s 只适用于它自家接收端；对真车必须留出至少一半的余量。
     * （固件周期 = 系统卸载运行时配置 udpconnection_setupKeepAliveOffload
     * @0x243d2ae6c，无编译常量 → 5s = 平台策略域，C3/D9 同域。）
     */
    fun startKeepAlive(
        host: String,
        port: Int,
    ) {
        if (port <= 0) return
        val payload = NativeBridge.nativeAirplayKplvPingFrame(4)
        Thread({
            var udp: java.net.DatagramSocket? = null
            try {
                udp = java.net.DatagramSocket()
                val address = java.net.InetAddress.getByName(host)
                var sent = 0
                var lastSentAt = 0L
                while (isSessionAlive() && udp?.isClosed == false) {
                    val now = SystemClock.uptimeMillis()
                    val gap = if (lastSentAt == 0L) 0L else now - lastSentAt
                    lastSentAt = now
                    udp.send(java.net.DatagramPacket(payload, payload.size, address, port))
                    sent++
                    if (sent <= 3 || sent % 60 == 0) {
                        log("AIRPLAY: keepAlive ping #$sent → $host:$port（间隔 ${gap}ms）")
                    }
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
            } catch (error: IOException) {
                log("AIRPLAY: keepAlive 结束 - ${error.message}")
            } finally {
                udp?.close()
            }
        }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    // ---- §10.57 会话生命周期 Rust 泵（控制通道读写单持有者）----
    // Kotlin legacy request() 的写计数器与 Rust 各自维护 → nonce 错位
    // （真机 21:58/22:04 BAD_DECRYPT 双实证）。会话建立后控制通道只允许
    // Rust 写；屏流/隧道/feedback 全部经 native 调用注入，泵统一排空。

    private val controlEventQueue = java.util.concurrent.LinkedBlockingQueue<org.json.JSONObject>()

    /** 屏幕流帧密封消费的会话泵句柄（A4——VideoSender 经它走 Rust 密封态）。 */
    @Volatile
    var sessionPumpHandle: Long = 0L
        private set

    private fun startSessionPump(
        handle: Long,
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        sessionPumpHandle = handle
        Thread({
            val buf = ByteArray(16 * 1024)
            while (!cancelled && sessionPumpHandle == handle) {
                val n =
                    try {
                        input.read(buf)
                    } catch (_: java.net.SocketTimeoutException) {
                        drainRustOutbound(handle, output)
                        continue
                    } catch (_: IOException) {
                        break
                    }
                if (n < 0) break
                val event = NativeBridge.nativeAirplayOnBytes(handle, buf.copyOf(n))
                drainRustOutbound(handle, output)
                if (!event.isNullOrEmpty()) {
                    val json = runCatching { org.json.JSONObject(event) }.getOrNull()
                    val t = json?.optString("type")
                    if (json != null && (t == "streamSetup" || t == "recorded")) {
                        controlEventQueue.offer(json)
                    } else if (json != null && t == "sdpSetup") {
                        log(
                            "AIRPLAY: SDP 端口 server=${json.optInt("serverPort")} " +
                                "control=${json.optInt("controlPort")}",
                        )
                    } else if (json != null && t == "volume") {
                        // 音量域（fw: _APSenderSessionUtilityFetchInitialVolume 应答
                        // 交付；-1000.0 = 接收端未报初始音量的 unset 哨兵）。
                        val db = json.optDouble("db")
                        if (db <= -100.0) {
                            log("VOLUME: 车机未报初始音量（unset 哨兵）——按 0.1.10 形态设 -25dB")
                            NativeBridge.nativeAirplayApplyVolume(handle, -2500)
                        } else {
                            log("VOLUME: 车机初始音量=${"%.1f".format(db)}dB——回写激活（fw: pending set-volume）")
                            NativeBridge.nativeAirplayApplyVolume(handle, (db * 100).toInt())
                        }
                        drainRustOutbound(handle, output)
                    }
                }
            }
        }, "rust-session-pump").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainRustOutbound(
        handle: Long,
        output: java.io.OutputStream,
    ) {
        NativeBridge.nativeAirplayTakeOutbound(handle)?.let { bytes ->
            synchronized(rtspWriteLock) {
                output.write(bytes)
                output.flush()
            }
        }
    }

    /** Rust 流级 SETUP（屏 110 / 隧道 130），同步等待 streamSetup 回执事件。 */
    fun rustStreamSetupWait(
        json: String,
        timeoutMs: Long = 8_000,
    ): org.json.JSONObject? {
        val handle = sessionPumpHandle
        if (handle == 0L || controlSessionHandle != handle) return null
        controlEventQueue.clear()
        NativeBridge.nativeAirplayStreamSetup(handle, json.toByteArray())
        val socket = socket ?: return null
        drainRustOutbound(handle, socket.getOutputStream())
        return controlEventQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** 取初始音量（GET_PARAMETER volume；应答经泵 on_bytes 事件回）。 */
    fun fetchVolume() {
        val h = sessionPumpHandle
        if (h == 0L) return
        NativeBridge.nativeAirplayFetchVolume(h)
        socket?.let { drainRustOutbound(h, it.getOutputStream()) }
    }

    /**
     * 6011 监听探针（2026-11-12 真车诊断）：S4.4 直切 = 请求 controlPort 会被
     * 接收端写进 config「RTCPPortRemote」= 手机 RTCP 收包口——车机往里发、
     * 我们监听（旧「发同步包到 car:6011」方向在真车永不绑定，ECONNREFUSED
     * 实录）。绑上后记录一切入站包，验证/证伪车机 RTCP 方向。
     */
    fun startControlPortProbe(port: Int) {
        if (port <= 0) return
        Thread {
            val s =
                runCatching {
                    java.net.DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(port))
                        soTimeout = 5_000
                    }
                }.getOrNull()
            if (s == null) {
                log("CPROBE: 绑 $port 失败——可能被占")
                return@Thread
            }
            log("CPROBE: 已监听 :$port（等车机 RTCP——诊断探针）")
            val buf = ByteArray(2048)
            var n = 0
            try {
                while (isSessionAlive() && sessionPumpHandle != 0L) {
                    val p = java.net.DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (timeout: java.net.SocketTimeoutException) {
                        continue
                    } catch (_: java.io.IOException) {
                        break
                    }
                    n++
                    if (n <= 3 || n % 20 == 0) {
                        val headLen = if (p.length < 16) p.length else 16
                        val head = buf.take(headLen).joinToString(" ") { "%02x".format(it) }
                        log(
                            "CPROBE: 收 #$n 来自 ${p.address.hostAddress}:${p.port} " +
                                "len=${p.length} head=$head",
                        )
                    }
                }
            } finally {
                s.close()
                log("CPROBE: 退出（共收 $n 包）")
            }
        }.apply {
            isDaemon = true
            name = "cprobe-$port"
            start()
        }
    }

    /** Rust 会话 feedback（'keep' POST /feedback——帧由 Rust 构建，1s 节奏）。 */
    fun startRustFeedbackLoop() {
        Thread({
            while (isSessionAlive() && sessionPumpHandle != 0L) {
                NativeBridge.nativeAirplayKeepAlive(sessionPumpHandle)
                Thread.sleep(1_000)
            }
        }, "rust-feedback").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * RTSP /feedback 心跳（对照 catplay FEEDBACK_INTERVAL=1000ms）：每秒 POST 空体
     * /feedback——接收端的活动看门狗。RTSP 事务由 [rtspLock] 串行化。
     */
    fun startFeedbackLoop() {
        // 'keep' 帧由 Rust 构建+泵排空（§10.57 控制通道单持有者——明文直写
        // 加密控制通道会 BAD_DECRYPT 断连，真机 21:58/22:04 实证）
        startRustFeedbackLoop()
    }

    /** 心跳线程的存活判据：RTSP socket 仍连着。 */
    private fun isSessionAlive(): Boolean = socket?.isClosed == false

    fun close() {
        stopTunnelDataPlane()
        teardownSession()
        timingServer.stop()
        runCatching { socket?.close() }
        socket = null
        // 事件通道 socket 也要关（2026-11-12 审查：此前全生命周期无 close，
        // 每轮会话泄漏一个 FD + 半开 TCP 连接）；关闭唤醒 event-reader 走
        // IOException 拆链路径（stopTunnelDataPlane/iapLink.close 均幂等）。
        runCatching { eventCommandSocket?.close() }
        eventCommandSocket = null
        activeHost = null
        activeAddress = null
        iapLink?.close()
        iapLink = null
        val h = controlSessionHandle
        controlSessionHandle = 0L
        if (sessionPumpHandle == h) sessionPumpHandle = 0L
        if (h != 0L) NativeBridge.nativeAirplayDestroy(h)
    }

    /** fw: sendTeardownWithStreamDescArray——会话收尾通知。 */
    private fun teardownSession() {
        val h = controlSessionHandle
        if (h == 0L) return
        runCatching {
            NativeBridge.nativeAirplayTeardown(h)
            NativeBridge.nativeAirplayTakeOutbound(h)?.let { bytes ->
                val out = socket?.getOutputStream() ?: return@let
                synchronized(rtspWriteLock) {
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    /**
     * 声明 HID 输入模式（hidSetInputMode——手机→车机的触摸启用命令）。
     * **命令体在 Rust**（fw: SetHIDInputMode_block_invoke @0x22789351c——
     * dict{uuid, hidInputMode} → sendCommandInternal）；uuid 为 /info
     * hidDevices[].uuid 的十六进制串。奥迪 display features=0x08（高保真
     * 触摸）+ hidDevices 齐备，缺这步武装车机不发 hidSendReport。
     */
    fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int = 0,
    ): Boolean {
        // bytes 传输（jstring 通路在荣耀 ROM 上会走 GetArrayLength 误判——2026-11-07 闪退实证）
        val result = NativeBridge.nativeAirplaySendHidSetInputMode(controlSessionHandle, deviceUuidHex.toByteArray(), mode)
        val sent = result.isNullOrEmpty() || !result.contains("\"type\":\"error\"")
        if (!sent) log("EVENT: hidSetInputMode 发送失败(rust) - $result")
        pumpEventOutbound()
        log("AIRPLAY: hidSetInputMode(事件通道) uuid=$deviceUuidHex mode=$mode ok=$sent")
        return sent
    }

    // ---- 事件通道命令发送（对照 DiPlay sendCommandLocked）----
    // 车机命令双向都走事件通道 socket 上的 POST /command（不是 RTSP 控制连接！）：
    // iAPSendMessage（虚拟链路字节）、hidSetInputMode、modesChanged 等。
    // 异步 fire-and-forget：不等应答（DiPlay 同款写法），独立 CSeq 计数。

    @Volatile
    private var eventCommandSocket: Socket? = null

    private val eventWriteLock = Any()

    /** 事件通道建立后由 connectEventChannel 调用（加密态由 nativeAirplayEventCreate 定）。 */
    private fun registerEventCommandChannel(socket: Socket) {
        eventCommandSocket = socket
    }

    /** 取走 Rust 事件通道出站（车机命令应答/我方命令帧）写网；无出站是常态空转。 */
    private fun pumpEventOutbound() {
        val socket = eventCommandSocket ?: return
        // **take 必须在锁内**（2026-11-04 断连根因）：取帧与写网原子化——
        // 两个线程并发 pump 时锁外 take 会帧序颠倒（A 取 nonce N、B 取
        // nonce N+1、B 先写 A 后写）→ ChaCha 写计数乱序 → 接收端
        // BAD_DECRYPT 拆链（K40 实测 10:41:59 encrypted=21741 帧解密失败，
        // 持续触摸洪泛放大竞态）。锁内取 = 写网顺序 == Rust 出队序 == nonce 序。
        synchronized(eventWriteLock) {
            val out = NativeBridge.nativeAirplayEventTakeOutbound(controlSessionHandle) ?: return
            if (out.isEmpty()) return
            try {
                socket.getOutputStream().apply {
                    write(out)
                    flush()
                }
            } catch (_: IOException) {
            }
        }
    }

    /** 命令发送面已全 Rust 化（A10 清点——hidSetInputMode/iAPSendMessage/
     *  modesChanged 各有专用 native 入口，dict 构造不过宿主）。 */

    /**
     * 车机资源/应用状态快照（/info 自报与 changeModes 事件共同更新）。
     * 语义派生已下沉 Rust（fw::airplay::event_channel `ModesState::semantics`
     * ——appStateID 1=Speech/2=PhoneCall、speechMode -1=None；借属域在
     * FigStarkModeController XPC 侧，fw 字典表示无该键，保守 false）。
     */
    data class ModesState(
        val phoneCall: Boolean,
        val speechActive: Boolean,
        val screenBorrowed: Boolean,
    ) {
        val busy: Boolean get() = phoneCall || speechActive || screenBorrowed
    }

    @Volatile
    var modesState = ModesState(false, false, false)
        private set

    /** 车机资源被占用（通话/语音/借用）——此期间 modesChanged 会被仲裁拒绝，重申必须冻结。 */
    fun modesBusy(): Boolean = modesState.busy

    /** 车机归还资源（UNBORROW/UNTAKE）回调——切屏窗口，宿主应立即补发 modesChanged。 */
    var onModesRestored: (() -> Unit)? = null

    /**
     * changeModes 事件状态更新（R2 起语义布尔由 Rust 事件 JSON 直给）。
     * 返回 true = 车机归还了资源（从占用 → 空闲）：这是切屏窗口，宿主应在
     * ~80ms 后补发 modesChanged。
     */
    fun onCarModesEvent(
        phoneCall: Boolean,
        speechActive: Boolean,
        screenBorrowed: Boolean,
    ): Boolean {
        val previous = modesState
        val next = ModesState(phoneCall, speechActive, screenBorrowed)
        modesState = next
        val restored = previous.busy && !next.busy
        log(
            "AIRPLAY: changeModes 事件 → 通话=$phoneCall 语音=$speechActive " +
                "主屏借用=$screenBorrowed" + if (restored) "（归还——切屏窗口）" else "",
        )
        return restored
    }

    fun assertModes(): Boolean {
        // §10.56：命令走**事件通道**（DiPlay 同款——命令双向都在事件通道
        // POST /command；控制通道明文直写会绕过 Rust 会话加密 → 接收端
        // BAD_DECRYPT 断连，真机 2026-11-02 实证）。R2 起命令体在 Rust
        // （EventChannel::send_modes_assert——catplay assert_modes 镜像）。
        val result = NativeBridge.nativeAirplayAssertModes(controlSessionHandle)
        val sent = result.isNullOrEmpty() || !result.contains("\"type\":\"error\"")
        if (!sent) log("EVENT: modesChanged 发送失败(rust) - $result")
        pumpEventOutbound()
        log("AIRPLAY: modesChanged（声明持有主屏/主音频资源）[事件通道] ok=$sent")
        return sent
    }

    /**
     * 请求车机 UI（车机桌面 OEM 图标点击语义）：会话保持不断，车机切回原
     * 界面。协议形在 Rust（EventChannel::send_request_car_ui——fw:
     * _carEndpoint_RequestCarUI @0x2277e8ab8 → requestUI 命令；接收端
     * DiPlay onHostUiRequested / WinPlay host-ui-requested 互证）。
     */
    fun requestCarUi(): Boolean {
        val result = NativeBridge.nativeAirplayRequestCarUi(controlSessionHandle)
        val sent = result.isNullOrEmpty() || !result.contains("\"type\":\"error\"")
        if (!sent) log("EVENT: requestUI 发送失败(rust) - $result")
        // 控制通道命令：出站即写网（主 RTSP 连接；rtspWriteLock 防与泵并发）
        socket?.let { drainRustOutbound(controlSessionHandle, it.getOutputStream()) }
        log("AIRPLAY: requestUI（请求车机 UI）[控制通道] ok=$sent")
        return sent
    }

    /**
     * /info 的 OEM 图标域 → 车机桌面「返回车机」块
     * （DiPlay 实测 {label "BYD", PNG 192², visible:true}；fw: copyOEMIcons
     * @0x2277f32c0 属性域）。不可见/无标签 → 清块。
     */
    private fun publishOemBlock(json: org.json.JSONObject) {
        val label =
            if (json.optBoolean("oemIconVisible", false)) {
                json.optString("oemIconLabel", "").ifEmpty { json.optString("name", "") }
            } else {
                ""
            }
        if (label.isEmpty()) {
            io.github.shaonialife.cardeck.launcher.VirtualLauncher
                .setOemBlock(null)
            return
        }
        val icon =
            json
                .optJSONArray("oemIcons")
                ?.optJSONObject(0)
                ?.optString("imageDataBase64", "")
                ?.takeIf { it.isNotEmpty() }
                ?.let { b64 ->
                    runCatching {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
        io.github.shaonialife.cardeck.launcher.VirtualLauncher.setOemBlock(
            io.github.shaonialife.cardeck.launcher.VirtualLauncher
                .OemBlock(label, icon),
        )
    }

    /**
     * SETUP #1：发送会话信息，接收 eventPort/timingPort/enabledFeatures。
     *
     * features 必须发「完整 iPhone 能力清单」：接收端计算
     * enabledFeatures = 车机支持 ∩ 手机提议，**不在提议里的能力一律不启用**。
     * 2026-10-11 奥迪实测教训：只提议 ["videoPlayback"] 时车机返回
     * enabledFeatures=[]（视频管线未启用），随后的屏幕流 SETUP 被 400。
     * 清单对照 catplay（真车验证的发送端，含 viewAreas/uiContext 等视频必需项）。
     */
    /**
     * Rust 控制建立路径（M5.2 §10.25 换装）：nativeAirplaySetTimingPort →
     * nativeAirplayControlSetup → 泵至 controlSetup 事件 → SetupInfo 组装
     * （displays/HID 源 = Rust info 事件全解析）。
     * 流 SETUP（'stup' /）由后续批接入（AudioSender 换装同批）。
     */

    /** ANNOUNCE 参数装配——**只注平台数据**（R2 壳层定形）：uuid = 传输会话
     *  UUID 首 8B BE、地址 = 本端 socket、设备名。协议常量（音频位 15、
     *  authType 8、latency 512/2048）与 KeyHolder 随机键 IV 的缺省域已下沉
     *  Rust（session::send_announce 固件锚注释）。 */

    /** G2 实验开关：`adb shell setprop debug.cardeck.sdp 1` 或 -Dcardeck.sdp=1。 */
    private fun sdpModeEnabled(): Boolean {
        if (System.getProperty("cardeck.sdp") == "1") return true
        return runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get =
                cls.getMethod(
                    "getBoolean",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            get.invoke(null, "debug.cardeck.sdp", false) as Boolean
        }.getOrDefault(false)
    }

    private fun buildAnnounceParams(socket: java.net.Socket?): String {
        val ia = (socket?.localSocketAddress as? java.net.InetSocketAddress)?.address
        val ip = ia?.hostAddress?.replace("%.*".toRegex(), "") ?: "0.0.0.0"
        val ip6 = ia is java.net.Inet6Address
        val uuidU64 =
            runCatching {
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(16)
                    .toLong(16)
            }.getOrDefault(0L)
        val params = org.json.JSONObject()
        params.put("uuidU64", uuidU64)
        params.put("localIp", ip)
        params.put("ip6", ip6)
        params.put("deviceName", android.os.Build.MODEL ?: "CarDeck")
        // G4 平台 hook：认证类型与 KeyHolder 键/IV 由宿主提供（引擎不再伪造
        // MFi 身份）。当前为旧 MFiSAP 兼容值；真 MFi 芯片接入时应替换为
        // APSKeyHolderGetAuthenticationType / makeBase64EncodedKeyAndIV 的产物。
        val rnd = java.security.SecureRandom()
        val key = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        params.put("authType", 8)
        params.put(
            "aesKeyB64",
            java.util.Base64
                .getEncoder()
                .encodeToString(key),
        )
        params.put(
            "aesIvB64",
            java.util.Base64
                .getEncoder()
                .encodeToString(iv),
        )
        return params.toString()
    }

    /** Rust 连接序自动链（fw: Stage2StartTransaction @0x2276cf53c 域序——
     *  'stup' → 流 100/101 → 'annc' → 'rcrd' 由会话机**自驱**（setup_auto_advance），
     *  Kotlin 只泵字节至 recorded/error。 */
    private fun setupRound(): String {
        if (storedReceiverIdentity == null) storedReceiverIdentity = loadReceiverIdentity()
        if (!timingServer.start()) {
            log("AIRPLAY: 计时服务未启动（继续，但媒体时钟无法同步）")
        }
        var handle = controlSessionHandle
        if (handle == 0L) {
            // 明文会话（配对被决策机跳过/降级）：建会话句柄（peerKnown=0）
            val identity = loadOrCreateIdentity()
            handle =
                NativeBridge.nativeAirplayCreate(
                    FLAGS_AIRPLAY_SENDER,
                    identity.pairingId.toByteArray(),
                    identity.privateKey,
                    (android.os.Build.MODEL ?: "CarDeck").toByteArray(),
                    if (storedReceiverIdentity != null) 1 else 0,
                    null,
                )
            if (handle == 0L) {
                log("AIRPLAY: ⚠ 明文会话句柄创建失败")
                return "{\"op\":\"setupFail\"}"
            }
            applyPlatformIdentity(handle)
            controlSessionHandle = handle
        }
        NativeBridge.nativeAirplaySetTimingPort(handle, timingServer.port)
        val sock = socket ?: return "{\"op\":\"setupFail\"}"
        val input = sock.getInputStream()
        val output = sock.getOutputStream()

        fun drain() {
            NativeBridge.nativeAirplayTakeOutbound(handle)?.let { bytes ->
                synchronized(rtspWriteLock) {
                    output.write(bytes)
                    output.flush()
                }
            }
        }
        // info 顺序已下沉 Rust：Stage2StartTransaction 先 ensureInfoFetched
        // （fw @0x2276cef90，+0xc4==0 → fetchInfo @0x22784e67c）；宿主只消费
        // 自动链上抛的 info 事件。
        // 自动链启动（ANNOUNCE 参数 = 宿主平台数据：本机地址/UUID/设备名/键 IV）
        val announce = buildAnnounceParams(sock).toByteArray()
        if (sdpModeEnabled()) {
            // 实验开关：走固件 SDP 序（ANNOUNCE→ssdp→volume→RECORD）
            val local = SdpLocalPorts.allocate()
            sdpLocalPorts = local
            log(
                "AIRPLAY: SDP 序启动 timing=${timingServer.port} " +
                    "control=${local?.controlPort ?: 0}",
            )
            NativeBridge.nativeAirplayBeginSdpAfterControl(
                handle,
                timingServer.port,
                local?.controlPort ?: 0,
                announce,
            )
        } else {
            NativeBridge.nativeAirplaySetupAuto(handle, announce)
        }
        drain()
        val buf = ByteArray(16 * 1024)
        val deadline = System.currentTimeMillis() + 30_000
        var controlJson: org.json.JSONObject? = null
        while (System.currentTimeMillis() < deadline && !cancelled) {
            val n =
                try {
                    input.read(buf)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (error: IOException) {
                    log("AIRPLAY: 连接序读断开 - ${error.message}")
                    return "{\"op\":\"setupFail\"}"
                }
            if (n < 0) return "{\"op\":\"setupFail\"}"
            val event = NativeBridge.nativeAirplayOnBytes(handle, buf.copyOf(n)) ?: continue
            drain()
            if (event.isEmpty()) continue
            val json = org.json.JSONObject(event)
            when (json.optString("type")) {
                "info" -> {
                    rustInfoEvent = json
                    log("AIRPLAY: info deviceID=${json.optString("deviceID")}")
                    publishOemBlock(json)
                    if (controlJson != null) {
                        pendingSetupInfo = buildSetupInfo(controlJson)
                        if (pendingSetupInfo == null) {
                            log("AIRPLAY: info 完成但缺 controlSetup 事件")
                            return "{\"op\":\"setupFail\"}"
                        }
                        log(
                            "AIRPLAY: ✓ 连接序完成（control+info，宿主驱动屏流）" +
                                " eventPort=${pendingSetupInfo!!.eventPort} " +
                                "timingPort=${pendingSetupInfo!!.timingPort} " +
                                "display=${pendingSetupInfo!!.displayWidth}x${pendingSetupInfo!!.displayHeight}",
                        )
                        sock.soTimeout = 1_000
                        startSessionPump(handle, input, output)
                        return "{\"op\":\"setupOk\"}"
                    }
                }
                "volume" -> {
                    // G8：初始音量事件（SDP 序 GET /volume 回包；fw:
                    // __NTPClock... 同层）——先记录，宿主音量执行面后续接。
                    initialVolumeDb = json.optDouble("db", Double.NaN)
                    log("AIRPLAY: 初始音量 db=$initialVolumeDb")
                }
                "controlSetup" -> {
                    controlJson = json
                    json.optJSONObject("timingPeerInfo")?.let {
                        log("AIRPLAY: 车机 timingPeerInfo=$it")
                    }
                    json.optJSONArray("timingPeerList")?.let {
                        log("AIRPLAY: 车机 timingPeerList=$it")
                    }
                    // DiPlay 等接收端在配对前 /info 已成功；Rust 自动链在
                    // control setup 后停止，不再补发 info。此时必须立即交回宿主，
                    // 否则等 info 事件 30s 超时（v83 DiPlay 实测）。
                    val cachedInfo = rustInfoEvent
                    val usable =
                        cachedInfo != null &&
                            (
                                cachedInfo.optString("deviceID").isNotEmpty() ||
                                    (cachedInfo.optJSONArray("displays")?.length() ?: 0) > 0
                            )
                    if (usable) {
                        pendingSetupInfo = buildSetupInfo(controlJson)
                        if (pendingSetupInfo != null) {
                            log(
                                "AIRPLAY: ✓ 连接序完成（control+cached info，宿主驱动屏流）" +
                                    " eventPort=${pendingSetupInfo!!.eventPort} " +
                                    "timingPort=${pendingSetupInfo!!.timingPort} " +
                                    "display=${pendingSetupInfo!!.displayWidth}x${pendingSetupInfo!!.displayHeight}",
                            )
                            sock.soTimeout = 1_000
                            startSessionPump(handle, input, output)
                            return "{\"op\":\"setupOk\"}"
                        }
                    }
                    // 无缓存 info：等 Rust 自动链补发的 Info 事件。
                }
                "recorded" -> {
                    pendingSetupInfo = buildSetupInfo(controlJson)
                    if (pendingSetupInfo == null) {
                        log("AIRPLAY: recorded 但缺 controlSetup 事件")
                        return "{\"op\":\"setupFail\"}"
                    }
                    log(
                        "AIRPLAY: ✓ 连接序完成（Rust 自动链）eventPort=${pendingSetupInfo!!.eventPort} " +
                            "timingPort=${pendingSetupInfo!!.timingPort} " +
                            "display=${pendingSetupInfo!!.displayWidth}x${pendingSetupInfo!!.displayHeight}",
                    )
                    // G6：屏幕锚消费——NTP 客户端 offset（raw，fw 未除 2 语义）；
                    // 只在样本存在且 |offset|<2s 时应用，异常样本不污染时间戳。
                    val off = timingServer.offsetSecs
                    if (off.isFinite() && kotlin.math.abs(off) < 2.0) {
                        ntpEpochAnchorSecs += Math.round(off)
                        log("TIMING: 会话锚应用 offset=${String.format("%.6f", off)}s")
                    } else {
                        val shown = if (off.isFinite()) String.format("%.6f", off) else "NaN"
                        log("TIMING: offset 未应用（offset=$shown）")
                    }
                    // §10.57：会话生命周期 Rust 泵（控制通道读写单持有者）
                    sock.soTimeout = 1_000
                    startSessionPump(handle, input, output)
                    return "{\"op\":\"setupOk\"}"
                }
                "error" -> {
                    log("AIRPLAY: 连接序错误 - ${json.optString("message")}")
                    return "{\"op\":\"setupFail\"}"
                }
            }
        }
        log("AIRPLAY: 连接序超时")
        return "{\"op\":\"setupFail\"}"
    }

    /** SetupInfo 装配（controlSetup 端口域 + info displays——宿主数据面消费）。 */
    private fun buildSetupInfo(controlJson: org.json.JSONObject?): SetupInfo? {
        val cj = controlJson ?: return null
        val root = rustInfoEvent
        val displays = root?.optJSONArray("displays")
        val display = displays?.optJSONObject(0)
        // fw: 接收端协商结果（enabledFeatures）——旧实现恒空，能力集被丢弃。
        val enabledFeatures = ArrayList<String>()
        cj.optJSONArray("enabledFeatures")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { enabledFeatures += it }
            }
        }
        if (enabledFeatures.isEmpty()) {
            cj.optString("enabledFeaturesHex").takeIf { it.isNotEmpty() }?.let { enabledFeatures += it }
        }
        // fw: /info hidDevices[]（uuid + hidDescriptor）——hidSetInputMode 武装源。
        val hidUuids = ArrayList<String>()
        val hidDescriptors = HashMap<String, ByteArray>()
        root?.optJSONArray("hidDevices")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val uuid = d.optString("uuid")
                if (uuid.isEmpty()) continue
                hidUuids += uuid
                hexToBytes(d.optString("descriptorHex"))?.let { hidDescriptors[uuid] = it }
            }
        }
        if (enabledFeatures.isNotEmpty()) {
            log("AIRPLAY: enabledFeatures=" + enabledFeatures.joinToString(","))
        }
        if (hidUuids.isNotEmpty()) {
            log("AIRPLAY: hidDevices=" + hidUuids.joinToString(",") + "（武装 hidSetInputMode）")
        }
        return SetupInfo(
            eventPort = cj.optInt("eventPort"),
            timingPort = cj.optInt("timingPort"),
            keepAlivePort = cj.optInt("keepAlivePort").takeIf { it != 0 },
            enabledFeatures = enabledFeatures,
            displayWidth = (display?.optLong("widthPixels") ?: 1080L).toInt(),
            displayHeight = (display?.optLong("heightPixels") ?: 2320L).toInt(),
            displayUuid = display?.optString("uuid").orEmpty(),
            maxFps = (display?.optLong("maxFPS") ?: 30L).toInt(),
            hidDeviceUuids = hidUuids,
            hidDescriptors = hidDescriptors,
            // R3 恢复接线（M5.2 起断链——/info 该键无人读；奥迪 3315 声明 true，
            // true = keepAlive 帧须携统计体 opcode=5+bplist）
            keepAliveSendStatsAsBody = root?.optBoolean("keepAliveSendStatsAsBody", false) ?: false,
        )
    }

    /** 会话级 SETUP 装配产物（controlSetup 端口域 + info displays——宿主数据面消费）。 */
    class SetupInfo(
        val eventPort: Int,
        val timingPort: Int,
        val keepAlivePort: Int?,
        val enabledFeatures: List<String>,
        /** 主显示屏参数（/info displays[0]，跟随接收端当前屏幕方向）。 */
        val displayWidth: Int,
        val displayHeight: Int,
        val displayUuid: String?,
        val maxFps: Int,
        /** /info hidDevices[].uuid 列表（0=触摸、1=旋钮；DiPlay 同款编号）。 */
        val hidDeviceUuids: List<String> = emptyList(),
        /**
         * uuid → HID 描述符。媒体键（Consumer Control）的 Array 报告字节是 Usage 数组下标，
         * 必须解析描述符才能得到正确映射（奥迪实测：按下「下一首」收 0x05=usages[5]=0xB5）。
         */
        val hidDescriptors: Map<String, ByteArray> = emptyMap(),
        /**
         * `/info.keepAliveSendStatsAsBody`：屏幕流 keepAlive 帧是否携带统计体
         * （true → opcode=5 + bplist；false → opcode=2 空帧）。奥迪 3315 为 true。
         */
        val keepAliveSendStatsAsBody: Boolean = false,
    )

    /** 屏幕流协商结果。 */
    class ScreenStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
    )

    private var sendIapCount = 0

    /**
     * 手机→车机 iAP2 消息：事件通道 iAPSendMessage 命令。
     * **命令体在 Rust**（fw: setUpAPAccClientEndpointForIdType_block_invoke
     * @0x227892c60——dict{data} → sendCommandInternal）。
     */
    fun sendIap(data: ByteArray): Boolean {
        val result = NativeBridge.nativeAirplaySendIap(controlSessionHandle, data)
        val sent = result.isNullOrEmpty() || !result.contains("\"type\":\"error\"")
        if (!sent) log("EVENT: iAPSendMessage 发送失败(rust) - $result")
        pumpEventOutbound()
        sendIapCount++
        if (sendIapCount <= 10) {
            log(
                "IAPLINK: tx(事件通道) ${data.size}B ok=$sent hex=" +
                    data.joinToString(" ") { "%02x".format(it) }.take(120),
            )
        }
        return sent
    }

    // ---- 事件通道上的虚拟 iAP2 链路 ----
    //
    // 奥迪实测（2026-10-11）：车机通过 iAPSendMessage 发来 iAP2 链路层 marker
    // （ff 55 02 00 ee 10）——它在事件通道上开了一条虚拟 iAP2 链路（type-130 TCP
    // 被车机拒绝：200 但无 dataPort）。EA 数据（触摸 HID）走这条链路。
    // 复用 [io.github.shaonialife.cardeck.iap2.Iap2LinkEngine] 完成握手与帧收发。

    @Volatile
    private var iapLink: NativeTunnelIap2Link? = null

    /**
     * 车机媒体键回调（方向盘/面板的上一曲、下一曲）。
     *
     * 通路：事件通道 → iAPSendMessage → iAP2 控制会话 `0x6802 AccessoryHIDReport`
     * → [ConsumerKeys] 映射成 Android KeyEvent → 本回调。由 SessionController 注入
     * `dispatchMediaKey`（与 HID 直投那条路合并处理）。
     */
    /** 控制通道 TCP 连接失败回执（每轮 connectToEndpoint 全候选失败时一次）——
     *  宿主据此检测网络变化（热点被系统切走→socket 失败是直接证据）并重走加入。
     *  2026-11-04 ENONET 案：热点无上行被系统秒级切回家庭 WiFi，
     *  网络对象存活类校验（capabilities）查不出，socket 失败才查得出。 */
    var onConnectFailed: ((failures: List<String>) -> Unit)? = null

    var onMediaKey: ((Int) -> Unit)? = null

    /** 导航键（iDrive 旋钮/四向拨杆）：需要虚拟屏焦点，由会话层注入 DPAD。 */
    var onNavKey: ((Int) -> Unit)? = null

    /**
     * MIB3 隧道控制会话完成（auth+burst 已发）回调：宿主据此调度 modesChanged
     * 资源重申 burst（AADisplay r17 时序 +1.5/4/12/30s）。082446 定谳：不重申的
     * 话车机全程健康却永不切到 CarPlay 画面。
     */

    /** 视频起流回调（屏幕上第一帧发出后）——实验模式下重申的触发源。 */
    var onStreamingStarted: (() -> Unit)? = null

    /** 隧道链路唯一实例（原生会话薄层，协议行为全在 cardeck-protocol）。 */
    private fun obtainIapLink(): NativeTunnelIap2Link =
        iapLink ?: synchronized(this) {
            iapLink
                ?: NativeTunnelIap2Link(
                    log = { line -> log(line) },
                    sendEventIap = ::sendIap,
                    alive = { socket?.isClosed == false },
                ).also {
                    it.onMediaKey = { keyCode -> onMediaKey?.invoke(keyCode) }
                    it.onNavKey = { keyCode -> onNavKey?.invoke(keyCode) }
                    armTunnelFlow(it)
                    iapLink = it
                }
        }

    /**
     * 隧道链固件编排布防（flow_start——真车隧道 = 新 iAP2 链，车机 SYN 握手
     * 完成后整链重跑 1D00→1D01→AA→通知波；sysdiagnose 2026-10-15 21:14:31
     * 实证）。此前隧道会话未布防——即使握手完成也无 1D00（2026-11-05 定谳
     * 的结构性缺口）。平台值与 BT 链同源（[resolveBluetoothMac] 缓存优先）。
     */
    private fun armTunnelFlow(link: NativeTunnelIap2Link) {
        val ctx =
            context ?: run {
                log("IAPLINK: 无 context——隧道链编排未布防")
                return
            }
        link.startFlow(
            io.github.shaonialife.cardeck.util.DeviceIdentity
                .deviceName(ctx)
                .toByteArray(),
            io.github.shaonialife.cardeck.bt
                .resolveBluetoothMac(ctx, log)
                .toByteArray(),
            io.github.shaonialife.cardeck.bt
                .platformLanguage()
                .toByteArray(),
            io.github.shaonialife.cardeck.bt
                .platformTzOffsetMinutes(),
            io.github.shaonialife.cardeck.bt
                .platformDstFlag(),
        )
        log("IAPLINK: 隧道链编排已布防（flow_start，等车机 SYN）")
    }

    /** 车机 iAPSendMessage 字节入口（SessionController 事件命令处理器调用）。 */
    fun onIapEventBytes(data: ByteArray) {
        obtainIapLink().onCarBytes(data)
    }

    // ---- iAP2 数据隧道（stream 130）数据面（G3 第二刀，2026-11-08）----
    //
    // 130 SETUP 应答 dataPort → Rust attach（编解码接管第二端传输槽，宿主
    // 零钥面）→ TCP 连入 → 泵线程：socket 读 → nativeAirplayTunnelFeed →
    // 返回值（endpoint 出站已封帧密文）→ socket 写；soTimeout 周期 drain
    // （tick/takeEvents 蹦床产物）。fw 锚见 fw::airplay::tunnel 模块头。

    @Volatile
    private var tunnelSocket: java.net.Socket? = null

    @Volatile
    private var tunnelThread: Thread? = null

    /** 隧道数据面已激活（事件通道 iAP 入口让路的判据）。 */
    val tunnelActive: Boolean
        get() = iapLink?.tunnelActive == true

    /** 由 streamSetup 事件（streamType=130）建立隧道数据面。 */
    fun startTunnelDataPlane(streamEvent: org.json.JSONObject): Boolean {
        val port = streamEvent.optInt("dataPort")
        if (port <= 0) {
            log("TUNNEL: 应答无 dataPort（车机拒绝隧道形态）——事件通道 fallback 保持")
            return false
        }
        val h = sessionPumpHandle
        if (h == 0L) return false
        val link = obtainIapLink()
        val iap2 = link.handle
        if (iap2 == 0L) return false
        val attach = NativeBridge.nativeAirplayTunnelAttach(h, iap2)
        if (attach != 0) {
            log("TUNNEL: attach 失败 code=$attach（seed/共享密钥缺失？）")
            return false
        }
        val host =
            activeHost ?: run {
                log("TUNNEL: 无 activeHost——数据面未建立")
                NativeBridge.nativeAirplayTunnelDetach(iap2)
                return false
            }
        return try {
            val sock = java.net.Socket()
            sock.tcpNoDelay = true
            sock.connect(java.net.InetSocketAddress(host, port), 5_000)
            sock.soTimeout = 200
            tunnelSocket = sock
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            val th =
                Thread(
                    {
                        val buf = ByteArray(16 * 1024)
                        try {
                            while (link.handle == iap2 && !sock.isClosed) {
                                val n =
                                    try {
                                        input.read(buf)
                                    } catch (_: java.net.SocketTimeoutException) {
                                        0
                                    }
                                if (n < 0) break
                                if (n > 0) {
                                    val w = NativeBridge.nativeAirplayTunnelFeed(iap2, buf.copyOf(n))
                                    if (w != null && w.isNotEmpty()) out.write(w)
                                }
                                // 周期冲洗：tick/takeEvents 蹦床产物（出站响应）
                                val d = NativeBridge.nativeAirplayTunnelDrain(iap2)
                                if (d != null && d.isNotEmpty()) out.write(d)
                            }
                        } catch (_: Exception) {
                            // socket 失败 = 隧道亡（网络切换/车机拆链）；会话级
                            // 失败回执由既有 onConnectFailed 链路处理
                        } finally {
                            runCatching { sock.close() }
                        }
                    },
                    "iap2-tunnel-pump",
                )
            th.isDaemon = true
            th.start()
            tunnelThread = th
            link.markTunnelActive()
            log("TUNNEL: 数据面建立 host=$host port=$port iap2=$iap2（真车序：隧道链随车机 SYN 自推进）")
            true
        } catch (e: Exception) {
            log("TUNNEL: TCP 连入失败 ${e.message}——事件通道 fallback 保持")
            runCatching { NativeBridge.nativeAirplayTunnelDetach(iap2) }
            false
        }
    }

    /** 拆隧道数据面（会话收尾；第二 iAP2 会话由 [NativeTunnelIap2Link.close] 销毁）。 */
    private fun stopTunnelDataPlane() {
        tunnelThread?.interrupt()
        tunnelThread = null
        runCatching { tunnelSocket?.close() }
        tunnelSocket = null
    }

    /** 音频流协商结果（RTP over UDP；密钥同视频流规则，盐用音频连接 ID）。 */
    class AudioStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val controlPort: Int,
        val streamKey: ByteArray,
        /** 协商出的采样率（Hz）：按车机声明选择，AudioSender 必须用同一值采集。 */
        val sampleRate: Int = 48_000,
    )

    companion object {
        /** NTP 纪元偏移（1900→1970，秒）——墙钟锚计算（fw: NTPClockSetEpoch
         *  锚定域；与 timing.rs respond 的 NTP_EPOCH_OFFSET 同值）。 */
        const val NTP_EPOCH_OFFSET_SECS = 2_208_988_800L

        // D7 基线三常量（audioCandidates 的兜底档；位图/流类型语义在 Rust
        // audio_semantics 模块——R2 壳层定形）。

        /** 媒体音频通道的 audioType（真 iPhone 媒体音频走这条）。 */
        const val AUDIO_TYPE_MEDIA = "media"

        /** 音频格式位：PCM 48000Hz 16bit 立体声（catplay AudioFormat 定义 1<<15）。 */
        const val AUDIO_FORMAT_PCM_48K_STEREO = 1L shl 15

        /**
         * 音频延迟兜底值（ms）：车机 /info 的 audioLatencies 里没给 outputLatencyMicros 时用。
         * catplay 注释：AirPlay 推流最低 250ms 才稳定（clamp 32..2000）——兜底取 250。
         */
        const val AUDIO_LATENCY_FALLBACK_MS = 250L

        /** 配对重试期间调用 [retryTick] 的间隔（次）：每轮回 2s，10 次 ≈ 每 20s 重新公告一次。 */
        private const val RETRY_TICK_ATTEMPTS = 10

        /**
         * UDP keepAlive 间隔。catplay 用 10s，但那是它自家接收端的口径；真车（奥迪
         * MMI 3315）看门狗同为 10s，等长即必输（sleep 抖动让心跳晚 1ms 到）。
         */
        private const val KEEPALIVE_INTERVAL_MS = 5_000L
    }

    /**
     * D7 音频候选（R2 起全在 Rust）：会话 'info' 存档 → fw::airplay::session
     * `InfoReply::audio_candidates`（位图匹配 + media 优先 + 250ms 钳制，
     * Honda/Alpine 定谳语义见 Rust 侧注释）。Kotlin 只做 JSON→AudioProfile
     * 映射；无会话句柄 / info 未取得 / 解析失败 → 协议基线（media+PCM48k）。
     *
     * 调用方必须能**换档重试**：车机常常「接受 SETUP、给端口、照收包，但内部
     * 音频通路起不来」几秒后拆会话（2026-10-19 Honda HFT 实测）。
     */
    fun audioCandidates(preferCompat: Boolean = false): List<AudioProfile> {
        val baseline =
            listOf(
                AudioProfile(AUDIO_TYPE_MEDIA, AUDIO_FORMAT_PCM_48K_STEREO, 48_000, AUDIO_LATENCY_FALLBACK_MS),
            )
        val h = controlSessionHandle
        if (h == 0L) return baseline
        val json =
            runCatching {
                NativeBridge.nativeAirplayAudioCandidates(h, if (preferCompat) 1 else 0)
            }.getOrNull()
        if (json.isNullOrEmpty() || json == "[]") {
            log("AIRPLAY: D7 候选不可用（info 未取得/声明为空）→ 按协议基线尝试")
            return baseline
        }
        val mapped =
            runCatching {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    AudioProfile(
                        audioType = o.optString("audioType"),
                        formatBit = o.optLong("formatBit"),
                        sampleRate = o.optInt("sampleRate"),
                        latencyMs = o.optLong("latencyMs"),
                        streamType = o.optLong("streamType"),
                        codec = AudioCodec.valueOf(o.optString("codec", "PCM")),
                    )
                }
            }.getOrElse {
                log("AIRPLAY: D7 候选解析失败 - ${it.message} → 基线")
                return baseline
            }
        if (mapped.isEmpty()) return baseline
        log("AIRPLAY: 音频候选（Rust D7 选择）=${mapped.map { it.label }}")
        return mapped
    }

    /**
     * 车机身份文本（音频档位记忆的键来源）：只用 /info 里稳定的 model + manufacturer，
     * 蓝牙名/固件串会随重连变化，不能进键。
     * R2 修复：读 Rust info 事件（此前 negotiatedInfo 从未赋值 → 恒 "unknown"，
     * 档位记忆键退化——该记忆已随 R3 退役）。
     */
    fun receiverIdentity(): String {
        val info = rustInfoEvent
        val manufacturer = info?.optString("manufacturer").orEmpty().trim()
        val model = info?.optString("model").orEmpty().trim()
        return "$manufacturer $model".trim().ifBlank { "unknown" }
    }

    /**
     * SETUP #2：协商主屏视频流（type 110）。streamConnectionID 由客户端指定，
     * 接收端开 TCP 数据端口并在应答里返回。
     *
     * **帧密钥 A3 起随 Rust streamSetup 应答交付**（fw: createCryptors 内联
     * 分派 @0x2276c5fd0-6488：配对态 ChaCha（DataStream-Output 钥）；et=NONE
     * → AES-128-CTR（全零会话钥派生，catplay 对照域））——宿主零本地派生，
     * 事件 `cipher` 键选择形态、`streamKey`/`key`+`iv` 携钥。
     */

    /** 屏流的 endpoint 描述（SetupPlan 输入；uuid 来自 /info displays[0]）。 */
    fun screenStreamPlanJson(displayUuid: String?): String {
        val uuid = displayUuid?.takeIf { it.isNotBlank() }
        return if (uuid == null) {
            "[{\"streamType\":110}]"
        } else {
            "[{\"streamType\":110,\"uuid\":\"$uuid\"}]"
        }
    }

    /** SetupPlan 结果：各流 SETUP 事件（按 streamType）+ RECORD 完成。 */
    class PlanResult(
        val streams: Map<Int, org.json.JSONObject>,
        val recorded: Boolean,
    )

    /** 屏 + 可选音频的 endpoint plan JSON（音频格式由 /info 候选档提供）。 */
    fun setupPlanJson(
        displayUuid: String?,
        audio: AudioProfile?,
    ): String {
        val items = ArrayList<String>()
        val uuid = displayUuid?.takeIf { it.isNotBlank() }
        items +=
            if (uuid == null) {
                "{\"streamType\":110}"
            } else {
                "{\"streamType\":110,\"uuid\":\"$uuid\"}"
            }
        if (audio != null) {
            items += "{\"streamType\":${audio.streamType}," +
                "\"audioType\":\"${audio.audioType}\"," +
                "\"audioFormat\":${audio.formatBit}," +
                "\"audioLatencyMs\":${audio.latencyMs}}"
        }
        return items.joinToString(",", "[", "]")
    }

    /** Rust 侧 /info 构造的 endpoint 集合 JSON；空 = /info 未取得。
     *  withTunnel = true 时首条为 iAP2 隧道流 130（真车序，隧道数据面待接）。 */
    fun endpointPlanJsonFromInfo(
        preferCompat: Boolean = false,
        withTunnel: Boolean = false,
    ): String? {
        val h = sessionPumpHandle
        if (h == 0L) return null
        val json = NativeBridge.nativeAirplayPlanFromInfo(h, if (preferCompat) 1 else 0, if (withTunnel) 1 else 0)
        return json.takeIf { it.isNotEmpty() && it != "[]" }
    }

    /** Rust /info 生成的仅音频 plan；空 = /info 未取得。 */
    fun endpointAudioPlanJsonFromInfo(preferCompat: Boolean = false): String? {
        val h = sessionPumpHandle
        if (h == 0L) return null
        val json = NativeBridge.nativeAirplayAudioPlanFromInfo(h, if (preferCompat) 1 else 0)
        return json.takeIf { it.isNotEmpty() && it != "[]" }
    }

    /** 固件式 SetupPlan：control→info 已完成，modes 已发；本方法推进 流→RECORD。 */
    fun runFirmwareSetupPlan(
        planJson: String,
        timeoutMs: Long = 8_000,
    ): PlanResult? {
        val h = sessionPumpHandle
        val sock = socket ?: return null
        if (h == 0L) return null
        controlEventQueue.clear()
        NativeBridge.nativeAirplaySetupPlan(h, planJson.toByteArray(), 1)
        drainRustOutbound(h, sock.getOutputStream())
        NativeBridge.nativeAirplayMarkPlanModes(h)
        drainRustOutbound(h, sock.getOutputStream())
        val deadline = System.currentTimeMillis() + timeoutMs
        val streams = HashMap<Int, org.json.JSONObject>()
        while (System.currentTimeMillis() < deadline) {
            val ev = controlEventQueue.poll(deadline - System.currentTimeMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            when (ev.optString("type")) {
                "streamSetup" -> {
                    val t = ev.optInt("streamType")
                    log("AIRPLAY: SetupPlan stream[$t]=$ev")
                    if (t != 0) streams[t] = ev
                    if (t == 130) {
                        // 真车序：隧道应答即建数据面（TCP 连入 + 泵；屏流
                        // SETUP 前——车机对无隧道的媒体流可能拒绝，G3）
                        startTunnelDataPlane(ev)
                    }
                }
                "recorded" -> {
                    log("AIRPLAY: ✓ SetupPlan 完成（流+RECORD）")
                    return PlanResult(streams, true)
                }
            }
        }
        log("AIRPLAY: SetupPlan 超时/失败")
        return null
    }

    /** 由 SetupPlan 的 streamSetup 事件构造屏流信息（不重复发 SETUP）。 */
    fun screenStreamInfoFromEvent(ev: org.json.JSONObject): ScreenStreamInfo? {
        val dataPort = ev.optInt("dataPort")
        if (dataPort == 0) {
            log("AIRPLAY: 主屏流被拒绝（ev=$ev）")
            return null
        }
        val connectionId =
            ev.optString("streamConnectionID").toLongOrNull()
                ?: (java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L)
        log("AIRPLAY: ✓ 主屏流已接受（SetupPlan）dataPort=$dataPort connId=$connectionId")
        return ScreenStreamInfo(connectionId, dataPort)
    }

    /** 由 SetupPlan 的音频 streamSetup 事件构造预协商音频流。 */
    fun audioStreamInfoFromEvent(
        ev: org.json.JSONObject,
        profile: AudioProfile,
    ): AudioStreamInfo? {
        val dataPort = ev.optInt("dataPort")
        if (dataPort == 0) {
            log("AIRPLAY: 音频流被拒绝（ev=$ev）")
            return null
        }
        val streamKey = hexToBytes(ev.optString("streamKey"))
        if (streamKey == null) {
            log("AIRPLAY: 音频流缺少 streamKey")
            return null
        }
        val connId = ev.optString("streamConnectionID").toLongOrNull() ?: 0L
        return AudioStreamInfo(
            streamConnectionId = connId,
            dataPort = dataPort,
            controlPort = ev.optInt("controlPort"),
            streamKey = streamKey,
            sampleRate = profile.sampleRate,
        )
    }

    /** RECORD：激活会话（接收端 onSessionActive）。路径用相对 `/`（catplay record() 同款）。 */
    fun record(): Boolean {
        // 音频流后加入会话的**再激活**（2026-10-17 奥迪定谳：缺这一步车机回
        // 200 给 dataPort 但不真正播放）。连接序的首个 RECORD 已由 Rust 自动
        // 链完成；此处经 Rust 泵补发（fw: RECORD 每流 SETUP 后重发，
        // Stage2StartTransaction @0x2276cf53c 域）。未建泵（无会话）返回 false。
        val h = sessionPumpHandle
        if (h == 0L) return false
        val ev = NativeBridge.nativeAirplayRecord(h)
        drainRustOutbound(h, socket?.getOutputStream() ?: return false)
        log("AIRPLAY: RECORD 补发（流后加入再激活）ev=$ev")
        return true
    }

    /**
     * 连接事件通道（接收端在 SETUP 应答的 eventPort 上等我们）。
     *
     * M5 迁移：解密/重组/命令解析/自动应答全在 Rust（fw::airplay::event_channel，
     * 应答策略 fw: carEndpoint_handleEventCreatingReply @0x2276b8214——changeModes
     * 200+status+params，空体曾致奥迪黑屏 ~5s 拆链）。
     * [eventsWriteKey]/[eventsReadKey] 直接取 Rust Done 交付的 Events 双钥
     * （fw: CreateEncryptionKeysForContext Events-Salt 族）；**null = 未配对
     * 会话，通道全程明文**（catplay `EventsServer::unencrypted` 同款）。
     * 读取线程把原始字节喂 nativeAirplayEventOnBytes，事件 JSON 分发：
     * command → [onCommand]（hidSendReport 触摸等）、modesChanged → 模式状态镜像。
     */
    fun connectEventChannel(
        eventsWriteKey: ByteArray?,
        eventsReadKey: ByteArray?,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit = {},
    ) {
        if (setup.eventPort == 0) {
            log("EVENT: SETUP 未提供 eventPort，跳过")
            return
        }
        val host = receiverHost() ?: return
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(resolveAddress(host), setup.eventPort), 5_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            // 事件通道同样是长连接：对端消失时要能自己发现（见 TcpLiveness 注释）。
            TcpLiveness.configure(socket, "事件通道", log)
            val wk = eventsWriteKey ?: ByteArray(0)
            val rk = eventsReadKey ?: ByteArray(0)
            val created = NativeBridge.nativeAirplayEventCreate(controlSessionHandle, wk, rk) == 1
            log(
                "EVENT: ✓ 已连接事件通道 $host:${setup.eventPort}" +
                    "（${if (wk.size == 32) "加密(Rust)" else "明文"} created=$created）",
            )
            registerEventCommandChannel(socket)
            Thread({
                val input = socket.getInputStream()
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count =
                            try {
                                input.read(buffer)
                            } catch (_: java.net.SocketTimeoutException) {
                                // 30s 空闲是正常状态（事件通道低频）：仅作唤醒节拍，绝不能退出——
                                // 读取线程退出会被接收端视作事件连接断开并拆除整个会话（含视频/蓝牙）
                                continue
                            }
                        if (count < 0) {
                            // EOF 与 IOException 同径拆链（2026-11-12 审查补）：
                            // 此前 break 正常退出 try 块，catch 不执行 →
                            // 隧道泵/虚拟链路多活到会话收尾才被 close 兜住
                            log("EVENT: 通道关闭（EOF）→ 拆链")
                            throw IOException("EOF")
                        }
                        // 原始字节 → Rust 泵（解密+RTSP 重组+plist 解析+自动应答）→ 事件 JSON
                        val eventsJson =
                            NativeBridge.nativeAirplayEventOnBytes(controlSessionHandle, buffer.copyOf(count))
                        pumpEventOutbound()
                        if (eventsJson.isNullOrEmpty() || eventsJson == "[]") continue
                        val arr = org.json.JSONArray(eventsJson)
                        for (i in 0 until arr.length()) {
                            val ev = arr.optJSONObject(i) ?: continue
                            when (ev.optString("type")) {
                                "modesChanged" -> {
                                    // 车机 changeModes：状态镜像 + 归还窗口回调
                                    // （200+status+params 应答已由 Rust 自动排入出站；
                                    // 语义布尔由 Rust 下沉直给——R2 壳层定形）
                                    val restored =
                                        onCarModesEvent(
                                            ev.optBoolean("phoneCall"),
                                            ev.optBoolean("speechActive"),
                                            ev.optBoolean("screenBorrowed"),
                                        )
                                    if (restored) onModesRestored?.invoke()
                                }
                                "command" -> {
                                    val body = ev.optJSONObject("body") ?: continue
                                    try {
                                        onCommand(jsonToCommandMap(body))
                                    } catch (error: Exception) {
                                        log("EVENT: 命令处理异常 - ${error.message}")
                                    }
                                }
                                "reply" -> {
                                    // 车机对我方命令的应答：不回、不记录（回应答触发乒乓回声）
                                }
                                "error" ->
                                    // fw: NSTCP verify failed 即断（Rust 已摘除通道）——
                                    // 事件通道死 = 整条会话将拆，走 IOException 拆链路径
                                    throw IOException("rust 通道错误: ${ev.optString("message")}")
                            }
                        }
                    }
                } catch (error: IOException) {
                    log("EVENT: 读取结束 - ${error.message}")
                    // 事件通道断开 = 整条会话将拆：虚拟链路的 MFi 静默计时必须停
                    // （断链≠静默，防止把该车所有挑战档位污染成快速跳过）
                    stopTunnelDataPlane()
                    iapLink?.close()
                    iapLink = null
                }
            }, "event-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: IOException) {
            log("EVENT: 连接失败 - ${error.message}")
        }
    }

    /**
     * Rust 事件 JSON → 命令字典（递归）。非空且全为 0..255 整数的数组还原为
     * ByteArray（plist Data 双射——与 Rust plist_to_json 的 Data→整数数组输出
     * 对应；消费方 SessionController 的 hidSendReport.hidReport 按 ByteArray 取）。
     */
    private fun jsonToCommandMap(body: org.json.JSONObject): Map<String, Any?> {
        fun conv(v: Any?): Any? =
            when (v) {
                is org.json.JSONObject -> {
                    val m = linkedMapOf<String, Any?>()
                    v.keys().forEach { key -> m[key] = conv(v.opt(key)) }
                    m
                }
                is org.json.JSONArray -> {
                    val items = (0 until v.length()).map { conv(v.opt(it)) }
                    val allBytes =
                        items.isNotEmpty() &&
                            items.all { (it is Int && it in 0..255) || (it is Long && it in 0..255) }
                    if (allBytes) {
                        items.map { (it as Number).toInt().toByte() }.toByteArray()
                    } else {
                        items
                    }
                }
                else -> v
            }
        return conv(body) as? Map<String, Any?> ?: linkedMapOf()
    }

    private fun ensureConnected(timeoutMillis: Int = 5_000): Boolean {
        if (socket?.isConnected == true && socket?.isClosed == false) return true
        // 快照端点与代数：连接期间主控可能换端点（0x4301 晚到），
        // 若用旧地址建好连接再被请求，会一直连错机器到本轮超时。
        val target = endpoint
        val generation = endpointGeneration
        val candidates = EndpointHosts.candidates(target.ipAddresses)
        if (candidates.isEmpty()) {
            log("AIRPLAY: 0x4301/ mDNS 未提供可用地址，无法连接")
            return false
        }
        if (target.airPlayPort <= 0) {
            log("AIRPLAY: 端口未知（${target.airPlayPort}），无法连接")
            return false
        }
        val failures = ArrayList<String>(candidates.size)
        for (host in candidates) {
            val address =
                try {
                    resolveAddress(host)
                } catch (error: Exception) {
                    failures += "$host（解析失败：${error.message}）"
                    continue
                }
            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(address, target.airPlayPort), timeoutMillis)
                if (generation != endpointGeneration) {
                    // 连接途中端点已更新：丢弃这条旧连接，下一轮按新地址重连
                    runCatching { newSocket.close() }
                    return false
                }
                // 读由独立读线程负责（阻塞等数据），请求超时由待响应表的 poll 控制；
                // 这里若留 soTimeout，读线程会周期性收到 SocketTimeoutException 而退出
                newSocket.soTimeout = 0
                // 对端健康检查：内核默认 keepalive 要等 2 小时才开始探测，而车机熄火/
                // 走出热点时链路常不发 RST，读线程会一直挂着（画面冻结、看门狗不触发）。
                TcpLiveness.configure(newSocket, "控制通道", log)
                socket = newSocket
                activeAddress = address
                activeHost = addressLiteral(address)
                log("AIRPLAY: TCP 已连通 $activeHost:${target.airPlayPort}（本机 ${newSocket.localAddress.hostAddress}）")
                return true
            } catch (error: IOException) {
                failures += "$host（${error.message}）"
            }
        }
        log(
            "AIRPLAY: 连接失败 - ${failures.joinToString("；")}\n" +
                "  ↳ 若反复失败：确认手机当前连的是接收端热点 \"${target.ssid}\"，" +
                "而不是家里/公司 WiFi（绑定到的网络不对时到达不了车机网段）",
        )
        onConnectFailed?.invoke(failures)
        return false
    }

    /**
     * 解析连接地址。IPv6 link-local 必须带 scope（`%wlan0`）才能被 Java 使用：
     * mDNS 返回的通常是带 scope 的地址，而 0x4301 里的 `fe80::…` 不带——这里补上
     * 当前 WiFi 接口，否则 `connect` 会报 Network is unreachable。
     */
    private fun resolveAddress(host: String): InetAddress {
        val address = InetAddress.getByName(host)
        if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0) {
            val zone = host.substringAfter('%', "").takeIf { it.isNotEmpty() }
            val iface = zone?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() } ?: wifiInterface()
            if (iface != null) {
                return Inet6Address.getByAddress(null, address.address, iface)
            }
        }
        return address
    }

    /** 找承载车机热点流量的接口（IPv6 link-local scope 用）。 */
    private fun wifiInterface(): NetworkInterface? {
        val candidates =
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            }.getOrDefault(emptyList())
        return candidates.firstOrNull { nic ->
            nic.isUp &&
                !nic.isLoopback &&
                (nic.name.startsWith("wlan") || nic.name.startsWith("ap") || nic.name.startsWith("eth"))
        } ?: candidates.firstOrNull { nic -> nic.isUp && !nic.isLoopback }
    }

    /**
     * 地址文本：IPv6 链路本地必须带 scope 才能被后续 `InetAddress.getByName` 使用。
     * 统一输出**数字 scope id**（`fe80::1%3`）——接口名形式在不同 ROM 上的解析支持不一致。
     */
    private fun addressLiteral(address: InetAddress): String {
        val text = address.hostAddress ?: return address.toString()
        if (address is Inet6Address && address.scopeId != 0) {
            return "${text.substringBefore('%')}%${address.scopeId}"
        }
        return text
    }

    /** 写侧串行：CSeq 分配 + 加密器写计数 + socket 写必须原子（读侧在独立线程）。 */
    private val rtspWriteLock = Any()

    // ---- 会话时钟锚 ----

    /** 会话墙钟锚（NTP-1900 秒 @ 进程时钟零点；runFlow 每连接轮刷新）。 */
    @Volatile
    var ntpEpochAnchorSecs: Long = 0L
        private set

    /** G8：初始音量（dB；NaN = 未收到/未请求）。 */
    @Volatile
    var initialVolumeDb: Double = Double.NaN
        private set

    // ---- 身份持久化 ----

    private fun loadOrCreateIdentity(): ControllerIdentity {
        val preferences = context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val storedId = preferences?.getString("pairingId", null)
        val storedPrivate = preferences?.getString("privateKey", null)
        // A9 下沉：ed25519 密钥域在 Rust（nativePairingIdentityKeyPair——
        // seed 推导公钥 / null 随机生成）。持久化（平台 KV）留本类。
        val keyPair =
            NativeBridge.nativePairingIdentityKeyPair(
                storedPrivate?.let { Base64.getDecoder().decode(it) },
            ) ?: throw IllegalStateException("身份密钥生成失败（native）")
        val privateKey = keyPair.copyOfRange(0, 32)
        val publicKey = keyPair.copyOfRange(32, 64)
        if (storedId != null && storedPrivate != null) {
            return ControllerIdentity(storedId, privateKey, publicKey)
        }
        val pairingId = UUID.randomUUID().toString()
        preferences
            ?.edit()
            ?.putString("pairingId", pairingId)
            ?.putString("privateKey", Base64.getEncoder().encodeToString(privateKey))
            ?.apply()
        return ControllerIdentity(pairingId, privateKey, publicKey)
    }

    /** 本进程 boot 关联 UUID（fw: APSCopyBootUUID 的宿主等价数据初值）。 */
    private val bootUuid: String by lazy { UUID.randomUUID().toString() }

    /** fw: appendControlSetupRequest 恒写身份键注入（平台值）。 */
    private fun applyPlatformIdentity(handle: Long) {
        val mac = DeviceIdentity.cachedBluetoothMac() ?: "02:00:00:00:00:00"
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "iPhone"
        val name = context?.let { DeviceIdentity.deviceName(it) } ?: model
        NativeBridge.nativeAirplaySetIdentityEx(
            handle,
            mac.toByteArray(),
            mac.toByteArray(),
            model.toByteArray(),
            name.toByteArray(),
            (Build.DISPLAY ?: "").toByteArray(),
            bootUuid.toByteArray(),
            "iOS".toByteArray(),
            (Build.VERSION.RELEASE ?: "").toByteArray(),
        )
        val stored = storedReceiverIdentity ?: loadReceiverIdentity().also { storedReceiverIdentity = it }
        stored?.publicKey?.takeIf { it.size == 32 }?.let {
            NativeBridge.nativeAirplaySetPeerPublicKey(handle, it)
        }
    }

    /** 配对库键：按车机区分（多台车不能共用一把对端公钥）。 */
    private fun receiverKey(): String {
        val e = endpoint
        val id = e.deviceIdentifier?.trim().orEmpty()
        if (id.isNotEmpty()) return id
        val ssid = e.ssid.trim()
        return if (ssid.isNotEmpty()) ssid else "default"
    }

    /** fw: SavePeer 持久化（identifier + 长期公钥，按车机分键）。 */
    private fun saveReceiverIdentity(identity: ReceiverIdentity) {
        if (identity.publicKey.size != 32 || identity.identifier.isEmpty()) return
        storedReceiverIdentity = identity
        val key = receiverKey()
        context
            ?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("receiverId:$key", identity.identifier)
            ?.putString("receiverPub:$key", Base64.getEncoder().encodeToString(identity.publicKey))
            ?.apply()
    }

    private fun loadReceiverIdentity(): ReceiverIdentity? {
        val prefs = context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE) ?: return null
        val key = receiverKey()
        val ident = prefs.getString("receiverId:$key", null) ?: return null
        val pub =
            prefs
                .getString("receiverPub:$key", null)
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                ?: return null
        if (pub.size != 32) return null
        return ReceiverIdentity(ident, pub)
    }

    // ---- 工具 ----

    /**
     * 接收端地址（事件通道 / iAP 隧道 / 视频 / 音频共用）。
     *
     * 优先用**配对时真正连通**的那个地址：多地址端点里 IPv4 可能不可达（奥迪热点
     * 常只给链路本地 IPv6），其它通道若各自回头挑 IPv4 就会「控制通道通了、视频通不了」。
     */
    private fun receiverHost(): String? = activeHost ?: EndpointHosts.candidates(endpoint.ipAddresses).firstOrNull()

    /**
     * 会话级 SETUP 自报的 deviceID（接收端用于识别「这是哪台 iPhone」）。
     *
     * 必须是本机真实蓝牙 MAC：WinPlay 2026-10-19 实测按用户选中的 iPhone 蓝牙
     * 地址过滤，占位/假值一律 403；Audi MIB3 与 DiPlay 不校验，所以旧写死值
     * 一直没暴露问题。BT 阶段必然已解析并缓存（0x4300/0x4E0E 也依赖它），
     * 这里优先读缓存避免再起 shell；缓存缺失才走一次阻塞解析。
     */
    private fun typeName(value: Any?): String =
        when (value) {
            null -> "null"
            is ByteArray -> "data(${value.size}B)"
            is List<*> -> "list(${value.size})"
            is Map<*, *> -> "dict(${value.size})"
            is String -> "str(${value.length})"
            else -> value.javaClass.simpleName
        }

    private fun logInfoSummary(root: Map<*, *>) {
        // 顶层字段全量清单（键: 类型）——车机把能力藏在哪个键下一目了然，避免只认得
        // Apple 官方那几个键（宝马 iD9 的 HID 声明可能就在我们没解析的键上）
        log(
            "AIRPLAY: /info 顶层字段(${root.size})=" +
                root.entries.joinToString(" ") { (key, value) -> "$key:${typeName(value)}" },
        )
        // 任何名字里带 hid 的键都完整打印（设备清单 + 描述符是触摸通路的前提）
        root.entries
            .filter { (key, _) -> key.toString().contains("hid", ignoreCase = true) }
            .forEach { (key, value) -> log("AIRPLAY: /info $key=$value") }
        // 车机资源/应用状态（2026-10-24 8015 定谳）：通话/语音/借用期间 modesChanged
        // 会被资源仲裁拒绝（HTTP 仍 200，拒绝在响应体）——重申必须冻结至归还。
        // 语义布尔由 Rust 下沉（info 事件 modes 对象直给——R2 壳层定形）
        (root["modes"] as? Map<*, *>)?.let { modes ->
            modesState =
                ModesState(
                    modes["phoneCall"] as? Boolean ?: false,
                    modes["speechActive"] as? Boolean ?: false,
                    modes["screenBorrowed"] as? Boolean ?: false,
                )
            if (modesState.busy) {
                log(
                    "AIRPLAY: /info modes——车机通话=${modesState.phoneCall} " +
                        "语音=${modesState.speechActive} 主屏借用=${modesState.screenBorrowed}" +
                        "（重申冻结至归还）",
                )
            }
        }
        val features = root["features"] as? Long
        log(
            "AIRPLAY: /info 摘要 name=${root["name"]} model=${root["model"]} " +
                "deviceID=${root["deviceID"]}" +
                if (features != null) " features=0x${features.toString(16)}" else "",
        )
        (root["displays"] as? List<*>)?.forEachIndexed { index, display ->
            log("AIRPLAY: /info displays[$index]=$display")
            if (display is Map<*, *>) {
                log(
                    "AIRPLAY: /info displays[$index] 键值=" +
                        display.entries.joinToString(" ") { (key, value) ->
                            "$key=${value.toString().take(80)}"
                        },
                )
            }
        }
        // 音频能力逐条打印（整行 /info 的日志会被截断，audioFormats 恰好落在截断处）：
        // 各车机为 type=100 声明的 audioType 档位与位图差异很大，协商依赖这些明细
        (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
            val format = entry as? Map<*, *> ?: return@forEachIndexed
            val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
            val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
            log(
                "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                    "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                    "（PCM48k立体声 bit15=${if ((output and (1L shl 15)) != 0L) "✓" else "✗"}）",
            )
        }
        (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
            val latency = entry as? Map<*, *> ?: return@forEachIndexed
            log(
                "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                    "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val cleaned = hex.trim()
        if (cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/**
 * 配对产物密钥（fw: `_coreUtilsKeyHolder_CreateEncryptionKeysForContext`
 * @0x22773e938——Control-Salt 族 HKDF-SHA512 双钥；sharedSecret 沿用旧接缝
 * 恒空数组，音频路径按 null 语义处理）。原 PairVerifyClient.ControlKeys，
 * M5.2 起 PairingClients.kt 全域移入 Rust（fw::airplay::session），类型随迁。
 */
data class ControlKeys(
    val phoneWriteKey: ByteArray,
    val phoneReadKey: ByteArray,
    val sharedSecret: ByteArray,
    /** Events 双钥（fw: CreateEncryptionKeysForContext Events-Salt 族，Rust Done 交付）——
     *  事件通道加密态直用，Kotlin 不再本地 HKDF（M5：事件通道加解密全 Rust）。 */
    val eventsWriteKey: ByteArray = ByteArray(0),
    val eventsReadKey: ByteArray = ByteArray(0),
)

/**
 * 配对库对端身份（fw: `_PairingSessionSavePeer` 入库内容——setup M6 的
 * identifier + 长期公钥；verify 的 FindPeerEx 取用）。原
 * PairSetupClient.ReceiverIdentity，随 M5.2 类型迁址。
 */
data class ReceiverIdentity(
    val identifier: String,
    val publicKey: ByteArray,
)

/** 控制器长期身份（ed25519 seed + 配对 ID；原 PairingClients.kt，M5.2 类型随迁）。 */
data class ControllerIdentity(
    val pairingId: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/** fw: session+0x64 发送端主形态（createAirPlayPairingSession @0x2277AD69C）。 */
const val FLAGS_AIRPLAY_SENDER: Int = 0xC
