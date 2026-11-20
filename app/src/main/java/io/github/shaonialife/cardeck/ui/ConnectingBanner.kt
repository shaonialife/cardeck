package io.github.shaonialife.cardeck.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.shaonialife.cardeck.SenderApp
import io.github.shaonialife.cardeck.SessionController

/**
 * 「正在连接 CarPlay」悬浮通知条（2026-11-20）：自动连接触发且**应用在后台**时
 * 弹出（前台有首页英雄卡，不重复展示）——底部圆角卡片：左侧粒子动画
 * （[ConnectingAnimView]）、右侧「正在连接 CarPlay」+ 实时阶段状态
 * （[SessionController.phaseHint]）、右上角 ✕ 手动关闭；背景色跟随系统深浅色。
 *
 * 自动收起：连接成功（推流运行）/ 连接流程终止（失败/取消，展示满 3s 才判，防
 * 启动间隙误判）；✕ 关闭后本次尝试不再弹出，下一次断开重置。
 *
 * 窗口：TYPE_APPLICATION_OVERLAY + NOT_FOCUSABLE + NOT_TOUCH_MODAL，尺寸只包住
 * 通知条——通知条可点（✕），其余触摸全部穿透给下层应用。
 */
object ConnectingBanner {
    private val main = Handler(Looper.getMainLooper())
    private var banner: View? = null
    private var statusText: TextView? = null
    private var appCtx: Context? = null
    private var shownAtMs = 0L
    private var polling = false
    private var dismissedByUser = false

    /** 连接触发时调用：后台 + 悬浮窗权限满足才弹；已显示则仅刷新状态。 */
    fun maybeShow(context: Context?) {
        val ctx = context?.applicationContext ?: return
        if (banner != null || dismissedByUser) return
        if (SenderApp.foregroundActivities > 0) return // 前台：首页英雄卡已展示状态
        if (!Settings.canDrawOverlays(ctx)) return
        appCtx = ctx
        main.post { showInternal(ctx) }
    }

    /** 连接结束（断开/取消）时调用：立即收起，并重置「本次已手动关闭」标记。 */
    fun dismiss() {
        dismissedByUser = false
        main.post { hideInternal() }
    }

    private fun showInternal(ctx: Context) {
        if (banner != null) return
        val dark =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val d = ctx.resources.displayMetrics.density
        val dp = { v: Float -> (v * d).toInt() }
        val bg = if (dark) Color.parseColor("#F21C1C1E") else Color.parseColor("#F7FFFFFF")
        val titleColor = if (dark) Color.parseColor("#FFF5F5F7") else Color.parseColor("#FF111114")
        val statusColor = if (dark) Color.parseColor("#99E5E5EA") else Color.parseColor("#803C3C43")
        val closeColor = if (dark) Color.parseColor("#99E5E5EA") else Color.parseColor("#803C3C43")

        val box =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(bg)
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(
                            view: View,
                            outline: Outline,
                        ) {
                            outline.setRoundRect(0, 0, view.width, view.height, 20 * d)
                        }
                    }
                clipToOutline = true
                elevation = 10 * d
            }
        box.addView(
            ConnectingAnimView(ctx),
            LinearLayout.LayoutParams(dp(64f), dp(64f)),
        )
        val textBox =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), 0, 0, 0)
            }
        textBox.addView(
            TextView(ctx).apply {
                text = "正在连接 CarPlay"
                setTextColor(titleColor)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        statusText =
            TextView(ctx).apply {
                setTextColor(statusColor)
                textSize = 11.5f
                setPadding(0, dp(3f), 0, 0)
                maxLines = 2
            }
        textBox.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        box.addView(
            textBox,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        box.addView(
            TextView(ctx).apply {
                text = "✕"
                setTextColor(closeColor)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                setOnClickListener {
                    dismissedByUser = true
                    hideInternal()
                }
            },
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_VERTICAL },
        )

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params =
            WindowManager
                .LayoutParams(
                    // 固定宽度 = 全屏宽减两侧边距（不再随内容伸缩），水平居中
                    ctx.resources.displayMetrics.widthPixels - dp(24f),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(14f)
                }
        runCatching {
            wm.addView(box, params)
        }.onFailure { return }
        banner = box
        appCtx = ctx
        shownAtMs = SystemClock.uptimeMillis()
        // 滑入：自下 24dp 平移 + 渐显
        box.translationY = 24 * d
        box.alpha = 0f
        box
            .animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(280)
            .start()
        statusText?.text = SessionController.phaseHint ?: "正在连接…"
        if (!polling) {
            polling = true
            main.postDelayed(poll, 700)
        }
    }

    private val poll =
        object : Runnable {
            override fun run() {
                if (banner == null) {
                    polling = false
                    return
                }
                statusText?.text = SessionController.phaseHint ?: "正在连接…"
                val age = SystemClock.uptimeMillis() - shownAtMs
                when {
                    // 连接成功：推流已运行 → 收起（用户要求）
                    SessionController.videoSender?.isRunning == true -> {
                        hideInternal()
                        return
                    }
                    // 展示满 3s 后仍未在连接流程（失败/取消/未真正开始）→ 收起
                    age > 3_000 &&
                        !SessionController.isSessionRunning() &&
                        !SessionController.sessionStarting.get() -> {
                        hideInternal()
                        return
                    }
                    // 用户回到前台：首页英雄卡已展示状态，通知条让位
                    SenderApp.foregroundActivities > 0 -> {
                        hideInternal()
                        return
                    }
                }
                main.postDelayed(this, 700)
            }
        }

    private fun hideInternal() {
        polling = false
        main.removeCallbacks(poll)
        val box = banner ?: return
        banner = null
        statusText = null
        val d = box.context.resources.displayMetrics.density
        box
            .animate()
            .translationY(24 * d)
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                runCatching {
                    (box.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(box)
                }
            }.start()
    }
}
