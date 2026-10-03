package com.heasafe.safestream.core

/**
 * 主进程 <-> 沙箱进程的通信契约。
 *
 * 两个进程不能共享静态状态，所以用显式 Intent 广播做通信：
 * 沙箱 -> 主进程上报发现结果；主进程 -> 沙箱下发控制指令。
 *
 * 安全约束（对应 PROMPT.md 第 3 节）：
 * - 只用显式 Intent（setPackage + setClass），绝不用隐式广播。
 * - 接收端统一 RECEIVER_NOT_EXPORTED。
 */
object Bridge {

    /** 沙箱上报一批发现的视频，extra key = EXTRA_VIDEO_JSON */
    const val ACTION_VIDEOS_FOUND = "com.heasafe.safestream.VIDEOS_FOUND"

    /** 沙箱上报页面标题，extra key = EXTRA_TITLE */
    const val ACTION_PAGE_TITLE = "com.heasafe.safestream.PAGE_TITLE"

    /** 沙箱上报安全事件，extra key = EXTRA_MESSAGE */
    const val ACTION_SECURITY_EVENT = "com.heasafe.safestream.SECURITY_EVENT"

    /** 主进程下发：清理沙箱内全部站点数据。 */
    const val ACTION_PURGE = "com.heasafe.safestream.PURGE"

    /** 主进程下发：切换拦截开关，extra key = EXTRA_ENABLED (Boolean) */
    const val ACTION_SET_FILTER = "com.heasafe.safestream.SET_FILTER"

    /** 主进程下发：要求沙箱立刻重新扫描页面。 */
    const val ACTION_RESCAN = "com.heasafe.safestream.RESCAN"

    const val EXTRA_VIDEO_JSON = "video_json"
    const val EXTRA_TITLE = "title"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_URL = "url"
}
