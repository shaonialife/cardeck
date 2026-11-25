# 平台 / Android / 工具链

> 2026-11-18 从宿主 `AGENTS.md` 拆出（原 1397 行 / 136KB）。
> 共 10 条，136 行。
>
> Shizuku、Shell、线程与 ANR、权限与通知、日志导出、安装与远程调试。

---

- **线程**：短命一次性任务用 `Bg.run("name") { }`（util/Bg.kt，cached 池）；长驻循环
  线程（GL 合成、链路 reader/pacer、accept 循环、看门狗等）保持独立 Thread——需要
  命名、interrupt 和生命周期持有，不要收进池。主线程禁止 IO/解码（封面等走专用执行器）。
- **UI 线程绝对禁止 `Shell.exec`/Shizuku 调用**（2026-10-18 系统排查，两次真机 ANR 的
  共同根因）：每条 `Shell.exec` 都是 `newProcess` + 进程等待，正常 ~100ms、超时上限
  可达数秒；`detach()` 里 3 屏 ×(set+get) = 6 条直接 ANR，横屏勾选框的 `setEnabled`
  2 条（各 4s 超时）最坏 8s 也够 ANR。**已加运行时守卫**：`Shell.exec` 检测到主线程
  调用时累加 `mainThreadViolations` 并写文件日志
  （`SHELL: ⚠ 主线程调用 shell（第 N 次）cmd=…`），接线在 `SessionController.ensure`。
  出现该日志即说明有新代码走了 UI 线程，必须改 `Bg.run` / 异步包装。
  排查结论（2026-10-18 全量复查）：`MainActivity`（refreshShellState/resetShellState/
  releaseDisplays/通知授权）、`ScreenDimmer`、`SessionController`（autoJoinCarWifi/
  shellWifiStatus，仅由 Bg 调用）、`DeviceIdentity.bluetoothMac`（调用方均在后台）、
  `VirtualLauncher.launchApp`（三个调用点都包在 Bg）均已正确；本次修掉的两处是
  `VirtualLauncher.detach` 与 `AppOrientationCompat.setEnabled`（后者新增
  `setEnabledAsync` 供 UI 用，内部执行器串行保证连点顺序）。
  另注意 `BtProfiles.proxy()` 用 `getProfileProxy`，其**回调经主线程 Handler 投递**——
  在主线程调用必然等满 1.2s 超时并返回 null（已加显式主线程守卫与告警日志），
  这类「阻塞等主线程回调」的 API 一律只能在后台调用。
- **Shell 命令**：参数用 `shellQuote`（单引号转义）；查 WiFi 开关/当前 SSID 用一次
  `cmd wifi status`（勿用「任意 WiFi 即成功」的盲绑判定——手机连着别的 WiFi 时会误判）。
  **无互联网热点：默认走应用专属网络，不再系统级入网**（2026-10-18 定谳；
  **2026-10-25 方案 C 修正「specifier 被静默路径架空」**）：
  `WifiNetworkSpecifier`（`WifiCarPlayConnector.joinCarPlayNetwork`）加入车机热点——
  网络只对本进程可见（系统侧 `Ephemeral: true` + `OwnerUid=本应用`），**不进系统已保存
  网络、不成为默认网络**，其它应用继续走 4G/5G。实测（荣耀 + Redmi K40 + DiPlay）：
  `cmd wifi list-networks` 无该 SSID（返回 0）、`Active default network` 仍是蜂窝、
  推流期间 `ping baidu.com` 0 丢包。顺带解决三个老问题：系统 watchdog 不再切回原
  WiFi；不需要 Shizuku 也能自动加入；不会因「已连接无互联网」被 ROM 连带关掉移动数据。
  **曾被静默路径架空（2026-10-25 定谳「WiFi 自动断开」根因）**：端点先到的入网入口
  `joinCarWifi` 曾是「Shizuku 就绪 → 静默 connect-network 优先」（为绕 specifier 强制
  弹窗），而 Shizuku 就绪是隐形屏模式硬前置 ⇒ specifier 事实永不出场；系统级入网把
  热点存进系统 + 顶掉当前 WiFi + 默认网变成无互联网热点 → ROM（荣耀 WLAN+）自动断开/
  切回有网网络 = 用户看到的「WiFi 自动断开」；且存过的 SSID 毒化之后所有 specifier
  请求（永久 pending）→ 永远走系统级的恶性循环（荣耀日志 21:53~21:54 实录）。
  **方案 C 现状**：全部入网入口 specifier 优先（含 0x5703 主路径 autoJoinCarWifi——
  2026-10-25 二次修正：只改端点先到的 joinCarWifi 不够，autoJoin 里还有一段静默优先，
  荣耀实测「连上又切回 CMCC」正是它）；静默 connect-network 降为兜底（覆盖用户拒
  specifier 弹窗、WEP 两场景）。**「清除/保留」弹窗绝不在连接关键路径上**（三次修正
  2026-10-25 晚：挂在 specifier 入口实测阻塞 3.4s，6318 连接失败回归的成分之一——
  现只挂在 promptSpecifierFailed 失败分支）。「保留」例外只认用户选择
  （`wifi_net/user_kept` 持久化）：旧判据「系统里已存在」会把自己遗留的保存当用户
  意愿——静默路径存的网络因 force-stop 没走到会话末清理时，例外让毒化**自延续**。
  来历不明的保存（存在但非用户保留）保守不删；两条 connect-network 路径（静默/深度
  兜底）都在命令 ok 瞬间登记会话末 forget。另：**「已保存 SSID → specifier 必挂起」
  并非普适**（荣耀存过 netId 69/63 依然 0.8s 入网），挂起只是部分 ROM 形态。
- **媒体卡片**：元数据切歌是分阶段的（歌名先到、封面/时长滞后），空结果需短退避重试
  而非固化缓存；播放位置用「锚点 + 速度」外推，UI 每秒自刷新。
- **日志导出落 `下载/CarDeck/`，不能落应用私有目录**（2026-10-18）：用户要能自己找到
  并转发给开发者。实现走 `MediaStore.Downloads`（无需存储权限、文件管理器可见、
  微信/分享面板能直接选到）；`filesDir` 与 `Android/data/<pkg>` 在 Android 11+ 的
  系统文件管理器里直接被屏蔽，导出等于没导。导出弹窗必须用 `DarkMessageDialog`
  列出**绝对路径 + 大小**，snack 两秒就消失、用户看不到（本次需求即为此）。
  **默认只导本次进程启动后的日志**（历史轮次动辄几百 KB、淹没关键行）：
  `SenderApp.onCreate` 用 `DebugFileLog.markSessionStart` 写一条
  `SESSION_MARKER` 进文件，导出时 `lastIndexOf` 截取最后一条之后的内容；
  长按导出按钮走 `Scope.ALL` 取全量。标记**必须写在文件里**而不是只记内存偏移
  ——2MB 截断会让偏移失效；截断后也要立刻补写一条标记，否则导出退化为全量。
  无标记（旧版本文件）时回退全文——全量比空好。
- **荣耀 ROM 打开目录要用显式组件**（2026-10-18 实测）：`ACTION_VIEW` +
  `vnd.android.document/directory` 走隐式 intent 会被 resolver 乱匹配（实测弹出
  「网易云音乐」当默认项）；而 `am start` 同样的 intent 却正确落到 DocumentsUI。
  解法：优先显式 `ComponentName("com.android.documentsui",
  "com.android.documentsui.files.FilesActivity")` + `document/primary%3ADownload%2FCarDeck`，
  失败再退隐式/存储根/文字指引。**凡是「唤起系统 UI」的功能都不要只写隐式 intent**。
- **远程调试桥：remote flavor 与外层 cardeck-bridge 仓库**（2026-10-20 落地，设计全文
  `docs/REMOTE-DEBUG-DESIGN.md`）：bridge 维度 flavor（standard=日常/发布，包名不变；
  remote=`.rd` 后缀、调试桥代码只在 `app/src/remote/`、依赖走 `remoteDebugImplementation`
  ——一行不进 standard 产物）。**构建任务名已 flavor 化**（assembleStandardDebug /
  lintStandardDebug / testStandardDebugUnitTest，pre-commit 钩子已同步）。App 与业务的
  耦合面仅两处：`DebugFileLog.tap`（main 侧日志序号+钩子）与 `BridgeCommands`（白名单
  命令，只调 SessionController 公开 API）。服务端在外层独立仓库 `../cardeck-bridge`
  （MIT，Node/TS，WS+MCP 单进程；`.npmrc` 固定公有源防公司源污染）。硬约定：
  ① remote 变体的 Shizuku/通知使用权需单独授权（新包名）；② 重装前必须先
  `SessionController.disconnect` 释放虚拟屏（否则安装确认弹窗落车机侧没人点得到）；
  ③ 命令白名单扩充走 `BridgeCommands` 单点评审，禁止任意 shell。
  ④ **差分更新**（2026-10-24 实测省 72%~97% 流量）：块差分协议与三重校验详见
     `docs/REMOTE-DEBUG-DESIGN.md`；**头部 u64 一律大端**（设备端 readLong 只读
     大端，LE + 头缓冲写短曾是「差分首跳静默回退全量」的真凶）；设备端任一校验
     失败无条件回退全量。
  ⑤ **`.rd` 包必须开悬浮窗权限**：安装确认 Intent 从后台 Receiver 拉起会被荣耀
     静默拦截（豁免条件=SYSTEM_ALERT_WINDOW），断连提示/复现引导也依赖它；
     安装确认另有前台 Activity 转发兜底（InstallReceiver → BridgeSetupActivity）。
  ⑥ remote 变体禁自动暗幕（`ScreenDimmer.autoDimAllowed=false`，排障要看屏幕），
     常亮与手动假息屏保留。
  ⑦ **AS 点 Run 装出 remote 版（2026-11-09 定谳）**：构建变体选择只存在本地
     `.idea/`（gitignore），任何 IDE 重导/缓存失效后记忆丢失 → AS 落到**默认变体 =
     flavor 字母序第一 × debug**，而 `remote` < `standard` → 默认 remoteDebug，点 Run
     静默装出 `.rd` 包（.idea 全量搜索无任何变体记录可证）。修法：Build → Select
     Build Variant → app → `standardDebug`（存在本地 .idea）；自验看 Run 面板任务名
     `:app:assembleStandardDebug` 或装机 versionName 是否带 `-rd`。仓库侧无解（新版
     AS 的 Run Configuration 没有变体字段，改名 flavor 又会牵动 CI 任务名）——每台
     开发机重导项目后都要切一次。
- **远程调试桥真机联调五雷**（2026-10-21 全链路打通时逐一定谳，含组件命名/网络/安装三族）：
  ① **`.rd` 包下自启组件必须写「applicationId/绝对类名」**：`am -n` 只认带斜杠的规范，
  相对类名按 applicationId 展开成不存在的 `…rd.launcher.DockActivity`、纯类名直接
  `Bad component name`——两形态都让 dock/grid 启动失败、车机黑屏 0fps（standard 包
  applicationId==命名空间从不暴露）。组件名一律 `X::class.java.name` 拼接，勿用
  `context.packageName + 相对路径`。
  ② **车机会话期间进程网络被绑到车机热点（无互联网）**，调试桥 WS/APK 下载在
  「加入热点」毫秒级断掉——`InternetRoute`（remote）跟踪蜂窝网络兜底，socket/DNS
  显式走蜂窝（`Network.socketFactory`/`getAllByName`）；但**默认网络 VALIDATED 时优先
  默认**（室内 WiFi 环境强走蜂窝反而断流）。
  ③ **WS 死连接**：蜂窝/NAT 静默断链无 FIN，读线程永久阻塞、重连循环卡死（实测断后
  4 分钟不重连、桥侧设备假在线）——25s 心跳 ping + 75s soTimeout 必备。
  ④ **握手协议由设备侧驱动**：先发 hello（含配对码）服务端才回 welcome，等 welcome
  再发 hello = 互等死锁。
  ⑤ **桥的 stdin 必须接 FIFO 而非 /dev/null**，否则 MCP（stdio）无法接收指令，
  notify/cmd 全部不可用；`~/bin/cardeck-debug-up.sh` 已固化该模式。
- **Honor 推送安装（PackageInstaller）的坑，四条全踩**（2026-10-21）：
  ① 会话必须 `createSession` 拿 id 再 `openSession`——直接 `openSession(0)` 抛
  「Caller has no access to session 0」；② 状态接收器在 manifest 里**必须带
  intent-filter**（自定义 action 无过滤器 = 广播永远投递不到，PENDING_USER_ACTION
  无人拉起、会话永挂 Active，还会堵死后续安装——App 启动时 `abandonStaleSessions`
  自愈清理）；③ **相同 versionCode 的推送安装 Honor 直接「假成功」**（显示安装成功
  但包不动，adb 路径无此行为）——**推送新包必须升 versionCode**（本项目由
  appVersionName 推导，改版本号即可）；④ `adb install -r` 装的字节相同包在
  PackageInstaller 眼里也不算数，测试推送闭环时**不要先 adb 装同一个文件**。
  安装确认/续接询问用 DarkMessageDialog 正规弹窗（悬浮窗会被忽略，用户实测），
  前台无 Activity 时带参拉起 BridgeSetupActivity 由它弹；调试包 manifest 开
  `usesCleartextTraffic`（桥可能是纯 HTTP 部署）。
- **音乐卡「无法读取播放信息」= 通知监听器「有授权、无绑定」**（2026-10-19 实测）：
  `settings get secure enabled_notification_listeners` 里能看到本组件、
  `MediaListenerService.onListenerConnected` 却从未被调用——此时 `dumpsys notification`
  的 **Live notification listeners** 段里没有本组件，`getActiveSessions()` 仍返回会话
  （播放状态可见）但 `metadata` 恒为 null，卡片就显示「无法读取播放信息」。
  **授权列表 ≠ 已绑定**：重装 APK 后列表项还在，绑定可能丢失。实测对照——App 进程
  不运行时静置 10s 不会自愈；启动 App 后 1s 内恢复，说明必须由我们主动重绑。
  自愈实现见 `NotificationAccess.rebindIfNotConnected`：仅当本进程尚未确认连上
  （`serviceConnected` 为 false，由 onListenerConnected 置位）时读一次
  `dumpsys notification --noredact`，**只认 Live 段**，缺失则 `disallow_listener`
  + `allow_listener` 触发系统重新绑定并短轮询确认。别用「在 enabled 列表里」当已授权
  判据——那正是这个 bug 的误判来源。
  另：监听器断开/销毁时必须 `MediaSessionsState.publish(null)`，否则卡片会保留旧歌名，
  出现「正在播放 + 未在播放」这类自相矛盾的残留状态（布局静态小标题也不能写死
  「正在播放」，已改为中性的「媒体」）。
- **remote 变体不在 pre-commit lint 范围（2026-11-19 实锤）**：钩子只跑
  lintStandardDebug，main 源码的 API 重构（哪怕只是删一个死成员，如 0.1.28.114 清扫
  `SessionController.statusLine`）会**静默弄坏 remote 编译**——`compileRemoteDebugKotlin`
  只在下一次手动构建 remote 时才炸。防复发的排查顺序：`./gradlew assembleRemoteDebug`
  看第一条 `e:`；remote 源（app/src/remote/）引用面极窄（SessionController 公开 API +
  BridgeOverlay），改 main 公共 API 时 `grep -rn "SessionController\." app/src/remote`
  过一遍即可。0.1.28.171 修复：BridgeCommands.get_state 改用 `phaseHint`（英雄卡
  副标题同源）。
- **连接后手机自动旋转开关被打开 = 地图应用写的，不是我们（2026-11-20 排查）**：
  全量 grep 主源码 + Shizuku UserService + remote 桥，没有任何 accelerometer_rotation
  /user_rotation 写操作（settings put 仅常亮的 screen_off_timeout）。触发链：连接 →
  仪表盘 → 默认地图（高德）自动启动 → 高德持 WRITE_SETTINGS（修改系统设置权限），
  启动/导航时自动开启系统自动旋转（全局设置，虚拟屏实例照样改手机开关），断开不
  还原。用户选择不处理（导航期间手机跟随转向反而有用）。若日后要治：会话级快照
  还原（同 screen_off_timeout 备份/恢复模式）或 `appops set <地图包> WRITE_SETTINGS
  ignore`（可能影响地图功能，慎用）。

- **卸载 Shizuku APK ≠ 服务下线**（2026-11-25 实证）：`shizuku_server` 由 ADB/root 以
  shell uid 拉起，卸载 APK 只杀应用 uid 进程——服务进程与 binder 原样存活到重启，
  ShizukuGate 仍报 READY（真实状态：连接/建屏/注入全部可用）。`pm list packages` 空
  但 `ps -A | grep shizuku_server` 有进程即为该形态。对用户是收益：换 Shizuku+/Sui
  时先卸官方再装新的，服务不掉线无缝接管。同理 ShizukuGate.installed 的权限定义发现
  在此形态下返回 null（定义方已卸载），引导会落到「未安装」——但 binder-first 的
  state() 不受影响（先探 binder）。
