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

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
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
            c.onPageStart = { injectScanner() }
            c.onPageDone = { injectScanner() }
            c.onRenderGone = {
                report("页面渲染进程已崩溃，正在重建网页")
                onFatal?.invoke("渲染进程崩溃")
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
        view.loadUrl(url)
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
        onSecurityEvent(message)
    }

    private fun injectScanner() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            report("当前 WebView 不支持安全消息通道，选集与网络发现不可用")
            return
        }
        view.evaluateJavascript(VideoScannerScript.SOURCE, null)
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
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when {
            root.has("url") && !root.has("batch") -> onNetworkHit(root)
            root.has("episodes") -> onEpisodes(root.optJSONArray("episodes"))
            else -> onBatch(root.optJSONArray("batch"))
        }
    }

    private fun onBatch(items: JSONArray?) {
        if (items == null || items.length() == 0) return
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!UrlGuard.allowNavigation(url)) continue
            out.put(
                JSONObject().apply {
                    put("url", url)
                    put("title", o.optString("title"))
                    put("mimeType", o.optString("mimeType"))
                    put("durationMs", o.optLong("durationMs"))
                    put("sourcePage", o.optString("sourcePage"))
                },
            )
        }
        if (out.length() > 0) onVideosFound(out.toString())
    }

    private fun onEpisodes(items: JSONArray?) {
        if (items == null || items.length() == 0) return
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!UrlGuard.allowNavigation(url)) continue
            out.put(
                JSONObject().apply {
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
        if (out.length() > 0) onEpisodesFound(out.toString())
    }

    private fun onNetworkHit(o: JSONObject) {
        val url = o.optString("url")
        val kind = o.optString("kind")
        if (kind == "mse" || url.startsWith("mse:")) return

        // 用户点了 blob 流：播本页已发现的媒体。地址本身在页面外没有意义，
        // 但点击已经表达了播放意图。
        if (kind == "play-found") {
            onVideosFound(PLAY_FOUND_MARKER)
            return
        }
        if (!UrlGuard.allowNavigation(url)) return
        onBatch(JSONArray().put(JSONObject().apply {
            put("url", url)
            put("title", "")
            put("mimeType", "")
            put("durationMs", 0)
            put("sourcePage", o.optString("page"))
        }))
    }

    companion object {
        const val TAG = "SafeStreamSecurity"

        /** 表示"播放本页已发现媒体"的哨兵值，走内部通道不占用 JSON 载荷。 */
        const val PLAY_FOUND_MARKER = "__play_found__"
    }
}
