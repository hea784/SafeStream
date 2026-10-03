package com.heasafe.safestream.core

import java.net.URI
import java.util.Locale

/**
 * 广告 / 跟踪 / 挖矿脚本的域名与路径黑名单。
 *
 * 保守的启发式列表 —— 宁可漏拦，不可误拦视频 CDN。
 * 对应 PROMPT.md 安全契约第 12 条。
 */
object TrackerBlocklist {

    private val DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "quantserve.com",
        "amplitude.com",
        "mixpanel.com",
        "segment.io",
        "hotjar.com",
        "fullstory.com",
        "appsflyer.com",
        "adjust.com",
        "chartbeat.com",
        "sentry.io",
        "coinhive.com",
        "crypto-loot.com",
        "webminepool.com",
        "minero.cc",
    )

    private val DOMAIN_SUBSTRINGS = listOf(
        "ads.", "adservice.", "adserver.", "adsystem.",
        "doubleclick", "pagead", "popads", "adcash", "propellerads",
        "track.", "tracker.", "beacon.", "telemetry.",
        "analytics", "collect.",
    )

    private val PATH_MARKERS = listOf(
        "/pagead/", "/ads/", "/adserver/", "/advert", "/banner_ad",
        "/track?", "/tracking/", "/collect?", "/beacon?",
        "/analytics.js", "/gtm.js", "/gtag/js", "/fbevents.js",
        "/coinhive.min.js", "/cryptonight",
    )

    /** 是否应当拦截这个请求。 */
    fun isBlocked(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        val host = runCatching { URI(lower).host }.getOrNull()
        if (host != null && DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return true
        }
        if (DOMAIN_SUBSTRINGS.any { lower.contains(it) }) return true
        if (PATH_MARKERS.any { lower.contains(it) }) return true
        return false
    }
}
