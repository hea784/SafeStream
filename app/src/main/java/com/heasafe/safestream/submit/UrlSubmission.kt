package com.heasafe.safestream.submit

import com.heasafe.safestream.core.UrlGuard

/**
 * 地址栏输入该被当成什么。
 *
 * 原来这段散在 MainActivity 的 submitUrl / looksLikeKeyword 里，
 * 只能靠手点输入框来验证。抽成纯逻辑之后，"什么算网址、什么算搜索词、
 * 重复提交怎么挡"都能在 JVM 上确定断言。
 */
class UrlSubmission {

    sealed interface Outcome {
        /** 走加载。需要用户确认明文时 [insecureHost] 非空。 */
        data class LoadUrl(
            val url: String,
            val insecureHost: String?,
            val needsInsecureConfirm: Boolean,
        ) : Outcome

        data class SearchUrl(val url: String) : Outcome

        data class Reject(val reason: String) : Outcome
    }

    private var lastUrl: String? = null
    private var lastAt = 0L

    /**
     * 防抖闸门。
     *
     * 曾经踩过：沙箱搜索栏回车会触发一次提交，若不挡，同一地址会被提交两遍，
     * 而第二次加载会清空播放列表 —— 表现为选集刚填好又变空。
     */
    fun tryAcquire(url: String, nowMs: Long, windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        if (url == lastUrl && nowMs - lastAt < windowMs) return false
        lastUrl = url
        lastAt = nowMs
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2000L
        const val SEARCH_PREFIX = "https://www.bing.com/search?q="

        fun resolve(raw: String): Outcome {
            val input = raw.trim()
            if (input.isEmpty()) return Outcome.Reject("地址为空")

            // 没有 scheme、没有点号、含空格 —— 按搜索词处理
            if (looksLikeKeyword(input)) {
                return Outcome.SearchUrl(
                    SEARCH_PREFIX + java.net.URLEncoder.encode(input, "UTF-8"),
                )
            }

            return when (val v = UrlGuard.inspect(input)) {
                is UrlGuard.Result.Rejected -> Outcome.Reject(v.reason)
                is UrlGuard.Result.Secure ->
                    Outcome.LoadUrl(v.normalized, null, needsInsecureConfirm = false)
                is UrlGuard.Result.Insecure -> Outcome.LoadUrl(
                    url = v.normalized,
                    insecureHost = runCatching { java.net.URI(v.normalized).host }.getOrNull(),
                    needsInsecureConfirm = true,
                )
            }
        }

        private fun looksLikeKeyword(input: String): Boolean {
            if (input.contains("://")) return false
            if (input.contains(' ')) return true
            return !input.substringBefore('/').contains('.')
        }
    }
}
