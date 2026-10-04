package com.heasafe.safestream.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heasafe.safestream.R
import com.heasafe.safestream.data.HistoryStore
import com.heasafe.safestream.databinding.ActivityMainBinding
import com.heasafe.safestream.model.VideoItem
import com.heasafe.safestream.sandbox.WebSandbox
import com.heasafe.safestream.store.PlaylistStore
import com.heasafe.safestream.submit.UrlSubmission
import org.json.JSONArray

// 唯一界面：顶部搜索栏 + 播放页/浏览页 + 底部导航。
// 浏览页以前是另一个跑在 :sandbox 进程的 Activity，靠 Intent 广播来回传数据；
// 现在 WebView 与播放器同处一个界面，点网页里的视频直接切到播放页，
// 不再有"两个界面连不上"的问题，也不再需要那套广播协议。
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var history: HistoryStore
    private lateinit var adapter: PlaylistAdapter
    private lateinit var sandbox: WebSandbox
    private lateinit var player: androidx.media3.exoplayer.ExoPlayer

    private val submission = UrlSubmission()
    private val playlist = PlaylistStore()

    private var videos: List<VideoItem> = emptyList()
    private var currentIndex = RecyclerView.NO_POSITION

    // 当前已交给播放器的地址，用于避免重复重建 MediaItem（会造成画面闪烁）
    private var playingUrl: String? = null

    private var lastSandboxUrl: String? = null
    private var insecureHostAllowed: String? = null
    private var pendingEpisode: VideoItem? = null
    private var sandboxRunning = false
    private var filterEnabled = true
    private var blockedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        history = HistoryStore(this)
        adapter = PlaylistAdapter { index, item -> playAt(index, item) }
        binding.playlistList.adapter = adapter

        sandbox = WebSandbox(
            context = this,
            onVideosFound = ::onVideosFound,
            onEpisodesFound = ::onEpisodesFound,
            onSecurityEvent = ::onSecurityEvent,
            onTitle = { title ->
                binding.statusLine.text =
                    if (title.isBlank()) getString(R.string.status_idle) else title
            },
        )
        sandbox.filterEnabled = filterEnabled
        sandbox.onFatal = { binding.statusLine.text = getString(R.string.render_gone) }
        // 用沙箱自己的 WebView 替换布局里的占位 ViewView
        (binding.browsePage.parent as? ViewGroup)?.removeView(binding.browsePage)
        binding.contentContainer.addView(
            sandbox.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO
            ) { submitUrl(); true } else false
        }
        binding.shieldButton.setOnClickListener { toggleFilter() }
        binding.shieldButton.setOnLongClickListener { purgeEverything(); true }
        binding.speedButton.setOnClickListener { cycleSpeed() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_play -> { showPage(browse = false); true }
                R.id.nav_browse -> { showPage(browse = true); true }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_play

        updateShieldUi()
        updatePlaylistUi()
        binding.statusLine.text = getString(R.string.status_idle)

        player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build().also {
            binding.playerView.player = it
            it.addListener(playerListener)
        }
    }

    /** 切换播放页 / 浏览页。WebView 一直活着，切回来时状态不丢。 */
    private fun showPage(browse: Boolean) {
        binding.playPage.visibility = if (browse) View.GONE else View.VISIBLE
        sandbox.view.visibility = if (browse) View.VISIBLE else View.GONE
        if (browse) {
            val url = lastSandboxUrl
            if (url.isNullOrBlank()) {
                toast(getString(R.string.browse_need_url))
                binding.bottomNav.selectedItemId = R.id.nav_play
            } else if (!sandboxRunning) {
                openSandbox()
            }
        }
    }

    private fun submitUrl() {
        val raw = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        if (!submission.tryAcquire(raw, SystemClock.elapsedRealtime())) return

        when (val outcome = UrlSubmission.resolve(raw)) {
            is UrlSubmission.Outcome.Reject -> toast(outcome.reason)
            is UrlSubmission.Outcome.SearchUrl -> loadUrl(outcome.url, null)
            is UrlSubmission.Outcome.LoadUrl ->
                if (outcome.needsInsecureConfirm) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.warn_insecure_title)
                        .setMessage(getString(R.string.warn_insecure_body, outcome.url))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.warn_insecure_ok) { _, _ ->
                            loadUrl(outcome.url, outcome.insecureHost)
                        }
                        .show()
                } else {
                    loadUrl(outcome.url, null)
                }
        }
    }

    private fun loadUrl(url: String, insecureHost: String?) {
        pendingEpisode = null
        playingUrl = null
        blockedCount = 0
        videos = emptyList()
        adapter.submitList(emptyList())
        updatePlaylistUi()

        insecureHostAllowed = insecureHost
        lastSandboxUrl = url
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
        sandbox.filterEnabled = filterEnabled
        sandbox.load(url, insecureHost)
        sandboxRunning = true
        showPage(browse = true)
        binding.bottomNav.selectedItemId = R.id.nav_browse
    }

    private fun openSandbox() {
        val url = lastSandboxUrl ?: return
        sandbox.load(url, insecureHostAllowed)
        sandboxRunning = true
    }

    private fun onSecurityEvent(message: String) {
        if (message.startsWith("已拦截")) blockedCount++
        binding.statusLine.text = buildString {
            append(if (sandboxRunning) "沙箱运行中" else "沙箱已停止")
            if (blockedCount > 0) append(" · 已拦截 $blockedCount 个跟踪请求")
            append(" · ")
            append(message)
        }
    }

    private fun toggleFilter() {
        filterEnabled = !filterEnabled
        sandbox.filterEnabled = filterEnabled
        updateShieldUi()
        toast(if (filterEnabled) "已开启广告与跟踪拦截" else "已关闭拦截（不推荐）")
    }

    private fun purgeEverything() {
        sandbox.purge()
        sandboxRunning = false
        blockedCount = 0
        videos = emptyList()
        adapter.submitList(emptyList())
        history.clearAll()
        playingUrl = null
        binding.statusLine.text = getString(R.string.sandbox_cleaned)
        updatePlaylistUi()
        toast(getString(R.string.sandbox_cleaned))
    }

    private fun onVideosFound(json: String) {
        // 用户在网页里点了 blob 流：播本页已发现的媒体
        if (json == WebSandbox.PLAY_FOUND_MARKER) {
            playFirstDiscovered()
            return
        }
        val arr = JSONArray(json)
        val parsed = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            VideoItem.fromJson(arr.optString(i))?.let(parsed::add)
        }
        if (parsed.isEmpty()) return
        val next = playlist.mergeVideos(videos, parsed)
        if (next == videos) return
        videos = next
        adapter.submitList(next)
        updatePlaylistUi()

        // 选集场景：用户点了第 N 集，该页媒体到手后自动播
        val waiting = pendingEpisode
        if (waiting != null) {
            val media = next.firstOrNull { !it.isEpisode && it.isPlayable }
            if (media != null) {
                pendingEpisode = null
                playAt(next.indexOf(media), media)
            }
        }
    }

    private fun onEpisodesFound(json: String) {
        val arr = JSONArray(json)
        val parsed = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            VideoItem.fromJson(arr.optString(i))?.let(parsed::add)
        }
        if (parsed.isEmpty()) return
        val next = playlist.mergeEpisodes(videos, parsed)
        if (next == videos) return
        videos = next
        adapter.submitList(next)
        updatePlaylistUi()
    }

    private fun playFirstDiscovered() {
        val target = playlist.firstPlayable(videos)
        if (target == null) {
            toast(getString(R.string.no_playable_source))
            return
        }
        showPage(browse = false)
        binding.bottomNav.selectedItemId = R.id.nav_play
        playAt(videos.indexOf(target), target)
    }

    private fun playEpisode(item: VideoItem) {
        if (item.url == lastSandboxUrl) {
            val found = videos.firstOrNull { !it.isEpisode && it.isPlayable }
            if (found != null) {
                playAt(videos.indexOf(found), found)
                return
            }
        }
        pendingEpisode = item
        toast("正在加载第 ${item.episodeNo} 集")
        loadUrl(item.url, insecureHostAllowed)
    }

    private fun playAt(index: Int, item: VideoItem) {
        if (item.isEpisode) {
            playEpisode(item)
            return
        }
        if (!item.isPlayable) {
            toast(getString(R.string.no_playable_source))
            return
        }
        // 同地址已在播放/加载中就不重建 MediaItem，否则画面反复重置闪烁
        if (item.url == playingUrl) return
        playingUrl = item.url
        currentIndex = index
        adapter.playingIndex = index

        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setUri(item.url)
            .setMediaId(item.url)
            .setMimeType(
                when {
                    item.url.contains(".m3u8") ->
                        androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    item.url.contains(".mpd") ->
                        androidx.media3.common.MimeTypes.APPLICATION_MPD
                    item.mimeType.isNotBlank() -> item.mimeType
                    else -> null
                },
            )
            .build()

        player.setMediaItem(mediaItem)
        val resumeAt = history.readProgress(item.url)
        if (resumeAt > 3_000) {
            player.seekTo(resumeAt)
            toast(getString(R.string.resume_toast, formatMs(resumeAt)))
        }
        player.prepare()
        player.playWhenReady = true
    }

    private val playerListener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            toast("播放失败：${error.errorCodeName}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            val item = videos.getOrNull(currentIndex) ?: return
            history.saveProgress(item.url, player.currentPosition, player.duration)
        }
    }

    private fun cycleSpeed() {
        val idx = SPEEDS.indexOfFirst { it == player.playbackParameters.speed }
            .takeIf { it >= 0 } ?: 0
        val next = SPEEDS[(idx + 1) % SPEEDS.size]
        player.setPlaybackSpeed(next)
        val label = formatSpeed(next)
        binding.speedButton.text = label
        toast(getString(R.string.speed_label, label))
    }

    private fun formatSpeed(speed: Float): String {
        val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
        return "${text}x"
    }

    private fun updateShieldUi() {
        binding.shieldButton.setImageResource(
            if (filterEnabled) R.drawable.ic_shield_on else R.drawable.ic_shield_off,
        )
        binding.shieldButton.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (filterEnabled) R.color.ok else R.color.danger,
            ),
        )
        binding.shieldButton.alpha = if (filterEnabled) 1f else 0.6f
    }

    private fun updatePlaylistUi() {
        binding.listHeader.text = if (videos.isEmpty()) {
            getString(R.string.playlist_section)
        } else {
            getString(R.string.playlist_title, PlaylistStore.playableCount(videos))
        }
        binding.emptyState.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun formatMs(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        val item = videos.getOrNull(currentIndex) ?: return
        if (player.isPlaying) {
            history.saveProgress(item.url, player.currentPosition, player.duration)
        }
    }

    override fun onDestroy() {
        binding.playerView.player = null
        player.release()
        sandbox.destroy()
        super.onDestroy()
    }

    private companion object {
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}
