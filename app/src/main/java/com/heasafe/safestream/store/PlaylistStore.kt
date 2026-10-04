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
     * **保留选集条目。** 曾经在这里把选集过滤掉，理由是"换页后旧选集该清掉"——
     * 但换页时 loadUrl 已经整体清空过一次，页面自己上报的选集随后到达，
     * 却被这条规则抹掉。实测后果：切到第 2 集后选集整个消失，点不到第 3 集。
     */
    fun mergeVideos(current: List<VideoItem>, incoming: List<VideoItem>): List<VideoItem> {
        val merged = LinkedHashMap<String, VideoItem>()
        current.forEach { merged[it.url] = it }
        incoming.forEach { merged.putIfAbsent(it.url, it) }
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

        /**
         * 给用户看的条目。
         *
         * 有选集时只给选集：连续剧的用户要选的是"第几集"，不是页面上恰好抓到的
         * 那几个 m3u8。真机实测站点写"全集 3 集"而 App 显示"5 个视频"，就是把
         * 3 个选集和 2 个媒体地址混在一起数了。
         */
        fun visibleItems(list: List<VideoItem>): List<VideoItem> {
            val episodes = list.filter { it.isEpisode }
            return if (episodes.isNotEmpty()) episodes else list.filter { it.isPlayable }
        }

        /** 角标上显示的数字，与用户实际看到的条目一致。 */
        fun visibleCount(list: List<VideoItem>): Int = visibleItems(list).size

        /**
         * 连播：当前集播完后要加载的下一集。
         *
         * 只在选集序列里找 afterUrl 的下一项——媒体地址（m3u8）不是集，
         * 不参与连播顺序。找不到（当前不是集 / 已是最后一集）返回 null，
         * 调用方据此停止连播。
         */
        fun nextEpisode(list: List<VideoItem>, afterUrl: String): VideoItem? {
            val episodes = list.filter { it.isEpisode }
            val idx = episodes.indexOfFirst { it.url == afterUrl }
            if (idx < 0 || idx + 1 >= episodes.size) return null
            return episodes[idx + 1]
        }
    }
}
