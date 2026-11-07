package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.input.HidNavigation
import io.github.shaonialife.cardeck.protocol.NativeBridge

/**
 * WiFi 隧道 iAP2 链路（原生会话薄层）——替代日志推断的 [VirtualIap2Link]。
 *
 * 协议行为全部在 cardeck-protocol 固件真身内（nativeSessionFeed：链路帧
 * FF 5A 走 ParseBuffer/FSM，车机 SYN → 原生 SYN|ACK；编排 [startFlow]
 * 布防后识别/MFi/特性启动波由编排泵在链路 Connected 时端到端自推进——
 * 真车 sysdiagnose 2026-10-15 21:14:31 实证：隧道 = 新 iAP2 链，车机 SYN
 * 后整链重跑 1D00→1D01→AA→通知波，与 BT 链同构）。
 * Kotlin 侧只剩：字节搬运（事件通道 onCarBytes 进、事件通道 iAPSendMessage 出）、
 * DETECT marker 应答（传输层探测，6 字节常量）、
 * HID 报文 → 媒体键/旋钮的平台注入（ConsumerKeys/HidNavigation）。
 *
 * ⚠ **2026-11-07 已知错误接线（勿当固件形）**：2026-11-06 iPhone 字节定谳
 * 真隧道 = RTSP `SETUP streams[{type:130}]` 的 ChaCha20-Poly1305 NetSocket
 * 数据流（流内跑第二套 iAP2 链），**不是事件通道**。本类仍把出站导向
 * `iAPSendMessage`——待 G3 重接（docs/FIRMWARE-GAP-ANALYSIS.md）。
 */
class NativeTunnelIap2Link(
    private val log: (String) -> Unit,
    /** 事件通道 iAPSendMessage 出站（旧形态兜底 + marker 应答）。 */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** 节拍线程存活条件。 */
    private val alive: () -> Boolean,
) {
    private val lock = Object()

    @Volatile
    var handle: Long = 0L
        private set

    /** 车机 HID 描述符（0x6800 载荷，媒体键解析用）。 */
    @Volatile
    var hidDescriptor: ByteArray? = null
        private set

    var onMediaKey: ((Int) -> Unit)? = null
    var onNavKey: ((Int) -> Unit)? = null
    var onControlSessionComplete: (() -> Unit)? = null
    val isAlive: Boolean get() = alive()

    /** 启动隧道链固件编排（与 BT 链 [Iap2LinkChannel.startFlow] 同一 JNI 面：
     *  身份/平台值注入 + flow_start）。隧道 = 新 iAP2 链，真车在车机 SYN
     *  握手完成后整链重跑（引擎在 Connected 前只布防不出帧）。 */
    fun startFlow(
        deviceName: ByteArray,
        bluetoothMac: ByteArray,
        language: ByteArray,
        tzOffsetMinutes: Int,
        dst: Int,
    ) {
        check(handle != 0L) { "flow start on dead tunnel link" }
        NativeBridge.nativeSessionFlowStart(handle, deviceName, bluetoothMac, language, tzOffsetMinutes, dst)
    }

    init {
        check(NativeBridge.ensureLoaded()) { "cardeck_protocol 未加载" }
        handle = NativeBridge.nativeSessionCreate(0x2000L)
        check(handle != 0L) { "原生隧道会话创建失败" }
        NativeBridge.nativeSetTransport(
            handle,
            object : NativeBridge.TransportSender {
                override fun send(bytes: ByteArray) {
                    routeOutbound(bytes)
                }
            },
        )
        NativeBridge.nativeSessionSetEventSink(
            handle,
            object : NativeBridge.EventSink {
                override fun onEvent(
                    msgId: Int,
                    bytes: ByteArray,
                ) {
                    when (msgId) {
                        0x6800 -> {
                            // 车机 StartHID 描述符（>8B 的参数），媒体键解析用
                            val params = parseParams(bytes)
                            hidDescriptor = params.firstOrNull { it.size > 8 }
                            log(
                                "IAPLINK: 车机 0x6800 描述符 " +
                                    (hidDescriptor?.size ?: 0) + "B",
                            )
                        }

                        0x6802 -> dispatchHid(parseParams(bytes))
                    }
                }
            },
        )
        Thread(
            {
                // 循环挂 handle 生命周期（close() 置 0 退出）——不挂 alive()：
                // 构造早于 RTSP socket 打开，首检 alive()=false 会出生即死，
                // TimeSync 周期域（session_tick）随之失联（2026-11-04 装机实证）
                while (handle != 0L) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (handle != 0L && alive()) NativeBridge.nativeSessionTick(handle)
                }
            },
            "iap-tunnel-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 传输路由：出站收敛到事件通道（iAPSendMessage——Audi 事件通道形态；
     *  控制通道直写已随 MIB3 隧道退役，2026-11-03 对接期清理）。 */
    private fun routeOutbound(bytes: ByteArray) {
        sendEventIap(bytes)
    }

    /**
     * 车机字节入口：事件通道（onIapEventBytes）与隧道读取共用。
     * **全部进原生链**（含 6B FF 55 marker——fw: ParseBuffer FF55 扫描器
     * 完成码分派 @0x10000d8f0-914：DETECT→0x13→FSM 0x17 SendDetect 全六态
     * 回显 + 协商域重置；RequestIdentify(…FE)→0x15→FSM 0x19 仅 ATTACHED
     * SendRST——引擎 endpoint.rs 同径实现）。Kotlin 直答分支已删（A2 直切
     * 定谳 2026-11-05）：它是引擎固件径的重复第二实现，且缺 SendDetect 的
     * 协商域重置副作用；出站回显经 bt_write 槽 → routeOutbound → 事件通
     * 道，feed 同步出站时序不变。
     */
    fun onCarBytes(bytes: ByteArray) {
        if (handle == 0L) return
        NativeBridge.nativeSessionFeed(handle, bytes)
    }

    private fun dispatchHid(params: List<ByteArray>) {
        // 参数 id=1 才是 HID 报文（id=0 是恒定的报告类型描述——本田 HFT 教训）
        val report = hidReportFromParams(params) ?: return
        // A6 下沉：判定树在 Rust（fw::airplay::hid——描述符驱动，media →
        // nav → consumer-release → touch；本入口的触点无消费者，touch/
        // other 判为 null 即无动作）。Kotlin 只剩动作回调。
        val dispatch = NativeBridge.nativeAirplayHidDispatch(hidDescriptor, report) ?: return
        val obj = org.json.JSONObject(dispatch)
        when (obj.getString("kind")) {
            "nav" -> {
                val mask = obj.getInt("mask")
                val delta =
                    if (obj.has("delta") && !obj.isNull("delta")) obj.getInt("delta") else null
                val hat = if (!obj.isNull("hat")) obj.getInt("hat") else null
                val navKeys = HidNavigation.handleRaw("iap2-hid", mask, delta, hat)
                if (navKeys.isNotEmpty()) {
                    log("IAPLINK: 旋钮/导航键 " + navKeys.joinToString("+") { HidNavigation.nameOf(it) })
                    navKeys.forEach { onNavKey?.invoke(it) }
                }
            }

            "media" -> {
                val usages = obj.getJSONArray("usages")
                for (i in 0 until usages.length()) {
                    onMediaKey?.invoke(usages.getInt(i))
                }
            }
        }
    }

    private fun hidReportFromParams(params: List<ByteArray>): ByteArray? {
        // param id=1 = HID 报文（本田 HFT 实测帧：id=0 是恒定描述、id=1 才是报告）
        return params.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    private fun parseParams(frame: ByteArray): List<ByteArray> {
        // wire TLV：[len16 BE][id16 BE][data]（id 仅用于 0x6802 的 id=1 判别——
        // 这里返回按出现顺序的 data 列表，hidReportFromParams 取 index 1）
        val out = ArrayList<ByteArray>()
        var off = 6 // 跳过 40 40 + total + id
        while (off + 4 <= frame.size) {
            val len = ((frame[off].toInt() and 0xFF) shl 8) or (frame[off + 1].toInt() and 0xFF)
            if (len < 4 || off + len > frame.size) break
            out += frame.copyOfRange(off + 4, off + len)
            off += len
        }
        return out
    }

    fun close() {
        if (handle != 0L) {
            NativeBridge.nativeSessionDestroy(handle)
            handle = 0L
        }
    }
}
