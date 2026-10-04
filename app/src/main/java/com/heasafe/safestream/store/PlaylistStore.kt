package com.heasafe.safestream.store

import com.heasafe.safestream.model.VideoItem

/**
 * 播放列表的状态与合并规则。
 *
 * 原来这些合并逻辑写在 MainActivity 的 onVideosFound / onEpisodesFound 里，
 * 无法单独验证。抽出来之后才能确定断言两件事：
 *  - 扫描脚本会反复上报同一批数据，整体替换会让列表不停闪
 *  - 选集条目排在媒体之前（用户想选的是"第几集"，不是页面里恰好抓到的那一个流）
 */
class PlaylistStore {

    /** 当前列表。合并后自动更新，供调用方渲染。 */
    var state: List<VideoItem> = emptyList()
        private set

    /**
     * 合并一批媒体。
     *
     * 换页之后旧的选集条目不再有意义 —— 用户已经点了某一集，
     * 留着一部剧的选集配另一部的媒体会让人点错。
     */
    fun mergeVideos(current: List<VideoItem>, incoming: List<VideoItem>): List<VideoItem> {
        val merged = LinkedHashMap<String, VideoItem>()
        current.filterNot { it.isEpisode }.forEach { merged[it.url] = it }
        incoming.filterNot { it.isEpisode }.forEach { merged.putIfAbsent(it.url, it) }
        val next = merged.values.toList()
        state = next
        return next
    }

    /** 合并选集，排在媒体之前。 */
    fun mergeEpisodes(current: List<VideoItem>, episodes: List<VideoItem>): List<VideoItem> {
        val merged = LinkedHashMap<String, VideoItem>()
        episodes.filter { it.isEpisode }.forEach { merged[it.url] = it }
        current.filterNot { it.isEpisode }.forEach { merged[it.url] = it }
        val next = merged.values.toList()
        state = next
        return next
    }

    /** 挑第一个真正能播的媒体，跳过选集条目。 */
    fun firstPlayable(list: List<VideoItem>): VideoItem? =
        list.firstOrNull { !it.isEpisode && it.isPlayable }

    companion object {
        /** 界面上显示的数量：只算真正能播的，选集与 blob 流不计入。 */
        fun playableCount(list: List<VideoItem>): Int = list.count { it.isPlayable }
    }
}
