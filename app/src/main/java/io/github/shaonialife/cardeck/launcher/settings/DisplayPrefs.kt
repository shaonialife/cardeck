package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context

/**
 * 「显示」面板偏好：视频流/虚拟屏的分辨率缩放与目标帧率。
 *
 * - 分辨率缩放（30%–160%）：按车机通告分辨率的百分比生成流画布与虚拟屏，
 *   密度同步缩放（逻辑布局不变，只改像素量）。**仅下次连接生效**——
 *   编码器与全部虚拟屏按会话建立时的尺寸创建，运行中改无意义。
 * - 帧率（30/60）：合成器出帧节奏上限，运行中可实时切换。
 *
 * 「实际生效值」由 SessionController 在会话建立时记录（activeScalePercent /
 * activeTargetFps），英雄卡显示运行时值而非偏好值。
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100

    const val FPS_DEFAULT = 30

    /** 触摸 move 注入节流默认值（ms）：0=默认关闭，交互滞后时在「显示」面板调高试验。 */
    const val MOVE_THROTTLE_DEFAULT = 0
    const val MOVE_THROTTLE_MAX = 48

    /** 连接时真息屏（物理面板断电）默认值。 */
    // 默认**关**（2026-10-24 用户要求）：连接成功就断面板对多数人太激进，
    // 想要的人在 设置→显示 手动开启（开关保留，只改默认值）
    const val SCREEN_OFF_DEFAULT = false

    /** HID 原始报文日志默认值：默认关（校准键位时临时打开，避免刷屏）。 */
    const val HID_RAW_LOG_DEFAULT = false

    /** 完整触摸日志默认值：默认关（只出 5s 汇总计数，无坐标）。 */
    const val TOUCH_FULL_LOG_DEFAULT = false

    /** 音频路由自愈默认值：默认开（断开的是会话期间抢走媒体输出的蓝牙设备）。 */
    const val AUDIO_ROUTE_RECLAIM_DEFAULT = true

    /**
     * 意外断开保留虚拟屏（2026-11-10）：默认开——车机软件拆链/WiFi 断/熄火后保留
     * 虚拟屏与屏上应用，自动重连成功即复用（应用状态连续）。手动断开始终立即销毁。
     */
    const val KEEP_DISPLAYS_DEFAULT = true

    /** 保留期重试窗口数（每窗 60s）：连续 N 窗未恢复即销毁虚拟屏。 */
    const val RETENTION_RETRIES_DEFAULT = 3
    const val RETENTION_RETRIES_MIN = 1
    const val RETENTION_RETRIES_MAX = 10

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"
    private const val KEY_SCREEN_OFF = "screen_off_on_connect"
    private const val KEY_HID_RAW_LOG = "hid_raw_log"
    private const val KEY_TOUCH_FULL_LOG = "touch_full_log"
    private const val KEY_AUDIO_ROUTE_RECLAIM = "audio_route_reclaim"
    private const val KEY_KEEP_DISPLAYS = "keep_displays_on_disconnect"
    private const val KEY_RETENTION_RETRIES = "display_retention_retries"

    /**
     * 完整触摸日志（杂项设置开关，默认关）：开=逐条打印触点坐标与注入结果
     * （调试车机触摸用）；关=只出每 5 秒一条的汇总计数（无坐标），避免
     * 连续触摸刷屏淹没其它日志。
     */
    fun touchFullLog(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOUCH_FULL_LOG, TOUCH_FULL_LOG_DEFAULT)

    fun setTouchFullLog(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOUCH_FULL_LOG, enabled)
            .apply()
        io.github.shaonialife.cardeck.input.TouchRouter.fullLogEnabled = enabled
    }

    /**
     * 音频路由自愈（杂项设置开关，默认开）：会话中检测到「采集全零 + 有应用在播」
     * 时自动断开蓝牙音频设备（耳机/车机 A2DP 回连抢走媒体输出会让推给车机的是
     * 静音流，2026-10-26 荣耀实测）。
     */
    fun audioRouteReclaim(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_ROUTE_RECLAIM, AUDIO_ROUTE_RECLAIM_DEFAULT)

    fun setAudioRouteReclaim(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_ROUTE_RECLAIM, enabled)
            .apply()
    }

    /**
     * 意外断开保留虚拟屏（默认开）：车机软件拆链/WiFi 断/熄火（AUTO_STOP）后保留
     * 虚拟屏与屏上应用，自动重连成功即复用；连续 [displayRetentionRetries] 个
     * 60s 窗口未恢复则销毁。手动断开/取消连接不受本键影响，始终立即销毁。
     */
    fun keepDisplaysOnUnexpectedDisconnect(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_DISPLAYS, KEEP_DISPLAYS_DEFAULT)

    fun setKeepDisplaysOnUnexpectedDisconnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_DISPLAYS, enabled)
            .apply()
    }

    /** 保留期重试窗口数（1–10，默认 3；每窗 60s）。 */
    fun displayRetentionRetries(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RETENTION_RETRIES, RETENTION_RETRIES_DEFAULT)
            .coerceIn(RETENTION_RETRIES_MIN, RETENTION_RETRIES_MAX)

    fun setDisplayRetentionRetries(
        context: Context,
        retries: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RETENTION_RETRIES, retries.coerceIn(RETENTION_RETRIES_MIN, RETENTION_RETRIES_MAX))
            .apply()
    }

    /**
     * HID 原始报文日志（键位校准用）：打开后每帧 `hidSendReport` 会写一行
     * `HID-RAW: uuid=… bytes=…` 到主日志，用来对照按下某个键时的字节。
     */
    fun hidRawLog(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HID_RAW_LOG, HID_RAW_LOG_DEFAULT)

    fun setHidRawLog(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HID_RAW_LOG, enabled)
            .apply()
        io.github.shaonialife.cardeck.input.HidNavigation.rawLogEnabled = enabled
    }

    /**
     * 连接时真息屏（默认开）：会话建立后把物理面板硬件断电（同 scrcpy `-S`），
     * 系统保持唤醒、虚拟屏照常渲染；关闭则只用暗幕。
     * 切换在会话中立即生效（见 SessionController.applyScreenOffPreference）。
     */
    fun screenOffOnConnect(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_OFF, SCREEN_OFF_DEFAULT)

    fun setScreenOffOnConnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREEN_OFF, enabled)
            .apply()
    }

    /** 流分辨率缩放百分比（30–160，步进 5；默认 100）。 */
    fun scalePercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, SCALE_DEFAULT)
            .coerceIn(SCALE_MIN, SCALE_MAX)

    fun setScalePercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCALE, percent.coerceIn(SCALE_MIN, SCALE_MAX))
            .apply()
    }

    /** 目标帧率（30/60；驱动合成器出帧节奏，运行中切换实时生效）。 */
    fun fps(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, FPS_DEFAULT)

    fun setFps(
        context: Context,
        fps: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FPS, if (fps >= 60) 60 else 30)
            .apply()
    }

    /** 触摸 move 注入节流（ms；0=关闭）。 */
    fun moveThrottleMs(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MOVE_THROTTLE, MOVE_THROTTLE_DEFAULT)
            .coerceIn(0, MOVE_THROTTLE_MAX)

    fun setMoveThrottleMs(
        context: Context,
        ms: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MOVE_THROTTLE, ms.coerceIn(0, MOVE_THROTTLE_MAX))
            .apply()
    }
}
