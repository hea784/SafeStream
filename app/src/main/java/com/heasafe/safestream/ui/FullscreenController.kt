package com.heasafe.safestream.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// 播放器全屏：只看片，界面全部让位。
// 只放大播放器，不把网页一起放大 —— 看剧时网页反而要缩着才顺手。
// 退出用返回键或再次点击播放器；不用滑动，避免和播放器进度条拖动冲突。
class FullscreenController(
    private val activity: Activity,
    private val player: View,
    private val chrome: List<View>,
) {

    var isFullscreen: Boolean = false
        private set

    private var restoreOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    fun toggle() {
        if (isFullscreen) exit() else enter()
    }

    private fun enter() {
        isFullscreen = true
        restoreOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        stretch(player)
        chrome.forEach { it.visibility = View.GONE }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSystemBarsHidden(true)
    }

    private fun exit() {
        isFullscreen = false
        setSystemBarsHidden(false)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        chrome.forEach { it.visibility = View.VISIBLE }
        // 交回 XML 里的 16:9 约束重新测量
        player.layoutParams = player.layoutParams.apply {
            width = 0
            height = 0
        }
        activity.requestedOrientation = restoreOrientation
    }

    private fun stretch(v: View) {
        v.layoutParams = v.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        (v.parent as? ViewGroup)?.let { p ->
            p.layoutParams = p.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val c = WindowInsetsControllerCompat(activity.window, player)
        if (hidden) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
