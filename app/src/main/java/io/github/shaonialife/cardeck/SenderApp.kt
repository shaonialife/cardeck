package io.github.shaonialife.cardeck

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * 进程级安全网：任一线程的未捕获异常只记录并让该线程结束，
 * 不再走默认处理器杀死整个进程（连接会话横跨多个后台线程，
 * 一个套接字异常不应毁掉全部链路与前台服务）。
 * 各线程自身仍应尽量用 try/catch 精确处理；这里是最后防线。
 */
open class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 前后台追踪：自动连接的「正在连接」通知条只在后台弹出（前台有首页英雄卡）
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    foregroundActivities++
                }

                override fun onActivityStopped(activity: Activity) {
                    foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        // 会话日志起点标记：日志文件跨进程累积（2MB 上限），导出默认只取本次启动
        // 之后的部分。必须在这里写（Application 是进程最早入口），晚于任何业务日志
        // 都会让它被算进「历史」。
        DebugFileLog.markSessionStart(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "未捕获异常（线程 ${thread.name}，已吞掉保进程）", throwable)
            // 不调用 previous：默认处理器会 killProcess 杀死整个 app
        }
        // 应用安装/卸载/更新 → 桌面与车机设置的共享应用清单缓存失效
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    VirtualLauncher
                        .invalidateAppsCache()
                    Log.i(TAG, "应用清单缓存已失效（包变化）")
                }
            },
            packageFilter,
        )
        // Rust 协议库（cardeck-protocol）安全加载：失败只记日志，不影响任何现有功能。
        // 这里不能用 SessionController.appendLog——它要等 ensure() 设过 appContext 才写文件，
        // 而本任务可能在 MainActivity 之前就跑完（表现：日志里完全没有 RUST 行）。
        io.github.shaonialife.cardeck.util.Bg.run("rust-load") {
            val line =
                try {
                    val bridge = io.github.shaonialife.cardeck.protocol.NativeBridge
                    if (bridge.ensureLoaded()) {
                        // 原生日志路径注入（跨平台：crate 内零平台路径硬编码，
                        // 鸿蒙等宿主注入自己的 files 目录即可）
                        bridge.nativeSetLogFile(
                            java.io.File(filesDir, "native_debug.log").absolutePath,
                        )
                        // 日志桥排空线程：Rust 侧日志（fw:: 链路编排/RTSP 诊断/panic）
                        // 经旁路队列 + 200ms 轮询接 appendLog（logcat + main_debug.log +
                        // UI 缓冲 + 挂屏增量四路自动获得）。**形态定谳**：JNI 回调方向
                        // （全局引用+方法 ID 反射调用）在本机 ART 三连 SEGV_ACCERR
                        // （BRIDGE-PROBE：返回的是不透明句柄）——队列轮询 + ByteArray
                        // 返回是唯一生产验证过的 JNI 形。
                        Thread(
                            {
                                while (true) {
                                    runCatching {
                                        bridge.nativeTakeLogs()?.let { bytes ->
                                            for (line in String(bytes, Charsets.UTF_8).split('\n')) {
                                                if (line.isEmpty()) continue
                                                // 行形 = "<原始时刻 epoch ms> <文本>"——按
                                                // Rust 侧时刻渲染（批次排空不重打时间戳）
                                                val sp = line.indexOf(' ')
                                                val head = if (sp > 0) line.substring(0, sp) else ""
                                                if (sp in 1..14 && head.all { it.isDigit() }) {
                                                    io.github.shaonialife.cardeck.SessionController.appendLog(
                                                        line.substring(sp + 1),
                                                        head.toLong(),
                                                    )
                                                } else {
                                                    io.github.shaonialife.cardeck.SessionController
                                                        .appendLog(line)
                                                }
                                            }
                                        }
                                    }
                                    Thread.sleep(200)
                                }
                            },
                            "rust-log-drain",
                        ).apply {
                            isDaemon = true
                            start()
                        }
                        // 顺手验一遍固件原语：marker 判定 + 校验和 + 参数名查表
                        val marker = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
                        val detect = bridge.nativeIap2IsDetectMarker(marker, marker.size)
                        val sum = bridge.nativeIap2Checksum(marker, 5, 0)
                        val p6 = bridge.nativeIdentificationParamName(6)
                        val syn =
                            bridge
                                .nativeIap2SynParamDefaults()
                                ?.joinToString("") { "%02x".format(it) }
                        "RUST: ✓ ${bridge.nativeVersion()}（${bridge.nativeHello()}；" +
                            "固件原语 detect=$detect sum=$sum p6=$p6 syn=$syn；日志桥（队列轮询）已通）"
                    } else {
                        "RUST: ⚠ cardeck_protocol 未加载"
                    }
                } catch (t: Throwable) {
                    "RUST: ⚠ 原生入口异常：$t"
                }
            DebugFileLog.append(this, line)
            Log.i(TAG, line)
        }
    }

    companion object {
        private const val TAG = "CarPlaySender"

        /** 已进入 STARTED 状态的 Activity 计数（>0 = 应用前台；ConnectingBanner 用）。 */
        @JvmStatic
        var foregroundActivities = 0
            private set
    }
}
