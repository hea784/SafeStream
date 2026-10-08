package com.heasafe.safestream.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.heasafe.safestream.R
import com.heasafe.safestream.data.HistoryStore
import com.heasafe.safestream.databinding.ActivityMainBinding
import com.heasafe.safestream.model.VideoItem

/**
 * 播放会话：播放器本体、迷你条控制、进度与倍速。
 *
 * 从 MainActivity 抽出来的原因：项目里三个历史 bug（跨线程崩溃、
 * 进度记错条目、连播锚点被清）都出在播放器与界面混写的同一个类里，
 * 改任何一处都要在 600 行文件里定位。
 *
 * 分工：**机制在这里，决策留在 MainActivity**——本集播完了要不要连下一集
 * （PlaylistStore.nextEpisode，纯逻辑已单测）、页面声明了什么 DRM、
 * 这些都要看列表与沙箱状态，不是播放器的职责。
 */
class PlaybackSession(
    private val playerView: androidx.media3.ui.PlayerView,
    private val binding: ActivityMainBinding,
    private val history: HistoryStore,
    /** 正在播的媒体地址（列表会重排，地址才是稳定身份）。 */
    private val playingUrl: () -> String?,
    /** 页面声明的 DRM 系统，无则 null。 */
    private val drmSystem: () -> String?,
    /** 本集播完：上层决定连播与否。 */
    private val onEnded: () -> Unit,
    /** 播放结束态变化（true=播完停在末尾，false=重新开始播放）。上层据此刻重播覆盖层。 */
    private val onEndedStateChanged: (Boolean) -> Unit = {},
) {

    val player: ExoPlayer = ExoPlayer.Builder(binding.root.context).build()

    /**
     * 当前视频宽高比（width/height），未知为 0。
     * 全屏方向据此自适应：横屏视频转横屏，竖屏视频保持竖屏
     * （固定 SENSOR_LANDSCAPE 会让竖屏视频上下大黑边）。
     */
    @Volatile
    var videoAspectRatio: Float = 0f
        private set

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 迷你条进度：半秒一跳；暂停时不刷新——进度本来就停着，
     * 而持续 invalidate 会让界面永远不 idle（无障碍与 uiautomator 全被拖死）。
     */
    private val progressTick = object : Runnable {
        override fun run() {
            if (binding.miniPlayer.visibility == View.VISIBLE &&
                player.isPlaying &&
                player.duration > 0
            ) {
                binding.miniProgress.max = player.duration.toInt().coerceAtLeast(1)
                binding.miniProgress.progress =
                    player.currentPosition.toInt().coerceIn(0, binding.miniProgress.max)
            }
            handler.postDelayed(this, 500)
        }
    }

    init {
        playerView.player = player
        binding.miniToggle.setOnClickListener { togglePlayPause() }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val drm = drmSystem()
                val message = if (drm != null) {
                    "该视频经过 $drm 加密，无法播放"
                } else {
                    "播放失败：${error.errorCodeName}"
                }
                Toast.makeText(binding.root.context, message, Toast.LENGTH_SHORT).show()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshToggleIcon()
                if (!isPlaying) return
                val url = playingUrl() ?: return
                history.saveProgress(url, player.currentPosition, player.duration)
            }

            override fun onPlaybackStateChanged(state: Int) {
                refreshToggleIcon()
                when (state) {
                    Player.STATE_ENDED -> {
                        onEndedStateChanged(true)
                        onEnded()
                    }
                    // STATE_READY 覆盖播完后的重播/seek 回退：覆盖层只在"停在末尾"时在
                    Player.STATE_READY -> onEndedStateChanged(false)
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                videoAspectRatio = if (videoSize.height == 0) {
                    0f
                } else {
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio /
                        videoSize.height.toFloat()
                }
            }
        })
        handler.post(progressTick)
    }

    /** 加载并播放一个媒体项。同地址已在播不重建，否则画面反复重置会闪。 */
    fun play(item: VideoItem) {
        videoAspectRatio = 0f
        val mediaItem = MediaItem.Builder()
            .setUri(item.url)
            .setMediaId(item.url)
            .setMimeType(
                when {
                    item.url.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                    item.url.contains(".mpd") -> MimeTypes.APPLICATION_MPD
                    item.mimeType.isNotBlank() -> item.mimeType
                    else -> null
                },
            )
            .build()
        player.setMediaItem(mediaItem)
        val resumeAt = history.readProgress(item.url)
        if (resumeAt > 3_000) {
            player.seekTo(resumeAt)
            Toast.makeText(
                binding.root.context,
                binding.root.context.getString(R.string.resume_toast, formatMs(resumeAt)),
                Toast.LENGTH_SHORT,
            ).show()
        }
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun refreshToggleIcon() {
        val playing = player.isPlaying
        binding.miniToggle.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.miniToggle.contentDescription =
            binding.root.context.getString(
                if (playing) R.string.mini_pause else R.string.mini_play,
            )
    }

    fun cycleSpeed() {
        val idx = SPEEDS.indexOfFirst { it == player.playbackParameters.speed }
            .takeIf { it >= 0 } ?: 0
        val next = SPEEDS[(idx + 1) % SPEEDS.size]
        player.setPlaybackSpeed(next)
        val label = formatSpeed(next)
        binding.miniSpeed.text = label
        binding.miniSpeed.visibility = View.VISIBLE
        Toast.makeText(
            binding.root.context,
            binding.root.context.getString(R.string.speed_label, label),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun speedLabel(): String = formatSpeed(player.playbackParameters.speed)

    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player.release()
    }

    private fun formatSpeed(speed: Float): String {
        val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
        return "${text}x"
    }

    private fun formatMs(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    private companion object {
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}
