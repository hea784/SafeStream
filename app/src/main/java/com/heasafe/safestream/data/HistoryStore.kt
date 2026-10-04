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

    fun clearAll() = prefs.edit().clear().apply()

    private companion object {
        const val OPENED_KEY = "opened_episodes"
    }
}
