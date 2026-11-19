package io.github.shaonialife.cardeck.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/**
 * 应用图标位图化工具（桌面网格 / dock / 设置页共用）。
 *
 * **规整化（2026-11-19）**：系统 `loadIcon()` 返回的图标形态参差——自适应图标（双层
 * Drawable）、全出血旧图标、透明底小图形、圆形/异形旧图标——直接画会出现「图形小到
 * 看不清 / 四角漏底 / 前景溢出区带杂色」的风格不统一。这里按三类分别处理：
 *
 *  1. **自适应图标**（`AdaptiveIconDrawable`）：官方双层渲染（背景铺满 + 前景居中），
 *     并按 1.5× 放大——108dp 网格的图层中央 ~72dp 才是系统 launcher 实际可见区，
 *     不放大等于把设计可见区缩小 1/3，图形比在手机桌面上一圈小（用户实测「图标偏小」）；
 *  2. **旧式图标**按不透明包围盒占比二分（见 [drawLegacy] 注释——两版实机教训：
 *     按「四角像素 alpha」判会把烤圆角抗锯齿边的满幅图误垫白框；按「白板补四角」
 *     会被抖音这类带透明边距的位图描出均匀白环）：包围盒 ≥85% 直接圆角裁剪；
 *     <85%（真悬空小图形）垫白色圆角底板、内容缩到 ~70% 居中（iOS 垫板风格，
 *     深色壁纸上不再悬空）；
 *
 * 圆角比例 [radiusFraction]≈22%（iOS 风格近似）。
 *
 * 在 Drawable 的副本上渲染（constantState.newDrawable；bounds 保存/恢复兜底）：
 * iconCache 中的实例被多个界面共同持有，直接 setBounds 会污染其他使用者——
 * 实机故障根因：桌面网格按自己的 iconSize 改写共享 Drawable 的 bounds 后，
 * dock 里同一实例的缩放矩阵失配（图标变小且偏左，点 home 触发网格重绑后复现）。
 */
object AppIcons {
    /** 垫板上图形最长边占 tile 比例（iOS 约 60~70%，取上限保辨识度）。 */
    private const val PLATE_CONTENT_SCALE = 0.70f

    /** 自适应图层放大倍数：108 网格中央 72/108 可见区 → 1/1.5 = 0.667。 */
    private const val ADAPTIVE_ZOOM = 1.5f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun rounded(
        drawable: Drawable,
        size: Int,
        radiusFraction: Float = 0.22f,
    ): Bitmap {
        if (size <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val target =
            runCatching { drawable.constantState?.newDrawable()?.mutate() }
                .getOrNull() ?: drawable
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * radiusFraction
        val path =
            Path().apply {
                addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW)
            }
        val savedBounds = Rect(target.bounds)
        canvas.save()
        canvas.clipPath(path)
        if (target is AdaptiveIconDrawable) {
            drawAdaptive(target, canvas, size)
        } else {
            drawLegacy(target, canvas, size)
        }
        canvas.restore()
        target.bounds = savedBounds
        return bitmap
    }

    /**
     * 自适应图标：背景/前景两层分别以 [ADAPTIVE_ZOOM] 放大的 bounds 绘制（中央 72dp
     * 可见区铺满 tile）。层为 null（异常 ROM）时退回整体绘制。
     */
    private fun drawAdaptive(
        icon: AdaptiveIconDrawable,
        canvas: Canvas,
        size: Int,
    ) {
        val grow = size * (ADAPTIVE_ZOOM - 1) / 2f
        val bounds = RectF(-grow, -grow, size + grow, size + grow)
        val bg = icon.background
        val fg = icon.foreground
        if (bg == null && fg == null) {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            return
        }
        bg?.let {
            it.bounds = Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            it.draw(canvas)
        }
        fg?.let {
            it.bounds = Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            it.draw(canvas)
        }
    }

    /**
     * 旧式图标：按**不透明包围盒占比**二分（2026-11-19 两版实机教训，判定只用
     * 包围盒、不再看占比/角点）：
     *
     *  - 包围盒 ≥85% tile（满幅 / 自带白板 / 烤圆角方图 / 圆形异形 / **带透明边距的
     *    近满幅图**）→ 原样出，透明处透壁纸。首版「白板只补四角」分支被抖音打回：
     *    其位图黑色方块四周留 ~6% 透明边距，白板把边距填成均匀白环，比透壁纸刺眼
     *    得多；
     *  - 包围盒 <85%（真悬空小图形）→ 白色圆角垫板 + 内容缩到 ~70% 居中。
     */
    private fun drawLegacy(
        target: Drawable,
        canvas: Canvas,
        size: Int,
    ) {
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rawCanvas = Canvas(raw)
        target.setBounds(0, 0, size, size)
        target.draw(rawCanvas)

        val pixels = IntArray(size * size)
        raw.getPixels(pixels, 0, size, 0, 0, size, size)
        var l = size
        var t = size
        var r = -1
        var b = -1
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                if ((pixels[row + x] ushr 24) > 32) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r < l || b < t) {
            // 内容全透明（异常图标）：原样兜底
            canvas.drawBitmap(raw, 0f, 0f, null)
            return
        }
        val contentW = r - l + 1
        val contentH = b - t + 1
        if (contentW >= size * 0.85f && contentH >= size * 0.85f) {
            // 近满幅：原样出（透明处透壁纸，好过垫板描边）
            canvas.drawBitmap(raw, 0f, 0f, null)
            return
        }
        // 真悬空小图形：白板 + 包围盒（外扩 1px 防裁掉抗锯齿边缘）等比缩放居中
        canvas.drawColor(android.graphics.Color.WHITE)
        val pad = 1
        val sl = (l - pad).coerceAtLeast(0)
        val st = (t - pad).coerceAtLeast(0)
        val sr = (r + pad).coerceAtMost(size - 1)
        val sb = (b + pad).coerceAtMost(size - 1)
        val boxW = sr - sl + 1
        val boxH = sb - st + 1
        val scale = PLATE_CONTENT_SCALE * size / maxOf(boxW, boxH)
        val dstW = boxW * scale
        val dstH = boxH * scale
        val dx = (size - dstW) / 2f
        val dy = (size - dstH) / 2f
        canvas.drawBitmap(
            raw,
            Rect(sl, st, sr + 1, sb + 1),
            RectF(dx, dy, dx + dstW, dy + dstH),
            paint,
        )
    }
}
