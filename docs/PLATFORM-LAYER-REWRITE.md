# 平台层重写设计（2026-11-03 对接期定稿——用户令：直接重写，不做渐进清理）

> 原则：Rust 侧连接链路已就绪（fw::runtime 编排泵 + fw::endpoint 协议栈 +
> fw::airplay RTSP/事件/时序 + fw::pairing），Kotlin 只保留**平台能力薄壳**。
> 本文档是新平台层的施工图；旧编排文件（SessionController/AirPlayClient）
> 在新壳接线验证后整文件删除。

## 一、现状盘点（哪些是平台能力、哪些是编排）

### 保留不动（平台能力，已是好形态）
| 层 | 文件 | 行数 | 职责 |
|---|---|---|---|
| BT | bt/*（Iap2BluetoothServer/Client/BtProfiles/BtSession/CarBluetoothReceiver/AutoConnectPrefs/TargetDevicePrefs） | ~1082 | RFCOMM socket、ACL 事件、自动连接 |
| 会话薄层 | transport/Iap2LinkChannel | 280 | nativeSessionCreate/FlowStart/SetTransport/SetEventSink/Feed/Tick 薄壳（已是理想形态） |
| WiFi/net | net/*（WifiCarPlayConnector/EndpointHosts/CarPlayServiceBrowser/CarPlayCtrlAdvertiser/TcpLiveness/WifiConnectCommand/WifiSavedNetworks/AirPlayFeatures） | 1246 | WiFi 加入/绑定、mDNS 浏览 |
| 音频硬件 | audio/*（AudioSender/AacLcEncoder/AudioSyncPacket） | ~1000 | MediaCodec 编码 + RTP 发送 |
| 视频硬件 | VideoSender/HiddenDisplaySource/ScreenDimmer | — | MediaCodec + 虚拟屏 |
| 注入 | input/*（ConsumerKeys/HidNavigation/TouchRouter） | — | HID 报文→按键注入 |
| JNI 声明 | protocol/NativeBridge | 458 | 全部 external fun（66 个） |

### 重写（新壳，替代编排层）
**R1 修正（2026-11-03 侦察定谳）**：iAP2 域主控壳**已存在**——
`Iap2BluetoothServer.runSession → Iap2LinkChannel.runSenderSession`
（create/flowStart/setTransport/setEventSink/reader/pacer 全套 + 平台动作
回调 onEndpoint/onWifiCredentials/onEndpointUpdate/onLinkClosed 上抛）。
iAP2 域（识别→认证→通知波→vehicle/location→WiFi→4300）已全在 Rust 泵 +
回调薄层，无需新壳。~~CarPlaySession.kt~~ 骨架初稿与现有薄层冲突已删。

| 新文件 | 替代 | 核心内容 |
|---|---|---|
| **AirPlayLink.kt**（~450 行） | AirPlayClient（2011 行） | AirPlay TCP socket + nativeAirplay* 泵（Create→Info→SetIdentity→StartPairing→FlowStep→SetupAuto→ControlSetup→StreamSetup→Announce→Record→Event*/Timing*）+ keepalive UDP + 流 socket 面 + 事件通道命令 POST。**协议决策全在 Rust**，壳只搬运字节与调度媒体硬件 |
| **SessionController 收缩**（3020→~1500） | — | 保留：UI 粘合/WiFi 加入族（net/ 调用策略）/音频路由/屏幕开关/onCarPlayEndpoint 的流启动时序（真机实证：SETUP→连端口→RECORD→编码器→音频 SETUP 2s 看门狗序）；删：端点合并族（browser enrich/merge/prefer）、awaitCtrlInvite、watchForPendingStartSession、resolveEndpointHost（B11 定谳后简化）|

### 删除（编排层，新壳验证后整文件删除）
SessionController.kt（3020——其中 WiFi 族编排方法随迁 CarPlaySession 简化版）、
AirPlayClient.kt（2011）、NativeTunnelIap2Link（165）、Iap2SenderSession（288）、
Iap2Csm（105）、Iap2MessageIds（已删）、BinaryPlist（465——Kotlin bplist 解析，
Rust plist 已有）、AudioProfileMemory（204——D7 经验记忆，Rust 声明集替代）、
ControlCipher（77）+ AirPlayCrypto（124）**暂保留**（E3 数据面加密仍在
Kotlin——见 §三 Phase B）、TimingServer（125——fw::airplay::timing 已建 E4，
UDP socket 面保留于 AirPlayLink）。

### MainActivity 粘合改造
SessionController 的 UI 面（logListener/uiHintSink/dialogSink/ssidSink/
audioEnabledForSession/startConnection/cancelConnecting/disconnect）在
CarPlaySession 上原样暴露（对象单例同形态），MainActivity 调用点
（~20 处）机械替换。

## 二、事件契约（Rust→Kotlin 平台动作，不变）

EventSink 正值 = iAP2 消息诊断镜像（过渡期）；负值 = 平台动作：
- `-1` EV_WIFI：热点凭据（ssid\0psk\0ch\0sec）→ joinCarWifi 动作
- `-2` EV_ENDPOINT：CarPlay 端点（port u32 BE + ident\0 + pubkey\0 + srcvers\0 +
  ip\0… + wifi 块）→ 启动 AirPlayLink
- `-3` EV_PHASE1_DONE：iAP2 会话就绪（诊断用）
- 正值镜像：0x6802（HID 报文→ConsumerKeys/HidNavigation 注入）、
  0x4300/0xA101 等（导航消费，对接期逐步转动作）

## 三、分期施工

### Phase R1（修正）：编排段收缩（无需新主控壳）
1. SessionController：删端点合并族/awaitCtrlInvite/watchForPendingStartSession/
   resolveEndpointHost；onCarPlayEndpoint 保留流启动时序（平台调度）
2. AirPlayLink.kt 新壳替代 AirPlayClient（泵+字节面；协议决策在 Rust）

### Phase R2：AirPlayLink 流壳
1. 新建 AirPlayLink.kt：TCP socket + nativeAirplay* 泵 + keepalive UDP +
   Timing socket + 流端口（audio/video dataPort socket）
2. 加密组件：ControlCipher/AirPlayCrypto 暂以传输组件保留（E3 catplay
   派生双界验证；迁移 Rust = Phase B 数据面统一）
3. AudioSender/VideoSender 由 CarPlaySession 经 AirPlayLink 的事件驱动

### Phase R3：删除日
旧六文件删除（~6300 行）；CarPlaySession/AirPlayLink 定稿；
JNI 注入面接线完成度盘点（set_platform_values 等）。

## 四、纪律（重写期间同样有效）

- 新壳内**零协议逻辑**：任何字节构造/决策疑问都以 `fw:` 锚定在 Rust 侧
  加能力，Kotlin 只搬运与执行
- 每步：Kotlin compile + JVM 测试 + spotless/lint + 装机存活
- 真机差分清单不变（§11）：E3 HKDF 内参 / D7 位域 / D8 流描述 / B10 0x5001

## 五、进度

### 已完成（2026-11-03 Phase A 四刀，随刀装机验证 PID 存活零崩溃）
- ✅ pass1（2af36ae）：MIB3 隧道 handoff 死链（armMib3 族/IapTunnelClient
  250 行/btHandoff 状态族/detachBtForMib3Handoff）+ **routeOutbound Audi
  出站丢弃 bug 修复**（收敛事件通道 iAPSendMessage）
- ✅ pass2（6e29b4c）：skipPairing/reconnect/postControlIap+controlIapTx 族/
  pairingUnsupported/attemptedPairing/Iap2MessageIds.kt 孤儿文件
- ✅ pass3（70047e5）：setupDeviceId/persistentTunnelChannelMac/uuidText +
  **/info modes 断链修复**（logInfoSummary 零调用致 modesState 初始化源
  丢失——info 事件派发处恢复调用，JSONObject→Map 适配）
- ✅ pass4（6c3e652）：CONTROLLER_FEATURES/音量探测族/PAIRING 窗口族常量 +
  controlEncrypted/pairSetupRejected/cSeq/hidEvents 孤儿属性
- ✅ protocol 侧 host.rs（b177816）：拉模型残留退役，trait 收缩到
  {bt_write, uptime_ms}——推模型对接说明见 src/host.rs 头注释

### 待做（压缩后按序开工）
- [ ] **R1 编排段收缩**：SessionController 删端点合并族（enrichEndpointFromBrowser/
      mergeBrowserEndpoint/preferEndpoint/drainPendingEndpoint/
      watchForPendingStartSession/onLateEndpoint/txtValue）、awaitCtrlInvite、
      resolveEndpointHost（B11 定谳后简化——地址集 Rust 透传，连接时直接用）；
      onCarPlayEndpoint 保留流启动时序（SETUP→连端口→RECORD→编码器→音频
      SETUP 2s 看门狗序=平台调度）
- [ ] **R2 AirPlayLink 壳**：新文件替代 AirPlayClient（2011 行）——TCP socket +
      nativeAirplay* 泵搬运 + keepalive UDP + Timing socket + 事件通道命令
      POST；ControlCipher/AirPlayCrypto 暂以传输组件保留（E3 双界验证）；
      AudioSender/VideoSender 调度接线
- [ ] **R3 删除日**：SessionController 残余编排/AirPlayClient/NativeTunnelIap2Link/
      Iap2SenderSession/Iap2Csm/BinaryPlist/AudioProfileMemory/TimingServer
      （~3000 行）——新壳接线验证后整文件退役
- [ ] **JNI 注入面接线**：set_platform_values（设备名/语言/时区）、
      set_vehicle_status_categories（0xA100 类别）、set_location_requests/
      set_nmea_sentences（0xFFFA/0xFFF0）——Kotlin 对接调用
- [ ] **真机差分清单**（§11 坐标）：E3 HKDF 内参 / D7 位域枚举 / D8 流描述 /
      B10 0x5001 copyDefault 字节域
