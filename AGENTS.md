# AGENTS.md — 给 AI 编码助手的项目指引

让一台未 root 的 Android 手机（荣耀 Magic7 Pro）扮演 **CarPlay 发送端（iPhone 角色）**：
自绘车机桌面 → H.264 编码推流 → 奥迪车机 / DiPlay 测试台（Redmi K40 上的接收端）显示，
车机触摸经 Shizuku（shell 权限）回注虚拟屏。协议链路：蓝牙 iAP2 → 车机热点 → mDNS →
AirPlay（配对/控制/事件/流）→ 视频 + 音频推流。

**装机设备映射**（序列号不猜，2026-11-12 前缀形态猜错一次；协议仓
AGENTS.md「命令」节同表）：
- **荣耀 Magic7 Pro**（装 app 主力机）= `AN6B025115008468`；USB 掉线用无线号
  `adb-AN6B025115008468-rD6LCD._adb-tls-connect._tcp`
- **REDMI K40**（DiPlay/xcertplay 测试台）= `a95a3b66`

## 常用命令

```bash
./gradlew assembleStandardDebug         # 构建（日常包；远程调试版为 assembleRemoteDebug）
./gradlew testStandardDebugUnitTest     # 单元测试（协议层/编解码为主）
./gradlew :app:lintStandardDebug        # Android Lint（存量问题在基线，新问题会失败）
./gradlew spotlessApply                 # 代码格式化（ktlint official 风格，规则在 .editorconfig）
adb -s <serial> shell am force-stop io.github.shaonialife.cardeck   # 安装前必须先杀应用
adb -s <serial> install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

**安装流程（重要）**：会话进行中直接 `adb install` 时，系统安装确认弹窗会落到车机侧
虚拟屏上（无人点得到，表现为 User rejected / 卡住）。**必须先 force-stop 再 install**；
若仍被拒，先 `input keyevent KEYCODE_WAKEUP` 亮屏再装——**熄屏时确认弹窗无人应答会被
系统自动拒绝**（2026-10-15 实测三次 INSTALL_FAILED_ABORTED，亮屏后即成）。

**装过 release 包之后 debug 包装不上**（2026-10-18）：`app-debug.apk` 用 debug 证书、
release 包用正式 keystore，`adb install -r` 会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`。同一台机器上要换类型只能
先 `adb uninstall`（会丢应用数据/Shizuku 授权），或直接改用
`./gradlew :app:assembleRelease` 的产物验证——**推荐后者**，签名一致、可覆盖安装。
注意 release 包不可 debuggable，`run-as ... tail files/main_debug.log` 会报
`package not debuggable`；日志验证要另走 debug 包或临时开 debuggable。

**卸载应用不会释放虚拟屏**（2026-10-18 实测，比上面更隐蔽）：虚拟屏由 Shizuku 的
UserService 子进程持有，**父应用卸载后该进程仍存活**，4 块屏（CarPlayDock/App/AppHi/Map）
继续占位。此时再 `adb install`，确认弹窗仍会落到这些屏上，表现为「安装卡死」，
而且因为应用已卸载、**无法靠「启动应用自动清理残留」补救**（那条路径需要应用存在）。
更糟的是每次尝试安装都会新叠一个 `PackageInstallerActivity`（实测堆积 11 个）。
处置（按顺序）：
1. `adb shell ps -A -o PID,NAME | grep car_input` 找到 `:<包名后缀>` 的 UserService 进程
   （名形如 `io.github.shaonialife.cardeck:car_input`，UID 为 shell）；
2. `adb shell kill <PID>` —— 进程退出，虚拟屏立即全部释放（实测 kill 后
   `dumpsys display | grep CarPlay` 为空）；
3. `adb shell am force-stop com.android.packageinstaller` 清掉堆积的安装弹窗；
4. 再 `adb install`。
（`adb install --user 0` / `-g` 等参数**绕不过** Honor 的安装确认弹窗，别指望静默安装。）
残留屏数量与是否为「上次会话遗留」可用 `dumpsys display | grep -oE '"CarPlay[A-Za-z]+", displayId [0-9]+'`
快速核对。

**发布（GitHub Release）**：
```bash
# 1) 改 app/build.gradle.kts 的 appVersionName（versionCode 自动推导）。
#    版本号四段制（2026-10-25 起）：主.次.修订.内部号——内部测试只递增第 4 位
#    （0.1.28.1 → 0.1.28.2）；对外发布升第 3 位、第 4 位归 0（0.1.29.0），
#    首页等对外位置经 BuildConfig.DISPLAY_VERSION 显示三段（0.1.29）。
# 2) 用正式密钥构建（需仓库根目录有 keystore.properties + .jks，均已 gitignore）
./gradlew :app:assembleStandardRelease
# 3) 验证签名的 CN 不是 "Android Debug"（必须是自己的证书，见下）
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs app/build/outputs/apk/standard/release/app-standard-release.apk
# 4) 打 tag 并发布（APK 重命名成 CarDeck-<版本>.apk，备注里写 SHA-256 与签名指纹）
git tag -a v0.1.0 -m "CarDeck 0.1.0"; git push origin v0.1.0
gh release create v0.1.0 CarDeck-0.1.0.apk --title "..." --notes-file notes.md --latest
```
**CI（`.github/workflows/android-ci.yml`，2026-10-22 起）**：每次 push 自动出**正式签名**包，
版本号自动累计——传 `-Pcardeck.buildNumber=<github.run_number>`，得到
`versionName=<appVersionName>.<N>`、`versionCode=base*10000+N`（能覆盖安装上一版；
base 升 patch 时自动反超）。产物名 `CarDeck-<版本>-release.apk`，**只在 Actions Artifacts，
不发 GitHub Release**（`permissions: contents: read`）。签名来自仓库 Secrets
`KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`（可选
`EXPECTED_CERT_SHA256` 做指纹比对）；**没配 KEYSTORE_BASE64 流水线直接失败**，
且 Gradle 侧 `check` 会拦「带构建号却没有 keystore.properties」，不允许 debug 签名的
「正式版」流出去。本地 `assembleRelease` 不带 `-P` 时仍是纯 base 版本（`1_130_000`）。
**签名是不可逆决定**：debug 证书签的包一经发出，以后换正式证书时已安装用户必须
卸载重装（证书不同无法覆盖安装）。所以**首个公开版本就必须用正式 keystore**。
keystore 与密码只存本地/密码管理器：私钥泄露 = 任何人可签发覆盖安装的更新；
私钥丢失 = 已安装用户再也收不到更新。证书 SHA-256 指纹写进 Release 备注，
便于用户核对下载完整性。

**测试分级（2026-10-26 用户约定）**：非协议相关改动（UI/文案/设置项/日志格式等）
  跑单测时用 `-Dtest.quick=true` **跳过重放测试**（BtLinkReplay/TunnelReplay/
  ForbiddenAudiReplay，一轮要 4 分多钟）；只有协议层（iap2/airplay/video/audio/
  Iap2LinkEngine 等）改动才需要全量含重放。远程/装机验证流程同理：先 quick 筛一遍，
  验证通过后再补全量。
**提交**：pre-commit 钩子（`.githooks/pre-commit`，经 `core.hooksPath` 启用；克隆后需
执行一次 `git config core.hooksPath .githooks`）会自动格式化暂存的 kt/kts 并跑
`lintDebug`，失败即拦截。存量 lint 问题豁免在 `app/lint-baseline.xml`——新代码引入
问题会直接失败；实际修复后条目自动失效，有意豁免经评审后 `./gradlew :app:updateLintBaseline`。
协作约定：功能完成后**装机给用户验证，用户说「提交」才 git commit**。

**作者邮箱（公开仓库，硬性要求）**：本仓库已公开（github.com/shaonialife/cardeck），
提交里的作者/提交者邮箱**只允许 `*@users.noreply.github.com`**
（`16135960+shaonialife@users.noreply.github.com`）。**绝不能出现公司/私人邮箱**——
提交推送后无法真正撤回，GitHub 会永久索引，搜索引擎也能关联到你的雇主。
四层防护（均已实测）：
1. **仓库局部 `.git/config`** 已设 noreply 邮箱，覆盖全局 `~/.gitconfig` 里的公司邮箱
   （根因就是全局配置是公司邮箱，任何新仓库都会默认带上）；
2. **`pre-commit` 守门**：`user.email` 不在白名单直接拒绝提交；
3. **`pre-push` 扫描**：检查**所有待推送提交**的 `%ae`/`%ce`，非白名单即拒绝推送。
   这条覆盖 pre-commit 挡不住的情况——`--no-verify`、cherry-pick/rebase 沿用原提交
   作者、从其它仓库 pull 进来的历史；
4. 必要时 `git filter-branch --env-filter` 改写历史（已对全部提交执行过一次）。

**踩坑记录**：`git reflog expire --expire=now --all` 会**连带清掉 stash 的 reflog**，
使 `git stash list` 变空（`refs/stash` 引用与内容其实还在，只是列表读 reflog）。
误清后可用 `git update-ref --create-reflog refs/stash <sha>` 或手工补
`.git/logs/refs/stash` 恢复。另：shell 脚本里 `$var` 紧跟中文/全角字符
（如 `$remote_ref（`）会被当成变量名的一部分，必须写成 `${remote_ref}`。

**调试**：logcat 受限（HKS），运行日志走文件 `files/main_debug.log`（任意线程
`SessionController.appendLog` 写入）：
`adb shell "run-as io.github.shaonialife.cardeck grep/tail files/main_debug.log"`。
接收端画面验证：`adb -s <测试台serial> exec-out screencap -p`。

## 架构速览

```
bt/          蓝牙 iAP2：Server（测试台回连）/ Client（真车主动连）+ BtSessionGate
iap2/        iAP2 链路层 + CSM + 发送端会话（0x5703 热点凭据 / 0x4301 AirPlay 端点）
net/         WiFi 入网绑定（WifiCarPlayConnector）+ mDNS ctrl 广播 + ctrl-int HTTP
airplay/     AirPlay：SRP 配对、加密 RTSP 控制通道、事件通道、流协商、ChaCha20 加密
video/       MediaCodec Surface 输入 H.264 → 128B 头 + ChaCha20 帧封装 TCP 推流
audio/       REMOTE_SUBMIX 直采（shell）→ RTP/ChaCha20 over UDP（回退=播放捕获）
launcher/    隐形屏启动器（核心）：三块受信虚拟屏（dock/app/map）
render/      DualDisplayComposer：GL 合成多虚拟屏画面 → 编码器 Surface（出帧节奏在此）
input/       TouchRouter：车机触摸按坐标分流注入 dock/app/map 屏
shizuku/     Shell（cmd 执行）+ CarInputClient（触摸注入/虚拟屏创建/resize，AIDL UserService）
SessionController  会话总管（进程级单例）：链路生命周期、配对、WiFi 自动加入、取消
launcher/settings/ 设置面板体系（Fragment）：车机双栏与手机二级菜单共用同一套面板
media/       通知使用权 → 媒体会话状态（音乐卡数据源）
ui/ScreenDimmer  常亮+暗幕；WallpaperStore 壁纸
MainActivity 手机侧操作台 UI（英雄卡状态机 1s 轮询）
```

**虚拟屏**（横屏车机，全部经 Shizuku shell 创建）：CarPlayDock（左侧竖条，自有
DockActivity，不受信）+ CarPlayApp（应用屏，**密度=手机侧**、px=dp×手机密度/160，
合成器缩小回画布——自带超采样，2026-11-06 起取代原 app+appHi 双屏）+
CarPlayMap（地图屏，同密度=手机侧；仪表盘 tile/全屏间动态 resize）。
竖屏车机时 dock 落底部、应用屏窗口宽 = 410dp（手机宽度）。

## 领域知识库索引（2026-11-18 拆分——本文件从 1397 行 / 136KB 降至精简版）

原「关键约定与已踩过的坑」1247 行 / 83 条已按域拆到 `docs/knowledge/`。
**改对应域代码前先读该域文件**（不要全读，按域加载）：

| 文件 | 域 | 条目 | 何时读 |
|---|---|---|---|
| [docs/knowledge/display-virtual-screen.md](docs/knowledge/display-virtual-screen.md) | 虚拟屏 / 显示 / 旋转 | 29 条 | 改该域代码前 |
| [docs/knowledge/connection-iap2.md](docs/knowledge/connection-iap2.md) | 连接链路 / iAP2 / 配对 / 心跳 | 37 条 | 改该域代码前 |
| [docs/knowledge/audio-channel.md](docs/knowledge/audio-channel.md) | 音频通道 | 2 条 | 改该域代码前 |
| [docs/knowledge/platform-android.md](docs/knowledge/platform-android.md) | 平台 / Android / 工具链 | 10 条 | 改该域代码前 |
| [docs/knowledge/misc-ui.md](docs/knowledge/misc-ui.md) | UI 杂项 / 对外 | 6 条 | 改该域代码前 |

**写入约定（2026-11-18 起）**：
- 新踩的坑 → 追加到对应 `docs/knowledge/<域>.md` 末尾（按 `- **主题**（日期）：说明` 格式）
- **只有「常用命令 / 架构速览 / 硬性约定」变化才改本文件**

---

## 知识沉淀约定

- **随时补录**：工作中遇到值得记录的点——性能实测结论、踩坑、设计决策、已知问题与
  放弃方向——应随手补录到 `docs/knowledge/<域>.md` 末尾（**不是本文件**，
  2026-11-18 起）。
- **取舍**：一次性的调试细节留在代码注释里；**跨会话仍有价值的**（会改变后续做法的
  结论、复发即踩的坑）才沉淀。判断标准：未来会话在不了解它的情况下大概率
  重犯或重查一遍。
