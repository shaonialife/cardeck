package io.github.shaonialife.cardeck.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
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
 *  2. **旧式图标一律原尺寸铺满、不缩放**（2026-11-20 用户定稿——缩放垫板两轮实机
 *     都出问题：缩到 70% 让满幅彩图被白边包住，白板又让白色 OEM 图标消失）：
 *     - 包围盒 ≥85%（满幅 / 自带白板 / 烤圆角方图 / 圆形异形 / 带透明边距的近满幅）
 *       → 直接圆角裁剪，透明处透壁纸；
 *     - 包围盒 <85%（透明底图形，含奥迪这类「透明底+白色图形」的 OEM 图标）→ 垫
 *       **对比色**底板：按不透明像素平均亮度选板色（亮图形垫深板、暗图形垫白板，
 *       白色 OEM 图标在白板上会彻底消失——2026-11-20 用户实测），图形原位铺满。
 *
 * 圆角比例 [radiusFraction]≈22%（iOS 风格近似）。
 *
 * 在 Drawable 的副本上渲染（constantState.newDrawable；bounds 保存/恢复兜底）：
 * iconCache 中的实例被多个界面共同持有，直接 setBounds 会污染其他使用者——
 * 实机故障根因：桌面网格按自己的 iconSize 改写共享 Drawable 的 bounds 后，
 * dock 里同一实例的缩放矩阵失配（图标变小且偏左，点 home 触发网格重绑后复现）。
 */
object AppIcons {
    /** 自适应图层放大倍数：108 网格中央 72/108 可见区 → 1/1.5 = 0.667。 */
    private const val ADAPTIVE_ZOOM = 1.5f

    /** 透明底图形的底板色：亮图形（OEM 白图标）垫深板，其余垫白板。 */
    private const val DARK_PLATE = 0xFF1C1C1E.toInt()

    /** 图形平均亮度 ≥ 该值视为「亮图形」（0~255）。 */
    private const val LIGHT_LUMINANCE_THRESHOLD = 140

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
     * 旧式图标：**原尺寸铺满、不缩放**（2026-11-20 用户定稿——缩放垫板两轮实机都出
     * 问题，判定只用不透明包围盒）：
     *
     *  - 包围盒 ≥85% tile（满幅 / 自带白板 / 烤圆角方图 / 圆形异形 / 带透明边距的
     *    近满幅图）→ 原样出，透明处透壁纸；
     *  - 包围盒 <85%（透明底图形，含奥迪「透明底+白色图形」的 OEM 图标）→ 垫
     *    **对比色**底板（按不透明像素平均亮度：亮图形垫深板，否则垫白板——白图标
     *    垫白板会彻底消失），图形原位铺满。
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
        var lumSum = 0L
        var opaque = 0
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                val px = pixels[row + x]
                if ((px ushr 24) > 32) {
                    opaque++
                    lumSum += (0.299f * ((px shr 16) and 0xFF) + 0.587f * ((px shr 8) and 0xFF) + 0.114f * (px and 0xFF)).toLong()
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
        // 透明底图形：对比色垫板 + 图形原位铺满（不缩放）
        val avgLum = (lumSum / opaque.toFloat()).toInt()
        canvas.drawColor(if (avgLum >= LIGHT_LUMINANCE_THRESHOLD) DARK_PLATE else android.graphics.Color.WHITE)
        canvas.drawBitmap(raw, 0f, 0f, null)
    }
}
