# 虚拟屏 / 显示 / 旋转

> 2026-11-18 从宿主 `AGENTS.md` 拆出（原 1397 行 / 136KB）。
> 共 28 条，558 行。
>
> 虚拟屏创建、分辨率与密度、旋转与横屏、息屏与常亮、分屏拖拽、键盘与方向、compat 覆盖。
> **改显示相关代码前必读**。这些坑多数是真机 ANR / 黑屏 / 字号漂移换来的。

---

- **首页旋转：声明 configChanges 不重建 + 横屏双栏**（2026-10-18）：手机侧首页是
  有状态的操作台（滚动位置、折叠卡、已填 SSID、正在显示的弹窗），系统重建会全部丢掉，
  所以 `MainActivity` 声明了 `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden`。
  **代价是 `layout-land/` 不会生效**，横屏排布只能在 `onConfigurationChanged` 里手工切：
  `applyContentLayout(landscape)` 在横屏把英雄卡**移出 ScrollView**、挂到 `contentArea`
  左缘固定（右栏 `mainScroll` 用左内边距让位），竖屏再移回 `contentRow`——
  英雄卡若留在同一个 ScrollView 里，滑右栏会把它一起带走（用户实测反馈）。
  移动父容器后 layoutParams 类型会变（LinearLayout ⇄ FrameLayout），必须整体重建；
  横屏高度要 `WRAP_CONTENT`（FrameLayout 里 MATCH_PARENT 会把按钮顶到卡片底缘）。
  **onCreate 也必须调一次**——以横屏启动不会触发 onConfigurationChanged。
  横屏屏高只有 ~300dp，实测预算：顶部栏 60 + 波形框 70 + 状态 40 + 副标题 20 +
  间距 18 + 按钮 52 + 内边距 24 ≈ 284dp 刚好放得下「未连接 + 启动连接」；波形框压矮后
  圆盘要同步缩到 44dp（否则 74dp 圆盘撑满框、弧线被完全挤出可视区），CARPLAY 标签
  在横屏隐藏。`SignalWavesView` 按 **min(宽比, 高比)** 缩放（原先只按宽度，压矮即溢出），
  `hubPlate`/`hubCaption` 用 gravity 居中而非固定 marginTop（后者按 176dp 算死，压矮必错位）。
  **英雄卡宽度不要读 `contentArea.width`**：旋转瞬间它还是旧方向的测量值（竖屏 1168 →
  横屏里英雄卡只剩 537px、副标题挤成三行），要用 `屏幕宽 - 页面内边距` 推算。
  弹窗尺寸统一走 `ui/PanelWindow`（宽度 92% 屏宽 + 可选高度比例），它维护「正在显示」
  的弱引用清单，`onConfigurationChanged` 调 `resizeShowing(this)` 重算——不重算的话
  弹窗会保持竖屏宽度、在横屏里只占中间一半。
- **三套坐标系**：车机触摸 = 通告视图坐标系（如 1160×540）；流/虚拟屏 = 缩放后尺寸
  （如 1450×674）。`TouchRouter.setCoordinateScale` 在会话建立时换算，改动触摸路由
  时不要破坏这层映射。地图屏坐标 = 显示内容本身（动态 resize 后）。
- **dock 尺寸必须随画布分辨率等比**（2026-10-17 修复「高分辨率下车机 dock 文字/图标
  显小」）：dock 的所有尺寸源自设计稿 `design/launcher-proto.html`（基准画布
  1160×540@420dpi，dock 48dp=126px、时钟 22px）。**dock 屏密度由 SessionController
  按画布短边等比下发**（`420 × min(流W,流H)/540`，上限 1680）——奥迪基准下系数恰为
  1.0、行为与旧版完全一致；测试台 2320×1080（2×）下密度 840，dock 条 252px、文字/
  图标同步 ×2，占画布短边恒定 23.3%。此前密度固定 `420×缩放%`，高分辨率车机上 dock
  相对画布减半显小（应用屏密度是自适应算的，所以只有 dock 显小）。配套约定：
  ① dock 布局（`activity_dock_sidebar/bar.xml`）**全部用 dp**（= 设计 px ÷ 2.625），
  禁止 px 写死；② 字号在 `DockActivity` 按「设计 px × designScale」以 PX 设置
  （designScale = densityDpi/420；不用 sp——dock 是像素级设计画布，不跟手机字体缩放）；
  ③ 模糊半径等物理像素量同样乘 designScale；④ `fitClockText` 的行程/步进也按
  designScale 缩放。改 dock 视觉时先确认这三处都随密度走。
- **应用屏/地图屏密度 = 手机侧密度（2026-11-06，字号跨屏漂移修复 + 移除超采样屏）**：
  旧行为：应用屏 360/240dpi、地图屏 200dpi 与手机 560dpi 不一致，应用跨屏打开时系统
  按新密度重排（手机开→车机开字号变大、反向变小）。现 `appDisplayGeometry/
  mapDisplayGeometry`（纯函数，`DisplayGeometryTest` 钉死）：**密度=手机侧
  （`context.resources.displayMetrics.densityDpi`，不 coerce——一致性是本特性核心）**，
  px = dp 窗口 × 手机密度/160，合成器 GL 缩小回画布应用区（缩放比 ≈0.43，天然高倍率
  超采样）。dp 窗口：横屏高 = LETTERBOX_TARGET_DP×LETTERBOX_ASPECT_RATIO（410×1.05），
  竖屏宽 = 410dp，其余维度按画布应用区纵横比；地图/固定屏 px = 画布区域 × 手机密度/
  DENSITY_BASELINE（2026-11-10 起基准统一为 Android dp 基准常量，原遗留 200 已退役；
  车机布局不变、标注变清晰）。px 最长边 ≤4096（GL 纹理安全，仅极端机型让密度略降）。
  **appHi 超采样屏已移除**（手机密度天然高于旧 1.5× 超采样倍率），gridDisplayId=应用屏，
  `composer.start` hi 两参传 0（`hasHiInput=false` 是既有降级路径）；`gridScale()/
  activeAppScale()` = 应用屏 px/画布应用区 px（**触摸坐标换算与 GridActivity 把手布局
  都依赖它，改密度公式必须同步检查**）；`resizeMapDisplay` 的 resize 目标 = 画布 tile
  尺寸 × `mapDisplayDensity/DENSITY_BASELINE`（显示 px 域），密度参数同步传手机值。回退路径（无
  Shizuku 应用侧屏）同样密度。旧 APP_SUPERSAMPLE/SUPERSAMPLE_MAX_HEIGHT 常量退役。
- **竖屏桌面网格行数自适应**（2026-10-17 修复「竖屏单元格占用范围过大」：用户开
  「显示布局边界」截图暴露——固定 3 行把格子拉成 ~1:2.5 瘦高条，图标只占三成、
  大片空框）：`GridActivity.computeCellMetrics()` 按**格子目标高宽比 1.25** 自适应
  行数（`usableH / (格宽 × 1.25)`，钳制 3~6 行）——横屏 CarPlay 规格仍算出 3 行、
  行为与旧版完全一致；竖屏（如 648×1241 应用屏）算出 6 行（对齐 iPhone 竖屏主屏
  4×6 观感），格子收敛到 ~154×193。分页容量 = `COLUMNS × rows`（运行时字段，
  勿回退成常量）。UI 验证技巧：手机开发者选项「显示布局边界」下截图，单元格
  红框即实况边界；改尺寸后先算 `usableH/(格宽×1.25)` 再对照截图红框高度核对。
- **流尺寸必须为偶数**（H.264 4:2:0）：奇数高度会让编码器首帧输入时进错误态且
  曾被静默吞掉。缩放计算后统一在 SessionController 对齐（VideoSender 内另有兜底）。
- **虚拟屏 resize 必须同步消费端**：`VirtualDisplay.resize` 后要
  `SurfaceTexture.setDefaultBufferSize(新尺寸)`，否则投影把新内容画进旧 buffer 左上角
  （内容偏居左、宽度只剩比例值）。
- **虚拟屏上的应用弹不出键盘：两条独立原因，都必须修**（2026-10-17 定谳，车机侧
  「设置」搜索框点击无键盘根因；已实现并经真机端到端验证——车机屏弹键盘、点击按键
  文本进入搜索框）：
  **① 私有屏拒绝第三方 IME 窗口**：`createVirtualDisplay` 不声明
  `VIRTUAL_DISPLAY_FLAG_PUBLIC` 时屏带 `FLAG_PRIVATE`（owner=shell uid 2000），而
  `WMS.addWindow` 的访问闸 `displayContent.hasAccess(session.mUid)` → `Display.hasAccess`
  对私有屏只放行 owner/system/root/UID-present——**输入法（uid 10xxx）的键盘窗口被
  `ADD_INVALID_DISPLAY(-9)` 拒绝**，`SoftInputWindow.show()` 捕获后进入
  `REJECTED_AT_LEAST_ONCE` **永不重试**（表现为 IME 服务侧 `mViewsCreated/mWindowVisible=
  true` 但 WM 里没有 InputMethod 窗口、`mImeWindow` 恒 null）。修复：受信屏 flags 加
  `VIRTUAL_DISPLAY_FLAG_PUBLIC`（公开 API = 1；只放宽窗口访问，「公开屏默认自动镜像」
  仅在不声明 OWN_CONTENT_ONLY 时生效，我们已声明，不受影响；dock 屏保持私有）。
  **② IME 展示策略默认 FALLBACK**：`VIRTUAL_DISPLAY_FLAG_TRUSTED` 只保证受信，**虚拟屏的
  IME 展示策略默认是 `DISPLAY_IME_POLICY_FALLBACK_DISPLAY`**（`dumpsys window displays`
  里该屏 SettingsEntry `mShouldShowIme=null` 即此默认值）——IMMS 的
  `computeImeDisplayIdForTarget` 对非 LOCAL 策略一律返回默认屏，键盘请求被路由到
  **手机主屏**。修复：创建受信屏后 shell 反射调 `IWindowManager.setDisplayImePolicy(id,
  LOCAL)`——**LOCAL 值必须运行时反射读 `android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL`
  而非硬编码**（本机 ROM 有魔改枚举值的风险，用户 2026-10-17 提示；AOSP = 0，读不到回落 0
  并记日志）。IWindowManager 代理走 `ServiceManager.getService("window")` +
  `IWindowManager$Stub.asInterface` 反射（`WindowManagerGlobal.getWindowManagerService`
  在本 ROM 上会致进程被杀，勿用）；需 `INTERNAL_SYSTEM_WINDOW`，本机
  `com.android.shell` 已授予，`wm`/`cmd window` 均无对应命令。策略按 display 实例
  （uniqueId 含代次）记录、虚拟屏每次会话重建，故必须随 createDisplay 重设（幂等）；
  只对受信屏可设（否则 WMS 抛 SecurityException）。
  两条都要修才通：只设策略 → 令牌迁到虚拟屏但窗口被 private 闸拒绝（实测
  `imeDisplayId=299` 而 `mImeWindow=null`、show 卡在
  `PHASE_SERVER_SET_REMOTE_TARGET_IME_VISIBILITY(49)`）；只放开 PUBLIC 不设策略 → 键盘
  仍落手机屏。**诊断工具**：反射读 `WindowManager` 全部 IME_POLICY 字段 + 逐屏读策略
  （`/data/local/tmp/const.jar ImeConst <ids...>`）；`dumpsys` 看 `mViewsCreated` 与
  `mImeWindow` 是否同真。改动文件：`CarInputServiceImpl.createDisplay`。
- **虚拟屏必须「忽略应用请求的方向」**（2026-10-18 定谳，头条旋转整屏的根因）：
  竖屏应用（头条等）在横屏虚拟屏上会请求 portrait，系统照办就把**整块虚拟屏**旋转
  ——`dumpsys display` 可见 `rotation 0→3`、`real 2251x1152→1152x2251`。后果是一连串：
  合成器按整幅映射 → 画面比例异常；`TouchRouter` 按未旋转尺寸换算坐标 → 点击漂移、
  点不到目标；退回桌面时仍是旋转态 → 「桌面分辨率也变了」。
  修法：会话建立时对 **app / appHi / map 三块屏**执行
  `cmd window set-ignore-orientation-request -d <id> true`，`detach` 时置回 false
  （必须在释放虚拟屏**之前**，否则 id 失效、设置以 uniqueId 残留到下次会话）。
  **踩过的坑**：这条命令只接受 `true|1|false|0`，传 `reset` 会报
  `Error: expecting true, 1, false, 0, but we get reset` 且**只写 stderr**——
  而 `Shell.exec` 不读 stderr，于是旧代码静默无效了整整一段时间（方向策略从未生效）。
  现在 `setIgnoreOrientationRequest()` 写完**读回校验**，失败会打印
  `⚠ 应用屏忽略方向请求失败`。**新增任何 shell 写操作都应读回确认**，
  尤其 `cmd window` 这类参数校验严格的命令。
- **GL 绘制坐标约定**：DualDisplayComposer 顶点/裁剪的 y 全程是 **GL 坐标（底部
  原点，向上增长）**，触摸/流坐标是顶部原点——两套方向相反，混用即出 bug。圆角
  裁剪矩形（uClipRect）必须与顶点同坐标系直传；曾因翻转成屏幕坐标 + 全高 tile
  对称重合而长期未暴露，半高 tile（竖屏）整块被 discard（地图卡不可见根因，
  2026-10-14）。部分区域可见性异常时优先核对该区域顶点与裁剪的坐标系一致性。
- **出帧节奏**在 DualDisplayComposer 合成循环：有新帧按 `1000/targetFps` 节流，
  静止画面保底 33ms 心跳（编码器断粮 = 接收端画面冻结）；「新帧」只统计会画进
  输出画面的输入（dock + 活动应用屏 + tile 可见时的 map）。
- **锁屏/息屏对虚拟屏的影响：两条独立 token 路径 + ALWAYS_UNLOCKED 豁免**（2026-10-23
  荣耀 Magic7 Pro 真机实证，flag 修复已落地、整车链路待车验证）：
  手机按电源键后车机冻结/黑屏是系统两条休眠路径叠加：① **keyguard sleep token**
  （锁屏时 KeyguardController 给每块屏发 token → 活动暂停）；② **Display-off sleep
  token**（PUBLIC 虚拟屏没有 `FLAG_NEVER_BLANK`——AOSP 只给非 PUBLIC 屏配，系统熄屏时
  虚拟屏电源态跟随主屏变 OFF/DOZE → WM 发 token）。修复：`CarInputServiceImpl.
  createDisplay` 给屏加 **`VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED`（@hide，1<<12）**——
  `setKeyguardShown` 对带此 flag 的屏直接忽略（连 PIN 安全锁也不影响），锁屏不再发
  keyguard token；**必须同时声明 `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`（1<<11）**，
  否则 flag 被系统剥掉（文档明说「只对不在默认 display group 的虚拟屏有效」，实测不带
  时创建成功但读回 display.flags 无此位）。权限 `ADD_ALWAYS_UNLOCKED_DISPLAY`/
  `ADD_TRUSTED_DISPLAY` 经实测 shell 已持有（dumpsys package com.android.shell）；
  ROM 拒绝时降级去掉重试（createDisplay 内置回退）。真机验证：带 flag 的屏锁屏后
  无 keyguard token，亮屏（仍 PIN 锁定）即自动苏醒、活动 RESUMED、画面帧恢复。
  **配套认知**：① 屏在**熄屏态创建**会出生即带 Display-off token（休眠），交互态创建
  才出生即醒——产品建屏发生在用户操作手机时，无此问题；② 系统真休眠（按电源键）期间
  窗口表面会被剥离，唤醒后表面重挂在本 ROM 上不稳定（活动 RESUMED 但 mHasSurface=
  false，靠后续亮屏稳态/活动重启恢复）——所以「按电源键」场景仍需产品侧配合：
  引导用「假息屏」而非电源键。
  **「连接时真息屏」（PR17，2026-10-24 合入）默认关**（设置→显示，用户要求）：
  断电由暗幕承载（`enterDimNow`，同手动假息屏路径）——面板断电后触摸 IC 实测
  仍工作（荣耀：黑屏下触摸直达 UI），点暗幕即唤醒；暗幕同时是口袋误触盾。
  **按电源键 = 真休眠 = 车机冻结风险**（Keyguard/表面剥离，本 ROM 不稳），
  正确姿势是点暗幕、别按电源键；降级路径（暗幕不可用）裸断电只能按电源键救。
  **真·假息屏已实现（2026-10-23 真机验证）**：`ICarInput.setScreenPower(on)` 在
  UserService 里反射 `SurfaceControl.setDisplayPowerMode(token, POWER_MODE_OFF/NORMAL)`
  （需要 ACCESS_SURFACE_FLINGER，shell 已持有）。系统保持交互态 → 虚拟屏零影响
  （`dumpsys power` mWakefulness 一直 Awake），物理面板真断电（
  `dumpsys SurfaceFlinger` 头部 `powerMode=Off/On/Doze` 可直接核对）。屏幕暗着
  但**没锁屏没睡眠**，触摸探针唤醒、会话结束/暗幕开关/detach 三路收口恢复供电、
  SCREEN_OFF 广播（真休眠）清 `panelOffByUs` 标记把面板交还系统。
  **token 获取入口随系统版本迁移，荣耀 A17 实测只有 ③ 通**：①
  `SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken`（Android 11~14，
  本机 NoSuchMethodException）→ ② `android.hardware.display.DisplayControl`（部分
  新系统，本机 ClassNotFoundException）→ ③ `SurfaceControl.getInternalDisplayToken`
  （本机可用）。三分支都失败时 setScreenPower 返回 false，暗幕自动退回纯遮罩态。
  **注意**：面板断电后物理触摸能否唤醒未在真机验证（bench 用的是注入事件）——
  若数字死屏不拾取，唤醒路径 = 电源键（真休眠一次再亮）。
  **暗幕仍保留，角色变化**：①setScreenPower 失败（老 ROM/Shizuku 未就绪）时的降级
  手段；②假息屏期间的误触盾——面板灭着时若无这层可点击遮罩，兜里触摸会点到前台
  应用（如「断开连接」）。面板断电时暗幕不可见，不影响观感。
  **生命周期收口（2026-10-23 用户实测 bug）**：假息屏中按电源键真休眠时，SCREEN_OFF
  必须**同时**清面板标记、撤暗幕、停计时——否则解锁后暗幕还盖在锁屏上，要多点一下
  才消失；USER_PRESENT（解锁）再重排计时——指纹/人脸解锁不产生屏幕触摸事件，探针
  收不到，不补排的话解锁后自动假息屏是停摆的。
  诊断：`dumpsys window displays` 看各屏 `mSleeping`/`mAllSleepTokens`；
  `dumpsys display` 看虚拟屏 `state`； UserService 建屏读回校验打在
  `/data/local/tmp/carinput_svc.log`（含 token 入口与 setScreenPower 结果）。
- **暗幕/常亮必须由会话驱动，不能由 App 启动驱动**（2026-10-19 用户实测
  「会话已断开、过一会儿仍然启动暗幕」）：`ScreenDimmer.attach` 原先在启动时就
  `schedule()` 45 秒定时器，而车机侧断开走的是 `VideoSender.onEnded`——它**不经过
  `retireSession`**，`VirtualLauncher` 仍挂着，定时器照常触发。现在自动暗幕的条件是
  `enabled && sessionActive && attached`（纯函数 `shouldAutoDim`，有单测）：
  会话开始（`VideoSender.start` 的 onSurfaceReady 里 `attach` 成功后才置位）才排程与
  把屏幕超时改「永不」，`onEnded` / `retireSession` 与 `detach()` 都必须清零标记
  （否则下次启动会沿用 true）。手动「假息屏」`enterDimNow` 走 `force` 路径不受限制。
  **判断标准**：任何「会话期间才该生效」的 UI/系统副作用，都要同时挂在
  「推流启动成功」与「onEnded」两端——只挂一端必然漏掉车机主动断开这条路径。
- **手机真息屏（物理面板断电）+ 虚拟屏独立常亮**（2026-10-23，详见
  `docs/PHONE-SCREEN-OFF.md`）：按电源键会「锁屏 + Doze」（车机黑屏/掉帧），正确做法是
  让系统逻辑上保持「亮屏且未上锁」，只把物理面板在 SurfaceFlinger 层断电（scrcpy `-S` 同源）：
  受信屏额外声明 `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP(1<<11)` +
  `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED(1<<12)`，再对物理主屏 token 调
  `SurfaceControl.setDisplayPowerMode(0)`。**dock 屏虽「不受信」也必须带同一对 flag**
  （同样的三级降级）——否则它落在默认显示组，物理屏一断电整组跟着灭，车机侧表现为
  「app 屏正常、dock 栏全黑，解锁后恢复」（2026-10-23 实测；AOSP 只在 `AUTO_MIRROR`
  时清 `OWN_DISPLAY_GROUP`，dock 没声明 PUBLIC 故 flag 保留）。四条硬约束：① `ALWAYS_UNLOCKED` 需
  `ADD_ALWAYS_UNLOCKED_DISPLAY`，**部分 ROM 未授予 shell**，带上会 SecurityException
  打挂整块屏 → `createDisplay` 必须三级降级（组+解锁 → 组 → 基础 flags）；
  ② **SDK 34+ 删除了 `SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken`**，
  必须经 `ClassLoaderFactory` + `SYSTEMSERVERCLASSPATH` 加载
  `com.android.server.display.DisplayControl`，并 `Runtime.loadLibrary0(cls,"android_servers")`，
  否则 JNI 未注册（scrcpy 同款）；③ 时机：推流成功后 `enterPhoneScreenOff()`，
  `onEnded` 走 `restorePhoneScreen(sleep=true)`（保持黑、直接 KEYCODE_SLEEP），
  `retireSession`/手动断开设 false（点亮面板）；④ **必须有三层恢复**（会话结束 /
  UserService 过期自尽前 / 应用下次启动）——否则应用崩溃会把手机留在黑屏
  （电源键按两次能救回，但不能依赖它）。**用户开关**：设置 → 显示 →「连接时真息屏」
  （默认开，`DisplayPrefs.screenOffOnConnect`，键 `screen_off_on_connect`）；关闭后
  只保留暗幕。会话中切换经 `SessionController.applyScreenOffPreference` **立即收放**
  （车机屏可操作，不必等下次连接）。「显示」面板已套 ScrollView——车机内容区只有
  ~200dp 高，4 段内容不滚动会把最后一段裁掉。
- **锁屏推流的正路：VirtualDeviceManager 路线已落地（0.1.28.56，2026-11；DiPlay 台链路
  通路已真机打通，真休眠核心场景待验证）**：
  legacy flags 挡不住「按电源键真休眠」的窗口表面剥离（2026-10-23 记录），per-display
  亮屏锁（0.1.28.10）只覆盖一部分。走 **VirtualDeviceManager 官方路线**（Android 14+，
  伴侣关联 + 虚拟设备），在 **UserService（shell uid）内闭环**实现：
  ① `ICarInput.setCompanionDevice(btMac)` 每次建屏前传入链路对端 MAC
  （`BtSessionGate.lastPeerAddress`，client/server 链路建立时写入；空 = 走 legacy）；
  ② `VdmSession.ensure()`：反射 `ICompanionDeviceManager.getAssociations("com.android.shell",
  userId)` 按 MAC+profile 复用关联，无则 ProcessBuilder 跑 `cmd companiondevice
  associate`（**位置形** `associate <user> com.android.shell <mac> <profile> false`——
  荣耀 SDK 37 实测可用；见下方命令面坑）+ 轮询 20×100ms 确认 + `role
  get-role-holders/add-role-holder` 兜底；同 MAC 但 profile 不同的既有用户关联
  **绝不复用，直接失败降级**；③ `VirtualDeviceParams.Builder setLockState(...)` +
  `VirtualDeviceManager(IVirtualDeviceManager$Stub.asInterface(ServiceManager.getService(
  "virtualdevice")), context).createVirtualDevice(assocId, params)`；④ `createDisplay`
  入口 VDM 优先：受信屏 flags 候选 `1355→1354`（1354=实测可用组合
  PRESENTATION|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|DESTROY_CONTENT_ON_REMOVAL|TRUSTED；
  **首选多带 PUBLIC**——VDM 屏默认私有，私有屏拒绝第三方 IME 窗口是 2026-10-17 定谳的
  键盘坑；专属虚拟设备 IME 是后续可选项，未做），dock 屏首选 **1354（带 TRUSTED）**：
  **本 ROM 上不在虚拟设备 display group 的 VDM 屏，锁屏后合成渲染会冻结**——窗口表面
  活着（mHasSurface=true）、活动 RESUMED、显示电源 ON、注入派发成功（`input -d` 点
  dock 图标能 LAUNCH），但画面停更成纯色条 → 用户视角即「锁屏后 dock 点不动」
  （2026-11-06 真机定谳；曾试过非受信 + OWN_DISPLAY_GROUP + ALWAYS_UNLOCKED = 6474：
  keyguard token 消失了但渲染照样冻结；DockActivity `am start -S` 重建也不能救）。
  dock 带 TRUSTED 进设备组后与 grid/appHi/map 同待遇，锁屏中时钟照走、`topResumed`。
  代价是第三方任务理论上可逃上 dock——dock 只承载自有 DockActivity，逃逸源基本不存在，
  实测未见。降级候选保留非受信 6474 组合（ROM 拒绝受信 dock 时）。受信屏 flags 候选
  `1355→1354` 的 PUBLIC 首选仅限 app/appHi/map（dock 无输入场景不加 PUBLIC），读回
  displayGroupId 只作日志；建屏后照旧进 `virtualDisplays`
  map + `keepDisplayAwake` + 受信屏 `applyImePolicyLocal`。⑤ `ICarInput.closeVirtualDevice()`
  在 `VirtualLauncher.detach` 与建屏失败回退分支收口：余屏释放 → device.close →
  **仅本次创建的关联才 disassociate**（复用的关联原样保留）→ role 丢失时回填。
  降级链：SDK<34 / 无 MAC / 关联失败 / createVirtualDevice 失败 / flags 全灭 → 逐屏回
  legacy（一行未改）。DiPlay 台实测（2026-11-06 真机验证）：四屏全部建在虚拟设备上、
  触摸注入/keepAlive/地图启动全链路正常；**推流中按电源键真休眠 1 分钟，帧/音频/触摸/
  keepAlive 全程持续，唤醒无缝恢复**（此前真休眠必冻结的根场景已消）；
  **锁屏可点性（2026-11-06 真机定谳，三层机制层层剥离）**：① keyguard token（无
  ALWAYS_UNLOCKED 豁免的屏会被掐停活动）——受信 VDM 屏系统自动豁免，已随 dock 升级
  受信解决；② Display-off token（面板超时 ~6s 后落下）不随 `wakeUp`（只拉回电源
  state）释放，须走 `IDisplayManager.requestDisplayPower(displayId, 2)`（经
  DisplayPowerController 状态机 token 才被摘）；③ **合成渲染冻结**（终极根因）：
  不在设备 display group 的 VDM 屏，锁屏后即使活动 RESUMED/电源 ON/token 全清，
  画面也不更新（dock 条变纯色）——解法 = dock 带 TRUSTED 进设备组（见上文 dock flags）。
  **SCREEN_OFF 广播在本 ROM 的 UserService 进程收不到**（只有注册行、历次无触发行）
  ——补唤醒的兜底是 `startDisplayStateWatchdog`（持锁期间 3s 轮询 `Display.state`，
  OFF/DOZE 即补踢 requestDisplayPower+wakeUp），不要依赖广播路径。
  验证手段（锁屏中）：`dumpsys window displays` 各 Display 的 mSleeping/mAllSleepTokens、
  `dumpsys activity activities` 的 DockActivity isSleeping/topResumedActivity、
  **K40 测试台 screencap 看 dock 条是否仍在渲染**（时钟走秒 = 合成活着；桌面/网格
  壁纸可用于视觉比对）。
  断开收口的 closeVirtualDevice 释放路径已接（点「断开」看 `carinput_svc.log` 的
  释放/收口行）。
  **荣耀 Magic7 Pro（SDK 37）ROM 特有事实（2026-11-06 实测定谳）**：
  ① `cmd companiondevice` **命令面大面积残废**：位置形 `associate` 可用；**旗标形
  `--mac-address`（含 `=value` 形）解析全废**——抛「not a valid MAC address」**却仍会
  用本机 BT MAC 创建一条垃圾关联**（危险：静默污染 CDM）；`disassociate` 无论
  `--id`/`--mac-address`/无冒号格式**全部解析失败**，清理只能走 binder 反射
  （`ICompanionDeviceManager.disassociate(int)`，旧签名 `(String,String)` 兜底）。
  ② 常量名不是 AOSP 文档的 `LOCK_STATE_UNLOCKED` 而是 **`LOCK_STATE_ALWAYS_UNLOCKED=1`**
  ——枚举 int 常量按候选名匹配（`VirtualDeviceRoute` 之外在 ensureDevice 里做），枚举
  结果全量落日志（`VDM: VirtualDeviceParams 常量 …`）供判读。
  ③ **`VirtualDeviceManager` 构造必须传自定义 AttributionSource 的 Context 包装**
  （对照参考实现 k5——原以为是 belt-and-braces，实测是必需品）：createPackageContext
  得到的 shell 包 Context `getAttributionSource()` 返回 null，直接 NPE
  （`CarInputServiceImpl.AttributionContext`：`AttributionSource.Builder(uid)
  .setPackageName("com.android.shell")`）。**排障铁律：VDM 失败日志必须根因解包再打**
  （InvocationTargetException.message 恒 null，只打 message 会把真因吞成空串）。
  ④ CDM 关联自带 role 授予：`associate` 带 app-streaming profile 后
  `role get-role-holders` 立即可见 com.android.shell，无需手动 add。
  **com.android.shell 名下的 CDM 关联是本应用的「命名空间」**（只能由 shell uid 创建）：
  ensure 开头的清道夫按「profile 匹配但 MAC≠当前目标 → 清除」处理换车残留与命令面
  bug 垃圾，其它 profile 只记日志不动。
  **判读日志**：`/data/local/tmp/carinput_svc.log` 搜 `VDM:`（关联 id、lockState、
  displayGroupId、flags 降级原因）；应用侧 `setCompanionDevice(...)` 行。
  验证场景：推流中按电源键 → 车机持续出帧；断开 → 本次创建的关联已清、复用的留存。
  UserService 进程死亡时虚拟设备/屏随进程回收，关联持久化留待复用——非泄漏。
- **车机 HID：方向盘媒体键 + iDrive 旋钮**（2026-10-23，详见 `docs/HID-KNOB.md`）：
  `/info` 的 hidDevices **已遍历全部 uuid 发 hidSetInputMode**——不要改成按 uuid/名称猜设备
  （奥迪发 `"0"/"1"`、DiPlay 发整数），**描述符才是唯一稳定信号**。Consumer 页(0x0C) →
  `ConsumerKeys`（Array/Variable 两种语义分开解，混用会一个键都点不出）→
  `AudioManager.dispatchMediaKeyEvent` 全局媒体键；Generic Desktop 页(0x01) 的
  Wheel(0x38)/Hat(0x39) → `HidNavigation` → `ICarInput.keyEvent(displayId, DPAD_*)`
  注入虚拟屏（KeyEvent 也走 `setDisplayId`；失败回退 `input -d`）。**DPAD 不能全局分发**
  （会落到手机主屏），目标屏取 `VirtualLauncher.activeAppDisplayId()`。焦点高亮 = 网格单元
  `isFocusable=true` + `foreground=focus_ring`（foreground 不覆盖 background）；
  **dock 是独立 display，DPAD 不跨屏**。键位校准开关：设置 → 显示 →「HID 原始日志」
  （`DisplayPrefs.hidRawLog` → `HidNavigation.rawLogEnabled`，日志行 `HID-RAW: uuid=… bytes=…`）。
- **回退（应用侧 PUBLIC 屏）形态也必须建地图屏**（2026-10-20 华为 Mate30Pro/EMUI12
  实测）：EMUI 对 shell 侧 `createVirtualDisplay` **静默返回 null**（不抛异常）——受信
  创建整体失败落回退分支，而回退分支历史上只建 dock/app/appHi 三屏、无地图，仪表盘
  永远没有地图 tile（日志特征：`三屏创建(回退)` + 每轮 `地图屏未创建`）。修复后回退
  分支同样建地图屏；**resize 分流**：受信形态走 shell `svc.resizeDisplay`，回退形态
  必须用应用侧 `VirtualDisplay.resize()`（shell 对他人创建的 display 无 resize 权限，
  token 属主校验）。诊断约定：受信创建失败要逐屏留痕（哪块屏 -1），不能只留「回退」。
- **UserService 虚拟屏跨版本残留：三层收口**（2026-10-16 定谳，「重启后检测到残留屏、
  点清理却释放 0/N」根因）：Shizuku 按「组件+进程名+版本(APK lastUpdateTime)」复用常驻
  shell 进程——重装 APK 起新进程，旧进程 `am force-stop` 杀不掉（实测）、Shizuku 也不
  回收；而虚拟屏**只有创建它的进程能释放**（`releaseDisplay` 只查本进程 map，跨进程返回
  false）。判定进程版本看 `/proc/<pid>/maps` 的 base.apk 路径（`==deleted==` 即旧版本）。
  三层收口（缺一有洞）：① `CarInputServiceImpl` 自清——周期性比对 lastUpdateTime，
  过期自尽（重装后 ≤15s 旧进程退出、屏随之释放，实测 5s）；② `CarInputClient.
  reapStaleUserServices`——绑定成功即枚举 `:car_input` 杀掉非当前绑定 pid（覆盖无自清
  逻辑的历史旧进程，实测「已回收 1/1」）；③ 启动检测到残留先自动清理一轮，清不掉的
  才弹窗。**今后任何「进程持有的资源」（虚拟屏等）都必须考虑跨版本残留的释放路径**：
  进程回收是唯一兜底，`force-stop` 不可依赖。
- **启动残留清理必须与「强制横屏复原」同条件触发**（2026-10-16）：`AppOrientationCompat.
  resetAll` 挂在「检测到残留屏」这一条件上（`MainActivity.refreshShellState` 的
  startupCheck 分支）——残留屏是「会话异常退出」的判据，会话末未执行的复原与它同源。
  两条约束：① 复原必须在残留清理**之前**执行（清理会让残留清单变空）；② 预绑定
  UserService（会触发旧进程回收）必须放在残留扫描**之后**，否则回收先让屏消失 →
  残留清单变空 → 复原被跳过（2026-10-16 差点引入）。测试方式：写
  `shared_prefs/app_orientation.xml` 造 `force_<pkg>=true`，建会话后 force-stop 应用，
  重启应见 `ORIENTATION: 残留会话的横屏覆盖已复原 N 个`。
- **系统侧 compat 覆盖的读法（应用横屏面板）**：事实源是 `dumpsys platform_compat`
  （该 ROM 无 `cmd platform_compat`；全量 30ms/36KB，`grep` 到两个 ChangeId 后约 3KB，
  面板打开时查一次、行绑定只做内存查表，绝不可每行一次）。**必须同时解析
  `packageOverrides` 与 `rawOverrides`**：已卸载应用的覆盖只存在于 rawOverrides——
  packageOverrides 按「已安装包」解析，包不存在时条目消失（2026-10-16 实测：给未安装
  包写的覆盖只在 rawOverrides 可见，只读前者会让「列表外残留」横幅永远空着）。
  rawOverrides 里还有 `force_xxx/组件` 形式的历史脏键（旧版本偏好键格式泄漏），
  一并按「列表外残留」清理。UI 三层：行内小字（勾选开+系统开=灰字正常；勾选关+系统开=
  **红色**「系统残留·点击清除」，弹窗提示会重启应用）、面板顶部列表外残留横幅（批量清）、
  会话期 applyAll/resetAll 常规收口。
- **`am compat enable/disable` 会立即杀掉目标应用**（兼容覆盖只能在新进程生效；
  2026-10-15 实测：执行后 pid 当场消失，用户返回时按新配置重启）。给「应用横屏」
  这类开关做交互时不要再设计「查活/询问是否重启」流程——问出问题时应用已经被
  系统杀了，pidof 是在与击杀竞态（提示时对时错）。会话建立时的 `applyAll` 批量
  写入同样会杀掉正在运行的名单内应用（含手机侧实例），属已知代价。
- **「强制横屏」需要 Android 14+（AOSP 标准 ChangeId，SDK 门槛）**（2026-10-18 定谳，
  「车机设置→应用横屏」勾选报「写入失败」的根因）：两个 ChangeId
  （`265451093` OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR /
  `265464455` OVERRIDE_ANY_ORIENTATION）**是 AOSP 标准项，自 Android 14 / SDK 34
  起定义**在 `android.content.pm.ActivityInfo`（`@ChangeId @Disabled @Overridable`），
  由 `LetterboxUiController` 消费；Android 16 起重构到
  `AppCompatOrientationOverrides`。**Android 13 及更早的 ActivityInfo 里一个
  orientation 类 ChangeId 都没有**（实测 A13=0 个、A14=9 个）——这是硬版本门槛。
  **实测对照（同一操作、同一个包 `com.autonavi.minimap`）**：
  - 荣耀 Magic7 Pro（Android 17 / SDK 37）：注册 392 个 ChangeId，含这两个 →
    写入成功，`packageOverrides` 里可见 `com.autonavi.minimap=true`；
  - 红米 K40（Android 13 / SDK 33）：只注册 173 个，两个都不在表内 → 必失败。
  **与 Shizuku 无关**（adb shell 与 UserService 同为 uid 2000，结果一致），
  换 Shizuku 版本或重试都无效。
  次要因素：即便 ChangeId 已注册，user build 也只允许改 debuggable 应用
  （`Cannot override a change on a non-debuggable app and user build.`）。
  另注意「给未安装的包写入会假成功」（只落 rawOverrides、不做 debuggable 校验），
  所以**用包名试探得出的结论会自相矛盾**，必须以 `dumpsys` 的 name 字段为准。
  代码侧：`AppOrientationCompat.MIN_SUPPORTED_SDK = 34` 先做零成本版本判定，
  SDK ≥ 34 再用 `changeIdsRegistered()` 跑一次 dumpsys 复核（约 30ms，进程内缓存，
  两个 ID 必须**各自**带 `name=`——只注册一个时用整段 `grep name=` 会误判）；
  `WriteResult.UNSUPPORTED`（永久：偏好**不落盘**，否则下次连接白跑 applyAll
  且 UI 与系统脱节）vs `FAILED`（Shizuku 临时故障，保留用户意愿待重试）；
  **写入入队点在配对起步（端点处理）而非 attach**（2026-10-23）：`am compat enable`
  会杀名单内应用，必须先于任何应用启动完成——配对阶段有几十秒空窗让写入自然排空，
  attach 只 `awaitPending()` drain（~0ms）。别在 attach 里同步跑 applyAll：屏幕口
  keepAlive 帧自 attach 返回后才起发，attach 每阻塞 1s 都吃掉「RECORD→首条
  keepAlive」的 ~10s 拆链预算；`awaitPending` 超时（6s）**必须留日志**——静默失效
  会让「先写覆盖再拉应用」的保证悄悄作废；
  `applyAll` 探测失败即整体跳过，不再逐应用空跑 2×4s 的 shell 往返；
  面板顶部显示黄色横幅并整列置灰。
  **查证方法教训**：gitiles 的 `?format=TEXT` 单文件抓取失败时会返回空/错误页，
  grep 不到不代表不存在——要校验字节数，或直接下 `+archive/.../<dir>.tar.gz`
  整目录再搜（本结论最初就是被一次静默失败的抓取误导成「OEM 定制项」）。
- **应用先运行、后写入覆盖 → 旧窗口仍 letterbox（需手动重启）**（2026-10-18 真机定谳）：
  `am compat enable` 确实杀掉进程，但系统恢复任务时 ActivityRecord 可能仍按**旧配置**
  重建（`displayChangedWithoutRestart=true`），表现为 `overrideOrientation=PORTRAIT`、
  `isLetterboxRunning=true`、`letterboxReason=FIXED_ORIENTATION`，而
  `platform_compat` 里该包明明是 `true`。只有 force-stop 后重新 `am start` 才生效
  （实测重启后同一屏变 `NOSENSOR`、`areBoundsLetterboxed=false`）。
  **确定性复现（不用跟任务时序碰运气）**：`am compat enable/disable --no-kill ...`
  写覆盖但不杀进程，此时把应用拉到虚拟屏就是纯净的「旧窗口 + 新覆盖」态。
  `--no-kill` 走 `setOverridesForTest`，只用于复现/排查，产品路径仍用常规写入。
  现行处理在 `LetterboxHintController`：事件驱动探测（连接建立/应用启动/地图全屏/重启后）
  → 解析 `dumpsys activity activities` 的 `bounds=`（内窗）与 `mMaxBounds=`（外框）
  算黑边 → 黑边够宽才在**左侧**画提示卡（`OverlayCard` 走合成器 2D 纹理层，
  左侧不够宽退右侧，两侧都放不下就不显示）→ 「强制重启」按钮 force-stop + 原屏重启。
  **补写自愈**：偏好开着但系统侧覆盖缺失时（会话写入失败/被外部清掉），先补写覆盖
  并自动把应用拉回原屏（`am compat` 会杀进程，补写后不重启就是把用户打回桌面还不给
  提示——2026-10-18 头条实测踩到）；重启后仍竖屏才给「仍然竖屏」终态卡。
  探测必须传 `displayIdHint`（launchApp/地图启动确知的目标屏）：不传时按合成器活动屏
  推断，而切屏要等目标屏出首帧（最长 2s），探测早于切换完成会看错屏、读空结果。
- **仪表盘分屏拖拽（2026-10-22 落地）的四个跨会话结论**：
  ① **视觉与触达分层**：中缝视觉宽（`DASH_GAP_PX=24`）与把手触达宽
  （`DASH_HANDLE_TOUCH_PX=60`）是两个常量——把手/粘性路由域比缝宽、两侧压在
  卡片上（地图侧溢出段被 tile 盖住**不可见但可触摸**：触摸路由与 GL 合成本就是
  两层）。一体宽 44px 的首版被用户反馈「中缝太宽」。
  ② **拖动中只改绘制矩形、松手才 resize**：composer `tileEdgeOverride` 只改 SPLIT
  目标矩形（纹理整幅 UV 拉伸），commit 才走 `resizeMapDisplay`（真实 resize 触发
  地图应用配置变更）。**限位钳制必须按比例域换算边界**——按位置域 [边距, 边距+可用]
  钳等于允许比例 [0,1]，用户实测能拉到 0。
  ③ **快拖防断流要粘性路由**：把手落后手指时坐标会越出命中域，TouchRouter 按坐标
  逐帧改判会跨屏重注（掐断拖拽+误触地图）；按下命中把手条即整流固定路由应用屏
  （`dashHandleHit` + `splitHandlePressed`，同 overlay 卡手势独占模式）。
  ④ 车机虚拟屏持有焦点时，**`am start` 不带 `--display` 会落到聚焦的虚拟屏**
  （用户车机画面闪出调试 UI 实测）——会话中启动手机侧 Activity 必须显式
  `--display 0`；无线 adb 的**流式安装协议会静默挂起**（push 76MB/s 证明传输无碍），
  大包改走 `adb push + pm install`。
  几何推导集中在 `VirtualLauncher.dashSplitGeom` 纯函数（`DashSplitMathTest` 钉死
  四方一致性），改分屏相关代码勿在四处重新裸算。
- **仪表盘右侧卡片 = 固定应用虚拟屏（2026-11-07 落地，0.1.28.63）**：设置→「仪表盘」
  （原「默认地图」改名）可把右侧音乐卡替换为「固定应用」——独立虚拟屏
  **CarPlayPinned**（密度=手机侧，px=卡片矩形×手机密度/DENSITY_BASELINE，复用
  `mapDisplayGeometry` 换算），GL 合成进原音乐卡区域，音乐卡 View 隐藏
  （GridActivity `pinnedCardActive` 闸）。跨会话结论：
  ① **卡片矩形三方一致契约**：`VirtualLauncher.pinnedCardRect` 纯函数（应用区坐标
  系，从 `dashSplitGeom.otherStart` 到末端留 `CARD_MARGIN_PX`）⇄ composer 绘制
  矩形（横=tileEdgeOverride+DASH_GAP_PX、竖=handleEdge+DASH_GAP_PX 起）⇄
  GridActivity layoutSplit 音乐卡 margin——三者必须同步改（DashSplitMathTest 钉死）。
  ② **拖拽拉伸 + 松手 resize 与地图 tile 完全同构**：preview 只改绘制矩形，
  `commitDashSplit` 同时 resize 地图屏与固定屏（`resizePinnedDisplay`）。
  ③ **点击卡片 = 触摸直注固定屏**（活交互面，TouchRouter `pinnedCardRouteOrigin`
  路由，屏内坐标×`pinnedDisplayScale`）；只有**冻结态**（应用被拉到手机侧）触摸才
  拦截为拉回（`pinnedPullPressed` 整条手势消费，同 overlay 卡独占模式）。
  ④ **拉回 = remove + 重建**：本 ROM `am start --display X` 对既有任务只带回前台
  **不迁移**（2026-10-10 定谳），所以 `pullBackPinnedApp` =
  `am stack remove` 其它屏上的同包任务（手机侧窗口消失，用户确认此语义）+
  `-f 0x18000000` 在固定屏新建实例——应用内状态重置（同 am compat 杀应用的既有
  代价）。**冻结看门狗只标记不自动拉回**（pinned-watchdog 线程 5s dumpsys 轮询，
  固定屏无该包任务即 `pinnedFrozen=true`）；拉回触发点 = 触摸卡片 / 回仪表盘
  （syncDashboard(true)）/ 点桌面固定应用图标。
  ⑤ **全屏形态**：`TileLayout.PINNED_FULLSCREEN`（与地图全屏互斥）；从桌面/dock
  点固定应用 → `launchApp` 顶部拦截 → `setPinnedFullscreen(true)`；⌂ 退出回仪表盘
  （卡片形式）；`activeAppDisplayId` 全屏时切到固定屏（HID DPAD 跟随）。
  ⑥ **会话中切换模式实时生效的关键**：composer **恒建**第 4 路输入（音乐模式也建，
  `pinnedSurface()` 常在）——GL 线程进入合成循环后无法再建输入，设置面板切「音乐⇄
  指定应用」全靠它现场建屏/释放（`applyDashCardChanged`）。同屏换应用 = 清旧包任务
  （互斥）+ 新建实例。
  ⑦ **应用单选弹窗**：列表 = 全部可启动应用**排除地图应用与自己**（同包在固定屏与
  地图屏互抢任务）；强制深色主题（`ContextThemeWrapper(Theme_AppCompat)`——面板宿主
  在车机屏可能是 DayNight 浅色形态）；行样式复用 item_map_option。
  ⑧ 所选应用被卸载 → `resolveDashCard` 返回 null 回退音乐卡片（面板显示音乐选中，
  存储保留，重装后下会话自动恢复固定模式）；DashCardPrefsTest 钉死。
  ⑨ **装机反馈四修复（2026-11-07）**：车机设置菜单**按位置**取面板曾整体错位一档
  （0.1.28.62 删超采样菜单后 `SettingsMenus` id 不再连续 0,2,3,4,5,6）→ 改视图→菜单
  id 显式映射；固定应用全屏 ⌂ 失效根因 = TouchRouter 固定屏路由**漏 `!inDock` 闸**
  （全屏命中域=应用区全部，dock 触摸被吞）——新增任何「大范围命中域」路由都必须带
  dock 区闸 + 命中函数自身校验（双保险）；dock 图标只在显式点击时登记 runningApps
  （自动拉起不上 dock，对齐地图 ensureMapTask）。
  ⑩ **地图屏拉回与固定应用完全同构（0.1.28.66）**：display-watchdog 一个线程同管
  `pinnedFrozen`/`mapFrozen`（5s dumpsys 只标记）；`pullBackApp` 公共路径（目标屏有
  任务=解除标记幂等；无任务=清他屏任务），地图走 `startMapApp` 候选链；TouchRouter
  `mapPullPressed` 与 pinned 同构，命中几何 `mapTileHit` 与路由分支同源。**触发点**
  一致：冻结态点卡片/回仪表盘（syncDashboard 统一调两个 pullBack）/点桌面图标。
  历史注：2026-10-10 曾移除「任务看护自动拉回」——本特性是它的受控复活（只在车机侧
  明确交互时拉回，绝不自动）。
  ⑪ **设置已统一为单 Activity 响应式（0.1.28.64）**：`SettingsActivity` 按宿主屏
  configuration（宽≥600dp 且高≥400dp）分 wide 双栏 / narrow（列表⇄详情两层叠放）；
  PhoneSettingsActivity/SettingsContentActivity/竖屏车机转发 hack 已删除（3 类→1 类，
  「映射分叉」类 bug 根除）。坑：主题改继承不透明的 `Theme.CarPlaySender`——原
  Launcher 派生透明窗口在手机侧会透出桌面；窄屏返回语义分形态（详情=回列表，
  列表=关页）；narrow 用 onSaveInstanceState 保形态与详情态。
  ⑫ **「应用横屏」并入「管理桌面应用」（0.1.28.153）**：同一应用列表每行双勾选框
  （左「横屏」= 强制横屏覆盖、右「桌面」= 显示到车机桌面，行点击=桌面、横屏框自己
  接管点击），列表上方表头两列文案与勾选框列**像素对齐**（对齐推导写死在
  fragment_panel_desktop.xml 注释：列表 paddingRight 26+行 margin/padding 12=38 等）。
  原独立「应用横屏」菜单/面板/两布局/旋转图标全删；`SettingsMenus.ORIENTATION(=2)`
  const 删除、id 不复用（视图→id 显式映射，天然免疫错位）。合并带来的新坑：
  **桌面勾选触发整表 notifyDataSetChanged（行分组按桌面勾选重排）**，横屏写入是异步
  （2 次 shell 最坏 8s），回调回来时 Holder 可能已复用绑定到别的应用——回调里必须
  校验 `entry.component` 身份再刷行 UI（面板级 `systemOn` 缓存则无条件更新）；
  能力探测不支持时只灰「横屏」列（表头+各行勾选框 alpha，bind 按 supported 判定），
  桌面列不受影响。
- **意外断开保留虚拟屏 + 重连复用（2026-11-10 落地，0.1.28.91）**：意外断开
  （车机软件拆链/WiFi 断 = onEnded；熄火/出范围 = AUTO_STOP 即
  `disconnect(userInitiated=false)`）→ 虚拟屏不动进保留态，自动重连成功后
  **复用**（新编码 surface 经新 AIDL `setDisplaySurface` 接回旧屏——屏与屏上任务/
  Activity 全程不动，应用/导航状态连续）；每 60s 一个重试窗口，连续 N 窗（设置→显示
  「断开保留虚拟屏」，默认 3）未恢复即销毁。跨会话结论：
  ① **关键事实：onEnded 路径本来就不 detach**（VirtualLauncher/composer/屏全活着，
  composer 继续心跳合成写失效编码 surface 静默失败）——「断开即毁」的只有 AUTO_STOP
  与回连瞬间 `onCarPlayEndpoint` 起步的 `retireSession()`。改造点集中在后者
  （`retireSession(keepDisplays)`）与 attach 复用分支，不是断开瞬间。
  ② **复用技术底座**：屏实例存 UserService `virtualDisplays` map（VDM/legacy 两路线
  统一，按 displayId 反查调 `VirtualDisplay.setSurface`）；per-display 保活锁保留不动；
  composer 必须每轮新建（编码 surface 每轮新建）——复用时先停旧 composer（GL 线程
  退出才释放旧输入 surface）再建新的，然后逐屏 `obtain()`：保留 id 的 setDisplaySurface
  成功=复用，失败（屏被系统回收）=回退新建自愈。回退形态（应用侧屏）同样
  `VirtualDisplay.setSurface` 复用。
  ③ **复用前置判定 = 流画布几何完全一致**（attach 参数 totalW/H == markRetained 时
  的基线）：用户改了流分辨率偏好 → 放弃复用正常重建（保守正确）。**跳过项**：stale
  同名屏清理（那是「按名无差别击杀」，会杀掉保留屏）、applyIgnoreOrientationRequest
  重发（策略还挂着，省 8 次 shell 往返）、`resetAllAsync`（am compat disable 杀名单内
  应用——保留的意义就是恢复状态，杀了等于白留）。**保留分支补做收口**：audioSender
  （submix 挂着=手机媒体持续静音）与 currentClient（keepAlive socket 已死）。
  ④ **startOn dock/grid 在复用路径照常执行**（幂等自愈：任务在=带回前台；系统内存
  压力杀了 Activity 也能借这次拉起）；固定应用**不** launchPinnedAppFresh（MULTIPLE_TASK
  会叠实例）——查任务在才 `am start` 带回前台，不在才新建。
  ⑤ **三条销毁语义**：手动断开/取消连接=立即销毁（不进保留态）；保留期 N×60s 超时
  =销毁（时间窗计数，用户选定——必然推进，无「永不销毁」悬空态）；几何不一致=放弃
  复用销毁重建。防重：onEnded 与 AUTO_STOP 对同一会话双触发——enterDisplayRetention
  用 CAS 幂等（已在保留态返回 true，disconnect 据此不再销毁）。
  ⑥ **已知边界**：app 进程死亡保留态丢失 → 下次启动走既有残留清理兜底；复用时
  composer 输入 buffer 尺寸必须=屏实际分辨率（attach 几何一致判定天然保证）。
  RetentionCounterTest 钉计数判定。
  ⑦ **两个装机日志定谳的杀手（2026-11-12，0.1.28.100 修复）**：
  ① **VideoSender.stop() 的拆卸也触发 onEnded**——手动断开会同步走
     retireSession→stop→onEnded，onEnded 在 detach 清 id 前的窗口里跑进来就会
     **误进保留态**（保留标记/计时器泄漏到下一轮：retireSession 误 keep=true +
     计时器 detach 可能误杀活会话）。修复：suppressRetentionOnce 一次性旗标——
     disconnect(userInitiated=true)/cancelConnecting 在 retireSession 前置位，
     onEnded 消费后走非保留分支；手动断开同时清残留保留态/计时器；保留期 tick
     加 `videoSender.isRunning` 兜底（绝不销毁活会话）。
  ② **HiddenDisplaySource.attach 的前置 detach 是复用杀手**——复用判定在
     VirtualLauncher.attach 头部，任何包装层抢先 detach 都会先清
     sessionRetained/屏 id，复用永远走不到（真机日志定谳：重连全量重建）。
     已删该行；**新增 attach 包装层时不得在 VirtualLauncher.attach 之前碰
     VirtualLauncher 状态**。
  ⑧ cancelConnecting 必须带 `userInitiated = true`（默认 false 是 AUTO_STOP
  语义，会让保留特性在用户取消时误保屏）；冷却其自身已装填同值，重复无害。
  ⑨ **复用后仪表盘卡片不渲染/地图黑（0.1.28.101~105 三刀修复）**：
  第一刀：composer 每轮新建，`tileLayout` 默认 HIDDEN，而保留屏的 UI 状态（正显示
  仪表盘页）挂在 dashboardMode 里、重连流程因 dashboardMode 未变化不会重发
  setTileLayout——attach 尾部按 `pinnedFullscreen/mapFullscreen/dashboardMode`
  恢复合成布局（全新会话 dashboardMode 必为 false，恒 HIDDEN 不受影响）。
  第二刀：**配对起步的 applyAllAsync（am compat）会杀名单内应用**——保留屏上的
  地图/固定应用任务可能已死或逃逸到手机侧，而重连流程（dashboardMode 未变化）
  不会触发 syncDashboard 的对账。修复两层：①复用路径幂等对账——地图
  `pullBackMapApp()`（任务在屏=am start 带回前台重启死亡活动；逃逸=清他屏任务+
  地图屏重建）、固定应用 pinnedTaskAlive 检查分支；②**强制横屏写入改为按需**
  （0.1.28.109）：applyAll 先查 `systemEnabled()`（一次 dumpsys），系统侧已生效的
  应用跳过写入——`am compat enable` 对已生效覆盖同样杀进程，保留重连轮次上
  名单应用正带着覆盖运行。半残状态（只开其一）同样跳过（与面板「取或」清理
  语义一致）。曾加过「保留屏重绘微抖」——resize 抖动逼静止应用出帧，0.1.28.102
  实测引入「地图发暗冻结」副作用（±2px 两次重排打断地图过渡帧）且被对账机制
  覆盖，0.1.28.106 移除。
  **真机端到端（荣耀+DiPlay 台，0.1.28.102 验证）**：意外断开（杀接收端，仪表盘态）→
  保留态 + 四屏保留 → 重启接收端 → 四屏原 id 复用 + 合成布局恢复 SPLIT + 重绘微抖 →
  **地图 tile 与固定应用卡自动渲染**（无手动页面切换）；手动断开 → 立即销毁且零
  保留日志。
- **8015 不切屏根因 = 半移交态：BT 不拆 + 探测不应答（2026-10-24/25 定谳，§13c）**：
  三方对照（同车同手机）——0.1.0 无 AirPlay iAP2 链+BT 保持 → 出画面 10s 拆；真 iPhone =
  AirPlay 链上**逐条应答车机 marker**、**94ms 拆 BT（首帧前 83ms）**、车机随后自发 SYN →
  稳定；0.1.37 链建成（我方 FF5A 升级**强行发起**，非车机应答 DETECT）但 BT 全程保持、
  探测零应答 → **车机 HMI 卡半移交态**（入口置灰直到重启）。模型：车机只在「WiFi 侧
  iAP2 会话存在」时进入移交态，完成条件=「探测被应答+BT 释放」。0.1.39 落地：
  `armMib3` 发 DETECT 后**立即** `detachBtForMib3Handoff`（RECORD 前，保证 BT 先于首帧
  落下；不得挂链 NORMAL——那里与 videoSender.isRunning 竞态会整场静默跳过）+ 逐条回
  marker（CLASSIC_MIB3）+ onEnded 复位豁免/重启 server/停音频 + AUTO_START 会话中短路。
  两条被推翻：iPhone **每条 marker 都回 DETECT**；「车机 SYN 由 BT DETACH 触发」不成立
  （0.1.37 BT 在挂时链照样建成）——SYN 与 HMI 是两层状态机。
- **屏幕流 VideoConfig 帧头必须带「加密标志 + 画布宽高」**（2026-10-19 奥迪 4851
  实车定谳，`docs/PROTOCOL-NOTES.md` §9g）：车友反馈「app 显示已连接、车机没反应」，
  日志里会话/触摸/心跳全健康、零错误。根因是我们只写了 `body_size`+`opcode`，而
  catplay `ScreenFrame::config` 还写：`small_param[1] |= ScreenFlag::Encrypted(0x04)`
  （**偏移 6**，声明后续帧是密文）、`params[1]`（**偏移 16**）= 宽高两个 **f32 LE**、
  `params[0]`（偏移 8..16）留 0。Audi/DELPHI 按这些头字段建解码器，缺了就**整屏黑但
  会话照旧、触摸照常**；DiPlay/WinPlay 只读 body 与 avcC，**测试台永远发现不了**。
  因此凡是「车机屏黑但 app 显示已连接」都先核 `VIDEO: ✓ VideoConfig … flags=` 这行
  日志（会同时打印头部前 24 字节 hex）。新增屏幕流头字段时同理：先用 catplay
  `screen_frame_ext.rs` 对照，别只对着测试台验证。
- **MIB3 切屏靠 modesChanged 资源重申，不是协商本身**（2026-10-23 双代理对照
  AADisplay 定谳）：082446（0.1.14）协商/MFi/突发/订阅全部成功、会话健康 35s+，
  车机却不切 CarPlay 画面——第二控制会话把车机资源仲裁重置，会话早期那份
  modesChanged 已失效。AADisplay r17 长播验证时序：auth+burst 后 **+1.5s 首发、
  +4/+12/+30s 重申**；声明落在 0xAA00/0xAA05 进行中会与 iAP POST 抢控制通道、
  奥迪 ~12s 死。实现：`VirtualIap2Link.onControlSessionComplete` →
  `SessionController.scheduleModesReclaim`。0.1.12「出画面 3 秒断」= 协商砸进
  刚起步的 HMI，同源不同症。配套两课：**拆链归因守卫**——MIB3 布防了但控制会话
  没完成就被拆 = 链路看门狗形态，严禁记音频档位不兼容（否则 media 通路被误拉黑、
  永远没声）；**sendRawCsmLocked 日志取消息号在 frame[4..5]**（[2..3] 是长度——
  曾把 0xFFFA 打成 0x32 误导排查一整轮）。
- **断开后保留活动应用横屏（2026-11-19，默认开）**：会话结束复原（resetAll）对
  **仍在运行**的应用跳过 `am compat disable`——该命令会立即杀进程，正在导航的应用
  被打断、页面状态全丢（用户实测痛点）。存活判定 = `pidof <pkg>`（exit 0 且输出
  非空，单次几十 ms，名单小逐个查）。开关在杂项设置「断开后保留应用横屏」
  （`DisplayPrefs.keepOrientationForAliveApps`）。一致性链条：活应用跳过复原 →
  系统侧覆盖留存 → 下轮 applyAll 的 `systemEnabled()` 过滤天然幂等（不重复写入
  = 不杀）；面板显式关某应用横屏仍立即写入（用户明确意图，杀进程代价照旧）；
  已退出的应用照常复原（顺手清系统侧防卸载残留）。三个复原入口（disconnect 手动
  / onEnded 非保留 / 启动残留清理）都走 resetAll 单点，gate 加在 resetAll 内
  一处生效。副作用：桌面面板「已开启横屏」状态小字不再断言「会话期间生效」。
- **虚拟屏去掉 DESTROY_CONTENT_ON_REMOVAL（2026-11-19）**：该 flag 语义=「屏移除时
  销毁内容」——手动断开释放屏的瞬间，屏上第三方任务全部销毁、无常驻服务的进程随即
  被系统回收（「断开后保留应用横屏」的 pidof 查到的已是尸体，功能对屏上应用形同
  虚设）。移除后（AOSP 语义）系统把屏上任务 **reparent 回主屏 display 0**：应用
  存活、页面状态保留（Activity 按新屏配置重建一次，进程/导航引擎不中断）。配套与
  注意：① 自有启动器界面（Grid/Dock/Settings）会在释放屏前被 detach 主动
  finishAndRemoveTask（否则 CarDeck 桌面会被搬上手机全屏弹出）——三者各持
  `current` 静态引用（注意 GridActivity/SettingsActivity 原有 private companion
  要改为非私有后合并）；② reparent 后只有栈顶一个任务成为手机前台，其余进后台/
  最近任务（不会「弹多个应用」）；③ 该 flag 只在屏移除时起作用，会话内零影响；
  ④ 意外断开保留虚拟屏（不释放屏）不受影响；保留期超时销毁时第三方应用也从
  「死亡」变「挪回手机」。flags 候选 1355/1354 → **1099/1098**（VirtualDeviceRouteTest
  钉死 + 防回归断言不含 1<<8）；legacy 路径本来就没带这个 flag。
- **几何变化的复用 = 逐屏 resize，不再重建（2026-11-19）**：复用判定放宽为「屏 id
  有效即复用」，attach 头的流画布逐像素比对删除；`obtain()` 里保留屏实际分辨率
  （Display.getRealSize）≠ 本次计算值时先 `svc.resizeDisplay(id, w, h, dpi)` 再接
  surface（机制同会话中地图屏全屏⇄分屏），resize/接 surface 失败才逐屏回退新建。
  横竖屏切换/换车机画布不同/保留期间改密度基准与 dock 宽度，全都走 resize：
  声明了方向自适应的应用（高德/抖音）只收 onConfigurationChanged **不重建
  Activity**（视频接着放），未声明的重建一次但任务/进程不动——优于 reparent
  路径（reparent 必重建）。虚拟屏属于手机不属于接收端：两台车机画布相同则跨
  设备复用照常成立。回退（应用侧 VirtualDisplay）路径未做 resize，几何变化仍走
  setSurface（拉伸）或旧屏失效重建，罕见形态不追。
- **竖屏两 bug（2026-11-19 DiPlay 红米旋转实测）**：① **TouchRouter 竖屏应用分支
  漏乘 activeAppScale**——应用屏密度=手机侧（2026-11-06）后横屏分支补了倍率、竖屏
  dockAtBottom 分支漏了（`localX=px` 直接注画布坐标），车机触摸全落在 ~1/scale 的
  左上区域：点图标无反应/误翻页。历史原因：竖屏支持早于密度改造，当时 scale=1 是
  对的；横屏补倍率时竖屏没人测。地图/固定卡竖屏分支一直有乘（mapScale/pinnedScale）。
  ② **composer 竖屏分支固定卡绘制缺 SPLIT 闸**——`else if (pinnedCardVisible)` 没
  限定布局，FULLSCREEN 时固定卡画在下半屏盖住全屏地图（横屏分支的
  `layout == SPLIT &&` 闸一直都在）。取证方法：adb `input -d <displayId> tap` 直注
  应用屏绕过 TouchRouter——直注正常 = 坐标换算层有问题（应用屏 id 用 dumpsys
  display 查，坐标按截图 app 区占比 × 屏 px 换算）。
- **VDM 屏 flags 仍带 DESTROY_CONTENT_ON_REMOVAL（2026-11-19 待查）**：候选已删但
  dumpsys 仍见该 flag——疑似 VDM 对虚拟设备屏强制补挂（同屏还自动补了
  ALWAYS_UNLOCKED）。注意：DESTROY 在屏释放时的实际行为（销毁 vs reparent）与此
  flag 的关系在本 ROM 上未定谳——断开保留/reparent 存活已实测成立，别急着下结论。
