package com.heasafe.safestream.data

import android.content.Context

/**
 * 只存一件事：每个视频的播放进度，用于断点续播。
 *
 * 原来这里还存了一份"访问历史"，但界面上没有任何地方展示它 ——
 * 写了从不读，是看不见的死状态，已删掉。要历史功能就等有界面时再加。
 *
 * 用 SharedPreferences 而不是数据库：只有一个 key-value 映射，引入 Room 是浪费。
 * 不存任何 Cookie、token 或页面内容。
 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("safestream", Context.MODE_PRIVATE)

    /**
     * 进度以 URL 的哈希为 key。
     *
     * 不能直接用 URL：签名流地址带 auth_key，长度可达几百字符，
     * 而且每次请求都变 —— 当 key 用既撑大 prefs 又永远命中不了。
     */
    private fun progressKey(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
        return "p_" + digest.joinToString("") { "%02x".format(it) }
    }

    fun saveProgress(url: String, positionMs: Long, durationMs: Long) {
        // 签名地址每轮都会换，记下来的进度永远匹配不上，不如不记
        if (com.heasafe.safestream.core.UrlGuard.isEphemeral(url)) return
        // 快到尾声的进度没有续播价值
        if (durationMs > 0 && positionMs > durationMs * 0.95) return
        prefs.edit().putLong(progressKey(url), positionMs).apply()
    }

    fun readProgress(url: String): Long =
        if (com.heasafe.safestream.core.UrlGuard.isEphemeral(url)) 0L
        else prefs.getLong(progressKey(url), 0L)

    /** 这一集是否留下过播放记录（看过标记用，起播即算，不等进度值非零）。 */
    fun hasProgress(url: String): Boolean =
        !com.heasafe.safestream.core.UrlGuard.isEphemeral(url) && prefs.contains(progressKey(url))

    /**
     * 记「开过某一集」。选集浮层的行是剧集页地址，而播放进度挂在
     * 媒体地址上——两边对不上，浮层的「看过」标记必须单独记账。
     */
    fun markOpened(url: String) {
        if (com.heasafe.safestream.core.UrlGuard.isEphemeral(url)) return
        val updated = prefs.getStringSet(OPENED_KEY, emptySet()).orEmpty() + url
        prefs.edit().putStringSet(OPENED_KEY, updated).apply()
    }

    fun hasOpened(url: String): Boolean =
        prefs.getStringSet(OPENED_KEY, emptySet())?.contains(url) == true

    /**
     * 记一个"最近打开的页面"（仅页面地址，不含媒体直链），供地址栏下拉补全。
     *
     * 与进度/开集记录分开存：这两者按 URL 哈希记 key，不可读出原文；
     * 补全需要把原文列出来。只存页面（loadUrl 的入口），媒体直链
     * （m3u8/mp4 签名地址）不进这里 —— 长且每轮变，列出来没有价值。
     * 有界：最近的在前，超出上限从尾部丢弃，防止 prefs 无限膨胀。
     */
    fun markVisited(url: String) {
        if (url.isBlank() || com.heasafe.safestream.core.UrlGuard.isEphemeral(url)) return
        val current = recentPages().toMutableList()
        current.removeAll { it == url }
        current.add(0, url)
        while (current.size > RECENT_LIMIT) current.removeAt(current.size - 1)
        prefs.edit().putString(RECENT_KEY, current.joinToString("\n")).apply()
    }

    /** 最近打开的页面，最新的在前。 */
    fun recentPages(): List<String> =
        prefs.getString(RECENT_KEY, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun clearAll() = prefs.edit().clear().apply()

    private companion object {
        const val OPENED_KEY = "opened_episodes"
        const val RECENT_KEY = "recent_pages"
        const val RECENT_LIMIT = 12
    }
}
