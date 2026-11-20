package io.github.shaonialife.cardeck.audio

/**
 * 音频重传缓存（环形，槽位 = seq % 容量，命中条件 = 槽内 seq 精确相等）。
 *
 * 纯 Kotlin、零 Android 依赖——JVM 单测直测（[ResendCacheTest]）。
 *
 * **历史教训（0.1.28.171 回归，2026-11-20 定谳）**：首版直接
 * `arrayOfNulls<ByteArray>(512)` 用裸 `sequence` 做索引——键域 0..65535
 * vs 容量 512，sequence=512 处必然越界（ArrayIndexOutOfBoundsException
 * "length=512; index=512"），被采集线程的 Throwable 兜底捕获 → 音频
 * ~11 秒静默死亡（AAC ≈47 包/秒 × 513 包 ≈ 11s，逐轮复现）。环形 +
 * seq 精确匹配后：越界不可能，陈旧槽位别名（旧 seq 查到新包）由
 * seq 相等判定挡住。
 */
class ResendCache(
    private val capacity: Int,
) {
    private val seqs = IntArray(capacity) { -1 }
    private val wires = arrayOfNulls<ByteArray>(capacity)

    /** 存入一包（覆盖同槽旧值）。[seq] 任意非负 Int（RTP 序号域 0..65535）。 */
    fun store(
        seq: Int,
        wire: ByteArray,
    ) {
        val idx = seq.mod(capacity)
        seqs[idx] = seq
        wires[idx] = wire
    }

    /** 精确查询：槽内 seq 与请求 seq 相等才返回（防陈旧槽位别名误发）。 */
    fun get(seq: Int): ByteArray? {
        val idx = seq.mod(capacity)
        return if (seqs[idx] == seq) wires[idx] else null
    }

    fun clear() {
        seqs.fill(-1)
        wires.fill(null)
    }
}
