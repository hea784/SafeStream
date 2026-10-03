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
        // 实测自 akep5.xxpofweu.cc：埋点上报
        "eventtracking.cyou",
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
        // 实测：api-ad1/ad3/ad4/ad5.adsdk1-5.cc 广告接口。
        // 原来的 "ads." 匹配不到 —— "ads" 后面是 k 不是点。
        "adsdk", "adsdk1.cc", ".adsdk",
        // 埋点上报接口路径
        "/eventtracking/", "batchreport.json",
    )

    private val PATH_MARKERS = listOf(
        "/pagead/", "/ads/", "/adserver/", "/advert", "/banner_ad",
        "/track?", "/tracking/", "/collect?", "/beacon?",
        "/analytics.js", "/gtm.js", "/gtag/js", "/fbevents.js",
        "/coinhive.min.js", "/cryptonight",
    )

    /**
     * 常见但非标准的端口：埋点上报、矿池、远控等。
     * 正常 CDN 极少用裸 IP 配这些端口，所以拦下来误伤概率很低。
     */
    private val SUSPICIOUS_PORTS = setOf(15212, 4443, 1337, 31337)

    private val IP_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** 是否应当拦截这个请求。 */
    fun isBlocked(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        val uri = runCatching { URI(lower) }.getOrNull()
        val host = uri?.host

        // 裸 IP + 非常规端口：域名黑名单对 IP 字面量完全无效，必须单独判。
        // 实测该站往 4 个裸 IP 的 15212 端口上报埋点。
        if (host != null && IP_LITERAL.matches(host) &&
            uri.port in SUSPICIOUS_PORTS
        ) return true

        if (host != null && DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return true
        }
        if (DOMAIN_SUBSTRINGS.any { lower.contains(it) }) return true
        if (PATH_MARKERS.any { lower.contains(it) }) return true
        return false
    }
}
