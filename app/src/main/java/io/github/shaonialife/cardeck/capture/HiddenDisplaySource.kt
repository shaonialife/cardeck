package io.github.shaonialife.cardeck.capture

import android.content.Context
import android.view.Surface
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.ShizukuGate

/**
 * 隐形屏模式：以普通 API 创建与车机同分辨率的 PUBLIC 虚拟屏（surface 即编码输入），
 * 经 Shizuku(shell) `am start --display` 把应用启动进去；手机主屏可正常息屏。
 *
 * 启动目标优先「高德地图车机版」（天生横屏；其引导页会丢 --display，需显式启动
 * MainMapActivity 并强制 fullscreen），未安装回退手机版高德。
 * （移植自参考项目 android-auto 的 VirtualDisplaySource）
 */
class HiddenDisplaySource(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : ScreenSource {
    override val name: String = "隐形屏"

    /** 兼容字段：双屏架构下指向应用屏 displayId。 */
    var displayId: Int = -1
        private set

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        // ⚠️ 不要在这里 detach：保留虚拟屏复用（2026-11-10）的判定在
        // VirtualLauncher.attach 头部——它复用时只停旧 composer（屏与屏上任务不动），
        // 非复用分支自己会 detach。这里抢先 detach 会把 sessionRetained/屏 id 全部
        // 清零，复用永远走不到（2026-11-12 真机日志定谳：重连全量重建的根因）。
        // 逐状态诊断：Shizuku「开着」≠ 已授权本应用（服务端授权必须弹窗点一次）
        when (val gate = ShizukuGate.state(context)) {
            ShizukuGate.State.READY -> Unit

            ShizukuGate.State.NOT_INSTALLED -> {
                log("SHIZUKU: 未安装 Shizuku App（shizuku.rikka.app）")
                return false
            }

            ShizukuGate.State.NOT_RUNNING -> {
                log("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」（手机重启后需重新启动）")
                return false
            }

            ShizukuGate.State.UNAUTHORIZED -> {
                log("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗中点「允许」后重连")
                ShizukuGate.requestPermission()
                return false
            }
        }
        // 等 UserService 真正就绪再建屏：会话在 RECORD 后立刻 attach，服务可能还在
        // spawn（调试包加载 dex 更慢）。立刻判「不可用」会白白回退到应用侧 PUBLIC 屏
        // ——那条路径对 UserService 依赖更重，服务没起来就彻底没有触摸（2026-10-22 K60E）。
        // 本方法已在后台线程（VideoSender.start 回调）执行，阻塞等待是安全的。
        val svc = CarInputClient.awaitReady(context, USER_SERVICE_WAIT_MS)
        if (svc == null) {
            log(
                "SHIZUKU: UserService 在 ${USER_SERVICE_WAIT_MS}ms 内未就绪" +
                    "——将回退应用侧 PUBLIC 屏（触摸可能不可用，请检查 Shizuku 是否被系统冻结）",
            )
        }
        // 双屏启动器：GL 合成器（dock 屏 + 应用屏）→ 编码 Surface
        val ok =
            VirtualLauncher.attach(
                context,
                surface,
                width,
                height,
                densityDpi,
                log,
            )
        displayId =
            if (ok) VirtualLauncher.appDisplayId else -1
        return ok
    }

    override fun detach() {
        VirtualLauncher
            .detach()
        displayId = -1
    }

    override fun release() = detach()

    private companion object {
        /**
         * 等 UserService 就绪的上限。取值权衡：太短会误判「不可用」而回退公屏（丢触摸），
         * 太长会推迟首帧。车机侧对屏幕流 SETUP→激活有看门狗，但 RECORD 已完成、
         * 视频数据口也已连上，这里多等 3s 不影响会话存活。
         */
        const val USER_SERVICE_WAIT_MS = 3_000L
    }
}
