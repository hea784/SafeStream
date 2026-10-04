package com.heasafe.safestream.submit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索引擎可配置。
 *
 * 原来硬编码 Bing，而 Bing 在国内需要代理才能用；分发给别人装时这直接决定
 * "能不能用"。做成可配置，默认仍为 Bing 保持既有行为。
 */
class SearchEngineTest {

    @Test
    fun `各引擎拼出的查询地址可用`() {
        assertEquals(
            "https://www.bing.com/search?q=%E9%BB%84%E6%9E%9C",
            SearchEngine.BING.queryUrl("黄果"),
        )
        assertEquals(
            "https://www.baidu.com/s?wd=%E9%BB%84%E6%9E%9C",
            SearchEngine.BAIDU.queryUrl("黄果"),
        )
        assertEquals(
            "https://www.sogou.com/web?query=%E9%BB%84%E6%9E%9C",
            SearchEngine.SOGOU.queryUrl("黄果"),
        )
    }

    @Test
    fun `按名字取值可用且未知时退回默认`() {
        assertEquals(SearchEngine.BAIDU, SearchEngine.fromName("baidu"))
        assertEquals(SearchEngine.BING, SearchEngine.fromName("bing"))
        assertEquals(SearchEngine.BING, SearchEngine.fromName("不存在"))
        assertEquals(SearchEngine.BING, SearchEngine.fromName(null))
    }

    @Test
    fun `空关键词不产生查询地址`() {
        assertEquals("", SearchEngine.BING.queryUrl(""))
    }

    @Test
    fun `resolve 使用指定引擎而非写死的 Bing`() {
        val r = UrlSubmission.resolve("黄果短剧", SearchEngine.BAIDU)
        assertTrue(r is UrlSubmission.Outcome.SearchUrl)
        assertTrue((r as UrlSubmission.Outcome.SearchUrl).url.contains("baidu.com"))
    }
}
