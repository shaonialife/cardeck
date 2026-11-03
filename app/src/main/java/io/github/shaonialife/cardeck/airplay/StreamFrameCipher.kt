package io.github.shaonialife.cardeck.airplay

/**
 * 屏幕/音频流帧加密方式（AirPlay `et` 字段的三种形态）。
 *
 * catplay `ScreenFrameCodec::new` 的选择依据：
 *  - 走过 /pair-setup/pair-verify → ChaCha20-Poly1305（共享密钥派生）；
 *  - 只走过 /auth-setup（MFi-SAP）→ AES-128-CTR（会话密钥）；
 *  - 都没走（`et = NONE`，老车机 pre-HomeKit）→ **AES-128-CTR 且会话密钥全零**
 *    （catplay 注释："There was never support for unencrypted screen sessions.
 *     ET = None maps to AES session where session_key before derivation is all zeroes."）
 *
 * 2026-10-19 数据点：奥迪 4xxx（如 4851）可能属于第三类——对它强制
 * pair-setup 必然失败，而 catplay 先用明文 SETUP 起步、屏幕流走 AES 零密钥即可连上。
 *
 * **A3 起形态选择与钥派生全在 Rust**（fw: createCryptors 分派
 * @0x2276c5fd0-6488 + DataStream 双钥 @0x2276c9a20）——本类只是事件交付
 * 值的载体，实例由 AirPlayLink 按 streamSetup 事件构建。
 */
sealed class StreamFrameCipher {
    /** 视频帧 ChaCha20-Poly1305 密封（16B tag），keepAlive/config 帧不加密。 */
    class ChaCha(
        val key: ByteArray,
    ) : StreamFrameCipher()

    /** 视频帧 AES-128-CTR 流式加密（无 tag，密钥流跨帧连续）。 */
    class AesCtr(
        val key: ByteArray,
        val iv: ByteArray,
    ) : StreamFrameCipher()
}
