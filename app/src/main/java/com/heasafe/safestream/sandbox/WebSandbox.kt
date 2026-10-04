package com.heasafe.safestream.sandbox

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.heasafe.safestream.core.UrlGuard
import com.heasafe.safestream.security.SafeDownloadListener
import com.heasafe.safestream.security.SafeWebChromeClient
import com.heasafe.safestream.security.SafeWebViewClient
import com.heasafe.safestream.security.applyHardening
import org.json.JSONArray
import org.json.JSONObject

/**
 * 受限网页环境的所有权。
 *
 * 从前是一个独立 Activity（还跑在 :sandbox 进程），靠广播与主界面通信。
 * 现在是普通对象，宿主直接把它的 view 塞进布局，回调直接调方法 ——
 * 于是那套 Intent 广播协议、进程间编解码、两份重复的界面外壳全部消失。
 *
 * 关于进程隔离：WebView 的渲染进程本来就是独立的（有 Chromium 自己的沙箱），
 * `android:process=":sandbox"` 只是把浏览器侧也挪走，换来崩溃与内存压力隔离。
 * 合并后用 [onRenderProcessGone] 兜住渲染进程崩溃，保住播放器和选集状态。
 */
class WebSandbox(
    context: Context,
    private val onVideosFound: (String) -> Unit,
    private val onEpisodesFound: (String) -> Unit,
    private val onSecurityEvent: (String) -> Unit,
    private val onTitle: (String) -> Unit,
) {

    /** 渲染进程崩溃等致命情况：宿主应重建 WebView 而不是留着半死状态。 */
    var onFatal: ((String) -> Unit)? = null

    var filterEnabled: Boolean = true

    private var insecureAllowedHost: String? = null

    /**
     * 页面声明的 DRM 系统（如 com.widevine.alpha）。
     * 非空说明这站内容加密，原生播放器拿不到密钥，播不了 ——
     * 这时要明确告诉用户，而不是让它停在黑屏上转圈。
     */
    var drmSystem: String? = null
        private set

    /**
     * WebView 的回调不一定在主线程。
     *
     * 真机（WebView 151）实测：`shouldInterceptRequest` 跑在 Chromium 的网络
     * 子线程上，回调里直接改 TextView 会抛
     * `ViewRootImpl$CalledFromWrongThreadException`。模拟器是 WebView 124，
     * 同样代码不崩 —— 只在真机复现，根因就是跨线程碰 View。
     *
     * 因此统一把回调切回主线程，调用方可以假定自己在主线程。
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else main.post(block)
    }

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        // 与 bg_root 一致：空状态插画按这个底色羽化融合，纯黑会出现可见的方形边界
        setBackgroundColor(android.graphics.Color.parseColor("#0C0F14"))
        applyHardening(this)
        settings.loadsImagesAutomatically = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webViewClient = SafeWebViewClient(
            onSecurityEvent = ::report,
            onPageTitle = onTitle,
            requestFilter = { true },
        ).also { c ->
            c.filterEnabled = filterEnabled
            c.insecureHostConfirmed = insecureAllowedHost
        c.onLoadError = { detail -> report("加载失败：$detail") }
        c.onPageStart = { onMain { injectScanner() } }
        c.onPageDone = { onMain { injectScanner() } }
        c.onRenderGone = {
            onMain {
                report("页面渲染进程已崩溃，正在重建网页")
                onFatal?.invoke("渲染进程崩溃")
            }
        }
        }
        webChromeClient = SafeWebChromeClient(onSecurityEvent = ::report)
        setDownloadListener(SafeDownloadListener(onSecurityEvent = ::report))
        attachMessageBridge(this)
    }

    /** 加载一个地址。传入空串表示"重新加载当前地址"。 */
    fun load(url: String, insecureHostAllowed: String?) {
        if (url.isBlank()) {
            view.reload()
            return
        }
        insecureAllowedHost = insecureHostAllowed
        (view.webViewClient as? SafeWebViewClient)?.insecureHostConfirmed = insecureHostAllowed
        val allowed = com.heasafe.safestream.core.UrlGuard.allowNavigation(url)
        if (!allowed) {
            report("已阻止跳转：" + url.substringBefore("://"))
            return
        }
        // 分享链接常带 utm_* 归因参数，进沙箱前剥掉（仅页面地址，媒体不动）
        val cleaned = com.heasafe.safestream.core.UrlCleaner.stripTrackingParams(url)
        if (cleaned != null) report("已剥离跟踪参数")
        view.loadUrl(cleaned ?: url)
        // App 自己驱动的换页（提交网址、切集）不算"历史"：清掉 WebView 回退栈，
        // 否则按返回键会被 WebView 吞掉、回不到上一集也退不出应用。
        // 用户在网页里点击产生的导航记录保留，行为与浏览器一致。
        runCatching { view.clearHistory() }
    }

    /** 停止加载并擦除站点数据。 */
    fun purge() {
        runCatching {
            view.stopLoading()
            view.loadUrl("about:blank")
        }
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        runCatching {
            view.clearCache(true)
            view.clearHistory()
            view.clearFormData()
        }
    }

    fun destroy() {
        runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        onMain { onSecurityEvent(message) }
    }

    private fun injectScanner() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            report("当前 WebView 不支持安全消息通道，选集与网络发现不可用")
            return
        }
        view.evaluateJavascript(VideoScannerScript.source(view.context), null)
    }

    /**
     * 页面 -> 原生的唯一通道。
     *
     * 绝不使用 addJavascriptInterface：那会把原生对象直接暴露给任意页面脚本。
     * addWebMessageListener 带 origin 白名单，且不暴露任何原生方法。
     */
    private fun attachMessageBridge(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            report("当前 WebView 不支持安全消息通道，选集与网络发现不可用")
            return
        }
        val listener = object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(
                view: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                replyProxy: JavaScriptReplyProxy,
            ) {
                // 不要用 message.type 判断：框架 WebMessage.TYPE_STRING 是 0，
                // androidx 的 WebMessageCompat.TYPE_STRING 是 1（它给类型加了偏移）。
                // 在 WebView 124 上按常量判断会把每一条消息都丢掉，表现为静默无产出。
                val payload = message.data ?: return
                if (payload.isNotBlank()) dispatch(payload)
            }
        }
        WebViewCompat.addWebMessageListener(
            wv,
            VideoScannerScript.MESSAGE_NAME,
            setOf("*"),
            listener,
        )
    }

    private fun dispatch(payload: String) {
        // 解析规则在 ScannerMessageParser（纯逻辑，10 个 JVM 用例覆盖每种消息形态）
        when (val msg = parser.parse(payload)) {
            is ScanMessage.Videos -> onMain { onVideosFound(msg.json) }
            is ScanMessage.Episodes -> onMain { onEpisodesFound(msg.json) }
            is ScanMessage.Drm -> {
                drmSystem = msg.system
                if (msg.system != null) {
                    report("检测到加密视频（${msg.system}），这类内容无法播放")
                }
            }
            ScanMessage.PlayFound -> onMain { onVideosFound(PLAY_FOUND_MARKER) }
            ScanMessage.Ignore -> Unit
        }
    }

    private val parser = ScannerMessageParser()

    companion object {
        const val TAG = "SafeStreamSecurity"

        /** 表示"播放本页已发现媒体"的哨兵值，走内部通道不占用 JSON 载荷。 */
        const val PLAY_FOUND_MARKER = "__play_found__"
    }
}
