package io.github.shaonialife.cardeck.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仪表盘卡片边距（流像素）——地图 tile 与音乐卡共用的契约值，四方统一引用：
 * 本类 SPLIT 绘制矩形、VirtualLauncher 建屏 tile 尺寸、GridActivity 音乐卡布局、
 * TouchRouter tile 触摸命中域。此前 16 在四个文件各自定义/裸写、仅靠注释结盟，
 * 改一处即静默错位（触摸命中域/卡片布局/绘制矩形三者不一致，坏味道审查 P1 收敛）。
 */
const val CARD_MARGIN_PX = 16

/**
 * 仪表盘分屏中缝宽（流像素）——地图 tile 与音乐卡之间**视觉**缝隙，几何契约同
 * [CARD_MARGIN_PX]：tile 内容侧边缘 = 把手中心 − DASH_GAP_PX/2，音乐卡内容侧
 * 边缘 = 把手中心 + DASH_GAP_PX/2（VirtualLauncher.dashSplitGeom 统一推导）。
 */
const val DASH_GAP_PX = 24f

/**
 * 分屏把手**触达**宽（流像素）：把手 View 宽度与 TouchRouter 粘性路由域按此值，
 * 比 [DASH_GAP_PX] 宽——两侧各溢出 (TOUCH−GAP)/2 压在卡片上扩大触控目标
 * （音乐卡侧把手叠在其上层；地图侧溢出段被 tile 盖住不可见但可触摸——
 * 视觉与触摸分层，2026-10-22 用户反馈「中缝太宽」的解法）。
 */
const val DASH_HANDLE_TOUCH_PX = 60f

/**
 * 双虚拟屏 GL 合成器：dock 屏 + 应用屏（原生 / 超采样两块，按活动切换）→ 拼接输出到编码器输入 Surface。
 *
 * 三路 SurfaceTexture 作为各虚拟屏的输出；GL 线程轮询新帧标志，有新帧即重绘合成画面。
 * 应用区同一时刻只画两块应用屏中的**活动**一块（[requestActiveApp] 切换；超采样屏
 * 等其画出首帧再切换，避免闪黑）。两块应用屏的内容持续消费（纹理保持最新）。
 * 输入 Surface 在 EGL/纹理就绪后经 [onSurfacesReady] 交给调用方去 createVirtualDisplay。
 */
class DualDisplayComposer(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    /** true：dock 是底部横条（竖屏车机）；false：dock 是左侧竖条（横屏车机）。 */
    private val dockAtBottom: Boolean = false,
) {
    /** 仪表盘↔桌面滑动进度（0=tile 原位，1=完全滑出左侧），由 GridActivity 随滑动驱动。 */
    @Volatile
    var tileSlideFraction: Float = 0f

    /**
     * 地图 tile 把手侧边缘覆盖值（流像素，顶部原点；横屏=tile 右缘 x、竖屏=tile
     * 下缘 y）。>0 时替代 SPLIT 公式值——拖动分屏把手时由 VirtualLauncher 逐帧写入，
     * **只改绘制矩形（纹理整幅拉伸），不动 buffer、不动虚拟屏**；松手 commit 才真实
     * resize。≤0 = 用默认中线公式。语义与 [tileSlideFraction] 同类：连续视觉态。
     */
    @Volatile
    private var tileEdgeOverride = -1f

    fun setTileEdgeOverride(edgePx: Float) {
        tileEdgeOverride = edgePx
    }

    /**
     * 目标帧率（30/60，合成出帧节奏上限；「显示」设置运行中实时切换）。
     * 有新输入帧时按 1000/targetFps 节流；静止画面保底 30fps 心跳（编码器不能断粮）。
     */
    @Volatile
    var targetFps: Int = 30

    companion object {
        private const val TAG = "DualComposer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val TILE_CORNER_RADIUS = 24f

        /** 2D 纹理程序用的单位纹理矩阵（Bitmap 已按目标方向摆好顶点）。 */
        private val IDENTITY_ST =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )
    }

    private val running = AtomicBoolean(false)
    private var glThread: Thread? = null

    /** start() 的就绪闩：GL 交付输入 Surface 后放行（注意不能等合成循环退出） */
    @Volatile
    private var startLatch: CountDownLatch? = null

    /** 是否创建了超采样应用屏（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasHiInput = false

    /** 是否创建了地图屏输入（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasMapInput = false

    /** 当前应用区显示的是超采样屏（true）还是原生屏（false）。触摸路由以它为准。 */
    @Volatile
    private var activeAppHi = false

    /** 待切换的目标屏（true=超采样屏；null=无待切换）；切屏等目标出帧，避免闪黑/闪旧帧。 */
    @Volatile
    private var pendingTargetHi: Boolean? = null

    @Volatile
    private var pendingBaseFrame = 0

    @Volatile
    private var pendingSince = 0L

    /**
     * 叠加卡片（输出画面坐标，顶部原点）。由调用方在后台线程生成位图后设置；
     * GL 线程负责上传纹理并绘制在最上层。触控命中用同一坐标，见 [OverlayCard.contains]。
     */
    @Volatile
    var overlayCard: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
        }

    @Volatile
    private var overlayDirty = false

    /** 已上传纹理对应的卡片（仅 GL 线程访问）。 */
    private var uploadedOverlay: OverlayCard? = null

    /** 叠加卡片纹理（0 = 尚未创建，仅 GL 线程访问）。 */
    private var overlayTexture = 0

    /** 超采样输入（GL 线程创建；start() 返回后对其他线程安全可读）。 */
    @Volatile
    private var appHi: Input? = null

    /** 原生应用屏输入（同上；requestActiveApp 读其帧号作切换判据）。 */
    @Volatile
    private var appInput: Input? = null

    /** 地图屏输入（GL 线程创建）。 */
    @Volatile
    private var mapInput: Input? = null

    /**
     * 固定应用屏输入（仪表盘右侧卡片）：**恒创建**（音乐卡片模式也建）——设置面板
     * 会话中切换「音乐 ⇄ 指定应用」时要用它的 Surface 现场建虚拟屏，届时 GL 线程
     * 已进入合成循环、无法再走 runGl 的建输入路径。
     */
    @Volatile
    private var pinnedInput: Input? = null

    /** 右侧卡片是否画固定应用屏（VirtualLauncher 按模式切换；false = 音乐卡 View 区域）。 */
    @Volatile
    var pinnedCardVisible: Boolean = false

    /** 固定应用屏 Surface（会话中建屏用；GL 未就绪/未创建时 null）。 */
    fun pinnedSurface(): Surface? = pinnedInput?.surface

    /** 固定应用屏动态 resize 后同步消费端 buffer 尺寸（同 [resizeMapInput] 铁律）。 */
    fun resizePinnedInput(
        width: Int,
        height: Int,
    ) {
        pinnedInput?.surfaceTexture?.setDefaultBufferSize(width, height)
    }

    @Volatile
    var onSurfacesReady: ((dock: Surface, app: Surface, appHi: Surface?, map: Surface?, pinned: Surface?) -> Unit)? = null

    @Throws(IllegalStateException::class)
    fun start(
        dockWidthPx: Int,
        dockHeightPx: Int,
        appWidthPx: Int,
        appHeightPx: Int,
        appHiWidthPx: Int = 0,
        appHiHeightPx: Int = 0,
        mapWidthPx: Int = 0,
        mapHeightPx: Int = 0,
        pinnedWidthPx: Int = 0,
        pinnedHeightPx: Int = 0,
    ) {
        if (running.getAndSet(true)) return
        hasHiInput = appHiWidthPx > 0 && appHiHeightPx > 0
        hasMapInput = mapWidthPx > 0 && mapHeightPx > 0
        val ready = CountDownLatch(1)
        startLatch = ready
        var failure: IllegalStateException? = null
        glThread =
            Thread({
                try {
                    runGl(
                        dockWidthPx,
                        dockHeightPx,
                        appWidthPx,
                        appHeightPx,
                        appHiWidthPx,
                        appHiHeightPx,
                        mapWidthPx,
                        mapHeightPx,
                        pinnedWidthPx,
                        pinnedHeightPx,
                    )
                } catch (error: IllegalStateException) {
                    failure = error
                } finally {
                    ready.countDown()
                }
            }, "dual-composer").apply {
                isDaemon = true
                start()
            }
        ready.await()
        failure?.let {
            running.set(false)
            throw it
        }
    }

    /**
     * 切换应用区显示：false=原生屏，true=超采样屏。目标屏出帧后生效（期间继续显示原
     * 画面）：等待「目标屏帧号前进」（新应用首帧）；600ms 兜底（目标屏有历史内容时
     * 平滑切换）；2s 超时兜底（目标屏迟迟不出帧也要把画面切过去）。
     * 无超采样屏（未创建）时恒为原生屏。
     */
    fun requestActiveApp(hi: Boolean) {
        val target = if (!hasHiInput) false else hi
        if (activeAppHi == target && pendingTargetHi == null) return
        val targetInput = if (target) appHi else appInput
        pendingBaseFrame = targetInput?.frameCount ?: 0
        pendingSince = android.os.SystemClock.uptimeMillis()
        pendingTargetHi = target
        if (!hasHiInput) {
            // 无超采样屏：无需等待，直接生效
            activeAppHi = false
            pendingTargetHi = null
        }
    }

    /** 应用区当前活动屏（触摸路由/坐标换算以此为准）。 */
    fun isActiveAppHi(): Boolean = activeAppHi

    /**
     * 地图屏（tile）布局状态：HIDDEN=普通桌面；SPLIT=采样画面中央带区映射到
     * 应用区左半（地图中心≈车辆位置，裁剪语义正确）；FULLSCREEN=整个应用区；
     * PINNED_FULLSCREEN=整个应用区被固定应用屏占据（右侧卡片模式的应用全屏，
     * 此时地图 tile 不绘制）。地图屏始终以全屏尺寸创建（应用窗口稳定，任务不迁移），
     * 分/全屏只是采样几何。
     */
    enum class TileLayout { HIDDEN, SPLIT, FULLSCREEN, PINNED_FULLSCREEN }

    @Volatile
    private var tileLayout = TileLayout.HIDDEN

    @Volatile
    private var lastPortraitTileDiag = 0L

    private fun launcherDashHint(): Boolean =
        try {
            VirtualLauncher.dashboardMode
        } catch (_: Throwable) {
            false
        }

    /** 切换地图 tile 布局。 */
    fun setTileLayout(layout: TileLayout) {
        tileLayout = layout
    }

    /**
     * 地图屏动态 resize 后同步消费端 buffer 尺寸。VirtualDisplay.resize 只改系统侧
     * 显示设备——SurfaceTexture 的 defaultBufferSize 仍锁在创建时的旧尺寸，投影会把
     * 新尺寸内容画进旧 buffer 的左上区域（tile 内容偏居左、宽度只剩比例值、右侧全黑）。
     */
    fun resizeMapInput(
        width: Int,
        height: Int,
    ) {
        mapInput?.surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        glThread?.interrupt()
        glThread = null
    }

    private class Input(
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        @Volatile
        var hasNewFrame = false

        /** 是否已产出过至少一帧（未产出的输入不参与绘制，避免空纹理） */
        @Volatile
        var hasContent = false

        /** 累计产出帧数（活动屏切换的「已出帧」判据）。 */
        @Volatile
        var frameCount = 0
    }

    private fun runGl(
        dockW: Int,
        dockH: Int,
        appW: Int,
        appH: Int,
        hiW: Int,
        hiH: Int,
        mapW: Int,
        mapH: Int,
        pinnedW: Int,
        pinnedH: Int,
    ) {
        // ---- EGL ----
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1), "eglInitialize")

        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0,
            "eglChooseConfig",
        )
        val context: EGLContext =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
        val outputSurface: EGLSurface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                output,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
        checkEgl(outputSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        checkEgl(
            EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context),
            "eglMakeCurrent",
        )

        // ---- 输入纹理 ----
        fun newInput(
            width: Int,
            height: Int,
        ): Input {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(width, height)
            val input = Input(tex[0], st, Surface(st))
            st.setOnFrameAvailableListener { input.hasNewFrame = true }
            return input
        }

        val dock = newInput(dockW, dockH)
        val app = newInput(appW, appH).also { appInput = it }
        val hi = if (hasHiInput) newInput(hiW, hiH).also { appHi = it } else null
        val map = if (hasMapInput) newInput(mapW, mapH).also { mapInput = it } else null
        // 固定应用屏输入恒创建（buffer 尺寸=卡片矩形×密度，音乐模式无帧不绘制）：
        // Surface 常在，设置面板会话中切换模式才能现场建虚拟屏（见 pinnedInput 注释）
        val pinned = newInput(pinnedW, pinnedH).also { pinnedInput = it }

        // ---- 着色器 ----
        val program = buildProgram(oes = true)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uClipEnabled = GLES20.glGetUniformLocation(program, "uClipEnabled")
        val uClipRect = GLES20.glGetUniformLocation(program, "uClipRect")
        val uClipRadius = GLES20.glGetUniformLocation(program, "uClipRadius")
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val matrix = FloatArray(16)

        // ---- 叠加卡片着色器（普通 2D 纹理；Bitmap 由 Canvas 预排版）----
        val overlayProgram = buildProgram(oes = false)
        val oaPos = GLES20.glGetAttribLocation(overlayProgram, "aPos")
        val oaTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        val ouSTMatrix = GLES20.glGetUniformLocation(overlayProgram, "uSTMatrix")
        val ouTexture = GLES20.glGetUniformLocation(overlayProgram, "uTexture")

        /** 上传/更新叠加卡片纹理（仅 GL 线程；卡片引用变化时才重新上传）。 */
        fun syncOverlayTexture(): OverlayCard? {
            val card =
                overlayCard ?: run {
                    uploadedOverlay = null
                    return null
                }
            if (card === uploadedOverlay && overlayTexture != 0) return card
            if (overlayTexture == 0) {
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                overlayTexture = tex[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, card.bitmap, 0)
            uploadedOverlay = card
            return card
        }

        /**
         * 绘制叠加卡片。[left]/[top]/[right]/[bottom] 是输出画面坐标（顶部原点），
         * 这里转成 GL 底部原点；Bitmap 首行在顶部，故纹理 v 轴与顶点方向相反。
         */
        fun drawOverlay(
            card: OverlayCard,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ) {
            GLES20.glUseProgram(overlayProgram)
            GLES20.glUniform1i(ouTexture, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)

            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val yTop = outHeight - top
            val yBottom = outHeight - bottom
            val positions =
                floatBuffer(
                    px(left),
                    py(yBottom),
                    px(right),
                    py(yBottom),
                    px(left),
                    py(yTop),
                    px(right),
                    py(yTop),
                )
            // Bitmap v=0 在顶部：底部顶点取 v=1，顶部顶点取 v=0
            val coords = floatBuffer(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(oaPos)
            GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(oaTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_BLEND)
            // 恢复主着色器状态（后续帧继续用 OES 程序）
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexture, 0)
        }

        /** [u0]/[u1]：横向纹理采样区间（地图分屏时采样缓冲中央带区用）。 */
        fun drawInputUV(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            u0: Float = 0f,
            u1: Float = 1f,
            cornerRadius: Float = 0f,
        ) {
            input.surfaceTexture.getTransformMatrix(matrix)
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
            // 圆角裁剪：shader 用 gl_FragCoord（GL 底部原点）与 uClipRect 比较，
            // 故直接传顶点坐标（本函数 y 即 GL 坐标）。此前翻转成屏幕坐标——
            // 全高 tile（横屏）对称区间恰好重合未暴露；竖屏半高 tile 顶点区域
            // 与裁剪区域完全不相交，整块被 discard（「地图卡不可见」根因）
            if (cornerRadius > 0f) {
                GLES20.glUniform1i(uClipEnabled, 1)
                GLES20.glUniform4f(uClipRect, x0, y0, x1, y1)
                GLES20.glUniform1f(uClipRadius, cornerRadius)
            } else {
                GLES20.glUniform1i(uClipEnabled, 0)
            }

            // 像素坐标 → 裁剪坐标（GL 左下原点；纹理翻转由 uSTMatrix 处理，勿重复翻转）
            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val positions =
                floatBuffer(
                    px(x0),
                    py(y0),
                    px(x1),
                    py(y0),
                    px(x0),
                    py(y1),
                    px(x1),
                    py(y1),
                )
            val coords = floatBuffer(u0, 0f, u1, 0f, u0, 1f, u1, 1f)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun drawInput(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
        ) = drawInputUV(input, x0, x1, y0, y1, u0 = 0f, u1 = 1f)

        fun composeFrame(fullRedraw: Boolean) {
            // 待切应用屏：目标屏出帧即切（帧号前进 / 已有内容 600ms 兜底 / 2s 超时兜底）
            pendingTargetHi?.let { targetHi ->
                val nowMs = android.os.SystemClock.uptimeMillis()
                val target = if (targetHi) hi else app
                val advanced = target != null && target.frameCount > pendingBaseFrame
                if (advanced ||
                    (target?.hasContent == true && nowMs - pendingSince >= 600L) ||
                    nowMs - pendingSince >= 2_000L
                ) {
                    activeAppHi = targetHi
                    pendingTargetHi = null
                }
            }
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // 只对有新帧的输入 updateTexImage；已有内容的输入沿用旧纹理（画面持久）。
            // 两块应用屏都持续消费（非活动屏也保持最新，切换时不闪旧帧）。
            if (dock.hasNewFrame) {
                dock.hasNewFrame = false
                dock.hasContent = true
                dock.frameCount++
                dock.surfaceTexture.updateTexImage()
            }
            if (app.hasNewFrame) {
                app.hasNewFrame = false
                app.hasContent = true
                app.frameCount++
                app.surfaceTexture.updateTexImage()
            }
            hi?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            map?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            pinnedInput?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            val activeInput = if (activeAppHi) hi else app
            if (dockAtBottom) {
                // 竖屏：dock 横条贴底（GL 坐标 y=0 为底边），app 在其余区域；
                // 仪表盘时地图卡叠在应用区上半（地图上/音乐下），全屏盖满应用区。
                if (dock.hasContent) drawInput(dock, 0f, outWidth.toFloat(), 0f, dockH.toFloat())
                if (activeInput?.hasContent == true) {
                    drawInput(activeInput, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                }
                val layoutP = tileLayout
                if (map?.hasContent == true && layoutP != TileLayout.HIDDEN && layoutP != TileLayout.PINNED_FULLSCREEN) {
                    if (layoutP == TileLayout.FULLSCREEN) {
                        drawInput(map!!, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                    } else {
                        // 上半卡片：左右各留 16、顶留 16；pager 水平滑动 → tile 向左滑出（跟手）
                        // tileEdgeOverride = tile 把手侧边缘（顶部原点 y，即 tile 下缘）；
                        // GL y = outHeight − 顶部 y（底部原点换算，勿翻转——坐标系约定见类注释）
                        val tileH = (outHeight - dockH) / 2f - CARD_MARGIN_PX * 1.5f
                        val handleEdge =
                            if (tileEdgeOverride > 0f) tileEdgeOverride else CARD_MARGIN_PX + tileH
                        val slide = tileSlideFraction * outWidth
                        val nowMs = android.os.SystemClock.uptimeMillis()
                        if (nowMs - lastPortraitTileDiag > 5_000) {
                            lastPortraitTileDiag = nowMs
                            VirtualLauncher.logSink?.invoke(
                                "COMPOSER: 竖屏 tile 绘制 tileH=$tileH handleEdge=$handleEdge " +
                                    "slide=$slide frames=${map!!.frameCount}",
                            )
                        }
                        drawInputUV(
                            map!!,
                            CARD_MARGIN_PX - slide,
                            outWidth - CARD_MARGIN_PX - slide,
                            outHeight - handleEdge,
                            (outHeight - CARD_MARGIN_PX).toFloat(),
                            cornerRadius = TILE_CORNER_RADIUS,
                        )
                    }
                } else {
                    // 竖屏 tile 未绘制：状态节流采样（5s），排查「看不到地图卡」
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    if (nowMs - lastPortraitTileDiag > 5_000) {
                        lastPortraitTileDiag = nowMs
                        VirtualLauncher.logSink?.invoke(
                            "COMPOSER: 竖屏 tile 未画 layout=$layoutP " +
                                "mapInput=${map != null} hasContent=${map?.hasContent} " +
                                "frames=${map?.frameCount} dash=${launcherDashHint()}",
                        )
                    }
                }
                // 固定应用屏（右侧卡片）：PINNED_FULLSCREEN=盖满应用区（地图不画）；
                // SPLIT=画在地图卡下方（= 音乐卡 View 的区域），同 slide 跟手滑动。
                if (pinnedInput?.hasContent == true && layoutP != TileLayout.HIDDEN) {
                    if (layoutP == TileLayout.PINNED_FULLSCREEN) {
                        drawInput(pinnedInput!!, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                    } else if (pinnedCardVisible) {
                        val tileH = (outHeight - dockH) / 2f - CARD_MARGIN_PX * 1.5f
                        val handleEdge =
                            if (tileEdgeOverride > 0f) tileEdgeOverride else CARD_MARGIN_PX + tileH
                        val slide = tileSlideFraction * outWidth
                        val cardTop = handleEdge + DASH_GAP_PX
                        val cardBottom = (outHeight - dockH - CARD_MARGIN_PX).toFloat()
                        drawInputUV(
                            pinnedInput!!,
                            CARD_MARGIN_PX - slide,
                            outWidth - CARD_MARGIN_PX - slide,
                            outHeight - cardBottom,
                            outHeight - cardTop,
                            cornerRadius = TILE_CORNER_RADIUS,
                        )
                    }
                }
            } else {
                if (activeInput?.hasContent == true) {
                    drawInput(activeInput, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                }
                // 地图层叠在应用区之上（HIDDEN 不画）：
                //  FULLSCREEN=整个应用区；SPLIT=左半卡片区域。
                //  地图屏经 VirtualLauncher 按布局动态 resize（分屏=tile 显示尺寸、
                //  全屏=应用区尺寸），屏上地图应用 relayout——两种布局都是整幅
                //  1:1 映射（旧的「全尺寸屏+中央带区裁剪采样」方案已移除）。
                val layout = tileLayout
                if (map?.hasContent == true && layout != TileLayout.HIDDEN && layout != TileLayout.PINNED_FULLSCREEN) {
                    if (layout == TileLayout.FULLSCREEN) {
                        drawInput(map!!, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                    } else {
                        // 卡片形态：四周留边 + 圆角（与音乐卡一致）。
                        // 分屏把手拖动期间 tileEdgeOverride（tile 右缘 x）替代公式值：
                        // 只改绘制矩形、纹理整幅拉伸（不动 buffer/虚拟屏），松手才 resize。
                        // tileSlideFraction：随 pager 滑动把 tile 平移出/入左侧（视觉跟手）。
                        val half = (outWidth - dockW) / 2f
                        val tileW = half - CARD_MARGIN_PX - CARD_MARGIN_PX / 2f
                        val x1 =
                            if (tileEdgeOverride > 0f) {
                                tileEdgeOverride
                            } else {
                                dockW + CARD_MARGIN_PX + tileW
                            }
                        val slide = tileSlideFraction * (outWidth - dockW)
                        drawInputUV(
                            map!!,
                            dockW + CARD_MARGIN_PX - slide,
                            x1 - slide,
                            CARD_MARGIN_PX.toFloat(),
                            (outHeight - CARD_MARGIN_PX).toFloat(),
                            cornerRadius = TILE_CORNER_RADIUS,
                        )
                    }
                }
                // 固定应用屏（右侧卡片）：PINNED_FULLSCREEN=盖满应用区（地图不画）；
                // SPLIT=把手右侧到应用区右缘（= 音乐卡 View 的区域），同 slide 跟手、
                // 拖动中随 tileEdgeOverride 整幅拉伸（松手才 resize，与地图 tile 一致）。
                if (pinnedInput?.hasContent == true && layout != TileLayout.HIDDEN) {
                    if (layout == TileLayout.PINNED_FULLSCREEN) {
                        drawInput(pinnedInput!!, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                    } else if (layout == TileLayout.SPLIT && pinnedCardVisible) {
                        val half = (outWidth - dockW) / 2f
                        val tileW = half - CARD_MARGIN_PX - CARD_MARGIN_PX / 2f
                        val tileEdge =
                            if (tileEdgeOverride > 0f) tileEdgeOverride else dockW + CARD_MARGIN_PX + tileW
                        val slide = tileSlideFraction * (outWidth - dockW)
                        drawInputUV(
                            pinnedInput!!,
                            tileEdge + DASH_GAP_PX - slide,
                            outWidth - CARD_MARGIN_PX - slide,
                            CARD_MARGIN_PX.toFloat(),
                            (outHeight - CARD_MARGIN_PX).toFloat(),
                            cornerRadius = TILE_CORNER_RADIUS,
                        )
                    }
                }
                // dock 最后绘制（最上层）：tile 滑动经过 dock 区域时从其下方穿过
                if (dock.hasContent) drawInput(dock, 0f, dockW.toFloat(), 0f, outHeight.toFloat())
            }
            // 叠加卡片最后绘制（最上层，不受分支影响）：卡片坐标为输出画面坐标（顶部原点）
            syncOverlayTexture()?.let { card ->
                drawOverlay(card, card.left, card.top, card.right, card.bottom)
            }
            if (fullRedraw ||
                overlayDirty ||
                dock.hasContent ||
                app.hasContent ||
                hi?.hasContent == true ||
                map?.hasContent == true ||
                pinnedInput?.hasContent == true
            ) {
                EGL14.eglSwapBuffers(display, outputSurface)
            }
            overlayDirty = false
        }

        // 初始黑帧（只清屏，不碰空纹理）+ 交付输入 Surface + 放行 start()
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, outputSurface)
        onSurfacesReady?.invoke(dock.surface, app.surface, hi?.surface, map?.surface, pinned.surface)
        startLatch?.countDown()

        // ---- 合成循环 ----
        // 有输入新帧：按目标帧率（1000/targetFps）节流合成。
        // 无新帧（界面静止，如启动器/阅读页/地图停靠）：以 33ms 心跳重复合成最后一帧内容——
        // 否则编码器无输入、视频流停摆，接收端画面冻结（表现为"桌面卡死/不可交互"，
        // 2026-10-09 实测踩坑：触摸其实送到了，只是没有帧能反映出来）。
        try {
            var lastComposeAt = System.currentTimeMillis()
            while (running.get()) {
                // 快车道（60fps 节流）只由「会被画进输出画面」的输入驱动：
                // dock + 当前活动应用屏（app/hi 二选一）+ tile 可见时的地图屏。
                // 高德走地图屏、竖屏应用走超采样屏——只看 dock/原生屏会漏掉它们，
                // 60fps 档被 33ms 静止心跳锁死在 30fps。
                // 非活动屏的新帧不在判定内（画面不含它）：帧仍会在 composeFrame 里
                // 持续消费（纹理保持最新，避免 SurfaceTexture 队列积压卡住后台应用），
                // 但不为看不见的内容白烧 GPU/带宽。
                val hasNew =
                    overlayDirty ||
                        dock.hasNewFrame ||
                        (if (activeAppHi) appHi else app)?.hasNewFrame == true ||
                        (tileLayout != TileLayout.HIDDEN && mapInput?.hasNewFrame == true) ||
                        // 固定应用屏：卡片可见（仪表盘 SPLIT）或全屏时才驱动快车道
                        (
                            pinnedCardVisible &&
                                tileLayout != TileLayout.HIDDEN &&
                                pinnedInput?.hasNewFrame == true
                        )
                val now = System.currentTimeMillis()
                val intervalMs = if (hasNew) 1000L / targetFps.coerceIn(1, 120) else 33L
                if (now - lastComposeAt >= intervalMs) {
                    composeFrame(fullRedraw = false)
                    lastComposeAt = now
                } else {
                    Thread.sleep(8)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            if (overlayTexture != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(overlayTexture), 0) }
                overlayTexture = 0
            }
            runCatching { dock.surface.release() }
            runCatching { app.surface.release() }
            runCatching { dock.surfaceTexture.release() }
            runCatching { app.surfaceTexture.release() }
            map?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            pinnedInput?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
                pinnedInput = null
            }
            hi?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            runCatching { EGL14.eglDestroySurface(display, outputSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    /** [oes]=true：外部纹理采样（SurfaceTexture）；false：普通 2D 采样（壁纸位图）。 */
    private fun buildProgram(oes: Boolean): Int {
        val fragment =
            if (oes) {
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                uniform int uClipEnabled;
                uniform vec4 uClipRect;
                uniform float uClipRadius;
                void main() {
                    if (uClipEnabled == 1) {
                        vec2 p = gl_FragCoord.xy;
                        vec2 half2 = vec2(uClipRadius);
                        vec2 c = clamp(p, uClipRect.xy + half2, uClipRect.zw - half2);
                        if (distance(p, c) > uClipRadius) discard;
                    }
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """.trimIndent()
            } else {
                """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """.trimIndent()
            }
        val vertex =
            """
            attribute vec2 aPos;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
            """.trimIndent()

        fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            // 编译失败必须留痕并抛出：静默继续 = 黑屏无从排查（EGL 有 checkEgl，GLSL 这层此前漏了）
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "GLSL 编译失败（type=$type）：${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GLSL compile type=$type")
            }
            return shader
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        return program
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun checkEgl(
        ok: Boolean,
        stage: String,
    ) {
        if (!ok) {
            Log.e(TAG, "EGL 失败：$stage")
            throw IllegalStateException("EGL $stage")
        }
    }
}
