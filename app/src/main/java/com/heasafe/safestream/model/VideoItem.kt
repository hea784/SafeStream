package com.heasafe.safestream.model

import org.json.JSONObject

/** 页面上发现的一个视频，序列化为 JSON 跨进程传递。 */
data class VideoItem(
    val url: String,
    val title: String,
    val mimeType: String = "",
    val durationMs: Long = 0L,
    val sourcePage: String = "",
) {

    /** 是否可交给 ExoPlayer。blob:/data: 流无法在原生播放器中解析。 */
    val isPlayable: Boolean
        get() {
            if (url.isBlank()) return false
            val lower = url.lowercase()
            return !lower.startsWith("blob:") &&
                !lower.startsWith("data:") &&
                !lower.startsWith("javascript:")
        }

    fun toJson(): String = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("mimeType", mimeType)
        put("durationMs", durationMs)
        put("sourcePage", sourcePage)
    }.toString()

    companion object {
        fun fromJson(raw: String): VideoItem? = try {
            val o = JSONObject(raw)
            val url = o.optString("url")
            if (url.isBlank()) {
                null
            } else {
                VideoItem(
                    url = url,
                    title = o.optString("title").ifBlank { url },
                    mimeType = o.optString("mimeType"),
                    durationMs = o.optLong("durationMs"),
                    sourcePage = o.optString("sourcePage"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
