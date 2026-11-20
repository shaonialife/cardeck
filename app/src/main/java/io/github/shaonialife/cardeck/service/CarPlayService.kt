package io.github.shaonialife.cardeck.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.shaonialife.cardeck.MainActivity
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.audio.AudioPref
import io.github.shaonialife.cardeck.audio.SilentProjectionActivity
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.ui.ConnectingBanner
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * 前台保活服务：连接建立期间以 connectedDevice 类型常驻，
 * 防止 app 退到后台后被系统/MagicOS 省电策略杀死导致断连。
 * 连接逻辑仍运行在 MainActivity 派生的线程里——本服务负责保住进程。
 */
class CarPlayService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 息屏保活：CPU 唤醒锁维持编码/协议线程与 TCP 存活探测的响应；
     * WiFi 低延迟锁阻止无线进入激进省电（否则接收端 20s 内判死拆链）。
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock =
            power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySender:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock =
            wifi
                .createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "CarPlaySender:wifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 下车：车机蓝牙 ACL 断开（由 startService 投递——服务在跑时进程已是前台服务态，
        // 不受后台启动限制）。disconnect 内部会 stopService → onDestroy。
        if (intent?.action == ACTION_AUTO_STOP_SESSION) {
            fileLog("SVC: 收到「下车自动结束」——收起会话")
            if (SessionController.isSessionRunning()) {
                SessionController.disconnect(applicationContext)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // 必须显式指定类型为 connectedDevice：
        // 2 参 startForeground 会拾取 manifest 声明的全部类型（含 mediaProjection），
        // 而 Android 14 启动 mediaProjection 类型要求 AppOps PROJECT_MEDIA（用户当次授权才有效）——
        // 重启/授权过期后直接 SecurityException 崩溃（2026-10-09 实测踩坑）。
        // 但**已有投影时**必须继续带上 mediaProjection 类型：否则这次 startForeground
        // 会把类型摘掉，Android 14 会连带停掉投影（自动连接刚拿到的令牌就废了）。
        val fgsTypes =
            if (mediaProjection != null) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            fgsTypes,
        )
        acquireLocks()
        // 授权结果在（用户刚确认投屏）时：升级前台类型再创建投影（try/catch 兜底不崩）
        if (pendingProjectionResult != null) {
            upgradeToProjection()
            takeProjection()
        } else if (mediaProjection != null) {
            upgradeToProjection()
        }
        // 上车自动连接：进程可能是被 ACL 广播冷启动的，先建会话对象（BT client/server、
        // WiFi 连接器、mDNS 广播器都在 ensureSessionObjects 里），再走与首页按钮相同的流程。
        // 放后台线程：冷启动时 ensure 要读偏好/绑定 UserService，别压在服务主线程上。
        if (intent?.action == ACTION_AUTO_START_SESSION) {
            // 会话活跃时短路：自动连接的 ACL 信号可能滞后/重复（移交拆链后的回弹、
            // 会话中车机 HFP 抖动），此时再跑 startConnection 会重启被移交刻意停掉的
            // server 监听、暴露「车机回连挂第二条 BT 链」的风险面
            if (SessionController.isSessionRunning()) {
                fileLog("AUTO: 会话已在进行——忽略本次「上车自动启动」")
                return START_STICKY
            }
            fileLog("SVC: 收到「上车自动启动」——拉起会话")
            val app = applicationContext
            Bg.run("auto-session-start") {
                SessionController.ensure(app)
                // 自动连接默认带声音（先静默领取投屏令牌；失败退回只推视频）——
                // 但用户在「启动连接」弹窗里明确选过「仅视频」时尊重其选择
                // （2026-10-25 测试者反馈：选仅视频后被自动连接覆盖成带声音）
                // 音频开关（hero 面板，2026-11-20）：自动连接按开关走，不再单独判断
                val wantAudio = AudioPref.enabled(app)
                if (!wantAudio) {
                    fileLog("AUTO: 音频开关关闭——本次自动连接仅推视频")
                    SessionController.audioEnabledForSession = false
                } else if (DisplayPrefs.audioCapturePreferSubmix(app)) {
                    // 采集首选 REMOTE_SUBMIX：投影令牌仅播放捕获兜底需要，跳过静默领取；
                    // submix 不可用 → 音频通道不启动（日志有提示）
                    fileLog("AUTO: 采集首选 REMOTE_SUBMIX——跳过静默投屏领取")
                    SessionController.audioEnabledForSession = true
                } else {
                    // 提示先行（2026-11-20 用户要求）：后台自动连接要弹投屏授权窗，
                    // 通知条/英雄卡先说明原因，避免用户疑惑
                    if (mediaProjection == null) {
                        val hint = "车机声音需要投屏授权——请在弹出的窗口点「立即开始」"
                        SessionController.phaseHint = hint
                        ConnectingBanner.holdStatus(hint)
                    }
                    SessionController.audioEnabledForSession = acquireProjectionSilently()
                }
                SessionController.startConnection(app)
            }
        }
        return START_STICKY
    }

    /**
     * 自动连接时静默领取 MediaProjection（AudioPlaybackCapture 的授权来源）。
     *
     * Android 14 的顺序硬约束：**先**有授权结果 → **再**把 FGS 类型升级为
     * mediaProjection → 才能调 `getMediaProjection`。这里直接复用
     * [pendingProjectionResult] + [upgradeToProjection] + [takeProjection]，
     * 与手动授权走同一条路径（唯一区别是令牌来自 [SilentProjectionActivity]）。
     *
     * @return 是否拿到实例；false 时自动连接只推视频（不弹窗、不重试）
     */
    private fun acquireProjectionSilently(): Boolean {
        val grant =
            SilentProjectionActivity.request(applicationContext)
                ?: run {
                    fileLog("SVC: 静默投屏令牌获取失败——本次自动连接只推视频")
                    return false
                }
        pendingProjectionResult = grant.resultCode to grant.data
        upgradeToProjection()
        takeProjection()
        val ok = mediaProjection != null
        fileLog(
            if (ok) {
                "SVC: 静默投屏令牌已就绪（自动连接带声音）"
            } else {
                "SVC: 静默投屏令牌无效（getMediaProjection 失败）"
            },
        )
        return ok
    }

    private fun takeProjection() {
        val (code, data) = pendingProjectionResult ?: return
        pendingProjectionResult = null
        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(code, data)
            // Android 14+：createVirtualDisplay 前必须注册回调（资源管理强制）
            projection?.registerCallback(
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                        // 关键诊断：授权丢失会导致音频/镜像不可用（此前只写 logcat，HKS 加密看不到）
                        fileLog("SVC: MediaProjection 被系统停止（onStop）——音频/镜像需重新授权")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            mediaProjection = projection
            if (projection != null) {
                fileLog("SVC: MediaProjection 就绪（投影创建成功）")
            } else {
                fileLog("SVC: getMediaProjection 返回 null（授权结果已失效？）")
            }
        } catch (error: Exception) {
            fileLog("SVC: getMediaProjection 失败 - ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun upgradeToProjection() {
        try {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (error: Exception) {
            fileLog("SVC: 升级 mediaProjection FGS 类型失败 - ${error.message}")
        }
    }

    override fun onDestroy() {
        releaseLocks()
        // Android 14+：MediaProjection 与前台的 mediaProjection 类型绑定——
        // 保活服务停止（如用户点「断开连接」）时系统会连带停止投影。
        fileLog(
            "SVC: onDestroy（保活服务停止）mediaProjection=" +
                (if (mediaProjection != null) "仍在（即将被系统回收）" else "null"),
        )
        super.onDestroy()
    }

    /** 投影生命周期等关键事件：logcat + 文件双写（HKS 环境下文件是唯一可读通道）。 */
    private fun fileLog(message: String) {
        android.util.Log.i("CarPlayService", message)
        DebugFileLog
            .append(this, message)
    }

    companion object {
        private const val CHANNEL_ID = "carplay_connection"
        private const val NOTIFICATION_ID = 1

        /** 上车自动连接：车机蓝牙 ACL 连上时由 CarBluetoothReceiver 投递。 */
        const val ACTION_AUTO_START_SESSION = "io.github.shaonialife.cardeck.action.AUTO_START"

        /** 下车自动收摊：ACL 断开时投递（只有服务在跑才发，见 CarBluetoothReceiver）。 */
        const val ACTION_AUTO_STOP_SESSION = "io.github.shaonialife.cardeck.action.AUTO_STOP"

        /** 镜像模式：授权后由 MainActivity 写入；服务重声明 FGS 类型后方可创建虚拟屏。 */
        @Volatile
        var mediaProjection: android.media.projection.MediaProjection? = null

        /** 授权结果暂存：由服务在前台类型升级后调用 getMediaProjection（Android 14+ 顺序要求）。 */
        @Volatile
        var pendingProjectionResult: Pair<Int, Intent>? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "CarDeck 连接",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持与车机的连接"
                    setShowBadge(false)
                },
            )
        }
        // 点击通知 → 打开 App 主界面（此前缺 setContentIntent，点击无反应）
        val contentIntent =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CarDeck 运行中")
            .setContentText("正在保持与车机的连接，点开应用可查看日志")
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
