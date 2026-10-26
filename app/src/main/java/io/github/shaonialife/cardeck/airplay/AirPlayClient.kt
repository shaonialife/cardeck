package io.github.shaonialife.cardeck.airplay

import android.content.Context
import android.os.SystemClock
import io.github.shaonialife.cardeck.audio.AudioCodec
import io.github.shaonialife.cardeck.audio.AudioProfile
import io.github.shaonialife.cardeck.audio.AudioSender
import io.github.shaonialife.cardeck.audio.AudioTuning
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.net.EndpointHosts
import io.github.shaonialife.cardeck.net.TcpLiveness
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
 * AirPlay 客户端（Phase 2）：
 *  - /info 能力清单拉取与 bplist 解析
 *  - /pair-setup + /pair-verify 完整配对（持久 RTSP 连接上顺序 POST TLV8）
 * 控制器长期身份（ed25519 + 配对 ID）首次生成后存入 SharedPreferences。
 * 后续在此叠加 SETUP/RECORD 流媒体会话。
 */
class AirPlayClient(
    initialEndpoint: Iap2SenderSession.CarPlayEndpoint,
    private val log: (String) -> Unit,
    private val context: Context? = null,
    /**
     * 车机链路代际（蓝牙侧 0x1D01 画像判定，见 [ReceiverProfile.linkGenerationOf]）。
     * 2026-10-23 二次定谳后仅用于日志——marker 协商策略全代际统一（不完成链路协商
     * 车机 ~10s 拆链；切屏靠协商完成后的 modesChanged 重申）。
     */
    private val linkGeneration: ReceiverProfile.LinkGeneration? = null,
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
    private var cSeq = 0

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

    /** pair-verify 之后启用：所有后续 RTSP 报文整体加密封装。 */
    private var cipher: ControlCipher? = null

    /** 握手阶段缓存的 /info（音频协商用：audioType/格式/延迟按车机声明选）。 */
    @Volatile
    private var negotiatedInfo: Map<*, *>? = null

    // ---- 对外流程 ----

    /** pair-setup 是否被 403 拒绝（真车特征）；主控据此决定是否使用 BT 退役 hack。 */
    @Volatile
    var pairSetupRejected: Boolean = false
        private set

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
        private set

    /** 本轮是否真正开始过配对（TCP 连上过接收端）。DiPlay 断链 hack 的启用条件。 */
    @Volatile
    var attemptedPairing: Boolean = false
        private set

    /**
     * 接收端**根本不支持** HomeKit 配对（/pair-setup 回 404/405/501 或 455）。
     *
     * 这类是 pre-HomeKit 老车机（CarPlay 210.81 时代，catplay 的接收端分类 1）：
     * 它们只接受 `et=NONE` 明文会话 + AES-CTR 屏幕流。继续在配对窗口里重试
     * 120 秒毫无意义——实测用户看到的就是「一直连不上」（2026-10-19 奥迪 4xxx）。
     */
    @Volatile
    var pairingUnsupported: Boolean = false
        private set

    /** 控制通道是否已加密（pair-verify 完成）。false = 明文会话（老车机 / 未配对）。 */
    val controlEncrypted: Boolean get() = cipher != null

    /**
     * 跳过 HomeKit 配对，直接走 `et=NONE` 明文会话（pre-HomeKit 老车机）。
     *
     * 依据是 `_airplay._tcp` TXT features 未声明 bit38 HK_PAIRING_AND_ENCRYPT
     * （catplay 的接收端分类 1：「pre-HomeKit: has no /pair-setup and no
     * HK_PAIRING_AND_ENCRYPT Bonjour flag (v210 era +/-)」）。
     * 不试 pair-setup 可以省掉注定失败的请求；若 TXT 判断有误，配对仍会在
     * SETUP 被拒后由调用方回退（不会比旧行为更差）。
     */
    fun skipPairing(reason: String) {
        pairingUnsupported = true
        log("AIRPLAY: 跳过 HomeKit 配对（$reason）——使用 et=NONE 明文会话 + AES-CTR 屏幕流")
    }

    /** 重新建立控制通道（配对失败/跳过配对后，SETUP 之前必须有一次新连接）。 */
    fun reconnect(timeoutMillis: Int = 5_000): Boolean = ensureConnected(timeoutMillis)

    /**
     * 明文 SETUP 被拒后的「加密升级」重试（catplay `handle_setup_result` 同义）：
     * 先按 pre-HomeKit 车机走 `et=NONE`，若 SETUP 被拒且此前没试过配对，
     * 就补做一次 pair-setup/pair-verify 再 SETUP。TXT features 判断错误时不至于直接失败。
     */
    fun retryWithPairing(windowMillis: Long = 30_000): PairVerifyClient.ControlKeys? {
        pairingUnsupported = false
        close()
        log("AIRPLAY: 明文 SETUP 未通过——尝试加密升级（补做 HomeKit 配对）")
        return runPairing(windowMillis)
    }

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

    fun runPairing(windowMillis: Long = PAIRING_WINDOW_MILLIS): PairVerifyClient.ControlKeys? {
        pairingInProgress = true
        try {
            return runPairingLoop(windowMillis)
        } finally {
            pairingInProgress = false
        }
    }

    private fun runPairingLoop(windowMillis: Long): PairVerifyClient.ControlKeys? {
        val identity = loadOrCreateIdentity()
        log("AIRPLAY: 控制器身份 pairingId=${identity.pairingId}")
        // 车机 AirPlay 窗口是「用户点车机上的手机图标 / 收到 ctrl 邀请后才开」的
        // （Audi 实测：端口从 Connection refused 变为开放）。因此重试采用时限制：
        // 默认 120s 内每 2s 试一次——用户在车机上点一下的等待不会被提前放弃。
        val deadline = System.currentTimeMillis() + windowMillis
        var attempt = 0
        while (System.currentTimeMillis() < deadline && !cancelled) {
            attempt++
            // 每轮重试前吸收一次 mDNS 新结果：网关兜底地址连不通时，车机的
            // _airplay._tcp 可能已经在后台发现到，及时换过去比空转 120s 强。
            endpointRefresher?.invoke(endpoint)?.let { refreshed ->
                updateEndpoint(refreshed, "配对期间 mDNS 刷新")
            }
            // 低频钩子：主控据此重新公告 ctrl mDNS（车机 Bonjour 可能漏掉首次公告）
            if (attempt == 1 || attempt % RETRY_TICK_ATTEMPTS == 0) {
                runCatching { retryTick?.invoke(attempt) }
            }
            close()
            if (!ensureConnected(2_000)) {
                if (attempt == 1 || attempt % 5 == 0) {
                    log("AIRPLAY: 车机 AirPlay 端口未开（等待你点车机上的手机图标 / 车机发 ctrl 邀请）…第 $attempt 次重试")
                }
                Thread.sleep(2_000)
                continue
            }
            attemptedPairing = true
            val transport = TlvTransport { path, body -> postPairing(path, body) }
            try {
                val setupStatus =
                    try {
                        PairSetupClient(identity, transport, log).run()
                        null
                    } catch (error: IOException) {
                        val message = error.message.orEmpty()
                        val forbidden = message.contains("403")
                        if (forbidden) {
                            pairSetupRejected = true
                            pairingForbidden = true
                        }
                        val unsupported = PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }
                        log(
                            "AIRPLAY: pair-setup " +
                                when {
                                    forbidden -> "被 403 拒绝（车机不接受匿名配对——iAP2 已完成信任）→ 转已配对快路径"
                                    unsupported -> "不被支持（$message）——该接收端是 pre-HomeKit 老车机，转明文会话"
                                    else -> "失败（$message）"
                                },
                        )
                        if (unsupported) {
                            pairingUnsupported = true
                            close()
                            return null
                        }
                        if (forbidden) "403" else "failed"
                    }
                // 403 后旧连接的状态机不可用：换全新连接走快路径
                if (setupStatus == "403") {
                    close()
                    if (!ensureConnected(2_000)) continue
                    val fastPathTransport = TlvTransport { path, body -> postPairing(path, body) }
                    val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                    val keys = PairVerifyClient(identity, fastPathTransport, log).run(receiverStaticKey)
                    cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                    log("AIRPLAY: 控制通道加密已启用（已配对快路径）")
                    // 认证缺口探针：iPhone 在 pair-verify 之后做两轮 /fp-setup（我们缺失）
                    runCatching { probeFpSetup() }
                    return keys
                }
                val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                val keys = PairVerifyClient(identity, transport, log).run(receiverStaticKey)
                // pair-verify 之后双方立即启用加密（接收端在 M4 应答后开启）
                cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                log("AIRPLAY: 控制通道加密已启用")
                // 认证缺口探针（见 probeFpSetup 注释）
                runCatching { probeFpSetup() }
                return keys
            } catch (error: IOException) {
                val message = error.message.orEmpty()
                // verify/认证段的 403 与 pair-setup 403 同源（车机信任已建立），
                // 见 pairingForbidden 注释：标记后主控不再断 BT / 不降级明文
                if (message.contains("403")) pairingForbidden = true
                log("AIRPLAY: 配对第 $attempt 次尝试失败 - $message")
                if (PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }) {
                    pairingUnsupported = true
                    log("AIRPLAY: 接收端不提供 HomeKit 配对通道——停止重试，转明文会话（et=NONE）")
                    close()
                    return null
                }
                // 403 退避（真机抓包：车机首连被拒后需 18~20s 才进入可接受状态，
                // 期间高频重试除徒增车机负载外可能一直重置其就绪状态机）；
                // 其余失败维持快速轮询等端口开放
                Thread.sleep(pairingRetryBackoffMs(message))
            }
        }
        log("AIRPLAY: 配对最终失败（接收端 RTSP 无应答）")
        return null
    }

    /**
     * /fp-setup 探针（2026-10-25，奥迪 8015/3315 不切屏排查）。
     *
     * **背景**：iPhone sysdiagnose（真车成功会话）显示 pair-verify 之后还有两轮
     * `/fp-setup`：33B → **711B**（内含 607B MFi 证书 + 64B 挑战 + 64B 签名，
     * iPhone 用 4 张 Apple MFi 根证书验签）→ 785B → 133B。车机首轮 403 正是拒在
     * 这一步（`mfiAuthentication_AuthenticateEndpoint → 200403 Forbidden →
     * Stage 1 failed → Activation Failed`）。我们从未发过这一步，SETUP/RECORD/
     * 推流却全 200——车机很可能因此永不把会话当作「已认证 CarPlay」去显示。
     *
     * **本函数只是探针**：真实第 2 轮要用 Apple MFi 私钥对车机挑战签名，我们无法伪造。
     * 目标 = 确认车机是否暴露 `/fp-setup`、接受哪种请求形态、返回什么。
     * 按 catplay 接收端解析（`FairPlaySession::setup`：请求 16B、mode 在 `[14]`、
     * 应答 142B、应答 mode 在 `[13]`）逐个 mode 试，全部打日志。
     */
    fun probeFpSetup() {
        if (cipher == null) {
            log("FP-SETUP: 跳过（未配对无加密通道）")
            return
        }
        for (mode in 0..3) {
            // 候选 A：FPLY + version + 01 + 01 + 000000 + len(0x10) + 0000 + mode + 00
            val candidate =
                byteArrayOf(
                    0x46,
                    0x50,
                    0x4C,
                    0x59,
                    0x03,
                    0x01,
                    0x01,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x10,
                    0x00,
                    0x00,
                    mode.toByte(),
                    0x00,
                )
            try {
                val (statusLine, body) = request("POST", "/fp-setup", candidate, timeoutMillis = 4_000)
                log(
                    "FP-SETUP: mode=$mode 请求 16B → [$statusLine] resp=${body.size}B" +
                        if (body.isNotEmpty()) {
                            " head=${body.take(32).joinToString(" ") { "%02x".format(it) }}"
                        } else {
                            ""
                        },
                )
                if (statusLine.contains("200") && body.size >= 16) {
                    log("FP-SETUP: ✓ 车机暴露 /fp-setup（mode=$mode）——可作为认证缺口修复的落点")
                    return
                }
            } catch (error: IOException) {
                log("FP-SETUP: mode=$mode 失败 - ${error.message}")
            }
        }
        log("FP-SETUP: 四种 mode 均未得到 200——车机可能不暴露该端点（或需要特定期望形态）")
    }

    /** 拉取并解析 /info（注意：接收端在活跃会话期间可能不应答）。 */
    fun fetchInfo(timeoutMillis: Int = 5_000): Map<*, *>? {
        if (!ensureConnected()) return null
        return try {
            val (statusLine, body) = requestInfo(timeoutMillis)
            log("AIRPLAY: ✓ /info [$statusLine] body=${body.size} 字节")
            if (body.isEmpty()) return null
            val root = BinaryPlist.parse(body)
            if (root is Map<*, *>) {
                logInfoSummary(root)
                root
            } else {
                null
            }
        } catch (error: IOException) {
            log("AIRPLAY: /info 失败 - ${error.message}")
            null
        }
    }

    /**
     * KeepAlive UDP 心跳（对照 catplay KeepAliveClient）：向车机 keepAlivePort 发 4 字节
     * 随机 UDP；协议无应答。**缺失时车机 10s 看门狗拆整轮会话**。
     *
     * 间隔取 **5s** 而不是 catplay 的 10s：10s 与车机看门狗等长，`Thread.sleep` 的
     * 毫秒级抖动会让每次心跳都晚到 deadline 之后 1ms（2026-10-19 奥迪 3315 两轮实测：
     * ping #2 距 #1 恰好 10.001s，随后 213~247ms 内整轮被 reset，RECORD 后 10.2s）。
     * catplay 的 10s 只适用于它自家接收端；对真车必须留出至少一半的余量。
     */
    fun startKeepAlive(
        host: String,
        port: Int,
    ) {
        if (port <= 0) return
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
                    val payload = ByteArray(4).also(java.security.SecureRandom()::nextBytes)
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

    /**
     * RTSP /feedback 心跳（对照 catplay FEEDBACK_INTERVAL=1000ms）：每秒 POST 空体
     * /feedback——接收端的活动看门狗。RTSP 事务由 [rtspLock] 串行化。
     */
    fun startFeedbackLoop() {
        Thread({
            var sent = 0
            while (isSessionAlive()) {
                try {
                    val (status, body) = request("POST", "/feedback", ByteArray(0))
                    sent++
                    if (sent <= 6 || sent % 30 == 0) {
                        log("AIRPLAY: feedback #$sent [$status]${if (body.isNotEmpty()) " body=${body.size}B" else ""}")
                    }
                    // 车机反馈里带各流状态（收没收到、是否欠载）：音频无声时这是最直接的
                    // 判据——能区分「车机没收到包」与「收到但不播」（2026-10-17）。
                    // 前 8 次全打（约覆盖起流后的 8s，正好是 6.5s 拆链看门狗窗口）：
                    // 音频流的 sampleTime 会不会从 0 往前走，是判断「包有没有被消费」的硬证据。
                    if (body.isNotEmpty() && (sent <= 8 || sent % 30 == 0)) {
                        logFeedbackBody(body)
                    }
                } catch (_: IOException) {
                    break // 连接已断（会话结束）
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "airplay-feedback").apply {
            isDaemon = true
            start()
        }
    }

    /** 解析并打印 /feedback 应答（bplist）。字段对照 catplay `StreamFeedback`：
     *  sr / type / streamConnectionID / sampleTime / timestamp / timestampRawNs
     *  （没有包计数——车机回的是自己侧的播放时钟，可据此判断音频流是否被推进）。 */
    private fun logFeedbackBody(body: ByteArray) {
        val root =
            runCatching { BinaryPlist.parse(body) as? Map<*, *> }.getOrNull()
        if (root == null) {
            log("AIRPLAY: feedback body 解析失败（${body.size}B）hex=${body.joinToString("") { "%02x".format(it) }}")
            return
        }
        val streams = root["streams"] as? List<*>
        if (streams == null) {
            log("AIRPLAY: feedback 无 streams 键（keys=${root.keys}）全字段=$root")
            return
        }
        streams.forEachIndexed { index, entry ->
            val stream = entry as? Map<*, *> ?: return@forEachIndexed
            log(
                "AIRPLAY: feedback.stream[$index] type=${stream["type"]} sr=${stream["sr"]} " +
                    "connId=${stream["streamConnectionID"]} sampleTime=${stream["sampleTime"]} " +
                    "ts=${stream["timestamp"]} tsRawNs=${stream["timestampRawNs"]} 全字段=$stream",
            )
        }
    }

    /** 心跳线程的存活判据：RTSP socket 仍连着。 */
    private fun isSessionAlive(): Boolean = socket?.isClosed == false

    fun close() {
        runCatching { socket?.close() }
        socket = null
        activeHost = null
        activeAddress = null
        // 新连接总是从**明文**开始（pair-verify 成功后才切加密）：旧连接的 cipher 必须
        // 清掉，否则重连后的读线程会把明文应答当密文帧解，后续所有请求超时。
        cipher = null
        synchronized(rtspWriteLock) {
            readerSocket = null
            readerThread = null
            readerGeneration++
        }
        failAllPending("控制通道关闭")
        runCatching { iapTunnel?.socket?.close() }
        iapTunnel = null
        iapLink = null
        // 注意：controlIapTx 不在这里 shutdown——close() 会被同实例的重试路径
        // （retryWithPairing 等）复用，杀掉执行器会废掉重连后的 MIB3 出站；
        // 守护线程常驻的代价可忽略（每客户端实例一根）
    }

    /**
     * 声明 HID 输入模式（hidSetInputMode，FromDevice 方向——手机→车机的触摸启用命令）。
     * 对照 catplay CommandHidSetInputMode {hidInputMode u8, uuid string}；
     * uuid 为 /info hidDevices[].uuid 的十六进制串（DiPlay sendHidReport 同款 uid.toString(16)）。
     * 奥迪 display features=0x08（高保真触摸）+ hidDevices 齐备，触摸无报文的最大嫌疑
     * 是缺这一步武装。
     */
    fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int = 0,
    ): Boolean {
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "hidSetInputMode",
                    "params" to
                        linkedMapOf<String, Any?>(
                            "hidInputMode" to mode.toLong(),
                            "uuid" to deviceUuidHex,
                        ),
                ),
            )
        log("AIRPLAY: hidSetInputMode(事件通道) uuid=$deviceUuidHex mode=$mode ok=$sent")
        return sent
    }

    /**
     * 声明资源归属（modesChanged）：手机持有主屏 + 主音频（对照 catplay assert_modes）。
     * 真车验证的顺序：modesChanged → 屏幕流 SETUP。跳过此声明时 Audi MIB3 对
     * 屏幕流 SETUP 直接 400（资源仲裁未通过）。appStates：话机/导航归 None（0）、
     * 语音 None（-1）；resources：主屏(1)/主音频(2)，entity=permanent=Controller(1)。
     */

    // ---- 事件通道命令发送（对照 DiPlay sendCommandLocked）----
    // 车机命令双向都走事件通道 socket 上的 POST /command（不是 RTSP 控制连接！）：
    // iAPSendMessage（虚拟链路字节）、hidSetInputMode、modesChanged 等。
    // 异步 fire-and-forget：不等应答（DiPlay 同款写法），独立 CSeq 计数。

    @Volatile
    private var eventCommandSocket: Socket? = null

    /** null = 明文事件通道（et=NONE 的老车机会话，catplay `EventsServer::unencrypted`）。 */
    @Volatile
    private var eventCommandCipher: ControlCipher? = null

    private val eventCommandLock = Any()
    private var eventCseq = 0

    /** 未知请求（非 /command 的合法请求）累计数：前 10 条留痕后静默。 */
    @Volatile
    private var unknownEventRequests = 0

    /** /command 里 plist 解不出来的累计数：前 8 条带原始 hex 留痕（排查车机命令通路）。 */
    @Volatile
    private var badEventCommands = 0

    /**
     * 解不出来的 /command 的「体型指纹」（长度 + 首 8 字节）。
     *
     * 车机一般每秒重发同一批命令，按指纹去重后只对**新出现**的体型做全文取证，
     * 既不刷屏又不会漏掉任何一类（此前只按条数截断，换个车型就只看到前 8 条）。
     */
    private val badEventSignatures = LinkedHashSet<String>()

    /** 事件通道建立后由 connectEventChannel 调用。cipher=null 表示明文通道。 */
    private fun registerEventCommandChannel(
        socket: Socket,
        cipher: ControlCipher?,
    ) {
        eventCommandSocket = socket
        eventCommandCipher = cipher
    }

    /** 在事件通道上发 POST /command（通道加密时加密）。通道未就绪时返回 false。 */
    fun postEventCommand(command: Map<String, Any?>): Boolean {
        val socket = eventCommandSocket ?: return false
        val cipher = eventCommandCipher
        return try {
            synchronized(eventCommandLock) {
                eventCseq++
                val body = BinaryPlist.encode(command)
                val head =
                    "POST /command RTSP/1.0\r\n" +
                        "Content-Type: application/x-apple-binary-plist\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "CSeq: $eventCseq\r\n\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val bytes = cipher?.encrypt(plaintext) ?: plaintext
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
            true
        } catch (error: IOException) {
            log("EVENT: 命令发送失败 - ${error.message}")
            false
        }
    }

    /**
     * 事件通道请求应答：只对**车机的请求**回 RTSP/1.0 200 OK，并 echo 其 CSeq
     * （DiPlay buildResponse 同款，对照 RtspMessage.kt:76）。踩坑记录（2026-10-11）：
     * 此前对一切入向消息都回 200——对"应答"回应答与 DiPlay 形成乒乓：DiPlay 把我们的
     * 多余应答解析成请求（"RTSP/1.0" 当 method 剥掉后 protocol 取到第三 token），
     * 回出畸形行 "OK 200 OK"；我们再回 → 无限回声，日志雪崩且空耗加密通道。
     * 通道写与 postEventCommand 共用一把锁，避免两线程加密帧交叉。
     */
    private fun respondEventRequest(
        cseq: String?,
        body: ByteArray? = null,
    ) {
        val socket = eventCommandSocket ?: return
        val cipher = eventCommandCipher
        try {
            synchronized(eventCommandLock) {
                val head =
                    "RTSP/1.0 200 OK\r\nContent-Length: ${body?.size ?: 0}\r\n" +
                        (cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + (body ?: ByteArray(0))
                socket.getOutputStream().apply {
                    write(cipher?.encrypt(plaintext) ?: plaintext)
                    flush()
                }
            }
        } catch (_: IOException) {
        }
    }

    /**
     * 车机资源/应用状态快照（/info 自报与 changeModes 事件共同更新）。
     * 语义出自 catplay change_modes.rs（镜像 Apple 配件框架枚举）：
     * appStateID 1=Speech/2=PhoneCall/3=TurnByTurn，speechMode -1=None/1=Speaking，
     * transferType 3=Borrow（主屏被借用）。
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
     * 事件通道 changeModes 请求的状态更新。返回 true = 车机归还了资源
     * （从占用 → 空闲）：这是切屏窗口，宿主应在 ~80ms 后补发 modesChanged。
     */
    fun onCarModesEvent(params: Map<*, *>?): Boolean {
        val previous = modesState
        val next = parseModesState(params, previous)
        modesState = next
        val restored = previous.busy && !next.busy
        log(
            "AIRPLAY: changeModes 事件 → 通话=${next.phoneCall} 语音=${next.speechActive} " +
                "主屏借用=${next.screenBorrowed}" + if (restored) "（归还——切屏窗口）" else "",
        )
        return restored
    }

    /** changeModes 应答体：status=0 + 当前资源快照（AADisplay CarPlayEventChannel.kt:344 同构）。 */
    private fun modesResponseBody(): ByteArray =
        BinaryPlist.encode(
            linkedMapOf(
                "status" to 0L,
                "params" to
                    linkedMapOf(
                        "appStates" to
                            listOf(
                                linkedMapOf<String, Any?>("appStateID" to 2L, "entity" to 0L),
                                linkedMapOf<String, Any?>("appStateID" to 3L, "entity" to 0L),
                                linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
                            ),
                        "resources" to
                            listOf(
                                linkedMapOf<String, Any?>("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                                linkedMapOf<String, Any?>("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
                            ),
                    ),
            ),
        )

    fun assertModes(): Boolean {
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "type" to "modesChanged",
                    "params" to
                        linkedMapOf(
                            "appStates" to
                                listOf(
                                    linkedMapOf<String, Any?>("appStateID" to 2L, "entity" to 0L),
                                    linkedMapOf<String, Any?>("appStateID" to 3L, "entity" to 0L),
                                    linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
                                ),
                            "resources" to
                                listOf(
                                    linkedMapOf<String, Any?>("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                                    linkedMapOf<String, Any?>("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
                                ),
                        ),
                ),
            )
        return try {
            val (statusLine, _) =
                request(
                    "POST",
                    "/command",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            log("AIRPLAY: modesChanged（声明持有主屏/主音频资源）[$statusLine]")
            statusLine.contains("200")
        } catch (error: IOException) {
            log("AIRPLAY: modesChanged 失败 - ${error.message}")
            false
        }
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
    fun runSetup(): SetupInfo? {
        // 计时端口：catplay 真车实现始终绑定真实 UDP timing server 通告给车机
        // （媒体时钟同步源）；timingPort=0 曾是我们的屏幕流 400 头号嫌疑。
        if (!timingServer.start()) {
            log("AIRPLAY: 计时服务未启动（继续，但媒体时钟无法同步）")
        }
        val deviceId = setupDeviceId()
        val body = buildSessionSetupBody(deviceId, timingServer.port, encrypted = cipher != null)
        log(
            "AIRPLAY: 初始 SETUP body=${body.size}B timingPort=${timingServer.port} " +
                "deviceID=$deviceId 加密=${if (cipher != null) "是" else "否（et=NONE）"}",
        )
        val (statusLine, responseBody) =
            try {
                // 请求 URI 用相对路径 `/setup`（catplay AirPlayRtspClient::initial_setup 同款）：
                // 部分老固件（210.81 时代）只认相对 URI，发绝对 `rtsp://host:port/` 会 404/400。
                request(
                    "SETUP",
                    "/setup",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: SETUP 失败 - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log("AIRPLAY: SETUP 应答 [$statusLine]（自报 deviceID=$deviceId）")
            if (statusLine.contains("403")) {
                // 403 的两类成因按可能性排序：设备过滤（WinPlay 等「只接受指定
                // iPhone」）最常见，其次是接收端不接受的配对/信任状态。把本机
                // 蓝牙地址直接打出来，用户对照接收端设置即可自查。
                log(
                    "AIRPLAY: 403 排查——接收端若启用了设备过滤，请在接收端选择本机蓝牙地址 " +
                        "$deviceId" +
                        if (deviceId == DeviceIdentity.PLACEHOLDER_MAC) {
                            "（当前是占位值：未能解析真实蓝牙 MAC，Shizuku 是否就绪？）"
                        } else {
                            ""
                        },
                )
            }
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody)
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: SETUP 应答 plist 解析失败（${responseBody.size} 字节）")
                null
            } as? Map<*, *>
        if (root == null) {
            log("AIRPLAY: SETUP 应答不是字典")
            return null
        }
        // 拉取主显示屏参数（跟随接收端屏幕方向；横屏车机应推横屏画面，触摸坐标也按此对齐）
        // 这一路**不能静默失败**：/info 挂掉意味着显示屏几何、HID 设备（触摸）、音频能力
        // 全部退回写死值。2026-10-20 宝马 iD9 就是在这里静默失败，日志只剩
        // `hidDevices uuids=[]` / `displayUuid=null` 两个结果，看不到原因。
        var infoRoot: Map<*, *>? = null
        val infoMap = loadInfoWithDiagnostics()
        infoRoot = infoMap
        val display = (infoMap?.get("displays") as? List<*>)?.firstOrNull() as? Map<*, *>
        if (display == null) {
            log(
                "AIRPLAY: 未取得 /info displays → 显示屏参数退回手机分辨率兜底" +
                    "（车机侧可能是小画面/黑边）；HID 设备为空 → 车机不会发 hidSendReport（触摸不可用）",
            )
        }
        // /info 全量转储：找奥迪私有的数据流 UUID（type-130 的 clientTypeUUID 候选）、
        // HID 设备描述（触摸）、以及任何我们没实现的能力声明
        // uuid 原样保留（奥迪发字符串 "0"/"1"，DiPlay 用整数——hidSetInputMode 原样回传）
        val parsedHidUuids = ArrayList<String>()
        // uuid → HID 描述符（媒体键记录需按下标取 Usage，触摸记录不需要但一并留存）
        val parsedHidDescriptors = HashMap<String, ByteArray>()
        infoRoot?.let { root ->
            val skipped = setOf("displays")
            val summary =
                root.entries.joinToString("; ") { (key, value) ->
                    if (key in skipped) "$key=[${(value as? List<*>)?.size ?: "?"}项]" else "$key=${value.toString().take(200)}"
                }
            log("AIRPLAY: /info 字段: $summary")
            // displays 已在 logInfoSummary 里逐条打印（含键值明细），此处不再重复
            // 音频能力逐条打印（整行 /info 被 take(200) 截断，audioFormats 恰好断在中间）：
            // 决定 audioType 只能靠这里——奥迪 type=100 到底声明了 compatibility 还是 media
            (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
                val format = entry as? Map<*, *> ?: return@forEachIndexed
                val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
                log(
                    "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                        "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                        "（PCM44.1k/48k stereo=" +
                        "${if ((output and (1L shl 11)) != 0L) "✓" else "✗"}" +
                        "${if ((output and (1L shl 15)) != 0L) "✓" else "✗"}，" +
                        "AAC-LC 44.1k=${if ((output and (1L shl 22)) != 0L) "✓" else "✗"}）",
                )
            }
            (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
                val latency = entry as? Map<*, *> ?: return@forEachIndexed
                log(
                    "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                        "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
                )
            }
            for (hidKey in listOf("hidDevices", "hids", "hid")) {
                (root[hidKey] as? List<*>)?.forEachIndexed { index, h ->
                    log("AIRPLAY: /info $hidKey[$index]=$h")
                    if (h is Map<*, *>) {
                        // 奥迪实测：uuid 是字符串 "0"/"1"（13:38 诊断 uuidClass=String）；
                        // DiPlay 是整数。统一转字符串，hidSetInputMode/hidSendReport 原样回传
                        val uuid =
                            when (val raw = h["uuid"]) {
                                is Number -> raw.toString()
                                is String -> raw
                                else -> null
                            }
                        if (uuid != null) parsedHidUuids += uuid
                        (h["hidDescriptor"] as? ByteArray)?.let { descriptor ->
                            log(
                                "AIRPLAY: hidDescriptor[$index] ${descriptor.size}B hex=" +
                                    descriptor.take(96).joinToString(" ") { "%02x".format(it) },
                            )
                            // 媒体键（Consumer Control）的 Usage 数组顺序只能从描述符得到：
                            // Array 报告的字节是「下标」而非 Usage ID（见 ConsumerKeys 注释）
                            if (uuid != null) parsedHidDescriptors[uuid] = descriptor
                        }
                    }
                }
            }
        }
        log("AIRPLAY: hidDevices 解析结果 uuids=$parsedHidUuids")
        val info =
            SetupInfo(
                eventPort = portOf(root["eventPort"]),
                timingPort = portOf(root["timingPort"]),
                keepAlivePort = root["keepAlivePort"]?.let(::portOf)?.takeIf { it != 0 },
                enabledFeatures = (root["enabledFeatures"] as? List<*>)?.map { it.toString() }.orEmpty(),
                displayWidth = ((display?.get("widthPixels") as? Long) ?: 1080L).toInt(),
                displayHeight = ((display?.get("heightPixels") as? Long) ?: 2320L).toInt(),
                // /info displays[0] 的显示屏 UUID（真实 iPhone 会把它回填进屏幕流 SETUP；
                // 键名以小写 "uuid" 为准，大写 "UUID" 兜底）
                displayUuid =
                    uuidText(
                        display?.get("uuid")
                            ?: display?.get("UUID")
                            ?: display?.get("displayUUID"),
                    ),
                maxFps = ((display?.get("maxFPS") as? Long) ?: 30L).toInt(),
                hidDeviceUuids = parsedHidUuids,
                hidDescriptors = parsedHidDescriptors,
                // 奥迪 MMI 3315 声明 true：屏幕流 keepAlive 用 opcode=5 带 bplist 统计体
                keepAliveSendStatsAsBody = (infoRoot?.get("keepAliveSendStatsAsBody") as? Boolean) == true,
            )
        log(
            "AIRPLAY: ✓ SETUP 完成 eventPort=${info.eventPort} timingPort=${info.timingPort} " +
                "keepAlive=${info.keepAlivePort ?: "无"} features=${info.enabledFeatures} " +
                "display=${info.displayWidth}x${info.displayHeight}@${info.maxFps} " +
                "displayUuid=${info.displayUuid}",
        )
        return info
    }

    /**
     * 拉 `/info` 并做完整取证（状态行 / body 长度 / 解析异常 / 原始 hex）。
     *
     * 此前的写法是 `catch (_: Exception) { null }`——车机一拒绝就彻底静默（2026-10-20
     * 宝马 iD9：`/info 字段` 那行日志根本没出现，只有 `uuids=[]` 和 `displayUuid=null`）。
     * 会话内被拒时再补一次**独立短连接**探测：接收端的 AirPlay 服务通常允许未绑定
     * 会话的 `/info`（能力查询），而活跃会话内的 `/info` 反而常被按 455/403 拒掉。
     */
    private fun loadInfoWithDiagnostics(): Map<*, *>? {
        var statusLine = "未请求"
        var body = ByteArray(0)
        try {
            val first = requestInfo()
            statusLine = first.first
            body = first.second
            if (!statusLine.contains("200") || body.isEmpty()) {
                log("AIRPLAY: /info 首次 [$statusLine] body=${body.size}B → 800ms 后重试一次")
                Thread.sleep(800)
                ensureConnected(2_000)
                val retry = requestInfo(5_000)
                log("AIRPLAY: /info 重试 [${retry.first}] body=${retry.second.size}B")
                if (retry.first.contains("200") && retry.second.isNotEmpty()) {
                    statusLine = retry.first
                    body = retry.second
                }
            }
        } catch (error: Exception) {
            log("AIRPLAY: /info 请求异常 - ${error.javaClass.simpleName}: ${error.message}")
        }
        parseInfoBody(statusLine, body)?.let { return it }
        scheduleSideProbe()
        return null
    }

    /**
     * 延后 [delayMillis] 再走侧信道 `/info`。
     *
     * 为什么不当场探：会话刚建立的头 20 秒正是最关键取证窗口（车机订阅、每秒 `/command`、
     * 隧道 CSM）。万一副连接让车机把主会话拆了，我们就丢掉整轮证据。延后探测即使出事，
     * 也能带着完整日志回来定位。
     *
     * 探到 HID 设备就立刻补发 `hidSetInputMode`——触摸可能当场生效（显示屏几何本轮已定，
     * 改不了，但输入通路可以不重建会话就补上）。
     */
    private fun scheduleSideProbe(delayMillis: Long = 25_000) {
        Thread(
            {
                runCatching { Thread.sleep(delayMillis) }
                val info = probeInfoOnSideConnection() ?: return@Thread
                val uuids =
                    (info["hidDevices"] as? List<*>).orEmpty().mapNotNull { entry ->
                        val hid = entry as? Map<*, *> ?: return@mapNotNull null
                        when (val raw = hid["uuid"]) {
                            is Number -> raw.toString()
                            is String -> raw
                            else -> null
                        }
                    }
                if (uuids.isEmpty()) {
                    log("AIRPLAY: 侧信道 /info 成功但车机未声明 HID 设备 → 触摸不走 AirPlay HID 通路")
                    return@Thread
                }
                log("AIRPLAY: 侧信道拿到 HID 设备 $uuids → 补发 hidSetInputMode（触摸可能立即生效）")
                uuids.forEach { uuid -> runCatching { sendHidSetInputMode(uuid, mode = 0) } }
            },
            "info-side-probe",
        ).apply { isDaemon = true }.start()
    }

    /** `/info` 报文 → 字典。失败时把原因与原始 hex 落日志，并缓存到 negotiatedInfo。 */
    private fun parseInfoBody(
        statusLine: String,
        body: ByteArray,
    ): Map<*, *>? {
        log("AIRPLAY: /info 应答 [$statusLine] body=${body.size}B")
        if (body.isEmpty()) return null
        val info =
            try {
                val result = BinaryPlist.parseRecovered(body)
                result.note?.let { log("AIRPLAY: /info $it") }
                result.value as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: /info plist 解析失败 - ${error.message}")
                log("AIRPLAY: /info 原始体（${body.size}B）hex=" + hexDump(body, 320))
                null
            }
        if (info == null) return null
        logInfoSummary(info)
        negotiatedInfo = info
        return info
    }

    /**
     * 独立短连接探测 `/info`（不加密、不带会话）：会话内被拒时的兜底。
     *
     * 只新建一条 TCP，发 `GET /info` 读完一条报文就关闭——不碰控制通道的状态机，
     * 车机若不认（455/403/超时）只是白跑一趟，不会影响已经跑起来的视频流。
     */
    private fun probeInfoOnSideConnection(timeoutMillis: Int = 4_000): Map<*, *>? {
        val target = endpoint
        if (target.airPlayPort <= 0) return null
        val host =
            activeHost ?: EndpointHosts.candidates(target.ipAddresses).firstOrNull() ?: return null
        var probe: Socket? = null
        return try {
            log("AIRPLAY: /info 侧信道探测 → $host:${target.airPlayPort}（独立短连接，不碰会话）")
            probe =
                Socket().apply {
                    connect(InetSocketAddress(resolveAddress(host), target.airPlayPort), timeoutMillis)
                    soTimeout = timeoutMillis
                }
            val payload = BinaryPlist.encode(linkedMapOf<String, Any?>())
            val head =
                "GET /info RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\n" +
                    "Content-Length: ${payload.size}\r\n\r\n"
            probe.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII) + payload)
                flush()
            }
            val input = probe.getInputStream()
            val collected = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var expected = -1
            while (true) {
                val count =
                    try {
                        input.read(chunk)
                    } catch (_: java.net.SocketTimeoutException) {
                        -1
                    }
                if (count < 0) break
                collected.write(chunk, 0, count)
                val bytes = collected.toByteArray()
                if (expected < 0) {
                    val separator = indexOf(bytes, HEAD_SEPARATOR)
                    if (separator >= 0) {
                        val (_, contentLength) =
                            parseRtspHead(String(bytes, 0, separator, Charsets.ISO_8859_1))
                        expected = separator + HEAD_SEPARATOR.size + contentLength
                    }
                }
                if (expected >= 0 && bytes.size >= expected) break
            }
            val bytes = collected.toByteArray()
            val separator = indexOf(bytes, HEAD_SEPARATOR)
            if (separator < 0) {
                log("AIRPLAY: /info 侧信道无有效应答（${bytes.size}B，车机可能只服务已绑定会话）")
                return null
            }
            val headText = String(bytes, 0, separator, Charsets.ISO_8859_1)
            val (line, contentLength) = parseRtspHead(headText)
            val bodyStart = separator + HEAD_SEPARATOR.size
            val infoBody = bytes.copyOfRange(bodyStart, minOf(bodyStart + contentLength, bytes.size))
            return parseInfoBody("侧信道 $line", infoBody)
        } catch (error: Exception) {
            log("AIRPLAY: /info 侧信道失败 - ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            runCatching { probe?.close() }
        }
    }

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
        /** 帧加密方式：ChaCha20（已配对）或 AES-128-CTR（未配对会话）。 */
        val cipher: StreamFrameCipher,
    )

    /**
     * iAP 隧道（type-130 DataStream，对照 DiPlay IapTunnel）：现代 CarPlay 的 iAP2
     * EA 数据通道——**车机→手机的 iAP2 消息（含触摸 HID）走这条加密 TCP**，
     * 手机→车机走事件通道 iAPSendMessage 命令（[sendIap]）。
     * 线格式：ChaCha 帧（2B LE 长度作 AAD + 密文 + 16B tag，nonce=8B LE 计数器，
     * 密钥 = HKDF(shared, "DataStream-Salt$seed", "DataStream-Output-Encryption-Key")）
     * ⊃ APTransportPackage（4B BE 总长，32B 头，偏移 16 处 type="comm"）→ body = iAP2 消息。
     */
    inner class IapTunnelClient(
        val socket: Socket,
        private val readKey: ByteArray,
        private val logPrefix: String = "TUNNEL",
        private val writeKey: ByteArray = readKey,
    ) {
        @Volatile
        var messageCount = 0
            private set

        /** 车机→手机 iAP2 消息回调（CSM 帧字节）。 */
        var onIapMessage: ((Iap2Csm.Frame) -> Unit)? = null

        private val parser =
            Iap2Csm
                .Parser()

        private val writeLock = Any()
        private var writeCounter = 0L
        private var writeFailureLogged = false

        /**
         * 手机→接收端方向（隧道）写入：DiPlay 的隧道链路引擎**只从 TCP socket 读**
         * （AirPlayIapTunnelStream 的 inbound=IapTunnel/TCP，outbound=事件通道），
         * 因此发送端必须把 iAP2 链路字节以「ChaCha 帧（2B LE 长度作 AAD + 密文 + 16B tag）
         * ⊃ APTransportPackage（4B BE 总长、偏移 16 处 type="comm"）」写进该 socket。
         * 密钥与读取方向同源（OUTPUT 标签 = 手机→接收端方向，2026-08 对照源码确认）。
         * 返回是否写出（socket 关闭/异常返回 false，调用方仅记录一次）。
         *
         * 包头偏移 20..28 为 8B 大端**包序号**（自 1 递增，2026-10-26 对照参考
         * 实现补齐——标准接收端校验该序号；DiPlay 不读它，补齐无副作用）。
         */
        fun writeIapBytes(bytes: ByteArray): Boolean {
            if (socket.isClosed || !socket.isConnected || bytes.isEmpty()) return false
            return try {
                val size = TUNNEL_PACKAGE_HEADER_BYTES + bytes.size
                val plain = ByteArray(size)
                plain[0] = (size ushr 24).toByte()
                plain[1] = (size ushr 16).toByte()
                plain[2] = (size ushr 8).toByte()
                plain[3] = size.toByte()
                plain[16] = 'c'.code.toByte()
                plain[17] = 'o'.code.toByte()
                plain[18] = 'm'.code.toByte()
                plain[19] = 'm'.code.toByte()
                // 双类型标签（参考实现 C4.d() 字节布局）：offset 4..8 恒写 "sync"——
                // 读端按 offset4 与 offset16 两个独立位置分发（sync→活性/rply、
                // comm→投递载荷），DiPlay 只看 comm 所以旧版不写也通，标准接收端
                // 按 offset4 分发时不写 sync 的上行包会被当未知类型丢弃（2026-10-26
                // 审查 P0：链路永远建立不起来的第一嫌疑）
                plain[4] = 's'.code.toByte()
                plain[5] = 'y'.code.toByte()
                plain[6] = 'n'.code.toByte()
                plain[7] = 'c'.code.toByte()
                bytes.copyInto(plain, TUNNEL_PACKAGE_HEADER_BYTES)
                val aad =
                    byteArrayOf(
                        (size and 0xFF).toByte(),
                        ((size ushr 8) and 0xFF).toByte(),
                    )
                // 包序号 @20..28 与 ChaCha 计数器都必须与写 socket 同锁：
                // 任一乱序都会让对端拒收/解密失败
                synchronized(writeLock) {
                    val sequence = ++writeSequence
                    for (index in 0 until 8) {
                        plain[20 + index] = (sequence ushr ((7 - index) * 8)).toByte()
                    }
                    val sealed =
                        AirPlayCrypto.chachaSeal(
                            writeKey,
                            AirPlayCrypto.nonce64(writeCounter++),
                            plain,
                            aad,
                        )
                    val output = socket.getOutputStream()
                    output.write(aad)
                    output.write(sealed)
                    output.flush()
                }
                writeFailureLogged = false
                true
            } catch (error: IOException) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    log("$logPrefix: 写出失败 - ${error.message}")
                }
                false
            }
        }

        /** APTransportPackage 包序号（偏移 20..28 的 8B BE，自 1 递增）。 */
        private var writeSequence = 0L

        fun startReading() {
            Thread({
                val input = socket.getInputStream()
                var pending = ByteArray(0)
                var plaintext = ByteArray(0)
                val buffer = ByteArray(16 * 1024)
                try {
                    while (socket.isClosed == false) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        pending += buffer.copyOf(count)
                        val decrypted = decryptFrames(pending)
                        pending = decrypted.second
                        plaintext += decrypted.first
                        plaintext = parsePackages(plaintext)
                    }
                    log("$logPrefix: 通道关闭（EOF），共 $messageCount 条 iAP2 消息")
                } catch (error: IOException) {
                    log("$logPrefix: 读取结束 - ${error.message}")
                }
            }, "iap-tunnel-reader").apply {
                isDaemon = true
                start()
            }
        }

        /** ChaCha 帧解密（与 ControlCipher 同帧格式，独立计数器/密钥）。 */
        private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
            val output = ArrayList<ByteArray>()
            var offset = 0
            while (buffer.size - offset >= 2) {
                val length = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
                val frameLength = 2 + length + 16
                if (buffer.size - offset < frameLength) break
                val aad = buffer.copyOfRange(offset, offset + 2)
                val sealed = buffer.copyOfRange(offset + 2, offset + frameLength)
                val plain =
                    AirPlayCrypto.chachaOpen(
                        readKey,
                        AirPlayCrypto.nonce64(tunnelReadCounter++),
                        sealed,
                        aad,
                    )
                output += plain
                offset += frameLength
            }
            val rest = buffer.copyOfRange(offset, buffer.size)
            return if (output.isEmpty()) ByteArray(0) to rest else output.reduce { a, b -> a + b } to rest
        }

        /**
         * APTransportPackage 解析：4B BE 总长，32B 头。
         *  - 偏移 16 type="comm" → body=iAP2 消息（CSM 解析 + [onRawBytes] 原样上抛）；
         *  - 偏移 4 type="sync" → 接收端存活探测，回 `rply`（抄回对方 20..28 时间戳）——
         *    参考实现对每条 sync 都回，标准接收端靠它
         *    判隧道活性，不回会被断开；DiPlay 从不发 sync，无行为变化。
         */
        private fun parsePackages(buffer: ByteArray): ByteArray {
            var offset = 0
            while (buffer.size - offset >= 32) {
                val size =
                    ((buffer[offset].toInt() and 0xFF) shl 24) or
                        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                        (buffer[offset + 3].toInt() and 0xFF)
                if (size < 32 || buffer.size - offset < size) break
                val messageType = String(buffer, offset + 16, 4, Charsets.US_ASCII)
                if (messageType == "comm") {
                    val body = buffer.copyOfRange(offset + 32, offset + size)
                    onRawBytes?.invoke(body)
                    onIapBytes(body)
                } else if (String(buffer, offset + 4, 4, Charsets.US_ASCII) == "sync") {
                    val reply = ByteArray(32)
                    reply[0] = 0
                    reply[1] = 0
                    reply[2] = 0
                    reply[3] = 32
                    "rply".toByteArray(Charsets.US_ASCII).copyInto(reply, 4)
                    System.arraycopy(buffer, offset + 20, reply, 20, 8)
                    writeRawPackage(reply)
                    if (syncReplies <= 3) {
                        syncReplies++
                        log("$logPrefix: sync 探测 → 已回 rply #$syncReplies")
                    }
                }
                offset += size
            }
            return buffer.copyOfRange(offset, buffer.size)
        }

        /** 已应答的 sync 探测数（日志节流）。 */
        private var syncReplies = 0

        /**
         * 车机→手机方向的原 comm 载荷（未解析）：喂给事件通道虚拟链路的引擎——
         * type-130 隧道全双工形态（参考实现语义）下车机把链路帧（DETECT/SYN/
         * CSM 的 FF5A 封装）直接放 comm 包里发过来，不走事件通道。
         */
        var onRawBytes: ((ByteArray) -> Unit)? = null

        /** 加密写出一个自构造的 APTransportPackage（rply 应答用，不含 comm 标记）。 */
        private fun writeRawPackage(plain: ByteArray): Boolean =
            try {
                val aad =
                    byteArrayOf(
                        (plain.size and 0xFF).toByte(),
                        ((plain.size ushr 8) and 0xFF).toByte(),
                    )
                synchronized(writeLock) {
                    val sealed =
                        AirPlayCrypto.chachaSeal(
                            writeKey,
                            AirPlayCrypto.nonce64(writeCounter++),
                            plain,
                            aad,
                        )
                    val output = socket.getOutputStream()
                    output.write(aad)
                    output.write(sealed)
                    output.flush()
                }
                true
            } catch (error: IOException) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    log("$logPrefix: 写出失败 - ${error.message}")
                }
                false
            }

        private fun onIapBytes(bytes: ByteArray) {
            messageCount++
            val frames = parser.offer(bytes)
            for (frame in frames) {
                // hex 格式化有成本：只在真要打日志的分支里做（此前每条消息都先算一遍）
                if (messageCount <= 20) {
                    val hex = bytes.joinToString(" ") { "%02x".format(it) }
                    log(
                        "$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) body=${frame.body.size}B " +
                            "hex=${hex.take(160)}",
                    )
                } else if (messageCount % 100 == 0) {
                    log("$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} body=${frame.body.size}B")
                }
                onIapMessage?.invoke(frame)
            }
        }

        private var tunnelReadCounter = 0L
    }

    /**
     * SETUP type-130 iAP 隧道并连接车机 dataPort。失败返回 null（会话继续，触摸不可用）。
     * 手机→车机方向用 [sendIap]（事件通道 iAPSendMessage）。
     *
     * 请求字段对照参考实现（2026-10-26）补齐
     * `controlType/channelID/clientUUID/wantsDedicatedSocket/sendMessageAsIs`——
     * 旧请求只有 type/clientTypeUUID/seed，标准接收端要求 wantsDedicatedSocket 才给
     * 专用端口。channelID 用本机蓝牙 MAC（与会话 SETUP 的 deviceID 同源，接收端按它对账）。
     *
     * [modernAuth] = 接收端 SETUP 应答 enabledFeatures 声明了 iAPChannel：隧道走
     * 全双工「现代认证」形态（两步式 0xAA00 + 记忆挑战 + runtime 问候，详见
     * [VirtualIap2Link.tunnelModernAuth]）；未声明（DiPlay）维持旧半双工行为。
     */
    fun runIapTunnel(
        sessionKey: ByteArray?,
        modernAuth: Boolean = false,
    ): IapTunnelClient? {
        // 隧道 socket 用 DataStream 密钥加密，未配对会话没有共享密钥可派生 → 跳过。
        if (sessionKey == null) {
            log("TUNNEL: 未配对会话没有共享密钥——跳过 iAP 隧道（触摸不可用）")
            return null
        }
        val host = receiverHost() ?: return null
        val seed = (System.currentTimeMillis() % 0x7FFF_FFFFL) + 1L
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        // channelID 用「02: 前缀伪 MAC + -RCS-1」形态（参考实现同款）：SHA-256(本机
        // 持久化 UUID) 前 5 字节、冒号分隔大写。**刻意不用真实蓝牙 MAC**——参考实现
        // 的 deviceID 用真 MAC 而 channelID 用伪 MAC，二者不对账；真 MAC 去冒号形态
        // 若被接收端按 MAC 解析会直接失败（2026-10-26 审查 P0）。
        val channelKey = persistentTunnelChannelMac()
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            buildMap<String, Any?> {
                                put("type", 130L)
                                put("controlType", 2L)
                                put("clientTypeUUID", IAP_DATASTREAM_UUID)
                                if (channelKey != null) put("channelID", "$channelKey-RCS-1")
                                put(
                                    "clientUUID",
                                    java.util.UUID
                                        .randomUUID()
                                        .toString()
                                        .uppercase(),
                                )
                                put("seed", seed)
                                put("streamConnectionID", connectionId)
                                put("wantsDedicatedSocket", true)
                                put("sendMessageAsIs", true)
                            },
                        ),
                ),
            )
        val (statusLine, responseBody) =
            try {
                request("SETUP", "/", body, contentType = "application/x-apple-binary-plist")
            } catch (error: IOException) {
                log("TUNNEL: SETUP 失败 - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log("TUNNEL: SETUP 应答 [$statusLine]（响应头：${lastResponseHeadOfRequest()}）")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (_: IllegalArgumentException) {
                null
            } ?: run {
                log("TUNNEL: SETUP 应答解析失败")
                return null
            }
        val streams = root["streams"] as? List<*>
        val stream = streams?.firstOrNull() as? Map<*, *>
        val dataPort = ((stream?.get("dataPort") as? Long) ?: 0L).toInt() and 0xFFFF
        if (dataPort == 0) {
            log("TUNNEL: SETUP 应答无 dataPort（streams=$streams）——接收端未启用 iAPChannel，降级事件通道")
            return null
        }
        val salt = "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII)
        // 读/写方向各派生一把密钥（参考实现语义：写=Output、读=Input。
        // 旧实现两方向共用 Output 派生——DiPlay 不从隧道发数据所以从未暴露；
        // 标准接收端车机→手机方向用 Input 派生，共用一把解不开它的帧）
        val writeKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                salt,
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        val readKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                salt,
                "DataStream-Input-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        val tcp =
            try {
                Socket()
            } catch (error: IOException) {
                return null
            }
        return try {
            tcp.tcpNoDelay = true
            tcp.connect(java.net.InetSocketAddress(host, dataPort), 5_000)
            log(
                "TUNNEL: ✓ iAP 隧道已建立 $host:$dataPort（seed=$seed 全双工密钥，" +
                    "形态=${if (modernAuth) "现代认证（iAPChannel 已声明）" else "兼容（半双工）"}）",
            )
            val link = obtainIapLink()
            link.tunnelModernAuth = modernAuth
            link.tunnelBluetoothMac = setupDeviceId()
            val client = IapTunnelClient(tcp, readKey = readKey, writeKey = writeKey)
            // 全双工形态：车机把链路帧放进 comm 包发来（不经事件通道），
            // 原样喂给虚拟链路引擎（DETECT 回显/SYN 协商/CSM 都在里面）
            client.onRawBytes = { bytes -> link.onCarBytes(bytes) }
            client.startReading()
            iapTunnel = client
            client
        } catch (error: IOException) {
            log("TUNNEL: TCP 连接失败 - ${error.message}")
            runCatching { tcp.close() }
            null
        }
    }

    /**
     * 隧道客户端引用（DiPlay 形态）：事件通道虚拟链路（[VirtualIap2Link]）的**出站方向**
     * 必须写进 type-130 TCP（DiPlay 的隧道链路引擎只从 TCP 读；其出站走事件通道，
     * 与我们被动接收一致）。无隧道（Audi 事件通道形态）时出站继续走事件通道 [sendIap]。
     */
    @Volatile
    private var iapTunnel: IapTunnelClient? = null

    private var sendIapCount = 0

    /**
     * 手机→车机 iAP2 消息：事件通道 iAPSendMessage 命令（对照 DiPlay sendIapMessage，
     * params 信封 + data 字节，catplay CommandIApSendMessage 同款）。
     */
    fun sendIap(data: ByteArray): Boolean {
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "iAPSendMessage",
                    "params" to linkedMapOf<String, Any?>("data" to data),
                ),
            )
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
    private var iapLink: VirtualIap2Link? = null

    /**
     * 车机媒体键回调（方向盘/面板的上一曲、下一曲）。
     *
     * 通路：事件通道 → iAPSendMessage → iAP2 控制会话 `0x6802 AccessoryHIDReport`
     * → [ConsumerKeys] 映射成 Android KeyEvent → 本回调。由 SessionController 注入
     * `dispatchMediaKey`（与 HID 直投那条路合并处理）。
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /** 导航键（iDrive 旋钮/四向拨杆）：需要虚拟屏焦点，由会话层注入 DPAD。 */
    var onNavKey: ((Int) -> Unit)? = null

    /**
     * MIB3 隧道控制会话完成（auth+burst 已发）回调：宿主据此调度 modesChanged
     * 资源重申 burst（AADisplay r17 时序 +1.5/4/12/30s）。082446 定谳：不重申的
     * 话车机全程健康却永不切到 CarPlay 画面。
     */
    var onTunnelControlSessionComplete: (() -> Unit)? = null

    /** 视频起流回调（屏幕上第一帧发出后）——实验模式下重申的触发源。 */
    var onStreamingStarted: (() -> Unit)? = null

    /**
     * MIB3 虚拟链路移交就绪（链路 NORMAL、即将发 0x1D00）：宿主据此拆 BT iAP2 会话
     * （对齐真 iPhone 的无线移交形态：AirPlay 链成型后 BT 立刻退役，单一链路）。
     */
    var onMib3HandoffReady: (() -> Unit)? = null

    /** MIB3 虚拟链路是否已布防（armMib3 成功）——拆链归因用：布防了但控制会话没完成就被拆 = 链路看门狗，与音频档位无关。 */
    var mib3LinkArmed: Boolean = false
        private set

    /** MIB3 控制会话是否完成过（auth+burst 已发）。 */
    var mib3ControlSessionDone: Boolean = false
        private set

    /**
     * 现代隧道形态（enabledFeatures 含 iAPChannel）下等待隧道控制会话完成
     * （识别+MFi+runtime 问候，即 [mib3ControlSessionDone]）——参考实现把「iAP2
     * 认证完成」作为屏幕流 SETUP 的前置（阻塞 6s，超时降级 screen-only 并重建
     * 事件桥；2026-10-26 审查 P1）。我们超时后继续走屏幕流（隧道并发跑着），
     * 不做关隧道降级——诊断期保留观察窗口。
     */
    fun awaitModernTunnelSession(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (mib3ControlSessionDone) return true
            if (iapTunnel == null) return false
            Thread.sleep(100)
        }
        return mib3ControlSessionDone
    }

    /** 虚拟链路唯一实例（出站路由/三形态语义见 [VirtualIap2Link]）。 */
    private fun obtainIapLink(): VirtualIap2Link =
        iapLink ?: synchronized(this) {
            iapLink
                ?: VirtualIap2Link(
                    log = { line -> log(line) },
                    sendEventIap = ::sendIap,
                    sendControlIap = ::postControlIap,
                    tunnelWriter = { iapTunnel },
                    alive = { socket?.isClosed == false },
                    // MFi 长度记忆必须持久化：缺 context 时记忆只在进程内存活，
                    // 冷启动即清零 → 基线 20B 排第一 → DiPlay（只认精确 32B，
                    // 长度不符直接断链）每次冷启动首连必断、二连才成
                    // （2026-10-26 17:38 实测定谳）。带上后每台车只在记忆空白
                    // 的第一次付一次试错代价。
                    mfiContext = context,
                ).also {
                    it.onMediaKey = { keyCode -> onMediaKey?.invoke(keyCode) }
                    it.onNavKey = { keyCode -> onNavKey?.invoke(keyCode) }
                    it.onControlSessionComplete = {
                        mib3ControlSessionDone = true
                        onTunnelControlSessionComplete?.invoke()
                    }
                    it.onMib3HandoffReady = { onMib3HandoffReady?.invoke() }
                    it.begin()
                    iapLink = it
                }
        }

    /** 车机 iAPSendMessage 字节入口（SessionController 事件命令处理器调用）。 */
    fun onIapEventBytes(data: ByteArray) {
        obtainIapLink().onCarBytes(data)
    }

    /**
     * MIB3 布防：屏幕流 SETUP 成功后调用——主动经**控制通道** POST /command 发一次
     * DETECT，随后等车机 SYN（真机 Frida 时序，详见 [VirtualIap2Link.armMib3]）。
     * 明文会话（pre-HomeKit 老车机）没有 WiFi 隧道语义，跳过。
     */
    private fun armMib3VirtualLink() {
        if (cipher == null) return
        if (iapTunnel != null) return // DiPlay 隧道形态：不需要（也不该）布防
        // 实验 C（2026-10-25，8015 不切屏排查）：CLASSIC_MIB3 完全跳过隧道布防——
        // 不发 DETECT、车机因此不发 marker、链路永不协商。对齐真 iPhone 形态
        // （sysdiagnose 成功会话 iAPChannel=0 = 无隧道）。已排除的变量：SPS level
        // （40/32 都不切）、帧率（30/60 都不切）、分辨率（= /info 声明值）、
        // modesChanged 重申与资源仲裁（全 200、modes 干净）。剩「协商完成抑制切屏」
        // 这一条：0.1.12 切屏窗口恰在协商完成之前。
        if (MIB3_TUNNEL_ARM_ENABLED) {
            val generation = linkGeneration ?: ReceiverProfile.LinkGeneration.EVENT_LINK
            obtainIapLink().setGeneration(generation)
            obtainIapLink().armMib3()
            mib3LinkArmed = true
        } else {
            log("IAPLINK: 实验模式——跳过 MIB3 隧道布防（不发 DETECT，纯 AirPlay 流）")
        }
    }

    /**
     * 控制通道 iAPSendMessage 出站（MIB3）：手机侧 iAP2 链路帧**必须**走 RTSP 控制
     * 连接上的 POST /command——写在事件通道上时 MIB3 根本不读（DiPlay 宽容，奥迪
     * 不宽容；AADisplay Frida 结论）。串行执行器：不阻塞链路节拍线程，与
     * /feedback 心跳共用 request() 的 CSeq/写锁（线程安全设计见 AirPlayClient）。
     */
    private val controlIapTx =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mib3-iap-tx").apply { isDaemon = true }
        }
    private var controlIapCount = 0

    fun postControlIap(frame: ByteArray) {
        controlIapTx.execute {
            try {
                val body =
                    BinaryPlist.encode(
                        linkedMapOf(
                            "type" to "iAPSendMessage",
                            "params" to linkedMapOf<String, Any?>("data" to frame),
                        ),
                    )
                val (statusLine, reply) =
                    request(
                        "POST",
                        "/command",
                        body,
                        contentType = "application/x-apple-binary-plist",
                    )
                controlIapCount++
                val head =
                    "IAPLINK: tx(控制通道) #$controlIapCount ${frame.size}B [$statusLine]" +
                        (if (reply.isEmpty()) "" else " resp=${reply.size}B hex=" + hexPrefix(reply))
                // 节流注意：不能按「应答体非空」放行——奥迪对每次 POST 都回 42B bplist
                // （1Hz 全命中，曾把这条节流击穿成每秒刷屏）。常态只打前 10 条 + 每 100 条；
                // 非 200 状态始终打（异常必须可见）
                if (controlIapCount <= 10 ||
                    controlIapCount % 100 == 0 ||
                    !statusLine.contains("200")
                ) {
                    log(head)
                }
                // 车机可能把链路字节直接塞在应答体里（而不是另发事件通道）：认出来就喂给
                // 链路引擎，别让它白躺在响应里。
                if (reply.isNotEmpty() && looksLikeIapLink(reply)) {
                    log("IAPLINK: 控制通道应答内含链路字节 ${reply.size}B → 喂给链路引擎")
                    iapLink?.onCarBytes(reply)
                }
            } catch (error: IOException) {
                log("IAPLINK: 控制通道 POST 失败 - ${error.message}")
                iapLink?.markLinkBroken()
            }
        }
    }

    /** 应答体的十六进制前缀（诊断用，截断到 24 字节）。 */
    private fun hexPrefix(
        bytes: ByteArray,
        limit: Int = 24,
    ): String = bytes.take(limit).joinToString(" ") { "%02x".format(it) } + if (bytes.size > limit) "…" else ""

    /**
     * 报文全量十六进制（故障取证用）：默认最多 480 字节、每行 48 字节。
     * 排查车机 plist 解不出来时，只给 24 字节前缀看不到出错的对象，必须给全文。
     */
    private fun hexDump(
        bytes: ByteArray,
        limit: Int = 480,
        perLine: Int = 48,
    ): String {
        if (bytes.isEmpty()) return "（空）"
        val shown = minOf(bytes.size, limit)
        val builder = StringBuilder(shown * 3)
        var index = 0
        while (index < shown) {
            if (index > 0) builder.append("\n    ")
            val end = minOf(index + perLine, shown)
            for (position in index until end) {
                builder.append("%02x".format(bytes[position]))
                if (position != end - 1) builder.append(' ')
            }
            index = end
        }
        if (bytes.size > shown) builder.append(" …（共 ${bytes.size}B，只显示前 $shown）")
        return builder.toString()
    }

    /** 应答体是否是 iAP2 链路帧（marker 0xFF55 / 同步包 0xFF5A）。 */
    private fun looksLikeIapLink(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 0xFF.toByte() &&
            (bytes[1] == 0x5A.toByte() || bytes[1] == 0x55.toByte())

    /** 音频流协商结果（RTP over UDP；密钥同视频流规则，盐用音频连接 ID）。 */
    class AudioStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val controlPort: Int,
        val streamKey: ByteArray,
        /** 协商出的采样率（Hz）：按车机声明选择，AudioSender 必须用同一值采集。 */
        val sampleRate: Int = 48_000,
    )

    /** plist 端口是 u16 但以 2 字节有符号整数编码（>32767 读出为负），按位掩出真实值。 */
    private fun portOf(value: Any?): Int = ((value as? Long) ?: 0L).toInt() and 0xFFFF

    companion object {
        /**
         * MIB3 隧道布防开关（2026-10-25 实验 C）：false = CLASSIC_MIB3 不发 DETECT。
         * 实验结论出来后：切屏则永久改为「不发隧道」路线；不切则恢复 true 并转向
         * 隧道内数据通路（仪表盘订阅/0x5201 等）。
         */
        private const val MIB3_TUNNEL_ARM_ENABLED = true

        /** 从 /info 或 changeModes 的 modes 结构解析状态；缺失的清单沿用 [base]。 */
        internal fun parseModesState(
            modes: Map<*, *>?,
            base: ModesState,
        ): ModesState {
            if (modes == null) return base
            var phoneCall = base.phoneCall
            var speechActive = base.speechActive
            var screenBorrowed = base.screenBorrowed
            (modes["appStates"] as? List<*>)?.forEach { entry ->
                val item = entry as? Map<*, *> ?: return@forEach
                when ((item["appStateID"] as? Number)?.toLong()) {
                    2L -> {
                        // 注意 Elvis 与比较的优先级：`a ?: b != 0L` 会解析成 (a ?: b) != 0L——
                        // Boolean 的 false 被拿去和 0L 比较恒为 true（实测踩过：state=false
                        // 被解析成「车机正在通话」，重申被错误冻结）
                        val stateFlag = item["state"] as? Boolean
                        phoneCall =
                            stateFlag ?: (((item["entity"] as? Number)?.toLong() ?: 0L) != 0L)
                    }

                    1L -> speechActive = (item["speechMode"] as? Number)?.toLong() != -1L
                }
            }
            (modes["resources"] as? List<*>)?.forEach { entry ->
                val item = entry as? Map<*, *> ?: return@forEach
                if ((item["resourceID"] as? Number)?.toLong() == 1L) {
                    val transferType = (item["transferType"] as? Number)?.toLong()
                    screenBorrowed =
                        transferType == 3L ||
                        (transferType == null && item.containsKey("borrowID"))
                }
            }
            return ModesState(phoneCall, speechActive, screenBorrowed)
        }

        /** 主屏视频流类型（对照接收端 STREAM_TYPE_MAIN_SCREEN；100 是音频、130 是数据/iAP 隧道）。 */
        const val SCREEN_STREAM_TYPE = 110L

        /** 主音频流类型（DiPlay STREAM_TYPE_MAIN_AUDIO；PCM 通路）。 */
        const val AUDIO_STREAM_TYPE = 100L

        /**
         * 高质量主音频流类型（catplay `StreamType::MainHighAudio`）。
         *
         * 部分车机（2026-10-20 Honda HFT / Alpine 8A250-30A 实测）的**真媒体通路**在这条：
         * `type=102 media` 只声明 AAC-LC，而 `type=100 media` 只声明 PCM——往 100 推 PCM
         * 时车机 SETUP 回 200、给 dataPort，却不消费（`/feedback` 的 sampleTime 恒 0），
         * 约 6.5s 后把整条会话拆掉。
         */
        const val AUDIO_STREAM_TYPE_MAIN_HIGH = 102L

        /** 音频格式位：PCM 48000Hz 16bit 立体声（catplay AudioFormat 定义 1<<15）。 */
        const val AUDIO_FORMAT_PCM_48K_STEREO = 1L shl 15

        /** 音频格式位：PCM 44100Hz 16bit 立体声（1<<11；48k 不可用时的回退）。 */
        const val AUDIO_FORMAT_PCM_44K_STEREO = 1L shl 11

        /** AAC-LC 44100Hz 立体声（1<<22；catplay `AAC_LC_44100_STEREO`）。 */
        const val AUDIO_FORMAT_AAC_LC_44K_STEREO = 1L shl 22

        /** AAC-LC 48000Hz 立体声（1<<23；catplay `AAC_LC_48000_STEREO`）。 */
        const val AUDIO_FORMAT_AAC_48K_STEREO = 1L shl 23

        /**
         * 媒体音频通道的 audioType（真 iPhone 媒体音频走这条）。
         * 具体选哪个由 [negotiateAudio] 按车机 /info 的 audioFormats 决定——这里只是
         * 「有 media 档时优先」的判据与其在 /info 缺失时的协议基线值。
         */
        const val AUDIO_TYPE_MEDIA = "media"

        /**
         * 音频延迟兜底值（ms）：车机 /info 的 audioLatencies 里没给 outputLatencyMicros 时用。
         * catplay 注释：AirPlay 推流最低 250ms 才稳定（clamp 32..2000）——兜底取 250。
         */
        const val AUDIO_LATENCY_FALLBACK_MS = 250L

        /** 车机音量 ≤ 这个值（dB）视为静音位（AirPlay 里 -144 约等于全静音）。 */
        private const val MUTE_THRESHOLD_DB = -100f

        /** 检测到静音位时抬到的音量（dB，0=最大；-25 属于中等偏小，不至于炸响）。 */
        private const val SAFE_VOLUME_DB = -25.0f

        /** 解析 `volume: -12.345678` 这类文本参数。 */
        private val VOLUME_REGEX = Regex("volume:\\s*(-?[0-9.]+)")

        /**
         * SETUP 里的 iPhone 能力清单（接收端按「车机支持 ∩ 提议」计算 enabledFeatures，
         * 缺失的能力整个功能被关——2026-10-11 奥迪实测：videoPlayback 单独提议时
         * 视频管线未启用、屏幕流 400）。清单对照 catplay 真车发送端。
         */
        val CONTROLLER_FEATURES =
            listOf(
                "uiContext",
                "viewAreas",
                "cornerMasks",
                "focusTransfer",
                "h.264Level5.1",
                "mainBuffered",
                "altScreen",
                "enhancedSiri",
                "hevc",
                "sessionManagement",
                "logTransfer",
                "iAPChannel",
                "vehicleStateProtocol",
                "videoPlayback",
            )

        /** 等车机打开 AirPlay 窗口的总时长（用户在车机上点手机图标的等待窗口）。 */
        private const val PAIRING_WINDOW_MILLIS = 120_000L

        /** 配对重试期间调用 [retryTick] 的间隔（次）：每轮回 2s，10 次 ≈ 每 20s 重新公告一次。 */
        private const val RETRY_TICK_ATTEMPTS = 10

        /**
         * 接收端明确回「没有这个接口」的 HTTP 状态。命中即判定为 pre-HomeKit 老车机，
         * 立刻转明文会话，不再把 120s 配对窗口空跑完。
         */
        private val PAIRING_UNSUPPORTED_STATUS = listOf("404", "405", "455", "501")

        /** 发送端自报的 AirPlay 版本（catplay 真车验证值，iOS 15.0.2 时代；勿用车机的版本）。 */
        private const val SENDER_SOURCE_VERSION = "566.25.21"

        /**
         * UDP keepAlive 间隔。catplay 用 10s，但那是它自家接收端的口径；真车（奥迪
         * MMI 3315）看门狗同为 10s，等长即必输（sleep 抖动让心跳晚 1ms 到）。
         */
        private const val KEEPALIVE_INTERVAL_MS = 5_000L

        /** 读线程结束/连接关闭时投递给等待者的哨兵状态行（非真实 RTSP 应答）。 */
        private const val RTSP_CLOSED_MARK = "__closed__"

        /** 屏幕流延迟（catplay c2a car_manager 实测值）。 */
        private const val SCREEN_LATENCY_MS = 25L

        /** iAP DataStream 的 clientTypeUUID（DiPlay IAP_DATASTREAM_UUID，真 iPhone 同款）。 */
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"

        /** 隧道 APTransportPackage 头长（4B BE 总长 + 12B 保留 + 4B type="comm" + 12B 保留）。 */
        private const val TUNNEL_PACKAGE_HEADER_BYTES = 32

        /** RTSP 报文头结束分隔符与头部字段正则（请求/应答两条重组路径共用，勿再内联）。 */
        private val HEAD_SEPARATOR = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

        private val CONTENT_LENGTH_REGEX = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        private val CSEQ_REGEX = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        /** 解析 RTSP 报文头：首行 + Content-Length（两条重组路径共用）。 */
        fun parseRtspHead(headText: String): Pair<String, Int> {
            val contentLength =
                CONTENT_LENGTH_REGEX
                    .find(headText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: 0
            val firstLine = headText.lineSequence().firstOrNull() ?: ""
            return firstLine to contentLength
        }

        fun indexOf(
            source: ByteArray,
            target: ByteArray,
        ): Int {
            outer@ for (index in 0..source.size - target.size) {
                for (offset in target.indices) {
                    if (source[index + offset] != target[offset]) continue@outer
                }
                return index
            }
            return -1
        }
    }

    /**
     * SETUP：协商主音频流（type 100，RTP over UDP）。
     *
     * 线格式与视频同族但传输不同：接收端在应答里开 **UDP** dataPort/controlPort，
     * 手机把 PCM 帧按 [AudioSender] 的格式发过去。密钥 = HKDF(shared,
     * "DataStream-Salt<音频 connId>", "DataStream-Output-Encryption-Key")。
     *
     * 字段按 catplay 发送端（`StreamDescriptionAudio::new`）对齐：
     *  - `audioLatencyMs` 必填（接收端会读；漏发会让严格实现拆会话）
     *  - `audioType` **按车机 /info 声明协商**（见 [negotiateAudio]）：媒体音频优先
     *    `media` 通道；曾经写死 `compatibility` 时车机接受 SETUP、给端口、照收包，
     *    但不接入播放通路（无声）——这是 2026-10-17 的主根因之一
     *  - `streamConnectionID` 用随机正 i64（catplay 同款 `rand::random::<u64>()`）：时间戳
     *    余数在短时间重连时会撞号
     *  - 不额外 GET /info（实测多花 ~2.5s；/info 已在握手阶段拉过并缓存复用）
     *
     * 格式：优先 PCM 48k 立体声（bit15），车机只声明 44.1k 时退 bit11；采样率下发给
     * [AudioSender] 保持一致。
     */
    fun runAudioStream(
        sharedSecret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning = AudioTuning.RAW_SPF,
    ): AudioStreamInfo? {
        // 档位由调用方按「车机 /info 声明的候选 + 本车机历史记忆」选定后传进来：
        // 同一台车机上不同 audioType 档位的可用性完全不同（见 AudioProfile 注释），
        // 单档失败就换下一档，而不是每次连接都盲试同一档。
        val negotiation = profile
        log(
            "AIRPLAY: 音频协商 → audioType=${profile.audioType} " +
                "format=0x${profile.formatBit.toString(16)} ${profile.sampleRate}Hz " +
                "latency=${profile.latencyMs}ms（档位=${profile.label}，" +
                "封装=${tuning.detail}）",
        )
        // 随机正 i64：BinaryPlist 按有符号整数编码，掩掉符号位避免负数
        val connectionId = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)
        // spf 取值：AAC 一帧 1024 采样；PCM 与发送端每包 352 帧保持一致
        val spfValue =
            tuning.spf?.let {
                if (profile.codec == AudioCodec.AAC_LC) it.toLong() else AudioSender.SAMPLES_PER_PACKET.toLong()
            }
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>().apply {
                                put("type", profile.streamType)
                                put("streamConnectionID", connectionId)
                                put("audioFormat", negotiation.formatBit)
                                put("audioType", negotiation.audioType)
                                put("audioLatencyMs", negotiation.latencyMs)
                                // 与 catplay 发送端（StreamDescriptionAudio::new）逐字段对齐：
                                // input=false（只下行，无麦克风上行）、
                                // supportsRTPPacketRedundancy=false（我们不打包冗余帧）。
                                // 缺这两个布尔时部分严格实现的接收端会按「未声明=不支持」处理，
                                // 在音频通路初始化阶段就放弃该流。
                                put("input", false)
                                put("supportsRTPPacketRedundancy", false)
                                put("supportsHighAccuracyTimestamps", tuning.highAccuracyTimestamps)
                                // spf = 每包样本帧数：AAC 一帧 1024，PCM 走 352。
                                // 2026-10-20 Honda 实测：不声明 spf 时车机照样收包并按 44.1k
                                // 实时消费，但喇叭无声——「按固定 SPF 排播」的实现需要这个值。
                                spfValue?.let { put("spf", it) }
                            },
                        ),
                ),
            )
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val startedAt = SystemClock.uptimeMillis()
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "rtsp://$host:${endpoint.airPlayPort}/",
                    body,
                    // 8s：奥迪音频 SETUP 实测可能耗时 ~2.7s（车机侧资源仲裁）；异步读线程
                    // 保证等待期间 feedback 心跳照常发送，长等待不再触发车机看门狗
                    timeoutMillis = 8_000,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: 音频流协商失败 - ${error.message}")
                return null
            }
        log("AIRPLAY: 音频 SETUP 往返 ${SystemClock.uptimeMillis() - startedAt}ms [$statusLine]")
        if (!statusLine.contains("200")) {
            log("AIRPLAY: 音频流协商应答 [$statusLine]")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: 音频流协商应答解析失败")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: 音频流协商应答缺少 streams")
                return null
            }
        val audio =
            streams.firstOrNull { entry ->
                ((entry as? Map<*, *>)?.get("type") as? Long) == profile.streamType
            } as? Map<*, *> ?: run {
                log("AIRPLAY: 音频流被拒绝（streams=$streams）")
                return null
            }
        val dataPort = portOf(audio["dataPort"])
        if (dataPort == 0) {
            log("AIRPLAY: 音频流未返回 dataPort（$audio）")
            return null
        }
        val streamKey =
            AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        log(
            "AIRPLAY: ✓ 音频流协商成功（type=${profile.streamType} ${negotiation.audioType} " +
                "format=0x${negotiation.formatBit.toString(16)} ${negotiation.sampleRate}Hz " +
                "latency=${negotiation.latencyMs}ms）" +
                "dataPort=$dataPort controlPort=${portOf(audio["controlPort"])} connId=$connectionId" +
                " 全字段=$audio",
        )
        // 音频通路已建好，但「车机在收数据、喇叭没声」还剩一种很常见的原因：
        // 接收端的 AirPlay 音量处于静音位（-144dB）——真 iPhone 会主动 SET_PARAMETER
        // volume。这里先查一次（顺带把值记进日志），查到静音位就抬到 -25dB。
        probeAndFixVolume(host)
        return AudioStreamInfo(
            streamConnectionId = connectionId,
            dataPort = dataPort,
            controlPort = portOf(audio["controlPort"]),
            streamKey = streamKey,
            sampleRate = negotiation.sampleRate,
        )
    }

    /**
     * 查询并纠正接收端音量（AirPlay 的 `GET_PARAMETER`/`SET_PARAMETER` + `volume`）。
     *
     * 为什么需要（2026-10-20 Honda 实测）：AAC-LC 通路建好后车机**在实时消费样本**
     * （`/feedback` 的 `sampleTime` 按 44.1k 走），但喇叭全静音。真 iPhone 在会话里
     * 会主动下发 `SET_PARAMETER volume`；接收端的 AirPlay 增益若停留在静音位
     * （-144dB），表现就正好是「数据在流、声音没有」。
     *
     * 这里只做两件事：① 把车机当前音量记进日志（这是判断「静音位」的直接证据）；
     * ② 若确实处于静音位（≤[MUTE_THRESHOLD_DB]），抬到 [SAFE_VOLUME_DB]。
     * 查询失败/车机不支持都不影响主链路。
     */
    private fun probeAndFixVolume(host: String) {
        val url = "rtsp://$host:${endpoint.airPlayPort}/"
        val volume: Float? =
            try {
                val (status, body) =
                    request(
                        "GET_PARAMETER",
                        url,
                        "volume".toByteArray(Charsets.US_ASCII),
                        timeoutMillis = 4_000,
                        contentType = "text/parameters",
                    )
                val text = body.toString(Charsets.UTF_8).trim()
                log("AIRPLAY: 车机音量查询 $status body=${text.take(160)}")
                VOLUME_REGEX
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
            } catch (error: Exception) {
                log("AIRPLAY: 车机音量查询失败 - ${error.message}（忽略，不影响音频流）")
                null
            }
        // 查得到且不在静音位：尊重车机当前音量，不再覆盖。
        if (volume != null && volume > MUTE_THRESHOLD_DB) return
        // 查不到（Honda 实测返回 501 Not Implemented）时也要下发一次：
        // 原来的实现直接 return，等于整个会话从没设置过接收端音量——若车机 AirPlay
        // 增益停在静音位，症状就是「数据在流、喇叭无声」，而我们连日志都拿不到。
        try {
            val payload = String.format(java.util.Locale.US, "volume: %.6f", SAFE_VOLUME_DB)
            val (status, body) =
                request(
                    "SET_PARAMETER",
                    url,
                    payload.toByteArray(Charsets.US_ASCII),
                    timeoutMillis = 4_000,
                    contentType = "text/parameters",
                )
            log(
                if (volume == null) {
                    "AIRPLAY: 车机不支持音量查询 → 仍下发音量 $SAFE_VOLUME_DB dB（$status ${body.size}B）"
                } else {
                    "AIRPLAY: 车机音量在静音位（$volume dB）→ 已设为 $SAFE_VOLUME_DB dB（$status ${body.size}B）"
                },
            )
        } catch (error: Exception) {
            log("AIRPLAY: 设置车机音量失败 - ${error.message}")
        }
    }

    /**
     * 按车机 `/info` 的 audioFormats / audioLatencies 生成**候选音频档位**（有序）。
     *
     * 与旧实现（只返回一个「协商结果」）的差别（2026-10-19 Honda HFT 实测）：
     * 车机常常「接受某档位的 SETUP、给 dataPort、照收 UDP 包，但内部音频通路起不来」，
     * 几秒后整条会话被拆。因此调用方必须能**换档重试**，这里返回全部合法档位。
     *
     * 规则：
     *  1. 只取 `type=100`（主音频）；
     *  2. 每个 `audioType` 档位按自己的 `audioOutputFormats` 位图匹配 PCM 格式
     *     （48k 立体声 bit15 优先，其次 44.1k 立体声 bit11）；
     *  3. 排序：默认 `media` 档优先（真 iPhone 媒体音频通路，奥迪实测唯一能出声的档）；
     *     [preferCompat]=true 时反过来把非 media（compatibility 等）排前面——
     *     部分车机 media 档只声明 44.1k 且其音频通路只吃 48k，只能用兼容档；
     *  4. 延迟取该 (type, audioType) 在 audioLatencies 的 outputLatencyMicros，
     *     取不到用 [AUDIO_LATENCY_FALLBACK_MS]。
     * 拿不到 /info 时回退协议基线（media + PCM48k + 250ms），与改动前一致。
     */
    fun audioCandidates(preferCompat: Boolean = false): List<AudioProfile> {
        val baseline =
            listOf(
                AudioProfile(AUDIO_TYPE_MEDIA, AUDIO_FORMAT_PCM_48K_STEREO, 48_000, AUDIO_LATENCY_FALLBACK_MS),
            )
        val info = negotiatedInfo ?: return baseline
        val declared =
            (info["audioFormats"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                .orEmpty()
        if (declared.isEmpty()) {
            log("AIRPLAY: 车机 /info 未声明 audioFormats → 按协议基线尝试")
            return baseline
        }

        // ---- 1) AAC-LC 通路：MainHighAudio(102) 优先，其次 type=100 ----
        // 车机若在 102/media 上声明 AAC-LC，说明它有一条独立的高质量媒体音频通路
        // （真 iPhone 媒体音频走这条）。往 100 推 PCM 的表现为：SETUP 回 200、给 dataPort、
        // 但 controlPort=0、/feedback 里 sampleTime 恒 0，约 6.5s 后整条会话被拆。
        val aac = mutableListOf<AudioProfile>()
        val aacOptions =
            listOf(
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
            )
        aacOptions.forEach { (streamType, bit, rate) ->
            declared
                .filter { ((it["type"] as? Number)?.toLong()) == streamType }
                .forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val audioType = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    // 媒体音频只走 media 档；compatibility/default 是提示音/兼容场景
                    if (audioType != AUDIO_TYPE_MEDIA) return@forEach
                    aac +=
                        AudioProfile(
                            audioType = audioType,
                            formatBit = bit,
                            sampleRate = rate,
                            latencyMs = latencyFor(info, audioType, streamType),
                            streamType = streamType,
                            codec = AudioCodec.AAC_LC,
                        )
                }
        }

        // ---- 2) PCM 通路（只存在于 type=100）----
        val formats = declared.filter { ((it["type"] as? Number)?.toLong()) == AUDIO_STREAM_TYPE }
        val pcm = mutableListOf<AudioProfile>()
        // 只考虑「媒体类」档位：车机还会声明 telephony / speechRecognition / alert 等档，
        // 它们位图里同样有 PCM48k，但把媒体流推进语音/提示音通路只会得到怪声或无声。
        // media / compatibility / default 这三档是收发两端都当作媒体通路的档位。
        val mediaTypes =
            formats.filter { channel ->
                val type = (channel["audioType"] as? String)?.lowercase()
                type == AUDIO_TYPE_MEDIA || type == "compatibility" || type == "default"
            }
        val pool = mediaTypes.ifEmpty { formats }
        // 外层按格式（48k → 44.1k），内层按车机声明顺序：同档位时 48k 自然排在前面
        listOf(AUDIO_FORMAT_PCM_48K_STEREO to 48_000, AUDIO_FORMAT_PCM_44K_STEREO to 44_100)
            .forEach { (bit, rate) ->
                pool.forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val type = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    pcm += AudioProfile(type, bit, rate, latencyFor(info, type, AUDIO_STREAM_TYPE))
                }
            }
        // 媒体档最优先；其余按 compatibility → default 的熟悉度排序（组内保持 48k 先）
        val priority = { type: String ->
            when {
                type == AUDIO_TYPE_MEDIA && !preferCompat -> 0
                type == "compatibility" && !preferCompat -> 1
                type == "compatibility" && preferCompat -> 0
                type == AUDIO_TYPE_MEDIA && preferCompat -> 2
                else -> 3
            }
        }
        // 「兼容档」= 强制走 PCM 旧通路（跳过 AAC）：AAC-LC 在某些车机上会被 SETUP 直接
        // 拒掉，留一个人工入口，免得自动候选每次都先撞一次墙。
        val ordered =
            (if (preferCompat) emptyList() else aac.distinctBy { it.key }) +
                pcm.sortedBy { priority(it.audioType) }
        if (ordered.isEmpty()) {
            log(
                "AIRPLAY: 车机未声明可用的 AAC-LC / PCM 立体声格式" +
                    "（共 ${declared.size} 档）→ 仍按基线尝试",
            )
            return baseline
        }
        log("AIRPLAY: 音频候选（按偏好）=${ordered.map { it.label }}")
        return ordered.distinctBy { it.key }
    }

    /** 该 (type, audioType) 档位声明的输出延迟（ms）；无有效值用兜底。 */
    private fun latencyFor(
        info: Map<*, *>,
        audioType: String,
        streamType: Long = AUDIO_STREAM_TYPE,
    ): Long {
        val declaredMs =
            (info["audioLatencies"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull {
                    ((it["type"] as? Number)?.toLong()) == streamType &&
                        (it["audioType"] as? String)?.lowercase() == audioType
                }?.let { (it["outputLatencyMicros"] as? Number)?.toLong() }
                ?.takeIf { it > 0 }
                ?.let { it / 1000 }
        // 下限 250ms：车机声明的 outputLatencyMicros 只是它自己的输出延迟（实测 35ms），
        // 拿它当端到端缓冲会让 WiFi 抖动直接变成欠载。catplay 对 AirPlay 镜像同样是
        // `max(声明值, 250ms)`。
        return declaredMs?.coerceAtLeast(AUDIO_LATENCY_FALLBACK_MS) ?: AUDIO_LATENCY_FALLBACK_MS
    }

    /**
     * 车机身份文本（音频档位记忆的键来源）：只用 /info 里稳定的 model + manufacturer，
     * 蓝牙名/固件串会随重连变化，不能进键。
     */
    fun receiverIdentity(): String {
        val info = negotiatedInfo ?: return "unknown"
        val manufacturer = (info["manufacturer"] as? String).orEmpty().trim()
        val model = (info["model"] as? String).orEmpty().trim()
        return "$manufacturer $model".trim().ifBlank { "unknown" }
    }

    /**
     * SETUP #2：协商主屏视频流（type 110）。streamConnectionID 由客户端指定，
     * 两端各自按会话加密方式派生流密钥；接收端开 TCP 数据端口并在应答里返回。
     *
     * [sessionKey]：pair-verify 派生的共享密钥；null = 未配对会话（`et=NONE`），
     * 按 catplay 走 AES-128-CTR + 全零会话密钥。
     */
    fun runScreenStream(
        sessionKey: ByteArray?,
        displayUuid: String? = null,
    ): ScreenStreamInfo? {
        // 对照 catplay（真车验证）：streamConnectionID = 随机 u64（+1 保证非零）
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val streamUuid =
            displayUuid ?: java.util.UUID
                .randomUUID()
                .toString()
        log(
            "AIRPLAY: 主屏流 SETUP uuid=$streamUuid " +
                if (displayUuid == null) {
                    "（⚠️ /info 未提供 displayUuid，只能用随机值——车机可能收下流却不绑到任何屏）"
                } else {
                    "（来自 /info displays[].uuid）"
                },
        )
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to SCREEN_STREAM_TYPE, // 110 = 主屏（100 是音频，DiPlay 会开 UDP 端口）
                                "streamConnectionID" to connectionId,
                                // 真实 iPhone / catplay 发送端：uuid 与 latencyMs 都必带。
                                // uuid 必须是接收端 /info displays[] 声明的显示屏 UUID（真车会校验；
                                // 随机值曾致 Audi 直接 400 拆会话）。latencyMs=25 为 catplay 实测值。
                                "uuid" to streamUuid,
                                "latencyMs" to SCREEN_LATENCY_MS,
                            ),
                        ),
                ),
            )
        // 请求路径与 catplay 完全一致：流 SETUP 走 "/"（初始 SETUP 走 "/setup"）。
        // 部分接收端（Audi 固件）对路径敏感，路径不符会整包 400。
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "/",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: 流协商失败 - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log(
                "AIRPLAY: 流协商应答 [$statusLine]（响应头：${lastResponseHeadOfRequest()}）\n" +
                    "  ↳ 请求体(${body.size}B)=" + body.joinToString("") { "%02x".format(it) },
            )
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: 流协商应答解析失败")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: 流协商应答缺少 streams")
                return null
            }
        val screen = streams.firstOrNull() as? Map<*, *>
        val dataPort = portOf(screen?.get("dataPort"))
        if (screen == null || dataPort == 0) {
            log("AIRPLAY: 主屏流被拒绝（streams=$streams）")
            return null
        }
        val cipher =
            if (sessionKey != null) {
                StreamFrameCipher.ChaCha(AirPlayStreamKeys.chachaStreamKey(sessionKey, connectionId))
            } else {
                val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, connectionId)
                StreamFrameCipher.AesCtr(key, iv)
            }
        log(
            "AIRPLAY: ✓ 主屏流协商成功 dataPort=$dataPort connId=$connectionId " +
                "加密=${if (cipher is StreamFrameCipher.ChaCha) "ChaCha20" else "AES-128-CTR（未配对会话）"}",
        )
        // MIB3：屏幕流 SETUP 200 拿到 dataPort 后，真机此时在控制通道发 detect（Frida
        // 时序：screen SETUP → phone detect on control → HU SYN on control）。见 armMib3VirtualLink。
        armMib3VirtualLink()
        return ScreenStreamInfo(connectionId, dataPort, cipher)
    }

    /** RECORD：激活会话（接收端 onSessionActive）。路径用相对 `/`（catplay record() 同款）。 */
    fun record(): Boolean =
        try {
            val (statusLine, _) =
                request(
                    "RECORD",
                    "/",
                    BinaryPlist.encode(linkedMapOf<String, Any?>()),
                    contentType = "application/x-apple-binary-plist",
                )
            val ok = statusLine.contains("200")
            log(if (ok) "AIRPLAY: ✓ RECORD 完成——会话已激活" else "AIRPLAY: RECORD 应答 [$statusLine]")
            ok
        } catch (error: IOException) {
            log("AIRPLAY: RECORD 失败 - ${error.message}")
            false
        }

    /**
     * 连接事件通道（接收端在 SETUP 应答的 eventPort 上等我们）。
     *
     * [sessionKey] 非 null 时用 Events 密钥加密（注意方向标签与直觉相反：接收端用
     * Events-Read 读我们的数据）；**null = 未配对会话，通道全程明文**（catplay
     * `EventsServer::unencrypted` 同款，pre-HomeKit 老车机只认这种）。
     * 读取线程把明文按 RTSP 报文重组、解析 plist 命令（如 hidSendReport 触摸）并回调 [onCommand]。
     */
    fun connectEventChannel(
        sessionKey: ByteArray?,
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
            log("EVENT: ✓ 已连接事件通道 $host:${setup.eventPort}（${if (sessionKey != null) "加密" else "明文"}）")
            val cipher =
                sessionKey?.let { secret ->
                    val phoneWriteKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Read-Encryption-Key".toByteArray(),
                        )
                    val phoneReadKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Write-Encryption-Key".toByteArray(),
                        )
                    ControlCipher(phoneReadKey, phoneWriteKey)
                }
            registerEventCommandChannel(socket, cipher)
            Thread({
                val input = socket.getInputStream()
                val accumulator = RtspAccumulator()
                var pending = ByteArray(0)
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
                            log("EVENT: 通道关闭（EOF）")
                            break
                        }
                        pending += buffer.copyOf(count)
                        val plain =
                            if (cipher == null) {
                                val all = pending
                                pending = ByteArray(0)
                                all
                            } else {
                                val decrypted = cipher.decrypt(pending)
                                pending = decrypted.rest
                                decrypted.data
                            }
                        var eventOtherLogged = 0
                        for (message in accumulator.offer(plain)) {
                            val firstLine = message.firstLine
                            // (a) 状态行（"RTSP/1.0 200 OK" 等）= 车机对**我们命令**的应答：
                            //     静默跳过——不回、不记录（回应答会触发乒乓回声，见 respondEventRequest）
                            if (firstLine.startsWith("RTSP/") || firstLine.startsWith("HTTP/")) continue
                            // (b) 请求判定：方法 token + "/"-开头路径。畸形行（如 "OK 200 OK"：
                            //     第 2 token 是数字）判为非请求 → 静默丢弃，不回不记
                            val tokens = firstLine.split(" ")
                            val isRequest = tokens.size >= 2 && tokens[1].startsWith("/")
                            if (!isRequest) continue
                            // 车机请求：非 /command 立即回空 200；/command **先解析后应答**——
                            // changeModes 的 200 必须带 status+params 体（空体曾让奥迪黑屏后
                            // ~5s 拆链，AADisplay CarPlayEventChannel.kt:169 原话；2026-10-24
                            // 8015 置灰案的放大器）。
                            val cseq = CSEQ_REGEX.find(message.head)?.groupValues?.get(1)
                            if (!firstLine.contains("/command")) {
                                respondEventRequest(cseq)
                                unknownEventRequests++
                                if (unknownEventRequests <= 10) {
                                    log("EVENT: 未知请求 [$firstLine] body=${message.body.size}B")
                                }
                                continue
                            }
                            var commandError: String? = null
                            val parsed =
                                try {
                                    val result = BinaryPlist.parseRecovered(message.body)
                                    result.note?.let { note ->
                                        log("EVENT: /command 容错解析成功——$note")
                                    }
                                    result.value
                                } catch (error: Throwable) {
                                    // 不能只 catch IllegalArgumentException：越界/长度异常若逃出
                                    // 这里会直接打死事件读线程，车机会把整个会话拆掉。
                                    commandError = "${error.javaClass.simpleName}: ${error.message}"
                                    null
                                }
                            val command = parsed as? Map<*, *>
                            if (command == null) {
                                respondEventRequest(cseq)
                                // 车机每秒都会 POST 一条解不出来的 /command——按「体型指纹」
                                // 去重，每种体型留一次全文（原因 + 报文头 + 完整 hex），其余只计数。
                                badEventCommands++
                                val signature =
                                    message.body.size.toString() + ":" + hexPrefix(message.body, 8)
                                if (badEventSignatures.add(signature) && badEventSignatures.size <= 4) {
                                    log(
                                        "EVENT: 命令无法识别 [$firstLine] body=${message.body.size}B " +
                                            "原因=" + (commandError ?: "解析结果=${parsed?.javaClass?.simpleName}"),
                                    )
                                    log(
                                        "EVENT: 命令报文头=" +
                                            message.head.replace("\r\n", " | ").take(300),
                                    )
                                    log("EVENT: 命令报文体（${message.body.size}B）hex=" + hexDump(message.body))
                                }
                                if (badEventCommands % 120 == 0) {
                                    log("EVENT: 累计 $badEventCommands 条 /command 解析失败（已静默，仅计数）")
                                }
                                continue
                            }
                            if (command["type"] == "changeModes") {
                                val restored = onCarModesEvent(command["params"] as? Map<*, *>)
                                respondEventRequest(cseq, modesResponseBody())
                                if (restored) onModesRestored?.invoke()
                            } else {
                                respondEventRequest(cseq)
                            }
                            try {
                                onCommand(command)
                            } catch (error: Exception) {
                                log("EVENT: 命令处理异常 - ${error.message}")
                            }
                        }
                    }
                } catch (error: IOException) {
                    log("EVENT: 读取结束 - ${error.message}")
                    // 事件通道断开 = 整条会话将拆：虚拟链路的 MFi 静默计时必须停
                    // （断链≠静默，防止把该车所有挑战档位污染成快速跳过）
                    iapLink?.markLinkBroken()
                }
            }, "event-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: IOException) {
            log("EVENT: 连接失败 - ${error.message}")
        }
    }

    /** 流式 RTSP 报文重组器：喂入任意分片明文，弹出完整消息（首行 + body）。 */
    private class RtspAccumulator {
        /** 完整报文：首行（分类用）、头部全文（提取 CSeq）、body。 */
        class Message(
            val firstLine: String,
            val head: String,
            val body: ByteArray,
        )

        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<Message> {
            if (bytes.isEmpty()) return emptyList()
            buffer += bytes
            val messages = ArrayList<Message>()
            while (true) {
                val index = indexOf(buffer, HEAD_SEPARATOR)
                if (index < 0) break
                val headText = String(buffer, 0, index, Charsets.ISO_8859_1)
                val (firstLine, contentLength) = parseRtspHead(headText)
                val bodyStart = index + HEAD_SEPARATOR.size
                if (buffer.size - bodyStart < contentLength) break
                messages +=
                    Message(
                        firstLine,
                        headText,
                        buffer.copyOfRange(bodyStart, bodyStart + contentLength),
                    )
                buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            }
            return messages
        }
    }

    /**
     * `GET /info`（catplay `AirPlayRtspClient::info` 同款）：
     * body 是**空的 bplist 字典**（不是零长度），Content-Type 必须是
     * `application/x-apple-binary-plist`。老固件（210.81 时代）对无 body/错
     * Content-Type 的 /info 会回 400/455，后续 SETUP 直接失败。
     */
    private fun requestInfo(timeoutMillis: Int = 10_000): Pair<String, ByteArray> =
        request(
            "GET",
            "/info",
            BinaryPlist.encode(linkedMapOf<String, Any?>()),
            timeoutMillis,
            contentType = "application/x-apple-binary-plist",
        )

    // ---- RTSP 请求 ----

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

    private fun post(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val (statusLine, responseBody) = request("POST", path, body)
        if (!statusLine.contains("200")) {
            throw IOException("POST $path 应答 [$statusLine]")
        }
        return responseBody
    }

    /**
     * 配对请求（pair-setup / pair-verify）：Apple 客户端与 catplay PairingHelperTx 同款头。
     * carplay 真车实测（catplay TX 方向工作于真实车机）：
     *  - Content-Type: application/pairing+tlv8
     *  - X-Apple-HKP: 2（HomeKit 配对 v2，Apple 客户端的标识头）
     *  - X-Apple-PD: 1（Pair-Derive，pair-verify 专用）
     * 2026-10-11：此前发默认 application/octet-stream 且无这两个头，奥迪对
     * pair-setup/pair-verify 全部回 403（HTTP 层拒绝，未进入 TLV 解析）。
     */
    private fun postPairing(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val extra =
            buildList {
                add("X-Apple-HKP" to "2")
                if (path.endsWith("pair-verify")) add("X-Apple-PD" to "1")
            }
        val (statusLine, responseBody) =
            request(
                "POST",
                path,
                body,
                contentType = "application/pairing+tlv8",
                extraHeaders = extra,
            )
        // 诊断（真车 403 排查）：请求长度 + 应答状态行/长度/头部字节
        val headHex = responseBody.take(16).joinToString(" ") { "%02x".format(it) }
        log(
            "AIRPLAY: $path req=${body.size}B -> [$statusLine] resp=${responseBody.size}B" +
                if (responseBody.isNotEmpty()) " head=[$headHex]" else "",
        )
        if (!statusLine.contains("200")) {
            throw IOException("POST $path 应答 [$statusLine]")
        }
        return responseBody
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray,
        timeoutMillis: Int = 10_000,
        contentType: String = "application/octet-stream",
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): Pair<String, ByteArray> {
        val client = socket ?: throw IOException("未连接")
        val startedAt = SystemClock.uptimeMillis()
        startReader(client)
        val queue = java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>(1)
        val cseq: Int
        synchronized(rtspWriteLock) {
            cseq = ++cSeq
            // 先登记再写：响应可能在 write 返回前就到达
            pendingResponses[cseq] = queue
            try {
                val head =
                    "$method $path RTSP/1.0\r\n" +
                        "CSeq: $cseq\r\n" +
                        "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                        "Content-Type: $contentType\r\n" +
                        extraHeaders.joinToString("") { "${it.first}: ${it.second}\r\n" } +
                        "Content-Length: ${body.size}\r\n" +
                        "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val encryption = cipher
                val wire =
                    if (encryption != null) {
                        synchronized(encryption) { encryption.encrypt(plaintext) }
                    } else {
                        plaintext
                    }
                val output = client.getOutputStream()
                output.write(wire)
                output.flush()
            } catch (error: Throwable) {
                pendingResponses.remove(cseq)
                throw error
            }
        }
        val response =
            try {
                queue.poll(timeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: throw IOException("RTSP 超时（${timeoutMillis}ms，CSeq=$cseq $method $path）")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("RTSP 被中断（CSeq=$cseq $method $path）")
            } finally {
                pendingResponses.remove(cseq)
            }
        // 慢应答留痕：>1.5s 说明车机在内部仲裁（同类问题排查的第一手证据）
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (elapsed > 1_500) {
            log("AIRPLAY: 慢应答 ${elapsed}ms（$method $path，CSeq=$cseq）——等待期间心跳未中断")
        }
        if (response.first == RTSP_CLOSED_MARK) {
            throw IOException("控制通道已关闭（CSeq=$cseq $method $path）")
        }
        lastRequestCSeq.set(cseq)
        return response
    }

    /**
     * 控制通道读线程：统一收包、解密、按 CSeq 把响应派发给对应请求。
     *
     * 为什么必须异步（2026-10-17 实测根因）：奥迪的音频 SETUP 应答要 ~2.7s，此前
     * 「一条连接一把锁 + 同步读响应」会让 feedback 心跳在这段时间完全停摆；车机对
     * feedback 有 ~3s 看门狗——音频 SETUP 期间断供即 reset 事件通道并拆掉整轮会话
     * （日志特征：feedback #1 与 #2 间隔 2.7s、随后 `EVENT: 读取结束 - Connection reset`）。
     * 改为异步后任何长请求都不再阻塞心跳。
     */

    /**
     * 读线程按「加密是否已启用」切分缓冲区：
     * cipher 从 null 变为非 null 的那一刻（pair-verify 完成），此前的明文残渣必须丢弃——
     * 若把明文尾巴拼进密文帧解析，ChaCha 帧边界会错位、后续所有应答都解不出来。
     */
    private fun readLoop(
        client: Socket,
        generation: Int,
    ) {
        var cipherPending = ByteArray(0)
        var buffer = ByteArray(0)
        var encryptedMode = false
        try {
            val input = client.getInputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                val encryption = cipher
                if (encryption != null && !encryptedMode) {
                    // 切换点：清掉明文侧残留，之后一律走帧解密
                    encryptedMode = true
                    cipherPending = ByteArray(0)
                }
                val data =
                    if (encryption == null) {
                        chunk.copyOf(count)
                    } else {
                        cipherPending += chunk.copyOf(count)
                        val decrypted =
                            try {
                                synchronized(encryption) { encryption.decrypt(cipherPending) }
                            } catch (error: Exception) {
                                log("AIRPLAY: 控制通道解密失败 - ${error.message}")
                                break
                            }
                        cipherPending = decrypted.rest
                        decrypted.data
                    }
                if (data.isEmpty()) continue
                buffer += data
                while (true) {
                    val parsed = parseRtspMessage(buffer) ?: break
                    buffer = parsed.rest
                    dispatchResponse(parsed.statusLine, parsed.body, parsed.head)
                }
            }
        } catch (_: IOException) {
        } finally {
            // 只由「当前」读线程清账：重连会换 socket 与新读线程，旧线程不得误伤新会话
            if (generation == readerGeneration) {
                failAllPending("控制通道读线程结束")
            }
        }
    }

    /** 控制通道报文（CSeq 派发用；head 供诊断与请求/应答分类）。 */
    private class RtspMessage(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
    ) {
        val cseq: Int? get() =
            CSEQ_REGEX
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /** 车机主动发来的请求（首行是 `METHOD /path RTSP/1.0`）；应答首行是 `RTSP/1.0 200 OK`。 */
        val isRequest: Boolean
            get() {
                val tokens = statusLine.split(" ")
                return tokens.size >= 2 && tokens[1].startsWith("/")
            }
    }

    private fun dispatchResponse(
        statusLine: String,
        body: ByteArray,
        head: String,
    ) {
        val message = RtspMessage(statusLine, body, head)
        message.cseq?.let { responseHeads[it] = head }
        val queue = message.cseq?.let { pendingResponses[it] }
        if (queue != null) {
            queue.offer(message.statusLine to message.body)
            return
        }
        // 无匹配：车机可能在控制通道上发自己的请求（须回 200 否则它会拆通道）。
        // 仅节流留痕，不静默丢弃——此前同步实现会把它错当成前一个请求的应答。
        unmatchedMessages++
        if (unmatchedMessages <= 5) {
            log(
                "AIRPLAY: 控制通道无匹配报文（#$unmatchedMessages）" +
                    "[$statusLine] cseq=${message.cseq} request=${message.isRequest} body=${body.size}B",
            )
        }
        if (message.isRequest) {
            // 复用请求 CSeq 回空应答（与事件通道同一约定）
            runCatching {
                val payload =
                    "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                        (message.cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val encryption = cipher
                synchronized(rtspWriteLock) {
                    val out = socket?.getOutputStream() ?: return@runCatching
                    out.write(
                        if (encryption != null) {
                            synchronized(encryption) { encryption.encrypt(payload.toByteArray(Charsets.US_ASCII)) }
                        } else {
                            payload.toByteArray(Charsets.US_ASCII)
                        },
                    )
                    out.flush()
                }
            }
        }
    }

    /** 无匹配报文计数（节流日志用）。 */
    private var unmatchedMessages = 0

    private fun failAllPending(reason: String) {
        val waiting = pendingResponses.values.toList()
        pendingResponses.clear()
        if (waiting.isEmpty()) return
        log("AIRPLAY: $reason——唤醒 ${waiting.size} 个待响应请求")
        waiting.forEach { it.offer(RTSP_CLOSED_MARK to ByteArray(0)) }
    }

    private fun startReader(client: Socket) {
        val previous: Socket?
        synchronized(rtspWriteLock) {
            if (readerSocket === client && readerThread?.isAlive == true) return
            previous = readerSocket
            readerSocket = client
            val generation = ++readerGeneration
            readerThread =
                Thread({ readLoop(client, generation) }, "airplay-rtsp-reader").apply {
                    isDaemon = true
                    start()
                }
        }
        // 旧 socket 由本线程持有其输入流：必须关掉（会抛 SocketException 让旧读线程退出），
        // 否则两个读线程可能阻塞在同一连接上抢包——异步派发后这会导致响应错投
        if (previous != null && previous !== client) {
            runCatching { previous.close() }
        }
    }

    /** 待响应的 CSeq → 响应队列（异步派发；feedback 不再被长请求饿死）。 */
    private val pendingResponses =
        java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>>()

    /** 写侧串行：CSeq 分配 + 加密器写计数 + socket 写必须原子（读侧在独立线程）。 */
    private val rtspWriteLock = Any()

    private var readerThread: Thread? = null
    private var readerSocket: Socket? = null

    /** 读线程代次：重连后旧线程不得清理新会话的待响应表。 */
    private var readerGeneration = 0

    /** 最近一次 RTSP 响应头（按 CSeq 存：读线程与请求线程并发，单一字段会错配到别的请求）。 */
    private val responseHeads = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** 本线程最近一次 request() 的 CSeq（诊断取响应头用；ThreadLocal 避免并发错配）。 */
    private val lastRequestCSeq = ThreadLocal<Int>()

    /** 取本线程最近一次请求的响应头（诊断用；读不到返回空串）。 */
    private fun lastResponseHeadOfRequest(): String =
        lastRequestCSeq
            .get()
            ?.let { responseHeads[it] }
            ?.replace("\r\n", " | ")
            .orEmpty()

    /** 解析结果（报文 + 未消费的剩余字节）。 */
    private class ParsedRtsp(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
        val rest: ByteArray,
    )

    /**
     * 从缓冲区切出完整报文（头 + Content-Length body）。
     * 只在控制通道读线程里调用（缓冲区是该线程的局部状态，无跨线程共享）。
     */
    private fun parseRtspMessage(buffer: ByteArray): ParsedRtsp? {
        val separatorIndex = indexOf(buffer, HEAD_SEPARATOR)
        if (separatorIndex < 0) return null
        val headText = String(buffer, 0, separatorIndex, Charsets.ISO_8859_1)
        val (statusLine, contentLength) = parseRtspHead(headText)
        val bodyStart = separatorIndex + HEAD_SEPARATOR.size
        if (buffer.size - bodyStart < contentLength) return null
        val responseBody = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        val rest =
            if (buffer.size > bodyStart + contentLength) {
                buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            } else {
                ByteArray(0)
            }
        return ParsedRtsp(statusLine, responseBody, headText, rest)
    }

    // ---- 身份持久化 ----

    private fun loadOrCreateIdentity(): ControllerIdentity {
        val preferences = context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val storedId = preferences?.getString("pairingId", null)
        val storedPrivate = preferences?.getString("privateKey", null)
        if (storedId != null && storedPrivate != null) {
            val privateKey = Base64.getDecoder().decode(storedPrivate)
            val publicKey = AirPlayCrypto.ed25519PublicKey(privateKey)
            return ControllerIdentity(storedId, privateKey, publicKey)
        }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val pairingId = UUID.randomUUID().toString()
        preferences
            ?.edit()
            ?.putString("pairingId", pairingId)
            ?.putString("privateKey", Base64.getEncoder().encodeToString(keyPair.privateKey))
            ?.apply()
        return ControllerIdentity(pairingId, keyPair.privateKey, keyPair.publicKey)
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
    private fun setupDeviceId(): String {
        val applicationContext = context ?: return DeviceIdentity.PLACEHOLDER_MAC
        // 缓存命中即返回（BT 阶段已解析）；缺失时才允许一次阻塞查询。
        return DeviceIdentity.cachedBluetoothMac()
            ?: DeviceIdentity.bluetoothMac(applicationContext)
    }

    /**
     * type-130 channelID 的伪 MAC（"02:xx:xx:xx:xx:xx"，冒号分隔大写）：SHA-256(本机
     * 持久化 UUID) 前 5 字节。首次生成后跨会话不变（SharedPreferences）——接收端可能
     * 把 channelID 当隧道对端标识记忆，每轮换值会造成「陌生客户端」。刻意与
     * deviceID 的真实蓝牙 MAC 无关（参考实现同款，二者不对账）。
     */
    private fun persistentTunnelChannelMac(): String? =
        runCatching {
            val prefs =
                context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
                    ?: return@runCatching null
            var uuid = prefs.getString("tunnel_channel_uuid", null)
            if (uuid == null) {
                uuid =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                prefs.edit().putString("tunnel_channel_uuid", uuid).apply()
            }
            val digest =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(uuid.toByteArray())
            return@runCatching "02:" + (0 until 5).joinToString(":") { "%02X".format(digest[it]) }
        }.getOrNull()

    /**
     * 构造会话级 SETUP 报文体（拆出来是为了单测：deviceID 一旦退回写死假值，
     * WinPlay 这类按设备过滤的接收端会直接 403，且现象是 45 秒后才断开）。
     */
    internal fun buildSessionSetupBody(
        deviceId: String,
        timingPort: Int,
        /** false = 未配对/未加密会话：必须显式带 `et=NONE`（catplay do_initial_setup 同款）。 */
        encrypted: Boolean = true,
    ): ByteArray {
        val fields =
            linkedMapOf<String, Any?>(
                "deviceID" to deviceId,
                "macAddress" to "aa:bb:cc:dd:ee:ff",
                "name" to "iPhone",
                "model" to "iPhone14,2",
                "osName" to "iPhone OS",
                "osBuildVersion" to "19A404",
                "osVersion" to "15.0.2",
                // iPhone 自己的 AirPlay 版本（catplay 实证值）；此前误用车机的 320.17.7
                "sourceVersion" to SENDER_SOURCE_VERSION,
                "timingPort" to timingPort.toLong(),
                "sessionUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "sessionCorrelationUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "statsCollectionEnabled" to false,
                "updateSessionRequest" to false,
                "keepAliveLowPower" to true,
                "features" to CONTROLLER_FEATURES,
            )
        if (!encrypted) {
            // AirPlayEncryptionType::NONE = 1<<0（catplay 未走 /pair-setup 时的降级值）。
            // 缺这个键时严格接收端可能把会话当成「加密但无密钥」而拒绝。
            fields["et"] = 1L
        }
        return BinaryPlist.encode(fields)
    }

    private fun typeName(value: Any?): String =
        when (value) {
            null -> "null"
            is ByteArray -> "data(${value.size}B)"
            is List<*> -> "list(${value.size})"
            is Map<*, *> -> "dict(${value.size})"
            is String -> "str(${value.length})"
            else -> value.javaClass.simpleName
        }

    /**
     * `/info displays[].uuid` 统一字符串化。
     *
     * 三种编码都见过：字符串（奥迪 "0"/"1"、部分固件给 36 位 UUID）、
     * **16 字节 UUID 类型**（bplist 0xB，`toString()` 会变成 `[B@1f2e3d4` 这种垃圾）、
     * CFKeyedArchiver 的 UID（整数）。屏幕流 SETUP 里的 `uuid` 必须与接收端声明的
     * **完全一致**，否则车机可能收下流却不往任何屏上绑（表现为「连上了没画面」）。
     */
    private fun uuidText(value: Any?): String? =
        when (value) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            is ByteArray ->
                if (value.size == 16) {
                    val hex = value.joinToString("") { "%02x".format(it) }
                    "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                        "${hex.substring(16, 20)}-${hex.substring(20)}"
                } else {
                    value.joinToString("") { "%02x".format(it) }
                }
            is Number -> value.toString()
            else -> value.toString().takeIf { it.isNotBlank() }
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
        // 会被资源仲裁拒绝（HTTP 仍 200，拒绝在响应体）——重申必须冻结至归还
        (root["modes"] as? Map<*, *>)?.let { modes ->
            val previous = modesState
            modesState = parseModesState(modes, previous)
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
 * 配对失败重试退避（纯函数，回放测试钉死）：403 = 真车「信任已建立、车机未就绪」
 * 状态（真机 sysdiagnose：就绪窗约 18~20s，期间高频重试可能一直重置其状态机）；
 * 其余失败（端口未开等）保持快速轮询等邀请。
 */
internal fun pairingRetryBackoffMs(failureMessage: String): Long = if (failureMessage.contains("403")) 5_000L else 500L
