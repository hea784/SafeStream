package com.heasafe.safestream.sandbox

import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 线程回归测试。
 *
 * 背景：真机（WebView 151）崩溃现场显示
 * `ViewRootImpl$CalledFromWrongThreadException`，堆栈里有
 * `shouldInterceptRequest -> ... -> TextView.setText`。
 * 根因是 shouldInterceptRequest 跑在 Chromium 的网络子线程上，
 * 回调里直接改 View。模拟器（WebView 124）不崩，只有真机复现，
 * 所以这条只能靠"真的从后台线程调一次"来守住。
 */
@RunWith(AndroidJUnit4::class)
class WebSandboxThreadingTest {

    @Test
    fun 从后台线程触发拦截时回调必须回到主线程() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val threads = mutableListOf<String>()
        val latch = CountDownLatch(1)

        lateinit var sandbox: WebSandbox
        lateinit var client: android.webkit.WebViewClient
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sandbox = WebSandbox(
                context = ctx,
                onVideosFound = { },
                onEpisodesFound = { },
                onSecurityEvent = {
                    threads.add(Thread.currentThread().name)
                    latch.countDown()
                },
                onTitle = { },
            )
            // WebView 的方法只能在创建它的线程上调用，取引用也要在主线程
            client = sandbox.view.webViewClient
        }

        val request = FakeRequest("https://www.google-analytics.com/g/collect?v=2")

        // 关键：明确从后台线程调用，模拟 Chromium 网络线程
        val t = Thread {
            // shouldInterceptRequest 内部只用 request 里的 url，不碰 WebView 实例
            client.shouldInterceptRequest(null, request)
        }
        t.start()
        t.join()

        assertTrue("拦截回调应被触发", latch.await(5, TimeUnit.SECONDS))
        assertEquals(
            "回调必须在主线程，否则碰 View 就崩",
            Looper.getMainLooper().thread.name,
            threads.first(),
        )
    }

    /** 最小 WebResourceRequest 替身。 */
    private class FakeRequest(private val url: String) : WebResourceRequest {
        override fun getUrl() = android.net.Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
