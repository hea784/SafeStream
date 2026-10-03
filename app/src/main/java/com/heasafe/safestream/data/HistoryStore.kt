package com.heasafe.safestream.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地只存两类数据：地址历史、每个视频的播放进度。
 *
 * 用 SharedPreferences + JSON 而不是数据库 —— 数据量小、结构固定，没有引入 Room 的必要。
 * 不存任何 Cookie、token 或页面内容。
 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("safestream", Context.MODE_PRIVATE)

    data class Entry(val url: String, val title: String, val visitedAt: Long)

    fun history(): List<Entry> {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    visitedAt = o.optLong("visitedAt"),
                ),
            )
        }
        return out.sortedByDescending { it.visitedAt }
    }

    fun rememberVisit(url: String, title: String) {
        val now = System.currentTimeMillis()
        val merged = history().filterNot { it.url == url }
            .toMutableList()
            .apply {
                add(0, Entry(url, title.ifBlank { url }, now))
            }
            .take(50)

        val arr = JSONArray()
        merged.forEach {
            arr.put(
                JSONObject().apply {
                    put("url", it.url)
                    put("title", it.title)
                    put("visitedAt", it.visitedAt)
                },
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** 断点续播位置。毫秒。 */
    fun progressKey(url: String) = "progress_$url"

    fun saveProgress(url: String, positionMs: Long, durationMs: Long) {
        // 快到尾声的进度没有续播价值
        if (durationMs > 0 && positionMs > durationMs * 0.95) return
        prefs.edit().putLong(progressKey(url), positionMs).apply()
    }

    fun readProgress(url: String): Long = prefs.getLong(progressKey(url), 0L)

    fun clearAll() = prefs.edit().clear().apply()
}

private const val KEY_HISTORY = "history"
