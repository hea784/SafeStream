package com.heasafe.safestream

import android.app.Application

class SafeStreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 尽早安装：启动阶段的崩溃也要能留下现场
        CrashReporter.install(this)
    }
}
