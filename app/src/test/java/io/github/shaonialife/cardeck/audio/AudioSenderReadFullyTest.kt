package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** AudioSender 管道流读帧助手：EOF/残包/多次短读行为钉死。 */
class AudioSenderReadFullyTest {
    private class ChunkedInputStream(
        private val chunks: MutableList<ByteArray>,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int = if (index < chunks.size) chunks[index++][0].toInt() and 0xFF else -1

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (index >= chunks.size) return -1
            val chunk = chunks[index]
            val n = minOf(length, chunk.size)
            System.arraycopy(chunk, 0, buffer, offset, n)
            if (n == chunk.size) index++ else chunks[index] = chunk.copyOfRange(n, chunk.size)
            return n
        }
    }

    @Test
    fun `reads full frame across chunk boundaries`() {
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6)
        val stream =
            ChunkedInputStream(mutableListOf(byteArrayOf(1, 2), byteArrayOf(3, 4, 5), byteArrayOf(6)))
        val buffer = ByteArray(6)
        assertTrue(AudioSender.readFully(stream, buffer))
        assertTrue(expected.contentEquals(buffer))
    }

    @Test
    fun `eof before full frame returns false`() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        assertFalse(AudioSender.readFully(stream, ByteArray(6)))
    }

    @Test
    fun `immediate eof returns false`() {
        assertFalse(AudioSender.readFully(ByteArrayInputStream(ByteArray(0)), ByteArray(4)))
    }

    @Test
    fun `zero length buffer is trivially full`() {
        assertTrue(AudioSender.readFully(ByteArrayInputStream(ByteArray(0)), ByteArray(0)))
    }

    @Test
    fun `sequential frames read in order`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val stream = ByteArrayInputStream(data)
        val first = ByteArray(2)
        val second = ByteArray(2)
        assertTrue(AudioSender.readFully(stream, first))
        assertTrue(AudioSender.readFully(stream, second))
        assertEquals(1.toByte(), first[0])
        assertEquals(4.toByte(), second[1])
    }
}
