package com.heasafe.safestream.security

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.DownloadListener

/**
 * 把 [WebSecurityPolicy.settings] 里的加固项写进真实 WebView。
 *
 * 抽成顶层函数是为了让 Activity 与设备测试走同一条路径 ——
 * 否则测试验证的是一份和线上不同的配置，等于没测。
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
fun applyHardening(wv: WebView) {
    val p = WebSecurityPolicy.settings
    wv.settings.javaScriptEnabled = true
    wv.settings.allowFileAccess = p.allowFileAccess
    wv.settings.allowContentAccess = p.allowContentAccess
    @Suppress("DEPRECATION")
    wv.settings.allowFileAccessFromFileURLs = p.allowFileAccessFromFileURLs
    @Suppress("DEPRECATION")
    wv.settings.allowUniversalAccessFromFileURLs = p.allowUniversalAccessFromFileURLs
    if (p.mixedContentNeverAllow) {
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    wv.settings.setSupportMultipleWindows(p.supportMultipleWindows)
    wv.settings.javaScriptCanOpenWindowsAutomatically = p.javaScriptCanOpenWindowsAutomatically
    wv.settings.setGeolocationEnabled(p.geolocationEnabled)
    wv.settings.domStorageEnabled = true
    wv.settings.mediaPlaybackRequiresUserGesture = false
    wv.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
    android.webkit.CookieManager.getInstance().setAcceptCookie(p.acceptCookies)
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, p.acceptThirdPartyCookies)
}

/**
 * 加固后的 WebViewClient / WebChromeClient / DownloadListener。
 *
 * 为什么从 Activity 抽出来：原先它们是 WebHostActivity 的匿名内部类，
 * 无法在测试里实例化，于是安全行为只能靠"设备上点坐标 + 抓日志"间接验证 ——
 * 那套手段已多次给出自相矛盾的结论。抽成独立类后，设备测试可以直接
 * 构造它们、调用真实回调并断言结果，不依赖坐标与时序。
 *
 * 判断逻辑统一委托给 [WebSecurityPolicy]，这里只做"调用 + 转译"。
 */
class SafeWebViewClient(
    private val onSecurityEvent: (String) -> Unit,
    private val onPageTitle: (String) -> Unit,
    private val requestFilter: (String) -> Boolean,
) : WebViewClient() {

    @Volatile
    var filterEnabled: Boolean = true

    /** 页面加载失败时回调，便于把原因暴露到界面。 */
    var onLoadError: ((String) -> Unit)? = null

    /** 渲染进程崩溃时回调；宿主应销毁 WebView 而不是复用。 */
    var onRenderGone: (() -> Unit)? = null

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        shouldOverrideUrlLoading(view, request?.url?.toString().orEmpty())

    @Deprecated("兼容旧版 WebView：非 http(s) 的 scheme 只会走这个重载")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val target = url.orEmpty()
        if (WebSecurityPolicy.shouldBlockNavigation(target)) {
            onSecurityEvent("已阻止跳转：" + target.substringBefore("://"))
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
        if (filterEnabled && WebSecurityPolicy.shouldBlockRequest(url)) {
            onSecurityEvent("已拦截：" + Uri.parse(url).host)
            return emptyResponse()
        }
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageTitle(view?.title?.toString().orEmpty())
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onLoadError?.invoke(error?.description?.toString() ?: "未知错误")
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onRenderGone?.invoke()
        return true
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?,
    ) {
        if (WebSecurityPolicy.followSslError()) {
            handler?.proceed()
        } else {
            handler?.cancel()
            onSecurityEvent("证书校验失败，已停止加载")
        }
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        java.io.ByteArrayInputStream(ByteArray(0)),
    )
}

class SafeWebChromeClient(
    private val onSecurityEvent: (String) -> Unit,
) : WebChromeClient() {

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (WebSecurityPolicy.grantDevicePermission()) {
            request?.grant(request.resources)
        } else {
            request?.deny()
            onSecurityEvent("已拒绝页面的设备权限请求")
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: android.webkit.GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, WebSecurityPolicy.grantGeolocation(), false)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        if (WebSecurityPolicy.allowFileChooser()) return false
        filePathCallback?.onReceiveValue(null)
        onSecurityEvent("已阻止页面的文件选择请求")
        return true
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        if (WebSecurityPolicy.allowNewWindow()) return false
        onSecurityEvent("已阻止页面弹窗")
        return false
    }
}

class SafeDownloadListener(
    private val onSecurityEvent: (String) -> Unit,
) : DownloadListener {
    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        if (!WebSecurityPolicy.allowDownload()) onSecurityEvent("已阻止下载")
    }
}
