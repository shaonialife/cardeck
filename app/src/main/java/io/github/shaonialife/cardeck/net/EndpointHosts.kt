package io.github.shaonialife.cardeck.net

import io.github.shaonialife.cardeck.protocol.NativeBridge

/**
 * 接收端地址候选的排序与判定——**薄包装**，全部逻辑在 Rust
 * `fw::netaddr`（字节钉在 Rust 测试；2026-11-04 跨平台下沉刀）。
 *
 * 来源定谳（防跑偏）：地址排序 = **平台连接策略层**，不是固件行为域——
 * B11 已定谳（FIRMWARE-LINK-AUDIT.md §11）：固件应用层无地址评估序，
 * 连接端点真身 = `udpconnection_createRemoteEndpoint` @0x243d4a628 交
 * `nw_endpoint_create_host`，地址族选择委托 Network.framework 系统评估器。
 * 排序策略（IPv4 → 全局 IPv6 → 链路本地 IPv6，同址去重）= Android 无
 * Network.framework 等价物时的宿主策略，下沉 Rust 仅为跨平台复用。
 *
 * 背景：奥迪 MIB3 的 `_airplay._tcp` 只发布链路本地 IPv6（`fe80::…%wlan0`）
 * 时，旧代码会把整条端点判成无地址退到网关兜底（2026-10-19 奥迪 4851 案）。
 */
object EndpointHosts {
    fun isIpv4(host: String): Boolean = NativeBridge.nativeNetaddrIsIpv4(host)

    /** 去掉 `%wlan0` / `%3` 这类 scope 后缀。 */
    fun stripScope(host: String): String = NativeBridge.nativeNetaddrStripScope(host)

    /** 排序 + 去重（空串/纯 scope/非法形态剔除；逻辑在 fw::netaddr）。 */
    fun candidates(addresses: List<String>): List<String> = NativeBridge.nativeNetaddrCandidates(addresses.toTypedArray()).toList()

    /**
     * 地址是否属于「本机所在的车机网段」：IPv4 与本地 /24 前缀一致
     * （拿不到本地 IPv4 时不设限）；链路本地 IPv6 恒真；全局 IPv6 需与
     * 本地某个 IPv6 的前 64 位一致（否则宁可退回网关兜底）。
     */
    fun isSameNetwork(
        host: String,
        localIpv4: String?,
        localIpv6: List<String> = emptyList(),
    ): Boolean = NativeBridge.nativeNetaddrIsSameNetwork(host, localIpv4, localIpv6.toTypedArray())
}
