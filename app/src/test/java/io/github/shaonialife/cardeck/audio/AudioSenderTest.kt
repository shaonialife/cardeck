package io.github.shaonialife.cardeck.audio

import io.github.shaonialife.cardeck.protocol.NativeBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * 音频 RTP 封包回环测试（JNI seam）：线格式字节构造已下沉 Rust
 * （fw::airplay::rtp，字节钉随 Rust 测试）；本测试验证 JNI 面接通 +
 * 「接收端解密路径」逐字节还原（对照 DiPlay `AudioStream.kt`：AAD=头
 * 4..12、密文=12..n-8、nonce=尾 8B 小端）。
 */
class AudioSenderTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    companion object {
        /**
         * 封包走 JNI（Rust 纯函数）——JVM 测试载宿主 dylib；找不到则跳过
         * （先 cargo build）。同 ForbiddenAudiReplayTest 先例。
         */
        @JvmStatic
        @BeforeClass
        fun loadNativeProtocolLib() {
            val candidates =
                listOf(
                    "../../cardeck-protocol/target/debug/libcardeck_protocol.dylib",
                    "../../cardeck-protocol/target/release/libcardeck_protocol.dylib",
                )
            val lib = candidates.firstOrNull { java.io.File(it).exists() }
            org.junit.Assume.assumeTrue("宿主 dylib 不存在，跳过（先 cargo build）", lib != null)
            System.load(java.io.File(lib!!).absolutePath)
        }

        /** 换序是对合运算（接收端 byteSwapS16 的测试内联形）。 */
        private fun swapS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private fun sender() =
        AudioSender(
            host = "127.0.0.1",
            dataPort = 5000,
            streamKey = key,
            log = {},
        )

    @Test
    fun headerFieldsMatchCarPlayRtpConvention() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES) { it.toByte() }
        val wire = sender().sealPacket(pcm, sequence = 0x1234, timestamp = 512, nonceCounter = 0)

        // version=2；PT=100（catplay 直接以 stream type 作 PT）
        assertEquals(0x80, wire[0].toInt() and 0xFF)
        assertEquals(100, wire[1].toInt() and 0x7F)
        // sequence BE
        assertEquals(0x12, wire[2].toInt() and 0xFF)
        assertEquals(0x34, wire[3].toInt() and 0xFF)
        // timestamp BE = 512
        assertEquals(0, wire[4].toInt() and 0xFF)
        assertEquals(0, wire[5].toInt() and 0xFF)
        assertEquals(2, wire[6].toInt() and 0xFF)
        assertEquals(0, wire[7].toInt() and 0xFF)
        // 总长 = 12 头 + 明文 + 16 tag + 8 nonce
        assertEquals(12 + pcm.size + 16 + 8, wire.size)
    }

    @Test
    fun receiverDecryptPathRestoresPcm() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES) { ((it * 13) and 0xFF).toByte() }
        val wire = sender().sealPacket(pcm, sequence = 7, timestamp = 1024, nonceCounter = 42)

        // —— 复刻接收端解密（DiPlay AudioStream.runData + AndroidMediaSink LPCM 路径）——
        val aad = wire.copyOfRange(4, 12)
        val sealedEnd = wire.size - 8
        val sealed = wire.copyOfRange(12, sealedEnd)
        val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
        val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }

        val decrypted = NativeBridge.nativeAirplayChachaOpen(key, nonce, aad, sealed)!!
        // 线格式为**小端 s16 直通**（2026-11-17 真车判形定谳：BE 七轮无声、
        // catplay NATIVE_ENDIAN 真车可出声 → 0.1.28.116 起切 LE）：
        // 解密结果与原采集 PCM 逐字节一致，接收端无需再换序。
        assertArrayEquals(pcm, decrypted)
    }

    @Test
    fun wirePayloadMatchesCaptureLittleEndian() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56) // 小端样本 0x1234, 0x5678
        val wire = sender().sealPacket(pcm, sequence = 0, timestamp = 0, nonceCounter = 0)
        val decrypted =
            NativeBridge.nativeAirplayChachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(4, 12),
                wire.copyOfRange(12, wire.size - 8),
            )!!
        // 线上 = 采集小端原样（0.1.28.116 起；BE 形态已判形排除）
        assertArrayEquals(pcm, decrypted)
    }

    @Test
    fun distinctNoncesProduceDistinctCiphertext() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES)
        val first = sender().sealPacket(pcm, 0, 0, nonceCounter = 0)
        val second = sender().sealPacket(pcm, 0, 0, nonceCounter = 1)
        val cipherA = first.copyOfRange(12, first.size - 8)
        val cipherB = second.copyOfRange(12, second.size - 8)
        // 明文相同但 nonce 递增：密文必须不同（防 nonce 复用）
        org.junit.Assert.assertFalse(cipherA.contentEquals(cipherB))
    }
}
