package io.github.shaonialife.cardeck.airplay

import java.io.Closeable
import java.net.DatagramSocket

/**
 * G2 SDP 序的本端 UDP 端口（fw: sdpsession_setupStreamCreatingResponseInternal
 * @0x22779d718 的 timingPort/controlPort 入参）。
 *
 * 只分配 **control** 端口；timing 端口复用 [TimingServer] 的 UDP 端口。
 * 端口保持 socket 打开直到 [close]，避免端口被系统回收后 SDP 声明失效。
 */
class SdpLocalPorts private constructor(
    val controlPort: Int,
    private val socket: DatagramSocket,
) : Closeable {
    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun allocate(): SdpLocalPorts? =
            runCatching {
                val socket = DatagramSocket(0)
                SdpLocalPorts(socket.localPort, socket)
            }.getOrNull()
    }
}
