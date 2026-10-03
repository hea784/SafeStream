# SafeStream

粘贴一个网址 → 自动找出这个页面里的所有视频 → 在原生播放器里播放，
同时把页面能带来的危害尽可能挡在外面。**只做 Android。**

> 本仓库的需求规格写在 [PROMPT.md](PROMPT.md)。代码改动请同步回写那里。

## 先说清楚两件事

**一、这个 App 不能保证"零风险"。**
Android WebView 和系统浏览器共用同一个渲染内核，没有任何第三方 App 能真正"隔离"网页恶意内容。
SafeStream 的做法是**最小权限 + 无桥接 + 可观测**：不给页面任何原生能力、不给它任何本地访问、
把它关在一个独立进程里、退出就擦干净。声称绝对安全的实现都是不可信的。

**二、编译与运行状态：均已在模拟器上实测通过。**
在 Windows 11 + Temurin JDK 17.0.20.1 + Android SDK 35 + Gradle 8.13 下，
`assembleDebug` 与 `assembleRelease`（含 R8 混淆、资源压缩、`lintVitalRelease`）均为
`BUILD SUCCESSFUL`，debug APK 9.36 MB。

运行验证在 AVD `venera_test`（pixel_6 / API 35 / x86_64 / 1080x2400）上完成，
用本地 HTTP 夹具页跑通了"沙箱打开页面 -> 发现视频 -> 原生播放"全链路，
自动化脚本见 [tools/e2e_smoke.py](tools/e2e_smoke.py)，实测截图见 [docs/screenshots/](docs/screenshots/)。

未验证：真机（不同厂商 WebView）、ARM 架构、HLS/DASH 流、字幕、投屏、后台播放。

## 架构

```
主进程 (:main)                     沙箱进程 (:sandbox)
+--------------------------+       +--------------------------+
| MainActivity             |       | WebHostActivity          |
|  地址栏 / UrlGuard       | 广播  |  零桥接 WebView           |
|  播放列表                |<----->|  拒绝弹窗/权限/下载       |
|  ExoPlayer (Media3)      |       |  拦截广告与跟踪           |
+--------------------------+       +--------------------------+
                              显式 Intent
```

页面在 `:sandbox` 进程里渲染，播放器在主进程。沙箱崩了播放器照跑；
播放器崩了也不影响已经渲染的页面。两边只通过**显式 Intent 广播**说话，没有 AIDL、没有共享内存。

### 一个实测踩到的坑

`onPermissionRequest` / `onCreateWindow` / `onShowFileChooser` /
`onGeolocationPermissionsShowPrompt` **不在 `WebViewClient` 上，而在 `WebChromeClient` 上**。
把它们写进 `WebViewClient` 能通过"看起来对"的直觉，但运行时完全不生效 ——
四条安全契约会静默变成空话。编译器最终也会报 `overrides nothing`。

另外 WebView 的 Safe Browsing **没有 API 也没有 WebSettings 开关**，
只能用 `AndroidManifest.xml` 里的 `android.webkit.WebView.EnableSafeBrowsing` meta-data 开启。

### 运行期才暴露的三个坑（编译全部通过）

1. **明文对话框是假的。** 原实现里 `cleartextTrafficPermitted=false` 让 WebView 在平台网络栈
   就掐断明文，用户点"仍要加载"也没用，页面全白。现在平台层放开、闸门移到
   `shouldInterceptRequest`，只放行用户确认过的 host 及其子域。
2. **RecyclerView 没设 LayoutManager。** 适配器里数据齐全、标题也显示"发现 3 个视频"，
   但列表一行都不渲染。编译期和 `lintVitalRelease` 都不会报，只有真跑起来才看得见。
3. **播放器默认撑满整屏。** 黑色视频区把播放列表和倍速按钮全挤到导航栏后面。
   改成 16:9 固定比例 + 列表常驻 + 居中空状态。

## 安全设计（对应 PROMPT.md 第 4 节）

| 措施 | 挡的是什么 |
| --- | --- |
| 独立进程 `:sandbox` | 沙箱崩溃不影响主界面 |
| **不用** `addJavascriptInterface` | 页面脚本无法调用任何原生方法 |
| `addWebMessageListener` + origin 白名单 | 唯一的数据回传通道，可限域 |
| 拒绝 `file:` `content:` `intent:` `market:` `javascript:` `blob:` `data:` | 读本地文件、拉起别的 App、注入脚本 |
| 关闭文件/内容访问、跨 file URL 访问 | 页面读不到手机里的任何文件 |
| `MIXED_CONTENT_NEVER_ALLOW` + `cleartextTrafficPermitted=false` | 明文降级攻击 |
| 禁止弹窗、禁止自动开窗 | 无痕广告弹窗、劫持新窗口 |
| `onPermissionRequest` 一律 deny | 页面拿不到摄像头/麦克风/定位 |
| `onShowFileChooser` 返回 null | 页面无法用"上传"骗你点授权 |
| `DownloadListener` 一律 abort | 页面不能骗你下 APK |
| 域名/路径黑名单拦截 | 广告、跟踪、浏览器挖矿脚本 |
| Safe Browsing 开启 | 已知恶意站点 |
| 证书错误直接 cancel | 中间人 |
| 渲染进程崩溃即销毁整个沙箱 | 被污染的渲染状态 |
| 退出清 Cookie / WebStorage / Cache / History / FormData | 站点不留痕 |
| 只申请 `INTERNET` + `ACCESS_NETWORK_STATE` | 权限面本身 |
| 禁备份、禁明文 | 数据外流 |

地址栏还有 `UrlGuard`：自动补 `https://`、拒绝内网地址（防 SSRF）、拒绝超长与含控制字符的输入、
明文 HTTP 必须用户点确认才加载。

## 已实现功能

- 地址栏加载 + 历史记录
- 自动视频发现（DOM 扫 `<video>` / `<source>` / `data-*`，加 Performance API 抓网络流）
- 播放列表：多视频、当前项高亮、单视频自动开播
- ExoPlayer 播放：HLS / DASH / 直链
- 倍速 0.75x - 2.0x
- 断点续播（播过 95% 的不记录）
- 防护开关实时切换
- 长按防护按钮 = 清理全部本地数据并停沙箱

## 未实现（P1，结构已预留）

字幕轨、投屏、收藏夹与播放队列、弹幕、后台播放与通知栏控制。

## 不做的事

不绕 DRM、不下载版权内容、不注入去广告代码二次分发、不集成第三方追踪 SDK、不用闭源商业组件。

## 构建

```bash
git clone https://github.com/<你的账号>/SafeStream.git
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

## 跑自动化冒烟测试

```bash
# 1. 启动 AVD（avd_home 必须显式给出，否则报 Unknown AVD name）
ANDROID_AVD_HOME=D:\dev-tools\caches\android\avd \
  D:\dev-tools\sdks\android-sdk\emulator\emulator.exe \
  -avd venera_test -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim

# 2. 生成夹具（需要 ffmpeg）与本地服务
python tools/make_test_fixture.py
cd tools/testfixture && python -m http.server 8099 --bind 127.0.0.1

# 3. 让设备能访问宿主服务
adb -s emulator-5554 reverse tcp:8099 tcp:8099

# 4. 跑测试
python tools/e2e_smoke.py
```

测试用的 `ALLOW_PRIVATE_HOSTS` 与明文放行只对 debug 构建生效，
release 里 `UrlGuard` 的内网拦截与明文闸门都保持默认拒绝。

## 许可

MIT。见 [LICENSE](LICENSE)。
