package com.heasafe.safestream.sandbox

import com.heasafe.safestream.model.VideoItem
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描消息解析：页面脚本的 JSON -> 应用内消息。
 *
 * 这层原先匿名内嵌在 WebSandbox.dispatch 里，只能靠"装设备 + 起夹具 +
 * 抓 UI"间接验证——而那套手段多次给出自相矛盾的结论。抽成纯逻辑之后，
 * 每种消息形态都能在 JVM 上确定性断言。
 *
 * 需要 org.json 的真实实现：android.jar 里的只是空壳。
 */
class ScannerMessageParserTest {

    private fun parse(payload: String) = ScannerMessageParser().parse(payload)

    private fun videos(json: String): List<VideoItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { VideoItem.fromJson(arr.optString(it))!! }
    }

    @Test
    fun `批量媒体保留全部字段`() {
        val msg = parse(
            """{"batch":[{"url":"https://a.com/v.mp4","title":"片头","mimeType":"video/mp4","durationMs":6000,"sourcePage":"https://a.com/p"}]}""",
        )
        assertTrue(msg is ScanMessage.Videos)
        val item = videos((msg as ScanMessage.Videos).json).single()
        assertEquals("https://a.com/v.mp4", item.url)
        assertEquals("片头", item.title)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(6000L, item.durationMs)
    }

    @Test
    fun `不允许导航的地址被剔除`() {
        val msg = parse(
            """{"batch":[{"url":"javascript:alert(1)"},{"url":"https://a.com/ok.m3u8"}]}""",
        )
        assertTrue(msg is ScanMessage.Videos)
        assertEquals(listOf("https://a.com/ok.m3u8"), videos((msg as ScanMessage.Videos).json).map { it.url })
    }

    @Test
    fun `整批被剔除后不产生消息`() {
        val msg = parse("""{"batch":[{"url":"javascript:alert(1)"}]}""")
        assertEquals(ScanMessage.Ignore, msg)
    }

    @Test
    fun `选集消息映射为 EPISODE 并带集号`() {
        // 扫描脚本在页面里已用 new URL(url, location.href) 解析成绝对地址才上报
        val msg = parse(
            """{"episodes":[{"url":"https://a.com/video/1/ep-3/","ep":3,"title":"03","page":"https://a.com/video/1/"}]}""",
        )
        assertTrue(msg is ScanMessage.Episodes)
        val item = videos((msg as ScanMessage.Episodes).json).single()
        assertTrue(item.isEpisode)
        assertEquals(3, item.episodeNo)
        assertEquals("03", item.title)
    }

    @Test
    fun `DRM 声明与无 DRM`() {
        assertEquals(
            ScanMessage.Drm("com.widevine.alpha"),
            parse("""{"url":"drm:com.widevine.alpha","kind":"drm"}"""),
        )
        assertEquals(
            ScanMessage.Drm(null),
            parse("""{"url":"","kind":"nodrm"}"""),
        )
    }

    @Test
    fun `网络层命中单条地址归入媒体批次`() {
        val msg = parse("""{"url":"https://cdn.com/a.m3u8","kind":"fetch","page":"https://a.com/p"}""")
        assertTrue(msg is ScanMessage.Videos)
        assertEquals("https://cdn.com/a.m3u8", videos((msg as ScanMessage.Videos).json).single().url)
    }

    @Test
    fun `mse 类型声明不入库`() {
        assertEquals(ScanMessage.Ignore, parse("""{"url":"mse:video/mp4","kind":"mse"}"""))
    }

    @Test
    fun `用户点击 blob 流转为播放哨兵`() {
        assertEquals(
            ScanMessage.PlayFound,
            parse("""{"url":"","kind":"play-found","page":"https://a.com/p"}"""),
        )
    }

    @Test
    fun `坏 JSON 与空载荷静默忽略`() {
        assertEquals(ScanMessage.Ignore, parse("not json at all"))
        assertEquals(ScanMessage.Ignore, parse(""))
        assertEquals(ScanMessage.Ignore, parse("""{"batch":[]}"""))
    }

    @Test
    fun `无 DRM 消息后系统字段为 null`() {
        val msg = parse("""{"url":"","kind":"nodrm"}""")
        assertNull((msg as ScanMessage.Drm).system)
    }
}
