# SafeStream

粘贴一个网址 → 自动找出这个页面里的所有视频 → 在原生播放器里播放，
同时把页面能带来的危害尽可能挡在外面。**只做 Android。**

> 本仓库的需求规格写在 [PROMPT.md](PROMPT.md)。代码改动请同步回写那里。

## 先说清楚两件事

**一、这个 App 不能保证"零风险"。**
Android WebView 和系统浏览器共用同一个渲染内核，没有任何第三方 App 能真正"隔离"网页恶意内容。
SafeStream 的做法是**最小权限 + 无桥接 + 可观测**：不给页面任何原生能力、不给它任何本地访问、
把它关在一个独立进程里、退出就擦干净。声称绝对安全的实现都是不可信的。

**二、仓库代码未经编译验证。**
创建这个仓库的机器上没有 JDK / Android SDK / Gradle，因此没有产出过 APK。
`assembleDebug` 能否通过需要你在本地跑一次——这是已知状态，不是我推测。

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
gradle wrapper          # 仓库只带了 wrapper.properties，jar 需先生成
./gradlew assembleDebug
```

Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 24。

## 许可

MIT。见 [LICENSE](LICENSE)。
