package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）的 iAP2 蓝牙 RFCOMM 监听。
 *
 * 真实无线 CarPlay 里 iPhone 配对后监听这个 SDP 服务等待车机回连；
 * 测试台上 DiPlay/xcertplay 会扫描已配对的 PHONE_SMART 设备并主动连入。
 *
 * 连入后：链路协商（marker/同步/ACK）→ 完整发送端 bring-up（Phase 1），
 * 成功时回调 [onEndpoint] 交出热点 SSID/密码与 AirPlay 端点。
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** 发送端链路断开（保活泵退出）时通知主控（释放僵尸轮次，见 SessionController.onSenderLinkClosed）。 */
    private val onLinkClosed: () -> Unit = {},
    /** 目标车机（动态读取：拒绝非目标设备的连入；null=来者不拒）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
) {
    companion object {
        /**
         * iPhone 的 iAP2 服务 UUID（Oligo Security 公布；与 xcertplay
         * CarPlayController.IAP2_IPHONE_UUID 一致）。配件端是 …decacaff。
         */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"
        private const val SESSION_TIMEOUT_MILLIS = 60_000L
    }

    private var serverSocket: AtomicReference<BluetoothServerSocket?> = AtomicReference(null)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启")
            return false
        }
        return try {
            serverSocket.set(
                adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID),
            )
            running = true
            log("BT: 监听 $SDP_NAME，等待接收端回连…")
            Thread(::acceptLoop, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: 监听失败 - ${error.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (running) {
            val socket: BluetoothSocket =
                try {
                    serverSocket.get()?.accept() ?: break
                } catch (error: IOException) {
                    if (running) log("BT: accept 失败 - ${error.message}")
                    break
                }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // 指定了目标车机时拒绝其它设备的连入（用户配对了多台车机：非目标车机
            // 的控制器也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null && !TargetDevicePrefs.matches(target, peerAddress)) {
                log(
                    "BT: 忽略非目标设备连入 name=$peerName addr=${peerAddress ?: "?"}" +
                        "（当前目标：「${target.name}」）",
                )
                runCatching { socket.close() }
                continue
            }
            log("BT: 接收端已连入 name=$peerName，开始 iAP2 发送端会话")
            Thread({ runSession(socket) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt") { line -> log(line) }
        activeChannel = channel
        BtSessionGate.inboundActive = true
        try {
            channel.runSenderSession(
                context,
                log,
                onEndpoint,
                onWifiCredentials,
                onLinkClosed,
            )
        } catch (error: IOException) {
            log("SESSION 失败: ${error.message}")
        } finally {
            activeChannel = null
            BtSessionGate.inboundActive = false
            channel.close()
            log("SESSION 结束，等待接收端重连（BT 监听仍在运行）")
        }
    }

    /** 主动结束当前 BT 控制会话（不影响监听）。用于 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT: 已主动断开当前控制会话（模拟 tunnel handoff 后的退役）")
        }
    }

    fun stop() {
        running = false
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        log("BT: 监听已停止")
    }
}
