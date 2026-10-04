# SafeStream

粘贴一个网址 → 自动找出这个页面里的所有视频 → 在原生播放器里播放，
同时把页面能带来的危害尽可能挡在外面。**只做 Android。**

> 本仓库的需求规格写在 [PROMPT.md](PROMPT.md)。代码改动请同步回写那里。

## 直接下载安装

**[Releases → v0.8.0](https://github.com/hea784/SafeStream/releases/tag/v0.8.0)** 下载 `app-release.apk`，
传到手机点击安装（会提示"未知来源应用"，允许即可，自签名应用的正常提示）。
要求 Android 7.0 (API 24) 及以上。

> 二进制产物走 Release 附件，不进版本控制 —— 把 APK 提交进 git 会让仓库体积
> 暴涨且每次改动都产生无意义的二进制 diff。

## 先说清楚两件事

**一、这个 App 不能保证"零风险"。**
Android WebView 和系统浏览器共用同一个渲染内核，没有任何第三方 App 能真正"隔离"网页恶意内容。
SafeStream 的做法是**最小权限 + 无桥接 + 可观测**：不给页面任何原生能力、不给它任何本地访问、
不给它任何 Cookie、拦掉能识别的广告与跟踪、退出就擦干净。声称绝对安全的实现都是不可信的。

**二、已验证到哪一步。**
构建在 Windows 11 + Temurin JDK 17.0.20.1 + Android SDK 35 + Gradle 8.13 下通过
（`assembleDebug` 与 `assembleRelease`，含 R8 混淆、资源压缩、`lintVitalRelease`）。

- 模拟器（AVD `venera_test`，pixel_6 / API 35 / x86_64）：JVM 用例 61 个、
  设备端用例 7 个（真实 WebView 回调）、端到端脚本 4 项断言，全过。
- 真机：WebView 151 上验证过能力自检、选集发现与播放；崩溃过一次，
  已定位（`shouldInterceptRequest` 在新内核上跑网络子线程、跨线程碰 View）并修复。
- **未验证**：真站签名 HLS（`auth_key` 的 m3u8）全程机器验证 —— 目前只在本地
  mp4 夹具上端到端跑过连播，真站的 m3u8 行为靠人工确认过发现数量。

## 架构

```
MainActivity（单进程，单界面）
+------------------------------------------------+
| 搜索栏 / 状态提示（自动淡出）                   |
| 网页常驻（WebView，不被切走）                   |
| 「N 个视频」浮标 -> 选集浮层（盖在网页之上）      |
| 迷你播放器（播放/暂停 + 进度条）-> 点它进全屏    |
+------------------------------------------------+
  |                    |                |
  v                    v                v
WebSandbox         PlaylistStore    PlaybackSession
  |                    |                |
SafeClients <-- WebSecurityPolicy      ExoPlayer
  |                    |
UrlGuard / UrlCleaner / TrackerBlocklist

ScannerMessageParser —— 扫描脚本消息的解析（纯逻辑）
```

网页与播放器同处一个界面：边播边翻网页、边看边点下一集。
页面渲染进程由 Chromium 自己沙箱化（`sandboxed_process`），
`onRenderProcessGone` 兜住渲染崩溃 —— 双 Activity 的"独立进程"架构已撤销，
它多买的只是崩溃与内存压力隔离，不值一整套跨进程广播协议。

### 三个实测踩到的坑（编译全部通过）

1. **明文对话框是假的。** `cleartextTrafficPermitted=false` 让 WebView 在平台网络栈
   就掐断明文，用户点"仍要加载"也没用，页面全白。现在平台层放开、闸门移到
   `shouldInterceptRequest`，只放行用户确认过的 host 及其子域。
2. **RecyclerView 没设 LayoutManager。** 数据齐全、标题显示"发现 3 个视频"，
   但列表一行不渲染。编译期和 `lintVitalRelease` 都不报。
3. **`shouldOverrideUrlLoading` 跨线程碰 View。** 真机 WebView 151 把
   `shouldInterceptRequest` 挪到了网络子线程，在里面直接改状态栏文本会崩
   （`CalledFromWrongThreadException`）——模拟器 WebView 124 上万次点击都碰不到。

另外 `onPermissionRequest` / `onCreateWindow` / `onShowFileChooser` /
`onGeolocationPermissionsShowPrompt` **不在 `WebViewClient` 上，而在 `WebChromeClient` 上** ——
写错了编译期报 `overrides nothing`，绕过去则四条安全契约静默失效。

## 安全设计（对应 PROMPT.md 第 4 节，共 18 条）

| 措施 | 挡的是什么 |
| --- | --- |
| **不用** `addJavascriptInterface` | 页面脚本无法调用任何原生方法 |
| `addWebMessageListener` + origin 白名单 | 唯一的数据回传通道，可限域 |
| 拒绝 `file:` `content:` `intent:` `market:` `javascript:` | 读本地文件、拉起别的 App、注入脚本 |
| 关闭文件/内容访问、跨 file URL 访问 | 页面读不到手机里的任何文件 |
| `MIXED_CONTENT_NEVER_ALLOW` + 明文按 host 授权 | 明文降级与"对话框放行全站" |
| 禁止弹窗、禁止自动开窗 | 无痕广告弹窗、劫持新窗口 |
| `onPermissionRequest` 一律 deny | 页面拿不到摄像头/麦克风/定位 |
| `onShowFileChooser` 返回 null | 页面无法用"上传"骗你点授权 |
| `DownloadListener` 一律 abort | 页面不能骗你下 APK |
| 域名/后缀/裸 IP 黑名单拦截 | 广告、跟踪、挖矿上报 |
| 导航剥离 `utm_*` 等跟踪参数 | 归因追踪；只剥白名单，签名参数绝不误伤 |
| Safe Browsing 开启 | 已知恶意站点 |
| 证书错误直接 cancel | 中间人 |
| **全拒 Cookie**（含第三方） | 站点无法跨会话追踪；清不了的东西从一开始就没有 |
| 渲染进程崩溃即重建 | 被污染的渲染状态 |
| 退出即清 Cookie/WebStorage/Cache/History/FormData | 站点不留痕（换页清 WebView 回退栈，返回键不被吞） |
| 防护面板：拦截明细 + 站内开关 + 一键清理 | "拦了什么"可查、可自证、可纠误伤 |
| 只申请 `INTERNET` + `ACCESS_NETWORK_STATE` | 权限面本身 |

地址栏还有 `UrlGuard`：自动补 `https://`、拒绝内网地址（防 SSRF）、拒绝超长与含控制字符的输入、
明文 HTTP 必须用户点确认才加载；签名/时效地址（`auth_key` 等 14 类查询参数）
不记断点进度 —— 每次都会变，记了也永远匹配不上。

## 已实现功能

- 网址/搜索二合一输入（回车提交，自动区分网址与搜索词；引擎可切必应/百度/搜狗）
- 自动视频发现：DOM 扫 `<video>`/`<source>`/`data-*` + fetch/XHR/MediaSource 钩子
  + Performance 资源表 —— MSE/blob 流站点的真实清单在请求瞬间被抓到
- 连续剧选集识别（剧集页地址，不是媒体地址）→ 选集浮层（盖在网页之上）
- **自动连播**：一集播完自动加载并播放下一集，状态行提示
- ExoPlayer 播放：HLS / DASH / 直链；倍速 0.75x–2.0x；断点续播（签名流除外）
- 迷你播放器：播放/暂停 + 进度条（不必进全屏）；点画面进全屏（横屏铺满、常亮）
- 选集浮层显示「播放中 / 看过」；防护面板显示本次会话的拦截明细
- DRM 探测：检测到 Widevine 等加密时明确告知"这集播不了"，而不是黑屏转圈
- 崩溃现场留存：崩溃堆栈存本地，下次启动弹窗展示（真机崩溃靠它定位过一次）
- 启动即报告网页内核能力（部分国产 ROM 冻结旧内核，此时直接提示而不是"列表一直空着"）

## 不做的事

不绕 DRM、不下载版权内容、不注入去广告代码二次分发、不集成第三方追踪 SDK、不用闭源商业组件。

字幕、投屏、收藏夹与播放队列、弹幕、后台播放与通知栏控制 —— 这些是"通用视频库"
的功能表，与实际用途（网页短剧播放）无关，已从规格中删除而不是留在文档里充数。

## 构建

```bash
git clone https://github.com/hea784/SafeStream.git
cd SafeStream
./gradlew assembleDebug
```

需要 JDK 17 + Android SDK 35。Gradle 8.13（wrapper 已包含）/ AGP 8.7.3 /
Kotlin 2.0.21 / compileSdk 35 / minSdk 24。

Windows 上的本地环境变量（不写入仓库，按你的实际路径调整）：

```powershell
$env:JAVA_HOME="D:\dev-tools\sdks\jdk-17.0.20.1+1"
$env:ANDROID_HOME="D:\dev-tools\sdks\android-sdk"
$env:GRADLE_USER_HOME="D:\dev-tools\caches\gradle"
```

## 发版

```bash
python tools/release.py --version 0.9.0   # 改版本号 -> 构建签名 -> 校验签名 -> 提交打标签 -> 发 Release
python tools/release.py --bump --dry-run  # 只跑到签名校验
```

签名凭据放在仓库外（`D:\dev-tools\secrets\safestream\`），找不到时回落到 debug 签名。

## 测试

三层，越靠下越贵：

```bash
# 1. JVM 纯逻辑（61 个用例，不碰设备）
./gradlew testDebugUnitTest

# 2. 设备端：真实 WebView 的加固与回调（7 个用例）
./gradlew connectedDebugAndroidTest

# 3. 端到端脚本（需要 AVD + 本地夹具服务）
python tools/make_episode_fixture.py            # 选集夹具（4 集 + MSE 流）
python tools/make_episode_fixture.py --seconds 60 --out testfixture_ep_long
python tools/make_security_fixture.py          # 恶意链接夹具
python tools/check_scanner.py                  # 扫描脚本 node --check + 关键钩子断言
python tools/test_autonext.py                  # 连播链 1->2->3
python tools/test_ui_protection.py             # 剥离/面板/暂停/看过标记（长片夹具，端口 8094）
```

端到端脚本的观察通道有个坑值得知道：**播放中的 `uiautomator dump` 拿不到 idle**
（`ERROR: could not get idle state`），所以点按走 `dumpsys` 视图层级坐标，
断言只在暂停态做。脚本注释里写了来龙去脉。

测试用的 `ALLOW_PRIVATE_HOSTS` 与明文放行只对 debug 构建生效，
release 里 `UrlGuard` 的内网拦截与明文闸门都保持默认拒绝。

## 许可

MIT。见 [LICENSE](LICENSE)。
