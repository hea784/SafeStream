package com.heasafe.safestream.core

import java.net.URI
import com.heasafe.safestream.BuildConfig

/**
 * URL 输入校验与 scheme 决策。
 *
 * 设计原则：白名单放行一切，其余一律拒绝。任何"猜测用户意图"的宽松处理都不写在这里。
 */
object UrlGuard {

    /** 允许在 WebView 中导航的 scheme。 */
    val NAVIGABLE_SCHEMES = setOf("http", "https")

    /** 出现在任何位置都直接拒绝的 scheme —— 本地文件读取、应用拉起、脚本注入的入口。 */
    private val ALWAYS_DENIED_SCHEMES = setOf(
        "file", "content", "intent", "market", "javascript",
        "blob", "data", "android.resource", "jar",
    )

    private const val MAX_INPUT_LENGTH = 2048

    /** 空白与控制字符：用来挡 URL 走私。 */
    private val ILLEGAL_CHARS = Regex("[\\s\\u0000-\\u001f\\\\^`{|}]")

    sealed interface Result {
        /** 可直接加载，且已加密。 */
        data class Secure(val normalized: String) : Result

        /** 明文 HTTP：需要用户显式确认才能继续。 */
        data class Insecure(val normalized: String) : Result

        /** 输入非法或属于禁止的 scheme。 */
        data class Rejected(val reason: String) : Result
    }

    fun inspect(rawInput: String): Result {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return Result.Rejected("地址为空")
        if (trimmed.length > MAX_INPUT_LENGTH) return Result.Rejected("地址过长")
        if (ILLEGAL_CHARS.containsMatchIn(trimmed)) return Result.Rejected("地址含非法字符")

        // 未写 scheme 时补 https://，避免默认落到明文 http
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return Result.Rejected("地址无法解析")
        }

        val scheme = uri.scheme?.lowercase() ?: return Result.Rejected("缺少协议")
        if (scheme in ALWAYS_DENIED_SCHEMES) return Result.Rejected("禁止的协议：$scheme")
        if (scheme !in NAVIGABLE_SCHEMES) return Result.Rejected("不支持的协议：$scheme")

        val host = uri.host
        if (host.isNullOrBlank()) return Result.Rejected("缺少主机名")
        if (!host.contains('.') && host != "localhost") return Result.Rejected("主机名不合法")
        // 内网地址：SSRF 到本机/局域网同样是一种危害
        if (isPrivateHost(host) && !BuildConfig.ALLOW_PRIVATE_HOSTS) {
            return Result.Rejected("不允许访问内网地址：$host")
        }

        return if (scheme == "https") {
            Result.Secure(uri.toString())
        } else {
            Result.Insecure(uri.toString())
        }
    }

    /** 导航决策：给 WebView 用。true 表示允许导航。 */
    fun allowNavigation(url: String): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        return scheme in NAVIGABLE_SCHEMES
    }

    fun isInsecure(url: String) = url.startsWith("http://", ignoreCase = true)

    /**
     * 查询串里带签名/时效标记的地址。
     *
     * 这类地址过期后内容就变了，拿它当断点续播的 key 会永远匹配不上 ——
     * 表现是"续播坏了"，其实只是地址换了一轮。遇到这类地址干脆不记进度。
     *
     * 只看查询串：路径里的 token 片段是长期标识，不代表会过期。
     */
    fun isEphemeral(url: String): Boolean {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return false
        return query.split('&').any { param ->
            EPHEMERAL_KEYS.contains(param.substringBefore('=').trim().lowercase())
        }
    }

    private val EPHEMERAL_KEYS = setOf(
        "auth_key", "token", "sign", "signature", "expires", "policy",
        "key-pair-id", "wssecret", "wmsauthsign", "x-amz-signature",
        "x-oss-signature", "e", "t", "time",
    )

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "0.0.0.0" || h == "::1") return true
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true
        if (h.startsWith("169.254.")) return true
        // 172.16.0.0/12
        h.removePrefix("172.").substringBefore('.').toIntOrNull()?.let { second ->
            if (second in 16..31) return true
        }
        return false
    }
}
