package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel

/**
 * 发送端会话（fw-engine 专用、纯驱动层）：协议逻辑 100% 在 Rust
 * （cardeck-protocol 固件真身——链路/FSM/协商/识别/认证/编排全链）。
 *
 * Kotlin 职责只剩两件（用户指令 2026-11-01：旧 JVM 连接逻辑已完整移除，
 * 不经开关、不留代码）：
 *  1. 链路就绪后调 startFlow 启动 Rust 编排泵
 *  2. 循环驱动：链路事件（WIFI/ENDPOINT/PHASE1_DONE）→ 主控回调
 *
 * **镜像面已退役（A7 下沉 2026-11-04）**：控制会话镜像帧（0x1D01 画像/
 * 0x5703 热点/0x4301 端点）不再过宿主边界——0x5703/0x4301 Rust 内部消化
 * 为 EV_WIFI/EV_ENDPOINT 平台动作事件，0x1D01 画像/EA 表 Rust 侧自有
 * 全量解析与日志（fw::session::ident），Kotlin 侧解析纯日志零行为消费。
 * Iap2Csm（CSM 分帧/TLV）随之退役。
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
    /** 收到 EV_WIFI（0x5703 消化产物）时回调——主控立即绑 WiFi + 广播 _carplay-ctrl。 */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
    /** 收到 EV_ENDPOINT（0x4301 消化产物，含 wifi 域）时回调——主控直接走 AirPlay 连接。 */
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

    /** WiFi 凭据（EV_WIFI 事件载荷解析产物）。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    /**
     * 会话主循环：链路就绪 → startFlow（Rust 编排泵自推进）→ 永续分发
     * （编排事件），直至链路断开（run 返回 = 本会话结束）。
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
        }
        log("iap2 会话循环结束（链路关闭）")
    }

    // ---- 链路事件载荷解码（Rust 平台动作事件体契约） ----

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
