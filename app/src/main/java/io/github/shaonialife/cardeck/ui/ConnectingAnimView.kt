package io.github.shaonialife.cardeck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import io.github.shaonialife.cardeck.util.Bg

/**
 * 「正在连接」动画（2026-11-20）：播放 assets 里的 **sprite sheet**
 * （`connecting_anim_sheet.webp`，30 帧 282×282、6×5 网格、33ms/帧——由外部
 * GIF 经「扣黑背景（alpha=亮度软键）+ 主体换应用文字蓝 #0A84FF」处理后拼帧，
 * 构建期产物，源 GIF 不入仓）。Banner 尺寸小巧，逐帧画格子远比视频解码轻。
 */
class ConnectingAnimView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val startMs = SystemClock.uptimeMillis()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val src = Rect()
        private val dst = RectF()

        @Volatile
        private var sheet: Bitmap? = null

        init {
            Bg.run("connecting-anim-decode") {
                val bmp =
                    runCatching {
                        context.assets.open(SHEET_ASSET).use(BitmapFactory::decodeStream)
                    }.getOrNull()
                if (bmp != null) {
                    sheet = bmp
                    postInvalidateOnAnimation()
                }
            }
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sheet = this.sheet ?: return
            val elapsed = SystemClock.uptimeMillis() - startMs
            val index = ((elapsed / FRAME_MS) % FRAME_COUNT).toInt()
            val col = index % COLS
            val row = index / COLS
            val cw = sheet.width.toFloat() / COLS
            val ch = sheet.height.toFloat() / ROWS
            src.set(
                (col * cw).toInt(),
                (row * ch).toInt(),
                ((col + 1) * cw).toInt(),
                ((row + 1) * ch).toInt(),
            )
            val side = minOf(width, height).toFloat()
            dst.set(0f, 0f, side, side)
            canvas.drawBitmap(sheet, src, dst, paint)
            postInvalidateOnAnimation()
        }

        private companion object {
            const val SHEET_ASSET = "connecting_anim_sheet.webp"
            const val COLS = 6
            const val ROWS = 5
            const val FRAME_COUNT = 30
            const val FRAME_MS = 33L
        }
    }
