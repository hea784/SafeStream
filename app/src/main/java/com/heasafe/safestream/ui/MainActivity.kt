package com.heasafe.safestream.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heasafe.safestream.R
import com.heasafe.safestream.core.Bridge
import com.heasafe.safestream.core.UrlGuard
import com.heasafe.safestream.data.HistoryStore
import com.heasafe.safestream.databinding.ActivityMainBinding
import com.heasafe.safestream.model.VideoItem
import com.heasafe.safestream.sandbox.WebHostActivity
import org.json.JSONArray

/**
 * 主界面。地址栏 + 播放列表 + 原生播放器。
 *
 * 本进程从不渲染远端页面，只接收沙箱上报的视频列表。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var history: HistoryStore
    private lateinit var adapter: PlaylistAdapter

    private var player: ExoPlayer? = null
    private var videos: List<VideoItem> = emptyList()
    private var currentIndex = RecyclerView.NO_POSITION

    /**
     * 当前已交给播放器的地址。
     *
     * 扫描脚本的 MutationObserver 在广告/懒加载频繁改动的页面上会持续上报，
     * 每次都重建播放列表会让 ExoPlayer 反复 setMediaItem + prepare，画面不停闪。
     * 用这个字段做去重：同地址不重启。
     */
    private var playingUrl: String? = null
    private var sandboxRunning = false
    private var filterEnabled = true
    private var blockedCount = 0

    /** 最近一次交给沙箱的地址，"浏览"标签复用它。 */
    private var lastSandboxUrl: String? = null

    private val sandboxReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Bridge.ACTION_VIDEOS_FOUND ->
                    intent.getStringExtra(Bridge.EXTRA_VIDEO_JSON)?.let { json ->
                        // 沙箱里点了视频：先播它，再把沙箱收掉让本界面回到前台
                        if (intent.getStringExtra(Bridge.EXTRA_MESSAGE) == "PLAY_NOW") {
                            playNow(json)
                        } else {
                            onVideosFound(json)
                        }
                    }

                Bridge.ACTION_PAGE_TITLE ->
                    intent.getStringExtra(Bridge.EXTRA_TITLE)?.let { title ->
                        history.rememberVisit(binding.urlInput.text?.toString().orEmpty(), title)
                    }

                Bridge.ACTION_SECURITY_EVENT -> onSecurityEvent(
                    intent.getStringExtra(Bridge.EXTRA_MESSAGE).orEmpty(),
                )

                Bridge.ACTION_NAVIGATE -> {
                    // 沙箱搜索栏回车：走和主界面一样的校验流程，不在沙箱里自行放行
                    val target = intent.getStringExtra(Bridge.EXTRA_URL).orEmpty()
                    binding.urlInput.setText(target)
                    submitUrl()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        history = HistoryStore(this)
        adapter = PlaylistAdapter { index, item -> playAt(index, item) }
        binding.playlistList.adapter = adapter

        // 没有"加载"按钮：搜索栏回车即提交，少一次点击
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO
            ) { submitUrl(); true } else false
        }
        binding.shieldButton.setOnClickListener { toggleFilter() }
        binding.shieldButton.setOnLongClickListener { purgeEverything(); true }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_play -> true
                R.id.nav_browse -> { openBrowser(); false }
                R.id.nav_shield -> { showShieldSheet(); false }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_play

        updateShieldUi()
        updatePlaylistUi()
        registerSandboxReceiver()

        player = ExoPlayer.Builder(this).build().also {
            binding.playerView.player = it
            it.addListener(playerListener)
        }
    }

    override fun onStop() {
        super.onStop()
        // 播放进度落盘，下次从断点继续
        val p = player ?: return
        val item = videos.getOrNull(currentIndex)
        if (item != null && p.isPlaying) {
            history.saveProgress(item.url, p.currentPosition, p.duration)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sandboxReceiver) }
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    /** 地址栏 -> UrlGuard -> 沙箱。 */
    private fun submitUrl() {
        val raw = binding.urlInput.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) return

        // 输入的如果不是网址，就当搜索词处理，省得用户自己拼搜索引擎
        if (looksLikeKeyword(raw)) {
            startSandbox(SEARCH_PREFIX + java.net.URLEncoder.encode(raw, "UTF-8"))
            return
        }

        when (val verdict = UrlGuard.inspect(raw)) {
            is UrlGuard.Result.Rejected ->
                toast(verdict.reason)

            is UrlGuard.Result.Secure -> startSandbox(verdict.normalized)

            is UrlGuard.Result.Insecure -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.warn_insecure_title)
                .setMessage(getString(R.string.warn_insecure_body, verdict.normalized))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.warn_insecure_ok) { _, _ ->
                    // 把用户确认的 host 传给沙箱：只有它被允许走明文
                    startSandbox(verdict.normalized, java.net.URI(verdict.normalized).host)
                }
                .show()
        }
    }

    /** 像域名/带 scheme 的算网址，否则当搜索词。 */
    private fun looksLikeKeyword(input: String): Boolean {
        if (input.contains("://")) return false
        if (input.contains(' ')) return true
        // 有空格或没有点号，就是搜索词；有扩展名样式则按网址处理
        val looksLikeHost = input.substringBefore('/').contains('.')
        return !looksLikeHost
    }

    /** 浏览标签：把沙箱切到前台。网页必须在 :sandbox 进程，所以是另一个 Activity。 */
    private fun openBrowser() {
        val url = binding.urlInput.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: lastSandboxUrl
            ?: return
        startActivity(Intent(this, WebHostActivity::class.java)
            .putExtra(Bridge.EXTRA_URL, url)
            .putExtra(Bridge.EXTRA_ENABLED, filterEnabled))
    }

    /** 防护标签：把防护相关的操作收进底部弹层，不占用主界面。 */
    private fun showShieldSheet() {
        val blocked = if (blockedCount > 0) "本次已拦截 $blockedCount 个跟踪/广告请求" else "本次尚未拦截到请求"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_shield)
            .setMessage(
                buildString {
                    append("广告与跟踪拦截：")
                    append(if (filterEnabled) "已开启" else "已关闭（不推荐）")
                    append("\n")
                    append(blocked)
                    append("\n\n沙箱进程：")
                    append(if (sandboxRunning) "运行中" else "未运行")
                    append("\n\n长按顶部的盾牌图标可清理全部本地数据并停止沙箱。")
                },
            )
            .setNeutralButton(if (filterEnabled) R.string.action_stop else R.string.action_shield) { _, _ ->
                toggleFilter()
            }
            .setPositiveButton(R.string.action_clear) { _, _ -> purgeEverything() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startSandbox(url: String) {
        startSandbox(url, insecureHostAllowed = null)
    }

    private fun startSandbox(url: String, insecureHostAllowed: String?) {
        stopSandbox()
        blockedCount = 0
        playingUrl = null
        lastSandboxUrl = url
        videos = emptyList()
        adapter.submitList(emptyList())
        currentIndex = RecyclerView.NO_POSITION
        updatePlaylistUi()

        val intent = Intent(this, WebHostActivity::class.java)
            .putExtra(Bridge.EXTRA_URL, url)
            .putExtra(Bridge.EXTRA_ENABLED, filterEnabled)
            .putExtra(Bridge.EXTRA_INSECURE_HOST, insecureHostAllowed)
        startActivity(intent)
        sandboxRunning = true
        history.rememberVisit(url, url)
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
        updateShieldUi()
    }

    private fun stopSandbox() {
        if (!sandboxRunning) return
        sendControl(Bridge.ACTION_PURGE)
        sandboxRunning = false
    }

    /** 长按防护按钮 = 清理全部本地数据并停沙箱。 */
    private fun purgeEverything() {
        sendControl(Bridge.ACTION_PURGE)
        history.clearAll()
        blockedCount = 0
        videos = emptyList()
        adapter.submitList(emptyList())
        currentIndex = RecyclerView.NO_POSITION
        sandboxRunning = false
        binding.statusLine.text = getString(R.string.sandbox_cleaned)
        updatePlaylistUi()
        toast(getString(R.string.sandbox_cleaned))
    }

    private fun toggleFilter() {
        filterEnabled = !filterEnabled
        sendControl(Bridge.ACTION_SET_FILTER) {
            putExtra(Bridge.EXTRA_ENABLED, filterEnabled)
        }
        sendControl(Bridge.ACTION_RESCAN)
        updateShieldUi()
        toast(if (filterEnabled) "已开启广告与跟踪拦截" else "已关闭拦截（不推荐）")
    }

    private fun onVideosFound(json: String) {
        val arr = JSONArray(json)
        val parsed = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { VideoItem.fromJson(it) }
                ?.let(parsed::add)
        }
        if (parsed.isEmpty()) return

        // 按 URL 合并而不是整体替换：扫描脚本会反复上报同一批视频，
        // 整体替换会把播放列表清空又填回，用户看到的是条目不停闪。
        val merged = LinkedHashMap<String, VideoItem>()
        videos.forEach { merged[it.url] = it }
        parsed.forEach { merged.putIfAbsent(it.url, it) }
        val next = merged.values.toList()
        if (next == videos) return

        videos = next
        adapter.submitList(next)
        updatePlaylistUi()

        // 只有一个视频时直接开始，省一次点击；已经在播同一个就不重启
        val only = next.singleOrNull()
        if (only != null && only.url != playingUrl) playAt(0, only)
    }

    /** 沙箱里点了视频：不进列表，直接播。 */
    private fun playNow(json: String) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val item = VideoItem.fromJson(arr.optString(i)) ?: continue
            videos = listOf(item)
            adapter.submitList(videos)
            updatePlaylistUi()
            playAt(0, item)
            return
        }
    }

    private fun onSecurityEvent(message: String) {
        if (message == "PURGED") return
        if (message.startsWith("已拦截")) blockedCount++
        binding.statusLine.text = buildString {
            append(if (sandboxRunning) "沙箱运行中" else "沙箱已停止")
            if (blockedCount > 0) append(" · 已拦截 $blockedCount 个跟踪请求")
            append(" · ")
            append(message)
        }
    }

    private fun playAt(index: Int, item: VideoItem) {
        if (!item.isPlayable) {
            toast(getString(R.string.no_playable_source))
            return
        }
        // 同地址已在播放/加载中就不重建 MediaItem，否则画面会反复重置闪烁
        if (item.url == playingUrl) return

        Log.d(LOG_TAG, "playAt 重建播放: " + item.url)
        playingUrl = item.url
        currentIndex = index
        adapter.playingIndex = index

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

        player?.apply {
            setMediaItem(mediaItem)
            val resumeAt = history.readProgress(item.url)
            if (resumeAt > 3_000) {
                seekTo(resumeAt)
                toast(getString(R.string.resume_toast, formatMs(resumeAt)))
            }
            prepare()
            playWhenReady = true
        }
        binding.playerView.requestFocus()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            toast("播放失败：${error.errorCodeName}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            val item = videos.getOrNull(currentIndex) ?: return
            if (isPlaying) {
                history.saveProgress(item.url, p.currentPosition, p.duration)
            }
        }
    }

    private fun sendControl(action: String, build: Intent.() -> Unit = {}) {
        if (!sandboxRunning) return
        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .putExtra(Bridge.EXTRA_ENABLED, filterEnabled)
                .apply(build),
        )
    }

    private fun registerSandboxReceiver() {
        val filter = IntentFilter().apply {
            addAction(Bridge.ACTION_VIDEOS_FOUND)
            addAction(Bridge.ACTION_PAGE_TITLE)
            addAction(Bridge.ACTION_SECURITY_EVENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sandboxReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sandboxReceiver, filter)
        }
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
            getString(R.string.playlist_title_found_none)
        } else {
            getString(R.string.playlist_title, videos.count { it.isPlayable })
        }
        // 列表区域常驻，空状态用叠加文字提示，避免隐藏列表把标题挤到底部
        binding.emptyState.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        if (!sandboxRunning && binding.statusLine.text.isNullOrBlank()) {
            binding.statusLine.text = getString(R.string.history_empty)
        }
    }

    private fun formatMs(ms: Long): String =
        "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    /** 倍速循环：1.0 -> 1.25 -> 1.5 -> 2.0 -> 0.75 -> 1.0 */
    private fun cycleSpeed() {
        val p = player ?: return
        val idx = SPEEDS.indexOfFirst { it == p.playbackParameters.speed }.takeIf { it >= 0 } ?: 0
        val next = SPEEDS[(idx + 1) % SPEEDS.size]
        p.setPlaybackSpeed(next)
        binding.speedButton.text = "%.2gx".format(next).replace(".00x", "x").replace("0x", "x")
        toast(getString(R.string.speed_label, "${next}x"))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val LOG_TAG = "SafeStream"

        /** 输入的不是网址时，按搜索词拼到这个前缀后交给沙箱。 */
        const val SEARCH_PREFIX = "https://www.bing.com/search?q="
    }
}

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
