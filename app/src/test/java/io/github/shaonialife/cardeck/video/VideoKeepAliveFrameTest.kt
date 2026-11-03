package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 屏幕流 keepAlive 帧格式回归。
 *
 * 2026-10-19 奥迪 MMI 3315 实测：只有 UDP keepAlive + /feedback 时，RECORD 后约
 * 10.2s 整轮会话被 reset（视频/事件通道都健康）。catplay 在视频持续推流时**仍每秒**
 * 向屏幕数据口写一条 keepAlive 帧——这是接收端屏幕上独立的存活信号。
 * 帧格式写错时接收端静默忽略、现象仍是「10 秒断开」，所以必须有单测锁住。
 * R3：统计体 bplist 在 Rust（nativeAirplayKeepAliveStatsBody）——带体用例
 * 需要宿主 dylib，键名/类型钉在 fw::airplay::screen 测试。
 */
class VideoKeepAliveFrameTest {
    private fun header(frame: ByteArray) = frame.copyOfRange(0, 128)

    private fun bodySize(header: ByteArray): Int =
        ByteBuffer
            .wrap(header, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    companion object {
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
    fun emptyKeepAliveIsOpcode2HeaderOnly() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = false, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        assertEquals(128, frame.size)
        assertEquals(0, bodySize(frame))
        assertEquals(2, frame[4].toInt() and 0xFF)
    }

    @Test
    fun statsKeepAliveIsOpcode5WithBplistBody() {
        val frame =
            VideoSender.buildKeepAliveFrame(
                withStats = true,
                frames = 30,
                bytesPerSecond = 900_000.0,
                framesPerSecond = 30,
            )
        val head = header(frame)
        assertEquals(5, head[4].toInt() and 0xFF)
        val size = bodySize(head)
        assertEquals(frame.size - 128, size)
        // 键名/类型由 Rust 侧测试钉（fw::airplay::screen::stats_body_keys_and_types）；
        // 此处锁「体确为 bplist00 且长度真实」
        val body = frame.copyOfRange(128, frame.size)
        assertEquals("bplist00", String(body, 0, 8, Charsets.US_ASCII))
        assertTrue(body.size > 8)
    }

    @Test
    fun statsBodyLengthIsMirroredIntoParams14AsLittleEndianFloat() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        val expected = (frame.size - 128).toFloat()
        // params[14] 位于偏移 120..128，后 4 字节是 f32 LE 的 body 长度（catplay 同款）
        val declared = ByteBuffer.wrap(frame, 124, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(expected, declared, 0.0f)
        // 前 4 字节固定 0.0f
        assertEquals(0.0f, ByteBuffer.wrap(frame, 120, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
    }

    @Test
    fun keepAliveHeaderCarriesNoChaChaTag() {
        // keepAlive 是明文帧：body 长度必须等于实际 body（不含 16B tag），
        // 否则接收端会把统计体后的字节当下一帧头，帧边界整体错位。
        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 1, bytesPerSecond = 0.0, framesPerSecond = 1)
        val size = bodySize(header(frame))
        assertTrue("body=$size", size > 0)
        assertEquals(128 + size, frame.size)
    }

    /**
     * VideoConfig 帧头必须与 catplay `ScreenFrame::config` 一致。
     *
     * 奥迪 4851（AUDI 5163 / DELPHI，0.1.4 日志）实测：配对、SETUP、RECORD、推流、
     * 触摸全部正常，**车机屏全黑**。catplay 在 config 头里写了两项被我们漏掉的字段：
     *  - `small_param[1] |= ScreenFlag::Encrypted (0x04)` —— 声明视频帧是密封的；
     *  - `params[1]` = (width, height) 两个 f32 LE —— 车机据此建立解码器画布。
     * DiPlay/WinPlay 只读 body、不校验头，所以测试台一直没暴露。
     */
    @Test
    fun videoConfigHeaderDeclaresEncryptionAndCanvasSize() {
        val header = VideoSender.buildVideoConfigHeader(bodySize = 35, width = 1412, height = 654)

        assertEquals(35, bodySize(header))
        assertEquals(1, header[4].toInt() and 0xFF) // opcode = VideoConfig
        // small_param[1] 在头部偏移 6；Encrypted = 1<<2
        assertEquals(0x04, header[6].toInt() and 0x04)

        // params[0]（偏移 8..16）是 PTS 槽位，config 必须留 0
        for (index in 8 until 16) {
            assertEquals("params[0] 应为 0（偏移 $index）", 0, header[index].toInt())
        }

        // params[1]（偏移 16..24）= width f32 LE、height f32 LE
        val width = ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).float
        val height = ByteBuffer.wrap(header, 20, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(1412.0f, width, 0.0f)
        assertEquals(654.0f, height, 0.0f)
    }

    @Test
    fun videoConfigHeaderUsesEncodedEvenDimensions() {
        // 编码器尺寸在构造时已向下对齐偶数，header 必须报同一个值（车机按它建画布）
        val header = VideoSender.buildVideoConfigHeader(bodySize = 20, width = 1260, height = 654)
        assertEquals(1260.0f, ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
        assertEquals(654.0f, ByteBuffer.wrap(header, 20, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
    }
}
