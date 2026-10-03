# SafeStream 编码提示词

> 本文件由需求方口述需求整理而成，是本仓库的规格说明书（source of truth）。
> 任何实现变更都应回写到这里，而不是只改代码。

## 1. 一句话需求

做一个**只做安卓端**的视频播放器 App：用户填入一个网址，App 打开这个网址并**自动发现该页面里的所有视频**，用户点哪一段就在哪一段播放；与此同时，App 把这个网址能带来的**一切危害尽可能隔绝**。功能对标市面大型视频软件。

## 2. 需求方原话 → 拆解

| 原话 | 拆成可验收的工程要求 |
| --- | --- |
| 在 GitHub 上创建一个项目 | 单仓库、MIT 许可、含 README/PROMPT/LICENSE、代码可 Gradle 构建 |
| 填入网址 | 顶部地址栏，回车/按钮加载；自动补全 `https://`；历史记录 |
| 看到填入网址的所有视频 | 打开页面后自动扫描：`<video>` 元素、`source` 子标签、页面网络请求里出现的 `.m3u8` / `.mp4` / `.webm` / `.mov`，汇总成播放列表 |
| 在这个软件看到 | 播放列表 UI + 原生播放器（Media3/ExoPlayer），不是只在网页里被动看 |
| 隔绝这个网址的一切危害 | 见第 4 节安全契约 |
| 大型视频软件的一切功能 | 见第 5 节功能清单，按 P0/P1/P2 分级 |
| 只做安卓端 | 无 iOS、无 Web 版、无桌面版 |
| 借鉴优秀开源项目 | 见第 7 节 |

## 3. 架构约束

- 单模块 Android App，Kotlin，ViewBinding，Material 3。
- **双进程**：`MainActivity` 在主进程；承载 WebView 的 `WebHostActivity` 声明 `android:process=":sandbox"`。
- 主进程与沙箱进程之间**只用显式 Intent 广播通信**（targetSdk 33+ 显式声明 `RECEIVER_NOT_EXPORTED`）。两个进程共享系统 WebView 内核，这是平台事实，隔离靠"最小权限 + 无桥接"而非"另开内核"。
- 播放器（Media3）运行在主进程，沙箱只负责"安全地把页面渲染出来并报告发现了哪些视频"。

## 4. 安全契约（可逐条验收）

1. 页面运行在**独立进程** `:sandbox`，沙箱崩溃不影响播放器。
2. **零 `addJavascriptInterface`**。页面→原生唯一通道是 `WebViewCompat.addWebMessageListener`（带 allowedOriginRules 白名单），不暴露任何原生对象。
3. 拒绝一切非 `https`/`http` 的导航：`file://`、`content://`、`intent://`、`market://`、`javascript:`、`blob:`、`data:` 一律 `return true`（即拦截）。
4. `http://` 默认拦截并提示用户"该站点未加密"，需用户显式放行。
5. 文件访问全关：`allowFileAccess=false`、`allowContentAccess=false`、`allowFileAccessFromFileURLs=false`、`allowUniversalAccessFromFileURLs=false`。
6. 混合内容一律拒绝：`MIXED_CONTENT_NEVER_ALLOW`；`networkSecurityConfig` 中 `cleartextTrafficPermitted=false`。
7. 不申请任何位置/相机/麦克风/通讯录/存储权限；`onPermissionRequest` 与 `onGeolocationPermissionsShowPrompt` 一律 `deny()`。
8. 不允许弹窗：`setSupportMultipleWindows(false)`，`onCreateWindow` 返回 `false`（防无痕弹窗与广告劫持）。
9. 不允许上传文件：`onShowFileChooser` 直接 `null`。
10. 禁止下载：`DownloadListener` 一律 `abort()`，不下 APK、不下 zip。
11. 禁止跳外部 App：`shouldOverrideUrlLoading` 中任何 `market://`、`intent://`、自定义 scheme 全部拦截。
12. 广告/跟踪/挖矿域名在 `shouldInterceptRequest` 层按域名与后缀黑名单拦截（`ads.`、`doubleclick`、`googlesyndication`、`adservice`、`analytics`、`/ads/`、`/pagead/` 等），并有可开关的白名单配置。
13. 开启 `WebSettings.safeBrowsingEnabled=true`，Release 包 `setWebContentsDebuggingEnabled(false)`。
14. 沙箱退出 / 页面关闭时：`CookieManager.removeAllCookies`、`WebStorage.deleteAllData`、`clearCache(true)`、`clearHistory()`、`clearFormData()`。
15. Release 包 `android:allowBackup="false"`、`android:usesCleartextTraffic="false"`、无 `android:debuggable`。
16. URL 输入做长度与字符校验，拒绝 `javascript:` 等注入型 scheme。

> 诚实边界：上述措施降低风险，但无法承诺"零风险"。Android WebView 与系统浏览器共用同一内核，任何声称绝对隔离网页恶意内容的实现都是不可信的。App 的立场是"最小权限 + 可观测 + 一键退出"。

## 5. 功能分级

**P0（本仓库已实现）**
- 地址栏加载、历史记录（时间 + 标题，不显示任务编号）
- 自动视频发现：DOM 扫描 + 网络流嗅探，结果进播放列表
- 播放列表：多视频、点选播放、当前项高亮
- 原生播放器：播放/暂停、拖动、时长、倍速、亮度/音量手势、画面比例
- 观看进度记忆与断点续播
- 图片画中画（PiP）
- 单视频全屏竖屏/横屏
- 页面内的广告/跟踪拦截开关
- 一键"关闭沙箱并清理数据"

**P1（结构已预留，未实现）**
- 字幕轨（SRT/VTT）与字幕样式
- 投屏（Cast / DLNA）
- 收藏夹与播放队列（`PlayerQueueHolder` 接口位）
- 弹幕
- 后台播放与通知栏控制（MediaSession）

**P2（暂不做）**
- 离线下载、账号体系、推荐算法、多端同步

## 6. 验收方式

- `./gradlew assembleDebug` 通过。
- 手测：打开一个含多个视频的页面，播放列表条数与页面中视频数量一致。
- 安全回归：页面尝试 `file://` 跳转、弹窗、请求定位、触发下载，均无任何系统级反应。
- 关闭沙箱后重新打开同一站点，Cookie 状态为"全新访客"。

## 7. 参考与借鉴

- 沙箱思路：Android 官方 *WebView 安全最佳实践*、`WebViewAssetLoader` 思路。
- 原生播放：AndroidX Media3 `PlayerView` / `ExoPlayer`。
- 安全通信：`androidx.webkit` `addWebMessageListener`（替代高危 `addJavascriptInterface`）。
- 拦截层：OkHttp `Interceptor` 做域名过滤，思路参考常见本地 DNS/hosts 型过滤方案。
- 产品形态对标：MX Player、VLC for Android、YouTube/Bilibili 的播放列表与手势交互。

## 8. 硬性红线

- 不绕过 DRM，不做下载任何受版权保护内容的功能。
- 不注入广告代码，不修改页面内容以"去广告后二次分发"。
- 不集成任何第三方追踪 SDK。
- 不使用闭源商业组件。
