package io.github.shaonialife.cardeck.shizuku

/**
 * VirtualDeviceManager 路线的纯判定逻辑（无 Android 依赖，单测钉死）。
 *
 * 以 COMPANION_DEVICE_APP_STREAMING 伴侣关联 + LOCK_STATE_UNLOCKED 虚拟设备建屏，
 * 整台虚拟设备无视主机锁屏/休眠——Android 14+（SDK 34）官方 API，零隐藏 flag。
 */
object VirtualDeviceRoute {
    /** VirtualDeviceManager / VirtualDeviceParams 自 Android 14（SDK 34）起可用。 */
    const val MIN_SDK = 34

    // VirtualDisplayConfig flags（实测主值 1098 = 2+8+64+1024）：
    const val FLAG_PUBLIC = 1 // 1 << 0：公开屏——私有屏拒绝第三方 IME 窗口（2026-10-17 定谳的键盘坑）
    const val FLAG_PRESENTATION = 1 shl 1
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_TRUSTED = 1 shl 10

    /**
     * VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP（@hide，1 << 11）：独立显示组——物理屏
     * 休眠/锁屏时该屏不跟着走（legacy dock 2026-10-23 同款修复）。
     */
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

    /**
     * VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED（@hide，1 << 12）：锁屏豁免——KeyguardController
     * 对带此 flag 的屏不发 keyguard token（活动不暂停、输入不被拦）。受信 VDM 屏由系统
     * 随 LOCK_STATE_UNLOCKED 自动补上；**非受信屏必须显式声明，否则锁屏后点不动**
     * （2026-11-06 真机定谳：dock 无此 flag 时锁屏下无法点击，app 屏正常）。
     */
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

    /**
     * 受信屏的 flags 候选（逐级尝试，全部失败落 legacy DisplayManager 路径）：
     * ① 1098|PUBLIC：公开化——VDM 屏默认私有，键盘窗口会被 WMS 访问闸拒绝；
     * ② 1098：ROM 拒绝 PUBLIC 时的降级。
     *
     * **不带 DESTROY_CONTENT_ON_REMOVAL（1<<8，2026-11-19 移除）**：该 flag 的语义是
     * 「屏移除时销毁内容」；**不带**它时系统在屏移除时会把屏上任务 reparent 回主屏
     * （display 0）——断开会话后第三方应用（导航等）随任务迁移继续存活、页面状态
     * 保留（原 flag 下应用跟着屏一起死，2026-11-19 用户实测痛点）。配套：自有
     * 启动器界面（Grid/Dock/Settings）在释放屏前由 detach 主动 finishAndRemoveTask，
     * 否则会被一起搬上手机。
     */
    val trustedFlagCandidates: List<Int> =
        listOf(
            FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
                FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED,
            FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
                FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED,
        )

    /**
     * dock 屏：首选**带 TRUSTED 进虚拟设备 display group**（与受信屏同待遇）。
     * 2026-11-06 真机定谳：本 ROM 上**不在设备组的 VDM 屏锁屏后合成渲染冻结**
     * （窗口/活动/显示电源全正常、注入派发成功，但画面停更——dock 条变纯色黑，
     * 用户视角即「锁屏后 dock 点不动」）；设备组内的屏（grid/appHi/map）锁屏中
     * 渲染照常。非受信的代价（第三方任务可能逃上 dock）远小于渲染冻结，
     * 且 dock 只承载自有 DockActivity，逃逸源基本不存在。
     * 候选②保留非受信组合做降级（ROM 拒绝 TRUSTED VDM 建屏时）。全部失败落 legacy。
     */
    val dockFlagCandidates: List<Int> =
        listOf(
            FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED,
            FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
        )

    /**
     * 本轮是否值得尝试 VDM 路线。空 MAC（自动模式下链路对端地址缺失）直接跳过
     * ——关联必须绑定具体设备地址，瞎猜会把关联挂在错误的车机上。
     */
    fun shouldAttempt(
        sdkInt: Int,
        companionMac: String?,
        disabledThisSession: Boolean,
    ): Boolean = sdkInt >= MIN_SDK && !companionMac.isNullOrBlank() && !disabledThisSession

    /**
     * `cmd role get-role-holders --user <id> android.app.role.COMPANION_DEVICE_APP_STREAMING`
     * 的输出里是否包含指定包名（输出含角色名与持有者列表，按空白分词精确比对
     * ——contains(pkg) 会被同前缀包名误判）。
     */
    fun roleHoldersContain(
        output: String,
        packageName: String,
    ): Boolean = output.split(Regex("\\s+")).contains(packageName)
}
