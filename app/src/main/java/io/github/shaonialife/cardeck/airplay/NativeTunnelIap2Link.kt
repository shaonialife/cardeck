package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.HidNavigation
import io.github.shaonialife.cardeck.protocol.NativeBridge
import java.util.concurrent.LinkedBlockingQueue

/**
 * WiFi 隧道 iAP2 链路（原生会话薄层）——替代日志推断的 [VirtualIap2Link]。
 *
 * 协议行为全部在 cardeck-protocol 固件真身内：
 *  - MIB3 控制通道形态：链路帧（FF 5A）走 nativeSessionFeed（ParseBuffer/FSM/
 *    分包器），车机 SYN → 原生 SYN|ACK；识别/MFi/问候/订阅空态由 flow handler
 *    端到端（车机发起型：0x1D01→0x1D02、0xAA00 两步序列号→0xAA01→0xAA02
 *    20B→0xAA03→0xAA05→问候突发→订阅空态应答）。
 *  - DiPlay type-130 隧道形态：隧道承载裸 CSM（40 40 无链路封装）——
 *    nativeSessionDispatchRaw 直进消息解析 + handler 分发（首条到达自动
 *    发 0x1D00）。
 *
 * Kotlin 侧只剩：传输路由（隧道/控制通道）、DETECT marker 应答（传输层探测，
 * 6 字节常量）、HID 报文 → 媒体键/旋钮的平台注入（ConsumerKeys/HidNavigation）。
 */
class NativeTunnelIap2Link(
    private val log: (String) -> Unit,
    /** 事件通道 iAPSendMessage 出站（旧形态兜底 + marker 应答）。 */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** 控制通道 iAPSendMessage 出站（MIB3 手机侧帧唯一正确通道）。 */
    private val sendControlIap: (ByteArray) -> Unit,
    /** DiPlay type-130 隧道写入口（每次出站动态取用，可为 null）。 */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** 节拍线程存活条件。 */
    private val alive: () -> Boolean,
) {
    private val lock = Object()

    @Volatile
    var handle: Long = 0L
        private set

    @Volatile
    var tunnelModernAuth: Boolean = false

    @Volatile
    var tunnelBluetoothMac: String? = null

    /** 隧道入站是否裸 CSM（首条 40 40 后固定）。 */
    @Volatile
    private var tunnelIngressRawCsm = false

    /** 车机 HID 描述符（0x6800 载荷，媒体键解析用）。 */
    @Volatile
    var hidDescriptor: ByteArray? = null
        private set

    var onMediaKey: ((Int) -> Unit)? = null
    var onNavKey: ((Int) -> Unit)? = null
    var onControlSessionComplete: (() -> Unit)? = null
    var onMib3HandoffReady: (() -> Unit)? = null

    private val hidEvents = LinkedBlockingQueue<ByteArray>()

    val isAlive: Boolean get() = alive()

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
                while (alive()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (handle != 0L) NativeBridge.nativeSessionTick(handle)
                }
            },
            "iap-tunnel-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 传输路由：隧道存在 → type-130；否则控制通道（MIB3 不读事件通道出站）。 */
    private fun routeOutbound(bytes: ByteArray) {
        val tunnel = tunnelWriter()
        if (tunnel != null) {
            tunnel.writeIapBytes(bytes)
        } else {
            sendControlIap(bytes)
        }
    }

    /**
     * 车机字节入口：事件通道（onIapEventBytes）与隧道读取共用。
     * marker（FF 55 6B）是传输层探测——薄层直答；其余进原生链。
     */
    fun onCarBytes(bytes: ByteArray) {
        if (handle == 0L) return
        // 半移交/Honda marker：6 字节 FF 55 探测，同毫秒回 DETECT（真 iPhone 行为）
        if (bytes.size == 6 && bytes[0] == 0xFF.toByte() && bytes[1] == 0x55.toByte()) {
            sendEventIap(MARKER)
            log("IAPLINK: 车机 marker——已回 DETECT（事件通道）")
            return
        }
        // DiPlay 隧道裸 CSM 形态判定：40 40 开头 = 无链路封装
        if (bytes.size >= 2 && bytes[0] == 0x40.toByte() && bytes[1] == 0x40.toByte()) {
            tunnelIngressRawCsm = true
        }
        if (tunnelIngressRawCsm && tunnelWriter() != null) {
            NativeBridge.nativeSessionDispatchRaw(handle, bytes)
        } else {
            NativeBridge.nativeSessionFeed(handle, bytes)
        }
    }

    /** MIB3 布防：控制通道发 DETECT；半移交（拆 BT）回调随即触发。 */
    fun armMib3() {
        sendControlIap(MARKER)
        log("IAPLINK: MIB3 detect 已发（控制通道）——等待车机 SYN")
        onMib3HandoffReady?.invoke()
    }

    private fun dispatchHid(params: List<ByteArray>) {
        // 参数 id=1 才是 HID 报文（id=0 是恒定的报告类型描述——本田 HFT 教训）
        val report = hidReportFromParams(params) ?: return
        if (HidNavigation.isNavigationDevice(hidDescriptor)) {
            val navKeys = HidNavigation.handle("iap2-hid", hidDescriptor, report)
            if (navKeys.isNotEmpty()) {
                log("IAPLINK: 旋钮/导航键 " + navKeys.joinToString("+") { HidNavigation.nameOf(it) })
                navKeys.forEach { onNavKey?.invoke(it) }
            }
        } else {
            val keyMap = hidDescriptor?.let(ConsumerKeys::parseDescriptor)
            val usages =
                when {
                    keyMap != null -> keyMap.usagesFor(report)
                    // 无描述符：退化为「单字节即 Usage ID」
                    else -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                }
            for (u in usages) {
                if (u != 0) onMediaKey?.invoke(u)
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

    private companion object {
        val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
    }
}
