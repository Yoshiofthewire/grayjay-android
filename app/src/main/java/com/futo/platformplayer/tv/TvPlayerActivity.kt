package com.futo.platformplayer.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.futo.platformplayer.stores.StringStorage
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.api.media.models.subtitles.ISubtitleSource
import androidx.media3.common.C
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.models.video.IPlatformVideoDetails
import com.futo.platformplayer.helpers.VideoHelper
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.toHumanNowDiffString
import com.futo.platformplayer.views.video.FutoVideoPlayerBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class TvPlayerActivity : AppCompatActivity() {
    private lateinit var player: TvVideoPlayer
    private lateinit var loading: View
    private lateinit var message: TextView
    private lateinit var upNext: View
    private val handler = Handler(Looper.getMainLooper())
    private var video: IPlatformVideoDetails? = null
    private var loadJob: Job? = null
    private var countdown = 0
    private var backHidesControls = false
    private var currentUrl: String? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) loading.visibility = View.GONE
            if (state == Player.STATE_ENDED) { saveHistory(); showUpNext() }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // The base player reloads these live-stream errors itself.
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                error.cause is androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker.PlaylistStuckException) return
            Logger.w(TAG, "Playback error ${error.errorCodeName}", error)
            showError()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Keep the screen on only while something is actually playing.
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); saveHistory() }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(StateApp.instance.getLocaleContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App state is owned by TvActivity; without it (process restored here) start over there.
        if (StateApp.instance.contextOrNull == null) { finish(); return }
        setContentView(R.layout.tv_player)
        player = findViewById(R.id.tv_player)
        loading = findViewById(R.id.tv_player_loading)
        message = findViewById(R.id.tv_player_message)
        upNext = findViewById(R.id.tv_upnext)
        findViewById<View>(R.id.tv_upnext_play).setOnClickListener { playNext() }
        findViewById<View>(R.id.tv_upnext_cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.tv_player_retry).setOnClickListener { currentUrl?.let(::load) }
        findViewById<View>(R.id.tv_player_next).setOnClickListener { playNext() }
        findViewById<View>(R.id.tv_player_back).setOnClickListener { finish() }
        player.playerView.findViewById<View>(R.id.tv_controls_settings)?.setOnClickListener { openSettings() }

        player.attach()
        player.exoPlayer?.player?.addListener(listener)
        load(intent.getStringExtra(EXTRA_URL) ?: return finish())
    }

    // singleTop: a new link from the phone replaces what is playing instead of stacking players.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        saveHistory()
        load(url)
    }

    private fun load(url: String) {
        currentUrl = url
        subtitle = null
        closeSettings()
        // The previous video stops now, so its end or errors can't act on the replacement.
        video = null
        player.exoPlayer?.player?.stop()
        hideUpNext()
        findViewById<View>(R.id.tv_player_actions).visibility = View.GONE
        player.playerView.useController = true
        player.playerView.findViewById<View>(R.id.tv_controls_channel)?.visibility = View.GONE
        loading.visibility = View.VISIBLE
        findViewById<View>(R.id.tv_player_spinner).visibility = View.VISIBLE
        message.setText(R.string.tv_player_loading)
        player.playerView.hideController()
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val details = withContext(Dispatchers.IO) { StatePlatform.instance.getContentDetails(url).await() }
                require(details is IPlatformVideoDetails) { "not a video" }
                video = details
                bindTitle(details)
                if (details.isLive && details.live != null) {
                    player.setSource(details.live, null, play = true)
                } else {
                    // Same choice as the phone: DASH/HLS when there is no plain file, else the best file near the preferred quality.
                    val v = player.getPreferredVideoSource(details, Settings.instance.playback.getCurrentPreferredQualityPixelCount())
                    val a = player.getPreferredAudioSource(details, Settings.instance.playback.getPrimaryLanguage(this@TvPlayerActivity))
                    require(v != null || a != null) { "no playable source" }
                    val resumeSec = StateHistory.instance.getHistoryPosition(details.url)
                    val resume = resumeSec > 10 && (details.duration <= 0 || resumeSec < details.duration - 30)
                    player.setSource(v, a, play = true, resume = resume)
                    if (resume) player.seekTo(resumeSec * 1000)
                }
                // "Sticky subtitles" (a phone setting): bring back the last caption language.
                if (Settings.instance.playback.stickySubtitles) subtitleLanguage.value.takeIf { it.isNotEmpty() }?.let { lang ->
                    player.getPreferredSubtitleSource(details, lang)?.let { player.swapSubtitles(it); subtitle = it }
                }
                player.playerView.showController()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(TAG, "Failed to play $url", e)
                showError()
            }
        }
    }

    /** Failed loads and stream errors wait for the viewer: retry, skip, or leave. Nothing auto-advances. */
    private fun showError() {
        player.playerView.hideController()
        // An idle player may auto-show its controls; keep their buttons from taking focus under the error.
        player.playerView.useController = false
        loading.visibility = View.VISIBLE
        findViewById<View>(R.id.tv_player_spinner).visibility = View.GONE
        message.setText(R.string.tv_player_failed)
        findViewById<View>(R.id.tv_player_actions).visibility = View.VISIBLE
        findViewById<View>(R.id.tv_player_next).visibility = if (TvQueue.peek() != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_player_retry).requestFocus()
    }

    private fun bindTitle(d: IPlatformVideoDetails) {
        val channel = player.playerView.findViewById<View>(R.id.tv_controls_channel)
        channel?.visibility = if (d.author.url.isNotBlank()) View.VISIBLE else View.GONE
        channel?.setOnClickListener {
            saveHistory()
            // TvActivity is singleTask: this closes the player and opens the channel there.
            startActivity(Intent(this, TvActivity::class.java).putExtra(TvActivity.EXTRA_CHANNEL, d.author.url))
        }
        player.playerView.findViewById<TextView>(R.id.tv_controls_title)?.text = d.name
        val source = d.id.pluginId?.let { id -> StatePlatform.instance.getAvailableClients().find { it.id == id }?.name }
        player.playerView.findViewById<TextView>(R.id.tv_controls_meta)?.text =
            listOfNotNull(d.author.name, source, d.datetime?.toHumanNowDiffString()?.let { "$it ago" }).joinToString(" · ")
    }

    // Up next

    private fun showUpNext() {
        val next = TvQueue.peek() ?: return finish()
        findViewById<TextView>(R.id.tv_upnext_title).text = next.name
        findViewById<TextView>(R.id.tv_upnext_meta).text = next.author.name
        (next as? com.futo.platformplayer.api.media.models.video.IPlatformVideo)?.thumbnails?.getHQThumbnail()?.let {
            Glide.with(this).load(it).into(findViewById<ImageView>(R.id.tv_upnext_thumb))
        }
        panel.visibility = View.GONE
        player.playerView.hideController()
        player.playerView.useController = false
        upNext.visibility = View.VISIBLE
        findViewById<View>(R.id.tv_upnext_play).requestFocus()
        countdown = UP_NEXT_SECONDS
        tick.run()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (upNext.visibility != View.VISIBLE) return
            if (countdown <= 0) { playNext(); return }
            findViewById<TextView>(R.id.tv_upnext_label).text = getString(R.string.tv_upnext_in, countdown)
            countdown--
            handler.postDelayed(this, 1000)
        }
    }

    private fun hideUpNext() {
        handler.removeCallbacks(tick)
        upNext.visibility = View.GONE
        player.playerView.useController = true
    }

    private fun playNext() {
        val next: IPlatformContent = TvQueue.take() ?: return finish()
        saveHistory()
        load(next.url)
    }

    // Keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (panel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) panelBack(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (findViewById<View>(R.id.tv_player_actions).visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && upNext.visibility != View.VISIBLE) {
            val exo = player.exoPlayer?.player
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { exo?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo?.pause(); return true }
            }
            // Controls hidden: left/right seek straight away, like YouTube, Netflix and Plex.
            if (!player.playerView.isControllerFullyVisible && exo != null && !exo.isCurrentMediaItemLive && exo.duration != androidx.media3.common.C.TIME_UNSET &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) SEEK_MS else -SEEK_MS
                exo.seekTo((exo.currentPosition + delta).coerceIn(0, exo.duration.coerceAtLeast(0)))
                player.playerView.showController()
                return true
            }
            // Controls hidden: OK toggles playback without first revealing the controller, like other TV apps.
            if (!player.playerView.isControllerFullyVisible && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                exo?.let { if (it.isPlaying) it.pause() else it.play() }
                player.playerView.showController()
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && upNext.visibility != View.VISIBLE) {
            // Decide on key-down, before PlayerView reveals the controls: visible -> hide them, hidden -> leave.
            if (event.action == KeyEvent.ACTION_DOWN) backHidesControls = player.playerView.isControllerFullyVisible
            if (!backHidesControls) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP) player.playerView.hideController()
            return true
        }
        return player.playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    // History

    private fun saveHistory() {
        val v = video ?: return
        val pos = player.exoPlayer?.player?.currentPosition ?: return
        if (v.isLive) return
        StateApp.instance.scopeOrNull?.launch(Dispatchers.IO) {
            try {
                val index = StateHistory.instance.getHistoryByVideo(v, true) ?: return@launch
                StateHistory.instance.updateHistoryPosition(v, index, true, pos / 1000, null, true)
            } catch (e: Throwable) {
                Logger.w(TAG, "Failed to save history", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvForeground.started(this)
    }

    override fun onStop() {
        super.onStop()
        TvForeground.stopped(this)
        if (!::player.isInitialized) return
        saveHistory()
        player.exoPlayer?.player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (!::player.isInitialized) return
        player.exoPlayer?.player?.removeListener(listener)
        player.exoPlayer?.player?.stop()
        player.detach()
    }

    // Quality and captions panel

    private val panel: View by lazy { findViewById(R.id.tv_settings_panel) }
    private val panelList: LinearLayout by lazy { findViewById(R.id.tv_settings_list) }
    private val subtitleLanguage = FragmentedStorage.get<StringStorage>("subtitleLanguage")
    private var subtitle: ISubtitleSource? = null
    private var panelPage = 0

    private class Option(val label: String, val selected: Boolean, val apply: suspend () -> Unit)

    private fun openSettings() {
        if (video == null) return
        player.playerView.hideController()
        player.playerView.useController = false
        panel.visibility = View.VISIBLE
        showSettingsRoot()
    }

    private fun closeSettings() {
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        player.playerView.useController = true
        player.playerView.showController()
        player.playerView.findViewById<View>(R.id.tv_controls_settings)?.requestFocus()
    }

    private fun panelBack() = if (panelPage != 0) showSettingsRoot() else closeSettings()

    private fun showSettingsRoot() {
        panelPage = 0
        findViewById<TextView>(R.id.tv_settings_title).setText(R.string.tv_player_settings)
        panelList.removeAllViews()
        val quality = qualityOptions()
        val first = panelList.addChoiceRow(TvChoice(getString(R.string.tv_quality), value = quality.firstOrNull { it.selected }?.label ?: getString(R.string.tv_auto)), false) { showOptions(R.string.tv_quality, quality, R.string.tv_quality_none) }
        panelList.addChoiceRow(TvChoice(getString(R.string.tv_captions), value = subtitle?.name ?: getString(R.string.tv_off)), false) { showOptions(R.string.tv_captions, captionOptions(), R.string.tv_captions_none) }
        first.post { first.requestFocus() }
    }

    private fun showOptions(title: Int, options: List<Option>, empty: Int) {
        panelPage = 1
        findViewById<TextView>(R.id.tv_settings_title).setText(title)
        panelList.removeAllViews()
        if (options.size <= 1) panelList.addView(TextView(this).apply { setTextAppearance(R.style.TvText_Body); setText(empty) })
        val rows = options.map { o -> panelList.addChoiceRow(TvChoice(o.label, selected = o.selected), true) {
            lifecycleScope.launch { try { o.apply() } catch (e: Throwable) { Logger.e(TAG, "Applying ${o.label} failed", e) }; showSettingsRoot() }
        } }
        (rows.getOrNull(options.indexOfFirst { it.selected }) ?: rows.firstOrNull())?.let { r -> r.post { r.requestFocus() } }
    }

    /**
     * Plain video files: pick another file at that height, keeping position and captions.
     * Streams (HLS/DASH, live): limit the player's adaptive choice to one height, or let it pick (Auto).
     */
    private fun qualityOptions(): List<Option> {
        val d = video ?: return listOf()
        val current = player.lastVideoSource
        val files = d.video.videoSources.toList()
        if (!d.isLive && current != null && current in files) {
            return files.filter { it.height > 0 }.groupBy { it.height }.toSortedMap(compareByDescending { it })
                .mapNotNull { (h, list) -> VideoHelper.selectBestVideoSource(list, -1, FutoVideoPlayerBase.PREFERED_VIDEO_CONTAINERS)?.let { h to it } }
                .map { (h, src) -> Option("${h}p", current.height == h) { player.swapSources(src, player.lastAudioSource, resume = true, play = true, keepSubtitles = true) } }
        }
        val exo = player.exoPlayer?.player
        val heights = exo?.currentTracks?.groups.orEmpty().filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }.filter { it > 0 }.distinct().sortedDescending()
        val target = player.targetTrackVideoHeight
        val playing = exo?.videoFormat?.height?.takeIf { it > 0 }
        val auto = Option(if (target <= 0 && playing != null) getString(R.string.tv_auto_now, "${playing}p") else getString(R.string.tv_auto), target <= 0) { player.selectVideoTrack(-1) }
        return listOf(auto) + heights.map { h -> Option("${h}p", target == h) { player.selectVideoTrack(h) } }
    }

    private fun captionOptions(): List<Option> {
        val d = video ?: return listOf()
        val tracks = d.subtitles.filter { it.format?.lowercase() in FutoVideoPlayerBase.SUPPORTED_SUBTITLES }
        val off = Option(getString(R.string.tv_off), subtitle == null) { setSubtitle(null) }
        return listOf(off) + tracks.map { t -> Option(t.name, subtitle?.url == t.url && subtitle?.name == t.name) { setSubtitle(t) } }
    }

    private suspend fun setSubtitle(s: ISubtitleSource?) {
        player.swapSubtitles(s)
        subtitle = s
        subtitleLanguage.setAndSave(s?.language ?: "")
    }

    companion object {
        private const val TAG = "TvPlayerActivity"
        const val EXTRA_URL = "url"
        private const val UP_NEXT_SECONDS = 10
        private const val SEEK_MS = 10_000L
    }
}
