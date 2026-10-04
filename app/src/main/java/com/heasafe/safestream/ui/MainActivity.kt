package com.heasafe.safestream.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.heasafe.safestream.R
import com.heasafe.safestream.showCrashIfAny
import com.heasafe.safestream.data.HistoryStore
import com.heasafe.safestream.databinding.ActivityMainBinding
import com.heasafe.safestream.model.VideoItem
import com.heasafe.safestream.sandbox.WebSandbox
import com.heasafe.safestream.store.PlaylistStore
import com.heasafe.safestream.submit.SearchEngine
import com.heasafe.safestream.submit.UrlSubmission
import org.json.JSONArray

// 唯一界面：顶部搜索栏 + 播放页/浏览页 + 底部导航。
// 浏览页以前是另一个跑在 :sandbox 进程的 Activity，靠 Intent 广播来回传数据；
// 现在 WebView 与播放器同处一个界面，点网页里的视频直接切到播放页，
// 不再有"两个界面连不上"的问题，也不再需要那套广播协议。
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var history: HistoryStore
    private lateinit var securityLog: com.heasafe.safestream.core.SecurityLog
    private lateinit var adapter: PlaylistAdapter
    private lateinit var sandbox: WebSandbox
    private lateinit var player: androidx.media3.exoplayer.ExoPlayer

    private val submission = UrlSubmission()
    private val playlist = PlaylistStore()
    private lateinit var fullscreen: FullscreenController
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var sheet: BottomSheetDialog
    private lateinit var sheetAdapter: PlaylistAdapter

    private var videos: List<VideoItem> = emptyList()

    // 当前已交给播放器的地址，用于避免重复重建 MediaItem（会造成画面闪烁）
    private var playingUrl: String? = null

    private var lastSandboxUrl: String? = null
    private var insecureHostAllowed: String? = null
    private var pendingEpisode: VideoItem? = null

    // 正在播的那一集（选集页地址）。连播用它找下一集；媒体地址不是集，
    // 不能当连播的锚点。
    private var playingEpisode: VideoItem? = null
    private var sandboxRunning = false
    private var filterEnabled = true
    private var blockedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 全屏时返回键先退出全屏，而不是直接退出 App
        onBackPressedDispatcher.addCallback(this, backHandler)

        history = HistoryStore(this)
        securityLog = com.heasafe.safestream.core.SecurityLog()
        adapter = PlaylistAdapter(onClick = { index, item -> playAt(index, item) })
        sheetAdapter = PlaylistAdapter(
            onClick = { index, item ->
                sheet.dismiss()
                playAt(videos.indexOf(item), item)
            },
            metaOverride = { item ->
                when {
                    // 浮层行是剧集页地址：播放中对照 playingEpisode 而不是媒体地址
                    item.url == playingEpisode?.url -> getString(R.string.meta_playing)
                    history.hasOpened(item.url) -> getString(R.string.meta_watched)
                    else -> null
                }
            },
        )
        sheet = BottomSheetDialog(this)
        sheet.setContentView(R.layout.view_playlist_sheet)
        sheet.findViewById<RecyclerView>(R.id.sheetList)!!.adapter = sheetAdapter

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
        binding.shieldButton.setOnClickListener { showShieldPanel() }
        binding.shieldButton.setOnLongClickListener { purgeEverything(); true }
        binding.miniSpeed.setOnClickListener { cycleSpeed() }
        binding.miniToggle.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
        }
        progressHandler.post(progressTick)
        // 点状态行可重新展开，否则提示淡出后就看不到了
        binding.statusLine.setOnClickListener { showStatusBriefly() }
        binding.settingsButton.setOnClickListener { showSettings() }
        // 播放器由代码创建，才能在迷你条与全屏容器之间搬运
        playerView = androidx.media3.ui.PlayerView(this).apply {
            // 控制器只在全屏开（FullscreenController 切换）；迷你槽有自己的迷你栏
            useController = false
            setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        binding.miniVideoSlot.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        fullscreen = FullscreenController(
            activity = this,
            playerView = playerView,
            miniSlot = binding.miniVideoSlot,
            fullscreenContainer = binding.fullscreenContainer,
        )
        binding.miniPlayer.setOnClickListener { fullscreen.toggle() }
        binding.fabPlaylist.setOnClickListener { sheet.show() }

        updateShieldUi()
        updatePlaylistUi()
        binding.statusLine.text = getString(R.string.status_idle)
        reportWebViewCapability()
        showCrashIfAny()

        player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.addListener(playerListener)
        }
    }

    /**
     * 启动即告知当前 WebView 是否满足选集/视频发现的要求。
     *
     * 为什么必须开机就报：部分国产 ROM 会冻结自带旧版 WebView，
     * 而 addWebMessageListener 需要 WebView 88+。能力不足时视频发现会
     * 静默失效 —— 等用户浏览完才发现"列表一直是空的"，那时已经晚了。
     */
    private fun reportWebViewCapability() {
        val ok = androidx.webkit.WebViewFeature.isFeatureSupported(
            androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER,
        )
        val version = runCatching {
            androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this)?.versionName
        }.getOrNull().orEmpty()
        binding.statusLine.text = if (ok) {
            getString(R.string.capability_ok, version.ifBlank { "未知版本" })
        } else {
            getString(R.string.capability_weak, version.ifBlank { "未知版本" })
        }
        // 同样要自动淡出，否则启动这一行会永久留在顶部
        showStatusBriefly()
    }

    private fun submitUrl() {
        val raw = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        if (!submission.tryAcquire(raw, SystemClock.elapsedRealtime())) return

        when (val outcome = UrlSubmission.resolve(raw, searchEngine)) {
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
        playingEpisode = null
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
        binding.browseHint.visibility = View.GONE
    }

    private fun openSandbox() {
        val url = lastSandboxUrl ?: return
        sandbox.load(url, insecureHostAllowed)
        sandboxRunning = true
    }

    private fun onSecurityEvent(message: String) {
        if (message.startsWith("已拦截")) blockedCount++
        securityLog.record(message)
        binding.statusLine.text = buildString {
            append(if (sandboxRunning) "沙箱运行中" else "沙箱已停止")
            if (blockedCount > 0) append(" · 已拦截 $blockedCount 个跟踪请求")
            append(" · ")
            append(message)
        }
        showStatusBriefly()
    }

    /**
     * 状态行只作提示，几秒后自己淡出，点它才重新展开。
     *
     * 真机反馈：这行原本永久占着顶部，看剧时一直挡视野 —— 提示应该是
     * "出现一下就消失"，不是常驻控件。
     */
    private fun showStatusBriefly() {
        binding.statusLine.visibility = View.VISIBLE
        binding.statusLine.alpha = 1f
        statusHide?.removeCallbacks(hideStatus)
        statusHide?.postDelayed(hideStatus, STATUS_VISIBLE_MS)
    }

    private val statusHide = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideStatus = Runnable {
        binding.statusLine.animate().alpha(0f).setDuration(300).withEndAction {
            binding.statusLine.visibility = View.GONE
        }.start()
    }

    /** 防护面板（借鉴 Brave Shields）：本次会话的安全事件明细 + 开关 + 一键清理。 */
    private fun showShieldPanel() {
        val panel = BottomSheetDialog(this)
        panel.setContentView(R.layout.view_shield_sheet)
        panel.findViewById<TextView>(R.id.shieldCount)!!.text =
            getString(R.string.shield_count, securityLog.count())
        val events = panel.findViewById<RecyclerView>(R.id.shieldList)!!
        events.adapter = ShieldEventsAdapter(securityLog.all())
        panel.findViewById<TextView>(R.id.shieldEmpty)!!.visibility =
            if (securityLog.count() == 0) View.VISIBLE else View.GONE
        val sw = panel.findViewById<Switch>(R.id.shieldSwitch)!!
        sw.isChecked = filterEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked != filterEnabled) toggleFilter()
        }
        panel.findViewById<Button>(R.id.shieldClear)!!.setOnClickListener {
            panel.dismiss()
            purgeEverything()
        }
        panel.show()
    }

    private inner class ShieldEventsAdapter(private var items: List<String>) :
        RecyclerView.Adapter<ShieldEventsAdapter.VH>() {

        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_security, parent, false) as TextView)

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.text.text = items[position]
        }
    }

    private fun toggleFilter() {
        filterEnabled = !filterEnabled
        sandbox.filterEnabled = filterEnabled
        updateShieldUi()
        toast(if (filterEnabled) "已开启广告与跟踪拦截" else "已关闭拦截（不推荐）")
    }

    private val settingsPrefs
        get() = getSharedPreferences("safestream_settings", MODE_PRIVATE)

    private var searchEngine: SearchEngine
        get() = SearchEngine.fromName(settingsPrefs.getString("engine", null))
        set(v) = settingsPrefs.edit().putString("engine", v.name).apply()

    /** 设置：搜索引擎、拦截状态、清理数据。之前这些没有入口，只能改代码。 */
    private fun showSettings() {
        val engines = SearchEngine.entries.toTypedArray()
        val current = engines.indexOf(searchEngine)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setSingleChoiceItems(
                engines.map { it.label }.toTypedArray(),
                current,
            ) { _, which ->
                searchEngine = engines[which]
                toast("搜索引擎：" + engines[which].label)
            }
            .setNeutralButton(R.string.settings_clear) { _, _ -> purgeEverything() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun purgeEverything() {
        sandbox.purge()
        sandboxRunning = false
        blockedCount = 0
        videos = emptyList()
        adapter.submitList(emptyList())
        history.clearAll()
        securityLog.clear()
        playingUrl = null
        playingEpisode = null
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
                // 「看过」按剧集页地址记账（浮层行就是页地址）
                history.markOpened(waiting.url)
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
        playAt(videos.indexOf(target), target)
    }

    private fun playEpisode(item: VideoItem) {
        if (item.url == lastSandboxUrl) {
            val found = videos.firstOrNull { !it.isEpisode && it.isPlayable }
            if (found != null) {
                playingEpisode = item
                playAt(videos.indexOf(found), found)
                return
            }
        }
        loadUrl(item.url, insecureHostAllowed)
        // 必须在 loadUrl 之后设置：loadUrl 开头会清掉播放会话状态，
        // 之前放在前面导致自动播放在任何情况下都不会触发（pendingEpisode 同坑）。
        pendingEpisode = item
        playingEpisode = item
        toast("正在加载第 ${item.episodeNo} 集")
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
        // 全屏的唯一入口是迷你播放器，它默认 gone —— 不在这里点明就永远进不去全屏
        updateMiniPlayer()
    }

    private val playerListener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val drm = sandbox.drmSystem
            toast(
                if (drm != null) {
                    "该视频经过 $drm 加密，无法播放"
                } else {
                    "播放失败：${error.errorCodeName}"
                },
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateMiniToggle()
            if (!isPlaying) return
            // 进度归属用地址不用索引：列表会因选集合并而重排，
            // 索引在重排后指向别的条目（实测进度被记到选集页地址上）
            val url = playingUrl ?: return
            history.saveProgress(url, player.currentPosition, player.duration)
        }

        override fun onPlaybackStateChanged(state: Int) {
            updateMiniToggle()
            if (state != androidx.media3.common.Player.STATE_ENDED) return
            val current = playingEpisode ?: return
            val next = PlaylistStore.nextEpisode(videos, current.url) ?: return
            binding.statusLine.text = getString(R.string.autoplay_next, next.episodeNo)
            showStatusBriefly()
            playEpisode(next)
        }
    }

    private fun cycleSpeed() {
        val idx = SPEEDS.indexOfFirst { it == player.playbackParameters.speed }
            .takeIf { it >= 0 } ?: 0
        val next = SPEEDS[(idx + 1) % SPEEDS.size]
        player.setPlaybackSpeed(next)
        val label = formatSpeed(next)
        binding.miniSpeed.text = label
        binding.miniSpeed.visibility = View.VISIBLE
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
        // 连续剧只显示选集；数字与用户实际看到的条目一致
        val visible = PlaylistStore.visibleItems(videos)
        val n = visible.size
        // 小圆按钮放不下文字，用无障碍描述承载数量
        binding.fabPlaylist.contentDescription =
            getString(R.string.fab_count, n) + "，" + getString(R.string.fab_desc)
        binding.fabPlaylist.visibility = if (videos.isEmpty()) View.INVISIBLE else View.VISIBLE
        sheetAdapter.submitList(visible) {
            // 条目没变但备注会变（播放中/看过），DiffUtil 比不出 ViewItem 之外的状态，
            // 小列表直接全量重绑，保证标记即时刷新
            sheetAdapter.notifyDataSetChanged()
        }
        sheetAdapter.playingIndex = visible.indexOfFirst { it.url == playingUrl }
        sheet.findViewById<android.widget.TextView>(R.id.sheetTitle)!!.text =
            getString(R.string.playlist_title, n)
        sheet.findViewById<android.widget.TextView>(R.id.sheetEmpty)!!.visibility =
            if (videos.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 有东西在播时才出现迷你播放器，点它进全屏。 */
    private fun updateMiniPlayer() {
        val item = playingItem()
        binding.miniPlayer.visibility = if (item != null) View.VISIBLE else View.GONE
        binding.miniTitle.text = item?.title.orEmpty()
        updateMiniToggle()
        if (!fullscreen.isFullscreen) {
            binding.miniSpeed.visibility = if (item != null) View.VISIBLE else View.GONE
            binding.miniSpeed.text = formatSpeed(player.playbackParameters.speed)
        }
    }

    /** 迷你条上的播放/暂停按钮与系统状态保持一致。 */
    private fun updateMiniToggle() {
        val playing = player.isPlaying
        binding.miniToggle.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.miniToggle.contentDescription =
            getString(if (playing) R.string.mini_pause else R.string.mini_play)
    }

    /** 迷你条底部进度条：半秒一跳；暂停时不刷新——进度本来就停着，
     * 而持续 invalidate 会让界面永远不 idle（无障碍与 uiautomator 全被拖死）。 */
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
            progressHandler.postDelayed(this, 500)
        }
    }

    /** 正在播的条目，按地址找而不是按位置找（列表会重排）。 */
    private fun playingItem(): VideoItem? = videos.firstOrNull { it.url == playingUrl }

    private fun formatMs(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        val url = playingUrl ?: return
        if (player.isPlaying) {
            history.saveProgress(url, player.currentPosition, player.duration)
        }
    }

    private val backHandler = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (fullscreen.isFullscreen) {
                fullscreen.toggle()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        progressHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player.release()
        // 恢复安全契约第 14 条「退出即清」：架构合并时被静默丢掉过。
        // 必须在 destroy() 之前——之后 WebView 已销毁，purge 里的清缓存会炸。
        sandbox.purge()
        sandbox.destroy()
        super.onDestroy()
    }

    private companion object {
        /** 状态行可见时长：提示而已，不该常驻挡视野。 */
        const val STATUS_VISIBLE_MS = 3500L
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}
