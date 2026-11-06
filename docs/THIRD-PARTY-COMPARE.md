# 无线链路互操作对照（2026-10-26）

> 本文件沉淀 CarPlay 无线链路的互操作要点，依据两层：真 iPhone sysdiagnose 抓包
> （PROTOCOL-NOTES §13 系列）的协议推导，加 `reference/` 下未入库的参考实现
> 行为对照（溯源与类名索引见 `reference/README.md`，gitignore；只提取协议行为
> 事实，代码不抄）。

## §A. WiFi 侧 iAP2 会话的载体：Type 130 专用 socket 隧道（主路径）+ 事件通道（降级）

**这是我们从未走过的路径，也是本次对照最大的收获**。AirPlay SETUP 应答的
`enabledFeatures` 含 `iAPChannel` 时（手机 SETUP features 里声明
`["iAPChannel","viewAreas"]`，取交集），协商 **type 130 数据流**：

- 请求：`SETUP / streams=[{type:130, controlType:2, channelID:"<BT_MAC>-RCS-1",
  clientTypeUUID:"E9459FD0-BCAD-4C45-820F-1E72447EF2F2", clientUUID:<rand>,
  seed:<rand i64>, wantsDedicatedSocket:true, sendMessageAsIs:true}]`；
- 应答给 `dataPort` → **专用 TCP socket**（不经事件通道、不经 /command）；
- 加密：密钥从 AirPlay 会话密钥 E 派生——`HKDF(E, "DataStream-Salt"+seed,
  "DataStream-Output/Input-Encryption-Key")`，输入输出流各自封装；
- 帧格式（32B 头 + 载荷）：`[4B 总长]["sync"@4..8]["comm"@16..20][时间戳@20..28]`；
  收到 sync 帧回 `rply`（带回对方时间戳）；comm 帧载荷 = iAP2 数据，喂给与 BT 链路
  **同一个链路引擎类**（L3）跑完整协商；
- 就绪判定：iAP2 认证 + runtime 问候完成后打 `iap_tunnel_ready`（6s 超时）；
- **失败降级**：接收端不声明 iAPChannel，或 type 130 握手超时/被拒 → 关隧道，改用
  **事件通道**承载 iAP2（A4 按车机在事件通道上发来的第一块数据自适应三模式：
  `FF 5A` 链路帧→完整 L3 协商；`FF 55`→DETECT 回显；`40 40`→裸 CSM 直喂状态机）。
  出站帧：type 130 模式包装 comm 帧写隧道；事件通道模式走 `POST /command` 的
  `{type:"iAPSendMessage", params:{data:…}}`（与 MIB3 方式一致）。

### clientTypeUUID 的定性（2026-10-24 查证）

`E9459FD0-BCAD-4C45-820F-1E72447EF2F2` **是协议通用常量，不是该 app 的私有值**：
xcertplay / DiPlay（车机模拟器）常量名 `IAP_DATASTREAM_UUID`，WinPlay 注释直说
「Generic DataStream (used for the iAP2-over-CarPlay tunnel) and the iAP channel
UUID」——三个独立实现同值。我们实现 type 130 时**直接用这个值**（与
`DataStream-Output-Encryption-Key` 等标签一样是线上协议的一部分，换成随机值
车机大概率不认）。`clientUUID` 才是每次随机生成的。

## §B. 8015 半移交的解法：不拆 BT、纯被动探测、等车机自己断

对照 PROTOCOL-NOTES §13c 的三组证据，抓包与参考实现共同指向**第四种协议形态**：

1. **从不主动发 DETECT**：链路引擎收到车机的 `FF 55 02 00 EE 10` 探测 → 原样回显
   6B（established 后重复探测忽略）；从不抢先发探测包。
2. **从不主动拆 BT**：BT 链路（RFCOMM）认证拿凭据后一直挂着（心跳泵照常）；90s
   看门狗只管「凭据未到手」阶段。BT 链路结束（车机拆或异常）时若媒体会话在跑 →
   `bluetooth_handoff` 打点「蓝牙链路结束，保留正在接入的 Wi-Fi/媒体会话；媒体结束
   前不发起第二轮蓝牙连接」，500ms 轮询等媒体结束才重连——**移交是被动形态**。
3. **Re-SYN 重置**：链路 established 后车机再发合法 SYN（宝马半移交重开协商的形态）
   → 重置整个链路+控制状态机（清窗口/队列/认证态），重走协商，上限 3 次
   （`link_resync`）。我们 Iap2LinkEngine 对此的处理需对照确认。
4. **版本协商强硬**：本机始终提议控制版本 2；车机反提议 1 → 直接判本轮失败
   （保留蓝牙 bond，等版本 2 ACK 才连）——「完整无线连接不回退版本 1」。

**若 8015 接受 type-130 隧道形态**，则 §13c 半移交模型需要修正：
切屏的钥匙不是「拆 BT」，而是 **WiFi 侧 iAP2 会话的载体是否被车机认作标准
iAPChannel（type 130 专用 socket）**——事件通道上出现的 iAP2 会话（0.1.37/我们
0.1.28.8 形态）才会触发「等 BT 释放」的半移交态。**验证顺序**：先在 8015 连接日志里
确认 SETUP 应答的 `enabledFeatures` 是否含 `iAPChannel`（含 → 下迭代实现 type 130
主路径；不含 → 维持 0.1.28.8 拆 BT 路线不变（事件通道降级形态）。

## §C. MFi 认证：手机=host 全套验证车机（与真 iPhone sysdiagnose 一致）

- 流程：`0xAA00(带选项4)` 要序列号 → 车机 `0xAA06`（16/32B 序列号）→ 手机再发
  `0xAA00(空)` 要完整证书 → `0xAA01` → **验证 Apple CA 链**（APK 内置 `mfi/*.der`：
  root CA G2/G3 + iPod CA 1/2 + AACA 0/2，完整证书链校验 + MFi v3 P-256 能力扩展）
  → 手机发 `0xAA02` 随机挑战 → 车机 `0xAA03` 签名 → **NONEwithECDSA 验签（64B）**
  → `0xAA05`。**手机侧全程不需要 MFi 私钥**；收到车机反向请求（0xAA00/0xAA02 角色对调）
  直接抛异常拒绝（「no fabricated phone certificate or signature」）。
- 我们的差异：方向一致（同为 host），但我们跳过证书/签名校验自报 0xAA05——对连接
  成败无影响（车机不知情），可不改。**多出的「先要序列号再要证书」两步**（0xAA06
  交互）我们没有——真 iPhone 抓包里同样出现「0xAA00→车机 0xAA06→iPhone 重发 0xAA00」
  （§13c AirPlay 链时间线），**8015 上值得补**：一步直要证书在 3315/6318 可行，
  但 8015 的 AirPlay 链抓包里 iPhone 走了两步。
- 挑战长度**静态三档白名单 + 默认 32B，无记忆、无试错循环**（C0020c5：默认
  ("none",32)，命中才换）——DELPHI AUDI3260=20B、DELPHI AUDI5163=32B、Alpine
  8A250-30A=32B。它能「首连即成」的全部秘密就在这：默认 32B 恰是 DiPlay/多数
  接收端接受的档位。我们隧道基线 20B 优先（对齐 3315 Frida/8015 iPhone 抓包）
  → DiPlay（只认 32B）首连必错一次，靠记忆第二次起恢复；代价结构相反——它对
  白名单外「认 20B 且长度不符即断链」的车会首连失败且永远失败（无自愈），
  我们首次试错一次后永久正确。8015（AUDI 5810/DELPHI）不在其白名单 → 它连
  8015 不在表内 → 也走默认 32B；该路径成立的旁证是 8015 至少不断链 32B（iPhone 抓包用
  20B 不代表 32B 被拒）——对我们意味着：8015 隧道若 20B 静默，换 32B 大概率可行。
- runtime 问候（隧道 ready 后，q() 顺序）：0x4E0C(身份)/0x4E0B(时间)/**0x4E0D(u8=1,
  WirelessCarPlayUpdate)**/0x4E0A(语言)/0x4E09(名称)/**0x4E0E(本机蓝牙 MAC,
  DeviceTransportIdentifier)**——与我们 6318 定谳「0x4E0D 必发（u8=1）」一致。
  ⚠ 首次记录时把 19981/19982 的十六进制换算搞反（4E0D↔4E0E），照抄进代码后
  DiPlay 收到畸形 WirelessCarPlayUpdate 于 2 秒后整栈下线（2026-10-26 17:15 实测：
  NSD 服务消失 + 事件/隧道/视频全链 Connection reset）——**十进制消息号换算后必须
  与 Iap2MessageIds 常量表互核，不能只信笔记**。

## §D. 宝马（PROTOCOL-NOTES §9h 交叉验证）

L3 对每条**纯 SYN 都回 SYN|ACK**、SYN|ACK 用新序号、同参数重复 SYN 字节级幂等
（只重发上次 SYN|ACK 不推序号，`link_syn_ack_repeat`）——与我们 2026-10-19 的
修复完全一致，属二次独立确认。新增细节：**established 后的 Re-SYN 触发全量重置**
（≤3 次）——覆盖「车机每轮重试用新序号重开协商」之外的「链路已建成后重开」场景。

## §E. 连接编排与媒体会话（零散但值得记录的事实）

- **BT 监听优先**（手机起 RFCOMM server 等车机连，`origin=car_initiated`）——与我们
  相同；出站重连亦有，但常态是等车机。
- ctrl mDNS：**单次注册**（`NsdServiceInfo.setNetwork(车机网络)` 直接绑网，无
  force-announce 循环）、TXT `id`=真实 BT MAC、`srcvers=566.25.21`、服务名
  "CarPlay"。**srcvers=566.25.21 + 服务名 CarPlay 的组合在参考路径上可用**——§9i 的回归教训
  需要重新划界：当时我们同时改了 srcvers（950.7.1→566.25.21）与实例名
  （sender→iPhone）两个变量，罪魁未必是 srcvers 本身；但未经 A/B 前**仍不改动**
  这批字段（§9i 纪律不变）。
- 端点解析**三路竞速**：0x4301 直连端点（`control_race_winner=direct_endpoint`）>
  ctrl 邀请 > mDNS 发现（`endpoint_discovery_parallel`，ctrl 邀请不等待 _airplay._tcp）；
  解析后做 **IPv4 网关优先注入**（ctrl TCP 对端若是 IPv4 且端点列表无 IPv4，把网关
  IPv4 插到列表头，`endpoint_ipv4_preferred`）。
- 每台车机**时序画像按配对持久化**：discoveryRounds(1~6)/discoveryRoundMs(5~15s)/
  controlWaitMs(30~90s)，带兼容包络校验——比我们固定窗口（2s 补发/4s 兜底）细一档。
- WiFi 入网：`WifiNetworkSpecifier` + `requestNetwork` 多路重试 + `bindProcessToNetwork`
  （与方案 C 主路径同型）。
- 媒体会话顺序：pair-verify（失败清 pin 重试一次）→ 媒体 MFi → timing socket
  （UDP 32B 请求应答，NTP 式）→ SETUP(deviceID=真实 BT MAC) → /info → 事件通道 →
  **type 130 隧道 ready 后才 POST /command modesChanged** → 屏幕流 SETUP(type 96) →
  dataPort → 再 POST /command → RECORD → 音频 → 推流。音频**首包**时才 claim main
  audio 的 modes 声明（`audio_first_packet`）。
- 看门狗：视频写超时 10s（「Car stopped consuming video」即重连）；A4 心跳泵每秒
  （部分消息 15s / 握手 30s 超时）；事件/隧道 iAP2 会话收到 0x5703/0x4301 **直接抛
  异常**（「event runtime must not rejoin Wi-Fi」）——我们「冗余凭据忽略」的更激烈
  版本。
- 它也发 `POST /command` modesChanged（F4 状态机：resource 1=屏幕 / 2=音频的
  borrowed/permanent entity），事件驱动更新（车机 requestUI/音频首包时刷新快照）。

## §F. 对下一迭代的行动清单（按优先级）

1. **8015 日志复核**：翻 8015 连接日志里 SETUP 应答的 `enabledFeatures`——含
   `iAPChannel` 则把 **type 130 专用 socket 隧道**列为 8015 主攻路径（协商参数照
   §A 的字段清单实现）；不含则维持 0.1.28.8 的拆 BT 路线。
2. **DETECT 改纯被动回显**：去掉 armMib3 的主动探测（保留 marker 回显），对齐
   参考实现与真 iPhone（iPhone 也是「车机 marker→回 DETECT」，从不裸发）。
3. **0xAA00 两步式**：8015 路径上先带选项要序列号（0xAA06）再要证书——对齐 iPhone
   抓包；3315/6318 保持现状（一步直要已验证可行）。
4. **Re-SYN 重置**：给 Iap2LinkEngine 补「established 后收到合法 SYN → 全量重置
   重协商（≤3 次）」，覆盖宝马重试形态的完整闭环。
5. 低优先：SETUP features 声明 `["iAPChannel","viewAreas"]`、挑战长度白名单初始值、
   时序画像持久化、ctrl TCP 对端 IPv4 注入端点列表头。

## §G. 落地记录（分支 type130-iap-channel，0.1.28.9，2026-10-26）

按 §F 行动清单改造完毕（全量单测通过，正式签名测试包已出）：

1. **隧道 SETUP 请求补齐字段**（AirPlayClient.runIapTunnel）：controlType=2、
   channelID=`<本机BT MAC>-RCS-1`（内部取 setupDeviceId，与 deviceID 同源）、
   clientUUID 随机、wantsDedicatedSocket=true、sendMessageAsIs=true。
   clientTypeUUID 保持 `E9459FD0-…`（§A 查证：协议通用常量）。
2. **隧道全双工**：读密钥改 `DataStream-Input-Encryption-Key` 派生（旧实现读写共用
   Output，标准车机读方向解不开）；写出包补 8B 序号@20..28（自 1 递增）；收到
   sync 探测包回 rply（抄回对方 20..28）；comm 载荷经 onRawBytes 喂
   VirtualIap2Link（不再只当 CSM 打日志）。
3. **隧道协商挪到屏幕流 SETUP 之前**（对齐参考实现时序；且隧道成功后
   armMib3VirtualLink 的「隧道形态不布防/不拆 BT」门禁才能在屏幕流时生效——
   顺序颠倒会先按事件通道形态发 DETECT+拆 BT）。
4. **形态分派**：接收端 SETUP 应答 enabledFeatures 含 iAPChannel →
   `tunnelModernAuth`（两步式 0xAA00【param0 空值 body `00 04 00 00` 要序列号 →
   0xAA06 → 空 body 要证书】+ 记忆式挑战 + runtime 问候【4E0C/4E0B/4E0E/4E0A/
   4E09/4E0D(真实 BT MAC，占位不发)】+ 认证完成即触发 modesChanged 重申）；
   未声明（DiPlay）→ 旧半双工一步式 32B 防回归。MIB3 事件通道形态同步改两步式
   （真 iPhone 对 3315 的 AirPlay 链就是两步式，sysdiagnose 佐证）。
5. **Iap2LinkEngine**：`echoMarker`（隧道形态协商期逐条回显 FF55 探测、NORMAL 后
   忽略）+ **Re-SYN 重置**（NORMAL 后新序号纯 SYN = 重置协商态重跑、≤3 次、
   超限判死；同序号重发仍走幂等重放）+ onResynchronized 回调（上层重跑识别/MFi/
   runtime，MFi 长度记忆保留）。新增 5 条引擎单测钉死语义。

**验证要点（8015 车主测试）**：
- 日志 `TUNNEL: 接收端 enabledFeatures=[…] iAPChannel=true/false`——8015 声明与否
  直接决定走的路径（true=type130 主路径；false=事件通道兜底即 0.1.28.8 形态）；
- type130 路径成功的标志链：`✓ iAP 隧道已建立 … 形态=现代认证` → 引擎
  `marker→回显`/`SYN|ACK` → `0xaa06（auth serial）→ 再发 0xaa00` → `挑战成功已记忆`
  → `现代隧道 runtime 问候已发` → 车机是否切 CarPlay 画面；
- `sync 探测 → 已回 rply` 出现 = 标准隧道活性协议在工作；
- 回归关注：6318（mib3 两步式改造）与 DiPlay（隧道时序前移 + 半双工保持）。

## §H. 锁屏推流 + 触摸的原理（2026-10-26 追查）

### 锁屏正常推流：VirtualDevice（伴侣设备应用流式传输）路线——非 DisplayManager 打 flag

我们的路线是 Shizuku 反射 `DisplayManager.createVirtualDisplay` + 手打
PUBLIC/TRUSTED/ALWAYS_UNLOCKED/OWN_DISPLAY_GROUP 四个 flag + `setDisplayImePolicy`
反射；真休眠时窗口表面仍会被剥离（AGENTS.md 2026-10-23 已记）。正路是
**VirtualDeviceManager**（Android 34+ 官方 API）：

1. **建关联**：`cmd companiondevice associate <user> com.android.shell <车机BT MAC>
   android.app.role.COMPANION_DEVICE_APP_STREAMING false`——以 shell 包名与车机
   蓝牙 MAC 建「App Streaming profile」关联（免弹窗；已有同 profile 关联则复用，
   按 getAssociations 的 MAC+deviceProfile 匹配；close 时 disassociate + 把 role
   还给 com.android.shell）。
2. **建虚拟设备**：反射 `VirtualDeviceManager.createVirtualDevice(assocId, params)`，
   `VirtualDeviceParams.Builder().setLockState(1)`（**LOCK_STATE_ALWAYS_UNLOCKED——
   官方字段，整台虚拟设备所有窗口无视主机锁屏**）+ 自有设备命名 +
   （可选）`setInputMethodComponent`（**虚拟设备专属 IME**——虚拟屏键盘的官方解法，
   替代我们 setDisplayImePolicy(LOCAL) 反射；设置失败则无 IME 重建）。
3. **建屏**：`virtualDevice.createVirtualDisplay(...)`，flags 1354（无 PUBLIC/
   TRUSTED/ALWAYS_UNLOCKED——VDM 屏天然对外+受信+锁屏豁免），读回 displayGroupId
   确认独立 display group。
4. **每块屏 per-display 保活**（建屏后幂等执行，核心一步）：
   - 反射 `PowerManager.newWakeLock(10, tag, displayId)`——**三参版（带 displayId）
     的新 API**，SCREEN_BRIGHT 级，`setReferenceCounted(false)` + 无超时 acquire；
   - 反射 `PowerManager.isInteractive(displayId)` 非交互 →
     `PowerManager.wakeUp(uptime, 2, tag, displayId)`（**per-display 唤醒**）。
   这直接解决我们的「真休眠窗口表面剥离」：剥离发生在 display 进 DOZE/OFF，
   per-display 亮屏锁把虚拟 display 永远钉在交互态。
5. 降级路径：老系统回 DisplayManager（其 UserService 里 `createVirtualDisplay(
   "…Independent", …, 7498)`——7498 即我们同款 ALWAYS_UNLOCKED|
   OWN_DISPLAY_GROUP 组合）。三参 newWakeLock/isInteractive(int)/wakeUp(带displayId)
   是 15/16+ API（反射探测），VirtualDeviceManager 需 34+。

### DiPlay 触摸正常

与我们同构，无独门秘诀：事件通道 `hidSendReport {uuid, hidReport}` →
S2 解析器按接收端 receiverInfo 里的 **hidDescriptor** 解出触摸/按键（与我们
ConsumerKeys/TouchReports「按描述符判定」同一路线）→ `MotionEvent.setSource(
SOURCE_TOUCHSCREEN)` + 反射 `InputEvent.setDisplayId(目标屏)` +
`InputManager.injectInputEvent(event, 0)`。差异点：目标是 VirtualDevice 屏
（独立电源域 + always unlocked），锁屏时注入依然有效——触摸「正常」的一半
功劳在锁屏推流那套，不在注入本身。

### 对我们的启示

- 锁屏推流的正路可能是 **VirtualDevice 路线**（前提 Android 34+/Shizuku；荣耀
  SDK 37 满足）：setLockState(1) + 专属 IME + per-display wake lock，全部官方
  接口、零 flag 打补丁。迁移成本：associate 需要车机 BT MAC（已有）、关联的
  生命周期收口（disconnect/onEnded 两端 disassociate）。
- 最小改动方案（不迁移）：先抄第 4 步——对我们现有 7498-flag 屏补
  per-display wake lock/wakeUp（反射，失败降级现状），验证能否消除真休眠
  表面剥离。三参 newWakeLock 在荣耀（SDK 37）大概率可用。

### §H 落地（0.1.28.10，2026-10-26）

已按「最小改动」实现：CarInputServiceImpl（UserService，shell uid——只有它拿得到
per-display wakeUp 的权限语义）内闭环，零 AIDL/应用侧改动：
- 建屏成功即 `keepDisplayAwake`：反射三参 `newWakeLock(10,"CarDeck:vd",displayId)`
  （SCREEN_BRIGHT 级、非引用计数、无超时）+ 非交互时四参
  `wakeUp(uptime,2,tag,displayId)`；releaseDisplay 对应释放。
- init 注册 ACTION_SCREEN_OFF：真休眠后对所有持锁屏补一次 wakeUp（只作用目标
  display 的语义；**若实测发现主屏被点亮则 ROM 实现是全局唤醒，去掉该补唤回首试**）。
- 三参 newWakeLock 不存在（Android 15/16 前）→ 整体静默降级维持现状。
验证判据（UserService 日志 /data/local/tmp/carinput_svc.log）：
「per-display 保活就绪」「per-display 亮屏锁已持有（create …）」
「wakeUp 已发（SCREEN_OFF…）」；现象判据：推流中按电源键，车机画面不再冻结。

### §H 落地（二）：VirtualDeviceManager 路线全量实现（0.1.28.56，2026-11-06，待真机验证）

按本节 1~5 步全量落地（此前只做了第 4 步 per-display 保活）。实现要点与参考实现的
差异清单（其余照抄）：

- **全部收口在 CarInputServiceImpl（UserService）**，应用侧只加两个 AIDL 方法：
  `setCompanionDevice(btMac)`（每次建屏前传链路对端 MAC，来源
  `BtSessionGate.lastPeerAddress`——出站连接成功/入站连入时写入）与
  `closeVirtualDevice()`（VirtualLauncher.detach 与建屏失败回退分支调用）。
- **关联**：先按 MAC+profile 复用 `getAssociations` 既有关联，无则
  `cmd companiondevice associate`（UserService 内 ProcessBuilder 直跑——本进程就是
  shell uid，无需 Shizuku 转发）+ 轮询确认；**stderr 合并读取**（cmd 参数面随 ROM
  变化，荣耀 SDK 37 未实测，失败即降级 legacy 并留痕）。
- **同 MAC 不同 profile 的既有用户关联：绝不复用/改写，直接失败降级**（参考实现
  同款「existing association profile differs」）。
- **flags 分两套**：受信屏 `1355→1354` 候选（参考只用 1354；我们首选加 PUBLIC 是
  为了键盘——VDM 屏默认私有，私有屏拒绝第三方 IME 窗口，2026-10-17 定谳；参考实现
  靠 `setInputMethodComponent` 虚拟设备专属 IME 解决同一问题，我们暂不引入 IME，
  保留现有 `setDisplayImePolicy(LOCAL)` 反射，失败只退化键盘不退化推流）；dock 屏
  `330`（无 TRUSTED/PUBLIC——保「第三方任务逃不上来」语义）。
- **建屏后**照旧：`virtualDisplays` map（resize/release 复用）+ per-display 亮屏锁
  （第 4 步已有）+ 受信屏 IME LOCAL。
- **收口**：`closeVirtualDevice` = 余屏释放 → `device.close()` → **仅本次创建的
  关联 disassociate**（复用的用户级关联原样保留）→ role 丢失回填（参考 close 同款）。
  进程死亡：虚拟设备/屏随进程回收（VDM 系统侧代管），关联持久化留待下次复用——非泄漏。
- **降级链**（逐屏，legacy 一行未改）：SDK<34 / MAC 空 / 关联或设备失败（本会话禁用
  VDM）/ flags 全灭（本会话禁用）→ DisplayManager 1354 组合原路径。
- 单测 `VirtualDeviceRouteTest`（flags 候选表 / gate / role 输出分词比对）。
- 验证判据：`carinput_svc.log` 搜 `VDM:`（关联 id → 虚拟设备已创建 lockState=1 →
  `#N displayGroupId=X（独立组 ✓）`）；现象判据 = 推流中按电源键车机持续出帧、
  断开后 `dumpsys companion` 关联已清（本次创建的）。
- 已知取舍：首个 createDisplay 会一次性承担关联+设备创建 ~1-3s（复用时 ~0.1s）；
  若真机显示首帧明显推迟，优化入口 = `setCompanionDevice` 阶段提前 ensure。

### §H 落地（二）续：荣耀真机联调定谳（2026-11-06，DiPlay 台）

通路已真机打通（关联复用/清道夫/虚拟设备创建 lockState=1/四屏建设备上/触摸·keepAlive·
地图启动全链路正常），过程中修正三处对参考实现的假设：

1. **k5 ContextWrapper 是必需品**（原判「belt-and-braces」错误）：`VirtualDeviceManager`
   构造要取 `context.getAttributionSource()`，createPackageContext 的 shell 包 Context
   该值为 null → NPE。已实现同款 `AttributionContext`。
2. **ROM 常量名 `LOCK_STATE_ALWAYS_UNLOCKED=1`**（非 AOSP 文档名
   `LOCK_STATE_UNLOCKED`）→ 枚举常量按候选名匹配 + 全量落日志。
3. **`cmd companiondevice` 命令面在荣耀上残废**：旗标形 associate 抛异常却仍建
   「本机 MAC」垃圾关联（实测发生，靠清道夫回收）；disassociate 全格式解析失败 →
   清理走 binder。**位置形 associate 可用**（= 参考实现所用形式，我们改为主选）。
   另实测：CDM 关联创建时自动授予对应 role（无需手动 add-role-holder）。
4. **displayGroupId 组归属是确定行为，非竞态**：带 TRUSTED 的屏被系统挪进虚拟设备
   自己的 display group；不带 TRUSTED 的 dock 留在默认组——**真休眠实测 dock 照样
   state ON、帧照出**（per-display 亮屏锁 + VDM 电源语义兜住），读回只作日志。
5. 排障方法教训：VDM 反射链失败的日志**必须根因解包**——首轮真机失败日志只打了
   InvocationTargetException.message（恒 null），差点归因成「ROM 不支持」。

**真休眠核心场景已真机验证（2026-11-06）**：推流中按电源键休眠 1 分钟——帧/音频/
触摸/keepAlive 全程持续，dumpsys 证实物理屏已灭而四块虚拟屏 state=ON，唤醒无缝恢复。
待验证：断开收口后 CDM 列表状态（复用的关联按设计保留）。

### §G 增补：DiPlay 首测（2026-10-26 17:15，修正前）

补字段后 **DiPlay 的 enabledFeatures=[iAPChannel, viewAreas]，type130 隧道全双工建立
成功**（dataPort=39533，形态=现代认证）；链路 NORMAL（探测回显/协商工作）→
两步式 0xAA00 后 DiPlay **跳过 0xAA06 直接回 0xAA01**（兼容两步式）→ 记忆挑战
32B 一次成功 → 0xAA05 → runtime 问候 → RECORD → 推流 3 帧 + 心跳全通。
**2.3s 后 DiPlay 整栈下线**：根因是问候里 4E0D/4E0E 内容填反（见 §C 修正）。
修正后待复测；若仍断，下一变量是 modernTunnel 形态新触发的 modes 资源重申
（断链前 54ms 发出过一条 +1s 重申）——届时 A/B 关闭该重申验证。
