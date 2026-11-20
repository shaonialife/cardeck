package io.github.shaonialife.cardeck.launcher

import android.content.Context

/**
 * 仪表盘左右卡片比例（2026-11-20）：
 *
 *  - **自由**（默认）：车机侧拖分屏把手调整，落定值持久化（VirtualLauncher 的
 *    `dashSplitFraction` 偏好，行为不变）。
 *  - **固定**：比例由车机设置→仪表盘的拖动条控制（松手才生效），车机侧把手隐藏、
 *    拖拽禁用（[VirtualLauncher.dashHandleHit] 按本开关拦截）。
 *
 * 两种模式各自独立存储：自由模式用旧键（历史拖动值保留，切回自由即恢复），
 * 固定模式用本文件的键——互不覆盖。
 */
object DashSplitPrefs {
    private const val PREFS = "dash_split"
    private const val KEY_FIXED_MODE = "fixed_mode"
    private const val KEY_FIXED_FRACTION = "fixed_fraction"

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
}
