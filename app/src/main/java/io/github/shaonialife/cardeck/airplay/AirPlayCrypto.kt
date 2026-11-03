package io.github.shaonialife.cardeck.airplay

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.SecureRandom

/**
 * AirPlay 流/控制通道密码学原语（BouncyCastle 轻量 API）+ 本机身份密钥生成。
 *
 * 配对协议原语（X25519/ed25519 签名/配对 nonce）已于 2026-11-02 移交 Rust
 * （fw::pairing，8B nonce=ChaCha 64x64 固件变体；Kotlin 旧的 12B RFC 补零
 * 形态与固件不相容，已随 Srp6aClient/Tlv8 一并删除）。
 * 流密钥派生（HKDF/SHA512）已于 2026-11-03 移交 Rust（A3——fw::airplay::
 * stream_keys，`_coreUtilsKeyHolder_CreateEncryptionKeysForStream`
 * @0x2276c9a20；钥随 streamSetup 事件交付宿主）。
 * 本文件只剩：身份 seed 生成（持久化，A9 退役域）、
 * 流/控制通道 AEAD（nonce64=12B RFC——流层形态，与配对域分立）。
 */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val NONCE_SIZE = 12
    private val random = SecureRandom()

    class Ed25519KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519PublicKey(privateKeyRaw: ByteArray): ByteArray = Ed25519PrivateKeyParameters(privateKeyRaw, 0).generatePublicKey().encoded

    /**
     * AES-128-CTR 密钥流（catplay `AirPlayCipherCodec` / `ScreenFrameCodec` 的 AES 路径）。
     *
     * 计数器是标准 CTR128BE（BouncyCastle SICBlockCipher 同为大端计数），
     * **状态跨帧连续**——catplay 每个流只建一个 `Aes128Ctr`、逐帧 apply_keystream，
     * 所以这里也必须由调用方持有同一个实例，不能每帧新建。
     */
    class Aes128CtrStream(
        key: ByteArray,
        iv: ByteArray,
    ) {
        // newInstance 的返回类型是 CTRModeCipher 接口（SICBlockCipher 的实现细节已封装）。
        private val cipher: org.bouncycastle.crypto.modes.CTRModeCipher =
            SICBlockCipher
                .newInstance(AESEngine())
                .apply { init(true, ParametersWithIV(KeyParameter(key), iv)) }

        /** 原地异或密钥流（加密与解密同一操作）。 */
        fun applyKeystream(data: ByteArray) {
            if (data.isEmpty()) return
            cipher.processBytes(data, 0, data.size, data, 0)
        }
    }

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val processed = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        return if (processed + finalized == output.size) output else output.copyOf(processed + finalized)
    }

    /** 控制通道计数 nonce：4 字节零 + 8 字节小端计数。 */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
        return nonce
    }
}
