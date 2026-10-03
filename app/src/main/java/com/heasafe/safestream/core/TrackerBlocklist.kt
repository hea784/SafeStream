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
        // 实测自 akep5.xxpofweu.cc / huangguoai.com（黄果短剧）2026-10-03
        "ahrefs.com",
        "cloudflareinsights.com",
        "ssp-core.jsdelivr.com",
    )

    private val DOMAIN_SUBSTRINGS = listOf(
        "ads.", "adservice.", "adserver.", "adsystem.",
        "doubleclick", "pagead", "popads", "adcash", "propellerads",
        // "track." 匹配不到 tracking.js（track 后面是 i 不是点），
        // 实测该站的 tracking.js 就是从这条路漏过去的
        "track.", "tracker.", "tracking.js", "beacon.", "beacon.min.js", "telemetry.",
        "analytics", "collect.",
        // 该站广告 SDK：ssp-core-vX.js / ssp-mount.js，负责插屏与全屏广告
        "ssp-core", "ssp-mount",
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
