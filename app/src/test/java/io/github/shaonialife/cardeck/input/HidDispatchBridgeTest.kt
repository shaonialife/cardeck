package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.protocol.NativeBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * HID 分派 JNI 桥接（A6 下沉后 Kotlin 侧仅剩的面）。
 *
 * 字节级判定（描述符扫描/格式判定/报告解码/判定树）全在 Rust
 * fw::airplay::hid（19 项测试钉，含退役 Kotlin 测试的全量字节钉）。
 * 本测试只验桥接：JNI 签名、JSON round-trip、null 语义。
 */
class HidDispatchBridgeTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadNative() {
            val candidates =
                listOf(
                    "../../cardeck-protocol/target/debug/libcardeck_protocol.dylib",
                    "../../cardeck-protocol/target/debug/libcardeck_protocol.so",
                )
            val lib = candidates.map(::File).firstOrNull { it.exists() }
            assumeTrue("native 协议库未构建（cargo build）——跳过", lib != null)
            System.load(lib!!.absolutePath)
        }
    }

    /** 奥迪 eso_playback 描述符（46B，Consumer Array 9 项）。 */
    private val audiConsumer =
        hex(
            "05 0c 09 01 a1 01 15 00 25 08 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a b3 " +
                "00 0a b4 00 0a b5 00 0a b6 00 0a b9 00 0a bc 00 75 08 95 01 81 00 c0",
        )

    private fun hex(text: String): ByteArray =
        text
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun mediaDispatchRoundTrip() {
        val json =
            NativeBridge.nativeAirplayHidDispatch(audiConsumer, hex("05"))
        assertEquals("media", JSONObject(json!!).getString("kind"))
        assertEquals(0xB5, JSONObject(json).getJSONArray("usages").getInt(0))
    }

    @Test
    fun consumerReleaseIsNull() {
        // Consumer 设备 0 值报文 = MediaRelease → 无动作（null）
        assertNull(NativeBridge.nativeAirplayHidDispatch(audiConsumer, hex("00")))
    }

    @Test
    fun touchFallbackWithoutDescriptor() {
        val json =
            NativeBridge.nativeAirplayHidDispatch(null, hex("01 00 02 00 01"))
        val obj = JSONObject(json!!)
        assertEquals("touch", obj.getString("kind"))
        val c = obj.getJSONArray("contacts").getJSONObject(0)
        assertEquals(0x0200, c.getInt("x"))
        assertEquals(true, c.getBoolean("down"))
    }
}
