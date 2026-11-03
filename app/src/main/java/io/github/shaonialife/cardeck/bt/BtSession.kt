package io.github.shaonialife.cardeck.bt

import android.content.Context
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.util.DeviceIdentity

/**
 * BT 控制会话核心流程（Server/Client 共用，此前两份近乎复制）：
 * 构建发送端会话 → 会话循环阻塞保活。协议逻辑 100% 在 Rust 引擎；
 * socket 封装 / activeChannel 登记 / 生命周期收尾由两条路径各自处理。
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onLinkClosed: () -> Unit = {},
) {
    // fw-engine：会话循环阻塞至链路关闭；端点/凭据经回调即时上抛
    // （无 Kotlin 超时——协议推进由 Rust 编排泵驱动，活性由链路状态机管）
    val session =
        Iap2SenderSession(
            this,
            { line -> log(line) },
            resolveBluetoothMac(context, log),
            DeviceIdentity.deviceName(context),
            platformLanguage(),
            platformTzOffsetMinutes(),
            platformDstFlag(),
            onWifiCredentials,
            onEndpoint,
        )
    session.run()
    onLinkClosed()
}

/** 本端语言标签（0x4E0A 平台值——Locale.toLanguageTag，如 "zh-CN"）。 */
private fun platformLanguage(): String =
    java.util.Locale
        .getDefault()
        .toLanguageTag()

/** 本端时区偏移（分钟，含夏令时偏移——0x4E0B 参数 1 平台值）。 */
private fun platformTzOffsetMinutes(): Int =
    java.util.TimeZone
        .getDefault()
        .getOffset(System.currentTimeMillis()) / 60_000

/** 本端夏令时标志（0x4E0B 参数 2 u8 平台值；1 = 当前生效）。 */
private fun platformDstFlag(): Int =
    if (java.util.TimeZone
            .getDefault()
            .inDaylightTime(java.util.Date())
    ) {
        1
    } else {
        0
    }

/**
 * 会话启动时解析真实蓝牙 MAC（0x4300 / 0x4E0E 用）。
 *
 * **必须 forceRetry**：`DeviceIdentity` 对解析失败有 10 秒静默窗口，而 BT 会话往往在
 * Shizuku 刚就绪的同一毫秒构造——首次解析（Shizuku 尚未 ready）失败后，10 秒内的所有
 * 调用都直接返回占位 `02:00:00:00:00:00`。奥迪会拿 0x4300 的传输标识与 RFCOMM 对端
 * 核对，占位值导致它**静默丢弃**（链路层照常 ACK、CSM 层不再回应）。
 * 2026-10-19 奥迪 0.1.7 日志即此：整轮都是占位值，车机始终没下发 0x4301。
 */
private fun resolveBluetoothMac(
    context: Context,
    log: (String) -> Unit,
): String {
    val cached = DeviceIdentity.cachedBluetoothMac()
    if (cached != null) return cached
    val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
    if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
        log(
            "BT: ⚠ 未能解析真实蓝牙 MAC（仍是占位值）——车机会校验 0x4300 的传输标识，" +
                "占位值可能导致它不下发 0x4301。请确认 Shizuku 已启动并已授权",
        )
    } else {
        log("BT: 本机蓝牙 MAC=$mac（0x4300/0x4E0E 与 ctrl 广播 TXT id 同源）")
    }
    return mac
}
