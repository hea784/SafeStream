package com.heasafe.safestream.core

/**
 * 本次会话的安全事件流水，防护面板的数据源。
 *
 * 「拦了什么」必须让用户看得到——只有计数没有明细的防护，
 * 用户既无法确认它在工作，也无法发现它误伤（比如把视频 CDN 拦了）。
 *
 * 容量封顶：广告多的页面一分钟能产生上百条事件，不封顶会无限吃内存。
 */
class SecurityLog {

    private val events = ArrayDeque<String>()

    /** 最新事件在最前。 */
    @Synchronized
    fun record(event: String) {
        if (event.isBlank()) return
        events.addFirst(event)
        while (events.size > CAPACITY) events.removeLast()
    }

    @Synchronized
    fun all(): List<String> = events.toList()

    @Synchronized
    fun count(): Int = events.size

    @Synchronized
    fun clear() = events.clear()

    companion object {
        const val CAPACITY = 300
    }
}
