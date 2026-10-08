package com.heasafe.safestream.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var playback: PlaybackSession

    private val submission = UrlSubmission()
    private val playlist = PlaylistStore()
    private lateinit var fullscreen: FullscreenController
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var endedOverlay: View
    private lateinit var suggestOverlay: ViewGroup
    private lateinit var suggestAdapter: UrlSuggestAdapter
    private lateinit var errorPage: ViewGroup

    /** 主框架加载失败：显示错误页（盖在 WebView 上），带上 WebView 报的原始原因。 */
    private fun showLoadError(detail: String) {
        errorPage.findViewById<TextView>(R.id.errorDetail).text =
            getString(R.string.load_failed_detail) + "\n\n" + detail
        errorPage.visibility = View.VISIBLE
    }

    private fun hideLoadError() {
        errorPage.visibility = View.GONE
    }
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
        // 主框架加载失败：给错误页 + 重试，而不是让用户面对黑屏猜
        sandbox.onLoadError = { detail -> showLoadError(detail) }
        // 用沙箱自己的 WebView 替换布局里的占位 ViewView
        binding.contentContainer.addView(
            sandbox.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // 错误页盖在 WebView 之上（同 View 树，show/hide 即可，无需动 WebView）
        errorPage = layoutInflater.inflate(
            R.layout.view_error_page,
            binding.contentContainer,
            false,
        ) as ViewGroup
        binding.contentContainer.addView(errorPage)
        errorPage.findViewById<Button>(R.id.retryButton).setOnClickListener {
            hideLoadError()
            lastSandboxUrl?.let { sandbox.load(it, insecureHostAllowed) }
        }

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO
            ) { submitUrl(); true } else false
        }
        setupUrlSuggestions()
        binding.shieldButton.setOnClickListener { showShieldPanel() }
        binding.shieldButton.setOnLongClickListener { confirmPurge(); true }
        binding.miniSpeed.setOnClickListener { playback.cycleSpeed() }
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
        // 播放结束覆盖层：盖在全屏容器上（迷你条此时只是缩略图，不需要它）。
        // 点重播 = 从头再播当前媒体；开始新播放或换页时隐藏。
        endedOverlay = layoutInflater.inflate(R.layout.view_ended_overlay, binding.fullscreenContainer, false)
        binding.fullscreenContainer.addView(endedOverlay)
        endedOverlay.findViewById<android.widget.ImageButton>(R.id.replayButton).setOnClickListener {
            hideEndedOverlay()
            val item = playingItem()
            if (item != null) {
                playback.play(item)
            } else {
                fullscreen.toggle()
            }
        }

        fullscreen = FullscreenController(
            activity = this,
            playerView = playerView,
            miniSlot = binding.miniVideoSlot,
            fullscreenContainer = binding.fullscreenContainer,
            // FAB 的 elevation 高于全屏容器，不隐藏会一直悬浮在视频上
            chromeViews = listOf(binding.topBar, binding.fabPlaylist),
            videoAspectRatio = { playback.videoAspectRatio },
        )
        // 全屏期间根布局不能吃导航条/刘海 inset：横屏时导航条 inset 落在左侧，
        // 系统栏隐藏后这个 padding 也不会重算，实测画面左侧留 48dp 死区。
        // 非全屏时交回 View 默认的 fitsSystemWindows 处理（状态栏/IME padding）。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            if (fullscreen.isFullscreen) {
                v.setPadding(0, 0, 0, 0)
                androidx.core.view.WindowInsetsCompat.CONSUMED
            } else {
                // 平时交回 View 默认的 fitsSystemWindows 处理（状态栏/导航条/IME padding）
                val platform = insets.toWindowInsets()
                if (platform == null) {
                    insets
                } else {
                    androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(
                        v.onApplyWindowInsets(platform),
                    )
                }
            }
        }
        binding.miniPlayer.setOnClickListener { fullscreen.toggle() }
        binding.fabPlaylist.setOnClickListener { sheet.show() }

        updateShieldUi()
        updatePlaylistUi()
        binding.statusLine.text = getString(R.string.status_idle)
        reportWebViewCapability()
        showCrashIfAny()

        playback = PlaybackSession(
            playerView = playerView,
            binding = binding,
            history = history,
            playingUrl = { playingUrl },
            drmSystem = { sandbox.drmSystem },
            onEnded = ::onPlaybackEnded,
            onEndedStateChanged = { ended -> if (ended) showEndedOverlay() else hideEndedOverlay() },
        )
    }

    /** 播完：全屏里出现重播入口，别让用户对着无声黑屏猜是卡住还是完了。 */
    private fun showEndedOverlay() {
        endedOverlay.visibility = View.VISIBLE
        endedOverlay.alpha = 0f
        endedOverlay.animate().alpha(1f).setDuration(250).start()
    }

    private fun hideEndedOverlay() {
        endedOverlay.visibility = View.GONE
        endedOverlay.alpha = 1f
    }

    /**
     * 本集播完：连播下一集（能找到下一集时）。
     *
     * 决策留在这一层——「要不要连播」取决于列表里有没有下一集
     * （PlaylistStore.nextEpisode，纯逻辑已单测），播放器只负责报告"播完了"。
     */
    private fun onPlaybackEnded() {
        val current = playingEpisode ?: return
        val next = PlaylistStore.nextEpisode(videos, current.url) ?: return
        binding.statusLine.text = getString(R.string.autoplay_next, next.episodeNo)
        showStatusBriefly()
        playEpisode(next)
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

    /**
     * 地址栏补全：聚焦且最近页面非空时，在网页上方弹出最近页面列表。
     *
     * 不用 AutoCompleteTextView：它的下拉是弹窗，与全屏沉浸切换会打架；
     * 直接把浮层挂在 contentContainer 顶层，点条目即加载，失焦即收。
     */
    private fun setupUrlSuggestions() {
        suggestAdapter = UrlSuggestAdapter { picked ->
            hideUrlSuggestions()
            binding.urlInput.setText(picked)
            binding.urlInput.setSelection(picked.length)
            // 走 submitUrl 而不是直接 loadUrl：明文地址需要过"该站点未加密"确认，
            // 直接 loadUrl(url, null) 会被 shouldBlockCleartext 静默拦成黑屏
            submitUrl()
        }
        suggestOverlay = layoutInflater.inflate(
            R.layout.view_url_suggestions,
            binding.contentContainer,
            false,
        ) as ViewGroup
        binding.contentContainer.addView(suggestOverlay)
        suggestOverlay.findViewById<RecyclerView>(R.id.suggestList).adapter = suggestAdapter
        // 用点击而不是焦点驱动显示：WebView 加载完成会 requestFocus 抢走焦点，
        // 焦点驱动的浮层会"闪现即消失"。点击意图明确 —— 点地址栏就是要输入。
        binding.urlInput.setOnClickListener {
            if (suggestOverlay.visibility == View.VISIBLE) {
                hideUrlSuggestions()
            } else {
                showUrlSuggestions()
            }
        }
        binding.urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideUrlSuggestions()
        }
        // 点网页即收起（WebView 自己消费触摸，这里只负责藏浮层）
        sandbox.view.setOnTouchListener { _, _ ->
            hideUrlSuggestions()
            false
        }
    }

    private fun showUrlSuggestions() {
        val pages = history.recentPages()
        if (pages.isEmpty()) return
        suggestAdapter.submit(pages)
        suggestOverlay.visibility = View.VISIBLE
    }

    private fun hideUrlSuggestions() {
        suggestOverlay.visibility = View.GONE
    }

    private fun submitUrl() {
        val raw = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        if (!submission.tryAcquire(raw, SystemClock.elapsedRealtime())) return
        // 提交即收起键盘：页面加载/警告对话框期间键盘挡着半屏，用户得手动收
        hideIme()
        hideUrlSuggestions()

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
        hideEndedOverlay()
        hideUrlSuggestions()
        hideLoadError()
        pendingEpisode = null
        playingEpisode = null
        playingUrl = null
        blockedCount = 0
        videos = emptyList()
        adapter.submitList(emptyList())
        updatePlaylistUi()

        insecureHostAllowed = insecureHost
        lastSandboxUrl = url
        history.markVisited(url)
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
        sandbox.filterEnabled = filterEnabled
        sandbox.load(url, insecureHost)
        sandboxRunning = true
        binding.browseHint.visibility = View.GONE
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
        ShieldPanel.show(
            activity = this,
            log = securityLog,
            filterEnabled = filterEnabled,
            onToggleFilter = ::toggleFilter,
            onClear = ::confirmPurge,
        )
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
            .setNeutralButton(R.string.settings_clear) { _, _ -> confirmPurge() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
    }

    /**
     * 清理数据是破坏性操作：清光播放进度、当前页面与防护记录。
     * 三个入口（盾牌面板按钮、长按盾牌、设置页）都先确认一次，防误触。
     */
    private fun confirmPurge() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.purge_confirm_title)
            .setMessage(R.string.purge_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.purge_confirm_ok) { _, _ -> purgeEverything() }
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
        hideEndedOverlay()
        playingUrl = item.url
        adapter.playingIndex = index
        playback.play(item)
        // 全屏的唯一入口是迷你播放器，它默认 gone —— 不在这里点明就永远进不去全屏
        updateMiniPlayer()
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
        // 连播期间扫描仍在上报，列表可能中途变化；全屏时 FAB 一律隐藏，
        // 否则 updatePlaylistUi 会把它重新浮到视频上
        binding.fabPlaylist.visibility =
            if (videos.isEmpty() || fullscreen.isFullscreen) View.INVISIBLE else View.VISIBLE
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
        playback.refreshToggleIcon()
        if (!fullscreen.isFullscreen) {
            binding.miniSpeed.visibility = if (item != null) View.VISIBLE else View.GONE
            binding.miniSpeed.text = playback.speedLabel()
        }
    }

    /** 正在播的条目，按地址找而不是按位置找（列表会重排）。 */
    private fun playingItem(): VideoItem? = videos.firstOrNull { it.url == playingUrl }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        val url = playingUrl ?: return
        val p = playback.player
        if (p.isPlaying) {
            history.saveProgress(url, p.currentPosition, p.duration)
        }
    }

    private val backHandler = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (fullscreen.isFullscreen) {
                fullscreen.toggle()
                return
            }
            // 用户在网页里点出来的导航历史先用返回键回退（App 自己驱动的换页
            // 已被 loadUrl 清出栈，这里只会回退到用户点过的页面），
            // 回退栈空了才真正退出 —— 退出会触发 onDestroy 的清数据契约。
            if (sandbox.view.canGoBack()) {
                sandbox.view.goBack()
                return
            }
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    override fun onDestroy() {
        playback.release()
        // 恢复安全契约第 14 条「退出即清」：架构合并时被静默丢掉过。
        // 必须在 destroy() 之前——之后 WebView 已销毁，purge 里的清缓存会炸。
        sandbox.purge()
        sandbox.destroy()
        super.onDestroy()
    }

    private companion object {
        /** 状态行可见时长：提示而已，不该常驻挡视野。 */
        const val STATUS_VISIBLE_MS = 3500L
    }
}
