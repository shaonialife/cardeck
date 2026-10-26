package io.github.shaonialife.cardeck.bt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 蓝牙「**只配对、不连接**」策略。
 *
 * 奥迪 MIB3 实测（2026-10-18，用户报告）：手机与车机处于蓝牙**已连接**状态
 * （HFP 通话 / A2DP 媒体配置文件在线）时，车机不开放无线 CarPlay 的 iAP2 服务
 * 窗口——表现为 `BT-C: 连接失败（车机未就绪或不在无线配对窗口）` 每 3 秒重试。
 * 真 iPhone 的无线 CarPlay 也不依赖这些配置文件：控制通道走 iAP2 RFCOMM（独立
 * SDP 服务），声音走 WiFi 上的 AirPlay。因此正确形态是：**系统级配对 + iAP2
 * RFCOMM，其余配置文件一律不连**。
 *
 * 实现说明：`BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)` 是
 * **@SystemApi**——编译期不可见，但运行时注解只要求 `BLUETOOTH_CONNECT`
 * （本应用已持有），因此反射可调。查询状态用公开的 `getProfileProxy` +
 * `connectedDevices`（需要主 Looper，应用进程有）。
 *
 * 曾试过把这段挪到 Shizuku UserService（shell uid）执行，实测不可行：
 * UserService 里 `BluetoothManager.getAdapter()` 返回 null（无主 Looper 的应用
 * 上下文），查询恒为「未连接」，反而掩盖真实状态。故固定在应用进程内做。
 */
object BtProfiles {
    private const val TAG = "BT-PROFILE"

    /** 关心的配置文件（按断开优先级排列）。 */
    private val profiles: List<Int> =
        listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.LE_AUDIO,
        )

    private fun profileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.HEADSET -> "通话(HFP)"
            BluetoothProfile.A2DP -> "音频(A2DP)"
            BluetoothProfile.LE_AUDIO -> "低功耗音频(LE)"
            else -> "profile#$profile"
        }

    /** profile id → 已获取的代理（进程内复用；代理本身无会话状态）。 */
    private val proxies = HashMap<Int, BluetoothProfile>()

    /** 该地址当前在线（已连接）的配置文件名称。 */
    fun connectedNames(
        context: Context,
        address: String,
    ): List<String> =
        profiles.mapNotNull { id ->
            val p = proxy(context, id) ?: return@mapNotNull null
            val connected =
                try {
                    @Suppress("DEPRECATION")
                    p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                } catch (_: Throwable) {
                    false
                }
            if (connected) profileName(id) else null
        }

    /**
     * 当前经音频类配置文件（HFP/A2DP/LE_AUDIO）在线的全部设备。
     * 用途：会话中「采集全零但媒体在播」的音频路由抢占回收——断开后媒体输出
     * 回落本地，AudioPlaybackCapture 才采得到（2026-10-26 荣耀实测：耳机抢占
     * A2DP 后推给车机的是静音流）。**必须在后台线程调用**（proxy 阻塞等回调）。
     */
    fun connectedAudioDevices(context: Context): List<BluetoothDevice> =
        profiles
            .flatMap { id ->
                val p = proxy(context, id) ?: return@flatMap emptyList<BluetoothDevice>()
                try {
                    @Suppress("DEPRECATION")
                    p.connectedDevices
                } catch (_: Throwable) {
                    emptyList()
                }
            }.distinctBy { runCatching { it.address }.getOrNull() }

    /**
     * 尽力断开该设备的全部配置文件连接。
     *
     * @return 断开前在线的配置文件（空 = 本来就没连接，无需任何动作）
     */
    fun disconnect(
        context: Context,
        device: BluetoothDevice,
    ): List<String> {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return emptyList()
            }
        val before = connectedNames(context, address)
        if (before.isEmpty()) return emptyList()
        for (id in profiles) {
            if (profileName(id) !in before) continue
            proxy(context, id)?.let { disconnectProfile(it, device) }
        }
        if (!awaitDisconnected(context, address, 1_500L)) {
            // 配置级断开未生效（个别 ROM 限流）：设备级断开兜底（@hide，同样只要求
            // BLUETOOTH_CONNECT）。车载/耳机当前连接的设备可能被一并断开，属预期。
            runCatching { BluetoothDevice::class.java.getMethod("disconnect").invoke(device) }
            awaitDisconnected(context, address, 500L)
        }
        return before
    }

    /** 断开后仍在线的配置文件（用于日志判断是否真的断开）。 */
    fun stillConnected(
        context: Context,
        address: String,
    ): List<String> = connectedNames(context, address)

    private fun awaitDisconnected(
        context: Context,
        address: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (connectedNames(context, address).isEmpty()) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return connectedNames(context, address).isEmpty()
    }

    private fun proxy(
        context: Context,
        profile: Int,
    ): BluetoothProfile? {
        synchronized(proxies) { proxies[profile]?.let { return it } }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?: return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BluetoothProfile?>(null)
        val requested =
            try {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            id: Int,
                            service: BluetoothProfile,
                        ) {
                            ref.set(service)
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(id: Int) {
                            // 蓝牙服务重启后旧代理失效：清缓存，下次重新获取
                            synchronized(proxies) { proxies.remove(id) }
                            latch.countDown()
                        }
                    },
                    profile,
                )
            } catch (_: Throwable) {
                false
            }
        if (!requested) return null
        // `getProfileProxy` 的回调经**主线程 Handler** 投递：若本方法跑在主线程，
        // 回调永远无法执行 → 必然白等到超时（1.2s）并返回 null。宁可显式失败也不
        // 静默卡顿——调用方必须转到后台线程（本对象所有入口都是后台调用）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "proxy() 在主线程调用——getProfileProxy 回调会被阻塞，返回 null")
            return null
        }
        if (!latch.await(1_200, TimeUnit.MILLISECONDS)) return null
        return ref.get()?.also { synchronized(proxies) { proxies[profile] = it } }
    }

    /**
     * 断开某配置文件的连接。`disconnect(BluetoothDevice)` 是 @SystemApi（编译期
     * 不可见），反射调用；失败不抛，由调用方按最终状态判定。
     */
    private fun disconnectProfile(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): Boolean =
        try {
            proxy.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
                ?.let { it as? Boolean }
                ?: true
        } catch (_: Throwable) {
            false
        }
}
