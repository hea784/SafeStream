package com.heasafe.safestream.model

import org.json.JSONObject

/** 页面上发现的一个视频，序列化为 JSON 跨进程传递。 */
data class VideoItem(
    val url: String,
    val title: String,
    val mimeType: String = "",
    val durationMs: Long = 0L,
    val sourcePage: String = "",
    /**
     * 媒体地址还是剧集页地址。
     *
     * 连续剧的每一集是独立页面（实测 /video/<剧id>/ep-<集号>/），不是媒体地址。
     * 两者播放路径不同：MEDIA 直接喂 ExoPlayer；EPISODE 要先加载该页、
     * 发现它的媒体，再播。
     */
    val kind: Kind = Kind.MEDIA,
    val episodeNo: Int = 0,
) {

    enum class Kind { MEDIA, EPISODE }

    val isEpisode: Boolean get() = kind == Kind.EPISODE

    /** 是否能直接交给 ExoPlayer。blob:/data: 流无法在原生播放器中解析。 */
    val isPlayable: Boolean
        get() {
            if (isEpisode) return true
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
        put("kind", kind.name)
        put("episodeNo", episodeNo)
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
                    kind = if (o.optString("kind") == Kind.EPISODE.name) {
                        Kind.EPISODE
                    } else {
                        Kind.MEDIA
                    },
                    episodeNo = o.optInt("episodeNo"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
