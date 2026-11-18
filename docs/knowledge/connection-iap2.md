# 连接链路 / iAP2 / 配对 / 心跳

> 2026-11-18 从宿主 `AGENTS.md` 拆出（原 1397 行 / 136KB）。
> 共 37 条，472 行。
>
> BT 与 iAP2 链路、配对、保活与心跳、mDNS、隧道、modesChanged、协议差异与真机对照。
> **改连接相关代码前必读**。含多条「真车定谳」结论，勿凭猜测回退。

---

- **Shizuku 未就绪必须硬拦连接**（2026-10-18 用户实测：手动停掉 Shizuku 后仍能点
  「启动连接」）：隐形屏模式的虚拟屏创建、触摸注入、窗口/显示状态读取全部经 Shizuku
  shell，未就绪时放行只会走到「蓝牙已连、车机黑屏 + 触摸全丢」的半残态。
  拦截点在两条路径上都设了：`ensureShizukuReady`（CTA 前置，排在蓝牙之前）与
  `startConnectionNow` 开头（授权回调等延迟路径的兜底）。三态引导文案由
  `ShizukuGate.guidanceOf` 给出**互不相同**的动作（未装=无跳转只说明；未运行=打开
  Shizuku App；未授权=请求授权并自动续连），因为「未就绪」一句话无法指导用户。
  **连接准备清单把 Shizuku 计为第 1 项**（`readyCount` 现在 x/4），状态行本身可点开引导。
  授权结果经 `Shizuku.OnRequestPermissionResultListener` 驱动：granted 时刷新状态并
  在 `pendingShizukuThenConnect` 置位时自动继续，Activity 销毁必须注销监听。
  提示弹窗统一走 `ui/DarkMessageDialog`——裸 `AlertDialog.Builder.setMessage` 在
  `Theme.AppCompat.DayNight` 下是**浅色**的，与全深色 UI 割裂（同日真机截图暴露）。
  该组件支持 1~2 个操作横排靠右、3 个及以上**竖排等宽**（窄屏横排会挤爆；竖排时次要
  按钮也要上胶囊底，否则只有主按钮是实心整宽、权重失衡）；`Emphasis` 区分主操作
  （实心青）/ 正文按钮 / 危险操作（暗红）；`dismissOnClick=false` 可保留弹窗重试。
  已统一：Shizuku 引导、音频选择、取消连接、断开连接、蓝牙未开启、加入热点、
  残留虚拟屏、悬浮窗权限、横屏残留（列表外横幅 + 行内清除）。
- **上车自动连接（ACL 广播唤醒，2026-10-23，详见 `docs/AUTO-CONNECT.md`）**：
  `bt/CarBluetoothReceiver` 以**清单静态接收器**监听 `ACTION_ACL_CONNECTED/DISCONNECTED`
  ——这两个在[隐式广播例外清单](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
  里，进程被杀也能冷启动。三条硬事实：① **Android 12+ 的后台 FGS 豁免清单里没有
  「蓝牙广播」**，本项目能 `startForegroundService` 是因为持有 `SYSTEM_ALERT_WINDOW`
  （暗幕/触摸探针本来就要，targetSdk 34 只看权限存在）；用户没授悬浮窗时会被
  `ForegroundServiceStartNotAllowedException` 拦下，接收器只记日志、不崩；
  ② FGS 必须用 `connectedDevice` 类型（2 参 `startForeground` 会带上 mediaProjection，
  Android 14 校验 AppOps PROJECT_MEDIA 直接崩）；③ 冷启动进程里必须先
  `SessionController.ensure(applicationContext)` 再 `startConnection(...)`——BT client /
  WiFi 连接器都在 `ensureSessionObjects` 里建，跳过会 NullPointer。
  自动连接**有声音**：靠 `audio/SilentProjectionActivity` 静默领 MediaProjection——
  `appops set <pkg> PROJECT_MEDIA allow` 让 SystemUI 直接返回 RESULT_OK（Tasker 同款，
  该 app-op **持久**，不需要时 `appops set … default` 清），再由 shell `am start` 拉起
  1×1 透明、`exported`、`noHistory` 的跳板（shell 只有 START_ACTIVITIES_FROM_BACKGROUND，
  没有 START_ANY_ACTIVITY，所以必须 exported）。**Android 14 铁律**：拿到授权后先
  `upgradeToProjection()` 把 FGS 类型加上 mediaProjection，才能 `getMediaProjection`；
  且 `onStartCommand` 里重复 `startForeground` 时若 `mediaProjection != null` 必须继续
  带上该类型（否则类型被摘、投影被停）。**开关**：设置 → 显示 →「上车自动连接」
  （`AutoConnectPrefs`，默认开；与「连接时真息屏」同一面板）。
  下车走 `ACTION_AUTO_STOP_SESSION` → `disconnect()`；该 intent 用 `startService` 投递
  （服务在跑时进程已是前台服务态，不受后台启动限制）。
- **取消连接必须全程可中断**：`sessionStarting` 互斥标志的复位依赖配对流程线程
  走到 finally——该线程可能阻塞在不可中断的等待（无超时 connect、mDNS 等待、
  BT 保活读循环），任何一处卡住都会让取消「无效」（英雄卡永久「正在连接」）。
  防御三件套（2026-10-15 实战）：disconnect/取消时主动 detach 关 BT 会话 socket
  （**只在用户终态做，见下条**）、AirPlayClient.cancelled 标志让配对重试循环提前退出、
  cancelConnecting 强制 `sessionStarting.set(false)`（用户显式取消即强制解锁）。
  **新增任何长等待点时必须响应 connectCancelled**，且取消路径要实测「发起后立即取消」场景。
- **连接耗时三大头（奥迪实测，2026-10-18 日志量化）**：从「车机 ctrl 邀请/端点解析」
  到 RECORD 的 4.9s 里，`/info` ~3.0s 是车机侧会话初始化（返回值与车机第二次 ctrl
  邀请同一毫秒到达——它在窗口就绪前不应答，catplay 同样要等，**不可优化**）；
  自家代码可省的两笔已移出关键路径：
  ① `retireSession` 的 `AppOrientationCompat.resetAll`（6 应用 × 2 次 `am compat`
     ≈1.6s，原先同步跑在配对前）；
  ② 虚拟屏 attach 里的 `applyAll`（≈2.0s，原先推迟首帧）。
  两者改为同一个**单线程执行器**异步执行（`applyAllAsync`/`resetAllAsync`，串行保证
  「先复原上一会话、后写入本会话」）。**注意异步化的副作用**：`am compat enable`
  会立即杀目标应用，而桌面图标点击可能在写入完成前发生——`VirtualLauncher.launchApp`
  必须先 `AppOrientationCompat.awaitPending()` 排空队列，否则应用刚启动就被杀。
  另一笔大浪费是 WiFi 绑定超时竞态：旧 `latch.await(5s)` 在手动加入热点场景会在
  onAvailable 到达前 ~116ms 判失败并放弃整轮（真实耗时 20:02:09.352 放弃 →
  20:02:09.467 成功），用户白等一轮 BT 重连（+13s）。现窗口 15s、分片等待可取消；
  `WifiCarPlayConnector` 的 5s 看门狗只提示不改判定。
- **iAP2 接收队列：暂存区与新到帧必须分开**（2026-10-18，日志刷屏活锁根因）：
  `expectOrNull` 等待期间会把非目标帧放进 `pendingFrames`「待后续消费」，而
  `recvFrame` 又优先从 `pendingFrames` 取——旧实现两者共用一个优先队列，同一帧被
  反复取出→打日志→塞回，形成活锁，日志 ~1000 行/秒直到等待超时（触发场景：车机
  bring-up 时 `0x5000 StartNowPlayingUpdates` 与 `0x5703` 相邻到达；跳过 2s
  StartHID 空等后它直接落进下一段等待）。修法：`recvFrame`（通用，优先暂存）与
  `recvNewFrame`（只读新帧）分开，`expectOrNull` 只走后者、并先查暂存区里是否已有
  目标帧；暂存区加 256 条上限。回归测试 `Iap2SenderSessionTest` 在 mock 时序里插入
  迟到的 0x5000——去掉修复即超时失败。另外 `pumpIncomingForever` 对同一消息 ID
  连续到达做了限流（文件日志超 2MB 会被删，刷屏等于丢掉有用上下文）。
- **连接时序：能力广播先行、邀请驱动、不盲等**（2026-10-18，对照 catplay 连接流程）：
  catplay 发送端的顺序是「CarPlayCtrlServer 常驻广播 `_carplay-ctrl._tcp`（每 1s
  force_announce）→ 车机 `GET /ctrl-int/1/connect` 邀请 → 收到邀请立刻建 AirPlay」，
  全程没有固定 sleep。我们旧流程三处盲等，合计每轮白花 5~13s：
  1) ctrl 广播要等 0x5703 才发布（车机发现手机天然晚一步）→ 改为 `startConnection`
     即发布，0x5703/绑网后只做 `ensureForNetwork` 重注册；
  2) 端点交接后固定睡 1.6s「赌邀请已到」→ 改 `awaitCtrlInvite`（邀请计数事件驱动，
     2s 未到补一次 force_announce，4s 兜底；已发现 `_airplay._tcp` 也放行）；
  3) 等 StartHID 2s + 等 0x4301 10s 无条件 → 先读 0x1D01 参数 6（车机声明它可发送的
     消息清单），没声明就跳过（奥迪只声明 0x5703/0xea03，正是这两个空等的来源），
     未给清单时才按保守窗口（0x4301 由 10s 收到 3s）。
  配套：`_airplay._tcp` 发现器常驻（绑网即后台发现，需要时查缓存而非现查重试 3×6s）；
  端点解析移到邀请之后（车机服务常在受邀后才上线）且不再占用网络回调线程；
  WiFi 绑定前先确认 SSID 已是车机热点（`bindCurrentWifi` 只按 transport 请求、
  **不校验 SSID**，切网途中会绑到旧网）；`bindCurrentWifi` 的 release+rebind 会
  解绑进程网络，故绑过同一热点后走快路径、并用锁串行化两条绑定路径。
  **注意**：广播提前后首注册可能赶上 Shizuku 未就绪 → TXT id 写成占位 MAC，
  而车机按该 id 匹配手机（占位 = 永不邀请）。`DeviceIdentity.bluetoothMac(forceRetry)`
  + `CarPlayCtrlAdvertiser.repairPlaceholderMac`（每 2s 重试、成功即重注册）是必需兜底。
- **配对/推流期间绝不能断 BT 控制会话**（2026-10-16 定谳，「连上约 3 秒即断」循环根因）：
  DiPlay 在 WiFi 隧道 iAP2 就绪前（AirPlay 通道从未下发 `disableBluetooth`，
  `handoffRequested=false`）看到 RFCOMM 断开即判 `wireless bring-up failed` →
  `closeWirelessStack` 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），**把已建立甚至
  正在推流的 AirPlay 会话一起拆掉**（日志：DiPlay 侧 `RFCOMM reader FAILED` →
  `bring-up failed`；手机侧约 +0.4s 后 `TUNNEL/EVENT 读取结束 - Connection reset`、
  `推流中止`）。持续关一次就重建栈 30~40s，形成连上→断→重建→再连循环。
  曾经错在 `retireSession()` 里 detach（每轮配对起步都会执行一次）；现在只在
  `disconnect()`（用户断开/取消的终态）detach。**改会话退役逻辑时先确认没有新增
  中途关 RFCOMM 的路径**；`SessionController.kt:876` 的「第二阶段 hack」（配对失败
  后故意断 BT 重试）对 DiPlay 必然触发一次整栈重建，属已知代价。
- **蓝牙只配对、不连接**（2026-10-18，用户实测奥迪 MIB3）：手机与车机处于蓝牙
  **已连接**状态（HFP/A2DP 在线）时，车机**不开放无线 CarPlay 的 iAP2 服务窗口**
  ——表现为 `BT-C: 连接失败（车机未就绪或不在无线配对窗口）` 每 3s 重试，直到系统
  空闲自己断开才连上。真 iPhone 的无线 CarPlay 也只用系统级配对 + iAP2 RFCOMM
  （独立 SDP 服务），声音走 WiFi 的 AirPlay，这些音频/通话配置文件可断。
  实现（`bt/BtProfiles`）：发 iAP2 连接前查目标地址的配置文件状态，有连接就先断开
  （配对成功回调里也断两次：立即 + 1.5s 后，车机侧重连常发生在两者之间）。
  **断开只对「确认提供 iAP2 服务（SDP …caff）或用户选定目标」的设备做**（2026-10-20
  收窄，`Iap2BluetoothClient.awaitIap2Service`）：自动模式候选=全部已配对设备，
  耳机/手表没有该服务、连不上还白被断 HFP/A2DP（设备级断开兜底更会波及其它在线
  设备）。SDP 探测先读系统 uuids 缓存、缺失再 fetchUuidsWithSdp 等 ACTION_UUID
  （2.5s 窗），结论按地址缓存到客户端停止、start() 时重置。
  **注意 API 约束**：`BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)` 是
  **@SystemApi**（编译不可见，运行时只要 `BLUETOOTH_CONNECT`），只能反射调用；
  查询用公开的 `getProfileProxy` + `connectedDevices`（需主 Looper）。**不要**把这段
  挪进 Shizuku UserService（曾试过，不可行）：UserService 里
  `BluetoothManager.getAdapter()` 返回 null（无主 Looper 的应用上下文），查询恒为
  「未连接」，反而掩盖真实状态。诊断日志：`BT-PROFILE: 连接前…的蓝牙配置文件状态=`。
- **保活等待必须监听 mDNS 发现（2026-10-25 奥迪 6318「连不上」定谳）**：晚武装车机
  （6318 实测入网后 **+88s** 才广播 `_airplay._tcp`、15s 后又消失）在旧「每 8s 拆链
  重试」循环里天然能接住；改成「无新凭据不拆链、保活等待」后，等待任务只轮询 WiFi/
  等 0x4301，**对 mDNS 发现无反应**——日志实录「21:02:32 NSD 发现 10.174.189.1:7000
  → 无人消费 → 21:03:34 车机拆 BT」。修法：`watchForPendingStartSession` 每 5s 检查
  「当前网==0x5703 下发的 SSID 且浏览器发现 `_airplay._tcp`」→ 立即以发现端点重走
  onCarPlayEndpoint（不需用户确认）。**把「拆链重试」改成「保活等待」类优化时，
  必须清点旧循环隐式提供的重试触发源**（mDNS 发现、热点出现等事件钩子）。
  **迁移坑**：旧版本用 `cmd wifi connect-network` 存过热点，只要还在系统配置里系统就
  会自动全局连接——加入前先 `cmd wifi list-networks` 查同 SSID，**命中则弹窗询问用户
  是否清除，绝不擅自删除**（保留是正当需求：用户可能就想用系统自动连、或车机热点
  本身带流量；删除只影响「会不会全局自动连」，与本轮连接无关）。选择保留 → 照常
  以应用专属网络连接。每个 SSID 每进程只问一次（`forgetPromptHandled`），否则配对
  重试/端点重入会反复弹窗挡住流程；无人应答按「保留」处理（不做破坏性动作）。
  解析用 `WifiSavedNetworks.idsOf`（纯函数，有单测：SSID 含空格/非 ASCII/同网多行
  同 netId/畸形行都覆盖；宁可漏判不误删）。手动兜底（`manualJoinCarWifi`）会把热点
  存回系统网络，日志与弹窗都明确写出这个代价。
  **specifier 挂起的兜底节奏必须按「车机窗口」倒排**（2026-10-23 奥迪 3315 毫秒级
  日志定谳）：车机从 0x4301 起 **~15s 拆 BT、~21s 后 AirPlay 服务窗关闭**（晚到即
  7000 端口 ECONNREFUSED，BT 再健康也无法激活）——从 0x5703 起算入网预算只有
  ~9-14s。因此 specifier 首窗只能给 5s（正常入网 0.03~3s；5s 零回调=挂起特征），
  之后依次：快速探测（系统已自己连上则直接绑定，≤3s）→ **重发 specifier（实测
  release→重发 1.6s 即入网，是被验证有效的恢复手段，必须紧跟探测）** 20s 窗 →
  connect-network 深度兜底（本轮窗口必然已过，只为下一轮占位）。曾犯的错：把兜底
  排在 20s 首窗**之后**——第 1 轮首窗没走完车机就拆链（15.2s），兜底根本轮不到执行；
  第 2 轮 21.6s 入网时服务窗已关。**connect-network 存系统网络的清理面**：登记必须
  在命令返回 ok 的**瞬间**（等「切网+bind 成功」才登记的话，取消/超时/bind 失败三条
  路径都会遗留）；清理挂 disconnect 与推流 onEnded **两端**（车机拆链是最常见的
  离场方式）；该 SSID 本轮之前已保存（用户选过「保留」）的不登记不删。
  **bindCurrentWifi 的 onLost 必须转发 onNetworkLost**（兜底入网成主路径后，不转发
  则熄火后英雄卡停在「已连接」、会话拖到心跳超时才死）。
  **specifier 挂起还有一层「系统脏状态」**（2026-10-26 深夜实测）：删净系统已保存
  网络后 specifier 依然两窗零回调（`cmd wifi forget-network` 无效）——WifiManager
  服务内残留挂死的 NetworkRequest/脏缓存，**只有重启手机能清**（同机同热点重启后
  立即恢复）。诊断顺序：①查系统已保存同 SSID → ②forget → ③仍挂起就让用户重启。
  另：同一台手机上第三方同类 app 在同场景同样连不上（非本 app 特有），属 ROM 层
  通病。
  **WEP 无法用 specifier 表达**（无对应 Builder API）→ `JoinResult.Unsupported`，
  走旧的 `connect-network` 兜底路径（`legacyJoinCarWifi`）。
  **PR17（BMW 带来的三个命令行坑，与 specifier 主线并存的知识）**：① `cmd wifi
  connect-network` 的 exit=0 **不代表已连上**（只把连接投给 WifiService）→ 必须轮询
  `cmd wifi status` 等 SSID 变更（我们的深度兜底已按此实现）；② NSD 注册没有 Network
  参数，mDNS 广播跟随**进程默认网络**——绑定了车机热点后必须 `bindProcessToNetwork`，
  否则广播从蜂窝出去车机永远发现不了；③ `cmd wifi set-wifi-watchdog-enabled` 这条命令
  **不存在**（AOSP 是 `set-wifi-watchdog`，仅 root），别照抄。另见 `docs/WIFI-JOIN.md`。
  specifier 在 **Android 12/12L 仍需位置权限 + 位置服务**（13+ 由 NEARBY_WIFI_DEVICES
  接管）：缺权限时 `requestNetwork` 直接失败、现象只是「未能加入车机热点」，很难自查，
  故在连接前置检查拦截（`ensureWifiLocationReady`）；manifest 里 FINE/COARSE 必须成对
  声明，否则 lint `CoarseFineLocation` 直接失败。`onLost` 必须处理（热点消失/熄火），
  否则英雄卡会在「已连接」与失败间静默摇摆（`WifiCarPlayConnector.onNetworkLost`）。
- **推流中的冗余端点/凭据必须忽略**：接收端控制器（DiPlay 实测）在我们配对推流**之后**
  才完成它本轮的 RFCOMM 连接并重发一整套 0x5703/0x4301；若照常当新会话处理，
  `retireSession()` 会把健康会话拆掉（编码器 stop 竞态，日志表现为 `Pending dequeue
  output buffer request cancelled`），接收端视为 session ended 再重连——形成「连上后
  自动断开」循环（2026-10-15）。防护在 `SessionController.onCarPlayEndpoint` /
  `onWifiCredentials`：`videoSender.isRunning` 时直接忽略/只刷新密码缓存；真断连时
  推流自行终止，下一个端点自然接续。会话中的冗余 0x5703 还会连带触发 WiFi 重绑 +
  ctrl mDNS force 重注册，都会扰动推流，一并拦下。
- **0x4301 是活事件，不是「该车机不发」**（2026-10-18，Audi MMI 9310 用户日志定谳）：
  这台车识别帧明确声明 `车机发送清单=0x4301 0xea03`，但 0x4301 在 Phase 1 结束后
  **2 分 35 秒**（配对 120s 窗口都已耗尽、mDNS 也始终没发现 `_airplay._tcp`）才到达；
  车机等到 15 秒无人连入 AirPlay 就自己拆掉 RFCOMM——用户看到的是「连上后自动断开」。
  旧实现有两处会吞掉它：`pumpIncomingForever` 把所有消息当 keep-alive 只记长度，
  且同 ID 连续到达会被限流 `continue`。现行三层修复：
  ① `Iap2SenderSession` 在保活泵里先于限流识别 0x4301 → `parseStartSession`（用缓存的
  0x5703）→ `onEndpointUpdate` 回调，并由 BT client/server 透传到
  `SessionController.onLateEndpoint`；② `onLateEndpoint` 按状态分流：推流中忽略（防上面
  的拆会话循环）/ 配对进行中 → `AirPlayClient.updateEndpoint` 热更新（关旧 socket，下一轮
  重连新地址）/ 交接尚未建 client → 暂存进 `pendingEndpointUpdate`，建 client 时 `drain`
  / 上一轮已失败 → 用新端点重走一轮 `onCarPlayEndpoint`；③ `AirPlayClient` 配对循环每轮
  重试前调用 `endpointRefresher` 再读一次 mDNS 缓存，网关兜底地址连不通时能及时切换。
  **新增生命周期消息（0x5702/0x6800/0x4301 等）时，先判断「它晚到会怎样」**——保活泵里
  必须按消息 ID 分流，不能只当日志。另：`resolveEndpointHost` 的等待窗口只有 3s，
  不能在超时日志里断言车机「不发」（MMI 9310 就是声明会发但晚到）。
- **0x4301 的地址字段是「定长 + NUL 填充」，解析必须去掉全部尾部 NUL**（2026-10-18，
  Audi MMI 3315 日志定谳）：车机把 IPv6 填充到 47 字节（`fe80::…` 后跟 **22 个 NUL**）、
  IPv4 填充到 17 字节（`10.174.189.1` 后跟 **5 个 NUL**）。`asStringOrEmpty()` 原先只
  `copyOf(size - 1)` 去掉一个，得到 `"10.174.189.1\0\0\0\0\0"`——这个字符串
  `contains('.')=true`、`contains(':')=false`，能通过所有「是不是 IPv4」的判断，
  但 Android 的 getaddrinfo 解不了（iOS/桌面 Java 反而容忍，极易误判为「不是这个原因」），
  日志表现为 `Unable to resolve host "10.174.189.1": No address associated with hostname`。
  典型症状链：SRP 配对靠 mDNS 刷新后的干净地址**侥幸成功** → 视频数据端口与 keepAlive
  仍用带 NUL 的旧地址 → 双双失败 → 车机 ~10s 后拆会话（用户看到「连上就断」）。
  教训：**协议字段里的 NUL 填充一律 `rstrip(0)`**，不要假设只有一个终止符。
  另外两处配套收口：① `SessionController.appendLog` 把 `\u0000` 换成可见的 `␀` 再写
  文件——否则导出的日志是二进制文件，用户反馈时打不开（本次日志 216 个 NUL，直接
  无法读取）；② 视频/音频/keepAlive 的 host 一律取 `client.currentEndpoint`（配对期间
  mDNS 刷新过的真实连接地址），不要用解析阶段的 `effectiveEndpoint` 旧值。
  单测 `nulPaddedAddressesAreStrippedCompletely` 用真实填充宽度锁住这一行为。
- **「音乐在播但卡片显示媒体信息未连接」= 没拿到通知使用权**（2026-10-18 定谳）：
  `MediaSessionManager.getActiveSessions()` 在**未授权时仍返回会话**——`PlaybackState`
  正常（`playing=true` 照常翻转），但 `metadata` 恒为 null，于是歌名/歌手全空。
  这个组合极易误判成 metadata 解析 bug，实际是权限问题（`dumpsys media_session`
  里能看到完整 `metadata: description=歌名, 歌手`，我们却读到 null，就是判据）。
  授权要点（`media/NotificationAccess`）：
  - **不能只在 onCreate 试一次**：那次若 Shizuku 未就绪就永久跳过（手机重启后
    Shizuku 未运行、或改包名后首次启动尚未授权 Shizuku），用户只会看到空卡片。
    现在三个时机都补（幂等，已授权直接返回）：App 启动、Shizuku 授权成功、
    **会话建立后**（`VirtualLauncher.attach`，此时 Shizuku 必然可用）。
  - **`cmd notification allow_listener` 的 exitCode 不可作判据**，且**立即复核也不可靠**：
    settings 写入有延迟，实测命令成功（结果=0）后马上读仍是旧值。必须短重试
    （4 次 × 250ms）确认，否则会误报「未授予」。
  - 判定「是否已授权」要**按条目精确比对** `enabled_notification_listeners`
    的 `:` 分隔列表，不能用 `contains(包名)`——旧包名/同前缀应用的条目会造成误判。
  - 改包名后 Shizuku 侧授权状态需要**重启 Shizuku 服务**才干净（实测：新包反复
    点「请求授权」无响应，在 Shizuku 里「停止」再「启动」后一次成功）。
  - 卡片文案要区分三种空：授权缺失（显示「未授予通知使用权」）、有会话但暂停、
    完全无会话（后两者都是「未在播放」）。
- **BT 断链僵尸轮次 + specifier 永久挂起（2026-10-22 奥迪 3315 真机定谳）**：车机 15s
  AirPlay 看门狗（15.07s 实测，与 sysdiagnose 互证）拆链后**立即回连重发端点**，而我们的
  「sessionStarting 互斥」只认标志不认轮次存活性——WiFi 加入卡死（120s 窗）的僵尸轮次把
  车机的新端点堵在门外，全程只剩 keep-alive。三处修复缺一不可：① `onSenderLinkClosed`
  （保活泵退出回调）：未推流即释放互斥+置取消（卡住的配对线程 ≤200ms 退场），**不停 BT
  监听**；② 新轮次起步清 `connectCancelled`（用户取消路径会停 BT 组件、被既有守卫拦，
  走到起步处说明组件在跑）；③ WiFi 加入 20s+20s 分段重发——「系统已保存该 SSID +
  Android 16」组合下 `WifiNetworkSpecifier` 请求**永久 pending 且零回调**（三条日志路径
  全静默），120s 干等毫无意义；加入失败且系统已连着目标 SSID 时直接 `bindCurrentWifi`
  （「保留系统网络」的近路）。另：首连 15s 预算里弹窗 1.7s + MFi 20B 优先白等 6s 是加重
  因素（记忆生效后仅首次付费）。判读特征：日志出现「已有会话在处理中，忽略本次端点」
  后再无连接动作、只有 `ff 55 02 00 ee 10` 心跳 = 本病。
- **真机 iPhone sysdiagnose 对照（2026-10-21，docs/PROTOCOL-NOTES.md §13）**：两车三份
  抓包（iOS 18，`/usr/bin/log show --archive` 读 unified log）定了四条铁律：
  ① **奥迪 MMI 3315_5GHz 首连 403 是车机固有状态**——真 iPhone 在 pair-verify 通过后
  照样被 MFi 段 403 拒，车机 18~20s **自动重邀、第二次即成**；正确恢复 = 保持 BT、
  原流程退避重试（已落地：`pairingForbidden` 禁断-BT-hack、禁明文降级、403 退避 5s）。
  ② 探歌类车机**只认 iAP2 配供应链带出的入网**：手动/系统保存过车机热点 = 永远无
  邀请无 mDNS（同 SSID 清理弹窗对此车是关键）；双频 SSID 必须用 0x5703 指定的那个。
  ③ 该奥迪成功会话 **iAPChannel 协商 = 0**（真 iPhone 未启 WiFi 隧道）——「MIB3 必须
  建隧道」结论存疑，动隧道前先真车 A/B。
  ④ 两车成功路径全是 **IPv6 link-local :7000** + pair-verify + MFi（标准 MODERN 形态，
  无特殊协议）；SETUP 应答可无 streamID（我们只读 dataPort 天然兼容）。
- **sysdiagnose 里的 iAP2 帧级证据在 `crashes_and_spins/accessoryd-packets-*.log.ips`**
  （2026-10-24）：accessoryd 的抓包文件——hex 载荷 + 运输层标注（Bluetooth Classic /
  AirPlay）+ 方向（Acc=车机发 / iPod=手机发），**unified log 里没有 iAP2 帧**，别再从
  airplayd 挖。`FF 55 02 00 EE 10` = control=0xEE 的 DETECT 探测包。文件名时间戳是
  会话起点、内容滚动覆盖到抓包时刻，按行内时间过滤。
- **车机画像（ReceiverProfile）：版本/能力信号统一收口**（2026-10-20）：`iap2/ReceiverProfile`
  纯函数聚合三处信号——0x1D01 身份+收/发双清单、mDNS features/srcvers——产出
  era（三时代）/wifiGen（新旧 WiFi 协议）判定，**依据随日志输出**（`PROFILE:` 行，
  判错可回溯）。两个接线点：`Iap2SenderSession.logIdentification`（BT 侧）与
  `SessionController.onCarPlayEndpoint` 端点诊断后（mDNS 侧）。硬约束：
  ① **按能力分支，不按版本号/品牌硬编码**（srcvers 只做先验，清单与实测行为才是
  分派依据——HID 描述符教训的推广）；② **「清单未声明」不等于「不支持」**：奥迪
  稀疏清单（2+5 项）不列 0x57xx 但旧协议实测工作——wifiGen=NEW 要求清单完整
  （合计 ≥10 项，iD9 形态）仍无 0x5703/0x5702，稀疏清单判 UNKNOWN 走旧协议
  （0x5703 有超时兜底）；③ identityKey 存原文（与 0x1D01 逐字一致），持久化键的
  清洗由 MfiLengthMemory.receiverKey 负责——后续 Memory 并表共用此键。
  **链路代际（LinkGeneration，2026-10-23；当日二次定谳后仅存日志意义）**：
  `linkGenerationOf`——DELPHI 制造 + AUDI 型号 + 双清单合计 1~9 项（三条件同时命中）
  → CLASSIC_MIB3，否则 EVENT_LINK。**marker 协商已全代际统一**（见 MIB3 条目二次
  定谳），代际仅用于日志与未来分派。教训：当天下出的「CLASSIC 忽略 marker」分派
  当晚即被车主日志证伪——单一车辆的现象归因（「协商挂起 UI」）在拿到反例日志前
  不要固化成分派逻辑。判定与 wifiGen 相互独立（BMW iD9：wifiGen=NEW 跳 0x5702，
  两维互不干扰；§9h 的 SYN|ACK 修复在 Iap2LinkEngine）。判定纯函数有单测
  （`ReceiverProfileTest` 五条）。代际缓存随 disconnect 清零。
- **能力清单只能证明「有」，不能证明「没有」——绝不可当硬门禁**（2026-10-22 奥迪
  3315 真车定谳，0.1.13 回归的根因）：8b64147 把 WiFi 凭据分派写成「清单没声明
  0x5702 就跳过旧协议」，而奥迪的可收清单只有 5 项（`0x4e0c 0x4e09 0x4300 0xea00
  0xea01`）不含 0x5702 → 整轮**一次 0x5702 都没发** → 车机 AirPlay 服务**始终没起来**
  （45 次连接全部 `ECONNREFUSED`、`_airplay._tcp` 也没广播），4 轮会话每轮 8 秒自拆。
  同一台车同一热点在旧版本（无脑发 0x5702）下的对照：`0x5702 → 0x5703` 应答
  **122/136/166/166/198/246/266/368ms**，入网 30ms 后端口 7000 即可连通。
  两条铁证说明「未声明 ≠ 不支持」：① 同日日志里 0x4e0d/0x4e0b/0x4e0e 三条**不在**
  清单里，车机逐条 ACK 到位（seq 107→ack=107、110→ack=110、113→ack=113）；
  ② catplay `catplay_carplay_rx_gadget` 与 xcertplay `Iap2IdentificationClient` 扮演
  车机时都把 0x5702 列进无线清单（`WIRELESS_PHONE_MESSAGES = [0x4e0d, 0x4e0e, 0x5702]`），
  说明规范要求声明、但现实车机常漏写。
  **现行分派**：只认 `ReceiverProfile.wifiGen`——`NEW`（两清单合计 ≥10 项仍无 0x57xx，
  BMW iD9 形态）才跳过 0x5702；`LEGACY` 与 `UNKNOWN`（稀疏/缺声明）**一律发**。
  0x5703 等待窗口收到 **1.5s**（奥迪实测有 4 倍余量），超时只降级不拆链。
  单测 `Iap2SenderSessionTest.sparseDeclarationsStillSendWifiRequest`（奥迪 3315 形态
  必须发）与 `fullDeclarationsWithoutOldProtocolSkipWifiRequest`（宝马 iD9 形态不发）
  钉死两个方向，**改这段先看这两条**。推广：任何「按清单能力分支」的地方，都要给出
  「清单不完整时走保守路径」的分支，不能把缺席当成否证。
- **无新凭据时不要拆链**（2026-10-22）：`Iap2SenderSession` 在「本轮既没 0x5703、
  8 秒内也没 0x4301」时，只要 `knownCarSsid()` 能给出已知热点（`boundCarSsid` 或
  `lastCarWifiSsid`），就合成 `airPlayPort=0` 的端点继续走 mDNS/网关，**不抛异常**。
  奥迪日志里手机第 2 轮起就在车机热点上，却因「无凭据」每轮 8 秒自杀，把车机侧
  邀请窗口反复清零——而真 iPhone 实录里车机**入网后约 21 秒**才发 ctrl 邀请
  （`StartSessionHost`，奥迪 sysdiagnose 2026-10-21 14:23:02 入网 → 14:23:23 邀请），
  每轮重启等于永远等不到。配套：0x4301 路径现在也写 `lastWifiPsk`/`lastCarWifiSsid`
  （此前只在 0x5703 路径写，导致手动兜底按钮误报「尚未拿到热点密码」）。
- **ctrl 广播端口必须固定**（2026-10-22）：Android NSD 没有 JmDNS 的 `reannounce()`，
  重发公告只能注销+重注册；旧实现连带 `stop()` 一起做，每次 `ServerSocket(0)` 换端口
  （奥迪日志实测 43857→33383→33125→38151→40697→38015），中间还有最长 1.2s 服务空窗。
  依赖 ctrl 邀请的车机（xcertplay 车机侧每会话 7 次 ×1.5s 探测）会按缓存端口连到已关闭
  的 socket。现改为：`unregisterServiceOnly()` 只换 mDNS 注册，socket 与 accept 线程
  在整个 advertiser 生命周期内复用（`acceptRunning` 独立于 `registered`）。
- **CarPlay 长连接要显式设 TCP keepalive**（2026-10-22，参照 DiPlay `TcpLiveness`）：
  内核默认 keepalive 要 **2 小时**才开始探测；车机熄火/手机走出热点时链路常**不发 RST**，
  控制通道读线程会一直挂在 `read()` 上（画面冻结、看门狗不触发）。`net/TcpLiveness`
  统一设 idle=10s / interval=3s / count=3 + `TCP_USER_TIMEOUT=20s`（19s 判死刻意大于
  车机 15s 看门狗），已接控制通道、事件通道、视频数据口三处。**API 坑**：
  `ParcelFileDescriptor.fromSocket` 在 API 29 前返回 socket 自身 fd 的包装，close 会把
  原 socket 一起关掉——29+ 才用它取副本，26~28 反射 `Socket.getFileDescriptor$()`。
- **车型重放回归（录制→重放测试）**（2026-10-20 落地）：防「改 A 车型回归 B 车型」。
  管线：`tools/extract_car_fixtures.py` 从运行日志提取 `RAW rx/tx`（蓝牙链路）与
  `IAPLINK rx`（隧道 CSM）成 JSONL fixture（`app/src/test/resources/carplay/fixtures/`，
  **默认 gitignore**——含真实地址/热点名，入册需逐条评审脱敏）→
  `replay/BtLinkReplayTest`（真实 Iap2LinkChannel+Iap2SenderSession 全栈重放）与
  `replay/TunnelReplayTest`（VirtualIap2Link 控制会话重放）断言「录制的应答序列
  ⊆ 回放序列（有序前缀/子序列）」。**录制即测试**：每次实车排障后跑一遍提取器，
  fixture 库滚雪球。三条硬教训：① RAW 转储预算按字节计（已提 4096B/方向，旧日志
  512B 连识别帧都盖不住）；② **帧长完整 ≠ 字节完好**——预算截断会把不同 read 的
  尾巴拼成「长度恰好完整」的伪帧，载荷校验和不过、引擎静默丢弃，提取器必须按引擎
  同款校验（帧头 9B 求和=0、载荷+尾校验求和=0）逐帧验证后再裁剪；③ 断言分级
  （`rxHasIdent`）：识别帧幸存才做全量断言，否则只断言链路层（SYN/采纳/0x1D00），
  隧道层用有序子序列（放行有意新增如 MIB3 突发，捕捉少答/乱序）。
- **协议差异以日志为准**：测试台（DiPlay）与真车（奥迪 MIB3）行为多处相反（MFi 挑战
  32B/20B、0x4301 有无、ctrl 邀请死锁等），细节见 `docs/PROTOCOL-NOTES.md` §9，
  改协议代码前先对照。**0x5702 的外部佐证**在 §5b（catplay / xcertplay /
  amineross-showcase / wiomoc-iap2 四个独立车机实现全部声明它）、**0.1.13 奥迪回归的
  完整证据链与判读要点**在 §9j。
- **黑盒抓包看不到条件分支，「这台车没走的路」不能推广为「这款车不走」**（2026-10-26
  type130 教训）：3315 成功会话 `iAPChannel FeatureKey=0` 曾被当成「奥迪不走 type-130
  隧道」的证据，runIapTunnel 在 3315「200 但无 dataPort」后归档为 DiPlay 专用——
  8015 因此从未试过该路径；而对照文档（THIRD-PARTY-COMPARE）里的第三方实现恰以
  type-130 为主路径且能连
  新款奥迪。抓包证明的只是「这台车这次走了哪条路」，不是「只有哪条可走」：载体维度
  （type-130 专用 socket vs 事件通道）在 accessoryd-packets 里都标「AirPlay 运输层」、
  根本不可分辨，SETUP 应答的 enabledFeatures 也从未对 8015 解过。**判读多车抓包时，
  每台车的「没走的路」要单独存疑；需要白盒证据（独立实现的分支逻辑）才能暴露
  「存在另一条路」**。另：抓包里已看到的行为差异若当时判定非关键而未对齐（如两步式
  0xAA00 的 body 差异——一步式在 3315/6318 都通过，按「工作即不动」纪律搁置），
  要在文档里显式记为「已知差异」而非默默归档，否则拿到第二份佐证时想不起第一份在哪。
- **老车机（CarPlay 210.81 / 奥迪 4xxx 固件）必须按 catplay 的「三时代」分类处理**
  （2026-10-19 定谳，`docs/PROTOCOL-NOTES.md` §9f）：该代是 **pre-HomeKit** 接收端
  （`_airplay._tcp` features 无 bit38 `HK_PAIRING_AND_ENCRYPT`），只接受
  `et=NONE` 明文控制会话 + **AES-128-CTR 零密钥**屏幕流，`pair-setup` 必然失败。
  六处硬约束：① bplist **根对象必须在字节 8**（210.81 不读 trailer 的 topObject，
  我们的编码器默认根在末尾——已统一 `makeRootFirst`）；② 初始 SETUP 用相对 URI
  `/setup`；③ 未配对会话显式带 `et=1`，事件通道明文；④ 屏幕帧未配对走 AES-128-CTR
  （密钥流**跨帧连续**，每帧新建实例 = 第二帧起全乱码）；⑤ 控制密文帧 MTU 1440 →
  单帧明文上限 **1422**（旧 0x4000 会被判 PayloadTooBig）；⑥ **不要用
  `contains('.')` 判「有没有地址」**——奥迪 4xxx 常只发 IPv6 link-local，判成无地址
  就会退到「网关:7000」兜底并永远连不上（统一走 `net/EndpointHosts`，IPv4 → 全局
  IPv6 → 链路本地 IPv6，且视频/音频/事件通道复用配对真正连通的 `activeHost`）。
  TXT features 缺失一律按「未知」保持旧路径，不能误判成「不支持配对」。
- **iAP2 协商：SYN|ACK 必须用新序号，且每条 SYN 都要回**（2026-10-19 BMW 87866
  实车定谳，`docs/PROTOCOL-NOTES.md` §9h）：14 轮会话全部卡在链路层（`iap2 link
  ready` 0 次），车机发 133 条 SYN、回 0 条 ACK。两个根因：① `sentSequence` 只在
  发数据帧时自增，导致 SYN 与 SYN|ACK 同为 seq=99，对端按 `peerSeq+1` 校验后判重复
  丢弃（catplay 用 `take_own_seq()`）；② 只有首次采纳时回显，而 BMW **每轮重试都用
  新序号重开协商**，其余 SYN 只拿到裸 ACK 永远等不到回显。现状：每条**纯 SYN**
  （`control and ACK == 0`）都回一帧 SYN|ACK，同一 SYN 的重发字节级幂等（不推进
  序号）。两条踩坑：**裸 ACK 会与 SYN|ACK 撞号**（故主机路径只回 SYN|ACK，Audi
  帧序随之从「裸 ACK+SYN|ACK」变为单帧，需复测）；**不能对所有带 SYN 位的帧都回**
  （会与对端的 SYN|ACK 形成无限乒乓，单测曾因此空转 55 秒，只有 `jstack` 能看出）。
- **会话中音频路由被蓝牙设备抢占 → 推静音流（2026-10-26 荣耀+6318 定谳）**：耳机
  （X7e）或车机 A2DP 回连后，手机媒体输出切去蓝牙，AudioPlaybackCapture 采不到
  （诊断行「采集样本 非零0%」持续）、推给车机的就是静音流——用户报「车机没声音了」，
  极易误判为音频协商问题。自愈（0.1.28.5）：采集全零 + MediaSessionsState 确认有
  应用在播 → `BtProfiles.connectedAudioDevices` 枚举并断开全部音频类蓝牙设备
  （每会话 ≤5 次、间隔 ≥30s）——路由回落本地后采集自动恢复。会话期间音频本就该
  走 WiFi 的 AirPlay，任何蓝牙音频接收端都是抢占方。
- **手动断开后立即重连必走二轮（2026-10-26 6318 日志定谳，非回归）**：手动断开
  （突关 RFCOMM）后车机有 ~25-30s 的清理/闭锁期——期间接受 BT 链路与识别、但
  **不理 0x5702**；车机自身 ~15s 链路看门狗拆掉第一轮（expectOrNull 经
  `!channel.isAlive` 提前返回），第二轮（3s 后）秒答 0x5703。0.1.13 同场景同形态
  （它也是等链路死再重走）。处置：接受二轮（总计 ~20s 连上）；想一轮连成，断开后
  等 ≥30s 再连。改这里的耐心没用——拆链是车机干的。
- **0x4E0D（WirelessCarPlayUpdate，u8=1）必须在设备状态问候里发**（2026-10-26
  奥迪 6318 定谳，0.1.28.3 修复）：0.1.37 按「iPhone 不发 4E0D」对齐 8015 抓包时把它
  删了——6318 随即**完全连不上**：车机不武装 AirPlay 栈（`_airplay._tcp` 零广播、
  0x4301 零下发、ctrl invite 永不到来），而 WiFi 入网/ctrl 注册侧一切正常，极易误判成
  网络/mDNS 问题。铁证：车机自己的 0x1D01 可收清单（7 项）**就声明了 0x4e0d**。
  恢复一行即修复（真机 A/B：恢复后 BT 连上 → 1 分钟内视频/触摸/仪表盘全通）。
  **§9i 教训升级：「某台车的 iPhone 抓包不发 X」只对那台车成立**——删任何消息字段前，
  先查目标车机的可收清单里有没有声明它；声明过的，删前必须真车 A/B。
- **车机如何识别我们的字段，一律不许"照 catplay 抄"**（2026-10-19 奥迪 0.1.7 回归
  定谳，`docs/PROTOCOL-NOTES.md` §9i）：把 ctrl TXT 的 `srcvers` 从 `950.7.1` 改成
  catplay 的 `566.25.21`、实例名 `sender` → `iPhone` 之后，同一台 Audi MMI 3315
  从「0x4300 后 0.65s 下发 0x4301」变成**连 `GET /ctrl-int/1/connect` 都不发**，
  `_airplay._tcp` 与 0x4301 双零、网关 `:7000` 空转 54s（`ECONNREFUSED`）。
  iAP2 全通、车机还在发 0xAE00，极易误判成网络问题。已回退并加测试
  `CarPlayCtrlAdvertiserTest` 钉死。**同一类字段**：`sourceVersion`、`deviceID`、
  服务名、`model`、TXT `id`——改前必须真车 A/B 验证，且日志必须能直接看到取值。
  配套：`_device-info._tcp` 已移除（Android `NsdServiceInfo` 不接受 port=0，
  每次都报 `The port is missing`，属未验证收益的噪音）。
- **0x4300/0x4E0E 的蓝牙标识必须在会话启动时就拿到真实 MAC**（2026-10-19 奥迪
  0.1.7 日志）：整轮都是占位 `02:00:00:00:00:00`。根因是 `DeviceIdentity` 的 10 秒
  静默窗口撞上「BT 会话在 Shizuku 刚就绪的同一毫秒构造」——首次失败后 10 秒内全返回
  占位值。`BtSession` 现改为 `forceRetry = true` 并打印解析结果。奥迪会用它与 RFCOMM
  对端核对，占位值导致静默丢弃。排查时**先看 `设备状态问候 … mac=` 这行**。
- **AirPlay 会话级 SETUP 的 `deviceID` 必须是本机真实蓝牙 MAC**（2026-10-19
  WinPlay 实测）：接收端可以按「用户选中的 iPhone 蓝牙地址」过滤会话，对不上即回
  **403**，状态行是它特有的 `403 OK`（WinPlay 的 STATUS_TEXT 表没有 403，回退成
  OK——看到这个组合优先怀疑设备过滤）。此前用 catplay 的写死假值
  `ff:ee:dd:cc:bb:aa`——Audi/DiPlay 不校验，WinPlay 必拒；表象是「连上后约 45s
  自动断开」（接收端等视频超时后自己拆链）。现统一走 `DeviceIdentity`（与
  0x4300/0x4E0E 同源），占位值 `02:00:00:00:00:00` 只作 Shizuku 未就绪兜底，
  403 日志会打印该值供用户到接收端对照。**SETUP 非 200 要主动 close 控制通道**，
  别静默等接收端拆链——否则用户只看到「自动断开」，排查从零开始。
- **会话存活靠三条相互独立的心跳，缺一整轮 10 秒后被车机拆**（2026-10-19 奥迪
  MMI 3315 两轮实测，时序逐毫秒重合）：
  ① UDP keepAlive 4B ping → keepAlivePort；**间隔必须远小于车机看门狗**——catplay
  取的 10s 与看门狗等长，`Thread.sleep` 抖动让心跳晚到 1ms 即超时（现取 5s）；
  ② RTSP `POST /feedback` 空体，1s；
  ③ **屏幕数据口内的 keepAlive 帧**（128B 明文头，opcode=2；`/info` 的
  `keepAliveSendStatsAsBody=true` 时 opcode=5 带 bplist 统计体），1s，**视频持续
  推流时也照发**——它不是视频流量的备份。只有 ①② 时现象是「推流正常、RECORD 后
  10.2s 整轮 reset」，日志里视频/feedback/事件通道全都健康，极易误判为网络问题。
  **纠偏（2026-10-19 晚）**：三条心跳齐发时 MMI 3315 **仍**在 ~10s 被拆——该车的
  10s 拆链另有主因（缺 WiFi 隧道，见下条）；心跳是必要条件，不是充分条件。
  keepAlive 帧不加密（catplay 只加密 opcode=VideoFrame），不消耗 ChaCha 帧 nonce。
- **MIB3 需要第二条 iAP2 链路（WiFi 隧道），只回 ACK 会被 ~10s 拆链**（2026-10-19
  定谳，当晚日志 + AADisplay Frida 对照；**2026-10-23 二次定谳：本条成立**——当天
  早些时候「CLASSIC_MIB3 忽略 marker 永不协商」的修正被车主 0.1.17 日志证伪：marker
  是车机在等会话表，1Hz 无限重发，不回应则 RECORD 后 ~10s 拆链（0.1.9=10.17s、
  0.1.17=10.18s），「0.1.9 忽略 marker 照常进入」系误传。marker 升级协商已全代际
  统一。当年「协商挂起车机 UI」的真因见下条 modesChanged）：AirPlay 会话建立后，手机必须在屏幕流
  SETUP 成功后**主动经控制通道** POST /command（iAPSendMessage 信封）发一次 DETECT，
  车机回 SYN（事件通道）→ 手机 SYN|ACK（控制通道）→ 链路 NORMAL 后重跑控制会话
  （0x1D00→0x1D01→0x1D02+0xAA00→0xAA06→0xAA02 挑战【长度按车机记忆，隧道独立键，
  冷启动 20B 优先；同车蓝牙/隧道偏好可能不同——3315 蓝牙认 32B 而 Frida 隧道 20B】
  →0xAA03→0xAA05→突发
  0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0→应答订阅 0x5000/0x4157/0x4154/
  0x4170，0xFFFB 只链路 ACK）。实现：`airplay/VirtualIap2Link.kt`（三形态合一：
  DiPlay 隧道/MIB3/旧回显），协议细节与 Frida 逐字节常量见 `docs/PROTOCOL-NOTES.md`
  §12。硬约束：① 手机→车机链路帧**只能**走控制通道 POST（事件通道出站 MIB3 不读）；
  ② DETECT 必须在屏幕 SETUP 之后发一次（早发被冲掉，之后车机 marker 一律忽略）；
  ③ 回显 LSP 声明自己的 maxOutgoing=0x7F（原样回显对端表=自己限死在小窗口，突发
  积压）；④ 0xFFFA/0xFFF0/0x4158/0x5001 用逐字节常量，**勿重新编码**；⑤ 参考仓库
  `reference/AADisplay` 是 GPL-3.0：只看协议事实，代码不抄。
- **MFi 挑战长度按车机持久化，不要全局写死某一档**（2026-10-19 奥迪 MMI 3315 实测）：
  20B 挑战在该车静默 **6.0 秒**无应答，32B 才在 0.64 秒回签名——只靠「真车都 20B
  优先」的基线，每次连接都白等 6 秒。而各接收端偏好相反：DiPlay 只认 32B（长度不符
  直接断链）、MMI 6318 认 20B（32B 曾 0.4s 拆链）、MMI 3315 认 32B。
  `MfiLengthMemory` 按 0x1D01 的 name+model+manufacturer 生成稳定 key，持久化
  成功/静默/被拒三种结局；`orderAttempts`（纯函数）把**上次成功的长度排第一**，静默
  与被拒只降级、不丢弃（固件可能变，全被拒时照常全试，防永久卡死）；已知静默过的
  档位重试窗口压到 1.5s。**记忆是跨进程的**——旧实现只在进程内记，App 重启即丢。
- **RTSP 控制通道必须异步读**（2026-10-17）：一条连接一把锁 + 同步读响应的实现，
  会让任何慢请求（奥迪音频 SETUP 2s+）期间 feedback 心跳停摆；车机对 feedback 有
  ~3s 看门狗，断供即拆会话。现为独立读线程收包解密 + 按 CSeq 投递，写侧只串行化
  「分配 CSeq + 加密写」；`soTimeout=0`（超时由待响应表的 poll 控制）。重连时旧 socket
  必须关（防两个读线程抢包），cipher 由空转非空的瞬间要丢弃明文残渣（否则密文帧
  边界错位、后续应答全解不出）。
- **HID 设备的能力一律按「设备描述符」判定，不按车机型号/uuid/报文长度写死**
  （2026-10-17：车机媒体键「收到但不切歌」+ 触摸多车机兼容的教训）：
  ① **媒体键（Consumer Control）**走 `hidSendReport`，1 字节报告、**字节值是 Usage
  数组下标**（HID Array 语义，`LogicalMax` = 数组长度-1），**不是 Usage ID**。
  奥迪按下「下一首」实测收 0x05 → usages[5]=0xB5；早期把 0x05 直接当 Usage ID →
  无对应键（表现为「有反应但不切歌」）。映射由 `ConsumerKeys.parseDescriptor()`
  解析描述符的 Usage 顺序得到，再按 `[KEYCODE_MEDIA_PLAY/PAUSE/NEXT/PREVIOUS/...]`
  派发（`AudioManager.dispatchMediaKeyEvent`，与桌面音乐卡同一路径）。
  ② **触摸**同样按描述符判格式：Digitizer(0x0D) 页 + X/Y 16bit 才是触摸设备；
  单指 Collection（奥迪 5 字节 `[tip][X u16LE][Y u16LE]`）vs 多槽（DiPlay 12 字节
  `[槽][down][X][Y]×2`）按「Contact Identifier(0x09 0x22) 出现次数」区分。
  **不能用报文长度或 uuid 判**：DiPlay 的 knob(4B)/telephony(1B) 与媒体键长度重叠，
  只靠长度会把 telephony 的 usage 0xB3 当成「快进」误发。描述符缺失时才回退长度判据。
  ③ 判定实现均有单测钉死语义（`ConsumerKeysTest` / `TouchReportsTest`，含奥迪与
  DiPlay 两套真实描述符），改这些解析器先看测试。
