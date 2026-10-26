# 无线 CarPlay 发送端协议笔记

> 本项目目标：Android 手机扮演 iPhone（CarPlay 发送端），连接 CarPlay 车机（接收端）。
> 本笔记综合四个本地参考仓库（`reference/`：DiPlay、xcertplay、catplay、
> carplay-wifi-extractor，已 gitignore）与公开逆向资料整理。
> **测试台接收端实际使用 DiPlay**（xcertplay 的衍生版，`reference/DiPlay/`）；
> 代码精读路径多为 xcertplay（`reference/xcertplay/`），两份源码同源、协议逻辑一致。
> 标注 ✅ 的为已在参考代码中逐行确证；标注 ❓ 的为待验证项。

## 1. 角色与认证模型

| | 接收端（车机角色） | 发送端（iPhone 角色，本项目） |
|---|---|---|
| 承担者 | 奥迪车机 / DiPlay（测试台） | 本 app |
| 需要苹果签名身份 | ✅ 是（MFi 证书/芯片，被手机验证） | ❌ 否 |
| 验证对方身份 | ❌ 否（单向认证，Oligo DEF CON 2025 证实） | 可选（测试台阶段直接跳过） |

**关键结论**：iAP2 认证是单向的——手机验证车机的 MFi 凭据（0xAA00 段挑战应答），
车机完全信任手机自报的验证结果。因此发送端**不需要**任何 Apple 签名材料，
DiPlay 从 Carlinkit 固件提取的证书对发送端无用（那是接收端身份）。

## 2. 分层结构（自底向上）

```
┌────────────────────────────────────────────────────────┐
│ AirPlay 主会话: RTSP 风格控制 + H.264/H.265 流 + HID     │  Phase 2
│   承载: WiFi TCP（车机 AirPlay 端口, 来自 0x4301）        │
├────────────────────────────────────────────────────────┤
│ mDNS/Bonjour: 车机广播 _airplay._tcp；手机广播           │
│   _carplay-ctrl._tcp（车机反连此端口）                    │
├────────────────────────────────────────────────────────┤
│ iAP2 控制会话 (CSM): 0x4040 分帧 + TLV 参数体            │  Phase 1
├────────────────────────────────────────────────────────┤
│ iAP2 链路层: FF55 SYN / FF5A 数据帧 + 校验和             │  Phase 1
│   承载: 蓝牙 RFCOMM（无线）/ USB bulk（有线,已排除）      │
└────────────────────────────────────────────────────────┘
```

## 3. 链路层（✅ 已按 xcertplay Iap2LinkEngine.kt 精读修正，2026-10-08）

数据帧头（大端，9 字节 + 载荷 + 载荷校验和）：

```
FF 5A | u16 总长 | u8 控制 | u8 seq | u8 ack | u8 会话ID | u8 头校验和 | 载荷 | u8 载荷校验和
```

**Phase 1 实测修正（与早期笔记的差异）**：
- 握手不是「FF55 控制帧」：双方互发 marker `FF 55 02 00 EE 10`，然后发同步包
  （FF5A 帧，control=0x80，载荷 = 参数协商结构 `>BBHHHBB` + 会话描述符×3）
- **无线模式下手机是同步发起方**：配件（openWireless, initiateNegotiation=false）
  只发 marker 等手机先发同步包；双方收到同步包即回 ACK，协商态收到 ACK 进 NORMAL
- **会话 ID 是 10（控制）/11（EA）/12（文件传输）**，不是 Oligo 笔记里的 0/1/2
  （那可能是蓝牙承载变体；以能互通 DiPlay 的 10/11/12 为准）
- 序号 u8 回绕，初始 99；数据帧 control=0x40（捎带 ACK）
- 控制位：SYNC=0x80, ACK=0x40, EAK=0x20, RST=0x10
- 校验和 = 字节和的补码（含校验和在内 mod 256 == 0），头/载荷各一个
- 无线参数参考（DiPlay/xcertplay WIRELESS_LINK_CONFIG）：maxOutgoing=4,
  controlSessionVersion=2, 保留 ACK（有线用 zero-ack 模式）
- CSM 大帧按对端协商的 maxLength 分片，接收端经 CSM 解析器重组

**本项目代码**: `app/src/main/java/.../iap2/Iap2LinkEngine.kt`（完整状态机，已回环测试）

## 4. CSM 分帧与 TLV（✅ xcertplay `iap2/wire/Iap2CsmFramer.kt`）

```
帧:   0x4040 | u16 总长(含头6字节) | u16 消息ID | 体       （大端, 无校验和）
参数: u16 参数总长(含4字节头) | u16 参数ID | 值             （体 = 参数序列）
字符串: NUL 结尾 UTF-8；嵌套参数 = 参数值为子参数序列
```

**本项目代码**: `app/src/main/java/.../iap2/Iap2Csm.kt`（帧编解码 + 增量解析器 + TLV 构建器已实现）

## 5. 无线控制时序（✅ xcertplay `Iap2WirelessControlClient.kt` + extractor README）

蓝牙 RFCOMM 建链（两设备需先系统级配对）后：

```
配件 → 手机: FF55 SYN                          （链路层握手由谁发起 ❓ Phase 1 验证）
手机 → 配件: StartIdentification      (0x1D00)
配件 → 手机: IdentificationInformation (0x1D01)   车机身份 + 收发消息能力清单
手机 → 配件: IdentificationAccepted    (0x1D02)
配件 → 手机: MFi 认证序列              (0xAA00 段)  ← 手机侧可跳过校验 ❓细节待读
手机 → 配件: RequestAccessoryWiFiConfiguration (0x5702)   ← **必备**：四个独立
                                                   实现都声明它；车机漏声明也要发
                                                   （见 §5b / §9j）
配件 → 手机: AccessoryWiFiConfiguration (0x5703)   明文 TLV: SSID/密码/信道/安全类型
   （安全类型: 0=None 1=WEP 2=WPA/WPA2 3=WPA3过渡 4=WPA3）
手机加入车机热点 → mDNS 解析 _airplay._tcp
手机 → 配件: CarPlayAvailability      (0x4300)
配件 → 手机: CarPlayStartSession      (0x4301)   无线参数 + 车机IP + AirPlay端口
                                                    + deviceIdentifier + 公钥 + sourceVersion
手机 → 配件: WirelessCarPlayUpdate    (0x4E0D)   通知无线 CarPlay 可用
手机 → 配件: DeviceTransportIdentifier(0x4E0E)   蓝牙/USB 标识，收到后配件重发 0x5703
配件 → 手机: StartHID                 (0x6800)   Phase 2: 触摸回传通道
手机 → 配件: DeviceHIDReport          (0x6801)   应答
```

0x1D01 身份参数布局（发送端需要解析；字段 id → 含义）：
0=name, 1=modelIdentifier, 2=manufacturer, 3=serialNumber, 4=firmwareVersion,
5=hardwareVersion, 6=发出的消息ID列表(u16[]), 7=接收的消息ID列表(u16[]),
8=电源类型, 9=最大电流(mA), 10=EA协议组, 12=语言, 13=语言列表,
16=USB传输组件(有线), 17=蓝牙传输组件(无线), 18=HID组件, 24=无线CarPlay组件(含SSID)。

## 5b. 0x5702 是「必备但常被漏声明」——四个独立实现的外部佐证（2026-10-22）

起因：0.1.13 在奥迪 MMI 3315 上从「能连」变成「连不上」。8b64147 把 WiFi 凭据
分派写成「车机可收清单里没有 0x5702 就跳过旧协议」，而奥迪稀疏清单（发 2 项 /
收 5 项）不含它。结果整轮一次 0x5702 都没发，车机 AirPlay 服务始终没起来
（端口 7000 全程 ECONNREFUSED），4 轮会话每轮 8 秒自拆。

**四个独立实现全部把 0x5702 列进「车机可接收」清单**，说明它是规范要求的必备项：

| 项目 | 角色 | 证据位置 |
|---|---|---|
| catplay | 车机（rx gadget） | `catplay_carplay_rx_gadget/src/iap2_session.rs`：`(identity.wifi_ssid.is_some(), &[RequestAccessoryWiFiConfigurationInformation::PACKET_ID])` |
| xcertplay | 车机 | `Iap2IdentificationClient.kt:315`：`WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)` |
| amineross/showcase | 车机（越狱 iPhone） | `source/carplay_bt.m` 的 `msgs_recv[]` 含 `0x5702 /* RequestAccessoryWiFiConfigInfo */` |
| wiomoc/iap2 | 车机（Python） | `iap2/__main__.py` 的 `msgs_recv` 含 `RequestAccessoryWiFiConfigurationInformation` |

**推论**：缺席的 0x5702 只能说明「清单不完整」，不能反证「不支持」。现实车机常漏写
（奥迪 MMI 3315 就是），而它们对未声明消息照常处理——同日日志里 `0x4e0d/0x4e0b/0x4e0e`
三条不在清单里，车机逐条 ACK 到位（seq 107→ack=107、110→ack=110、113→ack=113）。

现行分派只认 `ReceiverProfile.wifiGen`：`NEW`（两清单合计 ≥10 项仍无 0x57xx，
BMW iD9 形态）才跳过 0x5702；`LEGACY` 与 `UNKNOWN` 一律发，等待窗口 1.5s
（奥迪八轮实测应答 122/136/166/166/198/246/266/368ms）。

### 0x5703 的两个发送时机（传输通知前 / 后）

`amineross/showcase` 的 `send_accessory_wifi_config(phase)` 区分 pre-/post-transport：
`mfi_auth_succeeded → wireless_carplay_connecting_seen → transport_notification_seen
→ wifi_config_sent_post_transport`（最多重发 5 次）。它的注释记录了一个反例：

> iPhone8,1 / iOS 15.8.3 上，对端在 0x4E0E **之前**就请求 WiFi 配置，旧代码因此先发
> 了 0x4301，然后网络阶段再也没启动过。

即顺序本身不一定致命（我们 10-19/10-20 的奥迪日志里 0x5703 早于 0x4e0d/0x4e0e
到达也照常成功），但**时序敏感是真的**。我们的保活泵对重发的 0x5702 会重新应答
（`Iap2SenderSession` 的 `wifi` 缓存），与 showcase 的重发语义一致。

### 两种端点发现流（Apple 官方口径）

`harman-f/mhi2_altscreen_carplay` 的 `docs/research/mhi2-native-wireless-2026-10/
11_CONNECTION_FLOW_VARIANTS.md` 整理了 Apple 的两套设计：

```
A. 经典流（WWDC 2017）：
   配对 → BT iAP2（识别+MFi+传输标识）→ 凭据交换 → WLAN 关联
   → Bonjour browse/resolve → Controller Connect → 会话 → iAP2 交接 → ACTIVE

B. 简化流（WWDC 2023，iOS 14+）：
   iAP2 里直接交换 IP 端点 + TCP 端口 → 直接建会话 → 安全+传输交接 → ACTIVE
   （**不需要 Bonjour**，支持 WPA3-only）
```

同一文档强调 Apple 的显式警告：**WLAN 关联之后、CarPlay/iAP 交接到达预期状态之前，
蓝牙/iAP2 都不能拆**。这条正是「每轮 8 秒自拆链 = 永远等不到邀请」的理论依据——
真 iPhone 实录里车机入网后约 21 秒才发 `StartSessionHost`。

对应实现：奥迪 MMI 3315 的 TXT `features=0x4040280,0x61`（bit37 CARPLAY_CONTROL
与 bit38 HK_PAIRING 均置位）、`srcvers=450.14.2`，实际走 A（mDNS）+ B（0x4301）混合。

### 一条与 iOS 18 相关的反例（不适用于奥迪，但要知道）

`amineross/showcase` 故意**不声明** 0x4301，注释解释了原因：

> iPhone 16 Pro / iOS 18 rejects identification if 0x4301 is in msgs_sent。
> 而 0x4300 不能加进 msgs_recv——iPhone 校验 0x4300/0x4301 是强制配对，
> 缺 0x4301 就拒绝。所以整个 0x43xx 路径在 iOS 18+ 上是堵死的。

奥迪并不受此限：它的发送清单里明确有 `0x4301`（日志 `车机发送清单(2)=0x4301 0xea03`）。
**相反，它愿意声明 0x4301 却漏掉 0x5702**——进一步说明清单是不完整，不是能力受限。
改这条相关代码时，别把 showcase 的「iOS 18 限制」当成所有车机的通则。

## 6. mDNS/Bonjour（✅ xcertplay `network/CarPlayBonjour.kt`）

**车机侧**广播 `_airplay._tcp`，TXT 记录：

```
deviceid=AA:BB:CC:DD:EE:FF   features=0x44540380,0x61   flags=0x4
model=...  srcvers=...  protovers=1.1
pi=<配对ID>  pk=<ed25519公钥hex>
```

**手机侧**广播 `_carplay-ctrl._tcp`（TXT: `id=<蓝牙MAC>`），车机 resolve 后**反连**该端口
作为控制通道（即 iAP2 控制会话从蓝牙迁移到 WiFi TCP 的载体）。
测试台（DiPlay，沿袭 xcertplay）偏好 link-local IPv6，回落 IPv4——实测 ctrl 探测
源地址为 `fe80::…%wlan0`。

## 7. 蓝牙 RFCOMM（✅ xcertplay `CarPlayController.kt:1591,1963`）

- iPhone（发送端）监听 SDP 服务：UUID `00000000-deca-fade-deca-deafdecacafe`
- 配件端 UUID：`00000000-deca-fade-deca-deafdecacaff`
- 测试台（DiPlay）的连接策略：扫描**已配对**的 `PHONE_SMART` 设备 → 逐个连接上述 UUID
- 测试台因此只需：两台手机系统级配对 → 本 app 监听 → 备用机 DiPlay 回连

## 8. AirPlay 主会话（Phase 2，仅索引）

- 参考实现：catplay `carplay/catplay_carplay/src/carplay_tx/`（发送端 1959 行）+
  `screen/tx/`（视频发送会话）+ `rtp/record/`（发送侧 RTP）+ `pairing/pairing_helper_tx.rs`
- 加密：ed25519 PairSetup/PairVerify（HomeKit 风格）+ RTP AES/ChaCha
  （接收端参考实现在 xcertplay/DiPlay `airplay/PairSetup.kt`/`PairVerify.kt`/`Srp6a.kt`）
- 视频：H.264/H.265，AVCC/AnnexB 封装转换（catplay `video/`）
- 时钟：NTP 风格 timing 端口 + RTCP（catplay `clock/`）
- ❓ FairPlay/流加密细节无公开规格，靠 catplay 源码 + ludwig-v 解密固件对照

## 8b. 音频通道（✅ 已实现：手机 → 车机主音频流）

**协商**：在已加密的 RTSP 会话上 `SETUP`，body = `streams:[{type:100(主音频),
streamConnectionID, audioFormat:<位图>, audioType:"media"}]`；接收端应答
`{type:100, dataPort:<UDP>, controlPort:<UDP>, streamConnectionID}`。
- 音频格式位图（catplay `msg/streams.rs` AudioFormat）：PCM 48k 立体声 = `1<<15`、
  AAC-LC 48k 立体声 = `1<<23`；当前实现用 PCM 48k 立体声（协议基线）
- 流密钥与视频同规则：`HKDF(shared, "DataStream-Salt<音频 connId>", "DataStream-Output-Encryption-Key")`
- 音频流 ID 独立（用自己的 connId），与视频互不影响

**线格式**（RTP over UDP；接收端 DiPlay `AudioStream.kt` 解密路径证实）：
```
[RTP 头 12B][ChaCha20-Poly1305 密文 + 16B tag][8B 小端 nonce]
头: v=2 | M=0 PT=<stream type=100> | seq u16BE | timestamp u32BE(累计样本帧) | ssrc u32BE
AAD = 头字节 4..12（timestamp+ssrc 的 BE 表示，catplay "modern AAD"）
nonce(12B) = 4 字节零 + 尾 8 字节小端计数（逐包递增）
```
- 每包 512 样本帧（48000Hz 立体声 16bit = 2048B 负载，整包 ~2084B；
  接收端 UDP 收包缓冲 4096B，安全）
- 采集：`AudioPlaybackCapture`（Android 10+，需要 MediaProjection 授权 + RECORD_AUDIO）
- 当前为单向（媒体/导航下车机）；麦克风上行（通话）未实现

**本项目代码**：`audio/AudioSender.kt`（采集+封包+UDP）、
`airplay/AirPlayClient.runAudioStream()`（协商）、单测 `AudioSenderTest`（解密回环）

## 9. 真车（奥迪 MIB3）Phase 3 风险清单

1. catplay 兼容表记录：Audi/Porsche HARMAN MIB2「角色切换后对 dongle 断电重启」——
   但那是 USB 有线方向；无线方向未知
2. 用户车机为 MIB3（2020 款后），无公开兼容记录，全新未知
3. 真车首次配对：需要先有「系统级蓝牙配对」，奥迪车机通常要求先插 USB 才开放无线
   配对（❓ 关键未知项：能否纯蓝牙触发。DiPlay 测试台无此限制）
4. WifiNetworkSpecifier 不支持程序化传入密码（系统弹窗输入）——0x5703 拿到密码后
   无法静默入网，需要用户每次确认（❓ Phase 3 实测影响）

## 9b. 奥迪 MIB3 实测记录（2026-10-09，Audi_MMI_6318 / MHI3_AS_AU_P3801）

车机识别为 `AUDI 3260 / DELPHI / CL37_MIB3H_AU_AS_G4x_2200910PROD`。
蓝牙 SDP 广播配件端 iAP2 服务 `…decacaff`（系统级蓝牙配对后即可连，无需 USB 触发）。

**实测通过（逐字节对照修正后）**：

| 环节 | 结果 | 关键点 |
|---|---|---|
| RFCOMM 双向 | ✅ | 车机也主动回连（约 60s 一轮）；双向都工作 |
| 链路协商 | ✅ | 车机发 SYN 声明会话表 `{1:control, 2:EA}` → 手机必须**ACK + 原样回显该表**；之后 CSM 走**1 号会话**（发 10 号被静默丢弃，链路层照常 ACK——极具迷惑性） |
| 识别 | ✅ | 0x1D01 收到 |
| MFi | ✅ | **20 字节挑战**（32B 静默但不打断链）；0xAA03 签名 132B |
| WiFi 凭据 | ✅ | 0x5702 → 0x5703 ssid=Audi_MMI_6318_5GHz ch=149 psk 明文 |
| ctrl 邀请 | ✅ | 车机在 WiFi 上发现手机 `_carplay-ctrl._tcp` 后 `GET /ctrl-int/1/connect`（死锁关键：手机必须**在 0x5703 到达时立即**绑 WiFi + 广播带**真实蓝牙 MAC** 的 ctrl） |
| mDNS 端点 | ✅ | 车机 `_airplay._tcp` 上线于 10.174.189.1:7000，TXT: `features=0x4040280,0x61 srcvers=320.17.7 flags=0x4 model=AUDI 3260 deviceid=54:64:DE:5E:51:B0`（**无 pk 字段**） |
| AirPlay /info | ✅ | RTSP 200/455（在线） |
| pair-setup | ❌ 403 | 车机不接受匿名 SRP 配对（iAP2 已完成 MFi 信任） |
| pair-verify 快路径 | ⏳ | 403 后转「已配对设备」pair-verify，待实测 |

**2026-10-11 复测补充（新增两条经证实的坑）**：

1. **奥迪对 32B MFi 挑战的行为不稳定**——10-09 是「静默不断链」，10-11 实测
   车机在 32B 挑战后 ~0.4s **直接拆链**（此前 0xAA01 证书 912B 已正常收到，
   说明识别阶段全对）。若每次会话都从 32B 撞起会反复断链。
   修复：`Iap2SenderSession` 记「被拒档位」，本进程内后续会话直接跳过该长度。
2. **客户端线程泄漏会让车机错乱**（10-11 排查发现 3 个 `iap2-bt-client` 并发运行）：
   `BluetoothSocket.connect()` 是阻塞调用，旧代码成功后才登记 socket，
   `stop()` 关不到正在 connect 的 socket → 线程滞留；用户再次「启动连接」后
   旧线程因共享 `running=true` 复活 → 多个 RFCOMM 会话并发连车机，
   车机表现为随机拆链/时序错乱。修复：世代号（start/stop 递增，过期线程自杀）+
   connect 前登记 socket（保证 stop() 能 close 打断阻塞）。

**奥迪与测试台（DiPlay）的差异清单**：
1. 0x4301 因**车机型号/固件而异，不能按「奥迪」一概而论**：
   - **Audi MMI 6318（2026-10-09/11 全通那台）**：不发 0x4301——识别帧声明可收
     `0x4e0c 0x4e09 0x4e0d 0x4e0e 0x5702 0xea00 0xea01`、可发 `0x5703 0xea03`；
     AirPlay 端点只能走 WiFi 的 mDNS `_airplay._tcp`（10.174.189.1:7000）
   - **Audi MMI 9310（AUDI 5810 / DELPHI，2026-10-18 用户实测）**：识别帧
     **明确声明会发 0x4301**（`车机发送清单(2)=0x4301 0xea03`），但实际在 Phase 1
     结束后 **2 分 35 秒**才发（车机侧 CarPlay 窗口刚开）；此时我们的 3s 等待窗口、
     4s ctrl 邀请窗口、120s 配对窗口**全部已过期**。若把它当心跳丢弃，车机等 15s
     无人连入即拆 RFCOMM → 用户看到「连上后自动断开」。
     另：该车热点网关是 `10.174.189.254`（不是 .1），网关兜底端口 7000 仍 Connection
     refused——是否端口不同未定谳（该轮 mDNS 全程未发现 `_airplay._tcp`），
     下一轮日志的 `iap2 0x4301 原始体` 可确认它是否携带 IPv4/端口。
   - 结论：**0x4301 必须作为生命周期事件处理**（迟到也要解析并触发配对），
     超时日志不得写「该车机不发」。
2. 车机**主动发 SYN**（测试台等手机发）；会话表 {1,2} 且必须回显
3. **MFi 挑战长度双兼容（重要坑，2026-10-10 踩过）**：
   - DiPlay（Carlinkit AuthV3 EC 身份）：**只接受精确 32B**，收到 20B **直接断链**
     （实测 70~350ms 断；表现为「连不上 DiPlay + 挑战期间链路断开」）；应答 ~0.2s
   - Audi MIB3：对 32B 静默（不断链）或拆链（10-11 实测两种都出现过），对 20B 秒答
   - 结论：顺序必须 **32B(3s) → 20B(6s) → 16B(3s)** 回退。反过来 20B 优先会打死 DiPlay；
     对奥迪的 32B 拆链波动由「被拒档位」记忆自愈（见下方 2026-10-11 补充）
4. `pair-setup` 被 403（测试台接受 3939 SRP）
5. AirPlay 端口窗口式开放（ctrl 邀请接受后短暂开放；未受邀时 Connection refused）
6. **不要断 BT 控制会话**（测试台 hack 的前提是 DiPlay 重建栈；奥迪会在断链后拆掉
   整轮 AirPlay 窗口）

**设备身份（0x4300/0x4E0E/ctrl TXT id 用）**：真实蓝牙 MAC 必须动态解析
（Android 11+ 应用层只能拿到占位 `02:00:00:00:00:00`）。
Shizuku shell 侧读法：`content query --uri content://settings/secure/bluetooth_address`。

## 9c. 奥迪 MIB3 全通记录（2026-10-11，Phase 3-C 验收）

iAP2 → AirPlay 配对 → 视频推流 → 触摸回传 → 虚拟屏注入，全链路可交互。
本日在 §9b 基础上攻克的最后一批协议差异：

| 环节 | 根因 | 修复 |
|---|---|---|
| pair-setup/pair-verify 403 | 配对请求缺 Apple 标识头 | `Content-Type: application/pairing+tlv8` + `X-Apple-HKP: 2`（pair-verify 再加 `X-Apple-PD: 1`）；M1 加 Method=None(0)+State(1) TLV |
| 屏幕流 SETUP 400 | ① timingPort=0 ② features 只提 videoPlayback（接收端算交集得空） ③ 缺资源声明 ④ 身份自报用车机版本 | TimingServer（32B RTCP NTP，pt=0xD2 请求/0xD3 应答，回显 t3+本地单调钟）；CONTROLLER_FEATURES 完整清单（uiContext/viewAreas/…共 14 项）；SETUP 前发 modesChanged（resourceID 1/2 entity=1）；UA/sourceVersion=iPhone 自报 566.25.21 + osName/osBuild 等全字段 |
| 会话 ~10s 被拆 | 缺双心跳 | keepAlive UDP 4B ping →车机 keepAlivePort（10s）；POST /feedback 空体（1s）。两者独立看门狗 |
| 触摸无报文（一） | 车机命令路径错 | 命令必须发**事件通道 socket** 上的 POST /command（Events 密钥、独立 CSeq、fire-and-forget）；RTSP 控制连接上的 200 是假确认 |
| 触摸无报文（二） | 未武装 HID | SETUP 后对 /info hidDevices[].uuid 逐个发 hidSetInputMode {hidInputMode:0, uuid}；**奥迪 uuid 是字符串 "0"/"1"**（DiPlay 是整数） |
| 触摸无报文（三） | 事件通道是双向 RTSP | 每条来向请求立即回 RTSP/1.0 200 OK（加密）；不应答→车机塞 3 条就断通道（「能滑一下」） |
| 触摸坐标 | 奥迪 HID 描述符 | uuid=1 eso_touchScreen：5 字节 `[tip 1bit][X u16LE][Y u16LE]`，X≤1412/Y≤654=屏幕分辨率绝对坐标；uuid=0 是 Consumer 媒体键（非触摸） |
| 虚拟 iAP2 链路 | iAPSendMessage 语义 | 事件通道 iAPSendMessage.data = 链路层字节流；marker=DETECT，车机（客户端）每 200ms 重发、等服务器回一次 DETECT 后发 SYN；服务器重发 DETECT 会把客户端打回 Detecting |
| 0x4E0E 杀 BT 链路 | 车机接受但不 ACK | sendControl(awaitAck=false) 发后不管；0x4300 同改 |
| mDNS 抓错目标 | 手机开热点共享 | 按「本机 IP /24 前缀」过滤 _airplay._tcp 结果；mDNS 失败时网关兜底（网关=车机，端口 7000） |

**调试流程坑**：`adb install -r` 不杀旧进程（旧 dex 继续跑，修复「无效」）；
NsdManager unregisterService 异步（复用 listener 报 already in use，每次注册新建）；
ctrl mDNS 注册必须发生在 bindProcessToNetwork **之后**（组播跟随进程绑定网络）。

## 9d. WinPlay（Windows 接收端）实测记录（2026-10-19）

WinPlay 是开源 Windows 无线 CarPlay 接收端（`reference/WinPlay`，GPL-3.0，Node + TS，
接收端角色）。用户用荣耀做发送端连它（日志 `logs/cardeck/xiaomi-winplay.log`）：
iAP2 → WiFi → mDNS → 配对全通，卡在会话级 SETUP。

**根因：会话级 SETUP 的 `deviceID` 必须等于接收端选中的那台 iPhone 的蓝牙 MAC。**

- WinPlay 启动时用 `cfg.targetBluetooth`（用户在 Windows 上选定的已配对 iPhone）
  设置过滤器：`stack.deviceFilter = id => normalize(id) === normalize(cfg.targetBluetooth)`
  （`reference/WinPlay/src/engine/run.ts:64`）。
- 会话级 SETUP 里 `dict.deviceID` 对不上（或缺字段）直接回 **403**
  （`src/engine/protocol/cpStack.ts:696-699`）；`RECORD` 在设备验收前同样 403。
- 我们此前发的是 catplay 的写死假值 `ff:ee:dd:cc:bb:aa`
  （旧注释「车机不校验」只在 Audi/DiPlay 成立）。
- 状态行 `RTSP/1.0 403 OK` 是 WinPlay 的指纹：它的 `STATUS_TEXT` 表没有 403 条目，
  `buildResponse` 回退成 `OK`（`src/engine/protocol/rtspMessage.ts`）。看到「403 OK」
  优先怀疑设备过滤，而不是通用 RTSP 实现差异。
- 失败后的表象是「连上后约 45 秒自动断开」：发送端不再发请求，接收端等视频超时
  （`armVideoDeadline` 45s）后拆链路。发送端应在 SETUP 非 200 时主动 close，别静默等待。

对照 Audi/DiPlay：两者都不校验 SETUP 的 deviceID，所以这个字段长期没有被发现。
本项目已改为复用 `DeviceIdentity` 解析出的**真实本机蓝牙 MAC**（与 0x4300/0x4E0E
一致），占位值 `02:00:00:00:00:00` 只在 Shizuku 未就绪时兜底，并在 403 日志里
明确提示「接收端请选择该地址」。

**WinPlay 的其他特性（对本项目有用的）**：

- 端口固定 TCP 17000；`_airplay._tcp` TXT 与 iPhone 同构
  （`srcvers=950.7.1 features=0x44540380,0x61 flags=0x4`）。
- 会话级 SETUP 只在 `keepAliveLowPower` 为真时才开 keepAlive 端口。
- 事件通道/流 SETUP/音频协商路径与 DiPlay 基本一致（`_setupScreen`/`_setupAudio`），
  同样按 `DataStream-Salt<id>` 派生流密钥，可作为接收端行为的第三份交叉验证。

**顺带修掉的 bplist 编码 bug**：`BinaryPlist.encodeInt` 原先按**无符号**范围选宽度，
但 bplist 整数是**有符号**的——35449（0x8A79）被写成 2 字节，Apple 侧解析成 int16
-30087。临时端口（timingPort/eventPort/dataPort 常落在 32768..65535）都会踩到；
WinPlay 用 BigInt 无符号读取所以不受影响，Audi 是否受影响未知（视频曾正常，但不能
排除它忽略了错误的 timingPort）。现已改为按 `Byte/Short/Int` 有符号范围选宽度，
回归测试覆盖 0/127/128/255/256/32767/32768/35449/65535/65536。

## 9e. 奥迪 MMI 3315 会话 10 秒被拆：三条心跳缺一不可（2026-10-19）

用户实测日志 `logs/cardeck/CarDeck-log-20261019-081700.txt`：iAP2 → MFi → 热点 →
配对 → SETUP → RECORD → 视频 30fps 稳定推流，**RECORD 后 10.17~10.23s 整轮被 reset**，
两轮时序逐毫秒重合（不是随机网络问题）。

**根因一：UDP keepAlive 间隔与车机看门狗等长。** 我们每 10s 发一次（catplay
`KeepAliveClient::PING_INTERVAL`），而车机看门狗也是 10s。日志里 ping #2 距 #1
恰好 10.001s，随后 213~247ms 内被拆——`Thread.sleep` 的毫秒抖动让心跳永远晚到
deadline 1ms。catplay 的 10s 只适用于它自家接收端；对真车必须留余量。
改为 **5s**（`AirPlayClient.KEEPALIVE_INTERVAL_MS`）。

**根因二：缺屏幕流内 keepAlive 帧。** catplay 在**视频持续推流时仍然每秒**向
屏幕数据口写一帧 keepAlive（`ScreenTransmitSession::create_keep_alive`，
`DEFAULT_KEEP_ALIVE = 1s`）——它不是视频流量的备份，而是接收端屏幕上独立的存活
信号。我们从未发过。帧格式（`reference/catplay/.../screen/screen_frame.rs`）：

```
128B 明文头: u32LE body 长度 | u8 opcode | 3B 小参数 | 15 × 8B 参数
opcode 2 = KeepAlive（空体）
opcode 5 = KeepAliveWithBody（/info.keepAliveSendStatsAsBody=true 时；
           body = bplist{txUsageAvg, encoderCurrentFPS, sentFramesAvg,
                        queuedFramesAvg, lossAvg}，
           params[14] 后 4 字节 = body 长度 f32 LE）
```

**keepAlive 帧是明文**——catplay 的 `ScreenFrameCodec::encode_composite` 只对
`opcode == VideoFrame` 加密，所以 keepAlive 不消耗 ChaCha 帧 nonce。我们按此实现，
并在 `VideoSender` 的编码器出帧循环里按 1s 节流发送。

**三条心跳相互独立**：UDP keepAlive（5s，keepAlivePort） + RTSP /feedback（1s，
控制通道） + 屏幕流 keepAlive 帧（1s，视频数据口）。此前只实现了前两条，
`docs` §9c 里记的「补双心跳」只在 MMI 6318 上够用。

**音频采集全零（未解决，与拆链无关）**：`AUDIO: 采集样本 非零0% 峰值=0` 连续两轮。
候选原因按概率：当时无媒体在播（会前只有一条旧记录）、音频被路由到车机蓝牙
A2DP（PlaybackCapture 抓不到蓝牙输出）、或目标应用禁止播放捕获。

**MFi 挑战长度的车机差异（本机实测补充）**：MMI 3315 对 20B **静默 6.0 秒**
（15.605 发出 → 21.604 判定无应答），32B 在 0.64 秒回签名（22.247 收到 0xAA03）。
这 6 秒属于每次连接都要付的固定成本，除非按车记住偏好。现已实现
`MfiLengthMemory`：按 0x1D01 身份（name+model+manufacturer）持久化
「成功 / 静默 / 被拒」三种结局，命中成功档位直接首选；静默档位重试窗口压到 1.5s。
注意这与 §9b/§9c 的记录并不矛盾——**不同年款/固件的车机偏好相反**，MMI 6318
认 20B 且对 32B 会拆链，所以基线顺序仍按接收端类型选择，记忆只做覆盖。

## 9f. 老车机（CarPlay 210.81 / 奥迪 4xxx 固件）连不上：按 catplay 对齐的六处差异（2026-10-19）

用户反馈「奥迪软件版本 4 开头的（如 4851）连不上」。该代车机对应 catplay 分类中的
**pre-HomeKit 接收端**（README 兼容表：Audi/Porsche ~2020 HARMAN MIB2/MHI2 即
CarPlay 210.81）。对照 catplay 上游（本地引用停在 9-30，本次拉到 v0.17.0、39 个提交）
逐项核对后，本项目有六处与之不一致，全部按 catplay 修正：

**① bplist 根对象必须位于字节 8（catplay 9ad3a7e）**。CarPlay 210.81 不读 trailer 的
topObject，**直接按偏移 8 取根**；我们的编码器先编码子对象、最后才追加根容器，根默认在
末尾。现象是车机收到合法 bplist 却解析不出字典（`SETUP plist decode failed` / 400）。
修复：`BinaryPlist.encode` 末尾统一 `makeRootFirst` 重排并按偏移表修正（catplay 同算法），
单测 `StreamFrameCipherTest.encodedPlistRootObjectStartsAtByteEight` 用 >255 对象的
多字节偏移表锁住（小 plist 是单字节表、测不出该 bug）。

**② 初始 SETUP 的请求 URI 用相对路径 `/setup`**（catplay `initial_setup` 同款）。
旧实现发绝对 `rtsp://host:port/`。老固件对绝对 URI 敏感。

**③ 未配对会话必须带 `et=1`（NONE），屏幕流走 AES-128-CTR 零密钥**。
catplay `ScreenFrameCodec::new` 注释：「There was never support for unencrypted screen
sessions. ET = None maps to AES session where session_key before derivation is all
zeroes.」即 `et=NONE` ≠ 明文视频，而是 **AES-128-CTR + 16 字节全零会话密钥**，
密钥/IV = `SHA512("AirPlayStreamKey"/"AirPlayStreamIV" + connId(decimal) + sessionKey)[0..16]`。
事件通道在未配对会话里也是**明文**（catplay `EventsServer::unencrypted`）。
`et=NONE` 时音频没有共享密钥可派生 DataStream 输出密钥，直接跳过。

**④ 屏幕数据帧的两种加密形态**。已配对（pair-verify）→ ChaCha20-Poly1305；
未配对 → AES-128-CTR（无 tag、长度不变、密钥流**跨帧连续**，每帧新建实例会让第二帧起
全部乱码）。只加密 `opcode=VideoFrame`，keepAlive/config 始终明文。

**⑤ 控制通道密文帧的 MTU 是 1440**（catplay `AIRPLAY_CIPHER_MTU_MAX`）：
单帧明文上限 = 1440 - 2B AAD - 16B tag = **1422**。旧实现 0x4000 会被严格车机
判为协议违规（catplay 解码侧 `len > 1422` 直接 `PayloadTooBig`）。

**⑥ 只发 IPv4 地址的判据过窄**。奥迪 4xxx 的 `_airplay._tcp` / 0x4301 常只给
**IPv6 链路本地**（`fe80::…%wlan0`）。旧代码到处用 `contains('.')` 判「有没有地址」，
这类端点被判成不完整 → 白等 ctrl 邀请 → 退回「网关:7000」兜底（4xxx 的网关未必是
`.1`、端口也未必 7000）→ 永远连不上。现统一走 `net/EndpointHosts`：
IPv4 优先 → 全局 IPv6 → 链路本地 IPv6（补 scope），并且**视频/音频/事件通道复用
配对真正连通的地址**（`AirPlayClient.activeHost`），不再各自回头挑 IPv4。

**配套：用 TXT features 识别车机年代**。`_airplay._tcp` 的 `features=低32,高32`，
bit38 = `HK_PAIRING_AND_ENCRYPT`。老车机不声明该位 → 直接 `skipPairing` 走明文会话，
不再空跑 120s 配对窗口（这正是「一直连不上」的直接观感）。TXT 缺失/解析失败一律
视为「未知」并保持旧路径，绝不误降级。若明文 SETUP 仍被拒（判断失误），再补做一次
配对（catplay `handle_setup_result` 的 crypto 升级路径，窗口压到 30s）。

**顺带对齐**：`_carplay-ctrl._tcp` 的 TXT `srcvers` 改为发送端版本号（566.25.21，
此前误写车机侧 950.7.1）、服务名 iPhone；同时广播 `_device-info._tcp`（model=D84AP，
catplay `DeviceInfoBonjourEntry`）；`GET /info` 带空 bplist 字典体 + plist Content-Type。

## 9g. 奥迪 4851 实车「连上了但车机没反应」：VideoConfig 帧头缺两个字段（2026-10-19）

车友日志 `logs/cardeck/CarDeck-log-20261019-101353-all.txt`（小米 24129PN74C /
Android 16，应用 **0.1.4**；车机 `Audi_MMI_3315` / `AUDI 5163` / DELPHI，
`srcvers=450.14.2`，CarPlay `name=CarPlay`）。分析结果分两部分：

**A. 这次日志里确实每次都 10.2 秒被 reset**（六轮全部复现，与 §9e 同源）：
事件通道建立 → 恰好 +10.2s `EVENT: 读取结束 - Connection reset`。该版本缺
屏幕流内 keepAlive 帧、UDP keepAlive 还是 10s（与看门狗等长）。这两点已在
**0.1.6** 修掉（§9e），所以本轮不必再改——但车友必须升级到 0.1.6 才会有完整会话。

**B. 「app 显示已连接、车机没反应」的直接原因：VideoConfig 帧头缺字段。**
0.1.6 之前的实现在发 VideoConfig 时只写 `body_size` + `opcode`，而 catplay
`ScreenFrame::config` 还会写两项，Audi 这类严格解码器缺了就**整屏黑**：

| 字段 | 头部位置 | 值 | 作用 |
|---|---|---|---|
| `small_param[1]` | 偏移 6 | `ScreenFlag::Encrypted (0x04)` | 声明后续 VideoFrame 是加密的；不声明就按明文 NAL 解 → 解码失败 |
| `params[1]` | 偏移 16 | `(width, height)` 两个 **f32 LE** | 车机据此建立解码器画布尺寸 |
| `params[0]` | 偏移 8..16 | 全 0 | config 不带 PTS（只有 VideoFrame 才写） |

**为什么测试台一直没暴露**：DiPlay `ScreenStream.onMessage` 只读 `body`（opcode 0/1）
并从 avcC 里取分辨率，WinPlay `screenStream.ts` 同样只看 opcode 与 body——两者都不校验
这几个头字段。真车（Audi DELPHI）按头字段建解码器，缺了就黑屏，且**会话、触摸、
心跳全部正常**，日志里没有任何错误行——这是最难排查的一类。

**A 与 B 的关系**：A 让会话活不过 10 秒（用户看到「闪一下就断」）；B 让会话活着时
屏幕也是黑的（用户看到「显示已连接但车机没反应」）。两者独立，必须都修。

**诊断日志（本次新增）**：`VIDEO: ✓ VideoConfig 已发送 … flags=0x07 1412x654` 后面
跟一行头部前 24 字节 hex——下次反馈可直接核对这些字段是否写对。

## 9h. BMW 87866 卡在 iAP2 链路协商：SYN-ACK 序号重复（2026-10-19）

日志 `logs/cardeck/CarDeck-log-20261019-103436.txt`（OnePlus PKR110 / Android 16，
应用 0.1.6，车机 `BMW 87866` / MAC `9C:F5:5F:D0:44:09`）。**14 轮会话全部卡在链路层**：
`iap2 link ready` 0 次、`0x1D00` 发送 0 次、`0x1D01` 收到 0 次。

**帧统计（决定性证据）**：

| 方向 | 帧类型 | 次数 |
|---|---|---|
| BMW → 手机 | `0x80` SYN | **133** |
| BMW → 手机 | `0x40` ACK / 数据 | **0** |
| 手机 → BMW | `0x40` 裸 ACK | 350 |
| 手机 → BMW | `0xC0` SYN\|ACK | 14 |

**根因一：SYN-ACK 复用 SYN 的序号。** 原始字节：

```
我方 SYN    : ff 5a 00 1d 80 63 ...      seq = 0x63 (99)
我方 SYN|ACK: ff 5a 00 1a c0 63 a1 ...   seq = 0x63 (99)   ← 撞号
```

`sentSequence` 只在 `drainSendWindow()` 发数据帧时自增，协商阶段从不递增。对端按
`SYN.seq + 1` 校验回包（catplay `verify_neg_seq_ack`：`packet.seq == peer_seq + 1`），
同序号被判重复帧丢弃。catplay 的 `NegotiationTx::SynAck` 用 `take_own_seq()` 自增。
修复：`sendSynchronizationEcho` 消耗一个新序号；**同一个 SYN 的字节级重发保持幂等**
（重放上一帧，不推进序号——DiPlay 隧道模式每 500ms 重发同一 SYN）。

**根因二：只有第一条 SYN 得到 SYN|ACK。** 旧实现 `if (firstAdoption) sendSynchronizationEcho(...)`，
其后每条 SYN 只回裸 ACK。而 BMW **每轮重试都用新序号重开协商**（161→191→40…，
日志里单轮最多收到 36 条 SYN），永远等不到它要的回显。catplay `on_recv_neg_host`
对每条 SYN 都发 SYN+ACK。修复：**每条纯 SYN（不带 ACK 位）都回一帧 SYN|ACK**。

**踩坑：裸 ACK 会与 SYN|ACK 撞号。** 裸 ACK 用当前 `sentSequence`，而 SYN|ACK 会自增，
下一次裸 ACK 就复用上一帧 SYN|ACK 的序号。故主机路径改为**只回 SYN|ACK**（它自带
ACK 位与 ack 字段，裸 ACK 本就是冗余的；catplay 主机路径也只发 SYN+ACK）。
代价：Audi 实测的「裸 ACK + SYN|ACK」帧序变为单帧 SYN|ACK——仍符合协议，
但**需在 Audi 上复测确认**（最终 ACK 会回 100 而非 99，序号偏移一位是正常的）。

**踩坑：不能对所有带 SYN 位的帧都回 SYN|ACK。** 首版实现没区分 SYN 与 SYN|ACK，
两个引擎互喂时形成无限乒乓（单测 `windowBacklogFlushesAfterAcknowledgement` 直接
挂死并空转 CPU 55 秒，`jstack` 才看出）。判据必须是 `control and ACK == 0`。

**次要问题（本轮未改，建议后续处理）**：
- 出站客户端与入站会话并行：16 次「BMW 处于蓝牙已连接状态（HFP/A2DP）——已断开」，
  3 秒重试线程反复拆 HFP/A2DP，真车侧看是蓝牙被持续扰动；
- 未指定目标时依次去连蓝牙耳机（`HUAWEI FreeBuds 4` / `FreeClip 2`），候选应只保留
  声明了 CarPlay/iAP2 服务的设备；
- 裸 ACK 500ms 一次（350 条），BMW 的 LSP 声明 `ackTimeout=3000ms`，按对端值发即可。

## 9i. 0.1.7 在奥迪 MMI 3315 上的回归：ctrl 广播身份字段被改（2026-10-19）

日志 `logs/cardeck/CarDeck-log-20261019-141711.txt`（Xiaomi 15 / Android 16，0.1.7，
同车同手机在 0.1.6 下可正常连接）。表象：**iAP2 全通、车机却不开 AirPlay 窗口**。

**已走通**：RFCOMM → `iap2 link ready` → 0x1D01 → MFi（32B，按记忆首选 0.58s）
→ 0x5703（`Audi_MMI_3315_5GHz` ch149）→ 加入应用专属网络。车机 iAP2 层是活的
（后续还发来 0xAE00）。

**卡点（四项证据全为零）**：

| 检查项 | 0.1.7（本次） | 0.1.6（同车可用） |
|---|---|---|
| 0x4301 到达 | **0 次** | 0x4300 后 **0.65s** |
| 车机 `GET /ctrl-int/1/connect` | **0 次** | 有 |
| mDNS `_airplay._tcp` | **0 次** | `CarPlay -> 10.174.189.1:7000` |
| 网关 `:7000` | 54s 全部 `ECONNREFUSED` | 立即可连 |

**根因：ctrl 广播的 TXT `srcvers` 与实例名被我按 catplay 改了。** 0.1.6 是
`name=sender` + `srcvers=950.7.1`（车机认）；0.1.7 改成 `name=iPhone` +
`srcvers=566.25.21`（catplay 的发送端 SDK 版本），车机**连探测都不再发**。
这两项是车机在 WiFi 上唯一能看到的手机身份，改动等价于换了一台设备。
→ 已回退到 0.1.6 的值，并加测试 `CarPlayCtrlAdvertiserTest` 钉死（将来不要再
"照 catplay 抄"）。

**同时移除 `_device-info._tcp` 广播**：Android 的 `NsdServiceInfo` 不接受 port=0，
真机每次都报 `The port is missing`（日志 4 次）。它是未验证收益的附加广播，
失败只会制造噪音——移除而不是去修，因为没有任何证据表明这台车需要它。

**另两个顺带修掉的问题**：

1. **0x4300/0x4E0E 用的是占位 MAC**：本次日志全篇
   `设备状态问候 … mac=02:00:00:00:00:00`。`DeviceIdentity` 对解析失败有 10 秒静默
   窗口，而 BT 会话常在 Shizuku 刚就绪的同一毫秒构造——首次失败后 10 秒内全返回
   占位值（0.1.6 的日志里第 2、3 轮就是真实 MAC `5C:40:71:A4:0A:90`）。奥迪会拿
   0x4300 的传输标识与 RFCOMM 对端核对，占位值导致静默丢弃。
   → `BtSession` 改为会话启动时 `forceRetry` 解析并打日志。
2. **ctrl 广播的 TXT 从未进日志**：导致上面这次无法直接归因（只能靠版本对比）。
   → 每次注册打印 `name= / id= / srcvers=`，占位 MAC 额外标 ⚠。
3. **兜底期不再公告也不再查 mDNS**：4s 邀请窗口超时后只对着网关空转 54s。
   → `AirPlayClient` 增加 `retryTick`（每 10 次重试 ≈ 20s）：重新 force_announce
   ctrl，并把「车机邀请次数 + mDNS 是否发现」写进日志。

**方法论教训**：把「参考实现的值」直接搬进线上，代价可能是一整轮真车不可用。
凡是影响**车机如何识别我们**的字段（TXT、服务名、sourceVersion、deviceID），
都必须先在真车上 A/B 验证，且改动要能一眼从日志归因。

## 9j. BMW iD8.5（MGU-86）拿走 0x5702 后 5.4s 拆链：问候消息发晚了（2026-10-23）

日志 `F:\Downloads\CarDeck-log-20261021-202434.txt`（**用户给了两份，SHA-256 完全相同**；
0.1.12，Xiaomi 2106118C / Android 14）。车机 `BMW 31726` / `MGU-86-BMW`（iDrive 8.5）。

**表象**：6 轮会话全部失败，两种报错交替出现——
`SESSION 失败: 未拿到车机热点凭据…` 与 `SESSION 失败: iAP2 链路在发送 0x4e0d 时关闭`。

**已走通**（每轮都成立）：RFCOMM 由**车机主动连入**（我们出站 `BT-C` 每 3s 失败）→
链路 SYN/SYN|ACK 协商 → `0x1D01` 身份（BMW Group/CinemoHIDComponent）→ MFi
（`0xAA01` 611B 证书、`0xAA02` 32B 挑战、`0xAA03` 68B 签名，全部往返成功）。

**关键结构：车机在两种能力档案间交替**（几乎每次都换一种）：

| 档案 | 发送清单 | EA 协议 | 0x5702/0x5703 |
|---|---|---|---|
| A（14 项） | 无 0x5703 | `de.bmw.a4a.bt` protocol=7 | 收=否 / 发=否 |
| B（16 项） | 含 0x5703、0xaa06 | `com.bmw.carplay` protocol=8 | **收=是 / 发=是** |

只有 B 档案带无线 CarPlay 能力（还多出 `BMW 31726 Wifi` 的 identity 记录）。A 档案下
我们按能力清单跳过旧协议、走 0x4300/0x4301——但该车**不声明也不发 0x4301**，所以
A 档案注定失败。真正该成的是 B 档案。

**B 档案的死点（毫秒级时间线，两轮一致）**：

```
T+0.9s  tx 0xea00 startEASession protocol=8 com.bmw.carplay     ← 车机不回应
T+2.9s  tx 0x5702（空体）                                        ← 我们请求 WiFi 配置
T+3.0s  rx 链路 ACK（len=0）                                     ← 之后车机一个字节都不再发
T+8.3s  LINK closed: bt socket closed, read return: -1           ← 对端拆链
T+8.3s  发送 0x4e0d 失败 → SESSION 失败
```

时间差稳定在 **0x5702 之后 5.39~5.45s**，从无 0x5703。

**决定性证据：无线档案下设备状态问候一次都没发出去。** 全日志 `tx=0x4e0*` 只出现在
A 档案轮次；而真 iPhone 的顺序（§12 第 4 步）是 **0xAA05 之后立刻突发**
`0x4E0C/0x4E0A/0x4E0B/0x4E09…`，然后才轮到订阅/请求。我们的代码把问候排在
`0x5703 等待之后`（`Iap2SenderSession` 里 WiFi 交换 → 问候 → 0x4300），
于是 BMW 在等问候、我们在等 0x5703，5 秒后车机判定 accessory 未就绪 → 拆链。

**第二份日志（用户 2026-10-22 补发，`CarDeck-log-20261022-135133.txt`）证明这是可复现的**：
`BMW 17713` / `MGU-86`（另一台 iD8.5）、Xiaomi 23127PN0CC / Android 16、应用 **0.1.17-debug**。
**5 轮会话 → 5 次 tx 0x5702 → 0 次 rx 0x5703 → 5 次 LINK closed**，每轮都是
`0x5702 → 仅链路 ACK → 5.37s 后车机拆 RFCOMM → 发 0x4e0d 失败`。
这台车**每轮都是 16 项清单（都声明会发 0x5703）**，没有第一份日志里的 A/B 档案交替——
说明车机一直在认真尝试无线 CarPlay，纯粹是我们没按它要的次序应答。
（顺带：0.1.17-debug 比本地 HEAD 的 0.1.13 新，说明构建来自其它分支/上游，但失败签名一致。）

**第三处证据：车机声明会发 `0xEA03`（EA 会话状态），但从头到尾一次没发。**
`0x1D01` 的发送清单里有 `0xea03`、可收清单里有 `0xea00`，说明它支持 EA 会话；
而我们发出去的 `0xEA00` body 是：

```
param 0: id=0, u8  = protocolId          ← 正确
param 1: id=1, u16 = protocolId          ← 错：规范这里要 **EA 协议名字符串**
             译为 "com.bmw.carplay"
```

旧代码把 param 1 当成协议号发了个整数（日志里却打印 `name=com.bmw.carplay`，
看日志完全看不出问题）。车机拿到畸形 EA 请求 → 不回 `0xEA03` → CarPlay 数据通道
没打开 → 8 秒预算耗尽后拆链。奥迪的 EA 协议同样从未回过 `0xea03`，说明这个错误
对所有车机都一直存在，只是奥迪不靠它推进。

**处置（本轮改动，Audi 不受影响）**：

1. **`0xEA00` 的 param 1 改发协议名**（`Iap2Csm.string(1, name)`），发完探 800ms 的
   `0xEA03` 并记日志——回没回一眼可见；
2. **紧贴 `0xAA05` 立刻**发设备状态问候（真 iPhone 次序；原来排在 0x5703 等待之后）；
3. `0x5703` 分段等待：1.5s 未到 → **强制重发一次问候**（`force=true`）；
4. 再 1.3s 未到 → 重发一次 `0x5702`；
5. 之后按原窗口继续等，超时仍走 0x4300/0x4301。

问候总量仍是「一轮一次」（`greetingsSent` 幂等，force 才重发）；Audi 毫秒级回
0x5703，两条救急都走不到，行为不变。

**仍未定**（下次实车若仍失败，按序排查）：

1. `0x5702` 是否必须带参数（我们发的是**空体**，Audi 接受空体，BMW 可能不接受）——
   需要一次**真 iPhone 同车抓包**才能定死参数编号；
2. 链路 NORMAL 态**没有周期保活**（`Iap2LinkEngine` 只在协商期发 SYN），等待窗口内
   RFCOMM 完全静默 5s 是否触发 BMW 的空闲拆链——若①无效，下一轮加 1s 裸 ACK 保活；
3. 我们出站 `BT-C` 连车机的 iAP2 服务每 3s 失败（全靠车机回连），SDP 通道选择值得复核。

## §9j 0.1.13 奥迪 MMI 3315 连不上：清单当硬门禁 + 每轮自拆链（2026-10-22）

日志：`CarDeck-log-20261022-185112.txt`（0.1.13，Xiaomi 15 / Android 16 / Audi_MMI_3315_5GHz）。

### 现象链（逐毫秒）

```
18:47:59.934  0x1D01：Audi_MMI_3315 / AUDI 5163 / DELPHI
              发送清单(2)=0x4301 0xea03   可收清单(5)=0x4e0c 0x4e09 0x4300 0xea00 0xea01
18:48:00.634  ⚠「车机未声明支持 0x5702/0x5703 —— 跳过旧协议」
              → 整份日志 [tx=0x5702] 出现 0 次
18:48:03.72   0x4301 到达一次（含 SSID/PSK/fe80::900c:…/10.174.189.1:7000）
18:48:05→49:29  specifier 入网 84 秒无回调 → onUnavailable
18:49:37.885  第二次入网成功（1.9s，bindProcessToNetwork=true）
18:49:37→51:07  45 次连 7000 全部 ECONNREFUSED；_airplay._tcp 未发现；车机邀请=0 次
18:49:55 起 5 轮  每轮 0x1D00→0x1D01→MFi→0x4300 → 8s 无 0x4301
                 →「SESSION 失败: 未拿到车机热点凭据」→ 自拆链
```

### 与旧版本对照（同一台车、同一个热点）

| 日志 | 0x5702 | 结果 |
|---|---|---|
| `CarDeck-log-20261019-101353-all.txt` | 发（每轮） | 0x5703 应答 122~368ms；入网 30ms 后 `TCP 已连通 10.174.189.1:7000`；SETUP/RECORD 全通 |
| `CarDeck-log-20261020-103031.txt` | 发（每轮） | 同上，`0x5703` 166ms、入网 1.9s、端口立即可用 |
| `CarDeck-log-20261022-185112.txt`（0.1.13） | **0 次** | 端口 7000 **全程 RST**，4 轮全灭 |

**关键推断**：这台奥迪要先收到 0x5702 才会把 AirPlay 服务拉起来。缺了它，车机只吐
一次孤立的 0x4301，之后整轮沉默、端口不监听。

### 两个根因

1. **清单当硬门禁**：`legacyWifiExchange = declaresReceiveWifi != false && declaresSendWifi != false`。
   奥迪给了清单且不含 0x5702 → 跳过。而同一份日志里 `PROFILE` 行算的却是
   `wifiGen=UNKNOWN`（理由写着「保守按旧协议」）——**两套判定没接线**，日志说一套做一套。
2. **每轮 8 秒自拆链**：`wifiFromExchange == null && 无 0x4301` 时直接 `throw IOException`。
   而真 iPhone 实录（§13a）里车机入网后 **21 秒**才发 ctrl 邀请——每轮重启等于
   把车机的等待计时反复清零，永远等不到。

### 修复（0.1.14）

- 分派只认 `ReceiverProfile.wifiGen`：`NEW` 才跳过 0x5702；`LEGACY`/`UNKNOWN` 一律发；
  等待窗口 1.5s（奥迪实测 4 倍余量）。
- 无凭据但有已知热点 SSID 时合成 `airPlayPort=0` 端点继续（`credentiallessEndpoint`），
  晚到的 0x4301 仍走 `onLateEndpoint` 热更新。
- 0x4301 路径补写 `lastWifiPsk`/`lastCarWifiSsid`（此前只有 0x5703 路径写，手动兜底
  按钮因此误报「尚未拿到热点密码」）。
- ctrl 广播端口固定（重注册只换 mDNS 注册、复用 socket；旧实现每次都换端口，
  实测 43857→33383→33125→38151→40697→38015，中间还有最长 1.2s 空窗）。
- 长连接加 `TcpLiveness`（控制/事件/视频三处，idle=10s/count=3/userTimeout=20s）。
- 单测钉死双向：`sparseDeclarationsStillSendWifiRequest`（奥迪 3315 形态必须发）、
  `fullDeclarationsWithoutOldProtocolSkipWifiRequest`（宝马 iD9 形态不发）、
  `knownCarSsidAvoidsCredentiallessAbort` / `noCredentialAndNoKnownSsidStillFails`。

### 判读要点（下次同类问题）

- 日志出现「跳过 0x5702」+ 之后 **`ECONNREFUSED`（不是超时/路由不可达）** = 车机在网但
  没有进程监听 7000 —— 优先怀疑车机侧 CarPlay 栈没被拉起，而不是网络/地址问题。
- `[tx=0x5702]` 计数为 0 是这次的决定性证据；排查任何「无线连不上」先数它。
- iPhone sysdiagnose 侧的对应节奏：入网 → **21s** 邀请 → 首轮 MFi 403（正常）→
  **再等 18~20s** 车机自行重邀 → 成功。客户端任何「十几秒没进展就重来」的策略
  都会与这个节奏冲突。

## 10. 参考文件速查

> 表中路径均指 `reference/` 下**代码精读来源**（xcertplay 与 DiPlay 同源，
> 接收端逻辑一致，读哪份都行）；**实际测试台运行的是 DiPlay**（`reference/DiPlay/`）。

| 内容 | 位置 |
|---|---|
| 链路层字节格式/校验和 | `reference/carplay-wifi-extractor/example/iap2/link_layer.py` |
| RFCOMM 传输/UUID | 同上 `transport/bluetooth.py`；xcertplay/DiPlay `CarPlayController.kt` |
| CSM 分帧/TLV | `reference/xcertplay/.../iap2/wire/Iap2CsmFramer.kt`、`Iap2WireCodec.kt` |
| 无线控制时序 | `reference/xcertplay/.../transport/Iap2WirelessControlClient.kt` |
| 0x1D01 身份字段 | `reference/xcertplay/.../transport/Iap2IdentificationClient.kt` |
| 消息端点目录 | `reference/xcertplay/.../iap2/catalog/Iap2Endpoint.kt`（931行,全部参数布局） |
| Bonjour 细节 | `reference/xcertplay/.../network/CarPlayBonjour.kt` |
| 接收端 AirPlay 会话 | `reference/xcertplay/.../airplay/AirPlaySession.kt` + `PairVerify.kt` |
| 测试台（实际运行版） | `reference/DiPlay/`（含真车诊断/音频等增强；与 xcertplay 同源） |
| 发送端全套参考 | `reference/catplay/carplay/catplay_carplay/src/carplay_tx/` 等 |
| Windows 接收端（WinPlay） | `reference/WinPlay/`（TS 接收端；设备过滤见 §9d） |
| MFi 认证（接收端向） | `reference/xcertplay/.../mfi/`；Oligo 博客（单向性证据） |
| 解密固件(仅查阅) | ludwig-v/wireless-carplay-dongle-reverse-engineering（未克隆） |

## 12. MIB3 WiFi 隧道（AirPlay 会话之上的第二条 iAP2 链路，2026-10-19）

> **⚠ 2026-10-23 二次修正（以本块为准）**：本节「MIB3 必须建隧道否则 10~12s 拆链」
> 的结论**成立**；当天早些时候「CLASSIC_MIB3 忽略 marker 永不协商」的修正**被证伪**。
> 统一模型（证据链齐全）：
> ① **链路必须协商到 NORMAL**——车机的 marker 是它在等我们的会话表，1Hz **无限重发**
>   （0.1.17 车主日志实测远超「2~5 个」旧说）；不回应则 RECORD 后 ~10s 车机拆整条
>   无线栈（0.1.9 实测 10.17s、0.1.17 实测 10.18s，与心跳全健康无关）。
>   「0.1.9 忽略 marker 时奥迪照常进入 CarPlay」系误传——0.1.9 自己的日志就是 10.17s
>   拆链。真 iPhone `iAPChannel FeatureKey=0` 的含义是链路协商**完成**后结果为零隧道，
>   不是「不参与协商」。
> ② **切屏靠协商完成后的 modesChanged 资源重申**——082446（0.1.14）协商/MFi/订阅
>   全部成功、会话健康 35s+，车机却不切画面：第二控制会话把车机资源仲裁重置了一遍，
>   会话早期那份 modesChanged 已失效。AADisplay r17 长播验证的时序：auth+burst 后
>   **+1.5s 首发、+4/+12/+30s 重申**（不得在 0xAA00/0xAA05 进行中声明——与 iAP POST
>   抢控制通道会把奥迪搞死 ~12s）。0.1.12「出画面 3 秒断」= 协商砸进刚起步的 HMI，
>   时序错位同源。
> 现行实现：marker 升级协商全代际统一；`VirtualIap2Link.onControlSessionComplete`
> → `SessionController.scheduleModesReclaim`。曾据此写的「忽略 marker」分派已删。

> 事实源：`reference/AADisplay`（分支 AADisplay-Split-Lite-Carplay，**GPL-3.0 只读参考**）。
> 他们用 Frida 挂越狱 iPhone 的 mediaserverd 抓的真机握手；本仓库据此实现
> `airplay/VirtualIap2Link.kt`（协议事实照抄，代码独立编写）。

**「为什么需要」（原始结论，仅 EVENT_LINK 形态适用）**：MIB3 在 AirPlay 会话建立后还要求手机在**事件通道**上开第二条 iAP2
链路并重跑控制会话——只回链路 ACK（空回显）时 MIB3 约 10~12 秒整链拆除（本仓库
2026-10-19 两轮实车日志 10.2s 复现；此前误判为心跳缺失，§9e 的三条心跳修复并不能
救这台车）。AADisplay 源码注释原文："Empty ACK alone makes MIB3 tear AirPlay control
down in ~12s with no picture."

**传输不对称**（关键）：
- 车机→手机：链路字节走**事件通道**（TCP 5001）上的 `POST /command`，bplist
  `{"type":"iAPSendMessage","params":{"data":<链路帧>}}`；
- 手机→车机：走**RTSP 控制通道**（7000）上的 `POST /command`（同样的 iAPSendMessage
  信封，ChaCha 封帧）——写在事件通道上 MIB3 **不读**（DiPlay 宽容，奥迪不宽容）。

**时序**（Frida 真机）：
1. 屏幕流 SETUP 200（拿到 dataPort）后，手机主动发**一次** DETECT marker（经控制
   通道）——早发会在 SETUP 交换期间被冲掉；此后车机的 marker 都是探测重发，忽略；
2. 车机回 SYN（事件通道，纯 SYN 带 LSP）→ 手机回 SYN|ACK（控制通道）。LSP 要声明
   **自己的**参数：maxOutgoing=0x7F（原样回显对端表=把自己限死在对端小窗口里，
   突发消息会积压）、maxLength 保持对端值；会话表沿用对端（后续版本可注入
   file-transfer 0x0C，仪表盘封面要用）；
3. 链路 NORMAL 后手机发 0x1D00 → 车机 0x1D01 → 手机 0x1D02 + 0xAA00 →
   （车机 0xAA06 auth serial → 手机再发 0xAA00）→ 车机 0xAA01 证书 → 手机 0xAA02
   挑战 → 车机 0xAA03 → 手机 0xAA05（不验签直接接受）。**挑战长度无版本号可查**
   （由车机 MFi 认证芯片代次决定，不在任何身份/版本字段里）：Frida 抓到的隧道
   真机是 20B，DiPlay CARLINKIT 只认 32B，同一台 MMI 3315 蓝牙认 32B——所以隧道
   路径接 `MfiLengthMemory`（记忆键=身份串+" tunnel"，与蓝牙链路独立），冷启动
   基线 20B 优先，静默 6s/3s 换档重试、被拒（0xAA04）即换、全静默照发 0xAA05；
4. 0xAA05 后手机**突发**（先于车机任何订阅）：0x4E0C 无线会话标识(随机大写 UUID)、
   0xA100(param 6 空)、0x4E0A 语言 "zh"、0x4E0B 设备时间(u64 秒+s16 时区分+u8 夏令时)、
   0x4E09 设备名 "iPhone"、0xFFFA StartLocationInformation、0xFFF0 GPRMC 状态——
   后两条为逐字节 Frida 常量（`VirtualIap2Link` companion）；
5. 车机订阅应答：0x5000→0x5001 NowPlaying 空态（**仪表盘歌词的数据通路**，后续接
   MediaSessionsState）、0x4157→0x4158 通讯空态、0x4154→0x4155 无通话、
   0x4170→0x4171 空列表；0xEA02(requestAppLaunch)/0xA101(VehicleStatus)/
   **0xFFFB(PASCD 定位样本 ~1Hz) 只链路 ACK——漏 ACK 车机同样 ~10s 后关控制通道**。

**引擎配套改动**（`iap2/Iap2LinkEngine.kt`，均有单测）：
- 发起方收到 SYN|ACK（非纯 SYN）也采纳会话表并推进有序水位（只采纳不回显）；
- 响应方回显 SYN|ACK 即视为协商完成（真机不等 ACK 就发 0x1D00）；
- 回显用 ownSyncOf（maxOutgoing=0x7F），发送窗口按**本方声明值**计。

**AADisplay 参考索引**：`carplay/protocol/WifiIap2Link.kt`（时序+常量）、
`CarPlayRtspSession.kt:642`（控制通道 POST /command 写法）、`docs/AUDI_CARPLAY_CONNECT_ANALYSIS.md`。

## §13 真机 iPhone sysdiagnose 对照（2026-10-21/17，两车三份抓包）

来源：iOS 24A446（18.x）sysdiagnose 的 unified log（airplayd/mDNSResponder），验证手段
`/usr/bin/log show --archive system_logs.logarchive --predicate ...`（zsh 内建 `log` 会顶掉，必须全路径）。

### 13a. 奥迪 MMI 3315_5GHz ——「首连必拒、重邀即成」

两份抓包（同车，14:24/14:32）一致的时间线：

1. BT 供应链自动入网（airplayd 记 `CarPlay UUID` 有值）→ 约 **21s 后**车机首次 ctrl 邀请
   （`HandleControlServerEvent command 'connect'`）；
2. 连 `[fe80::…%en0]:7000`（**IPv6 link-local**，1022ms）→ pair-verify M1-M4 **32ms 成功**；
3. **MFi 认证段被 403 拒**（`MFi failed: 200403 Forbidden → Stage 1 failed → StartupFailed`）
   ——**真 iPhone 首次连接同样被拒**，属车机固有状态而非客户端问题；
4. **车机 18~20s 后自行重发邀请**，第二次 verify 16ms、MFi（challenge 64B / 证书 607B）
   通过，Stage 1/2 → 授权 → 开流。全程**不断蓝牙、不换网络**；
5. 成功会话里 **`iAPChannel` FeatureKey 协商结果 = 0**（WiFi iAP2 隧道未启用）——
   与我们「MIB3 必须建隧道」的结论相悖，改隧道策略前必须真车 A/B；
6. 杂项：SETUP 应答可不带 streamID（iPhone 自动补 0）；RECORD 往返 17ms；流级心跳
   Standard 2017ms；会话级 keepAlive 10s（LowPower）。

落地（2026-10-22）：`AirPlayClient.pairingForbidden`（任意段 403 标记）→ 主控不再走
「断 BT 第二阶段」、不再降级 et=NONE；配对循环 403 后退避 5s（车机就绪窗 ~20s，
高频重试可能一直重置其状态机）。

### 13b. 一汽大众探歌 23 款（VW WLAN）——「手动入网永远无效」

- 2.4G `VW WLAN` 手动加入 ×5 轮：`CarPlay UUID: N/A`、**`_airplay._tcp` 全程零 mDNS
  应答**、无邀请，iPhone 等 67s 退网，循环；
- BT 供应链就绪后自动加入 `VW WLAN_5GHz`（带 UUID）→ **77ms 后**车机邀请 →
  `fe80::…:7000`（1831ms）→ verify 21ms → MFi 成功 → 正常推流 3 分钟（无 403）；
- 车机 SETUP 应答带 viewAreas/initialViewArea 结构但未声明 feature（iPhone 仅告警容忍）。

结论：该车型只对「iAP2 配供应链带出的那次入网」激活 CarPlay 栈——系统里保存过/手动
连过车机热点都会堵死连接（我们的「同 SSID 已保存弹窗清理」对此车是关键步骤），
且必须严格用 0x5703 下发的 SSID（双频 SSID，成功路径是 `_5GHz`）。

### 13c. 奥迪 MMI 8015（AUDI 5810 / DELPHI / 450.14）——「不切屏根因 = BT 永不拆除」（2026-10-24 定谳）

五份 iPhone sysdiagnose（`/Users/lizhihua/logs/cardeck/audi/`，2026-10-15/16，含连接全程
与音频）。**帧级证据源是 `crashes_and_spins/accessoryd-packets-*.log.ips`**——accessoryd 的
iAP2 抓包（hex 载荷 + 运输层标注 `Bluetooth Classic` / `AirPlay` + 方向 `Acc`（车机→手机）
/ `iPod`（手机→车机）），unified log 里没有这些帧；文件名时间戳是会话起点，内容滚动覆盖
到抓包时刻，注意按行内时间过滤。`FF 55 02 00 EE 10` = control=0xEE 的 **DETECT 探测包**。

#### 真机完整时间线（17:31:43~52，#4 抓包「先抓包后连接」）

**BT 链（17:31:43.57 ATTACH）**：车机 marker ×3（~1Hz）→ iPhone 逐条**回 DETECT** →
44.651 车机发 SYN（ctl=0x80 seq=2 + 会话表）→ 44.652 iPhone SYN|ACK（0xC0 + 回显表
maxOutgoing=0x7F）→ 0x1D00/0x1D01/0x1D02 → MFi（**挑战 20B**、签名 132B、证书 912B）→
0xAA05 → 0x4E0C/0x4E09（李永正的iPhone）/0x5702→0x5703 → 0x4301。

**AirPlay 链（17:31:49.92 ATTACH，AirPlay ConnectedTime +0.37s）**：
49.921 车机在 **AirPlay 运输层**发 marker → iPhone 回 DETECT → **50.015 iPhone 主动
DETACH 蓝牙**（两链并存仅 94ms）→ **50.023 车机 SYN**（DETACH 后 8ms！ctl=0x80 + 3 会话
表）→ iPhone SYN|ACK → ACK → 0x1D00→0x1D01→0x1D02→0xAA00→（车机 0xAA06→iPhone 重发
0xAA00）→0xAA01→0xAA02(20B)→0xAA03→0xAA05→突发 0x4E0C/0xA100(**仅参数 0x0006**)/
0x4E0A/0x4E09/0x4E0B/0xFFFA/0xFFF0 → 车机来 0xEA02/0x4157/0x4154/0x4170/0x5000/0xA101 →
iPhone 应 0x5001/0x4158/0x4155/0x4171 + 会话 2（EA 数据，含 JPEG 封面流）→
52.211 起车机 **0xFFFB（GPGGA+GPRMC+PASCD）~1Hz 推送**，iPhone 回一次 0xFFFC、
**+6s 重发 0xFFFA/0xFFF0**。全程 2.3s 完成，此后只有 0x5001/0xFFFB 稳态流量。

#### 三方对照（同车同手机 MEIZU 20 Pro）

| 实现 | AirPlay iAP2 链 | 蓝牙 | 结果 |
|---|---|---|---|
| 0.1.0+NUL修复（用户侧 AI 改版） | 无（EA datastream SETUP 200 无 dataPort） | 保持 | **出画面/触摸/音频/方向盘全通**，RECORD+10.1s 拆链 |
| 真 iPhone | marker→回DETECT→**拆BT**→SYN→全套会话 | 0.1s 内拆除 | 出画面，长期稳定 |
| 0.1.37 | 主动 DETECT→全套会话（内容与时序基本同 iPhone） | **全程保持** | 不出画面，车机连接入口置灰直到重启 |

判读：车机把「**BT 释放**」当无线移交完成条件。0.1.0 无 AirPlay 链时画面照切（BT-only
形态、10s 看门狗拆链）；一旦隧道链存在而 BT 不拆，HMI 卡在半移交态。0.1.12「出画面
3 秒断」说明协商早入时画面也能切——但正确形态是 iPhone 的「链成型即拆 BT」。

另外两条被推翻的旧认知：①「回 marker 会把车机打回 Detecting」——iPhone 每条 marker 都
回 DETECT，0.1.0 回 marker 也出画面；②「iPhone 不回 marker、车机自己发 SYN」——车机
SYN 紧跟 iPhone 的 **BT DETACH**（8ms），不是凭空自发；0.1.0 全程回 marker 但 BT 不拆，
车机就永远只发 marker 不发 SYN。

落地（0.1.38，待真车验证）：`VirtualIap2Link.onMib3HandoffReady`（MIB3 控制通道形态、
链路 NORMAL 即将发 0x1D00 时触发）→ `SessionController.detachBtForMib3Handoff`
（CLASSIC_MIB3 + 推流中 + 每会话一次）：拆两路 BT 会话 + 停 server accept（防车机回连
RFCOMM 重挂第二条链；每轮连接起步会重新 start）+ `btHandoffActive` 豁免
`CarBluetoothReceiver` 的 ACL 事件（否则 ACL_DISCONNECTED 会 AUTO_STOP 把会话杀掉——
HFP/A2DP 连接前已断，RFCOMM 是 ACL 唯一持有者）。客户端重连循环本就被
`BtSessionGate.sessionActive` 静默，会话结束自动恢复探测。若实测仍不切屏，下一迭代对齐
iPhone 的精确顺序：应答车机事件通道 marker → **先拆 BT 再等 SYN**（当前是等 SYN 后拆）。

#### §13c 增补（2026-10-25 四路审查 + 抓包对照修正）

1. **事实修正**：0.1.37 的链路并非「车机对我们的 DETECT 回 SYN」——日志实为裸 DETECT
   发出后车机 1Hz marker 不应答，~1s 后我方升级「marker+FF5A 同步包」（engine.initiate）
   才撬开车机应答。**我们是以发起方姿态强行建链**，与 iPhone（车机自发 SYN）角色相反。
2. **半移交模型（唯一同时自洽解释三组证据的模型）**：车机只在「WiFi 侧 iAP2 会话存在」
   时进入无线移交态，该态完成条件 =「探测被应答 + BT 释放」。0.1.0 无 WiFi 侧会话 →
   画面照切（也不需要 SYN）；0.1.37 有会话但 BT 不拆、探测零应答 → HMI 卡死。
   「SYN 依赖 BT 释放」被 0.1.37 证伪（BT 在挂时链路照样建成、3.4 分钟健康）。
3. **iPhone 精确时序**（airplayd + 帧级抓包互证）：屏幕流 SETUP 应答 50.037、iAP2 舞蹈
   夹在 SETUP 请求与应答之间、**BT 在首帧（50.098）前 83ms 已落下**。两链并存仅 94ms、
   窗口内 BT 零流量；车机协议层对并存容忍 ≥分钟级（0.1.37 实证），HMI 层零容忍。
4. **0.1.39 落地形态**（0.1.38 的审查修正版）：
   - 拆 BT 触发点从「链 NORMAL」提前到 **armMib3 发出 DETECT 之后立即**（屏幕 SETUP 200
     后、RECORD 前）——原时点与 videoSender.isRunning 存在竞态（事件通道 reader 先于
     videoSender 创建启动，hook 触发时 isRunning 几乎必然 false 且 mib3CsmStarted 已
     闭锁，整场静默不拆、无日志可判）；提前后 BT 保证在首帧前落下，复现 iPhone 可观察
     顺序「DETECT → BT 落下 → 车机 SYN → 0x1D00」。marker 计数升级（第 2 个起 FF5A
     协商）保留为车机不自发 SYN 的兜底。
   - **CLASSIC_MIB3 逐条应答车机 marker**（经控制通道回 DETECT）——旧实现引擎层直接
     丢弃、一条不回；iPhone 是每条 marker 同毫秒回 DETECT。
   - onEnded 复位移交豁免 + 重启 server 监听 + 停音频（车机拆链是最常见离场，移交后
     ACL 路径被豁免，不收口则熄火后音频无限空转、下次上车自动重连哑火）。
   - AUTO_START_SESSION 加 isSessionRunning 短路（防会话中误触发重启被停的 server）。
   - A100 突发改单参数 6（iPhone 抓包 `a1 00 00 04 00 06`；旧 3 参数源自 Frida 抓包）。
   遗留小偏差（低风险、未改）：FFF0 12ms 双发、FFFA/FFF0 +5s 周期重发、首条 0xFFFB 回
   0xFFFC、0x4171 应答给真实列表元数据（我们全零）、EA 会话 2（封面流）。

