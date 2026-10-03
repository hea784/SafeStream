package com.heasafe.safestream.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全策略契约测试。
 *
 * 为什么要抽这个类：原来的安全判断匿名内嵌在 Activity 的
 * WebViewClient / WebChromeClient 里，测不了 —— 只能靠"截图点坐标 + 抓 logcat"
 * 这种易碎手段。抽成纯逻辑类后，这些判断能在 JVM 上确定性验证，
 * 不依赖设备、不依赖坐标、不依赖时序。
 */
class WebSecurityPolicyTest {

    private val dangerous = listOf(
        "file:///sdcard/Download/x.apk",
        "content://media/external/file/1",
        "intent://scan/#Intent;scheme=zxing;end",
        "market://details?id=com.example",
        "javascript:void(0)",
        "blob:https://x/y",
        "data:text/html,x",
        "jar:http://x!/a",
        "android.resource://x",
    )

    @Test
    fun `危险 scheme 一律拦下`() {
        for (u in dangerous) {
            assertTrue("应拦下 " + u, WebSecurityPolicy.shouldBlockNavigation(u))
        }
    }

    @Test
    fun `http 与 https 正常放行`() {
        assertFalse(WebSecurityPolicy.shouldBlockNavigation("http://example.com/a"))
        assertFalse(WebSecurityPolicy.shouldBlockNavigation("https://example.com/a"))
        assertFalse(WebSecurityPolicy.shouldBlockNavigation("about:blank"))
    }

    @Test
    fun `网页能力请求全部拒绝`() {
        assertFalse(WebSecurityPolicy.grantDevicePermission())
        assertFalse(WebSecurityPolicy.grantGeolocation())
        assertFalse(WebSecurityPolicy.allowNewWindow())
        assertFalse(WebSecurityPolicy.allowFileChooser())
        assertFalse(WebSecurityPolicy.allowDownload())
        assertFalse(WebSecurityPolicy.followSslError())
    }

    @Test
    fun `WebSettings 加固项全部为关闭`() {
        val s = WebSecurityPolicy.settings
        assertFalse("allowFileAccess", s.allowFileAccess)
        assertFalse("allowContentAccess", s.allowContentAccess)
        assertFalse("allowFileAccessFromFileURLs", s.allowFileAccessFromFileURLs)
        assertFalse("allowUniversalAccessFromFileURLs", s.allowUniversalAccessFromFileURLs)
        assertFalse("jsCanOpenWindows", s.javaScriptCanOpenWindowsAutomatically)
        assertFalse("supportMultipleWindows", s.supportMultipleWindows)
        assertFalse("geolocationEnabled", s.geolocationEnabled)
        assertFalse("thirdPartyCookies", s.acceptThirdPartyCookies)
        assertFalse("cookies", s.acceptCookies)
        assertTrue("mixedContentNeverAllow", s.mixedContentNeverAllow)
    }

    @Test
    fun `广告与跟踪请求走同一个入口且不误伤视频`() {
        val blocked = listOf(
            "https://54.169.219.76:15212/api/eventTracking/batchReport.json",
            "https://api-ad1.adsdk1.cc/api/sdk/ad/request",
            "https://www.google-analytics.com/g/collect?v=2",
        )
        for (u in blocked) {
            assertTrue("应拦截 " + u, WebSecurityPolicy.shouldBlockRequest(u))
        }
        val m3u8 = "https://yd-hls.tktjpm.cn/videos5/a/a.m3u8"
        assertFalse("视频清单不能被拦", WebSecurityPolicy.shouldBlockRequest(m3u8))
    }

    // 合并架构时这条被架空了：isInsecureAllowed() 变成没有调用方的死代码，
    // 结果"只放行用户确认过的明文 host"这条规则整体失效。
    @Test
    fun `未确认的明文 host 被拦下`() {
        val confirmed = "example.com"
        assertTrue(
            "未确认的 http 必须拦",
            WebSecurityPolicy.shouldBlockCleartext("http://evil.test/x.js", confirmed),
        )
    }

    @Test
    fun `用户确认过的 host 与其子域放行明文`() {
        val confirmed = "example.com"
        assertFalse(
            WebSecurityPolicy.shouldBlockCleartext("http://example.com/x.js", confirmed),
        )
        assertFalse(
            WebSecurityPolicy.shouldBlockCleartext("http://cdn.example.com/x.js", confirmed),
        )
    }

    @Test
    fun `没有任何确认时明文一律被拦`() {
        assertTrue(WebSecurityPolicy.shouldBlockCleartext("http://any.test/x", null))
        assertFalse(WebSecurityPolicy.shouldBlockCleartext("https://any.test/x", null))
    }
}
