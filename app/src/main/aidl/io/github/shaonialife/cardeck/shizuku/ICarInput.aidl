// Shizuku UserService 接口：跑在常驻 shell 进程中，以毫秒级延迟注入触摸事件。
// 相比每次 spawn `input` 命令（约 200-400ms），injectInputEvent 直连系统服务。
package io.github.shaonialife.cardeck.shizuku;

import android.view.Surface;

interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // 连续拖拽原语：整段滑动共用一次 DOWN，MOVE 逐事件直注（真实时间戳），
    // 应用侧速度跟踪器才能算出正确的滚动惯性。一次只支持单指。
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    // 按键注入（iDrive 旋钮 → 方向键/确认键）：与触摸同一条 injectInputEvent 通路，
    // KeyEvent 带 targetDisplayId 直注虚拟屏，毫秒级、不碰手机主屏。
    // 失败返回 false（调用方回退 `input -d <id> keyevent <code>`）。
    boolean keyEvent(int displayId, int keyCode);

    // 受信虚拟屏（shell 权限）：第三方应用的新任务可落在其上，
    // 解决「应用内跳转的任务被重定向到手机主屏」问题（AOSP 安全策略）。
    // trusted=false：不声明 FLAG_TRUSTED——第三方任务无法被系统安置到该屏
    // （dock 屏只承载自有 DockActivity，去掉受信可杜绝其它应用任务逃逸上来）。
    int createDisplay(String name, int width, int height, int dpi, in Surface surface, boolean trusted);
    boolean releaseDisplay(int displayId);

    // 动态调整虚拟屏尺寸（创建者才可调）：屏上应用的配置变更由系统派发，
    // 应用自行 relayout——地图屏「分屏 ⇄ 全屏」切换即用此能力（任务不动，状态不丢）。
    boolean resizeDisplay(int displayId, int width, int height, int dpi);

    // VirtualDeviceManager 路线（Android 14+ 官方 API）：以 shell 包名与车机 BT MAC 建
    // COMPANION_DEVICE_APP_STREAMING 伴侣关联 +
    // LOCK_STATE_UNLOCKED 虚拟设备，建在其上的屏整台无视主机锁屏/休眠。
    // mac 在每次会话建屏前传入（空串=不启用，全部走 DisplayManager legacy）。
    void setCompanionDevice(String btMac);

    // 会话结束收口：释放虚拟设备上残余的屏 → device.close → disassociate（仅当
    // 关联是本轮创建的；已有的用户级关联复用后原样保留）→ role 兜底回填。
    boolean closeVirtualDevice();

    // 物理主屏硬件电源（SurfaceControl.setDisplayPowerMode）：false=断电（真息屏），
    // true=恢复点亮。系统逻辑上仍保持唤醒（不锁屏、不进 Doze），虚拟屏照常渲染。
    // 原理同 scrcpy -S/--turn-screen-off；失败返回 false（ROM 未授权/取不到 display token）。
    boolean setPhysicalScreenPower(boolean on);

    boolean ping();
    String diag();

    // 真·假息屏：SurfaceControl 层直接断/上内置屏电源（POWER_MODE_OFF/NORMAL）。
    // 系统保持交互态（不睡眠、无 keyguard、虚拟屏零影响），物理面板真正断电。
    // 需要 ACCESS_SURFACE_FLINGER——shell 已持有（实测荣耀 Magic7 Pro granted=true）。
    // 反射链 SurfaceControl → DisplayControl（新系统 token 入口迁移），逐级回退。
    boolean setScreenPower(boolean on);

    // 服务进程 PID：应用侧清理「非当前绑定进程」的陈旧 UserService 时用于排除自己
    // （旧进程持有的虚拟屏跨进程无法 release，杀进程是唯一释放途径，见 CarInputClient）。
    int pid();
}
