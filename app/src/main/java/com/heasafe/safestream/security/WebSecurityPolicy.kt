package com.heasafe.safestream.security

import com.heasafe.safestream.core.TrackerBlocklist
import com.heasafe.safestream.core.UrlGuard

/**
 * 全部安全决策的唯一出处。
 *
 * 之前这些判断匿名内嵌在 Activity 的 WebViewClient / WebChromeClient 里，
 * 只能在设备上靠"截图点坐标 + 抓 logcat"间接验证 —— 那套手段不可信，
 * 出现过两次工具自相矛盾的结论。抽到这里之后是纯逻辑，可以在 JVM 上
 * 确定性断言，不依赖设备与坐标。
 *
 * 活动组件只负责"调用这里并把结果翻译成界面/日志"，不再自己判断。
 */
object WebSecurityPolicy {

    /** WebView 需要设置的加固项。集中在此，避免散落在 Activity 里被漏改。 */
    data class Settings(
        val allowFileAccess: Boolean,
        val allowContentAccess: Boolean,
        val allowFileAccessFromFileURLs: Boolean,
        val allowUniversalAccessFromFileURLs: Boolean,
        val javaScriptCanOpenWindowsAutomatically: Boolean,
        val supportMultipleWindows: Boolean,
        val geolocationEnabled: Boolean,
        val acceptCookies: Boolean,
        val acceptThirdPartyCookies: Boolean,
        val mixedContentNeverAllow: Boolean,
    )

    val settings = Settings(
        allowFileAccess = false,
        allowContentAccess = false,
        allowFileAccessFromFileURLs = false,
        allowUniversalAccessFromFileURLs = false,
        javaScriptCanOpenWindowsAutomatically = false,
        supportMultipleWindows = false,
        geolocationEnabled = false,
        acceptCookies = false,
        acceptThirdPartyCookies = false,
        mixedContentNeverAllow = true,
    )

    /** 只放行 http/https 与 about:blank，其余 scheme 全部拦下。 */
    fun shouldBlockNavigation(url: String): Boolean {
        if (url.isBlank()) return true
        if (url == "about:blank") return false
        return !UrlGuard.allowNavigation(url)
    }

    /** 广告、跟踪、挖矿、裸 IP 上报等。复用唯一一份黑名单判断。 */
    fun shouldBlockRequest(url: String): Boolean = TrackerBlocklist.isBlocked(url)

    /**
     * 明文请求是否应当拦下。
     *
     * 平台层 cleartextTrafficPermitted 必须为 true（否则对话框里点"仍要加载"
     * 也没用，页面全白），所以真正的闸门在这里：
     * 只有用户在警告框里确认过的那个 host 及其子域可以走明文。
     *
     * 注意这条在架构合并时曾被架空 —— 合并后所有 http 都放行了，
     * 页面跳转到未确认的明文 host 也不拦。现已收回并补测试。
     */
    fun shouldBlockCleartext(url: String, confirmedHost: String?): Boolean {
        if (!UrlGuard.isInsecure(url)) return false
        val host = confirmedHost?.takeIf { it.isNotBlank() } ?: return true
        val target = runCatching { java.net.URI(url).host }.getOrNull() ?: return true
        return !(target.equals(host, ignoreCase = true) ||
            target.endsWith(".$host", ignoreCase = true))
    }

    fun grantDevicePermission(): Boolean = false

    fun grantGeolocation(): Boolean = false

    fun allowNewWindow(): Boolean = false

    fun allowFileChooser(): Boolean = false

    fun allowDownload(): Boolean = false

    /** 证书错误一律中断，不给"继续"选项。 */
    fun followSslError(): Boolean = false
}
