package io.github.shaonialife.cardeck.audio

import android.content.Context

/**
 * 车机侧声音开关（2026-11-20 hero 面板开关控制，连接不再弹询问）。
 *
 * 存储沿用旧键（"settings"/audio_user_pref(+audio_user_pref_set)）——上车自动连接
 * 的旧读取逻辑无缝兼容；音频采集首选链路（播放捕获/REMOTE_SUBMIX）另存于
 * [io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs]。
 */
object AudioPref {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "audio_user_pref"
    private const val KEY_MIGRATED = "audio_pref_migrated_default_on"

    /** 开关状态（默认开）。 */
    fun enabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 一次性迁移（2026-11-20）：旧「连接弹窗」时代的存量选择（如「仅视频」）作废——
        // hero 开关成为唯一控制入口、默认开；迁移标记持久化，之后开关状态照常记忆。
        if (!sp.getBoolean(KEY_MIGRATED, false)) {
            sp
                .edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_MIGRATED, true)
                .apply()
            return true
        }
        return sp.getBoolean(KEY_ENABLED, true)
    }

    fun set(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean("audio_user_pref_set", true)
            .apply()
    }
}
