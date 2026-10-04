package com.heasafe.safestream.submit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址提交决策。
 *
 * 原来这段逻辑散在 MainActivity 的 submitUrl/looksLikeKeyword 里，
 * 无法单独测试；抽出来之后"什么输入会被当成网址、什么会被当成搜索词、
 * 重复提交怎么防抖"都能在 JVM 上确定验证。
 */
class UrlSubmissionTest {

    @Test
    fun `带 scheme 的按网址处理`() {
        val r = UrlSubmission.resolve("https://example.com/a")
        assertTrue(r is UrlSubmission.Outcome.LoadUrl)
        assertEquals("https://example.com/a", (r as UrlSubmission.Outcome.LoadUrl).url)
    }

    @Test
    fun `裸域名补上 https`() {
        val r = UrlSubmission.resolve("example.com/video/1")
        assertEquals("https://example.com/video/1", (r as UrlSubmission.Outcome.LoadUrl).url)
        assertEquals(null, r.insecureHost)
    }

    @Test
    fun `明文网址需要用户确认并带出 host`() {
        val r = UrlSubmission.resolve("http://example.com/a") as UrlSubmission.Outcome.LoadUrl
        assertTrue("需要用户确认", r.needsInsecureConfirm)
        assertEquals("example.com", r.insecureHost)
    }

    @Test
    fun `带空格的按搜索词处理`() {
        val r = UrlSubmission.resolve("黄果短剧 在线看") as UrlSubmission.Outcome.SearchUrl
        assertTrue(r.url.contains("bing.com/search"))
        assertTrue(r.url.contains("%"))
    }

    @Test
    fun `没有点号没有空格的短词按搜索词处理`() {
        val r = UrlSubmission.resolve("shortdrama") as UrlSubmission.Outcome.SearchUrl
        assertTrue(r.url.contains("shortdrama"))
    }

    @Test
    fun `空输入与危险协议被拒绝`() {
        assertTrue(UrlSubmission.resolve("") is UrlSubmission.Outcome.Reject)
        assertTrue(UrlSubmission.resolve("   ") is UrlSubmission.Outcome.Reject)
        val bad = UrlSubmission.resolve("file:///sdcard/x") as UrlSubmission.Outcome.Reject
        assertTrue(bad.reason.isNotBlank())
    }

    // ---- 防抖：同一地址短时间重复提交会清空播放列表，必须挡掉 ----

    @Test
    fun `短时间重复提交同一地址被忽略`() {
        val gate = UrlSubmission()
        val first = gate.tryAcquire("https://a.com/x", 1000L)
        assertTrue("首次应放行", first)
        assertTrue("1 秒内重复应忽略", !gate.tryAcquire("https://a.com/x", 1500L))
    }

    @Test
    fun `超过防抖窗口后允许再次提交`() {
        val gate = UrlSubmission()
        assertTrue(gate.tryAcquire("https://a.com/x", 0L))
        assertTrue(gate.tryAcquire("https://a.com/x", 99_000L))
    }

    @Test
    fun `不同地址之间互不影响`() {
        val gate = UrlSubmission()
        assertTrue(gate.tryAcquire("https://a.com/x", 0L))
        assertTrue(gate.tryAcquire("https://b.com/y", 10L))
    }
}
