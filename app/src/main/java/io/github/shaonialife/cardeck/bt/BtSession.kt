package io.github.shaonialife.cardeck.bt

import android.content.Context
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.util.DeviceIdentity

/**
 * BT 控制会话核心流程（Server/Client 共用，此前两份近乎复制）：
 * 构建发送端会话 → Phase 1 交付端点 → 保活泵（阻塞直至链路断开）。
 * socket 封装 / activeChannel 登记 / 生命周期收尾由两条路径各自处理。
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    timeoutMillis: Long,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onLinkClosed: () -> Unit = {},
    /** 本进程已知的车机热点 SSID（无新凭据时沿用，避免每轮拆链）。 */
    knownCarSsid: () -> String? = { null },
    /** 0x1D01 画像判定的链路代际（CLASSIC_MIB3/EVENT_LINK），Phase 1 完成后回调。 */
    onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    /** 0x1D01 识别完成时回调身份原文（车型分派用，如半移交拆 BT 白名单）。 */
    onIdentity: ((String) -> Unit)? = null,
) {
    val session =
        Iap2SenderSession(
            this,
            { line -> log(line) },
            resolveBluetoothMac(context, log),
            DeviceIdentity.deviceName(context),
            onWifiCredentials,
            onIdentity,
            onEndpointUpdate,
            knownCarSsid,
            // MFi 挑战长度按车机身份持久化（省掉 20B 静默 6 秒的试探）
            context.applicationContext,
        )
    val endpoint = session.run(timeoutMillis)
    onGeneration(session.linkGeneration)
    onEndpoint(endpoint)
    // Phase 2 过渡：保持控制通道存活记录配件消息；AirPlay 配对由 onEndpoint 回调发起
    session.pumpIncomingForever()
    // 链路断开（保活泵退出）：通知主控释放「会话处理中」——否则车机拆链后重连带来的
    // 新端点会被僵尸轮次堵门（2026-10-22 奥迪 3315 真机定谳，见 SessionController.onSenderLinkClosed）
    onLinkClosed()
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
