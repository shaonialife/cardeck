package io.github.shaonialife.cardeck.iap2

/**
 * iAP2 控制会话（CSM）分帧与 TLV 参数体编解码。
 *
 * 帧：`0x4040 | u16 总长(含头) | u16 消息ID | 体`，大端，无校验和。
 * 参数：`u16 参数总长(含4字节头) | u16 参数ID | 值`，可嵌套（参数值为子参数序列）。
 * 字符串以 NUL 结尾的 UTF-8 传输。
 *
 * CSM 字节流再由链路层 [Iap2LinkPacket]（会话ID 0）分片承载。
 */
object Iap2Csm {
    const val START = 0x4040
    const val HEADER_BYTES = 6
    const val MAX_FRAME_BYTES = 0xFFFF

    data class Frame(
        val messageId: Int,
        val body: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Frame && other.messageId == messageId && other.body.contentEquals(body)

        override fun hashCode(): Int = messageId * 31 + body.contentHashCode()
    }

    /** 增量解析器：喂入任意分片的 CSM 字节流，弹出完整帧。 */
    class Parser {
        private var buffer = ByteArray(0)

        fun offer(chunk: ByteArray): List<Frame> {
            buffer += chunk
            val frames = ArrayList<Frame>()
            while (true) {
                val start = indexOfStart(buffer)
                if (start < 0) {
                    // 没有帧头：保留最后 1 字节以防跨 chunk 的 0x40
                    buffer = buffer.takeLast(1).toByteArray()
                    break
                }
                if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
                if (buffer.size < HEADER_BYTES) break
                val length = readU16(buffer, 2)
                if (length < HEADER_BYTES || length > MAX_FRAME_BYTES) {
                    buffer = buffer.copyOfRange(1, buffer.size)
                    continue
                }
                if (buffer.size < length) break
                val messageId = readU16(buffer, 4)
                frames += Frame(messageId, buffer.copyOfRange(HEADER_BYTES, length))
                buffer = buffer.copyOfRange(length, buffer.size)
            }
            return frames
        }

        private fun indexOfStart(bytes: ByteArray): Int {
            if (bytes.size < 2) return -1
            for (index in 0..bytes.size - 2) {
                if (bytes[index] == 0x40.toByte() && bytes[index + 1] == 0x40.toByte()) return index
            }
            return -1
        }
    }

    // ---- TLV 参数体 ----

    data class Param(
        val id: Int,
        val value: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Param && other.id == id && other.value.contentEquals(value)

        override fun hashCode(): Int = id * 31 + value.contentHashCode()
    }

    fun parseParams(body: ByteArray): List<Param> {
        val params = ArrayList<Param>()
        var offset = 0
        while (offset + 4 <= body.size) {
            val length = readU16(body, offset)
            if (length < 4 || offset + length > body.size) break
            params += Param(readU16(body, offset + 2), body.copyOfRange(offset + 4, offset + length))
            offset += length
        }
        return params
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

fun Iap2Csm.Param.asString(): String? =
    if (value.isNotEmpty() && value.last() == 0.toByte()) {
        value.copyOf(value.size - 1).decodeToString()
    } else {
        null
    }

fun Iap2Csm.Param.asU16List(): List<Int> {
    require(value.size % 2 == 0) { "u16[] payload has odd length" }
    return List(value.size / 2) {
        ((value[it * 2].toInt() and 0xFF) shl 8) or (value[it * 2 + 1].toInt() and 0xFF)
    }
}
