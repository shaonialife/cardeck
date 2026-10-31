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
    external fun nativeSessionSetEventSink(sink: EventSink)

    /** 事件回调：msgId = iAP2 消息 ID（0x1D01/0x5703/0x4301…），bytes = 完整 40 40 帧。 */
    interface EventSink {
        fun onEvent(
            msgId: Int,
            bytes: ByteArray,
        )
    }

    /** 注册传输出口（Rust 协议层的 bt_write 落地）。 */
    external fun nativeSetTransport(sender: TransportSender)

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
}
