package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 仪表盘右侧卡片解析回归测试（[resolveDashCard] 纯函数）。
 *
 * 需求口径：音乐卡片是默认；「指定应用」模式且应用可启动才生效；所选应用被卸载
 * （componentFor=null）→ **回退音乐卡片**而非报错（设置面板同步显示音乐卡片选中，
 * 存储保留——应用重装后下个会话自动恢复固定模式）。
 *
 * 用字符串做「已安装应用 → 可启动组件」的替身：泛型纯函数，单测环境里 Android
 * 类（ComponentName）的方法都是未实现的空桩（同 DefaultMapPrefsTest 结构）。
 */
class DashCardPrefsTest {
    private fun pick(
        mode: String?,
        pkg: String?,
        installed: Set<String> = setOf(pkg ?: ""),
    ): String? =
        resolveDashCard(
            mode = mode,
            pkg = pkg,
            componentFor = { p -> if (p in installed) p else null },
        )

    /** 未设置过 / 音乐模式：null = 音乐卡片（现状不变）。 */
    @Test
    fun musicModeAndUnsetReturnNull() {
        assertNull(pick(mode = null, pkg = null))
        assertNull(pick(mode = DashCardPrefs.MODE_MUSIC, pkg = "com.wechat"))
        assertNull(pick(mode = "bogus", pkg = "com.wechat"))
    }

    /** 指定应用模式且应用可启动：返回该应用组件替身。 */
    @Test
    fun appModeResolvesInstalledPackage() {
        assertEquals("com.wechat", pick(mode = DashCardPrefs.MODE_APP, pkg = "com.wechat"))
    }

    /** 指定应用已卸载：回退音乐卡片（null），存储仍保留（本函数不管存储）。 */
    @Test
    fun uninstalledPinnedAppFallsBackToMusic() {
        assertNull(
            pick(
                mode = DashCardPrefs.MODE_APP,
                pkg = "com.gone",
                installed = emptySet(),
            ),
        )
    }

    /** 包名缺失（只切了模式还没挑应用）：回退音乐卡片。 */
    @Test
    fun appModeWithoutPackageFallsBackToMusic() {
        assertNull(pick(mode = DashCardPrefs.MODE_APP, pkg = null))
    }
}
