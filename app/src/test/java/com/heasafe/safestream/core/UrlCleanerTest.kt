package com.heasafe.safestream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 页面导航地址的跟踪参数剥离。
 *
 * 借鉴 Firefox ETP / Brave / iOS Link Tracking Protection 的公共行为：
 * utm_* / fbclid / gclid 这类纯归因参数对页面功能毫无作用，剥掉即可。
 *
 * 关键约束：**绝不能碰签名/时效参数**（auth_key、token、sign 等）——
 * 视频清单地址靠它们防盜链，误剥直接播不出来。所以清单是精确
 * 白名单 + utm_ 前缀，不做模糊匹配。
 */
class UrlCleanerTest {

    @Test
    fun `剥离 utm 参数并保留其余参数`() {
        val out = UrlCleaner.stripTrackingParams(
            "https://a.com/video?id=7&utm_source=app&utm_medium=x&from=list",
        )
        assertEquals("https://a.com/video?id=7&from=list", out)
    }

    @Test
    fun `剥离常见点击 id 与营销参数`() {
        val out = UrlCleaner.stripTrackingParams(
            "https://a.com/p?fbclid=abc&gclid=def&msclkid=1&igshid=2&spm_id_from=3&q=词",
        )
        assertEquals("https://a.com/p?q=词", out)
    }

    @Test
    fun `没有跟踪参数时返回 null 表示无需重写`() {
        assertNull(UrlCleaner.stripTrackingParams("https://a.com/video?id=7"))
        assertNull(UrlCleaner.stripTrackingParams("https://a.com/video"))
        assertNull(UrlCleaner.stripTrackingParams("https://a.com/a?b=c"))
    }

    @Test
    fun `签名与时效参数绝不能剥`() {
        val out = UrlCleaner.stripTrackingParams(
            "https://cdn.com/v.m3u8?auth_key=123-0-token&utm_source=app",
        )
        assertEquals("auth_key 必须保留，否则视频播不出来", "https://cdn.com/v.m3u8?auth_key=123-0-token", out)
    }

    @Test
    fun `fragment 保留且跟踪参数只在查询串里剥`() {
        val out = UrlCleaner.stripTrackingParams("https://a.com/p?utm_source=x#title")
        assertEquals("https://a.com/p#title", out)
    }

    @Test
    fun `非 http 地址不处理`() {
        assertNull(UrlCleaner.stripTrackingParams("intent://x#Intent;scheme=http;end"))
        assertNull(UrlCleaner.stripTrackingParams("about:blank?utm_source=1"))
    }

    @Test
    fun `剥离后参数清空则连问号一起去掉`() {
        val out = UrlCleaner.stripTrackingParams("https://a.com/p?fbclid=abc")
        assertEquals("https://a.com/p", out)
    }
}
