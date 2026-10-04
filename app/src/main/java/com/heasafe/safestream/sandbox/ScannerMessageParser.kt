package com.heasafe.safestream.sandbox

import com.heasafe.safestream.core.UrlGuard
import org.json.JSONArray
import org.json.JSONObject

/** 扫描脚本一条消息解析后的结果。 */
sealed interface ScanMessage {
    /** 规范化后的媒体批次（JSON 数组字符串，与既有回调契约一致）。 */
    data class Videos(val json: String) : ScanMessage

    /** 规范化后的选集批次。 */
    data class Episodes(val json: String) : ScanMessage

    /** 页面声明的 DRM 系统；显式"无 DRM"时为 null。 */
    data class Drm(val system: String?) : ScanMessage

    /** 用户点了 blob 流：播本页已发现的媒体。 */
    data object PlayFound : ScanMessage

    /** mse: 之类的噪声、坏 JSON、被 URL 闸门清空的批次。 */
    data object Ignore : ScanMessage
}

/**
 * 页面扫描脚本的消息解析（assets/scanner.js -> 应用内消息）。
 *
 * 原来这段匿名内嵌在 WebSandbox.dispatch 里，只能靠"装设备 + 起夹具 +
 * 抓 UI"间接验证，而那套手段多次给出自相矛盾的结论。抽成纯逻辑后，
 * 消息的每种形态都能在 JVM 上确定性断言（org.json 用真实实现，
 * android.jar 里的只是空壳）。
 *
 * 载荷形态见 assets/scanner.js：
 *   {"batch":[媒体…]} / {"episodes":[…]} / {"url":…,"kind":"fetch"|"xhr"|"mse"|"drm"|"nodrm"|"play-found"|"click"}
 */
class ScannerMessageParser(
    /** URL 闸门：注入以便测试，生产用 UrlGuard 的 SSRF / scheme 判断。 */
    private val allowUrl: (String) -> Boolean = { UrlGuard.allowNavigation(it) },
) {

    fun parse(payload: String): ScanMessage {
        if (payload.isBlank()) return ScanMessage.Ignore
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return ScanMessage.Ignore
        val kind = root.optString("kind")
        return when {
            kind == "drm" -> ScanMessage.Drm(root.optString("url").substringAfter("drm:").ifBlank { "DRM" })
            kind == "nodrm" -> ScanMessage.Drm(null)
            root.has("url") && !root.has("batch") -> networkHit(root, kind)
            root.has("episodes") -> episodes(root.optJSONArray("episodes"))
            else -> batch(root.optJSONArray("batch"))
        }
    }

    private fun networkHit(o: JSONObject, kind: String): ScanMessage {
        val url = o.optString("url")
        // mse: 只是流类型声明（"mse:video/mp4"），不是地址
        if (kind == "mse" || url.startsWith("mse:")) return ScanMessage.Ignore
        // 用户点了 blob 流：地址在页面外没有意义，但点击已表达播放意图
        if (kind == "play-found") return ScanMessage.PlayFound
        val out = JSONArray()
        if (allowUrl(url)) {
            out.put(
                JSONObject().apply {
                    put("url", url)
                    put("title", "")
                    put("mimeType", "")
                    put("durationMs", 0)
                    put("sourcePage", o.optString("page"))
                },
            )
        }
        return if (out.length() > 0) ScanMessage.Videos(out.toString()) else ScanMessage.Ignore
    }

    private fun batch(items: JSONArray?): ScanMessage {
        if (items == null || items.length() == 0) return ScanMessage.Ignore
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!allowUrl(url)) continue
            out.put(
                JSONObject().apply {
                    put("url", url)
                    put("title", o.optString("title"))
                    put("mimeType", o.optString("mimeType"))
                    put("durationMs", o.optLong("durationMs"))
                    put("sourcePage", o.optString("sourcePage"))
                },
            )
        }
        return if (out.length() > 0) ScanMessage.Videos(out.toString()) else ScanMessage.Ignore
    }

    private fun episodes(items: JSONArray?): ScanMessage {
        if (items == null || items.length() == 0) return ScanMessage.Ignore
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!allowUrl(url)) continue
            out.put(
                JSONObject().apply {
                    put("url", url)
                    put("title", o.optString("title"))
                    put("mimeType", "")
                    put("durationMs", 0)
                    put("sourcePage", o.optString("page"))
                    put("kind", "EPISODE")
                    put("episodeNo", o.optInt("ep"))
                },
            )
        }
        return if (out.length() > 0) ScanMessage.Episodes(out.toString()) else ScanMessage.Ignore
    }
}
