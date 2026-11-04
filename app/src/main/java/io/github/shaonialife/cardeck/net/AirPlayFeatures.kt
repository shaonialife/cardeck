package io.github.shaonialife.cardeck.net

import io.github.shaonialife.cardeck.protocol.NativeBridge

/**
 * `_airplay._tcp` TXT `features` 位图（形如 `0x44540380,0x61`）——**薄包装**，
 * 解析与位判定在 Rust `fw::netaddr`（字节钉在 Rust 测试；2026-11-04 跨平台
 * 下沉刀）。
 *
 * **固件真身（2026-11-04 直切出土）**：配对/加密能力判定 =
 * `_airPlayDescription_supportsCUPairingAndEncryption` @0x2276c6d98 =
 * `hasFeature(0x26)|hasFeature(0x2e)|hasFeature(0x2b)|hasFeature(0x30)`
 * ——位 **38∪46∪43∪48** 任一命中即支持。此前 Kotlin 单查 bit38 是固件
 * 子集（bit38 车机行为不变；只声明 43/46/48 的接收端现在正确走配对路径）。
 *
 * 解析失败返回 null（未知），调用方按「保持旧行为」处理——绝不能把解析
 * 失败当成「不支持配对」，那会让正常车机被降级成明文。
 */
object AirPlayFeatures {
    /** 解析失败 = null（Rust 侧 `Long.MIN_VALUE` 哨兵转译）。 */
    fun parse(txt: String?): Long? = NativeBridge.nativeAirplayFeaturesParse(txt.orEmpty()).takeIf { it != Long.MIN_VALUE }

    /** 接收端是否声明配对/加密能力（fw @0x2276c6d98 四位或集）；null = 未知。 */
    fun supportsPairing(features: Long?): Boolean? = features?.let { NativeBridge.nativeAirplaySupportsPairing(it) }
}
