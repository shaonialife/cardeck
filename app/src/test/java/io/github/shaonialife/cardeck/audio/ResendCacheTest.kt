package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 重传缓存环形/精确匹配语义钉（0.1.28.171 裸索引越界回归的回归钉）。
 *  现场：sequence=512 时 ArrayIndexOutOfBoundsException（"length=512;
 *  index=512"）→ AAC ≈47pps 下 11 秒必崩，音频静默死亡五轮复现。 */
class ResendCacheTest {
    private fun cache() = ResendCache(512)

    @Test
    fun store_and_get_roundtrip() {
        val c = cache()
        val wire = ByteArray(4) { it.toByte() }
        c.store(0, wire)
        assertSame(wire, c.get(0))
        val wire2 = ByteArray(2)
        c.store(65535, wire2)
        assertSame(wire2, c.get(65535))
    }

    @Test
    fun `sequence=512 处不越界（原回归现场）`() {
        val c = cache()
        // 0.1.28.171 在第 513 包（seq=512）崩出 length=512; index=512
        repeat(600) { s -> c.store(s, ByteArray(s % 7 + 1)) }
        // 环形容量 512：seq 0..87 已被 512..599 覆盖，588..599 仍在
        assertNull(c.get(0))
        assertNull(c.get(87))
        assertSame(c.get(88)!!, c.get(88))
        assertEquals(600 % 512, 88)
        // 覆盖窗内精确命中
        for (s in 88..599) {
            val w = c.get(s)
            assertEquals(s % 7 + 1, w!!.size)
        }
    }

    @Test
    fun `陈旧槽位别名防误发`() {
        val c = cache()
        val old = ByteArray(1)
        val new = ByteArray(2)
        c.store(0, old) // 槽 0
        c.store(512, new) // 同槽覆盖（512 % 512 = 0）
        assertNull("旧 seq 查询必须落空（防把新包当旧包重发）", c.get(0))
        assertSame(new, c.get(512))
    }

    @Test
    fun clear_empties_all() {
        val c = cache()
        c.store(5, ByteArray(1))
        c.clear()
        assertNull(c.get(5))
    }
}
