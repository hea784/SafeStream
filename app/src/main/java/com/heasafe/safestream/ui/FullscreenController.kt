package com.heasafe.safestream.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// 播放器全屏：把播放器从迷你条搬进全屏容器，退出再搬回来。
// 同一个 PlayerView 实例在两个容器间移动 —— SurfaceView 换父容器会重建，
// 表现为一瞬间黑屏，可接受；用两个 PlayerView 则要管理两份 player 实例，更糟。
class FullscreenController(
    private val activity: Activity,
    private val playerView: androidx.media3.ui.PlayerView,
    private val miniSlot: FrameLayout,
    private val fullscreenContainer: FrameLayout,
    /** 全屏时一并隐藏的界面元素（顶栏、FAB 等会压在视频上或分散注意力的控件）。 */
    private val chromeViews: List<View> = emptyList(),
) {

    var isFullscreen: Boolean = false
        private set

    private var restoreOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // chromeViews 进入全屏前的可见性，退出时原样恢复（FAB 平时可能是 INVISIBLE）
    private val savedChromeVisibility = mutableListOf<Pair<View, Int>>()

    fun toggle() {
        if (isFullscreen) exit() else enter()
    }

    private fun enter() {
        isFullscreen = true
        restoreOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        savedChromeVisibility.clear()
        chromeViews.forEach { v ->
            savedChromeVisibility.add(v to v.visibility)
            v.visibility = View.GONE
        }
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        fullscreenContainer.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // 控制器只在全屏开：迷你槽里它以约 1 秒一次的频率刷时间条，
        // 暂停了也在渲染，界面永远不 idle
        playerView.useController = true
        fullscreenContainer.visibility = View.VISIBLE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSystemBarsHidden(true)
    }

    private fun exit() {
        isFullscreen = false
        savedChromeVisibility.forEach { (v, visibility) -> v.visibility = visibility }
        savedChromeVisibility.clear()
        setSystemBarsHidden(false)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fullscreenContainer.removeView(playerView)
        miniSlot.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        playerView.useController = false
        fullscreenContainer.visibility = View.GONE
        activity.requestedOrientation = restoreOrientation
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val c = WindowInsetsControllerCompat(activity.window, fullscreenContainer)
        if (hidden) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
