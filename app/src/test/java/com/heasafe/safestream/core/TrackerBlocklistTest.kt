package com.heasafe.safestream.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拦截规则回归测试。
 *
 * BLOCKED 里的 URL 全部是 2026-10-03 用真实 Chrome 抓包抓到的、该站实际发出的请求，
 * 不是编出来的样例。这类测试的价值在于：以后有人放宽规则时，这些会立刻失败。
 */
class TrackerBlocklistTest {

    /** 实测抓到、必须拦下的。 */
    private val mustBlock = listOf(
        // 4 个裸 IP + 非标准端口上报埋点（域名黑名单对 IP 字面量无效）
        "https://54.169.219.76:15212/api/eventTracking/batchReport.json",
        "https://13.229.106.237:15212/api/eventTracking/batchReport.json",
        "https://52.221.236.23:15212/api/eventTracking/batchReport.json",
        "https://54.151.247.122:15212/api/eventTracking/batchReport.json",
        // 广告接口。原规则 "ads." 匹配不到 adsdk1.cc（ads 后面是 k 不是点）
        "https://api-ad1.adsdk1.cc/api/sdk/ad/request",
        "https://api-ad3.adsdk3.cc/api/sdk/ad/request",
        "https://api-ad4.adsdk4.cc/api/sdk/ad/request",
        "https://api-ad5.adsdk5.cc/api/sdk/ad/request",
        "https://api-ad1.adsdk1.cc/api/sdk/ad/available",
        // 埋点域名
        "https://api-dc-prod-001.cyou/api/eventTracking/batchReport.json",
        "https://api-dc2-prod-06.cyou/api/eventTracking/batchReport.json",
        // 常见的
        "https://www.google-analytics.com/g/collect?v=2&tid=G-4HY4G5GBN1",
        "https://analytics.ahrefs.com/analytics.js",
        "https://static.cloudflareinsights.com/beacon.min.js",
        "https://www.googletagmanager.com/gtag/js?id=G-4HY4G5GBN1",
        // 该站自己的跟踪脚本（"tracking.js" 曾从 "track." 规则漏过去）
        "https://akep5.xxpofweu.cc/static/web/js/tracking.js?v=20260929002",
        "https://akep5.xxpofweu.cc/static/web/js/lib/ssp-core-v0.0.4-20260827.js",
        "https://akep5.xxpofweu.cc/static/web/js/ssp-mount.js?v=20260929002",
        "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
        "https://www.doubleclick.net/ad.gif",
    )

    /** 必须放行的：拦了这些视频就播不了了。 */
    private val mustAllow = listOf(
        // 该站的视频清单与分片 CDN
        "https://yd-hls.tktjpm.cn/videos5/d1368ff17f/d1368ff17f.m3u8?auth_key=1791026163",
        "https://tp6.wirqed.cn/videos5/d1368ff17f/d1368ff17f0.ts?auth_key=1791026169",
        // 站点页面与接口
        "https://akep5.xxpofweu.cc/video/5671/ep-2/",
        "https://akep5.xxpofweu.cc/api/videos/5671/comments?page=1&size=12&ep=1",
        "https://huangguoai.com/",
        // 常规站点
        "https://example.com/",
        "https://www.bing.com/search?q=test",
        // 常规 443 的 IP 直连不拦，拦了会误伤
        "https://1.1.1.1/",
    )

    @Test
    fun `实测抓到的广告与埋点请求必须被拦`() {
        for (u in mustBlock) {
            assertTrue("应拦截: $u", TrackerBlocklist.isBlocked(u))
        }
    }

    @Test
    fun `视频清单分片与站点地址不能被误伤`() {
        for (u in mustAllow) {
            assertFalse("不应拦截: $u", TrackerBlocklist.isBlocked(u))
        }
    }
}
