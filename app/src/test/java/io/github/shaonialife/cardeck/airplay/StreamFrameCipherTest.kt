package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 流帧加密的两种形态（catplay `ScreenFrameCodec` 的三分类）。
 *
 * 关键回归点：
 *  1. AES-128-CTR 的 key/iv 派生必须与 catplay `derive_aes_stream_keys` 逐字节一致；
 *     密钥流**跨帧连续**（每帧新建实例会让第二帧起全部乱码）；
 *  2. 未配对会话的会话密钥是全零（`et=NONE` 的约定，不是随机值）。
 * bplist「根对象落字节 8」布局钉（CarPlay 210.81 域）已随 R3 迁 Rust
 * （fw::airplay::screen::bplist_root_object_starts_at_byte_eight）。
 */
class StreamFrameCipherTest {
    @Test
    fun aesStreamKeysMatchCatplayDerivation() {
        val sessionKey = ByteArray(16) { (it + 1).toByte() }
        val connectionId = 0x0102_0304_0506_0708L
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(sessionKey, connectionId)

        fun expected(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    "72623859790382856".toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)

        assertArrayEquals(expected("AirPlayStreamKey"), key)
        assertArrayEquals(expected("AirPlayStreamIV"), iv)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
    }

    @Test
    fun unpairedSessionUsesZeroSessionKey() {
        assertArrayEquals(ByteArray(16), AirPlayStreamKeys.ZERO_SESSION_KEY)
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, 1L)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
        // 零密钥派生出的流密钥不应也是全零（否则加解密退化成明文）
        assertNotEquals(ByteArray(16).toList(), key.toList())
    }

    @Test
    fun aesCtrKeystreamIsContinuousAcrossFrames() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val iv = ByteArray(16) { (it * 5).toByte() }
        val frameA = ByteArray(152) { it.toByte() }
        val frameB = ByteArray(152) { (it + 7).toByte() }

        // 发送端：同一实例连续两帧
        val sender = AirPlayCrypto.Aes128CtrStream(key, iv)
        val wireA = frameA.copyOf().also(sender::applyKeystream)
        val wireB = frameB.copyOf().also(sender::applyKeystream)

        // 接收端：同一实例连续解密
        val receiver = AirPlayCrypto.Aes128CtrStream(key, iv)
        val plainA = wireA.copyOf().also(receiver::applyKeystream)
        val plainB = wireB.copyOf().also(receiver::applyKeystream)

        assertArrayEquals(frameA, plainA)
        assertArrayEquals(frameB, plainB)
        // 若错误地每帧新建实例（密钥流重置），wireB 会等于 wireA 的前缀
        assertNotEquals(wireA.take(16), wireB.take(16))
    }

    @Test
    fun chachaStreamKeyMatchesHkdfDerivation() {
        val secret = ByteArray(32) { it.toByte() }
        val key = AirPlayStreamKeys.chachaStreamKey(secret, 42L)
        assertArrayEquals(
            AirPlayCrypto.hkdfSha512(
                secret,
                "DataStream-Salt42".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            ),
            key,
        )
        assertEquals(32, key.size)
    }
}
