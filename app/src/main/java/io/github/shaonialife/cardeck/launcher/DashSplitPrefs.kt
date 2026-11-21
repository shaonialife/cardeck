package io.github.shaonialife.cardeck.launcher

import android.content.Context

/**
 * 车机仪表盘偏好（2026-11-21 从「左右卡片比例」扩展为仪表盘行为偏好家）：
 *
 *  - **左右卡片比例**——**自由**（默认）：车机侧拖分屏把手调整，落定值持久化
 *    （VirtualLauncher 的 `dashSplitFraction` 偏好，行为不变）；**固定**：比例由
 *    车机设置→仪表盘的拖动条控制（松手才生效），车机侧把手隐藏、拖拽禁用
 *    （[VirtualLauncher.dashHandleHit] 按本开关拦截）。
 *  - **点地图卡片进全屏**（[tapMapCardFullscreen]，默认开）：关=轻点地图卡片不切
 *    全屏，仅操作卡片内地图。
 *
 * 比例两种模式各自独立存储：自由模式用旧键（历史拖动值保留，切回自由即恢复），
 * 固定模式用本文件的键——互不覆盖。
 */
object DashSplitPrefs {
    private const val PREFS = "dash_split"
    private const val KEY_FIXED_MODE = "fixed_mode"
    private const val KEY_FIXED_FRACTION = "fixed_fraction"
    private const val KEY_TAP_MAP_FULLSCREEN = "tap_map_card_fullscreen"

    /** 固定模式默认比例：与分屏默认一致（把手居中）。 */
    const val FIXED_FRACTION_DEFAULT = 0.5f

    fun fixedMode(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIXED_MODE, false)

    fun setFixedMode(
        context: Context,
        fixed: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIXED_MODE, fixed)
            .apply()
    }

    fun fixedFraction(context: Context): Float =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_FIXED_FRACTION, FIXED_FRACTION_DEFAULT)
            .let(VirtualLauncher::clampDashSplitFraction)

    fun setFixedFraction(
        context: Context,
        fraction: Float,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FIXED_FRACTION, VirtualLauncher.clampDashSplitFraction(fraction))
            .apply()
    }

    /** 轻点地图卡片进入全屏地图（默认开；关=轻点只操作卡片内地图）。 */
    fun tapMapCardFullscreen(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TAP_MAP_FULLSCREEN, true)

    fun setTapMapCardFullscreen(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TAP_MAP_FULLSCREEN, enabled)
            .apply()
    }
}
