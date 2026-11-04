package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 用车机热点承载 CarPlay 流量。
 *
 * **两种入网方式（2026-10-22 起默认静默命令行）**：
 *  - 静默路径（默认，见 `SessionController.joinCarWifiSilently`）：Shizuku 执行
 *    `cmd wifi connect-network`，系统直接把热点连上（**0 弹窗**），随后
 *    [bindCurrentWifi] 用**不带 specifier** 的 `requestNetwork` 捕获 `Network` 句柄并绑定。
 *  - [joinCarPlayNetwork]：`WifiNetworkSpecifier` 应用专属网络——静默路径不可用时的兜底。
 *    网络只对本进程可见（不保存、不成为默认网络），但首次加入会弹系统确认框。
 *
 * 关键点：
 *  - 请求不带 `NET_CAPABILITY_INTERNET`，系统不会因「无互联网」拆连接；
 *  - 两条路径都必须 `bindProcessToNetwork` 后再建 socket / 注册 mDNS——mDNS 广播
 *    (`NsdManager.registerService`) 没有 Network 参数，只能跟随进程默认网络；
 *  - SSID 必须精确匹配（热点名可能带空格/中文）：静默路径用 `cmd wifi status` 核对。
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    val currentNetwork: Network? get() = boundNetwork.get()
    private val boundNetwork = AtomicReference<Network?>(null)

    /**
     * 应用专属网络被系统断开（热点关闭/离开范围）时回调。会话层据此提示用户；
     * 空实现 = 不关心（例如手动绑定路径）。
     */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * 当前绑定网络的网关地址（车机热点场景 = 车机 IP）。
     * mDNS 发现失败时的兜底路径。
     */
    fun gatewayAddress(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 本机在当前绑定网络上的 IPv4 地址（如 10.174.189.57）。
     * 用于 mDNS 结果过滤：手机双归属（连车机热点 + 自己开热点共享给电脑）时，
     * mDNS 会跨网发现电脑的 AirPlay 接收器——非本网段地址一律不是车机。
     */
    fun localIpv4Address(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 本机在当前绑定网络上的全部 IPv6 地址。
     *
     * mDNS 结果的网段验收用：奥迪 4xxx 的 `_airplay._tcp` 常只发链路本地 IPv6，
     * 旧过滤只按 IPv4 前缀判定，会把车机本身也当成「非本网段」丢掉（2026-10-19）。
     */
    fun localIpv6Addresses(): List<String> {
        val network = boundNetwork.get() ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点——**静默路径不可用时的兜底**。
     * 首次加入会弹一次系统确认框；网络不保存、不成为默认网络。
     *
     * 阻塞直到连接成功/系统拒绝/超时（内部 200ms 分片，[isCancelled] 可即时退出）。
     * 成功后进程网络已绑定，调用方可直接注册 mDNS / 建 socket。
     *
     * 安全类型映射按 0x5703（xcertplay Iap2WirelessSecurity）：
     * 0=NONE 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 仅。
     * WEP 无对应 API（返回 [JoinResult.Unsupported]，调用方走旧路径）。
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
        /** 首窗无回调时的快速探测（毫秒级 shell 往返 + ≤3s 绑定，必须不阻塞重发）。 */
        quickFallback: (() -> Network?)? = null,
        /** 首窗零回调且未连着热点 = 系统确认弹框可能在等用户应答——保持请求并提示
         *  用户（**不 release 不重发**：重发会撤回展示中的弹框，2026-11-04 DiPlay
         *  连公司网场景实测弹框等待形态）。 */
        onPendingHint: (() -> Unit)? = null,
        /** 重发窗也无回调后的深度兜底（connect-network，10s 量级——本轮窗口已过，为下一轮省时间）。 */
        deepFallback: (() -> Network?)? = null,
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        // 等待节奏按「车机窗口」设计（2026-10-23 奥迪 3315 真机日志定谳）：0x4301 后
        // 车机 ~15s 拆 BT、~21s 后 AirPlay 服务窗关闭（晚到即 ECONNREFUSED，BT 再健康
        // 也无法激活）——从 0x5703 起算的入网预算只有 ~9-14s。 specifier 挂起形态下
        // （「系统已保存该 SSID + 部分 ROM（Android 16/小米实测）」或 forget→request
        // 紧邻操作，请求**永久 pending** 零回调）各阶段的预算：
        //  ① 首窗 5s（正常入网实测 0.03~3s；5s 零回调即挂起特征）；
        //  ② 快速探测（quickFallback：系统已自己连上该 SSID 则直接绑定，≤3s）；
        //  ③ 弹框应答窗 8s（2026-11-04 DiPlay 连公司网场景定谳）：零回调 ≠ 死挂——
        //     可能是**系统确认弹框在等用户应答**（未保存 SSID 首次必弹）。此时
        //     **保持请求不 release 不重发**（重发会撤回展示中的弹框），置提示让用户
        //     去点；用户应答 → onAvailable 直接成功。
        //  ④ 深度兜底（deepFallback：connect-network 直连，10s 量级）——本轮窗口必然
        //     已过，价值是让手机先挂上热点，下一轮（车机 0.17s 即重连 BT）走「已连着」近路。
        // 旧「release→重发 1.6s 即入网」的 workaround 是已保存 SSID 场景的形态；对
        // 弹框等待场景重发有害（撤框重出）。两类形态无法从零回调区分——先提示后兜底。
        // 旧 20s 首窗的教训：第 1 轮首窗没走完车机就拆链（15.2s），排在首窗**之后**的
        // 任何兜底都轮不到执行；第 2 轮 20s+1.6s=21.6s 入网时 7000 端口已拒绝连接。
        val firstWindowMs = minOf(timeoutMillis, 5_000L)
        val pendingWindowMs = minOf(timeoutMillis - firstWindowMs, 8_000L).coerceAtLeast(0)
        val unavailable = AtomicBoolean(false)
        var network = requestAndAwait(ssid, request, firstWindowMs, isCancelled, unavailable)
        // 系统明确拒绝（onUnavailable：用户拒了授权框/热点不在范围）后兜底/等待无意义，
        // 与挂起/弹框（零回调）必须区分开。
        if (network == null && !isCancelled() && !unavailable.get() && pendingWindowMs > 0) {
            // **此分支内不得 release**：撤请求 = 撤掉展示中的系统确认弹框。
            if (!isCancelled()) {
                // 快速兜底成功 = 已绑定到车机热点（系统自动重连的场景），直接返回。
                quickFallback?.invoke()?.let { fallback ->
                    log("WiFi: 快速兜底入网成功（$fallback）——跳过弹框应答窗")
                    return JoinResult.Joined(fallback)
                }
            }
            if (!isCancelled()) {
                // 弹框应答窗：同一请求保持活着，提示用户去点弹框。
                log("WiFi: 首窗 ${firstWindowMs / 1000}s 零回调——保持 specifier 请求，提示用户应答系统弹框（${pendingWindowMs / 1000}s）")
                onPendingHint?.invoke()
                network = awaitSameRequest(request, unavailable, pendingWindowMs, isCancelled)
            }
            if (network == null && !isCancelled() && !unavailable.get()) {
                release()
                if (!isCancelled()) {
                    deepFallback?.invoke()?.let { fallback ->
                        log("WiFi: 深度兜底入网成功（$fallback）")
                        return JoinResult.Joined(fallback)
                    }
                }
            }
        }
        if (network == null) {
            log("WiFi: 等待加入 \"$ssid\" 失败（首窗 ${firstWindowMs / 1000}s + 弹框应答窗 ${pendingWindowMs / 1000}s + 深度兜底）")
            release()
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** 发起一次 requestNetwork 并等待到窗口结束/成功/取消；调用方负责 release。 */
    private fun requestAndAwait(
        ssid: String,
        request: NetworkRequest,
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        unavailable: AtomicBoolean,
    ): Network? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    boundNetwork.set(network)
                    result.set(network)
                    log("WiFi: 已加入应用专属网络 \"$ssid\"（$network bindProcessToNetwork=$bound）——不影响其它应用联网")
                    latch.countDown()
                }

                override fun onUnavailable() {
                    log("WiFi: 系统未允许加入 \"$ssid\"（用户拒绝或热点不在范围内）")
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        connectivityManager.bindProcessToNetwork(null)
                        log("WiFi: 应用专属网络 \"$ssid\" 已断开（$network）")
                        onNetworkLost?.invoke(ssid)
                    }
                }
            }
        // 先撤上一任回调：快速兜底的 bindCurrentWifi 与本请求共用 callback 槽位，
        // 直接覆盖会让旧 callback 滞留在 ConnectivityManager 里直到 disconnect。
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        callback.set(newCallback)
        val accepted =
            try {
                connectivityManager.requestNetwork(request, newCallback)
                true
            } catch (error: Exception) {
                log("WiFi: 发起应用专属网络请求失败（${error.message}）——可能需要位置权限或系统不支持")
                false
            }
        if (!accepted) return null
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                return null
            }
        }
        return result.get()
    }

    /**
     * 弹框应答窗：**同一请求/callback 继续等待**（不 release 不重发——重发会撤回
     * 展示中的系统确认弹框）。等待期间每 200ms 分片检查取消。
     */
    private fun awaitSameRequest(
        request: NetworkRequest,
        unavailable: AtomicBoolean,
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
    ): Network? {
        // callback 已由 requestAndAwait 注册并活着；这里只延后判定时间。
        // result/latch 在 callback 闭包里——重新等待需要重新拿 latch/result，
        // 简化实现：轮询 boundNetwork（callback onAvailable 已写入）。
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            if (unavailable.get()) return null
            boundNetwork.get()?.let { return it }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    /** 构造 specifier；WEP 或参数不合法返回 null（走旧路径）。 */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // 开放热点：无密码
                0 -> Unit
                // WEP：WifiNetworkSpecifier 无对应 API（现代车机不再使用）
                1 -> return null
                // WPA/WPA2 与 WPA3 过渡模式：过渡模式 AP 同时接受 WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 仅
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // 未知类型：按有密码的 WPA2 尝试（比直接放弃更可能成功）
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: 构造应用专属网络参数失败（${error.message}）")
            null
        }
    }

    /**
     * 绑定「当前已连接的 WiFi」——静默命令行连网成功后用它捕获 `Network` 句柄。
     *
     * 请求**不带 specifier**（只声明 TRANSPORT_WIFI、移除 INTERNET），所以系统不会弹
     * 任何确认框，也会把刚连上的车机 WiFi 立刻回调过来。热点无互联网时系统可能不把它
     * 设为默认网络，必须显式 bindProcessToNetwork，我们的 socket / mDNS 才会走 WiFi。
     * 带看门狗：WiFi 未连接时 requestNetwork 会无限静默挂起——5 秒无果给出明确指引
     * （请求保持挂起，用户加入热点后仍会自动完成绑定并回调）。
     */
    fun bindCurrentWifi(
        /** 绑定目标热点的 SSID——断开通知（[onNetworkLost]）需要它；未知则传 null（不断开通知）。 */
        ssidHint: String? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        // 网络回调线程与 Bg 看门狗线程并发读写——原子标志保证可见性与单次投递
        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    boundNetwork.set(network)
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    log("WiFi: 已绑定当前WiFi网络 $network bindProcessToNetwork=$bound")
                    if (reported.compareAndSet(false, true)) onResult(true)
                }

                override fun onUnavailable() {
                    log("WiFi: 没有可用的WiFi连接——请先在系统设置里加入接收端热点")
                    if (reported.compareAndSet(false, true)) onResult(false)
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        // 熄火/离开范围：解绑进程网络，否则所有 socket 继续指向死网络；
                        // 并通知会话层（英雄卡不能停在「已连接」）。
                        // 与 specifier 路径同语义：兜底入网（bindCurrentWifi）是奥迪 3315
                        // 的主路径，不转发 onLost 会停在假「已连接」拖到心跳超时才死
                        // （2026-10-23 审阅定谳）。
                        connectivityManager.bindProcessToNetwork(null)
                        log("WiFi: 绑定的 WiFi 网络已断开（$network）")
                        ssidHint?.let { onNetworkLost?.invoke(it) }
                    }
                }
            }
        callback.set(newCallback)
        connectivityManager.requestNetwork(request, newCallback)
        // 提示性看门狗（**不决定成败**）：requestNetwork 在「手机尚未连上 WiFi」时
        // 会一直挂起，等系统关联+DCHP 完成才回调 onAvailable——实测用户手动加入
        // 热点时可达 10s+。这里只在 5s 时给用户一句指引，超时判定由调用方的
        // WIFI_BIND_WAIT_S 负责；不要在这里取消请求（回调晚到仍会正确绑定）。
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (!reported.get()) {
                log("WiFi: 5 秒尚未绑定（手机可能还没连上热点）——请到 设置>WLAN 加入接收端热点，加入后本轮绑定会自动完成")
            }
        }
    }

    fun release() {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        if (boundNetwork.getAndSet(null) != null) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }
}
