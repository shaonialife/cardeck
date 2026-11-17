package io.github.shaonialife.cardeck.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出：把运行日志写成 `下载/CarDeck/CarDeck-log-<时间>.txt`，普通文件管理器可见。
 *
 * 为什么落 MediaStore.Downloads 而不是应用私有目录：
 *  - `filesDir` / `getExternalFilesDir` 普通用户在文件管理器里找不到（Android 11+
 *    系统文件管理器直接屏蔽 `Android/data`），日志导出等于没导；
 *  - MediaStore.Downloads 无需任何存储权限，落盘即出现在系统「下载」目录，
 *    微信/QQ/邮件发送附件也能直接选到。
 *
 * 内容优先取 `files/main_debug.log`：它记录全部 appendLog（不受 UI 日志开关影响，
 * 最多保留最近 2MB）；文件不可读时才退回 UI 的内存缓冲（最近 20~40KB）。
 */
object LogExporter {
    /** 导出范围：默认只导本次进程启动后的日志（历史轮次对排查是噪音）。 */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        CURRENT_SESSION("仅本次启动", ""),
        ALL("全部历史日志", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** 给用户看的绝对路径（文件管理器里按这个找）。 */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
    )

    /** 导出目录（相对外部存储根）：下载/CarDeck。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarDeck"

    /** 文件管理器里展示的绝对路径前缀（主用户外部存储；分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** 导出并返回结果；失败返回 null（调用方提示用户）。任意线程可调（含 IO）。 */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.CURRENT_SESSION,
    ): Result? {
        val body = buildContent(context, fallbackText, scope)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "CarDeck-log-$stamp${scope.fileNameTag}.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // 先标 pending 再写：避免文件管理器/相册扫描到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream 返回 null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", bytes.size.toLong(), scope)
        } catch (_: Exception) {
            // 半截文件（IS_PENDING=1）对用户不可见，但会占空间——清掉
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun buildContent(
        context: Context,
        fallbackText: String,
        scope: Scope,
    ): String =
        buildString {
            appendLine("CarDeck 运行日志")
            appendLine("导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("应用版本: " + versionName(context))
            appendLine("导出范围: " + scope.label)
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} | " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("==================================================")
            val fileLog = DebugFileLog.read(context, currentSessionOnly = scope == Scope.CURRENT_SESSION)
            if (!fileLog.isNullOrBlank()) {
                if (scope == Scope.CURRENT_SESSION) {
                    appendLine("（仅本次进程启动之后的日志；需要历史请点「导出全部」）")
                } else {
                    appendLine("（全部文件日志；文件最多 2MB，超出时系统只保留最近一段）")
                }
                append(fileLog)
                if (!fileLog.endsWith('\n')) appendLine()
            } else {
                // 启动标记刚写、其余日志还没落盘（或文件不可读）时，界面缓冲仍是有效来源
                appendLine("（文件日志不可用——导出界面缓冲，可能不完整）")
                append(fallbackText)
            }
            appendNativeLog(context, scope)
        }

    /**
     * 追加 Rust 侧原生日志（`files/native_debug.log`）。
     *
     * 2026-11-17 用户令：此前导出只含宿主 `main_debug.log`，**Rust 日志
     * （FW-TX/RTSP-PHASE/EVENT-IAP 等协议证据行）从未被导出**——这些恰是
     * 固件判形最关键的证据。同时该文件此前无上限（真车实测 47MB），
     * Rust 侧已加 2MB 轮转（src/lib.rs rotate_if_needed）。
     *
     * 分段：CURRENT_SESSION 仍取整份（Rust 侧已按会话量级轮转，头部老日志
     * 由轮转裁掉）；ALL 取全文。
     */
    private fun StringBuilder.appendNativeLog(
        context: Context,
        scope: Scope,
    ) {
        val file = java.io.File(context.filesDir, "native_debug.log")
        if (!file.isFile) return
        runCatching {
            appendLine()
            appendLine("==================================================")
            appendLine("Rust 原生日志 (native_debug.log)")
            appendLine("大小: ${file.length()} 字节（Rust 侧超 2MB 自动轮转，保留最近约 1MB）")
            appendLine("==================================================")
            val text = file.readText(Charsets.UTF_8)
            append(text)
            if (text.isNotEmpty() && !text.endsWith('\n')) appendLine()
        }.onFailure {
            appendLine("（Rust 日志读取失败：${it.message}）")
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("?")
}
