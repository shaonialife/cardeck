package io.github.shaonialife.cardeck.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局调试文件日志（files/main_debug.log）。
 *
 * 荣耀机型 logcat 被 HKS 加密，本文件是 adb 侧唯一调试通道（run-as 读取）。
 * MainActivity 与 CarPlayService（投影生命周期诊断）共用同一把锁，避免同文件并发写交叉。
 */
object DebugFileLog {
    private val lock = Any()

    /**
     * 行时间戳格式：**含日期**（2026-11-17 用户令——跨天测试时只有时分秒
     * 无法区分是哪一天，日志会「混乱」）。年-月-日 时:分:秒.毫秒。
     */
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 调试桥 tap：每条落盘的日志同步交给监听者（**远程调试变体**在 Application 里安装；
     * 日常/发布变体恒为 null，零开销）。seq 单调递增——断线重连按 seq 回补。
     * 监听者在写锁外被调用、异常被吞：绝不能让它拖慢或打断文件写入。
     */
    @Volatile
    var tap: ((seq: Long, line: String) -> Unit)? = null

    private val sequence =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** 最近一条已落盘日志的序号（0 = 本进程尚无日志）。 */
    val lastSeq: Long get() = sequence.get()

    /**
     * 「本次进程启动」标记行。日志文件跨进程保留（最多 2MB），用户反馈问题时
     * 历史轮次的日志会把真正有用的部分淹没——导出默认只取最后一条标记之后的内容。
     * 标记本身写进文件而不是只记内存偏移：文件可能被 2MB 截断，偏移会失效。
     */
    const val SESSION_MARKER = "===== CarDeck 进程启动（本次日志起点）====="

    /**
     * 写入本次启动标记。由 [io.github.shaonialife.cardeck.SenderApp.onCreate] 调用一次；
     * 重复调用会产生多条标记，导出仍取最后一条，行为安全。
     */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /**
     * 读取日志正文。[currentSessionOnly] = true 时只返回最后一条 [SESSION_MARKER]
     * 之后的内容（无标记时返回全文——首次安装/旧版本文件都属这种情形）。
     */
    fun read(
        context: Context,
        currentSessionOnly: Boolean,
    ): String? =
        runCatching {
            val text = File(context.filesDir, "main_debug.log").readText(Charsets.UTF_8)
            if (currentSessionOnly) currentSessionSlice(text) else text
        }.getOrNull()

    /**
     * 读文件尾部原始字节（远程调试桥 `log_file.req` 用，最大 [maxBytes]）。
     * 与写线程并发安全：只读不写；文件不存在返回 null。
     */
    fun readTail(
        context: Context,
        maxBytes: Int,
    ): ByteArray? =
        runCatching {
            val file = File(context.filesDir, "main_debug.log")
            if (!file.isFile) return null
            val len = file.length()
            val start = if (len <= maxBytes) 0L else len - maxBytes
            file.inputStream().use { input ->
                input.channel.position(start)
                input.readBytes()
            }
        }.getOrNull()

    /**
     * 取最后一条启动标记之后的内容（纯函数，单测覆盖边界）。
     * 无标记 → 全文：旧版本写入的文件里没有标记，此时全量比空更好。
     */
    internal fun currentSessionSlice(text: String): String {
        val markerAt = text.lastIndexOf(SESSION_MARKER)
        return if (markerAt < 0) text else text.substring(markerAt + SESSION_MARKER.length)
    }

    fun append(
        context: Context,
        message: String,
    ): Unit = append(context, message, System.currentTimeMillis())

    /** 写入一行（[atMs] = 行的原始发生时刻——Rust 日志桥批次排空时按
     *  Rust 侧时刻渲染，避免整批行时间重复）。 */
    fun append(
        context: Context,
        message: String,
        atMs: Long,
    ) {
        try {
            synchronized(lock) {
                val file = File(context.filesDir, "main_debug.log")
                // 超限直接删会连「本次启动」标记一起丢（导出就退化为全量历史）。
                // 删后立即重写一条标记，保证截断后的新文件仍能按会话切分。
                if (file.length() > 2_000_000) {
                    file.delete()
                    FileWriter(
                        file,
                        true,
                    ).use {
                        it
                            .append('[')
                            .append(timestamp.format(Date(atMs)))
                            .append("] ")
                            .append(SESSION_MARKER)
                            .append('\n')
                    }
                }
                FileWriter(file, true).use { writer ->
                    writer
                        .append('[')
                        .append(timestamp.format(Date(atMs)))
                        .append("] ")
                        .append(message)
                        .append('\n')
                }
            }
            tap?.let { listener ->
                val line = "[" + timestamp.format(Date(atMs)) + "] " + message
                runCatching { listener(sequence.incrementAndGet(), line) }
            }
        } catch (_: Throwable) {
        }
    }
}
