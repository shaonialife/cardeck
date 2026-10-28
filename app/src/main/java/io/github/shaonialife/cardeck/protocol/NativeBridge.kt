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
}
