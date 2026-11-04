package io.github.shaonialife.cardeck.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.bt.AutoConnectPrefs

/**
 * 「杂项设置」面板：不属于任何主题域的开关。2026-10-26 从「显示」面板拆出
 * （「上车自动连接」「HID 原始日志」与显示毫无关系，挤在一起误导归类）。
 * 偏好存储位置不变（AutoConnectPrefs / DisplayPrefs），只是入口换了地方。
 */
class MiscPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_misc, container, false)

    override fun onViewCreated(view: View) {
        val ctx = requireContext()

        // 上车自动连接（车机蓝牙 ACL 连上时自动拉起会话）：默认开
        val autoConnectOn = view.findViewById<TextView>(R.id.btnAutoConnectOn)
        val autoConnectOff = view.findViewById<TextView>(R.id.btnAutoConnectOff)
        var autoConnectEnabled = AutoConnectPrefs.enabled(ctx)

        fun refreshAutoConnect(enabled: Boolean) {
            autoConnectOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            autoConnectOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            autoConnectOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            autoConnectOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshAutoConnect(autoConnectEnabled)

        fun applyAutoConnect(enabled: Boolean) {
            if (enabled == autoConnectEnabled) return
            autoConnectEnabled = enabled
            AutoConnectPrefs.setEnabled(ctx, enabled)
            refreshAutoConnect(enabled)
            hostToast(if (enabled) "已开启：上车自动连接" else "已关闭：上车不再自动启动")
        }
        autoConnectOn.setOnClickListener { applyAutoConnect(true) }
        autoConnectOff.setOnClickListener { applyAutoConnect(false) }

        // HID 原始日志（键位校准）：默认关；打开后每帧 hidSendReport 写一行 HID-RAW
        val hidRawOn = view.findViewById<TextView>(R.id.btnHidRawOn)
        val hidRawOff = view.findViewById<TextView>(R.id.btnHidRawOff)
        var hidRawEnabled = DisplayPrefs.hidRawLog(ctx)

        fun refreshHidRaw(enabled: Boolean) {
            hidRawOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            hidRawOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            hidRawOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            hidRawOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshHidRaw(hidRawEnabled)

        fun applyHidRaw(enabled: Boolean) {
            if (enabled == hidRawEnabled) return
            hidRawEnabled = enabled
            DisplayPrefs.setHidRawLog(ctx, enabled)
            refreshHidRaw(enabled)
            hostToast(if (enabled) "已开启 HID 原始日志（校准完请关掉）" else "已关闭 HID 原始日志")
        }
        hidRawOn.setOnClickListener { applyHidRaw(true) }
        hidRawOff.setOnClickListener { applyHidRaw(false) }

        // 完整触摸日志（调试车机触摸）：默认关（只出 5s 汇总计数，无坐标）
        val touchFullOn = view.findViewById<TextView>(R.id.btnTouchFullOn)
        val touchFullOff = view.findViewById<TextView>(R.id.btnTouchFullOff)
        var touchFullEnabled = DisplayPrefs.touchFullLog(ctx)

        fun refreshTouchFull(enabled: Boolean) {
            touchFullOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            touchFullOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            touchFullOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            touchFullOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshTouchFull(touchFullEnabled)

        fun applyTouchFull(enabled: Boolean) {
            if (enabled == touchFullEnabled) return
            touchFullEnabled = enabled
            DisplayPrefs.setTouchFullLog(ctx, enabled)
            refreshTouchFull(enabled)
            hostToast(
                if (enabled) {
                    "已开启完整触摸日志（逐条坐标；排查完请关掉）"
                } else {
                    "已关闭完整触摸日志（只出 5s 汇总）"
                },
            )
        }
        touchFullOn.setOnClickListener { applyTouchFull(true) }
        touchFullOff.setOnClickListener { applyTouchFull(false) }

        // 音频路由自愈：默认开（会话中采集全零+在播 → 断开抢占的蓝牙音频设备）
        val reclaimOn = view.findViewById<TextView>(R.id.btnAudioReclaimOn)
        val reclaimOff = view.findViewById<TextView>(R.id.btnAudioReclaimOff)
        var reclaimEnabled = DisplayPrefs.audioRouteReclaim(ctx)

        fun refreshReclaim(enabled: Boolean) {
            reclaimOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            reclaimOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            reclaimOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            reclaimOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshReclaim(reclaimEnabled)

        fun applyReclaim(enabled: Boolean) {
            if (enabled == reclaimEnabled) return
            reclaimEnabled = enabled
            DisplayPrefs.setAudioRouteReclaim(ctx, enabled)
            refreshReclaim(enabled)
            hostToast(if (enabled) "已开启：音频路由自愈" else "已关闭：音频路由自愈（不再自动断开耳机）")
        }
        reclaimOn.setOnClickListener { applyReclaim(true) }
        reclaimOff.setOnClickListener { applyReclaim(false) }
    }
}
