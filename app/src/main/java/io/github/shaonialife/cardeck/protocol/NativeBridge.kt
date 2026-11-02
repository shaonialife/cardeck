package io.github.shaonialife.cardeck.protocol

/**
 * Rust 协议库（cardeck-protocol，iOS accessoryd/AirPlaySender 等单元的工具化转译）
 * 的原生桥。
 *
 * 已接入的固件原语（iOS accessoryd 转译，行为由 Rust 侧单测锁定）：
 * iAP2 校验和、DETECT marker 判定、0x1D01 参数名查表。这些是纯值函数，
 * 不需要构造 Ghidra 还原的内部对象，因此可以直接调用。
 *
 * 其余转译产物（链路状态机、AirPlay 会话）依赖对象布局与全局量，需要先经
 * host 桥注入平台能力才能跑；接入时按同样方式逐个加导出。
 */
object NativeBridge {
    @Volatile private var loaded = false

    /** so 是否已成功加载（加载失败不影响 App 其它功能）。 */
    val available: Boolean
        get() = loaded

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded =
            try {
                System.loadLibrary("cardeck_protocol")
                true
            } catch (t: Throwable) {
                io.github.shaonialife.cardeck.SessionController.appendLog(
                    "RUST: ⚠ cardeck_protocol 加载失败：$t",
                )
                false
            }
        return loaded
    }

    /** so 版本串（如 "cardeck-protocol 0.1.0"）。 */
    external fun nativeVersion(): String

    // ---- 原生会话门面（Rust 拥有 run loop 与全部协议逻辑；Kotlin 只供
    // 传输字节与时钟节拍）。C 符号 cardeck_*（非 JNI 风格，System.loadLibrary
    // 后经 LibLoader 直接 dlsym 语义由 JNA/手写 extern 皆可——此处用
    // external 走 JNI 命名不匹配，故以 @JvmStatic 外的 companion 不可用；
    // 改用顶层 external 不可行，实际走 native 方法命名约定无效——这组
    // 入口通过 System.loadLibrary + 显式符号由 Rust 侧导出，Kotlin 侧用
    // 同名 external fun 需 JNI 前缀。因此这里提供轻量 Kotlin 包装（反射
    // dlsym 不必要——Rust 侧同时导出 Java_ 前缀版本）。 ---- */

    /**
     * 创建原生会话（BT attach 前调）。返回会话句柄（0 = 失败）。
     * 蓝牙链与 WiFi 隧道链并存——每通道一个句柄。
     */
    external fun nativeSessionCreate(deviceEndpoint: Long): Long

    /** BT 链路建立（RFCOMM connected 回调里调）。 */
    external fun nativeSessionBtAttached(handle: Long)

    /** 推入 BT 收到的字节（半包由固件解析器驻留）。返回是否消费了完整帧。 */
    external fun nativeSessionFeed(
        handle: Long,
        bytes: ByteArray,
    ): Int

    /** 会话节拍（Kotlin 线程循环调）。返回建议睡眠毫秒。 */
    external fun nativeSessionTick(handle: Long): Int

    /** 识别启动（链路就绪后调；0x1D00 → 0x1D01 → 0x1D02 全程固件链）。 */
    external fun nativeSessionStartIdentification(handle: Long): Int

    /** 销毁会话（断开时调）。 */
    external fun nativeSessionDestroy(handle: Long)

    /** Kotlin → Rust 会话级发送（完整 40 40 帧走原生链：许可门→分包→重传）。 */
    external fun nativeSessionSendControl(
        handle: Long,
        bytes: ByteArray,
    ): Int

    /** 链路是否就绪（FSM s5 Connected）。 */
    external fun nativeSessionLinkReady(handle: Long): Int

    /** 对端协商的最大控制载荷。 */
    external fun nativeSessionPeerMaxControl(handle: Long): Int

    /** 事件汇：每条完整会话消息（含被 Rust handler 消费的）镜像给 Kotlin。 */
    external fun nativeSessionSetEventSink(
        handle: Long,
        sink: EventSink,
    )

    /**
     * 启动 Phase-1 编排线程（识别→MFi→问候→WiFi→CarPlay 会话，全固件真身）。
     * 通道创建后立即调；结果经 EventSink：-1=WiFi 凭据、-2=端点、-3=Phase-1 完成。
     */
    external fun nativeSessionFlowStart(
        handle: Long,
        deviceName: String,
        bluetoothMac: String,
    )

    /** 隧道模式编排（车机发起型：mode 1=DiPlay、2=MIB3、3=现代隧道）。 */
    external fun nativeSessionFlowStartTunnel(
        handle: Long,
        mode: Int,
        deviceName: String,
        bluetoothMac: String,
    )

    /** 裸 CSM 分发（DiPlay type-130 隧道：入站即完整会话消息，无链路封装）。 */
    external fun nativeSessionDispatchRaw(
        handle: Long,
        bytes: ByteArray,
    ): Int

    /** 事件回调：msgId = iAP2 消息 ID（0x1D01/0x5703/0x4301…），bytes = 完整 40 40 帧。 */
    interface EventSink {
        fun onEvent(
            msgId: Int,
            bytes: ByteArray,
        )
    }

    /** 注册传输出口（Rust 协议层的 bt_write 落地）。 */
    external fun nativeSetTransport(
        handle: Long,
        sender: TransportSender,
    )

    /** 传输出口回调接口：Rust 协议层出帧 → Kotlin RFCOMM 写。 */
    interface TransportSender {
        fun send(bytes: ByteArray)
    }

    /** 自检：返回 "alive, host=<是否已注入回调>"。 */
    external fun nativeHello(): String

    // ---- 固件原语（转译自 iOS accessoryd，有单测锁定行为）----

    /**
     * iAP2 校验和（固件实现）。[kind] 0 = 单字节累加和，1 = Fletcher-16 变体。
     * [len] 是**不含校验字节本身**的长度（固件调用点就是这么传的）。
     * 失败返回 -1。
     */
    external fun nativeIap2Checksum(
        data: ByteArray,
        len: Int,
        kind: Int,
    ): Int

    /** 是否 iAP2 DETECT marker（`FF 55 02 00 EE 10`）。 */
    external fun nativeIap2IsDetectMarker(
        data: ByteArray,
        len: Int,
    ): Int

    /** 0x1D01 识别帧的参数名（固件查表；未知参数返回 "Unknown"）。 */
    external fun nativeIdentificationParamName(paramId: Int): String?

    /**
     * 固件自己的链路层 SYN 协商默认参数（16 字节 / 4 个 u32 槽位）。
     *
     * iAP2 链路建立的第一个字节级契约。之前 Kotlin 侧是按对照实现抄的常量，
     * 现在直接用 accessoryd 里的值。
     */
    external fun nativeIap2SynParamDefaults(): ByteArray?

    /**
     * MFi 挑战长度确定性判定（固件 0x10002EF50 家族，Rust 真身有单测锁定）：
     * 读对端证书 X.509 版本号 → v3=20B、MFi4 新格式=32B、MFi4 协商+非 v3=16B。
     *
     * 这是 2026-10-27 定下的「不用记忆或轮换测试」的终局：长度由**对端证书
     * 本身**决定，而不是按车机记忆试探。返回 -1 = 证书不可解析（调用方回落
     * MfiLengthMemory 记忆与基线顺序）。
     *
     * [certDer] 是 0xAA01 收到的对端证书 DER 字节；[negotiatedMfi4] = 本会话
     * 是否协商为 MFi4 证书。
     */
    external fun nativeMfiChallengeSizeForCertificate(
        certDer: ByteArray,
        negotiatedMfi4: Boolean,
        authVerMajor: Int = 0,
    ): Int

    // ---- 配对族（fw::pairing，协议 100% 在 Rust；Kotlin 只剩 RTSP 传输）----
    // fw 溯源：verify = _VerifyClientExchange @0x192948D78、
    //          setup = _SetupClientExchange @0x192944724、
    //          派生 = _PairingSessionDeriveKey @0x19286D644。

    /** 创建 pair-verify 会话并构建 M1（fw: state1）。flags=0xC 主形态。返回句柄，0=失败。 */
    external fun nativePairingVerifyCreate(
        flags: Int,
        identifier: ByteArray,
        privKey32: ByteArray,
    ): Long

    /** M1 出站体。 */
    external fun nativePairingVerifyM1(handle: Long): ByteArray?

    /** 处理 M2（验签对端身份 + 备好 M3）。[peerStaticPub] 传 null = 配对库缺项（兼容跳过验签）。0=成功。 */
    external fun nativePairingVerifyM2(
        handle: Long,
        m2: ByteArray,
        peerStaticPub: ByteArray?,
    ): Int

    /** M3 出站体（M2 成功后可用）。 */
    external fun nativePairingVerifyM3(handle: Long): ByteArray?

    /** 处理 M4（解密终报）。0=交换完成。 */
    external fun nativePairingVerifyM4(
        handle: Long,
        m4: ByteArray,
    ): Int

    /** fw: _PairingSessionDeriveKey——Control/Events/DataStream salt-info 表。 */
    external fun nativePairingVerifyDeriveKey(
        handle: Long,
        salt: ByteArray,
        info: ByteArray,
    ): ByteArray?

    /** 创建 pair-setup 会话并构建 M1（fw: state1；extra = fw: type 0x1F 域，0=不带）。 */
    external fun nativePairingSetupCreate(
        flags: Int,
        extra: Long,
        identifier: ByteArray,
        privKey32: ByteArray,
    ): Long

    /** M1 出站体。 */
    external fun nativePairingSetupM1(handle: Long): ByteArray?

    /** 处理 M2（SRP 启动 + 备好 M3）。0=成功。 */
    external fun nativePairingSetupM2(
        handle: Long,
        m2: ByteArray,
        password: ByteArray,
    ): Int

    /** M3 出站体。 */
    external fun nativePairingSetupM3(handle: Long): ByteArray?

    /** 处理 M4（服务器证明 + 备好 M5）。0=成功。 */
    external fun nativePairingSetupM4(
        handle: Long,
        m4: ByteArray,
    ): Int

    /** M5 出站体。 */
    external fun nativePairingSetupM5(handle: Long): ByteArray?

    /** 处理 M6 → `[accIdentifier][0x00][accPublicKey(32)]`；null=失败。 */
    external fun nativePairingSetupM6(
        handle: Long,
        m6: ByteArray,
    ): ByteArray?

    /** 销毁会话。 */
    external fun nativePairingDestroy(handle: Long)

    // ---- 配对编排决策（fw: apsession_ensureConnectedInternal 配对段——
    //      isPeerKnown 分支 + 单连接单次顺序流；重试节奏归宿主连接策略层）----

    /** 创建编排会话。[peerKnown] = 配对库有对端记录。返回句柄，0=失败。 */
    external fun nativePairingFlowCreate(peerKnown: Int): Long

    /** 下一步动作：1=Setup、2=ReconnectThenVerify、3=Verify、4=PlaintextFallback、5=Done、0=非法。 */
    external fun nativePairingFlowNextAction(handle: Long): Int

    /** setup 交换结果反馈（403=信任已建；unsupported=无配对通道）。 */
    external fun nativePairingFlowOnSetupResult(
        handle: Long,
        forbidden403: Int,
        unsupported: Int,
    )

    /** verify 交换结果反馈。 */
    external fun nativePairingFlowOnVerifyResult(
        handle: Long,
        ok: Int,
    )

    /** 销毁编排会话。 */
    external fun nativePairingFlowDestroy(handle: Long)

    // ---- AirPlay RTSP 会话泵（M5.2 架构裁定：协议帧不过 Kotlin 边界——
    //      会话序/CSeq/头集/TLV8/密钥派生全在 Rust；Kotlin 只剩 TCP 三原语 +
    //      泵 + 平台动作消费。fw: apsession_ensureConnectedInternal 直切）----

    /** 创建会话。[clientName]/[setupCode] = **UTF-8 字节数组**（2026-11-02
     *  真机定谳：jstring 通路在荣耀 ROM 上 GetStringUTFChars 越界进 dalvik
     *  守护页 tombstone_25——桥面统一走 bytes）；[setupCode] = pair-setup
     *  SRP 密码（车机侧 PIN，null 回落 3939）；[peerKnown] = 配对库有对端
     *  记录。返回句柄，0=失败。 */
    external fun nativeAirplayCreate(
        flags: Int,
        identifier: ByteArray,
        privateKey: ByteArray,
        clientName: ByteArray?,
        peerKnown: Int,
        setupCode: ByteArray?,
    ): Long

    /** 发起 'info'（裸 GET /info）。出站经 [nativeAirplayTakeOutbound] 取。 */
    external fun nativeAirplayInfo(handle: Long): String?

    /** 注入认证类型（fw: APSKeyHolderGetAuthenticationType；8 = MFiSAP——
     *  'mfi ' 认证循环 Rust 内化，须在 controlSetup 前调用）。 */
    external fun nativeAirplaySetAuthType(
        handle: Long,
        authType: Int,
    )

    /** 注入身份键（fw: 'stup' deviceID=本端 MAC 字符串/sessionCorrelationUUID=
     *  BootUUID/osName/osVersion——宿主等价数据初值，audit §10.34）。 */
    external fun nativeAirplaySetIdentity(
        handle: Long,
        deviceId: ByteArray?,
        correlationUuid: ByteArray?,
        osName: ByteArray?,
        osVersion: ByteArray?,
    )

    /** 发起配对 M1（决策 = orchestrator：isPeerKnown ? verify : setup）。
     *  返回事件 JSON（空 = 无事件；{"type":"pinRequired"} = /pair-pin-start
     *  已发，车机应显示配对码，等 [nativeAirplayProvidePin]）。 */
    external fun nativeAirplayStartPairing(handle: Long): String?

    /** 注入车机展示的配对码（fw: PerformSetup param_2 非 0 形态——PIN 只进
     *  SRP 掩蔽从不上线）。[pin] = UTF-8 字节数组。返回事件 JSON。 */
    external fun nativeAirplayProvidePin(
        handle: Long,
        pin: ByteArray,
    ): String?

    /** 泵入口：注入入站字节（可 null = 纯轮询），推进状态机。返回事件 JSON：
     *  ""（继续等）/ info / send / reconnectRequired / done / error。 */
    external fun nativeAirplayOnBytes(
        handle: Long,
        bytes: ByteArray?,
    ): String?

    /** 取走出站缓冲（null = 无）。泵每轮写完即清。 */
    external fun nativeAirplayTakeOutbound(handle: Long): ByteArray?

    /** 销毁会话。 */
    external fun nativeAirplayDestroy(handle: Long)

    // ---- M5.2 控制面原语（fw: §10.25 换装——协议逻辑全在 Rust）----

    /** 发控制建立（'stup' POST /setup，体/键域全 Rust：et 映射/布尔门控/
     *  sourceVersion 字面量）。出站经 [nativeAirplayTakeOutbound]。 */
    external fun nativeAirplayControlSetup(handle: Long): String?

    /** 注入我方 timing 端口（宿主 UDP 计时服务启动后、控制建立前）。 */
    external fun nativeAirplaySetTimingPort(
        handle: Long,
        port: Int,
    )

    /** 发流级 SETUP（'stup' URL=/，每调用恰 1 条流——固件
     *  setupStreamCreatingResponseInternal @0x2276c5250）。[streamJson] =
     *  **UTF-8 字节数组**（桥面 bytes 约定，同 create）——
     *  {"streamType":N,"audioType":"media"?,...,"timestampInfo":{...}?}；
     *  五路音频 streamType = 100/101/103/106/107（Main/Alt/Buffered/Aux/OOB，
     *  fw: APCarPlayAudioFormatsCreate @0x2277d33f8）。 */
    external fun nativeAirplayStreamSetup(
        handle: Long,
        streamJson: ByteArray,
    ): String?

    /** 发 keep-alive（'keep' 空体 POST /feedback；宿主按 10s 泵）。 */
    external fun nativeAirplayKeepAlive(handle: Long): String?

    /** 创建事件通道（Events 双钥；两数组空 = 明文形态）。 */
    external fun nativeAirplayEventCreate(
        handle: Long,
        writeKey: ByteArray,
        readKey: ByteArray,
    ): Int

    /** 事件通道泵：注入字节，返回事件 JSON 数组
     *  [{"type":"command","cseq":N,"body":{...}},{"type":"reply","status":200}]。 */
    external fun nativeAirplayEventOnBytes(
        handle: Long,
        bytes: ByteArray?,
    ): String?

    /** 事件通道发 'comm' 命令（JSON 体 → plist）。 */
    external fun nativeAirplaySendCommand(
        handle: Long,
        bodyJson: ByteArray,
    ): String?

    /** 取走事件通道出站（应答/命令帧；null = 无）。 */
    external fun nativeAirplayEventTakeOutbound(handle: Long): ByteArray?

    /** 发 ANNOUNCE（'annc'，SDP 文本体——fw: _sdpsession_sendAnnounce
     *  @0x22779ddd4 直切）。[paramsJson] = {"uuidU64":N,"localIp":"..",
     *  "ip6":B,"deviceName":"..","audioFormatBit":N,"authType":N,
     *  "aesKeyB64":"..","aesIvB64":"..","latencyMin":N,"latencyMax":N}。
     *  audioFormatBit = APAudioFormat 位枚举（fw::airplay::asbd 表）。
     *  回复经泵 {"type":"announced"}。 */
    external fun nativeAirplayAnnounce(
        handle: Long,
        paramsJson: ByteArray,
    ): String?

    /** 发 RECORD（'rcrd'，空体——fw: Stage2StartTransaction
     *  @0x2276cf518 直切）。回复经泵 {"type":"recorded"}。 */
    external fun nativeAirplayRecord(handle: Long): String?

    /** timing UDP 服务器态应答（车机轮询我方 timingPort——fw:
     *  __NTPClockServerProcessPacket @0x1928677bc 直切；纯函数）。
     *  [epochSecsUnix] = 当前 Unix 秒（内部 + NTP 1900 纪元偏移）。
     *  返回 null = 请求非法。 */
    external fun nativeAirplayTimingRespond(
        packet: ByteArray,
        arrivalNanos: Long,
        transmitNanos: Long,
        epochSecsUnix: Long,
    ): ByteArray?

    /** timing 客户端态请求构造（轮询车机 timingPeerInfo 端口——fw:
     *  _NTPClockClientSendRequest @0x19293fe94 模板直切）。
     *  [rtcp] = true → 32B RTCP 变体，false → 48B 经典 NTP。 */
    external fun nativeAirplayTimingRequest(
        rtcp: Boolean,
        nowNanos: Long,
        epochSecsUnix: Long,
    ): ByteArray

    /** timing 客户端态偏移观测（秒；fw: __NTPClockClientProcessResponse
     *  + 偏移公式 @0x1929402d0 直切——未除 2 固件语义）。
     *  NaN = 响应非法。 */
    external fun nativeAirplayTimingObserve(
        response: ByteArray,
        arrivalNanos: Long,
        epochSecsUnix: Long,
    ): Double
}
