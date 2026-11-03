package io.github.shaonialife.cardeck.audio

import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * 音频编码通路的**回归测试**。
 *
 * 背景（2026-10-20 Honda HFT 实测三次都是「有包无声」）：旧实现对所有负载无条件做
 * 大端 s16 换序，连 AAC 比特流也按 2 字节对调。AAC 帧被换序后，车机解码器直接判坏帧
 * ——于是包在流、`/feedback` 的 `sampleTime` 一路推进、喇叭全静音。
 * 这条测试锁死「AAC 帧必须原样上线，只有 PCM 才换序」。
 */
class AudioAacPayloadTest {
    private val key = ByteArray(32) { (it * 11 + 3).toByte() }

    companion object {
        /** 封包走 JNI（Rust 纯函数）——载宿主 dylib，缺则跳过（先 cargo build）。 */
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
    }

    private fun sender(codec: AudioCodec) =
        AudioSender(
            host = "127.0.0.1",
            dataPort = 5000,
            streamKey = key,
            log = {},
            codec = codec,
            streamType = if (codec == AudioCodec.AAC_LC) 102L else 100L,
        )

    @Test
    fun aacFrameGoesOnTheWireByteForByte() {
        // 真实 MediaCodec 首帧的头部（裸 AAC：id_syn_ele=CPE，tag=1）
        val frame = ByteArray(557) { index -> ((index * 37 + 0x21) and 0xFF).toByte() }
        val wire = sender(AudioCodec.AAC_LC).sealPacket(frame, 0, 0, 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        assertArrayEquals(frame, decrypted)
    }

    @Test
    fun pcmIsStillByteSwapped() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val wire = sender(AudioCodec.PCM).sealPacket(pcm, 0, 0, 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), decrypted)
    }

    @Test
    fun firstPacketCarriesMarkerBit() {
        val frame = ByteArray(16)
        val first = sender(AudioCodec.AAC_LC).sealPacket(frame, 0, 0, 0, marker = true)
        val second = sender(AudioCodec.AAC_LC).sealPacket(frame, 1, 1024, 1, marker = false)
        // 字节 1：M(0x80) | PT(102)
        assertEquals(0x80 or 102, first[1].toInt() and 0xFF)
        assertEquals(102, second[1].toInt() and 0xFF)
    }
}
