package io.github.shaonialife.cardeck.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * 上车自动连接 / 下车自动收摊。
 *
 * **为什么用清单静态接收器**：`ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` 在
 * 隐式广播豁免清单里（Android 开发者文档「隐式广播例外情况」明确列出），所以即使
 * 应用进程被杀，车机蓝牙连上的瞬间也能把进程拉起来。
 *
 * **为什么能启动前台服务**：Android 12+ 限制后台启动 FGS，豁免清单里**没有**
 * 「蓝牙广播」这一项；本应用能起是因为持有 `SYSTEM_ALERT_WINDOW`（暗幕/触摸探针
 * 依赖它）——它是官方豁免项（targetSdk 34 下只需要权限存在；Android 15+ 才额外
 * 要求当时有可见悬浮窗）。没授悬浮窗权限时 `startForegroundService` 会抛
 * `ForegroundServiceStartNotAllowedException`，这里只记日志——用户手动打开 App
 * 连接一次即可（那次有可见 Activity，不受限制）。
 *
 * **目标过滤**：优先用户选定的目标车机（[TargetDevicePrefs]）；未选定时按车机名称
 * 关键词兜底。耳机/手表等 ACL 连接不会误触发。
 *
 * 音频注意：自动连接只推视频——AudioPlaybackCapture 需要 MediaProjection 授权令牌，
 * 系统不允许无令牌静默获取（appops PROJECT_MEDIA 只是其中一道闸，不产生令牌）。
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        ) {
            return
        }
        val device = deviceOf(intent) ?: return
        val app = context.applicationContext
        // 「上车自动连接」开关（设置 → 显示，默认开）
        if (!AutoConnectPrefs.enabled(app)) return
        // 这两个访问需要 BLUETOOTH_CONNECT；未授权时放弃（无法判断是不是目标车机）
        val address = runCatching { device.address }.getOrNull() ?: return
        val name = runCatching { device.name }.getOrNull()

        if (!isCarDevice(app, address, name)) {
            DebugFileLog.append(app, "AUTO: 忽略非车机蓝牙 ${name ?: "?"}（$address）")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            // 用户刚取消过：ACL 抖动（车机拆栈重试）不再自动重连，尊重用户意愿
            val remainingMs = SessionController.autoConnectSuppressedUntil - android.os.SystemClock.uptimeMillis()
            if (remainingMs > 0) {
                DebugFileLog.append(
                    app,
                    "AUTO: 用户刚取消连接——本次 ACL 连入忽略（冷却剩余 ${remainingMs / 1000}s）",
                )
                return
            }
            DebugFileLog.append(app, "AUTO: 车机蓝牙已连上 ${name ?: "?"}（$address）——自动拉起会话")
            runCatching {
                context.startForegroundService(
                    Intent(context, CarPlayService::class.java)
                        .setAction(CarPlayService.ACTION_AUTO_START_SESSION),
                )
            }.onFailure { error ->
                DebugFileLog.append(
                    app,
                    "AUTO: 自动启动被系统拦截（${error.javaClass.simpleName}: ${error.message}）" +
                        "——请授予「显示在其他应用上层」后重试，或手动打开 App 连接一次",
                )
            }
        } else {
            DebugFileLog.append(app, "AUTO: 车机蓝牙已断开 ${name ?: "?"}（$address）——结束会话")
            // 会话在跑时服务就是前台进程，startService 允许；不在跑说明本来就没有会话
            runCatching {
                context.startService(
                    Intent(context, CarPlayService::class.java)
                        .setAction(CarPlayService.ACTION_AUTO_STOP_SESSION),
                )
            }
        }
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun isCarDevice(
        context: Context,
        address: String,
        name: String?,
    ): Boolean {
        val target = TargetDevicePrefs.get(context)
        if (target != null) return TargetDevicePrefs.matches(target, address)
        val label = name ?: return false
        return CAR_KEYWORDS.any { label.contains(it, ignoreCase = true) }
    }

    private companion object {
        /** 未选目标车机时的兜底：常见车机蓝牙名关键词（真车 + 测试台）。 */
        val CAR_KEYWORDS = listOf("BMW", "MINI", "AUDI", "PORSCHE", "MERCEDES", "BENZ", "VOLVO", "CARPLAY")
    }
}
