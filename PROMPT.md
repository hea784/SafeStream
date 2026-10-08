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
- **单界面单进程**：`MainActivity` 一个界面承载全部 —— 顶部搜索栏 + 常驻 WebView +
  迷你播放器/全屏容器。早期的"双进程双 Activity"（WebView 跑 `:sandbox` 进程、
  Intent 广播通信）已撤销：WebView 的渲染进程本来就是 Chromium 沙箱化的独立进程，
  `:sandbox` 多买的只是崩溃与内存压力隔离，不值一整套跨进程广播协议；
  渲染进程崩溃由 `onRenderProcessGone` 兜底（重建网页，播放与选集状态不动）。
- 页面→原生的唯一通道是 `addWebMessageListener`（带 allowedOriginRules 白名单），
  不因进程合并而改变，不暴露任何原生对象。
- 播放器（Media3）与 WebView 同处一个界面，点网页里的视频直接切到原生播放。

## 4. 安全契约（可逐条验收）

1. 页面渲染由 Chromium 自己的沙箱渲染进程承载（`sandboxed_process`）；宿主用
   `onRenderProcessGone` 兜住渲染崩溃，播放器与选集状态不受影响
   （原 `:sandbox` 双进程方案已撤销，见第 3 节）。
2. **零 `addJavascriptInterface`**。页面→原生唯一通道是 `WebViewCompat.addWebMessageListener`（带 allowedOriginRules 白名单），不暴露任何原生对象。
3. 拒绝一切非 `https`/`http` 的导航：`file://`、`content://`、`intent://`、`market://`、`javascript:`、`blob:`、`data:` 一律 `return true`（即拦截）。
4. `http://` 默认拦截并提示用户"该站点未加密"，需用户显式放行。
   **实现细节（实测踩坑）**：`network_security_config.xml` 的
   `cleartextTrafficPermitted` 必须为 `true`，否则 WebView 在**平台网络栈**就会掐断明文，
   用户在对话框里点"仍要加载"也没用——对话框成了假的，页面一片空白。
   真正的闸门因此放在 `shouldInterceptRequest`：只放行用户确认过的那个 host 及其子域。
   代价是平台层不再兜底，该拦截必须始终保持"默认拒绝"。
5. 文件访问全关：`allowFileAccess=false`、`allowContentAccess=false`、`allowFileAccessFromFileURLs=false`、`allowUniversalAccessFromFileURLs=false`。
6. 混合内容一律拒绝：`MIXED_CONTENT_NEVER_ALLOW`；`networkSecurityConfig` 中 `cleartextTrafficPermitted=false`。
7. 不申请任何位置/相机/麦克风/通讯录/存储权限；`onPermissionRequest` 与 `onGeolocationPermissionsShowPrompt` 一律 `deny()`。
8. 不允许弹窗：`setSupportMultipleWindows(false)`，`onCreateWindow` 返回 `false`（防无痕弹窗与广告劫持）。
9. 不允许上传文件：`onShowFileChooser` 直接 `null`。
10. 禁止下载：`DownloadListener` 一律 `abort()`，不下 APK、不下 zip。
11. 禁止跳外部 App：`shouldOverrideUrlLoading` 中任何 `market://`、`intent://`、自定义 scheme 全部拦截。
12. 广告/跟踪/挖矿域名在 `shouldInterceptRequest` 层按域名与后缀黑名单拦截（`ads.`、`doubleclick`、`googlesyndication`、`adservice`、`analytics`、`/ads/`、`/pagead/` 等），并有可开关的白名单配置。
13. 开启 WebView Safe Browsing（**只能**用 `AndroidManifest.xml` 里的
    `android.webkit.WebView.EnableSafeBrowsing` meta-data，不存在对应的 API 或 WebSettings 开关），
    Release 包 `setWebContentsDebuggingEnabled(false)`。
13b. 页面能力类回调（`onPermissionRequest`、`onCreateWindow`、`onShowFileChooser`、
    `onGeolocationPermissionsShowPrompt`）必须挂在 **`WebChromeClient`** 上。
    它们在 `WebViewClient` 上不存在，重写了不会有任何效果 —— 这是本项目实测踩过的坑。
14. 沙箱退出 / 页面关闭时：`CookieManager.removeAllCookies`、`WebStorage.deleteAllData`、`clearCache(true)`、`clearHistory()`、`clearFormData()`。
    退出即清这条在架构合并（双 Activity 合为单 Activity）时曾被静默丢掉，
    现由 `onDestroy` 恢复；且 App 自己驱动的换页要清 WebView 回退栈，
    否则返回键被 WebView 历史吞掉、用户退不出应用（也就不会触发清理）。
    返回键语义（2026-10-07 补）：用户在网页内点出来的导航历史可以用返回键回退
    （`canGoBack` → `goBack`）；App 自己驱动的换页（提交网址、切集）已清出回退栈，
    回退栈空时返回键才退出应用 —— 既保住"浏览器式回退"，也保住"退得出、退即清"。
15. Release 包 `android:allowBackup="false"`、`android:usesCleartextTraffic="false"`、无 `android:debuggable`。
16. URL 输入做长度与字符校验，拒绝 `javascript:` 等注入型 scheme。
17. 页面导航剥离跟踪参数（`utm_*`、`fbclid`、`gclid`、`msclkid`、`igshid`、
    `spm_id_from` 等，Firefox ETP / Brave / iOS LTP 同一思路）。
    只剥精确白名单 + `utm_` 前缀：**绝不模糊匹配**，签名清单地址的
    `auth_key`/`token`/`sign` 一旦误剥，视频直接播不出来。
    仅作用于主框架导航，媒体请求不受影响。
18. 防护可观测：本次会话的安全事件流水进 `SecurityLog`（封顶 300 条），
    点盾牌弹出面板展示明细、站点内开关与一键清理 —— 只有计数没有明细的
    防护既无法自证工作，也无法发现误伤。

> 诚实边界：上述措施降低风险，但无法承诺"零风险"。Android WebView 与系统浏览器共用同一内核，任何声称绝对隔离网页恶意内容的实现都是不可信的。App 的立场是"最小权限 + 可观测 + 一键退出"。

## 5. 功能分级

**P0（本仓库已实现）**

- 搜索栏：回车提交，自动区分网址与搜索词
- 自动视频发现：DOM 扫描 + fetch/XHR/MediaSource 网络钩子（覆盖 MSE blob 流页面）
- **选集识别**：连续剧的剧集页地址（实测 `/video/<剧id>/ep-<集号>/`），
  列表中排在前；选中后加载该集页面，媒体到达即自动播
- 网页内点视频直接切到原生播放器播放
- 播放列表：点选播放、当前项高亮
- 原生播放器：播放/暂停、拖动、时长、倍速
- 断点续播（进度以 URL 哈希为 key）
- 广告/跟踪拦截开关与拦截计数
- 一键停止沙箱并清理全部本地数据（三个入口均需二次确认，防误触清光播放进度）
- 返回键回退网页历史（回退栈空才退出应用）
- 全屏沉浸：隐藏顶栏与选集浮标、导航条/刘海 inset 归零；提交网址后收起键盘

**已从文档中移除（原先列出但从未实现，或不属于实际用途）**

- ~~图片画中画（PiP）~~ —— 原先写在这里但代码里没有，属于文档说谎
- ~~历史记录~~ —— 存了但界面无任何入口展示，是只写不读的死状态，已删代码
- ~~亮度/音量手势~~ —— 从未实现
- ~~字幕轨（SRT/VTT）~~ —— 目标内容无字幕轨
- ~~投屏（Cast / DLNA）~~ —— 目标使用场景不存在
- ~~弹幕~~ —— 属于特定社区产品，不是通用播放器需求
- ~~后台播放与通知栏控制~~ —— 与"沙箱退出即清理"的安全策略直接冲突，
  真要做需要重新设计沙箱生命周期，不能挂着不做

> 判断依据：这些条目是照着"大型视频软件"的功能表列的，不是从实际使用场景
> （短剧网页播放）推出来的。留着只会让文档显得注水。

**明确不做**

- 离线下载、账号体系、推荐算法、多端同步
- 绕过 DRM
- 访问记录界面（直到真有界面再加回存储逻辑）

## 6. 验收方式与实测结论

自动化脚本 `tools/e2e_smoke.py`，夹具 `tools/make_test_fixture.py`（生成 3 个真实 mp4 +
含视频与恶意链接的测试页），在 AVD `venera_test` 上执行。

| 验收项 | 状态 |
| --- | --- |
| `assembleDebug` / `assembleRelease` | 已通过 |
| App 冷启动、无崩溃 | 已通过 |
| 明文 HTTP 触发警告对话框 | 已通过 |
| 沙箱以 `:sandbox` 独立进程运行 | 已撤销（架构合并为单界面，见第 3 节；渲染崩溃兜底改由 `onRenderProcessGone` 承担） |
| 广告/跟踪请求被拦截 | 已通过（实测拦下 google-analytics、googlesyndication） |
| 无外部 Activity 被拉起（file:// / intent:// / market://） | 已通过 |
| 自动发现 3 个视频 | 已通过 |
| 播放列表渲染 | 已通过 |
| 原生播放器解码出真实画面 | 已通过 |

**仍未验证**：真机（不同厂商 WebView）、ARM 架构、HLS/DASH 流、字幕、投屏、
后台播放与通知栏、Cookie 清理的跨会话效果、真实商业站点的兼容性。

> 教训：上面三个"编译全部通过但运行才炸"的坑（明文对话框失效、RecyclerView 缺
> LayoutManager、播放器撑满整屏）说明 **UI 与 WebView 的正确性只能靠运行时验证**，
> 编译和 lint 都不算证据。

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
