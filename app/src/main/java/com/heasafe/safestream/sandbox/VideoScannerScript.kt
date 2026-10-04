package com.heasafe.safestream.sandbox

import android.content.Context

/**
 * 注入页面的扫描脚本。
 *
 * 脚本本体在 assets/scanner.js：没有语法检查工具的 250 行内嵌字符串
 * 改一个字符就得重编 APK 才能验证，还因此出过括号不平衡的事故。
 * 移出来之后 `py tools/check_scanner.py`（node --check + 关键标记断言）
 * 几秒钟就能验完。
 *
 * 通过 WebViewCompat.addWebMessageListener 回传 —— 这是受白名单 origin 约束的通道，
 * 比 addJavascriptInterface 安全得多（后者会把整个原生对象暴露给任意脚本）。
 */
object VideoScannerScript {

    const val MESSAGE_NAME = "SafeStreamBridge"

    fun source(context: Context): String =
        context.assets.open("scanner.js").bufferedReader().use { it.readText() }
}
