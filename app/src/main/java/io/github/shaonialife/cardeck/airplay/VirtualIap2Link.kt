package io.github.shaonialife.cardeck.airplay

import android.content.Context
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.MfiLengthMemory
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.HidNavigation
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/**
 * 事件通道上的虚拟 iAP2 链路（AirPlay 会话之上的「WiFi 隧道」），一条实现服务三种形态：
 *
 *  - **DiPlay（type-130 隧道）**：出站写隧道 TCP；链路协商用「回显先行」语义
 *    （对端引擎硬性期望，见 [Iap2LinkEngine.echoFirst]）；
 *  - **Audi MIB3（事件通道 + 控制通道 POST）**：真机时序（AADisplay 以 Frida 挂
 *    越狱 iPhone mediaserverd 抓包确认）——**屏幕流 SETUP 成功后由手机主动发一次
 *    DETECT（经控制通道 POST /command 的 iAPSendMessage）→ 车机回 SYN（事件通道）
 *    → 手机回 SYN|ACK（控制通道）**，随后控制会话整段重跑：0x1D00 → 0x1D01 →
 *    0x1D02+0xAA00 →（0xAA06 → 0xAA00）→ 0xAA02 挑战 20B → 0xAA03 → 0xAA05 →
 *    手机突发消息 → 应答车机订阅。**只回显空 ACK 的话 MIB3 约 10~12 秒整链拆除**
 *    （本仓库 2026-10-19 两轮实车日志 10.2s 复现，此前误判为心跳缺失）；
 *  - **旧事件形态 / 明文会话**：marker 一问一答回显（保持历史行为）。
 *
 * 车机→手机的链路字节统一从事件通道 iAPSendMessage 进来（[onCarBytes]）；
 * 手机→车机按形态分流（隧道 TCP / 控制通道 POST <-- 默认）。**方向不能对调**：
 * 出站写在事件通道上车机不读（Frida 结论；2026-10-20 Honda HFT 实测复现——
 * 出站改到事件通道后，车机 20 分钟内一条 FF5A 同步包都没回，只按 1Hz 重发 marker）。
 */
class VirtualIap2Link(
    private val log: (String) -> Unit,
    /** 事件通道 iAPSendMessage 出站（旧形态兜底）。 */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** 控制通道 iAPSendMessage 出站（MIB3 手机侧帧的唯一正确通道）。 */
    private val sendControlIap: (ByteArray) -> Unit,
    /** DiPlay type-130 隧道写入口（每次出站动态取用，可为 null）。 */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** 节拍线程存活条件（宿主控制连接未关）。 */
    private val alive: () -> Boolean,
    /** MFi 长度记忆的持久化上下文；null = 进程内记忆（单测）。 */
    private val mfiContext: Context? = null,
    /** 时钟（单测可注入）。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private val engine = Iap2LinkEngine()
    private val parser = Iap2Csm.Parser()
    private var frameCount = 0
    private var eaCount = 0
    private var started = false

    // ---- DiPlay 隧道路径状态 ----
    private var tunnelCsmStarted = false
    private var tunnelIdentified = false

    /** 同步回显待投递（隧道门闸：回显真正写进隧道 TCP 后才允许发 0x1D00）。 */
    private var pendingEchoFlush = false
    private var tunnelEchoDelivered = false

    // ---- MIB3 路径状态 ----
    private var mib3Armed = false

    /**
     * 车机链路代际（枚举与判定收口在 [ReceiverProfile]，判定依据随 PROFILE/IAPLINK
     * 日志输出）。
     *
     * **2026-10-23 二次定谳：marker 协商不再按代际分派**——所有车机都必须完成链路
     * 协商（车机 marker = 它在等我们的会话表，1Hz 无限重发；不回应则链路永远到不了
     * NORMAL，RECORD 后 ~10s 被车机拆链：0.1.9 实测 10.17s、0.1.17 实测 10.18s）。
     * 此前 0.1.16/0.1.17 曾对 CLASSIC_MIB3 忽略 marker，依据是「0.1.9 忽略 marker
     * 时奥迪照常进入」——该论断被 0.1.9 自己的日志（10.17s 拆链）与 0.1.17 车主日志
     * 推翻。当年「协商挂起车机 UI 卡原界面」的真因是协商完成后缺 modesChanged 重申
     * （见 [onControlSessionComplete]）。
     *
     * 代际现在仅用于日志与未来分派，marker 策略统一：按阈值升级协商（Honda HFT 的
     * 媒体键载体在建链后的 0x6802 里，奥迪的切屏前提是链路 NORMAL + 资源重声明）。
     */
    private var generation: ReceiverProfile.LinkGeneration = ReceiverProfile.LinkGeneration.EVENT_LINK
    private var mib3SynAckQueued = false
    private var mib3CsmStarted = false
    private var mib3Identified = false
    private var mib3AuthStage = 0
    private var mib3MarkerIgnores = 0
    private var mib3DeferredLogs = 0
    private var mib3WirelessSessionId: String? = null
    private var mib3ControlSessionNotified = false

    /**
     * 事件通道链路模式（2026-10-20 Honda HFT 实测新增）。
     *
     * 车机在**事件通道**上以发起方姿态反复发 SYN marker（`FF 55 02 00 EE 10`，
     * 每秒一次、连续上百次），等的是我们对它回一个 marker 才会继续发同步包。
     * 而 [armMib3] 的 detect 走的是**控制通道**——车机看不到，于是永远重发。
     *
     * 连续第 [EVENT_LINK_MARKER_THRESHOLD] 个 marker 起判定它确实在等回应，
     * 切到本模式：出入站都走事件通道，marker 应答 + 同步包协商即可建链。
     * 链路建立后车机才会发 0x5000（NowPlaying 订阅）、0x6800（StartHID）、
     * 0x6802（HID 报告 = 方向盘/面板媒体键）——切歌按键的载体就在这里。
     */
    private var eventLinkArmed = false
    private var eventLinkMarkers = 0
    private var eventCsmStarted = false

    /**
     * 控制通道协商轮次（2026-10-20 Honda HFT 定案后新增）。
     *
     * 车机的 marker 从**事件通道入站**，但它的 iAP2 栈只从**控制通道**（RTSP
     * POST /command 的 iAPSendMessage）读我们发的字节——项目原有 Frida 结论就写着
     * 「写在事件通道上时车机根本不读」。r5/r6 把出站也挪到事件通道后，车机连一条
     * FF5A 同步包都不回、1Hz 重发 marker 到天亮，正是这一条被踩中。
     *
     * 现在出站回归控制通道：每轮 [armMib3] / [restartControlNegotiation] 发一次
     * 「marker + 本表会话表」，引擎内部按 500ms/1s 重发；[CONTROL_SYN_ROUND_MILLIS]
     * 无进展就进入下一轮，最多 [MAX_CONTROL_SYN_ROUNDS] 轮，之后停在 NEGOTIATING
     * 静默等待（不再打点，避免把车机栈刷懵）。
     */
    private var controlSynRounds = 0
    private var controlSynRoundStartedAtMs = 0L
    private var controlSynGaveUp = false

    /** 未处理的控制会话消息计数（媒体键排查的观测点，见 [maybeRespondControlCsmLocked]）。 */
    private var unknownCsmIds = 0

    /** 上一次记录过的链路状态（只在对日志里出现一次状态迁移）。 */
    private var lastLinkState = Iap2LinkEngine.State.IDLE

    // ---- 车机 HID 媒体键（0x6802）状态 ----
    /** 上一次报文里按下的 Usage 集合（用于判定「新按下」，松开后自动清空）。 */
    private var lastHidUsages: Set<Int> = emptySet()

    /** 当前这批按键的起始时刻（连续同一按键超过 [HID_HOLD_REPEAT_MILLIS] 才允许连发）。 */
    private var hidHoldStartedAtMs = 0L

    /** 已打印的参数明细帧数（只打前几帧，之后按需打动作）。 */
    private var hidReportFrames = 0

    // ---- MIB3 隧道 MFi 挑战（按车机持久化记忆；与蓝牙链路**独立记**——同一台车
    // 两条链路的认证后端可能不同，实测 MMI 3315 蓝牙认 32B 而 Frida 隧道是 20B） ----
    private var mfiIdentity: String? = null
    private var mfiMemory = MfiLengthMemory.Entry()
    private var mfiOrder: List<Int> = emptyList()
    private var mfiPosition = -1
    private var mfiCurrentLength = 0
    private var mfiWindowMillis = 0L
    private var mfiChallengeSentAtMs = 0L
    private var mfiFinished = false

    init {
        engine.onEaData = { bytes -> handleEaData(bytes) }
        engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
        Thread(
            {
                while (alive()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    synchronized(lock) {
                        engine.advanceTime(System.nanoTime() / 1_000_000L)
                        drainLocked()
                    }
                }
            },
            "iap-link-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 事件形态启动：服务端待命（收到 marker 回 marker，等车机 SYN）。 */
    fun begin() {
        synchronized(lock) {
            if (started) return
            started = true
            engine.enterNegotiating()
        }
    }

    /**
     * MIB3 布防（屏幕流 SETUP 成功后调用，且仅一次）：主动向**控制通道**发一次 DETECT。
     * 必须等 SETUP 完成后再发——早发会在 SETUP 交换期间被冲掉（Frida 结论）；此后
     * 车机的 marker 都是探测重发，真机（MIB3）回一次 SYN 就不再重发。
     *
     * 注意这里**只发裸 marker**：MIB3 的时序是「它回 SYN → 我们回 SYN|ACK」，
     * 提前把会话表拍过去反而会打乱它的状态机（见 [Iap2LinkEngine.startResponder]
     * 的实测记录）。车机若像 Honda HFT 那样反复重发 marker，才由
     * [startControlRoundLocked] 升级成「marker + 本表会话表」并重试。
     */
    fun armMib3() {
        synchronized(lock) {
            begin()
            if (mib3Armed) return
            mib3Armed = true
            sendControlIap(Iap2LinkEngine.MARKER)
            log("IAPLINK: MIB3 detect 已发（控制通道 iAPSendMessage，代际=$generation）——等待车机")
            // 半移交顺序（见 onMib3HandoffReady）：DETECT 发出即拆 BT——iPhone 的 BT
            // 在车机 SYN 与首帧视频之前就已落下；拖到链 NORMAL 再拆会错过车机 HMI
            // 的早期激活窗口（审查定谳：原时点还与 videoSender.isRunning 存在竞态）
            onMib3HandoffReady?.invoke()
        }
    }

    /**
     * 显式声明车机链路代际（布防前后均可，幂等）。代际现仅用于日志与未来分派，
     * marker 协商策略全代际统一（2026-10-23 二次定谳，见 [generation] 文档）。
     */
    fun setGeneration(generation: ReceiverProfile.LinkGeneration) {
        synchronized(lock) {
            if (this.generation == generation) return
            this.generation = generation
            log("IAPLINK: 链路代际 → $generation")
        }
    }

    /**
     * 调试/特殊形态用：强制开启一轮控制通道协商（正常路径由车机 marker 触发）。
     * 一经调用，后续 marker 按协商态处理。
     */
    fun forceControlNegotiation(reason: String) {
        synchronized(lock) {
            if (tunnelWriter() != null) return
            eventLinkArmed = true
            startControlRoundLocked(reason)
        }
    }

    /**
     * MIB3 控制会话完成回调（auth+burst 发出时触发一次）：宿主应在此之后按
     * AADisplay r17 验证过的时序重申 modesChanged 资源声明（+1.5s 首发、
     * +4/+12/+30s 重申）。**082446 定谳**：协商/MFi/订阅全部成功但只在会话
     * 建立早期声明过一次资源 → 车机全程健康却永不切到 CarPlay 画面——第二控制
     * 会话把车机的资源仲裁重置了一遍，旧声明已失效，它在等一份新的。
     * 注意不得在 0xAA00/0xAA05 进行中声明（与 iAP POST 抢控制通道会把奥迪搞死，
     * AADisplay 注释原话），必须等 burst 完成。
     */
    var onControlSessionComplete: (() -> Unit)? = null

    /**
     * MIB3 无线移交启动（armMib3 发出 DETECT 后立即触发，仅控制通道形态）。
     * **半移交模型（2026-10-24 抓包对照定谳）**：车机只在「WiFi 侧 iAP2 会话存在」时
     * 进入无线移交态，该态以「探测被应答 + BT 释放」为完成条件——0.1.0 无 WiFi 侧会话
     * 所以画面照切；0.1.37 会话建成但 BT 不拆、探测零应答，HMI 卡死到重启车机。
     * 真 iPhone 的可观察顺序（sysdiagnose 2026-10-16 17:31）：marker 应答 → 94ms 内
     * 拆 BT → 车机 8ms 后自发 SYN → 0x1D00。宿主据此**在 RECORD 之前**拆 BT，
     * 保证 BT 在首帧视频前已落下（若车机因此不自发 SYN，marker 计数升级协商仍是兜底）。
     * 不适用于 TCP 隧道形态（DiPlay 见 RFCOMM 断开即拆整轮栈）。
     */
    var onMib3HandoffReady: (() -> Unit)? = null

    /**
     * 开一轮控制通道协商：marker + 本表同步包（会话 10/11/12），刷新引擎重发节拍。
     */
    private fun startControlRoundLocked(reason: String) {
        val now = nowMillis()
        controlSynRounds++
        controlSynRoundStartedAtMs = now
        engine.initiate(now)
        log(
            "IAPLINK: 控制通道 marker + 本表同步包（第 $controlSynRounds/$MAX_CONTROL_SYN_ROUNDS 轮，$reason）" +
                "——等车机同步包（FF5A）",
        )
        drainLocked()
    }

    /** 车机链路字节入口（事件通道 iAPSendMessage 的 data）。 */
    fun onCarBytes(bytes: ByteArray) {
        synchronized(lock) {
            // 隧道存在时切换「回显先行」协商语义（DiPlay 对端引擎硬性期望）
            engine.echoFirst = tunnelWriter() != null
            val isMarker =
                bytes.size == 6 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0x55.toByte()
            if (isMarker && tunnelWriter() == null) {
                if (mib3Armed) {
                    mib3MarkerIgnores++
                    // iPhone 行为（sysdiagnose 2026-10-16 17:31:49.921）：车机每条
                    // marker 同毫秒回一条 DETECT。半移交模型里「探测被应答」是移交
                    // 完成的一半条件（另一半是 BT 释放，见 onMib3HandoffReady）。
                    // 经控制通道回（MIB3 不读事件通道出站，Frida/HondaHFT 定谳）。
                    // 仅 CLASSIC_MIB3：EVENT_LINK（Honda）维持既有「计数→升级协商」。
                    if (generation == ReceiverProfile.LinkGeneration.CLASSIC_MIB3) {
                        sendControlIap(Iap2LinkEngine.MARKER)
                        if (mib3MarkerIgnores <= 3 || mib3MarkerIgnores % 20 == 0) {
                            log("IAPLINK: 车机 marker（#$mib3MarkerIgnores）——已回 DETECT（对齐真 iPhone）")
                        }
                    }
                    val now = nowMillis()
                    when (generation) {
                        // 2026-10-23 定谳：**所有代际都必须完成链路协商**。曾按「奥迪忽略
                        // marker」分派（0.1.16/0.1.17），被车主 0.1.17 日志证伪——车机 marker
                        // 以 1Hz **无限重发**（远超旧注释的「2~5 个后静默」），链路永远到不了
                        // NORMAL，RECORD 后 10.18s 车机拆掉整条会话（0.1.9 同款 10.17s）。
                        // 协商完成本身无副作用（082446 全程健康），缺的是协商完成后的
                        // modesChanged 重申（见 onControlSessionComplete）。
                        ReceiverProfile.LinkGeneration.CLASSIC_MIB3,
                        ReceiverProfile.LinkGeneration.EVENT_LINK,
                        -> {
                            if (!eventLinkArmed && mib3MarkerIgnores >= EVENT_LINK_MARKER_THRESHOLD) {
                                eventLinkArmed = true
                                log(
                                    "IAPLINK: 车机连续发 SYN marker ×$mib3MarkerIgnores" +
                                        "（它在等我们的会话表）→ 控制通道补发协商轮次",
                                )
                                startControlRoundLocked("车机 marker 重发")
                            } else if (eventLinkArmed) {
                                eventLinkMarkers++
                                if (engine.state != Iap2LinkEngine.State.NORMAL && !controlSynGaveUp) {
                                    val stale = now - controlSynRoundStartedAtMs >= CONTROL_SYN_ROUND_MILLIS
                                    when {
                                        !stale -> Unit
                                        controlSynRounds >= MAX_CONTROL_SYN_ROUNDS -> {
                                            controlSynGaveUp = true
                                            engine.stopNegotiationRetries()
                                            log(
                                                "IAPLINK: 控制通道 $MAX_CONTROL_SYN_ROUNDS 轮协商无应答——停止自动重发，" +
                                                    "链路停在 ${engine.state}（车机若发 0x1D01 会自动继续）",
                                            )
                                        }

                                        else -> startControlRoundLocked("上一轮 ${CONTROL_SYN_ROUND_MILLIS}ms 无应答")
                                    }
                                }
                                // 引擎节拍线程也会重发，这里把待发字节立刻冲出去
                                drainLocked()
                                if (eventLinkMarkers <= 3 || eventLinkMarkers % 20 == 0) {
                                    log(
                                        "IAPLINK: 车机 marker（#$eventLinkMarkers）链路状态=${engine.state}" +
                                            "（控制通道第 $controlSynRounds 轮）",
                                    )
                                }
                                return
                            } else if (mib3MarkerIgnores <= 3 &&
                                generation != ReceiverProfile.LinkGeneration.CLASSIC_MIB3
                            ) {
                                log("IAPLINK: 车机 marker（#$mib3MarkerIgnores）——凑够 $EVENT_LINK_MARKER_THRESHOLD 个后补发协商")
                            }
                        }
                    }
                } else {
                    // 布防前（屏幕 SETUP 未完成）：不回应——真机在 SETUP 前不 detect，
                    // 事件通道上的回显对 MIB3 无意义（它只认控制通道）；旧明文接收端
                    // 不走此链路语义
                    if (mib3DeferredLogs < 2) {
                        mib3DeferredLogs++
                        log("IAPLINK: SETUP 前的车机 marker 暂不回应（布防后主动 detect）")
                    }
                    return
                }
            }
            engine.feed(bytes, System.nanoTime() / 1_000_000L)
            drainLocked()
        }
    }

    /**
     * 链路字节出站：隧道 TCP（DiPlay）→ 控制通道 POST /command（MIB3 / Honda HFT）→
     * 事件通道（旧明文形态兜底）。
     *
     * **分工是固定的**：车机的链路字节从事件通道 `iAPSendMessage` **入站**，我们的
     * 出站字节只能写控制通道——写在事件通道上时车机不读（Frida 结论，2026-10-20
     * Honda HFT 再次实测：出站挪到事件通道后，车机 20 分钟里一条 FF5A 都没回）。
     */
    private fun sendLinkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> tunnel.writeIapBytes(bytes)
            eventLinkArmed -> sendControlIap(bytes)
            mib3Armed -> sendControlIap(bytes)
            else -> sendEventIap(bytes)
        }
    }

    /**
     * 引擎出站字节落盘：SYN|ACK 真正投递到对应通道后才允许启动 CSM——
     * 对端引擎未见到 SYN|ACK 前收到裸 ACK 会直接 die（DiPlay 实测）。
     */
    private fun flushOutputLocked() {
        val out = engine.takeOutput()
        if (out.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> {
                val ok = tunnel.writeIapBytes(out)
                if (ok && pendingEchoFlush) {
                    pendingEchoFlush = false
                    if (!tunnelEchoDelivered) {
                        tunnelEchoDelivered = true
                        log("IAPLINK: ✓ 同步回显已写入隧道（链路协商完成，发送端 CSM 就绪）")
                    }
                }
            }

            eventLinkArmed -> {
                // 入站事件通道 / 出站控制通道：应答、同步包、CSM 一律写控制通道
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    log("IAPLINK: ✓ SYN|ACK 已投递控制通道——链路协商完成，启动控制会话")
                }
            }

            mib3Armed -> {
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    mib3SynAckQueued = true
                    log("IAPLINK: ✓ MIB3 SYN|ACK 已投递控制通道——等待链路确认后启动 CSM")
                }
            }

            else -> {
                sendEventIap(out)
                pendingEchoFlush = false
            }
        }
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control ->
                    handleFrameLocked(event.bytes)

                is Iap2LinkEngine.Event.Dead -> {
                    log("IAPLINK: 虚拟链路终止 - ${event.reason ?: "EOF"}")
                    return
                }
            }
        }
        noteLinkStateLocked()
        // 先冲刷（回显必须先行送达对端），再启动 CSM；超时换档可能新排队帧，最后再冲一次
        flushOutputLocked()
        maybeStartControlCsmLocked()
        checkMfiTimeoutLocked()
        flushOutputLocked()
    }

    /** 链路状态迁移只在日志里出现一次（IDLE→NEGOTIATING→NORMAL 是按键通路是否打通的分水岭）。 */
    private fun noteLinkStateLocked() {
        val state = engine.state
        if (state == lastLinkState) return
        lastLinkState = state
        log("IAPLINK: 链路状态 → $state（NORMAL 才可能收到车机 0x6800/0x6802 媒体键）")
    }

    /** MFi 挑战静默超时：记入该车机的记忆并换下一档重试（节拍线程每 200ms 驱动一次）。 */
    private fun checkMfiTimeoutLocked() {
        val identity = mfiIdentity ?: return
        if (mfiFinished || mfiCurrentLength == 0) return
        if (nowMillis() - mfiChallengeSentAtMs < mfiWindowMillis) return
        val failed = mfiCurrentLength
        MfiLengthMemory.recordSilent(mfiContext, identity, failed)
        log("IAPLINK: 隧道 MFi ${failed}B 静默 ${mfiWindowMillis}ms——记入记忆，换下一档重试")
        mfiCurrentLength = 0
        startNextMfiChallengeLocked()
    }

    /**
     * 下一档 MFi 挑战（顺序=按车机记忆重排，冷启动基线 20B 优先——Frida 真机值）。
     * 全部档位无应答时与蓝牙路径同语义：跳过校验直接 0xAA05，由车机后续行为判定。
     */
    private fun startNextMfiChallengeLocked() {
        val identity = mfiIdentity ?: return
        if (mfiOrder.isEmpty()) {
            mfiMemory = MfiLengthMemory.load(mfiContext, identity)
            mfiOrder = MfiLengthMemory.orderAttempts(TUNNEL_MFI_BASELINE, mfiMemory)
            if (!mfiMemory.isEmpty) {
                log(
                    "IAPLINK: 隧道 MFi 按车机记忆：${MfiLengthMemory.describe(mfiMemory)}" +
                        " → 本轮顺序 ${mfiOrder.joinToString("/")}B",
                )
            }
        }
        mfiPosition += 1
        if (mfiPosition >= mfiOrder.size) {
            log("IAPLINK: 隧道 MFi 挑战全部无应答——跳过校验直接 0xaa05（由车机后续行为判定）")
            mfiFinished = true
            mib3AuthStage = 4
            sendCsmLocked(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
            sendMib3BurstLocked()
            return
        }
        mfiCurrentLength = mfiOrder[mfiPosition]
        val base = TUNNEL_MFI_WINDOWS[mfiCurrentLength] ?: TUNNEL_MFI_SILENT_WINDOW_MS
        mfiWindowMillis =
            if (mfiCurrentLength in mfiMemory.silent && mfiCurrentLength != mfiMemory.succeeded) {
                minOf(base, TUNNEL_MFI_SILENT_WINDOW_MS)
            } else {
                base
            }
        mfiChallengeSentAtMs = nowMillis()
        val challenge = ByteArray(mfiCurrentLength).also { SecureRandom().nextBytes(it) }
        sendCsmLocked(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        mib3AuthStage = 3
        log("IAPLINK: 隧道 tx=0xaa02 challenge=${mfiCurrentLength}B（第 ${mfiPosition + 1} 次，窗口 ${mfiWindowMillis}ms）")
    }

    /**
     * 控制会话启动（隧道/MIB3 两形态）：链路 NORMAL 且 SYN|ACK 已投递后，主动发
     * 0x1D00 StartIdentification。缺此步时对端控制端 5 分钟窗口超时即拆掉整条链路
     * （DiPlay 2026-10-11 实测根因；MIB3 的拆除窗口短得多，~10s）。
     */
    private fun maybeStartControlCsmLocked() {
        if (engine.state != Iap2LinkEngine.State.NORMAL) return
        val onTunnel = tunnelWriter() != null
        if (onTunnel) {
            if (tunnelCsmStarted || !tunnelEchoDelivered) return
            tunnelCsmStarted = true
        } else if (eventLinkArmed) {
            // 车机发起方形态：出站走控制通道，只要已采纳对端会话表即可开工
            if (eventCsmStarted || engine.peerSynchronization == null) return
            eventCsmStarted = true
        } else {
            if (!mib3Armed || mib3CsmStarted || !mib3SynAckQueued) return
            mib3CsmStarted = true
        }
        sendCsmLocked(Iap2MessageIds.START_IDENTIFICATION)
    }

    /** 链路上的 CSM 发送（进入引擎 backlog，由引擎按发送窗口 flush）。 */
    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B ok=$ok",
        )
    }

    /** 直接投递完整 CSM 帧（Frida 抓的逐字节常量，勿重新编码）。 */
    private fun sendRawCsmLocked(frame: ByteArray) {
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        // CSM 帧布局：FF 5A [len:2] [msgId:2] …——消息号在 [4..5]，[2..3] 是载荷长度。
        // 曾误取 [2..3]，日志把 0xFFFA 打成 0x32、0xFFF0 打成 0x0E、0x4158 打成 0x64
        // （长度值），排查时极易被带偏（2026-10-23 实际误导过一轮）。
        val messageId = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B raw ok=$ok",
        )
    }

    /**
     * 控制会话应答（隧道与 MIB3 共用识别/MFi 骨架，MIB3 另有突发与订阅应答）：
     *   0x1D01 → 0x1D02 + 0xAA00；0xAA06 → 再发 0xAA00；
     *   0xAA01 → 0xAA02 挑战（MIB3 20B / DiPlay 32B）；0xAA03 → 0xAA05（MIB3 随发突发）。
     * 订阅（仅 MIB3）：0x4157→0x4158、0x4154→0x4155、0x4170→0x4171、0x5000→0x5001 空态。
     * 0xEA02（requestAppLaunch）/0xA101（VehicleStatus）/0xFFFB（PASCD 定位样本 ~1Hz）
     * 只链路 ACK（引擎自动）——**0xFFFB 漏 ACK 车机同样 ~10s 后关控制通道**（Frida 结论）。
     */
    private fun maybeRespondControlCsmLocked(frame: Iap2Csm.Frame) {
        val onTunnel = tunnelWriter() != null
        // 车机发起方形态复用 MIB3 那套识别/MFi 骨架（出站同样走控制通道）
        val mib3 = (mib3Armed || eventLinkArmed) && !onTunnel
        if (!onTunnel && !mib3) return
        val ids = Iap2MessageIds
        when (frame.messageId) {
            ids.IDENTIFICATION_INFORMATION -> {
                val identified = if (onTunnel) tunnelIdentified else mib3Identified
                if (identified) return
                if (onTunnel) tunnelIdentified = true else mib3Identified = true
                val params =
                    Iap2Csm
                        .parseParams(frame.body)
                        .associateBy { it.id }
                log(
                    "IAPLINK: 隧道 rx 0x1d01 接收端身份 " +
                        "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                // 参 6/7 = 车机声明「它会发 / 它可收」的消息清单。媒体键排查就靠这两行：
                // 若 0x6800/0x6802 不在发送清单里，说明这台车机根本不用 iAP2 HID 送按键，
                // 不必再在这条通路上耗（改查蓝牙/AVRCP 侧）。
                params[6]?.value?.let { raw ->
                    val sends = u16List(raw)
                    log(
                        "IAPLINK: 车机发送清单(${sends.size})=" +
                            sends.joinToString(" ") { "0x%04x".format(it) } +
                            " 含HID0x6800=${sends.contains(Iap2MessageIds.START_HID)}" +
                            " 含0x6802=${sends.contains(Iap2MessageIds.ACCESSORY_HID_REPORT)}",
                    )
                }
                params[7]?.value?.let { raw ->
                    val receives = u16List(raw)
                    log(
                        "IAPLINK: 车机可收清单(${receives.size})=" +
                            receives.joinToString(" ") { "0x%04x".format(it) },
                    )
                }
                if (mib3) {
                    // 记忆键 = 车机身份 + " tunnel"：与蓝牙链路的记忆互不污染
                    mfiIdentity = mfiIdentityOf(params) + " tunnel"
                }
                sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 1
            }

            MFI_AUTH_SERIAL -> {
                if (!mib3 || mib3AuthStage != 1) return
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 2
                log("IAPLINK: MIB3 0xaa06（auth serial）→ 再发 0xaa00")
            }

            ids.MFI_AUTHENTICATION_FAILED -> {
                if (!mib3 || mfiFinished) return
                mfiIdentity?.let { MfiLengthMemory.recordRejected(mfiContext, it, mfiCurrentLength) }
                log("IAPLINK: 隧道 MFi ${mfiCurrentLength}B 被拒（0xaa04）——记入记忆，换下一档")
                mfiCurrentLength = 0
                startNextMfiChallengeLocked()
            }

            ids.MFI_CERTIFICATE -> {
                if (mib3) {
                    // 长度按车机记忆（冷启动 20B 优先，Frida 真机值；详见 startNextMfiChallengeLocked）
                    startNextMfiChallengeLocked()
                } else {
                    // DiPlay CARLINKIT 身份只接受精确 32B（长度不符直接断链）
                    val challenge =
                        ByteArray(32).also { SecureRandom().nextBytes(it) }
                    sendCsmLocked(ids.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                    mib3AuthStage = 3
                }
            }

            ids.MFI_RESPONSE -> {
                if (mib3) {
                    mfiIdentity?.let { MfiLengthMemory.recordSuccess(mfiContext, it, mfiCurrentLength) }
                    log("IAPLINK: 隧道 rx=0xaa03 签名 ${frame.body.size}B（${mfiCurrentLength}B 挑战成功，已记忆）")
                    mfiCurrentLength = 0
                    mfiFinished = true
                }
                sendCsmLocked(ids.MFI_AUTHENTICATION_SUCCEEDED)
                if (mib3) {
                    mib3AuthStage = 4
                    sendMib3BurstLocked()
                } else {
                    log("IAPLINK: 隧道 MFi 完成——等待对端订阅（随后其标记隧道就绪）")
                }
            }

            START_COMMUNICATIONS_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_COMMUNICATIONS_UPDATE_IDLE)
                log("IAPLINK: MIB3 0x4157 → 0x4158（空态）")
            }

            START_CALL_STATE_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    CALL_STATE_UPDATE,
                    Iap2Csm.param(0x0002, byteArrayOf(0)),
                )
                log("IAPLINK: MIB3 0x4154 → 0x4155（无通话）")
            }

            START_LIST_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    LIST_UPDATE,
                    Iap2Csm.param(0x0000, byteArrayOf(0)) +
                        Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                        Iap2Csm.param(0x0005, byteArrayOf(0)) +
                        Iap2Csm.param(0x0007, byteArrayOf(0, 0)),
                )
                log("IAPLINK: MIB3 0x4170 → 0x4171（空列表）")
            }

            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_NOW_PLAYING_UPDATE_IDLE)
                // 仪表盘歌词/媒体卡的数据通路（0x5001 + file-transfer 封面）后续接
                // MediaSessionsState——空态应答是 MIB3 存活的前置，先落这版。
                log("IAPLINK: MIB3 0x5000 → 0x5001（空态）——仪表盘媒体数据待接")
            }

            ids.START_HID -> {
                // 车机声明「我要开始发 HID 报告了」，通常带描述符（决定报告字节语义）
                val params = Iap2Csm.parseParams(frame.body)
                val descriptor = params.map { it.value }.firstOrNull { it.size > 8 }
                if (descriptor != null) {
                    hidDescriptor = descriptor
                    log(
                        "IAPLINK: 车机 0x6800 StartHID 描述符 ${descriptor.size}B hex=" +
                            descriptor.joinToString(" ") { "%02x".format(it) }.take(120),
                    )
                } else {
                    log("IAPLINK: 车机 0x6800 StartHID（无描述符）参数=${params.map { it.id }}")
                }
            }

            ids.ACCESSORY_HID_REPORT -> {
                // 方向盘/面板媒体键。
                //
                // 2026-10-20 本田 HFT 实测帧（11B）：
                //   00 06 00 00 00 03 | 00 05 00 01 04
                //   param id=0 值 00 03（报告类型/描述，恒定）← 之前被当成报告值
                //   param id=1 值 04（真正的 HID 报文）
                // 旧代码取「第一个 1~8 字节的 param」→ 永远拿到 00 03，位图里一位都不置，
                // 于是日志一直打 `usage=无 keyCode=0`，按键到了却点不出任何动作。
                val params = Iap2Csm.parseParams(frame.body)
                val report = hidReportFromParams(params)
                // 导航类设备（iDrive 旋钮/四向拨杆）：Generic Desktop 页的 Wheel/Hat，
                // 走 DPAD 注入（需要虚拟屏焦点），不能当媒体键全局分发。
                if (report != null && HidNavigation.isNavigationDevice(hidDescriptor)) {
                    val navKeys = HidNavigation.handle("iap2-hid", hidDescriptor, report)
                    if (navKeys.isNotEmpty()) {
                        log("IAPLINK: 旋钮/导航键 " + navKeys.joinToString("+") { HidNavigation.nameOf(it) })
                        navKeys.forEach { onNavKey?.invoke(it) }
                    }
                } else {
                    val keyMap = hidDescriptor?.let(ConsumerKeys::parseDescriptor)
                    val usages =
                        when {
                            report == null -> emptyList()
                            keyMap != null -> keyMap.usagesFor(report)
                            // 无描述符：退化为「单字节即 Usage ID」
                            else -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                        }
                    if (hidReportFrames < HID_REPORT_VERBOSE_FRAMES) {
                        hidReportFrames++
                        log(
                            "IAPLINK: 车机 0x6802 params=" +
                                params.joinToString(",") { "${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) } } +
                                " 报告=" + (report?.joinToString(" ") { "%02x".format(it) } ?: "空") +
                                " 位图=" + (keyMap?.isArray?.let { if (it) "Array" else "Variable" } ?: "无描述符"),
                        )
                    }
                    fireHidKeysLocked(usages)
                }
            }

            else -> {
                // 未处理的消息也留痕：车机若用非常规 ID 下发媒体键/按键，这里能第一时间
                // 在日志里看到对应消息号（切歌按键排查的关键观测点）。
                // 带全量 hex：宝马 iD9 的 0xad00/0xad02/0xad03 家族既不在 Apple 公开表里，
                // 也没有参考实现的命名——只能靠报文体结构反推它是不是输入（触摸/HID）通路。
                unknownCsmIds++
                if (unknownCsmIds <= 20) {
                    log(
                        "IAPLINK: 未处理的 CSM 0x${frame.messageId.toString(16)} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=" +
                            frame.body.joinToString(" ") { "%02x".format(it) },
                    )
                }
            }
        }
    }

    /**
     * 把一批「本次按下的 Usage」转成 Android 媒体键注入。
     *
     * 车机的 HID 报告是**状态式**的：按住会以 ~10Hz 连推同一个位图，松开推 0x00。
     * 因此只在「新出现」时触发一次（边沿触发），否则一次长按会跳掉几十首歌；
     * 只有在同一按键持续按住超过 [HID_HOLD_REPEAT_MILLIS] 时才允许再触发，
     * 保持方向盘长按「连续切歌」的手感。
     */
    private fun fireHidKeysLocked(usages: List<Int>) {
        val now = nowMillis()
        if (usages.isEmpty()) {
            lastHidUsages = emptySet()
            hidHoldStartedAtMs = 0L
            return
        }
        val fresh = usages.filter { it !in lastHidUsages }
        val repeat =
            fresh.isEmpty() &&
                hidHoldStartedAtMs > 0 &&
                now - hidHoldStartedAtMs >= HID_HOLD_REPEAT_MILLIS
        lastHidUsages = usages.toSet()
        if (fresh.isNotEmpty()) hidHoldStartedAtMs = now
        if (fresh.isEmpty() && !repeat) return
        if (repeat) hidHoldStartedAtMs = now
        val firing = if (fresh.isNotEmpty()) fresh else usages
        for (usage in firing) {
            val keyCode = ConsumerKeys.keyCodeFor(usage)
            log(
                "IAPLINK: 车机媒体键 ${ConsumerKeys.nameOf(usage)}(0x%02x) → keyCode=".format(usage) +
                    (if (keyCode != 0) "$keyCode 已注入" else "无对应 Android 键（忽略）"),
            )
            if (keyCode != 0) onMediaKey?.invoke(keyCode)
        }
    }

    /**
     * 0xAA05 后的突发（Frida：真机在认证成功后立即发这一批，先于车机的订阅请求）。
     * 0xFFFA/0xFFF0/0x4158/0x5001 用逐字节常量——字段布局只存在于真机抓包里，
     * 重新编码极易走样（对照 AADisplay WifiIap2Link 的 Frida 常量）。
     */
    private fun sendMib3BurstLocked() {
        val sessionId =
            mib3WirelessSessionId ?: UUID.randomUUID().toString().uppercase().also {
                mib3WirelessSessionId = it
            }
        sendCsmLocked(
            WIRELESS_SESSION_ID,
            Iap2Csm.param(0, sessionId.toByteArray(Charsets.UTF_8)),
        )
        // 0xA100 车辆状态能力：iPhone 只声明参数 6（sysdiagnose 2026-10-16 17:31:51.099，
        // `a1 00 00 04 00 06`）。旧「3 参数（3/6/9）」源自 Frida 抓包，与 iPhone 抓包矛盾
        // ——按本车族 iPhone 实测对齐；车机对 3 参数形态也会回 A101(6)，但少声明我们
        // 永不推送的状态类型更贴近真实语义
        sendCsmLocked(
            AUX_STATUS_NOTIFICATION,
            Iap2Csm.param(0x0006, ByteArray(0)),
        )
        sendCsmLocked(
            DEVICE_LANGUAGE,
            Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(DEVICE_TIME, mib3DeviceTimeParams())
        sendCsmLocked(
            DEVICE_INFORMATION_UPDATE,
            Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)),
        )
        sendRawCsmLocked(MIB3_START_LOCATION_INFORMATION)
        sendRawCsmLocked(MIB3_GPRMC_DATA_STATUS)
        log("IAPLINK: MIB3 突发已发（会话标识 $sessionId）——等待车机订阅")
        if (!mib3ControlSessionNotified) {
            mib3ControlSessionNotified = true
            onControlSessionComplete?.invoke()
        }
    }

    /** 0x4E0B：u64 秒 + s16 时区偏移(分) + u8 夏令时。 */
    private fun mib3DeviceTimeParams(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(java.util.Date(now))) 1 else 0
        val seconds = ByteArray(8)
        for (i in 0 until 8) {
            seconds[i] = (now / 1000L ushr ((7 - i) * 8)).toByte()
        }
        val tz =
            byteArrayOf(
                (offsetMin shr 8).toByte(),
                offsetMin.toByte(),
            )
        return Iap2Csm.param(0, seconds) + Iap2Csm.param(1, tz) + Iap2Csm.param(2, byteArrayOf(dst.toByte()))
    }

    /** 大端 u16 数组（识别信息里的消息 ID 清单）。 */
    private fun u16List(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map {
            ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)
        }

    /** 0x1D01 身份参数（name/model/manufacturer）→ 记忆键素材（NUL 去净、空档跳过）。 */
    private fun mfiIdentityOf(params: Map<Int, Iap2Csm.Param>): String =
        listOf(0, 1, 2)
            .mapNotNull { id ->
                params[id]
                    ?.value
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                    ?.takeIf { it.isNotBlank() }
            }.joinToString(" ")

    private fun handleFrameLocked(bytes: ByteArray) {
        frameCount++
        val frames = parser.offer(bytes)
        for (frame in frames) {
            val hex = frame.body.joinToString(" ") { "%02x".format(it) }
            if (frameCount <= 30 || frameCount % 100 == 0) {
                log(
                    "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=${hex.take(160)}",
                )
            }
            maybeRespondControlCsmLocked(frame)
        }
        if (frames.isEmpty() && frameCount <= 10) {
            log("IAPLINK: rx ${bytes.size}B（非 CSM）hex=" + bytes.joinToString(" ") { "%02x".format(it) }.take(160))
        }
    }

    /** 引擎回调：EA 会话（2 号）数据帧——触摸 HID 的高概率载体。 */
    var onEaData: ((ByteArray) -> Unit)? = null

    /**
     * 引擎回调：iAP2 控制会话下发的 HID 报告解析出的 Android 媒体键。
     *
     * 车机方向盘/面板的上一曲、下一曲在 CarPlay 里走 `0x6802 AccessoryHIDReport`，
     * 只有 iAP2 链路真正建立后才会来（见 [eventLinkArmed]）。
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /** 导航键（iDrive 旋钮/四向）：交给会话层注入虚拟屏（DPAD_*），不是全局媒体键。 */
    var onNavKey: ((Int) -> Unit)? = null

    /** 车机在 0x6800 StartHID 里给出的 HID 描述符（解析报告用；null = 未提供）。 */
    private var hidDescriptor: ByteArray? = null

    fun handleEaData(bytes: ByteArray) {
        eaCount++
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        if (eaCount <= 30 || eaCount % 100 == 0) {
            log("IAPLINK: EA 数据 #$eaCount ${bytes.size}B hex=${hex.take(200)}")
        }
        onEaData?.invoke(bytes)
    }

    companion object {
        /** 隧道 MFi 冷启动基线（Frida 真机 20B 优先；顺序会被按车机记忆重排）。 */
        val TUNNEL_MFI_BASELINE = listOf(20, 32, 16)
        val TUNNEL_MFI_WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val TUNNEL_MFI_SILENT_WINDOW_MS = 1_500L

        /**
         * 车机在事件通道上连续发到第几个 SYN marker 才判定「它在等我们回 marker」。
         * 真机（MIB3）在我们主动 detect 后就不再重发；Honda HFT 实测连发上百次，
         * 取 2 既能避开 MIB3 的一次探测重发，又不会让车机白等太久。
         */
        const val EVENT_LINK_MARKER_THRESHOLD = 2

        /**
         * 控制通道协商：每轮多久无进展就重开下一轮，最多几轮。
         * 车机 marker 是 1Hz，4s 一轮 ⇒ 每轮能收到 4 次重发机会；5 轮 ≈ 20s，
         * 之后停手静默等待（它真发 0x1D01 仍会被正常处理）。
         */
        const val CONTROL_SYN_ROUND_MILLIS = 4_000L
        const val MAX_CONTROL_SYN_ROUNDS = 5

        /**
         * 0x6802 AccessoryHIDReport 的报文参数号。
         *
         * 本田 HFT 实测：param 0 = 报告类型/描述（`00 03`，永远不变），
         * param 1 = 真正的 HID 报文（如 `04` = 下一首）。取错参数号等于所有按键都解码成「无」。
         */
        const val HID_REPORT_DATA_PARAM = 1

        /** 参数明细只打前几帧（之后每帧都会触发按键动作，日志已足够）。 */
        const val HID_REPORT_VERBOSE_FRAMES = 8

        /** 同一按键持续按住多久才允许再次触发（模拟长按连发，短按绝不重复）。 */
        const val HID_HOLD_REPEAT_MILLIS = 1_500L

        /**
         * 0x6802 的参数表 → HID 报文。
         *
         * 优先取 param [HID_REPORT_DATA_PARAM]；缺省（个别车机只给一个参数）时退回
         * 「最后一个 1~8 字节的参数」——注意**不能取第一个**：本田 HFT 的 param 0 恒为
         * `00 03`（报告类型），当成报文值会让所有按键都解成「无」。
         */
        fun hidReportFromParams(params: List<Iap2Csm.Param>): ByteArray? =
            params.firstOrNull { it.id == HID_REPORT_DATA_PARAM }?.value?.takeIf { it.isNotEmpty() }
                ?: params.lastOrNull { it.value.isNotEmpty() && it.value.size <= 8 }?.value

        // ---- MIB3 消息号（无正式名/未入 Iap2MessageIds 的小集合） ----
        const val MFI_AUTH_SERIAL = 0xAA06
        const val WIRELESS_SESSION_ID = 0x4E0C
        const val DEVICE_TIME = 0x4E0B
        const val DEVICE_LANGUAGE = 0x4E0A
        const val DEVICE_INFORMATION_UPDATE = 0x4E09
        const val AUX_STATUS_NOTIFICATION = 0xA100
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val CALL_STATE_UPDATE = 0x4155
        const val START_CALL_STATE_UPDATES = 0x4154
        const val START_LIST_UPDATES = 0x4170
        const val LIST_UPDATE = 0x4171

        /** ---- 以下为 Frida 抓包的逐字节常量（MIB3 真机 iPhone → Audi MMI）---- */
        private val HEX_DIGITS = "0123456789abcdef"

        /** 0xFFFA StartLocationInformation。 */
        internal val MIB3_START_LOCATION_INFORMATION: ByteArray =
            hex(
                "40400032fffa00080000000000140004000100088001000000fa" +
                    "0004000200088002000000fa000400040008800400000014",
            )

        /**
         * 0xFFF0 GPRMCDataStatusValuesNotification。
         * iPhone 真车抓包（2026-10-21 14:23:23.621）：**18B、3 个空参数（id 0/1/2）**——
         * 旧常量 14B/2 参数（缺 `00 04 00 02`），字节级不一致。
         */
        internal val MIB3_GPRMC_DATA_STATUS: ByteArray =
            hex("40400012fff0000400000004000100040002")

        /** 0x5001 NowPlayingUpdate 空态（无媒体/无播放）。 */
        internal val MIB3_NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** 0x4158 CommunicationsUpdate 空态（无 SIM/无信号场景的可用占位）。 */
        internal val MIB3_COMMUNICATIONS_UPDATE_IDLE: ByteArray =
            hex(
                "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                    "00000500050100050006010005000700000500080000050009000005000a000005" +
                    "000b000005000c010005000d000005000e000005000f0000050010000005001100",
            )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                val hi = HEX_DIGITS.indexOf(clean[i * 2])
                val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
