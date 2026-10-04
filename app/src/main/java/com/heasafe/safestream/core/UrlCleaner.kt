package com.heasafe.safestream.core

/**
 * 页面导航地址的跟踪参数剥离。
 *
 * 与 Firefox ETP / Brave / iOS Link Tracking Protection 同一思路：
 * utm_* 与各广告平台的点击 id 是纯归因参数，剥掉不影响页面功能。
 *
 * 边界（这条最重要）：只剥精确白名单 + utm_ 前缀，绝不做模糊匹配——
 * 视频清单地址的 auth_key / token / sign 是防盗链签名，误剥直接播不出来。
 * 返回 null 表示无需重写，调用方据此避免多余的一次 loadUrl。
 *
 * 纯字符串解析而非 android.net.Uri：这段逻辑要在 JVM 单测里真实执行，
 * 而 Uri 在 unit test 里是 not-mocked 的空壳。
 */
object UrlCleaner {

    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "msclkid", "dclid", "twclid", "yclid",
        "wbraid", "gbraid", "igshid", "mkt_tok",
        "mc_cid", "mc_eid", "_hsenc", "_hsmi",
        "spm_id_from", "spm_from", "vd_source",
    )
    private const val UTM_PREFIX = "utm_"

    /** 剥掉跟踪参数；无需改动时返回 null。只处理 http/https。 */
    fun stripTrackingParams(url: String): String? {
        val scheme = url.substringBefore(":", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val qStart = url.indexOf('?')
        if (qStart < 0) return null
        val hash = url.indexOf('#', qStart)
        val qEnd = if (hash < 0) url.length else hash
        val query = url.substring(qStart + 1, qEnd)
        if (query.isEmpty()) return null

        val pairs = query.split('&')
        val kept = pairs.filter { !isTracking(it.substringBefore('=')) }
        if (kept.size == pairs.size) return null

        val head = url.substring(0, qStart)
        val tail = if (hash < 0) "" else url.substring(hash)
        val newQuery = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return head + newQuery + tail
    }

    private fun isTracking(name: String): Boolean =
        name.lowercase() in TRACKING_PARAMS || name.lowercase().startsWith(UTM_PREFIX)
}
