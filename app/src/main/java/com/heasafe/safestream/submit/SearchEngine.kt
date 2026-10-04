package com.heasafe.safestream.submit

/**
 * 可配置的搜索引擎。
 *
 * 原来硬编码 Bing —— 而 Bing 在国内通常需要代理才能访问，
 * 这对"发给别人就能用"是硬伤。默认保持 Bing 以维持既有行为，但可以切。
 */
enum class SearchEngine(val label: String, private val template: String) {
    BING("必应", "https://www.bing.com/search?q=%s"),
    BAIDU("百度", "https://www.baidu.com/s?wd=%s"),
    SOGOU("搜狗", "https://www.sogou.com/web?query=%s"),
    ;

    fun queryUrl(keyword: String): String {
        if (keyword.isBlank()) return ""
        return template.format(java.net.URLEncoder.encode(keyword, "UTF-8"))
    }

    companion object {
        /** 未知或未设置时退回默认，不让配置损坏导致搜索不可用。 */
        fun fromName(name: String?): SearchEngine =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BING
    }
}
