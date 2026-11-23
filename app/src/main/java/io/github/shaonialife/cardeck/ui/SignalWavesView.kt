package io.github.shaonialife.cardeck.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.shaonialife.cardeck.R

/**
 * 英雄卡「连接状态」视觉（design/phone-ui-proto.html 的 waves SVG 还原）：
 * 外圈虚线轨道 + 三层以正上方为轴、左右各 45° 的 WiFi 式信号弧。
 * 三种状态：
 *  IDLE       静止灰弧
 *  CONNECTING 青弧脉冲呼吸（1.5s，相位错开）+ 轨道快旋（2.6s/圈）
 *  CONNECTED  青弧慢呼吸（2.8s）+ 轨道慢旋（9s/圈）
 * 设计稿坐标以 210×176 为基准，按实际宽度等比缩放。
 */
class SignalWavesView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        enum class State { IDLE, CONNECTING, CONNECTED }

        var state: State = State.IDLE
            private set

        private val density = resources.displayMetrics.density

        private val orbitPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = currentColor(R.color.m3_orbit)
                strokeWidth = 1.6f * density
                pathEffect = DashPathEffect(floatArrayOf(3f * density, 9f * density), 0f)
            }
        private val arcPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

        // 设计稿基准：圆心 (105,88)，三层弧半径 50/68/86，跨 -135°..-45°（12 点方向 ±45°）
        private val designWidth = 210f
        private val designHeight = 176f
        private val cx = 105f
        private val cy = 88f
        private val radii = floatArrayOf(50f, 68f, 86f)
        private val arcWidths = floatArrayOf(2.6f, 2.4f, 2.4f)

        private var animator: ValueAnimator? = null
        private var orbitRotation = 0f

        /** 排队中的动画启动任务（post 到主线程执行；见 [startAnimation] 竞态防护）。 */
        private var pendingStart: Runnable? = null

        fun setState(next: State) {
            // 同状态且动画形态一致（CONNECTING/CONNECTED 应有动画在跑，IDLE 应无）
            // 才跳过——否则自愈式重启（历史 bug：停止连接后蚂蚁线仍在转）。
            val animExpected = next != State.IDLE
            if (state == next && (animator != null) == animExpected && pendingStart == null) return
            state = next
            startAnimation()
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startAnimation()
        }

        override fun onDetachedFromWindow() {
            pendingStart?.let { removeCallbacks(it) }
            pendingStart = null
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        private fun startAnimation() {
            pendingStart?.let { removeCallbacks(it) }
            pendingStart = null
            animator?.cancel()
            animator = null
            orbitRotation = 0f
            val idle = state == State.IDLE
            if (idle) {
                invalidate()
                return
            }
            val period = if (state == State.CONNECTING) 1_500L else 2_800L
            val newAnim =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = period
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { a ->
                        arcPhase = (a.animatedValue as Float) * 2f * Math.PI.toFloat()
                        val playMs = a.currentPlayTime.toLong()
                        val orbitPeriod = if (state == State.CONNECTING) 2_600L else 9_000L
                        orbitRotation = (playMs % orbitPeriod) * 360f / orbitPeriod
                        invalidate()
                    }
                }
            animator = newAnim
            // 竞态防护：start 排队到主线程下一拍执行，其间 setState/startAnimation
            // 可能已重入（cancel+置空/换新）——执行时校验仍是当前动画才启动，否则
            // 会泄出一个无人取消的无限动画（停止连接后蚂蚁线仍在转的历史根因）。
            val r =
                Runnable {
                    pendingStart = null
                    if (animator === newAnim) newAnim.start()
                }
            pendingStart = r
            post(r)
        }

        private var arcPhase = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 按**较短边**等比缩放：横屏下英雄卡被压矮时，只按宽度缩放会让弧线
            // 溢出视图（高度只剩 70dp、宽度仍是半屏）。取 min 后图形始终内接视图。
            val scale = minOf(width / designWidth, height / designHeight)
            val centerX = width / 2f
            // 竖直居中：设计稿圆心 88/176 恰为正中，缩放后同样居中
            val centerY = height / 2f

            canvas.save()
            canvas.rotate(orbitRotation, centerX, centerY)
            canvas.drawCircle(centerX, centerY, radii[2] * scale, orbitPaint)
            canvas.restore()

            val baseColor =
                when (state) {
                    State.IDLE -> currentColor(R.color.m3_wave_idle)
                    State.CONNECTING -> currentColor(R.color.m3_wave_connecting)
                    State.CONNECTED -> currentColor(R.color.m3_wave_connected)
                }
            for (i in radii.indices) {
                // 相位错开：连接中 0.25s/层，已连接 0.4s/层；IDLE 不做明暗变化
                val offset = if (state == State.CONNECTING) i * 0.25f else i * 0.4f
                val alpha =
                    if (state == State.IDLE) {
                        1f
                    } else {
                        0.625f + 0.375f * kotlin.math.sin(arcPhase - offset * 2f * Math.PI.toFloat())
                    }
                arcPaint.color = baseColor
                arcPaint.alpha = (alpha.coerceIn(0.15f, 1f) * 255).toInt()
                arcPaint.strokeWidth = arcWidths[i] * scale
                canvas.drawArc(
                    centerX - radii[i] * scale,
                    centerY - radii[i] * scale,
                    centerX + radii[i] * scale,
                    centerY + radii[i] * scale,
                    -135f,
                    90f,
                    false,
                    arcPaint,
                )
            }
        }

        private fun currentColor(id: Int) =
            androidx.core.content.ContextCompat
                .getColor(context, id)
    }
