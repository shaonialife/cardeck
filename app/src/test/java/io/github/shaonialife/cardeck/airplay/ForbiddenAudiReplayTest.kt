package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 「奥迪 MMI 3315_5GHz 首连必拒」回放测试（2026-10-21 真机 sysdiagnose，
 * docs/PROTOCOL-NOTES.md §13a）：车机在自身就绪窗口内对配对请求一律 403
 * （真 iPhone 的 pair-verify 通过后照样在认证段被 403，18~20s 后车机自动
 * 重邀、第二次即成）。
 *
 * 脚本化接收端 = 回环 ServerSocket，对所有 pair-setup / pair-verify 请求
 * 恒回 `HTTP/1.1 403 Forbidden`，驱动真实 [AirPlayClient] 配对循环，断言：
 *  1. `pairingForbidden` 被置位（verify/认证段的 403 也要标记——旧实现只认
 *     pair-setup 段，导致主控误走「断 BT 重试」与「明文降级」两条歧路）；
 *  2. 退避节奏：403 后 ≥4.5s 才进入下一轮尝试（5s 退避，见
 *     [pairingRetryBackoffMs]），不再以 500ms 猛攻可能重置车机就绪状态机；
 *  3. 12s 窗口内 2~4 轮尝试、不产出密钥（对 403 车机没有可成功的路径）。
 */
class ForbiddenAudiReplayTest {
    companion object {
        /**
         * 配对协议在 Rust（fw::pairing，2026-11-02 起）——回放循环需要宿主
         * dylib。按仓库相对路径找 cargo 产物；找不到则跳过（assume），
         * CI 无 Rust 构建时不至于误报。
         */
        @JvmStatic
        @BeforeClass
        fun loadNativeProtocolLib() {
            val candidates =
                listOf(
                    "../../cardeck-protocol/target/debug/libcardeck_protocol.dylib",
                    "../../cardeck-protocol/target/release/libcardeck_protocol.dylib",
                )
            val lib = candidates.firstOrNull { java.io.File(it).isFile }
            org.junit.Assume.assumeTrue("宿主 dylib 不存在，跳过（先 cargo build）", lib != null)
            System.load(java.io.File(lib!!).absolutePath)
        }
    }

    @Test
    fun `首连 403 窗口 - 标记 forbidden 并按 5s 退避重试`() {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val requestAtNs = ArrayList<Long>()
        val lock = Any()
        val acceptor =
            thread(isDaemon = true, name = "forbidden-audi") {
                while (!server.isClosed) {
                    val sock =
                        try {
                            server.accept()
                        } catch (_: Exception) {
                            return@thread
                        }
                    sock.use { s ->
                        s.tcpNoDelay = true
                        s.soTimeout = 4_000
                        val input = BufferedInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        try {
                            while (true) {
                                val head = readHead(input) ?: break // 连接关闭：回到 accept 等下一连接
                                val length =
                                    Regex("Content-Length: *(\\d+)", RegexOption.IGNORE_CASE)
                                        .find(head)
                                        ?.groupValues
                                        ?.get(1)
                                        ?.toIntOrNull() ?: 0
                                if (length > 0) skipFully(input, length)
                                synchronized(lock) { requestAtNs.add(System.nanoTime()) }
                                // 必须回显 CSeq：客户端按 CSeq 派发应答，缺了会当 10s 超时
                                val cseq =
                                    Regex("CSeq: *(\\d+)", RegexOption.IGNORE_CASE)
                                        .find(head)
                                        ?.groupValues
                                        ?.get(1) ?: "1"
                                if (head.startsWith("GET /info")) {
                                    // M5.2 泵协议：info 先行（fw: fetchInitialInfo）——
                                    // 回最小合法 bplist（空字典），让会话推进到配对段
                                    out.write(
                                        (
                                            "HTTP/1.1 200 OK\r\nCSeq: $cseq\r\n" +
                                                "Content-Length: ${EMPTY_BPLIST.size}\r\n\r\n"
                                        ).toByteArray(Charsets.ISO_8859_1),
                                    )
                                    out.write(EMPTY_BPLIST)
                                } else {
                                    // 配对段恒 403（本测试钉的就是 403 窗口重试策略）
                                    out.write(
                                        "HTTP/1.1 403 Forbidden\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n"
                                            .toByteArray(Charsets.ISO_8859_1),
                                    )
                                }
                                out.flush()
                            }
                        } catch (_: Exception) {
                            // 客户端断开换新连接（403 快路径行为），继续服务
                        }
                    }
                }
            }

        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "Audi_MMI_3315_5GHz",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = server.localPort,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("127.0.0.1"),
            )
        val client = AirPlayClient(endpoint, log = { println("AIRPLAY-TEST: $it") }, context = null)
        val keys =
            try {
                client.runPairing(windowMillis = 12_000)
            } finally {
                runCatching { server.close() }
                runCatching { acceptor.join(1_000) }
            }

        assertNull("403 窗口内不可能建立密钥", keys)
        assertTrue("verify/认证段的 403 也必须置位 pairingForbidden", client.pairingForbidden)
        assertTrue(client.attemptedPairing)

        val stamps =
            synchronized(lock) { requestAtNs.sorted() }.map { it / 1_000_000 }
        assertTrue("请求过少（${stamps.size} 次）——循环未真正轮转", stamps.size >= 4)
        // 轮次聚类：同轮内 setup→verify 两条请求间隔 <3s，轮与轮之间 ≥4.5s（5s 退避）
        val cycles = ArrayList<Long>()
        for (i in stamps.indices) {
            if (i == 0 || stamps[i] - stamps[i - 1] >= 3_000) cycles.add(stamps[i])
        }
        assertTrue("轮次 ${cycles.size}（期望 2~4）", cycles.size in 2..4)
        val gaps = cycles.zipWithNext { a, b -> b - a }
        assertTrue("轮间退避不足（$gaps ms，期望 ≥4500）", gaps.all { it >= 4_500 })
    }

    /** 读到 HTTP 头结束（\r\n\r\n）；连接关闭返回 null。 */
    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        val chunk = ByteArray(1)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(chunk[0].toInt().toChar())
            if (buf.length >= 4 && buf.endsWith("\r\n\r\n")) return buf.toString()
            if (buf.length > 16_384) return buf.toString()
        }
    }

    private fun skipFully(
        input: InputStream,
        count: Int,
    ) {
        var left = count
        val buf = ByteArray(4096)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size))
            if (n < 0) return
            left -= n
        }
    }
}

/** 最小合法 bplist00（空字典，plist crate 序列化常量）——info 步回复体。 */
private val EMPTY_BPLIST =
    byteArrayOf(
        98,
        112,
        108,
        105,
        115,
        116,
        48,
        48,
        0xD0.toByte(),
        8,
        0,
        0,
        0,
        0,
        0,
        0,
        1,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        9,
    )
