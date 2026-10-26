# CarDeck — 让 Android 手机扮演 iPhone 直连车机

让一台普通 Android 手机（未 root）扮演 **CarPlay 发送端（iPhone 角色）**，
把 app 自绘界面投到支持 CarPlay 的车机上。

> **非官方项目**：本项目为个人实验性作品，与 Apple Inc. 及任何汽车制造商均无关联，
> 未获授权或认证，**不是 MFi 产品**。CarPlay / AirPlay 是 Apple Inc. 的商标，
> 文中出现仅用于说明兼容目标。
>
> **驾驶安全**：仅限停车时配置与操作；行驶中断连可能导致导航中断，请以原厂导航为安全冗余。
>
> 完整条款见 [DISCLAIMER.md](DISCLAIMER.md)（与应用内首次启动的免责声明同源）。

## 结论先行（可行性）

- **方向与 DiPlay/xcertplay 相反**：它们是接收端（车机角色）；本项目是发送端。
  **DiPlay 是 xcertplay 的衍生版，也是本项目实际使用的测试台**；`reference/`
  里两者的源码都留作参考（协议知识在 iAP2/链路层/Bonjour 层共享，角色逻辑互不包含）。
- **发送端不需要苹果签名身份**：iAP2 认证是单向的（手机验证车机的 MFi 证书，
  车机信任手机自报结果，Oligo Security DEF CON 2025 已公开证实）。
- **有线 USB 路线已排除**（app 无法控制 USB gadget，荣耀无法解锁 BL），
  只走无线：蓝牙 RFCOMM → 车机热点 → mDNS → iAP2/AirPlay。
- 详细协议依据见 [docs/PROTOCOL-NOTES.md](docs/PROTOCOL-NOTES.md)。

## 下载安装

**推荐：GitHub Release**（可直接下载、附 SHA-256 与签名指纹）：
[Releases](https://github.com/shaonialife/cardeck/releases/latest)

**国内备用：百度网盘**（国内访问 GitHub 慢时用）

> 通过网盘分享的文件：CarDeck
> 链接: https://pan.baidu.com/s/1RQoDzFBermUobakZ0d0e3Q?pwd=xc5i
> 提取码: xc5i

安装前请确认：

- **Android 14+**（`应用横屏` 功能依赖 SDK 34 起的平台 ChangeId，13 及更早不可用）
- 已安装并**启动** **Shizuku**（无线调试激活，免 root）——本应用的虚拟屏、触摸注入、
  窗口状态读取全部经它完成，未就绪时无法连接
  - 官网（含分步骤激活指南）：https://shizuku.rikka.app/
  - GitHub（版本与更新说明）：https://github.com/RikkaApps/Shizuku
- 首次启动会显示免责声明，需滑到底并等待 10 秒后才能接受

> 两个渠道提供的是同一个构建产物（官方 release 密钥签名）。网盘无法自动校验，
> 建议下载后用 `apksigner verify --print-certs <apk>` 核对证书指纹是否与 Release
> 备注里公布的一致（SHA-256 `9457d04d2709094404a534ba06d7ecbc53cb1406224ec7c53c05a9c67ccc4970`），
> 不一致就不要安装。

## 路线图与 Go/No-Go

| 阶段 | 内容 | 门槛 | 状态 |
|---|---|---|---|
| Phase 0 | 测试台 + 网络层 demo（本仓库） | 两台手机 mDNS 互发现 | ✅ 已验收（DiPlay 测试台） |
| Phase 1 | iAP2 发送端会话（链路状态机 + 0x1D00/0x5702/0x4300） | 接收端日志显示「iPhone 接入」 | ✅ 已验收：完整走通至 0x4301，拿到热点凭据与 AirPlay 端点 |
| Phase 2 | AirPlay 主会话（PairVerify + H.264 编码 + HID 回传） | 备用机显示本机画面并可触摸 | ✅ 已验收（2026-10-08 23:17）：30fps 推流+渲染、触摸事件回传打通 |
| Phase 3 | 真车（奥迪 MIB3）验证 | 车机点亮 | ✅ **全通（2026-10-11）**：视频稳定上屏 + 车机触摸/滑动可交互（详见下方 Phase 3-C） |
| 兜底 | 买 Tbox 安卓盒子，把同一套自绘 UI 装进盒子 | — | 不需要（真车路线已通） |

**Phase 3-C 全通（2026-10-11，奥迪实车验收）**：iAP2 → AirPlay 配对 → 视频推流 →
触摸回传 → 虚拟屏注入全链路可交互。本日攻坚战果（细节见 `docs/PROTOCOL-NOTES.md` §9c）：

1. **AirPlay 配对 403 破案**：配对请求缺 Apple 标识头（`Content-Type: application/pairing+tlv8` +
   `X-Apple-HKP: 2`、pair-verify 加 `X-Apple-PD: 1`，对照 catplay PairingHelperTx 逐字）。
   补齐后 pair-setup/pair-verify 全 200，车机保存长期公钥。
2. **屏幕流 400 破案**：SETUP 必须通告**真实 UDP 计时端口**（TimingServer，RTCP NTP 应答）
   且 features 发**完整 iPhone 能力清单**（接收端按「车机支持 ∩ 手机提议」算交集，
   只提 videoPlayback 会得到空集 → 视频管线不启用）；modesChanged 资源声明先行；
   UA/sourceVersion 用 iPhone 自报值（566.25.21），勿用车机的。
3. **会话 10s 拆链破案**：双心跳缺一不可——keepAlive UDP ping（车机 keepAlivePort，10s 周期）
   + `POST /feedback`（1s 周期，catplay FEEDBACK_INTERVAL）。
4. **触摸链路（本日最后战场，五连破）**：
   - 车机命令必须走**事件通道 socket** 上的 `POST /command`（fire-and-forget、独立 CSeq、
     Events 密钥），发在 RTSP 控制连接上车机收不到（DiPlay sendCommandLocked 实证）
   - `hidSetInputMode`（uuid 原样字符串回传）武装 HID 后车机才开始发触摸
   - 奥迪 `/info hidDevices[].uuid` 是**字符串** "0"/"1"（触摸=uuid 1 eso_touchScreen，
     报文 5 字节 `[tip 1bit][X u16LE][Y u16LE]`，坐标上限 1412×654=屏幕分辨率）
   - 事件通道是双向 RTSP：**每条来向请求立即回 200**，否则车机请求管道塞满判通道死亡
     （表现为「能滑一下就断」）
   - 事件通道 `iAPSendMessage` 承载车机发起的**虚拟 iAP2 链路**（marker=DETECT 一问一答，
     车机是客户端发 SYN——服务器重发 DETECT 会把它打回 Detecting）
5. **稳定性三修**：BT 客户端线程世代号（防僵尸并发）、ctrl mDNS 注册跟随网络绑定
   （换网/绑定后 force 重注册，listener 每次新建）、mDNS 结果按车机网段过滤
   （防手机热点共享把电脑 AirPlay 当车机）。
6. **调试流程坑（记一辈子）**：`adb install -r` 不杀旧进程——旧 dex 继续跑，
   「修复无效」时先 force-stop 重启；验证时进程代次（`ps -T` 线程数）
   与 APK 内字符串（解包 dex grep）都要查。

**Phase 3 实车突破（2026-10-09，Audi_MMI_6318 / MHI3_AS_AU_P3801，见 `docs/PROTOCOL-NOTES.md` §9b）**：
与测试台（DiPlay）相比，奥迪 MIB3 有六处关键差异，逐一修正后 iAP2 全链路首次贯通：
1. 车机**主动发 SYN** 声明会话表 `{1:control, 2:EA}`，手机必须 ACK+原样回显，
   之后 CSM 走 **1 号会话**（发 10 号被车机静默丢弃，而链路层照常 ACK——极具迷惑性）
2. **MFi 挑战必须 20 字节**（32B 车机完全不应答；测试台 DiPlay 反而要 32B → 做成 32/20/16 回退）
3. 车机**不发 0x4301**（识别帧能力清单实锤：可收 0x4e0c/0x4e09/0x4e0d/0x4e0e/0x5702/0xea00/0xea01、
   可发 0x5703/0xea03）→ AirPlay 端点只能走 WiFi 上 **mDNS `_airplay._tcp`** 发现
4. **ctrl 邀请死锁**：车机在 WiFi 上发现手机 `_carplay-ctrl._tcp` 并 `GET /ctrl-int/1/connect`
   邀请后才开放 AirPlay 窗口 → 手机必须**在 0x5703 到达时立即**绑热点+广播 ctrl
5. ctrl TXT `id` 必须是**真实蓝牙 MAC**（Shizuku shell 读 `settings secure bluetooth_address`；
   应用层 API 只有占位 `02:00:…`，占位值导致车机不邀请 → 双向死锁）
6. pair-setup 被 **403**（奥迪不接受匿名 SRP 配对）→ 转「已配对设备」pair-verify 快路径；
   且**绝不能**断 BT 控制会话（测试台 detach hack 会让奥迪拆掉整轮会话）

**Phase 1 真机调试记录（2026-10-08，DiPlay on Redmi K40）**：
1. `StreamDuplexStream` 读写共享锁导致死锁（读阻塞持锁，写永远等待）→ 读写分离
2. MFi 挑战 16 字节被拒（Carlinkit EC 身份走 AuthV3，要求**精确 32 字节**）→ 已修
3. 参考代码（xcertplay）在订阅后发 0x6800 StartHID；**实际测试台 DiPlay 不发**（订阅完直接等 0x5702）→ 改为 2 秒可选等待

**Phase 2 里程碑 A：AirPlay 配对打通（2026-10-08 22:58）**——蓝牙保持连接状态下一次通过：
`/info` 前哨 87ms 应答 → pair-setup（SRP 固定码 3939，M1/M3/M5）→ pair-verify（M1/M3，
接收端 ed25519 签名对照 mDNS `pk` 校验通过）→ 控制通道 ChaCha20 密钥派生。
DiPlay 侧 logcat 确认：5 个 POST 全部处理，随即 `airplay control encryption enabled`，
之后耐心等待 23 秒（等加密的 SETUP/事件通道）才重置。

**调试教训（值得记一辈子的一个）**：「RTSP 无应答」曾归因于接收端状态门禁/时序竞争，
最终真凶是**客户端读缓冲 bug**——响应头和 body 在同一次 read 到达，`readExactly` 却去
流里等已消费的字节。**在下结论「对端不理我」之前，先用对端日志/抓包验证字节是否到达、
自己的读取路径是否正确。**

**Phase 2 全链路验收（2026-10-08 23:17）**：控制面（蓝牙 iAP2 → pair-setup/verify →
加密 RTSP → SETUP → 事件通道 → 流协商 → RECORD）+ 数据面（MediaCodec H.264 →
128 字节头+ChaCha20 帧封装 → TCP 推流）。接收端实测 rx=shown=29.6fps、decode p50=6ms、
touch2frame 18ms、recoveries=0。

**触摸闭环验收（2026-10-09 10:20）**：事件通道命令解析（RtspAccumulator + plist）→
hidSendReport 双槽报文解析（u16LE 像素坐标）→ UI 命中测试。真机确认：按住高亮、
松开熄灭、计数递增、坐标实时刷新；拖动轨迹完整。注意坑：报文第二槽是
`(0,0)↑` 空闲占位，必须过滤，否则覆盖真实手指状态。

**音频通道（2026-10-10）**：SETUP type=100 协商 UDP 音频流 → `AudioPlaybackCapture`
捕获本机媒体/导航声音 → PCM 48k 立体声按 RTP+ChaCha20 封包推流（每包 512 帧）。
密钥与视频同规则（`DataStream-Salt<connId>`）；需要 MediaProjection + RECORD_AUDIO
授权（点「申请投屏权限」时一并请求）。已单测（解密回环 3 用例），待真机验证。
剩余：麦克风上行（通话）、正式车机桌面 UI 完善、真车（Phase 3）。

## 测试台搭建（Phase 0 使用说明）

1. **备用安卓手机**（Android 9+）：从 [DiPlay releases](https://github.com/shihabal3amri/DiPlay/releases)
   下载 APK 安装——它是我们的「虚拟车机」（接收端）。
   （DiPlay 是 xcertplay 的衍生版，更活跃；`reference/` 里两份源码都留作参考。）
2. **两台手机系统级蓝牙配对**（设置里直接配对即可，这是 DiPlay 回连的前提）。
3. **本 app 安装到荣耀手机**：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. 按界面四步操作：
   - **③ 启动蓝牙 iAP2 监听**：监听 iPhone UUID，等 DiPlay 回连。
     连上后日志会十六进制转储入向字节——看到 `ff 55 02 00 …` 即链路层 SYN，
     Phase 0 的核心验证点；
   - 备用机 DiPlay 选无线模式（热点/局域网），开启后它会扫描已配对手机并回连；
   - **① 加入热点**：输入 DiPlay 显示的 SSID（系统弹窗输密码）；
   - **② 扫描 _airplay._tcp**：应解析出备用机的 AirPlay 端点 + TXT（deviceid/features/pi/pk）；
   - **④ 广播 _carplay-ctrl._tcp**：验证 DiPlay 能发现并反连手机控制通道。
5. DiPlay 的发布版内置了从 Carlinkit 固件提取的实验性配件身份（其 README 已声明），
   即可走完 MFi 认证（我们的发送端跳过校验）。若需自备 MFi 身份（芯片/本地证书/
   远程服务三种途径），见 xcertplay README。

## 工程结构

```
app/src/main/java/io/github/shaonialife/cardeck/
├── MainActivity.kt                 # 手机侧操作台 UI（连接/声音/暗幕/日志/免责声明入口）
├── PhoneSettingsActivity.kt        # 手机二级设置菜单入口
├── SessionController.kt            # 会话总管（进程级单例）：链路生命周期、配对、WiFi、取消
├── SenderApp.kt                    # Application（未捕获异常兜底、包变化失效缓存）
├── iap2/                           # iAP2 链路层 + CSM + 发送端会话（含车机画像/挑战长度记忆）
├── airplay/                        # AirPlay 配对/控制通道/流协商/流加密/虚拟 iAP2 链路
├── audio/AudioSender.kt            # 音频通道（播放捕获 → RTP/ChaCha20 over UDP）
├── video/VideoSender.kt            # H.264 编码推流
├── bt/                             # RFCOMM 双向（Server 等回连 / Client 真车主动连）+ 目标设备
├── launcher/                       # 隐形屏启动器（dock + 应用网格 + 地图屏；含分屏把手/letterbox 提示）
│   └── settings/                   # 设置面板 Fragment（车机双栏与手机二级菜单共用）
├── render/DualDisplayComposer.kt   # EGL 多屏合成 → 编码 Surface（含叠加卡片层）
├── input/                          # 触摸分流（dock/应用屏/地图屏直注）+ 消费键
├── capture/                        # 隐形屏画面源
├── shizuku/                        # Shizuku 桥（shell 权限 / 触摸注入 / 受信虚拟屏 AIDL）
├── net/                            # WiFi 入网 / mDNS 发现与 ctrl 广播
├── media/                          # 通知使用权 → 媒体会话（音乐卡数据源）
├── service/CarPlayService.kt       # 前台保活服务（连接/投屏 FGS 类型）
├── transport/                      # 传输层封装
├── ui/                             # 暗幕、波形图、壁纸、深色弹窗、面板窗口尺寸
├── legal/                          # 免责声明（内容/接受状态/弹窗）
└── util/                           # Bg（后台任务池）等工具
app/src/remote/                     # 远程调试 flavor（调试桥代码只在 remote 变体编入）
docs/PROTOCOL-NOTES.md              # 协议知识库（分层格式/时序/参考索引）
docs/CARLIFE-GATEWAY-FEASIBILITY.md # 另一条路线的可行性调研（CarLife 网关）
reference/                          # 参考仓库（gitignore，不入库不分发）
```

## 代码质量（提交前自动拦截）

提交（`git commit`）前 pre-commit 钩子（`.githooks/pre-commit`，经 `core.hooksPath` 启用）
会自动执行两步，任一失败即阻止提交：

1. **代码格式化**：Spotless + ktlint（Kotlin 官方代码风格，规则在根目录 `.editorconfig`）。
   暂存过的 `.kt`/`.kts` 会被自动格式化并重新暂存，无需手工处理；
2. **Android Lint**：`lintDebug` 全量检查，error 级问题使提交失败。

手工命令：

```bash
./gradlew spotlessApply             # 全量格式化
./gradlew :app:lintDebug            # lint 检查（报告: app/build/reports/lint-results-debug.html）
./gradlew :app:updateLintBaseline   # 修复问题后重建 lint 基线
```

存量 lint 问题（273 条，均为车机 UI 场景的有意取舍：硬编码中文文案、px 定位、RTL 等）
记录在 `app/lint-baseline.xml` 中豁免；**新代码再引入同类问题仍会被拦截**。
问题实际修复后基线条目自动失效；确认为误报可单独关闭对应 issue id，或评审后重建基线。
全新克隆后需执行一次 `git config core.hooksPath .githooks` 启用钩子。

## 构建与发布

```bash
./gradlew :app:assembleDebug      # 日常调试包（app/build/outputs/apk/debug/）
./gradlew :app:assembleRelease    # 发布包（app/build/outputs/apk/release/）
./gradlew testDebugUnitTest       # 单元测试
```

**版本号**：`app/build.gradle.kts` 顶部 `appVersionName` 是唯一来源（语义化版本），
`versionCode` 由它自动推导（`major*10000 + minor*100 + patch`），避免两处不同步导致
用户装不上更新却没有任何提示。改版本只改这一行。

**CI 自动出包**（`.github/workflows/android-ci.yml`）：每次 push 用仓库 Secrets 里的
正式 keystore 构建 `assembleRelease`，并自动累计构建号——传
`-Pcardeck.buildNumber=<run_number>`，得到 `0.1.13.<N>` / `versionCode=base*10000+N`
（每次推送 +1，可直接覆盖安装上一版；升 patch 时自动反超）。产物
`CarDeck-<版本>-release.apk` 只在 Actions 的 Artifacts 里，**不发布 GitHub Release**。
没配 `KEYSTORE_BASE64`（以及 `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）时
流水线直接失败，不会用 debug 签名凑一个「正式版」。

**签名**：本地 `assembleRelease` 在没有 `keystore.properties` 时退回 **debug 签名**
（只给 fork 者试装；CI 带构建号的构建会直接报错），正式对外分发前请配置真实签名：

1. 复制 `keystore.properties.example` → `keystore.properties`（已 gitignore）；
2. `keytool -genkeypair -v -keystore cardeck-release.jks -alias cardeck -keyalg RSA -keysize 4096 -validity 10000`；
3. 填好密码后重新 `assembleRelease`，用 `apksigner verify --print-certs` 确认证书 CN 不是 `Android Debug`。

> ⚠️ 私钥泄露 = 任何人可签发能覆盖安装的更新；私钥丢失 = 已安装用户再也收不到更新
> （只能让所有人卸载重装）。keystore 与密码绝不入库、绝不外发。

## 边界

- **产品形态是隐形屏启动器**（受信虚拟屏跑手机应用 + dock 常驻），不是整机镜像；
  车机触摸经 Shizuku 直注虚拟屏（2026-10-10 起桌面/镜像两种模式已移除）。
- **许可证**：本项目源码以 [MIT](LICENSE) 发布。`reference/` 下参考项目
  （xcertplay/DiPlay 为 GPL-3.0；**catplay 仓库未声明任何许可证**，默认保留所有权利）
  **已被 `.gitignore` 排除、不随本仓库分发**，其许可义务不适用于本仓库；本项目为
  独立实现，仅参考公开协议格式与真机抓包，不复制其代码。
  勿分发固件提取的密钥或证书。
- 真车验证前请先用可借到的 iPhone 与车机做一次正常无线配对留作对照。

## 名称与商标

- 应用名为 **CarDeck**，**不含**任何第三方商标。早期版本曾用「CarPlay 车机投屏」，
  该名称已弃用——把商标用作产品名属商标性使用，容易被误认为官方产品。
- 「CarPlay」「AirPlay」是 Apple Inc. 的商标；文中出现仅用于**说明兼容目标**
  （描述性引用），不表示关联、赞助或认可。
- 本项目**不使用** Apple 或任何车厂的图形标识、认证标（含 MFi）。
- 包名 `io.github.shaonialife.cardeck` 同样不含第三方商标（采用 GitHub 用户名的
  标准倒写形式）。从旧的 `com.lizhihua.carplaysender` 迁移过来属于**不兼容变更**：
  系统视为不同应用，需卸载重装，Shizuku 授权与本地设置（含 AirPlay 配对身份）
  都会重置，车机端也需重新配对。

## 已知风险（详单见 PROTOCOL-NOTES.md §9）

1. 奥迪 MIB2 在 catplay 兼容表中有断电前科；MIB3 全新未知。
2. 真车可能要求先插 USB 才开放无线配对（测试台无此限制）——Phase 3 实测。
3. WifiNetworkSpecifier 无法程序化传密码，每次入网需系统弹窗确认。
