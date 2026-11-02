package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.protocol.NativeBridge
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * AirPlay 计时服务（timing UDP 泵——**字节域已移交 Rust 固件逆向实现**，
 * Kotlin 只剩 socket 编排）。
 *
 * 协议真相（固件直切，docs/FIRMWARE-LINK-AUDIT.md §10.35/§10.36/§10.39）：
 * - 双变体：经典 NTP 48B（[0x23]=LI0/VN4/Mode3）+ RTCP 32B
 *   （[80 D2 00 07] 请求 / [80 D3 00 07] 应答）——`fw::airplay::ntp`
 * - 应答：origin=请求 transmit、receive=到达（SocketGetPacketUpTicks）、
 *   transmit=发送前（mach_absolute_time）、stratum=1、precision=
 *   `1-bitlen(tps)`（fw: _NTPClockCreate @0x19293f574-b4）、refID="XNU1"
 * - 时间换算：秒 = upTicks/tps + epoch（NTP 1900 纪元偏移）、
 *   分数 = 余<<32/tps（fw: @0x192867a70-aa0）——宿主 tps = 10⁹
 *   （System.nanoTime 纳秒直接作 upTicks）；**ntpFromNanos 保留为
 *   AudioSender 的 RTP 时间戳格式化垫片**（同一固件公式 @0x192867a7c-84
 *   的 Kotlin 算术形，非独立协议决策）
 *
 * 宿主分工（M5.2 战役架构）：UDP socket/线程 = 本类；字节校验/应答构造
 * = `NativeBridge.nativeAirplayTimingRespond`（纯函数，Rust 侧有全量测试）。
 *
 * 旧 catplay 对照实现（32B 硬编码 version/pt 校验、t2==t3、无 1900 纪元）
 * 已删除——那不是固件语义。
 */
class TimingServer(
    private val log: (String) -> Unit = {},
) {
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** 收到的 timesync 请求计数（诊断：车机是否真的来同步媒体时钟）。 */
    private var syncRequests = 0L
    private var lastSyncLogAt = 0L

    /** 非 timesync 包计数（诊断：有没有别的 UDP 报文打到这个端口）。 */
    private var unexpected = 0L

    val port: Int get() = socket?.localPort ?: 0

    fun start(): Boolean {
        if (port != 0 && running) return true
        return try {
            val s = DatagramSocket()
            s.reuseAddress = true
            socket = s
            running = true
            Thread({ loop(s) }, "airplay-timing").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("TIMING: 启动失败 - ${error.message}")
            false
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                return // socket closed
            }
            val request = buffer.copyOfRange(0, packet.length)
            val arrivalNanos = System.nanoTime()
            // fw: 应答构造 = __NTPClockServerProcessPacket @0x1928677bc 直切
            // （48B/32B 双变体、精确消费、错误码域——null = 非法请求）
            val response =
                NativeBridge.nativeAirplayTimingRespond(
                    request,
                    arrivalNanos,
                    System.nanoTime(),
                    System.currentTimeMillis() / 1000L,
                )
            if (response == null) {
                unexpected++
                if (unexpected <= 5) {
                    log("TIMING: 收到非 timesync 报文 ${request.size}B（累计 $unexpected）")
                }
                continue
            }
            syncRequests++
            val now = System.currentTimeMillis()
            if (syncRequests <= 3 || now - lastSyncLogAt >= 30_000L) {
                lastSyncLogAt = now
                log("TIMING: timesync 应答 #$syncRequests → ${packet.address.hostAddress}:${packet.port}")
            }
            try {
                s.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /**
         * 单调纳秒 → NTP 64 位（秒<<32 | 分数），大端 8 字节。
         * fw: `秒 = upTicks/tps + epoch`、`分数 = 余<<32/tps`
         * （@0x192867a70-aa0 直切；epoch 由调用方语义决定——RTP 域取 0 基准，
         * NTP 线域走 Rust 侧 [NativeBridge.nativeAirplayTimingRespond]）。
         */
        fun ntpFromNanos(nanos: Long): ByteArray {
            val secs = Math.floorDiv(nanos, 1_000_000_000L)
            val fracNanos = Math.floorMod(nanos, 1_000_000_000L)
            val frac = (fracNanos.toLong() shl 32) / 1_000_000_000L
            val packed = (secs shl 32) or (frac and 0xFFFF_FFFFL)
            return ByteArray(8) { index -> (packed ushr (8 * (7 - index))).toByte() }
        }
    }
}
