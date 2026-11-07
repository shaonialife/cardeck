package io.github.shaonialife.cardeck.launcher

import android.content.ComponentName
import android.content.Context

/**
 * 仪表盘右侧卡片偏好：音乐卡片（默认）或「固定应用」（独立虚拟屏 CarPlayPinned
 * 承载的指定应用，密度=手机侧）。
 *
 * mode=[MODE_MUSIC]：卡片由 GridActivity 仪表盘页的音乐卡 View 承载（现状）。
 * mode=[MODE_APP] 且包名可启动：卡片区域由固定应用虚拟屏渲染，音乐卡 View 隐藏。
 *
 * 所选应用被卸载后 [resolve] 返回 null → 自然回退音乐卡片（设置面板同步显示音乐
 * 卡片选中；**存储保留**——应用重装后下个会话自动恢复固定模式）。
 *
 * ⚠️ 选择列表必须排除地图应用（[VirtualLauncher.isMapPackage]）：同一地图包在
 * 固定屏与地图屏两个 display 上会互抢任务。
 */
object DashCardPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_MODE = "dashCardMode"
    private const val KEY_PKG = "dashCardPackage"

    /** 右侧卡片模式：音乐卡片（默认）。 */
    const val MODE_MUSIC = "music"

    /** 右侧卡片模式：固定应用虚拟屏。 */
    const val MODE_APP = "app"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户设置的模式（null = 从未设置过，按音乐卡片）。 */
    fun mode(context: Context): String? = prefs(context).getString(KEY_MODE, null)

    /** 固定应用包名（mode=app 时的选择；与 mode 独立存储——切回音乐不清包名，重选无需重挑）。 */
    fun pinnedPackage(context: Context): String? = prefs(context).getString(KEY_PKG, null)

    fun set(
        context: Context,
        mode: String,
        packageName: String?,
    ) {
        prefs(context)
            .edit()
            .putString(KEY_MODE, mode)
            .putString(KEY_PKG, packageName)
            .apply()
    }

    /** 当前生效的固定应用（mode=app 且应用可启动；null = 音乐卡片回退）。 */
    fun resolve(context: Context): ComponentName? =
        resolveDashCard(
            mode = mode(context),
            pkg = pinnedPackage(context),
            componentFor = { pkg -> launchComponent(context, pkg) },
        )

    /** 该包的可启动 Activity（未安装 / 无启动入口 → null）。 */
    private fun launchComponent(
        context: Context,
        pkg: String,
    ): ComponentName? {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val activity = context.packageManager.resolveActivity(launch, 0)?.activityInfo ?: return null
        // resolveActivity 在无匹配时可能给出其它包的兜底结果：只接受包名一致者，
        // 否则「已卸载」的显式选择会被误判为可用。
        if (activity.packageName != pkg) return null
        return ComponentName(pkg, activity.name)
    }
}

/**
 * 固定应用卡片解析的**纯函数**部分：mode=app 且包名解析出可启动组件才生效，
 * 否则 null = 音乐卡片回退。应用被卸载（componentFor 返回 null）即自然回退。
 * 独立出来便于单测（不需要 Context），见 DashCardPrefsTest。
 */
internal fun <T> resolveDashCard(
    mode: String?,
    pkg: String?,
    componentFor: (String) -> T?,
): T? {
    if (mode != DashCardPrefs.MODE_APP) return null
    return pkg?.let { componentFor(it) }
}
