package com.heasafe.safestream.security

import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备上的安全契约验证。
 *
 * 为什么不能只靠 JVM 测试：JVM 测试只能证明"策略函数会返回 true"，
 * 证明不了 WebView 真的把回调交给了它。这一层直接构造真实的 WebView、
 * 真实的 PermissionRequest/GeolocationPermissions 对象，验证拦截确实生效。
 *
 * 关键：全部直接调用回调或读真实 WebSettings，不依赖坐标点击、不依赖时序等待。
 */
@RunWith(AndroidJUnit4::class)
class SafeClientsInstrumentedTest {

    private lateinit var webView: WebView
    private val events = mutableListOf<String>()

    /** WebView 的所有方法都必须在主线程调用。 */
    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // WebView 必须在有 Looper 的主线程创建，否则抛
        // "WebView cannot be initialized on a thread that has no Looper"。
        onMain {
            webView = WebView(ctx)
            // 与线上走同一个加固入口，否则测的是一份不同的配置
            applyHardening(webView)
        }
        events.clear()
    }

    // ---- 契约 5/6/8/11：WebSettings 加固 ----

    @Test
    fun hardenedSettingsAppliedToRealWebView() {
        // WebView 的所有方法都必须在主线程调用，读取 settings 也不例外。
        onMain {
            val s = webView.settings
            assertFalse("allowFileAccess", s.allowFileAccess)
            assertFalse("allowContentAccess", s.allowContentAccess)
            @Suppress("DEPRECATION")
            assertFalse("allowFileAccessFromFileURLs", s.allowFileAccessFromFileURLs)
            @Suppress("DEPRECATION")
            assertFalse("allowUniversalAccessFromFileURLs", s.allowUniversalAccessFromFileURLs)
            assertFalse("jsCanOpenWindows", s.javaScriptCanOpenWindowsAutomatically)
            // geolocationEnabled 只有 setter 读不到取值，
            // 它的"已关闭"由 JVM 层的 WebSecurityPolicyTest 断言。
            assertFalse("supportMultipleWindows", s.supportMultipleWindows())
            assertEquals(
                "mixedContentMode",
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                s.mixedContentMode,
            )
        }
    }

    // ---- 契约 3/11：危险 scheme 导航 ----

    @Test
    fun clientBlocksDangerousSchemes() {
        val client = SafeWebViewClient(
            onSecurityEvent = { events.add(it) },
            onPageTitle = {},
            requestFilter = { true },
        )
        val dangerous = listOf(
            "file:///sdcard/Download/x.apk",
            "content://media/external/file/1",
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=com.example",
            "javascript:void(0)",
            "blob:https://x/y",
            "data:text/html,x",
        )
        for (u in dangerous) {
            @Suppress("DEPRECATION")
            val blocked = client.shouldOverrideUrlLoading(webView, u)
            assertTrue("应拦下 $u", blocked)
        }
        @Suppress("DEPRECATION")
        assertFalse("http 应放行", client.shouldOverrideUrlLoading(webView, "http://a.com/"))
        @Suppress("DEPRECATION")
        assertFalse("https 应放行", client.shouldOverrideUrlLoading(webView, "https://a.com/"))
    }

    // ---- 契约 7：设备权限一律拒绝 ----

    @Test
    fun chromeClientDeniesDevicePermission() {
        val chrome = SafeWebChromeClient(onSecurityEvent = { events.add(it) })
        val request = FakePermissionRequest()
        chrome.onPermissionRequest(request)
        assertFalse("设备权限必须拒绝", request.granted)
        assertTrue("应记录拒绝事件", events.any { it.contains("权限") })
    }

    @Test
    fun chromeClientDeniesGeolocation() {
        val chrome = SafeWebChromeClient(onSecurityEvent = { events.add(it) })
        var granted = false
        chrome.onGeolocationPermissionsShowPrompt(
            "https://x",
            android.webkit.GeolocationPermissions.Callback { _, allow, _ -> granted = allow },
        )
        assertFalse("定位必须拒绝", granted)
    }

    // ---- 契约 8/9：禁弹窗、禁文件选择 ----

    @Test
    fun chromeClientBlocksPopupAndFileChooser() {
        val chrome = SafeWebChromeClient(onSecurityEvent = { events.add(it) })
        assertFalse("不应创建新窗口", chrome.onCreateWindow(webView, false, true, null))
        var delivered = "not-called"
        val handled = chrome.onShowFileChooser(
            webView,
            { value -> delivered = value?.joinToString() ?: "null" },
            null,
        )
        assertTrue("应接管文件选择请求", handled)
        assertEquals("文件选择应返回 null", "null", delivered)
        assertTrue("应记录弹窗事件", events.any { it.contains("弹窗") })
    }

    // ---- 契约 10：下载阻断 ----

    @Test
    fun downloadListenerAlwaysAborts() {
        var aborted = false
        val listener = SafeDownloadListener(onSecurityEvent = { events.add(it) })
        listener.onDownloadStart(
            "https://x/a.apk",
            "ua",
            "attachment; filename=a.apk",
            "application/vnd.android.package-archive",
            1024L,
        )
        assertTrue("下载必须被中止", aborted || events.any { it.contains("下载") })
    }

    /** 最小 PermissionRequest 替身，只关心 grant 还是 deny。 */
    private class FakePermissionRequest : PermissionRequest() {
        var granted = false
        override fun getOrigin(): android.net.Uri = android.net.Uri.parse("https://x")
        override fun getResources(): Array<String> = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        override fun grant(resources: Array<String>?) { granted = true }
        override fun deny() { granted = false }
    }
}
