package com.heasafe.safestream

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 崩溃现场留存。
 *
 * 没有它，真机崩溃只能靠用户口述现象 —— 而崩溃原因往往藏在堆栈里，猜是猜不出来的。
 * 安装后任何未捕获异常都会：
 *   1. 打印完整堆栈到 logcat（tag: SafeStreamCrash）
 *   2. 存到 app 私有目录，App 下次启动时弹窗展示，用户截图即可交回现场
 */
object CrashReporter : Thread.UncaughtExceptionHandler {

    private const val LOG_TAG = "SafeStreamCrash"
    private const val FILE_NAME = "last_crash.txt"

    private var previous: Thread.UncaughtExceptionHandler? = null
    private var pending: String? = null
    private var appDir: java.io.File? = null

    fun install(app: Application) {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        appDir = app.filesDir
        pending = runCatching {
            val f = app.getFileStreamPath(FILE_NAME)
            if (f.exists()) f.readText().also { f.delete() } else null
        }.getOrNull()
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val text = buildString {
            append("时间: ").append(java.util.Date()).append('\n')
            append("线程: ").append(t.name).append("\n\n")
            append(stackTraceOf(e))
        }
        Log.e(LOG_TAG, text)
        runCatching {
            // 进程即将死亡，不能依赖 Activity 上下文，用安装时记下的目录
            java.io.File(appDir, FILE_NAME).writeText(text)
        }
        // 交给系统默认处理器，保证进程真的结束（否则状态不确定）
        previous?.uncaughtException(t, e)
    }

    /** 取走并清空上一次崩溃的堆栈；没有则返回 null。 */
    fun consume(): String? = pending.also { pending = null }

    private fun stackTraceOf(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }
}

/** 启动后展示上一次崩溃现场，用户截图即可交回。 */
fun Activity.showCrashIfAny() {
    val text = CrashReporter.consume() ?: return
    val scroll = ScrollView(this)
    scroll.addView(
        TextView(this).apply {
            this.text = text
            textSize = 11f
            setPadding(32, 32, 32, 32)
        },
    )
    AlertDialog.Builder(this)
        .setTitle("上次发生了崩溃")
        .setView(scroll)
        .setPositiveButton("知道了", null)
        .show()
}
