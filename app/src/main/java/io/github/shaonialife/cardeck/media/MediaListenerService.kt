package io.github.shaonialife.cardeck.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import io.github.shaonialife.cardeck.launcher.VirtualLauncher

/**
 * 通知监听服务：借通知使用权枚举系统活跃媒体会话，向应用提供「正在播放」状态
 * （歌名/歌手/播放中）。音乐卡片（仪表盘）据此渲染与控制。
 *
 * **为什么必须有通知使用权**：`MediaSessionManager.getActiveSessions()` 在未授权时
 * 仍会返回会话（所以播放状态拿得到），但 `metadata` 恒为 null——表现为「播放状态在变、
 * 歌名歌手却始终为空」。这个组合最容易误判成解析 bug，实际是权限没给（2026-10-18 实测）。
 *
 * 授权路径：系统设置「通知使用权」，或 Shizuku（shell）执行
 * `cmd notification allow_listener <本组件>`（见 [NotificationAccess]）。
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        fun componentName(context: android.content.Context): ComponentName = ComponentName(context, MediaListenerService::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 已连上即代表系统认可本应用的通知使用权（此时 metadata 才会可读）。
        // 两个状态都置位：NotificationAccess 供「是否要再试授权」判断，
        // MediaSessionsState 供 UI 文案区分「没在播放」与「读不到信息」。
        NotificationAccess.markGranted()
        NotificationAccess.markConnected()
        MediaSessionsState.accessGranted = true
        try {
            manager.addOnActiveSessionsChangedListener(
                sessionListener,
                componentName(this),
            )
            MediaSessionsState.resyncHook = { mainHandler.post { refresh() } }
            update(manager.getActiveSessions(componentName(this)))
            mainHandler.post(periodicRefresh)
        } catch (_: SecurityException) {
            // 通知使用权被撤销：静默（授权后系统会重新绑定本服务）
            MediaSessionsState.accessGranted = false
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaSessionsState.accessGranted = false
        NotificationAccess.markDisconnected()
        // 必须清空：否则卡片会保留断开前的歌名/播放状态，出现「正在播放 + 未在播放」
        // 甚至「正在播放 + 无法读取播放信息」的自相矛盾显示（2026-10-19 实测：
        // 重装后监听器未被系统绑定，卡片长时间停在旧状态）。
        MediaSessionsState.publish(null)
        watched.clear()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
        MediaSessionsState.publish(null)
        super.onDestroy()
    }

    private val manager by lazy {
        getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            update(controllers ?: emptyList())
        }

    /** 已注册播放回调的会话（避免重复注册）。 */
    private val watched = HashSet<android.media.session.MediaSession.Token>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 兜底轮询：部分播放器不触发回调/回调丢失时保证卡片信息更新。
     * 自适应间隔（2026-11-10 排查耗电）：会话活跃（车机媒体卡需要秒级进度）1s；
     * 无会话 4s（只服务手机侧首页卡片，getActiveSessions 是 binder 调用，
     * 服务连接期间永续轮询、与是否连接无关——按需降频省电）。
     */
    private val periodicRefresh =
        object : Runnable {
            override fun run() {
                refresh()
                val interval =
                    if (io.github.shaonialife.cardeck.SessionController.videoSender
                            ?.isRunning == true
                    ) {
                        1_000L
                    } else {
                        4_000L
                    }
                mainHandler.postDelayed(this, interval)
            }
        }

    /** 上次封面提取键（会话+歌名+封面 uri）——getBitmap 每次反序列化，1s 轮询不能重复做。 */
    private var lastArtKey: String? = null

    /** 封面位图缓存（键不变时复用同一实例，保证 NowPlaying 的 equals 判稳）。 */
    private var cachedArt: android.graphics.Bitmap? = null

    /** 上次「键匹配但封面为空」的时刻：切歌瞬间 metadata 常不完整（歌名先到、
     *  封面字段滞后），空结果只保持 2 秒，之后重试直到封面就绪。 */
    private var artEmptyAt = 0L

    /** 封面 uri 解码专用单线程（串行；主线程不能做 IO/解码）。 */
    private val artExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "media-art").apply { isDaemon = true }
        }

    private fun update(controllers: List<android.media.session.MediaController>) {
        controllers.forEach { controller ->
            if (watched.add(controller.sessionToken)) {
                controller.registerCallback(
                    object : android.media.session.MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            refresh()
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            refresh()
                        }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
        }
        refresh()
    }

    /** 从活跃会话提取正在播放态：优先 PLAYING 的会话，否则保留第一个有元数据的。 */
    private fun refresh() {
        val controllers =
            try {
                manager.getActiveSessions(componentName(this))
            } catch (_: SecurityException) {
                emptyList()
            }
        val picked =
            controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull { it.metadata != null }
        val now =
            picked?.let { controller ->
                val meta = controller.metadata
                val state = controller.playbackState
                val playing = state?.state == PlaybackState.STATE_PLAYING
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                MediaSessionsState.NowPlaying(
                    title = title,
                    artist =
                        meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    playing = playing,
                    art = artFor(controller, meta, title),
                    pkg = controller.packageName,
                    positionMs = state?.position ?: 0L,
                    playbackSpeed = if (playing) (state?.playbackSpeed ?: 1f) else 0f,
                    durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    anchoredAtMs = android.os.SystemClock.elapsedRealtime(),
                )
            }
        MediaSessionsState.publish(now)
    }

    /**
     * 封面提取（带缓存）：仅「会话+歌名+封面 uri」变化时重新提取——位图键直接从
     * metadata 取；uri 型封面（content:// 等）经后台线程解码，完成前先给无封面态。
     */
    private fun artFor(
        controller: android.media.session.MediaController,
        meta: MediaMetadata?,
        title: String?,
    ): android.graphics.Bitmap? {
        val artUri =
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = "${controller.sessionToken}/$title/$artUri"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastArtKey) {
            if (cachedArt != null) return cachedArt
            // 键没变但封面为空：2 秒内不重试（getBitmap 有成本），超后重提取——
            // app 在切歌后补封面（bitmap 键从无到有）靠这条路兜住
            if (now - artEmptyAt < 2_000) return null
        }
        lastArtKey = key
        cachedArt = null
        val direct =
            meta
                ?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            cachedArt = scaleArt(direct)
            return cachedArt
        }
        artEmptyAt = now
        if (artUri != null) {
            // 解码在专用线程；就绪后不主动 refresh——periodicRefresh 下一轮（≤1s）拉取
            artExecutor.execute {
                val bmp =
                    runCatching {
                        val input =
                            if (artUri.startsWith("http")) {
                                java.net.URL(artUri).openStream()
                            } else {
                                contentResolver.openInputStream(android.net.Uri.parse(artUri))
                            }
                        input?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                if (bmp != null && key == lastArtKey) {
                    cachedArt = scaleArt(bmp)
                }
            }
        }
        return null
    }

    /** 封面缩到 ≤512px（卡片显示 ~120dp，控制内存）。 */
    private fun scaleArt(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= 512) return src
        val sample =
            generateSequence(1) { it * 2 }.first { maxSide / it <= 512 }
        return android.graphics.Bitmap
            .createScaledBitmap(src, src.width / sample, src.height / sample, true)
    }
}

/** 全局「正在播放」状态（本进程内共享；GridActivity 音乐卡监听渲染）。 */
object MediaSessionsState {
    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val playing: Boolean,
        val art: android.graphics.Bitmap? = null,
        /** 媒体会话所属应用（点击封面拉回正在播放的应用）。 */
        val pkg: String? = null,
        /** 采样时刻的播放位置（ms）。 */
        val positionMs: Long = 0L,
        /** 播放速度（暂停时 0——不外推）。 */
        val playbackSpeed: Float = 1f,
        val durationMs: Long = 0L,
        /** 位置采样的 SystemClock.elapsedRealtime 锚点。 */
        val anchoredAtMs: Long = 0L,
    ) {
        /** 渲染时刻的推算播放位置（播放中按速度外推，钳制在时长内）。 */
        fun displayPositionAt(elapsedRealtimeMs: Long): Long {
            if (durationMs <= 0) return 0
            val base =
                if (playing && anchoredAtMs > 0) {
                    positionMs + ((elapsedRealtimeMs - anchoredAtMs) * playbackSpeed).toLong()
                } else {
                    positionMs
                }
            return base.coerceIn(0, durationMs)
        }
    }

    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    /**
     * 通知使用权是否已授予。
     *
     * **未授权时 `metadata` 恒为 null**（会话本身仍可见、播放状态照常变化），
     * 卡片必须据此区分「真的没在播放」与「有播放但读不到信息」，
     * 否则文案会误导成「媒体信息未连接」。
     */
    @Volatile
    var accessGranted: Boolean = false

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun publish(value: NowPlaying?) {
        val old = nowPlaying
        nowPlaying = value
        // 位置每秒都在变（1s 兜底轮询）——日志只看内容键，避免刷屏；
        // listeners 仍每次通知（UI 据此刷新进度文本）
        val contentChanged =
            old?.title != value?.title ||
                old?.artist != value?.artist ||
                old?.playing != value?.playing ||
                old?.art !== value?.art
        if (contentChanged) {
            VirtualLauncher.logSink?.invoke(
                "MEDIA: " + (
                    value?.let { "${it.title ?: "?"} - ${it.artist ?: "?"} playing=${it.playing}" }
                        ?: "无活跃会话"
                ),
            )
        }
        // 内容未变（仅位置锚点随轮询更新）不通知：进度走秒由 UI 侧 mediaTick 每秒自刷新
        // ——此前 anchoredAtMs 恒变导致每秒全量通知监听者，与 mediaTick 形成双路重复渲染
        if (!contentChanged) return
        listeners.forEach { runCatching { it() } }
    }

    /** 重同步钩子（服务连接时安装）：UI 乐观更新后调用以拉取真实状态。 */
    @Volatile
    var resyncHook: (() -> Unit)? = null

    fun requestResync() {
        resyncHook?.invoke()
    }
}
