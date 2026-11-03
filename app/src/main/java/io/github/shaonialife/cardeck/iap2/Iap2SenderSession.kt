package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel

/**
 * 发送端会话（fw-engine 专用、纯驱动层）：协议逻辑 100% 在 Rust
 * （cardeck-protocol 固件真身——链路/FSM/协商/识别/认证/编排全链）。
 *
 * Kotlin 职责只剩两件（用户指令 2026-11-01：旧 JVM 连接逻辑已完整移除，
 * 不经开关、不留代码）：
 *  1. 链路就绪后调 startFlow 启动 Rust 编排泵
 *  2. 循环驱动：链路事件（WIFI/ENDPOINT/PHASE1_DONE）+ 会话镜像帧分发，
 *     把 0x1D01 画像 / 0x5703 热点凭据 / 0x4301 端点回调给主控
 *
 * 无任何超时（fw-engine 路径）：协议推进由 Rust 编排泵驱动，活性由链路
 * 状态机管——固件 accessoryd 会话引擎同样无识别/认证完成时限（仅链路层
 * 有重传上限，已实现于 Rust）。
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机真实蓝牙 MAC（0x4300/0x4E0E 的传输标识与 _carplay-ctrl 广播的 id；真车会校验）。 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 本机蓝牙名（0x4E09 设备信息）。 */
    private val deviceName: String = "iPhone",
    /** 本端语言标签（0x4E0A；Locale.toLanguageTag 形态，宿主平台值）。 */
    private val language: String = "zh-CN",
    /** 本端时区偏移（分钟，含夏令时；0x4E0B 参数 1，负值按 u16 位形透传）。 */
    private val tzOffsetMinutes: Int = 480,
    /** 本端夏令时标志（0x4E0B 参数 2 u8；1 = 夏令时生效）。 */
    private val dst: Int = 0,
    /** 收到 0x5703（热点凭据）时回调——主控立即绑 WiFi + 广播 _carplay-ctrl。 */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
    /** 收到 0x4301（CarPlay 端点，含 wifi 域）时回调——主控直接走 AirPlay 连接。 */
    private val onEndpoint: ((CarPlayEndpoint) -> Unit)? = null,
) {
    /** 从 0x5703 + 0x4301 解析出的接收端信息。 */
    data class CarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKey: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        val advertisedFeatures: Long? = null,
    )

    /** WiFi 凭据（0x5703 解析产物）。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    /** 车机声明「它会发送」的消息清单（0x1D01 参数 6）。 */
    var accessorySendIds: Set<Int> = emptySet()
        private set

    /** 车机声明「它可接收」的消息清单（0x1D01 参数 7）。 */
    var accessoryReceiveIds: Set<Int> = emptySet()
        private set

    /** EA 会话协议表（0x1D01 组件 10/11/12 → (protocolId, name)）。 */
    var eaProtocols: List<Pair<Int, String>> = emptyList()
        private set

    private val parser = Iap2Csm.Parser()

    /**
     * 会话主循环：链路就绪 → startFlow（Rust 编排泵自推进）→ 永续分发
     * （链路事件 + 会话镜像帧），直至链路断开（run 返回 = 本会话结束）。
     */
    fun run() {
        if (!channel.awaitReady(0L)) { // 0 = 无限期（固件会话引擎无完成时限；活性归链路层）
            log("iap2 链路未就绪即终止")
            return
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")
        channel.startFlow(
            deviceName.toByteArray(),
            bluetoothMac.toByteArray(),
            language.toByteArray(),
            tzOffsetMinutes,
            dst,
        )

        while (channel.isAlive) {
            // 链路事件（Rust：WIFI=-1 / ENDPOINT=-2 / PHASE1_DONE=-3）。
            // R1 对接定形（2026-11-03）：ENDPOINT 是唯一的连接触发事件，
            // 直通主控 onCarPlayEndpoint——旧「晚到端点」合并状态机
            // （onLateEndpoint/prefer/drain）随空端点观察器一并退役；
            // PHASE1_DONE 纯诊断（协议推进由 Rust 泵管，端点未到不连接）。
            channel.pollFlowEvent(200L)?.let { event ->
                when (event.id) {
                    -1 -> {
                        val cfg = decodeWifiEvent(event.bytes)
                        log("iap2 flow wifi ssid=${cfg.ssid} channel=${cfg.channel} security=${cfg.securityType}")
                        onWifiCredentials?.invoke(cfg)
                    }
                    -2 -> {
                        val endpoint = decodeEndpointEvent(event.bytes)
                        log(
                            "iap2 flow endpoint port=${endpoint.airPlayPort} " +
                                "ssid=${endpoint.ssid} ips=${endpoint.ipAddresses}",
                        )
                        onEndpoint?.invoke(endpoint)
                    }
                    -3 -> log("iap2 flow phase-1 done")
                }
            }
            drainMirrorFrames()
        }
        log("iap2 会话循环结束（链路关闭）")
    }

    /** 非阻塞清空镜像帧队列并分发。 */
    private fun drainMirrorFrames() {
        while (true) {
            val bytes = channel.recvControl(0L) ?: return
            for (frame in parser.offer(bytes)) {
                dispatchMirror(frame)
            }
        }
    }

    /** 镜像帧分发：1D01 画像日志/EA 表、5703 热点凭据；0x4301 已在 Rust 内消化
     *  为 ENDPOINT 事件（协议帧不过宿主边界），镜像面只剩日志。 */
    private fun dispatchMirror(frame: Iap2Csm.Frame) {
        when (frame.messageId) {
            0x1D01 -> {
                logIdentification(frame)
                eaProtocols = parseEaProtocols(frame)
            }
            0x5703 -> {
                val cfg = parseWifiConfiguration(frame)
                log("iap2 rx=0x5703 ssid=${cfg.ssid} channel=${cfg.channel} security=${cfg.securityType}")
                onWifiCredentials?.invoke(cfg)
            }
            0x4301 -> log("iap2 rx=0x4301 CarPlayStartSession（Rust 已消化为 ENDPOINT 事件）")
        }
    }

    // ---- 0x1D01 / 0x5703 解析（纯函数，输入来自 Rust 镜像） ----

    private fun parseWifiConfiguration(frame: Iap2Csm.Frame): WifiConfiguration {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val ssid = params[0]?.value?.asStringOrEmpty().orEmpty()
        val passphrase = params[1]?.value?.asStringOrEmpty().orEmpty()
        val channel =
            params[2]
                ?.value
                ?.firstOrNull()
                ?.toInt()
                ?.and(0xFF) ?: 0
        val security =
            params[3]
                ?.value
                ?.firstOrNull()
                ?.toInt()
                ?.and(0xFF) ?: 0
        return WifiConfiguration(ssid, passphrase, channel, security)
    }

    private fun parseEaProtocols(frame: Iap2Csm.Frame): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        for (groupId in intArrayOf(10, 11, 12)) {
            val value = params[groupId]?.value ?: continue
            val sub = Iap2Csm.parseParams(value).associateBy { it.id }
            val protocolId =
                sub[0]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: continue
            val name = sub[1]?.value?.asStringOrEmpty() ?: "?"
            out += protocolId to name
            log("iap2 0x1d01 EA 组件[$groupId] protocol=$protocolId name=$name")
        }
        return out
    }

    private fun logIdentification(frame: Iap2Csm.Frame) {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val name = params[0]?.value?.asStringOrEmpty()
        val model = params[1]?.value?.asStringOrEmpty()
        val manufacturer = params[2]?.value?.asStringOrEmpty()
        log("iap2 rx=0x1d01 车机身份 name=$name model=$model manufacturer=$manufacturer")
        val sends = params[6]?.value?.asU16List()
        val receives = params[7]?.value?.asU16List()
        if (sends != null) {
            accessorySendIds = sends.toSet()
            log("iap2 0x1d01 车机发送清单(${sends.size})=" + sends.joinToString(" ") { "0x%04x".format(it) })
        }
        if (receives != null) {
            accessoryReceiveIds = receives.toSet()
            log("iap2 0x1d01 车机可收清单(${receives.size})=" + receives.joinToString(" ") { "0x%04x".format(it) })
        }
    }

    /** u16 BE 清单（0x1D01 参数 6/7）；奇长度返回 null（fw: 长度须偶）。 */
    private fun ByteArray.asU16List(): List<Int>? {
        if (size % 2 != 0) return null
        return List(size / 2) {
            ((this[it * 2].toInt() and 0xFF) shl 8) or (this[it * 2 + 1].toInt() and 0xFF)
        }
    }

    private fun ByteArray.asStringOrEmpty(): String? {
        if (isEmpty() || last() != 0.toByte()) return null
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOf(end).decodeToString()
    }

    // ---- 链路事件载荷解码（Rust session_flow 事件体） ----

    private fun nulIndexOf(
        b: ByteArray,
        from: Int,
    ): Int {
        var i = from
        while (i < b.size) {
            if (b[i] == 0.toByte()) return i
            i++
        }
        return -1
    }

    /** EV_WIFI 载荷：ssid\0 psk\0 ch(u8) sec(u8)。 */
    private fun decodeWifiEvent(b: ByteArray): WifiConfiguration {
        val nul = nulIndexOf(b, 0)
        val ssid = String(b, 0, if (nul < 0) b.size else nul)
        val nul2 = nulIndexOf(b, nul + 1)
        val pskEnd = if (nul2 < 0) b.size else nul2
        val psk = String(b, nul + 1, pskEnd - nul - 1)
        val ch = b.getOrNull(pskEnd + 1)?.toInt()?.and(0xFF) ?: 0
        val sec = b.getOrNull(pskEnd + 2)?.toInt()?.and(0xFF) ?: 0
        return WifiConfiguration(ssid = ssid, passphrase = psk, channel = ch, securityType = sec)
    }

    /** EV_ENDPOINT 载荷（Rust fw/endpoint.rs 契约）：port(u32be) + ident\0 +
     *  pubkey\0 + srcvers\0 + ips（各\0）+ 空\0 + [ssid\0 + pass\0 + ch u8 + sec u8]。
     *  尾块 = 0x4301 组1 wifi 域透传（R1 对接修正 2026-11-03——此前丢弃致
     *  ssid 恒空，端点全数落入空端点分支）；旧载荷无尾块时默认空。 */
    private fun decodeEndpointEvent(b: ByteArray): CarPlayEndpoint {
        var off = 4
        val port =
            ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

        fun nulString(): String {
            val end = nulIndexOf(b, off)
            if (end < 0) {
                val s = String(b, off, b.size - off)
                off = b.size
                return s
            }
            val s = String(b, off, end - off)
            off = end + 1
            return s
        }
        val ident = nulString()
        val pubkey = nulString()
        val srcvers = nulString()
        val ips = ArrayList<String>()
        while (off < b.size) {
            val ip = nulString()
            if (ip.isEmpty()) break
            ips += ip
        }
        // wifi 尾块（0x4301 组1 透传；旧载荷在此处已到末尾，全部取默认）
        val ssid = nulString()
        val pass = nulString()
        val channel = b.getOrNull(off)?.toInt()?.and(0xFF) ?: 0
        val security = b.getOrNull(off + 1)?.toInt()?.and(0xFF) ?: 0
        return CarPlayEndpoint(
            ssid = ssid,
            passphrase = pass,
            channel = channel,
            securityType = security,
            airPlayPort = port,
            deviceIdentifier = ident,
            publicKey = pubkey,
            sourceVersion = srcvers,
            ipAddresses = ips,
        )
    }
}
