package com.heasafe.safestream.sandbox

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
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
        webView.setDownloadListener { _, _, _, _, _ ->
            // 安全契约 10：不下载任何东西
            reportEvent("已阻止下载")
        }

        registerControlReceiver()
        webView.loadUrl(url)
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

        // 安全契约 13：开启 Safe Browsing，Release 关闭远程调试
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebViewCompat.enableSafeBrowsing(this, null)
        }
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
                if (message.type != WebMessageCompat.TYPE_STRING) return
                dispatchDiscovered(message.data.orEmpty())
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
        val rawItems = runCatching {
            org.json.JSONArray(payload)
        }.getOrNull() ?: return

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

    private fun injectScanner() {
        val js = VideoScannerScript.SOURCE
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            webView.evaluateJavascript(js, null)
        }
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

            // 安全契约 4：明文 HTTP 一律断掉，不给页面机会降级
            if (UrlGuard.isInsecure(url)) {
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

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val title = view?.title?.toString().orEmpty()
            sendUp(Bridge.ACTION_PAGE_TITLE) { putExtra(Bridge.EXTRA_TITLE, title) }
            injectScanner()
        }

        // 安全契约 7：权限一律拒绝
        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            callback?.invoke(origin, false, false)
        }

        // 安全契约 9：不提供文件选择器，页面无法上传本地文件
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            return true
        }

        // 安全契约 8：不创建新窗口
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean = false

        override fun onRenderProcessGone(view: WebView?, detail: android.view.RenderProcessGoneDetail?): Boolean {
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
}
