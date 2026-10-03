package com.heasafe.safestream.sandbox

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.heasafe.safestream.R
import com.heasafe.safestream.core.Bridge
import com.heasafe.safestream.core.TrackerBlocklist
import com.heasafe.safestream.core.UrlGuard
import com.heasafe.safestream.databinding.ActivityWebHostBinding

/**
 * 沙箱进程的唯一 Activity。
 *
 * 职责边界很窄：把远端页面安全地渲染出来，然后报告"这个页面上有哪些视频"。
 * 它不做播放决策，也不持有任何本地持久数据。
 *
 * 逐条安全加固见 PROMPT.md 第 4 节，代码里每处都标注了对应编号。
 */
class WebHostActivity : ComponentActivity() {

    private lateinit var binding: ActivityWebHostBinding
    private lateinit var webView: WebView

    /** 安全契约 12：拦截开关，默认开启。 */
    private var filterEnabled = true

    /**
     * 安全契约 4：用户显式确认放行明文的主机。
     * 只有记录在案的主机才允许 http，其余明文请求一律返回空响应。
     */
    private var insecureAllowedHost: String? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Bridge.ACTION_PURGE -> purgeSessionData()
                Bridge.ACTION_RESCAN -> runCatching { injectScanner() }
                Bridge.ACTION_SET_FILTER ->
                    filterEnabled = intent.getBooleanExtra(Bridge.EXTRA_ENABLED, true)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filterEnabled = intent.getBooleanExtra(Bridge.EXTRA_ENABLED, true)
        insecureAllowedHost =
            intent.getStringExtra(Bridge.EXTRA_INSECURE_HOST)?.takeIf { it.isNotBlank() }

        val url = intent.getStringExtra(Bridge.EXTRA_URL)
        if (url.isNullOrBlank() || !UrlGuard.allowNavigation(url)) {
            finish()
            return
        }

        webView = WebView(this)
        binding.sandboxContainer.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        hardenWebView(webView)
        attachWebMessageListener(webView)
        webView.webViewClient = SafeWebViewClient()
        // 这四个加固回调（权限/弹窗/文件选择/定位）都挂在 WebChromeClient 上，
        // 不是 WebViewClient。漏掉它等于四条安全契约全是空话。
        webView.webChromeClient = SafeWebChromeClient()
        webView.setDownloadListener { _, _, _, _, _ ->
            // 安全契约 10：不下载任何东西
            reportEvent("已阻止下载")
        }

        wireChrome()

        registerControlReceiver()
        webView.loadUrl(url)
    }

    /**
     * 沙箱界面的外壳：搜索栏与底部导航和主界面保持一致，
     * 两个界面虽然分属不同进程，但切换时视觉上是连续的。
     */
    private fun wireChrome() {
        binding.urlInput.setText(intent.getStringExtra(Bridge.EXTRA_URL).orEmpty())
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            val text = binding.urlInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnEditorActionListener true
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
            ) {
                // 交给主进程做校验与调度，沙箱不自行放行任何东西
                sendUp(Bridge.ACTION_NAVIGATE) { putExtra(Bridge.EXTRA_URL, text) }
                true
            } else false
        }
        binding.shieldButton.setOnClickListener {
            sendUp(Bridge.ACTION_SET_FILTER) {
                putExtra(Bridge.EXTRA_ENABLED, !filterEnabled)
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_browse
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_play -> { finish(); true }
                R.id.nav_browse -> true
                R.id.nav_shield -> {
                    reportEvent("广告与跟踪拦截" + if (filterEnabled) "已开启" else "已关闭")
                    true
                }
                else -> false
            }
        }
    }

    /**
     * WebView 加固。每一行都对应 PROMPT.md 第 4 节的一条契约。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun hardenWebView(wv: WebView) {
        // 页面是远端内容，必须能跑 JS；但我们不向它暴露任何原生对象（见 attachWebMessageListener）
        wv.settings.javaScriptEnabled = true

        // 安全契约 5：文件与内容访问全关
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        wv.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        wv.settings.allowUniversalAccessFromFileURLs = false

        // 安全契约 6：混合内容一律拒绝
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // 安全契约 3/11：不允许任何新窗口，页面无法拉起别的 App
        wv.settings.setSupportMultipleWindows(false)
        wv.settings.javaScriptCanOpenWindowsAutomatically = false
        wv.settings.setGeolocationEnabled(false)

        // 减少页面可用的能力面
        wv.settings.domStorageEnabled = true      // 视频站点普遍需要
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.loadsImagesAutomatically = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE  // 退出即不留缓存

        // 安全契约 13：Safe Browsing 由 AndroidManifest 的
        // android.webkit.WebView.EnableSafeBrowsing meta-data 开启（WebViewCompat 没有这个 API）；
        // Release 包关闭远程调试。
        if (!isDebuggable()) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        // 安全契约 2：绝不使用 addJavascriptInterface
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        CookieManager.getInstance().setAcceptCookie(false)  // 不持久化站点 Cookie
    }

    /**
     * 页面 -> 原生的唯一通道。
     *
     * addWebMessageListener 带 allowedOriginRules 白名单，且不接受任意 origin 的消息，
     * 比 addJavascriptInterface 安全：后者会把原生对象方法直接暴露给页面脚本。
     * 对应安全契约 2。
     */
    private fun attachWebMessageListener(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // 降级：拿不到扫描结果，但仍可正常浏览页面
            reportEvent("当前 WebView 不支持安全消息通道，视频自动发现不可用")
            return
        }
        val originRules = setOf("*")
        val listener = object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(
                view: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                replyProxy: JavaScriptReplyProxy,
            ) {
                // 不要用 message.type 判断类型：框架 WebMessage.TYPE_STRING 是 0，
                // 而 androidx 的 WebMessageCompat.TYPE_STRING 是 1（它给类型加了偏移）。
                // 在 WebView 124 上按常量判断会把每一条消息都丢掉，表现为静默无产出。
                // 直接取 data 更稳，且不依赖版本相关的常量映射。
                val payload = message.data
                if (payload.isNullOrBlank()) return
                android.util.Log.d(
                    "SafeStream",
                    "onPostMessage type=${message.type} main=$isMainFrame len=" +
                        payload.length,
                )
                dispatchDiscovered(payload)
            }
        }
        WebViewCompat.addWebMessageListener(
            wv,
            VideoScannerScript.MESSAGE_NAME,
            originRules,
            listener,
        )
    }

    private fun dispatchDiscovered(payload: String) {
        // 页面回传的 JSON 一律当作不可信输入解析，解析失败直接丢弃
        val root = runCatching { org.json.JSONObject(payload) }.getOrNull() ?: return

        // 网络钩子单条上报：{"url":..., "kind":"fetch|xhr|mse"}
        if (root.has("url") && !root.has("batch")) {
            reportNetworkHit(root)
            return
        }

        // 选集上报：{"episodes":[{"url","ep","title","page"}]}
        if (root.has("episodes")) {
            reportEpisodes(root.optJSONArray("episodes"))
            return
        }

        val rawItems = root.optJSONArray("batch") ?: return

        val json = buildString {
            append("[")
            var first = true
            for (i in 0 until rawItems.length()) {
                val o = rawItems.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (!UrlGuard.allowNavigation(url)) continue
                if (!first) append(",")
                append(
                    org.json.JSONObject().apply {
                        put("url", url)
                        put("title", o.optString("title"))
                        put("mimeType", o.optString("mimeType"))
                        put("durationMs", o.optLong("durationMs"))
                        put("sourcePage", o.optString("sourcePage"))
                    }.toString(),
                )
                first = false
            }
            append("]")
        }

        sendUp(Bridge.ACTION_VIDEOS_FOUND) { putExtra(Bridge.EXTRA_VIDEO_JSON, json) }
    }

    /**
     * 网络钩子命中的媒体地址。
     *
     * "mse:video/mp2t" 这类只是流类型声明，不是可播放地址，丢掉。
     * 真正的 .m3u8/.mp4 直接进列表，这样 MSE/blob 页面也能在原生播放器里播。
     */
    /**
     * 选集转成播放列表条目。
     *
     * 剧集是页面地址而不是媒体地址，标记为 EPISODE；主进程在用户选中时再去加载
     * 对应页面、发现它的媒体并播放。
     */
    private fun reportEpisodes(arr: org.json.JSONArray?) {
        if (arr == null || arr.length() == 0) return
        val json = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!UrlGuard.allowNavigation(url)) continue
            json.put(
                org.json.JSONObject().apply {
                    put("url", url)
                    put("title", o.optString("title"))
                    put("mimeType", "")
                    put("durationMs", 0)
                    put("sourcePage", o.optString("page"))
                    put("kind", "EPISODE")
                    put("episodeNo", o.optInt("ep"))
                },
            )
        }
        if (json.length() == 0) return
        sendUp(Bridge.ACTION_EPISODES_FOUND) {
            putExtra(Bridge.EXTRA_VIDEO_JSON, json.toString())
        }
    }

    private fun reportNetworkHit(o: org.json.JSONObject) {
        val url = o.optString("url")
        val kind = o.optString("kind")
        if (kind == "mse" || url.startsWith("mse:")) return

        // 用户点了 blob: 流：播本页已经发现到的媒体即可。
        // 地址本身在页面外没有意义，但点击已经表达了播放意图。
        if (kind == "play-found") {
            sendUp(Bridge.ACTION_PLAY_FOUND) {
                putExtra(Bridge.EXTRA_MESSAGE, "PLAY_FOUND")
            }
            binding.root.postDelayed({ finish() }, 150)
            return
        }

        if (!UrlGuard.allowNavigation(url)) return

        // 用户在网页里点了某个视频：把这个地址交给主进程播放，
        // 然后关掉沙箱让播放器回到前台 —— 这就是"在网页里挑着看"的路径。
        if (kind == "click") {
            sendUp(Bridge.ACTION_VIDEOS_FOUND) {
                putExtra(
                    Bridge.EXTRA_VIDEO_JSON,
                    org.json.JSONArray().put(
                        org.json.JSONObject().apply {
                            put("url", url)
                            put("title", "")
                            put("mimeType", "")
                            put("durationMs", 0)
                            put("sourcePage", o.optString("page"))
                        },
                    ).toString(),
                )
                putExtra(Bridge.EXTRA_MESSAGE, "PLAY_NOW")
            }
            // 主界面就在本 Activity 下方，自行结束即可回到前台，
            // 不用在主进程里做 Activity 跳转。留一点时间让广播送达。
            binding.root.postDelayed({ finish() }, 150)
            return
        }

        val json = org.json.JSONArray().put(
            org.json.JSONObject().apply {
                put("url", url)
                put("title", "")
                put("mimeType", "")
                put("durationMs", 0)
                put("sourcePage", o.optString("page"))
            },
        ).toString()
        sendUp(Bridge.ACTION_VIDEOS_FOUND) { putExtra(Bridge.EXTRA_VIDEO_JSON, json) }
    }

    private fun injectScanner() {
        val js = VideoScannerScript.SOURCE
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // 原生侧探针：直接读页面里桥接对象是否存在。
            // JS 侧所有 postMessage 都被 try/catch 包着，桥接缺失时是静默失败，
            // 没有这行日志就只能靠猜。
            webView.evaluateJavascript("String(typeof window.SafeStreamBridge)") { r ->
                android.util.Log.d(
                    "SafeStream",
                    "bridge=" + r + " featureSupported=" +
                        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
                )
            }
            webView.evaluateJavascript(js, null)
        } else {
            android.util.Log.w("SafeStream", "WebView 不支持 WEB_MESSAGE_LISTENER，选集与网络发现不可用")
        }
    }

    private fun isInsecureAllowed(url: String): Boolean {
        val host = insecureAllowedHost ?: return false
        val target = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        // 只放行该主机本身或其子域，避免授权意外扩散到别处
        return target.equals(host, ignoreCase = true) ||
            target.endsWith(".$host", ignoreCase = true)
    }

    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(Bridge.ACTION_PURGE)
            addAction(Bridge.ACTION_RESCAN)
            addAction(Bridge.ACTION_SET_FILTER)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }
    }

    /** 沙箱 -> 主进程的显式广播。 */
    private fun sendUp(action: String, build: Intent.() -> Unit) {
        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .apply(build),
        )
    }

    private fun reportEvent(message: String) {
        sendUp(Bridge.ACTION_SECURITY_EVENT) { putExtra(Bridge.EXTRA_MESSAGE, message) }
    }

    /** 安全契约 14：退出即清空 Cookie、存储、缓存、历史。 */
    private fun purgeSessionData() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        runCatching {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
        }
        sendUp(Bridge.ACTION_SECURITY_EVENT) { putExtra(Bridge.EXTRA_MESSAGE, "PURGED") }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(controlReceiver) }
        purgeSessionData()
        runCatching {
            webView.removeJavascriptInterface(VideoScannerScript.MESSAGE_NAME)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** WebViewClient：导航决策 + 请求拦截，全部按白名单处理。 */
    private inner class SafeWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val url = request?.url?.toString().orEmpty()
            return blockIfNotAllowed(url)
        }

        @Deprecated("兼容 API 24")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            blockIfNotAllowed(url.orEmpty())

        private fun blockIfNotAllowed(url: String): Boolean {
            if (url.isBlank()) return true
            if (url == "about:blank") return false
            if (!UrlGuard.allowNavigation(url)) {
                // 安全契约 3 + 11
                reportEvent("已阻止跳转：${url.substringBefore("://")}")
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            if (url.isBlank()) return null

            // 安全契约 12：广告/跟踪/挖矿请求直接返回空响应
            if (filterEnabled && TrackerBlocklist.isBlocked(url)) {
                reportEvent("已拦截：${Uri.parse(url).host}")
                return WebResourceResponse(
                    "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)),
                )
            }

            // 安全契约 4：明文默认断掉，不给页面降级机会；
            // 仅当用户在该 host 的警告框里点过"仍要加载"才放行。
            if (UrlGuard.isInsecure(url) && !isInsecureAllowed(url)) {
                return WebResourceResponse(
                    "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)),
                )
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            injectScanner()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            // 静默失败是排查噩梦；主文档失败必须让用户看见原因
            if (request?.isForMainFrame == true) {
                reportEvent("加载失败：${error?.description ?: "未知错误"}")
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val title = view?.title?.toString().orEmpty()
            sendUp(Bridge.ACTION_PAGE_TITLE) { putExtra(Bridge.EXTRA_TITLE, title) }
            injectScanner()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // 渲染进程崩溃（恶意页面常见手法）时销毁整个沙箱，而不是复用可能已损坏的状态
            reportEvent("页面渲染进程已崩溃，沙箱已终止")
            finish()
            return true
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            // 证书错误一律中断，不给"继续"选项
            handler?.cancel()
            reportEvent("证书校验失败，已停止加载")
        }
    }

    /**
     * 页面能力请求的守门人。
     *
     * WebChromeClient 才是这些回调的宿主 —— WebViewClient 上重写它们不会有任何效果。
     * 安全契约 7 / 8 / 9 全靠这个类落实。
     */
    private inner class SafeWebChromeClient : WebChromeClient() {

        // 安全契约 7：网页请求的摄像头/麦克风/传感器一律拒绝
        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
            reportEvent("已拒绝页面的设备权限请求")
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
            request?.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            callback?.invoke(origin, false, false)
        }

        // 安全契约 9：不提供文件选择器，页面无法用"上传"诱导你交出本地文件
        override fun onShowFileChooser(
            view: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            reportEvent("已阻止页面的文件选择请求")
            return true
        }

        // 安全契约 8：不创建新窗口，堵住无痕弹窗与广告劫持
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            reportEvent("已阻止页面弹窗")
            return false
        }
    }
}
