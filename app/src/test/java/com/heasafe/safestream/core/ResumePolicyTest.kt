package com.heasafe.safestream.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续播适用性。
 *
 * 真机问题：目标站的 m3u8 带 auth_key 签名，有时效。这类地址过期后
 * 存下来的进度就再也匹配不上，表现为"续播功能好像坏了"。
 * 对这类地址应该干脆不记进度，而不是记一个用不了的。
 */
class ResumePolicyTest {

    @Test
    fun `带签名参数的地址不记录进度`() {
        val signed = listOf(
            "https://yd-hls.tktjpm.cn/videos5/a.m3u8?auth_key=1791026163-abc",
            "https://cdn.example.com/v.m3u8?token=xyz&t=1700000000",
            "https://cdn.example.com/v.m3u8?sign=abc&expires=1700000000",
            "https://cdn.example.com/v.m3u8?Policy=abc&Key-Pair-Id=1",
            "https://cdn.example.com/v.mp4?wsSecret=abc",
            "https://cdn.example.com/v.m3u8?wmsAuthSign=abc",
        )
        for (u in signed) {
            assertTrue("应视为易失地址: $u", UrlGuard.isEphemeral(u))
        }
    }

    @Test
    fun `普通地址照常记录进度`() {
        val stable = listOf(
            "https://example.com/videos/episode-2.mp4",
            "https://example.com/v/playlist.m3u8",
            "https://example.com/v/a.mp4?start=10",
        )
        for (u in stable) {
            assertFalse("应视为稳定地址: $u", UrlGuard.isEphemeral(u))
        }
    }

    @Test
    fun `路径里的签名不影响判断`() {
        // 只有查询参数才会过期，路径里的 token 片段是长期标识
        assertFalse(UrlGuard.isEphemeral("https://example.com/auth_key/a/v.mp4"))
    }
}
