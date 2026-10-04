package com.heasafe.safestream.store

import com.heasafe.safestream.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表的合并与选择。

 * 这段逻辑原先在 MainActivity 的 onVideosFound / onEpisodesFound 里。
 * 抽出来之后才能确定验证两件事：
 *  - 扫描脚本会反复上报同一批视频，整体替换会导致列表不停闪
 *  - 选集条目要排在媒体条目前面（用户想选的是"第几集"）
 */
class PlaylistStoreTest {

    private fun media(url: String) = VideoItem(url = url, title = "m")

    private fun episode(url: String, no: Int) =
        VideoItem(url = url, title = "第 $no 集", kind = VideoItem.Kind.EPISODE, episodeNo = no)

    @Test
    fun `重复上报同一批视频不产生变化`() {
        val store = PlaylistStore()
        val a = store.mergeVideos(emptyList(), listOf(media("m3u8://a"), media("m3u8://b")))
        assertEquals(2, a.size)
        val b = store.mergeVideos(a, listOf(media("m3u8://a"), media("m3u8://b")))
        assertEquals("重复上报应保持原样", a, b)
    }

    @Test
    fun `新增视频追加而不打乱既有顺序`() {
        val store = PlaylistStore()
        val first = store.mergeVideos(emptyList(), listOf(media("a"), media("b")))
        val second = store.mergeVideos(first, listOf(media("b"), media("c")))
        assertEquals(listOf("a", "b", "c"), second.map { it.url })
    }

    @Test
    fun `选集排在媒体之前`() {
        val store = PlaylistStore()
        val withMedia = store.mergeVideos(emptyList(), listOf(media("m3u8://a")))
        val withEp = store.mergeEpisodes(withMedia, listOf(episode("p/1", 1), episode("p/2", 2)))
        assertEquals(listOf("p/1", "p/2", "m3u8://a"), withEp.map { it.url })
    }

    @Test
    fun `同地址重复上报选集只保留一条`() {
        val store = PlaylistStore()
        val eps = listOf(episode("p/1", 1), episode("p/2", 2))
        val once = store.mergeEpisodes(emptyList(), eps)
        val twice = store.mergeEpisodes(once, eps)
        assertEquals(2, twice.size)
        assertEquals(once, twice)
    }

    @Test
    fun `媒体到达时不得抹掉本页已上报的选集`() {
        // 真机崩溃前的实测：切到第 2 集后选集整个消失，点不到第 3 集。
        // 原因就是 mergeVideos 把选集过滤掉了 —— 而"换页要清旧选集"
        // 这件事 loadUrl 已经做过一次，不需要在合并时再做一遍。
        val store = PlaylistStore()
        store.mergeEpisodes(emptyList(), listOf(episode("p/1", 1), episode("p/2", 2)))
        val afterMedia = store.mergeVideos(store.state, listOf(media("m3u8://ep2")))
        assertEquals(
            "选集必须保留，用户才能继续点下一集",
            listOf("p/1", "p/2", "m3u8://ep2"),
            afterMedia.map { it.url },
        )
    }

    @Test
    fun `挑第一个可播媒体时跳过选集条目`() {
        val store = PlaylistStore()
        val list = store.mergeEpisodes(
            store.mergeVideos(emptyList(), listOf(media("m3u8://a"))),
            listOf(episode("p/1", 1)),
        )
        assertEquals("m3u8://a", store.firstPlayable(list)?.url)
    }

    @Test
    fun `只有选集没有媒体时挑不出可播项`() {
        val store = PlaylistStore()
        val list = store.mergeEpisodes(emptyList(), listOf(episode("p/1", 1)))
        assertNull(store.firstPlayable(list))
    }

    @Test
    fun `统计可播条目时排除 blob 流`() {
        val blob = VideoItem(url = "blob:https://x/y", title = "b")
        val list = listOf(episode("p/1", 1), media("m3u8://a"), blob)
        // 选集条目是可播的（一集就是一条视频），blob 流不是。
        // 一部 5 集的剧，标题就该显示"发现 5 个视频"。
        assertEquals("选集与媒体都算，blob 不算", 2, PlaylistStore.playableCount(list))
    }

    // 真机实测：站点播放器写"全集 3 集"，App 却显示"5 个视频"。
    // 原因是选集条目和媒体地址被混在一起计数 —— 3 集 + 2 个抓到的地址 = 5。

    @Test
    fun `有选集时只展示选集不混入媒体地址`() {
        val store = PlaylistStore()
        val merged = store.mergeEpisodes(
            store.mergeVideos(emptyList(), listOf(media("m3u8://a"), media("m3u8://b"))),
            listOf(episode("p/1", 1), episode("p/2", 2), episode("p/3", 3)),
        )
        val visible = PlaylistStore.visibleItems(merged)
        assertEquals("连续剧只该显示 3 集", 3, visible.size)
        assertTrue("应全是选集", visible.all { it.isEpisode })
    }

    @Test
    fun `没有选集时才展示媒体`() {
        val store = PlaylistStore()
        val onlyMedia = store.mergeVideos(emptyList(), listOf(media("m3u8://a"), media("m3u8://b")))
        val visible = PlaylistStore.visibleItems(onlyMedia)
        assertEquals(2, visible.size)
        assertTrue(visible.none { it.isEpisode })
    }

    @Test
    fun `对用户展示的数量以可见条目为准`() {
        val store = PlaylistStore()
        val merged = store.mergeEpisodes(
            store.mergeVideos(emptyList(), listOf(media("m3u8://a"))),
            listOf(episode("p/1", 1), episode("p/2", 2)),
        )
        assertEquals("用户看到 2 集而不是 3 条", 2, PlaylistStore.visibleCount(merged))
    }

    @Test
    fun `连播 - 返回当前集的下一集`() {
        val eps = listOf(
            episode("p/1", 1),
            episode("p/2", 2),
            episode("p/3", 3),
            media("m3u8://ep2"), // 混在列表里的媒体地址不参与连播序列
        )
        val next = PlaylistStore.nextEpisode(eps, "p/2")
        assertEquals("p/3", next?.url)
        assertEquals(3, next?.episodeNo)
    }

    @Test
    fun `连播 - 已是最后一集时返回 null`() {
        val eps = listOf(episode("p/1", 1), episode("p/2", 2))
        assertNull(PlaylistStore.nextEpisode(eps, "p/2"))
    }

    @Test
    fun `连播 - 当前不在选集序列里时返回 null`() {
        val eps = listOf(episode("p/1", 1), episode("p/2", 2))
        assertNull("媒体地址不该匹配到任何一集", PlaylistStore.nextEpisode(eps, "m3u8://x"))
        assertNull(PlaylistStore.nextEpisode(eps, ""))
    }
}
