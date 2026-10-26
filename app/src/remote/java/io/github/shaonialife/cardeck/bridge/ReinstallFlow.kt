package io.github.shaonialife.cardeck.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 重装闭环：服务端推送新 APK → 下载并校验 → 悬浮窗提示 → 点击后
 * **先彻底停会话释放虚拟屏**再走 PackageInstaller。
 *
 * 停会话释放屏是硬要求：安装确认弹窗会落到仍存活的虚拟屏上（车机侧），
 * 手机上没人点得到——AGENTS.md 记录过的「安装卡死」根因。
 */
object ReinstallFlow {
    private val main = Handler(Looper.getMainLooper())

    /**
     * 系统安装确认 Intent（PENDING_USER_ACTION 广播携带）。荣耀实测：从
     * BroadcastReceiver 直接 startActivity 该 Intent 会被静默拦截（无异常、
     * 无界面、无失败回执）——.rd 无悬浮窗权限不受后台启动豁免。改由
     * [BridgeSetupActivity] 前台转发（2026-10-24）。
     */
    @Volatile
    var pendingConfirmIntent: Intent? = null

    /**
     * 本次提交的安装会话 id。PENDING/SUCCESS 广播按它过滤——今天多次失败尝试
     * 的遗留会话其广播可能迟到，异会话的确认 Intent 曾把新版安装引到旧版
     * 「安装成功页」（2026-10-24 用户实拍：点了安装却看到旧版成功页+完成）。
     */
    @Volatile
    var currentInstallSessionId: Int = -1

    /** 设置页组件（绝对类名，am/intent 用；applicationId 后缀不影响）。 */
    const val SETUP_ACTIVITY = "io.github.shaonialife.cardeck.bridge.BridgeSetupActivity"

    /**
     * 清理本应用名下卡死的安装会话。旧版本收不到 PENDING_USER_ACTION 广播
     * （receiver 无 intent-filter）时，确认无人拉起、会话永远停在 Active，
     * 还会堵住后续安装（2026-10-21 实测：会话挂死后连续两次安装「成功」却没装上）。
     * App 启动时调用，只影响自己创建的会话。
     */
    fun abandonStaleSessions(context: Context) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            installer.mySessions.forEach { info ->
                runCatching { installer.abandonSession(info.sessionId) }
                DebugFileLog.append(context, "BRIDGE: 清理遗留安装会话 ${info.sessionId}")
            }
        }
    }

    fun offer(
        context: Context?,
        url: String,
        sha256: String,
        version: String,
        deltaUrl: String? = null,
        deltaSha256: String? = null,
        baseSha256: String? = null,
        deltaSizeKB: Int? = null,
    ) {
        val ctx = context?.applicationContext ?: return
        if (url.isBlank()) return
        DebugFileLog.append(
            ctx,
            "BRIDGE: 收到新版本推送 v$version（" +
                if (deltaUrl.isNullOrBlank()) {
                    "全量"
                } else {
                    "差分包 ${deltaSizeKB ?: "?"}KB" +
                        "）"
                },
        )
        Bg.run("bridge-apk-download") {
            // 差分优先：典型 15MB → 0.1~2MB（蜂窝流量友好）。任何一步失败都
            // 无条件回退全量下载——差分只是优化，绝不阻塞更新。
            var file: File? = null
            var deltaNote: String? = null
            if (!deltaUrl.isNullOrBlank() && !deltaSha256.isNullOrBlank()) {
                file = tryDelta(ctx, deltaUrl, deltaSha256, baseSha256, version)
                if (file == null) {
                    DebugFileLog.append(ctx, "BRIDGE: 差分更新未成——回退全量下载")
                } else if (deltaSizeKB != null) {
                    deltaNote = "差分包 ${deltaSizeKB}KB · 完整包 ${file.length() / 1024 / 1024}MB"
                }
            }
            if (file == null) {
                DebugFileLog.append(ctx, "BRIDGE: 开始下载 $url")
                // 大包跨公网传输可能中途断流（蜂窝弱信号实测），重试一次
                file = download(ctx, url) ?: download(ctx, url)
                if (file == null) {
                    DebugFileLog.append(ctx, "BRIDGE: APK 下载失败：$url")
                    return@run
                }
                val digest = sha256Of(file)
                if (sha256.isNotBlank() && !digest.equals(sha256, ignoreCase = true)) {
                    DebugFileLog.append(ctx, "BRIDGE: APK 校验失败（本地=$digest 期望=$sha256）——拒绝安装")
                    file.delete()
                    return@run
                }
            }
            DebugFileLog.append(
                ctx,
                "BRIDGE: 新版本 v$version 就绪（${file.length() / 1024 / 1024}MB，sha256 ✓" +
                    (deltaNote?.let { "，$it" } ?: "") + "）",
            )
            promptInstallReady(ctx, file, version, deltaNote)
        }
    }

    /**
     * 差分路径：下补丁 → 用本进程 sourceDir 作基线拼装 → 产物 sha256 强校验。
     * 返回 null = 任何失败（调用方回退全量）。
     */
    private fun tryDelta(
        ctx: Context,
        deltaUrl: String,
        deltaSha256: String,
        baseSha256: String?,
        version: String,
    ): File? {
        if (baseSha256.isNullOrBlank()) return null
        val patch =
            download(ctx, deltaUrl, "bridge-update.bspatch") ?: run {
                DebugFileLog.append(ctx, "BRIDGE: 差分包下载失败")
                return null
            }
        val patchDigest = sha256Of(patch)
        if (!patchDigest.equals(deltaSha256, ignoreCase = true)) {
            DebugFileLog.append(ctx, "BRIDGE: 差分包校验失败（本地=$patchDigest 期望=$deltaSha256）")
            patch.delete()
            return null
        }
        val base = File(ctx.applicationInfo.sourceDir)
        val out = File(ctx.cacheDir, "bridge-update-delta.apk")
        val error = DeltaPatch.apply(patch, base, out)
        if (error != null) {
            DebugFileLog.append(ctx, "BRIDGE: 差分应用失败（$error）——v$version")
            out.delete()
            patch.delete()
            return null
        }
        val patchKB = patch.length() / 1024
        patch.delete()
        DebugFileLog.append(ctx, "BRIDGE: 差分应用成功（补丁 ${patchKB}KB + 基线 → ${out.length() / 1024 / 1024}MB）——v$version")
        return out
    }

    /**
     * 新版本就绪后的安装确认：**正规弹窗**（DarkMessageDialog），不用悬浮窗——
     * 悬浮窗易被忽略且点按目标小（用户实测反馈）。前台没有自己的 Activity 时
     * 带参拉起设置页，由它在 onCreate/onNewIntent 里弹。
     */
    private fun promptInstallReady(
        ctx: Context,
        file: File,
        version: String,
        deltaNote: String? = null,
    ) {
        main.post {
            val activity = BridgeSetupActivity.resumed()
            if (activity != null) {
                activity.promptInstall(file, version, deltaNote)
            } else {
                runCatching {
                    ctx.startActivity(
                        Intent()
                            .setClassName(ctx.packageName, SETUP_ACTIVITY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("prompt", "install")
                            .putExtra("apkPath", file.absolutePath)
                            .putExtra("version", version)
                            .putExtra("deltaNote", deltaNote),
                    )
                }
            }
        }
    }

    fun installNow(
        ctx: Context,
        file: File,
    ) {
        BridgePrefs.setResumePending(ctx, true)
        // 先停会话释放全部虚拟屏（含 UserService 持有的）：否则系统安装确认弹窗
        // 落在车机侧虚拟屏，手机上无法确认
        runCatching { SessionController.disconnect(ctx) }
        Bg.run("bridge-install") {
            try {
                val installer = ctx.packageManager.packageInstaller
                // 遗留会话（先前失败尝试停在 PENDING 的）会堵后续安装、其迟到的
                // 状态广播还会串台——每次提交前先清一遍自己的旧会话
                abandonStaleSessions(ctx)
                // 必须先 createSession 拿到会话 id 再 openSession——直接 openSession(0)
                // 会抛 SecurityException「Caller has no access to session 0」
                // （2026-10-21 真机实测：悬浮窗点了没反应，安装从未发起）
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                currentInstallSessionId = sessionId
                val session = installer.openSession(sessionId)
                session.openWrite("apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                // 有意用隐式 action 广播：InstallReceiver 在本包内（exported=false）
                val intent =
                    Intent("io.github.shaonialife.cardeck.BRIDGE_INSTALL_RESULT")
                        .setPackage(ctx.packageName)
                val sender =
                    PendingIntent.getBroadcast(
                        ctx,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(sender.intentSender)
                session.close()
                DebugFileLog.append(ctx, "BRIDGE: 已发起安装（等待系统确认）")
            } catch (error: Exception) {
                DebugFileLog.append(ctx, "BRIDGE: 安装发起失败 - ${error.message}")
            }
        }
    }

    private fun download(
        ctx: Context,
        url: String,
        cacheName: String = "bridge-update.apk",
    ): File? {
        return try {
            val target = URL(url)
            // 优先绑定蜂窝网络下载：会话进行中推新版本时，进程默认网络在车机热点上
            // （无互联网），HttpURLConnection 默认路由必然失败
            val connection =
                (
                    BridgeClient
                        .internetNetwork()
                        ?.let { net -> runCatching { net.openConnection(target) }.getOrNull() }
                        ?: target.openConnection()
                ) as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            // 补丁与全量必须用不同缓存文件（2026-10-25 实测：共用 bridge-update.apk
            // 会让补丁内容被同目录的旧全量覆盖 → sha 校验必然失败、差分永远回退）
            val file = File(ctx.cacheDir, cacheName)
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (error: Exception) {
            DebugFileLog.append(ctx, "BRIDGE: 下载异常 - ${error.message}")
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
