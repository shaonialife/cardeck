# 音频通道

> 2026-11-18 从宿主 `AGENTS.md` 拆出（原 1397 行 / 136KB）。
> 共 2 条，37 行。
>
> 车机侧声音的采集源、编码、RTP 推流与路由抢占处理。

---

- **车机音频采集：REMOTE_SUBMIX 直采（0.1.28.59，首选）→ 投影播放捕获（回退）**：
  旧路径 `AudioPlaybackCapture` 依赖投屏授权链且会话中强制手机媒体音量=0（断开恢复，
  音量条被劫持）。现首选走 **shell UserService 内 `AudioRecord(audioSource=8
  /*REMOTE_SUBMIX*/)` + `ParcelFileDescriptor.createPipe()`**：PCM16LE 立体声经管道
  AIDL 回传（`startSubmixAudio(sampleRate)`/`stopSubmixAudio()`），应用侧从管道读满
  帧喂既有 RTP 管线。**静音原理 = submix 输入激活时 AudioPolicy 把媒体策略整体路由
  走——手机本地自然静音、音量条不动**（与「音量置零」有本质区别：置零只压播放端，
  路由换整条策略）。要点：① shell uid 持 CAPTURE_AUDIO_OUTPUT，无需 MediaProjection
  （视频镜像的投影授权照旧）；② `AudioRecord.Builder.setContext` 是 hidden API，
  反射带 AttributionContext 包装（`AttributionContext`，与 VDM 共用），失败仅降级；
  ③ 泵线程写管道失败（应用断开/崩溃）即自愈关闭采集——媒体路由随之恢复，不留孤儿
  （否则 submix 挂着手机媒体全程静音）；④ 采样率随 SETUP 协商值（48k/44.1k）；
  ⑤ 通话（STRATEGY_PHONE）不受路由影响，导航播报随媒体一并路由；⑥ 收口两端：
  `AudioSender.onStopped` → `stopSubmixAudio`（幂等）+ 泵线程自愈。验证手段：日志
  「AUDIO: 已启动（REMOTE_SUBMIX…）」且**无**「已静音手机媒体输出」行；推流中手机
  媒体音量键调音量条可动；断开后手机本地音频立即恢复。
- **音频通道（车机侧声音）三条硬约束**（2026-10-17 奥迪联网实测打通，此前从无声音）：
  ① **会话建立顺序必须是 屏幕SETUP → 连数据端口 → RECORD → 视频起流 → 音频SETUP →
  补发RECORD**：车机对「屏幕流 SETUP 后约 2s 内未激活」有看门狗，而音频 SETUP 本身
  要 2.0~2.7s（车机侧音频通路初始化），排在 RECORD 前必然把激活拖过看门狗——日志特征
  是 `音频 SETUP 往返 20xxms` 与 `EVENT 读取结束 - Connection reset` 同一毫秒、5 次
  实测全复现。音频流在 RECORD 之后加入时必须**补发一次 RECORD**才纳入会话。
  ② **audioType 必须用 `media`**（媒体音频专用通路）：车机 /info 的 audioFormats 里
  type=100 分档声明（奥迪有 compatibility/alert/default/media/telephony/speechRecognition
  六档，**每档位图不同**）；往 compatibility 推媒体流时车机接受 SETUP、给 dataPort、
  照收 UDP 包，**但不接入播放通路**（表现：手机静音成功、采集非零、包持续发送、无声）。
  必须**全量解析 audioFormats 逐档选**，不能只看前两档（旧日志 take(200) 截断曾导致
  误判「只有 compatibility」）。具体选择交给 `AirPlayClient.negotiateAudio()`：优先
  media 档 + PCM48k 立体声（bit15），退 44.1k（bit11），延迟取该档 audioLatencies 的
  outputLatencyMicros，无 /info 时回退协议基线（media/48k/250ms）。
  ③ **RTP 参数按 catplay 真车值**：SSRC=**0**（随机 SSRC 会被当未知流）、每包 **352**
  样本帧（AirPlay 标准 SPF）、timestamp 按帧递增、PT=100、大端 s16、AAD=头 4..12、
  尾 8B 小端 nonce。改这些值等于改线上格式，必须对照 catplay `rtp_recorder.rs` /
  `rtp_cipher_chacha.rs`。
  诊断三件套（无声时按序看）：`AUDIO: 采集样本 非零X% 峰值=N`（0%=没采到：应用禁止
  捕获或音频走了蓝牙）、`音频协商 → audioType=?`（选错通路）、车机 `/feedback` 里该
  connId 的 sampleTime 是否推进。
