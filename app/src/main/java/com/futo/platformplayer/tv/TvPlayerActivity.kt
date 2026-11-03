package com.futo.platformplayer.tv

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
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

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) loading.visibility = View.GONE
            if (state == Player.STATE_ENDED) { saveHistory(); showUpNext() }
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

        player.attach()
        player.exoPlayer?.player?.addListener(listener)
        load(intent.getStringExtra(EXTRA_URL) ?: return finish())
    }

    private fun load(url: String) {
        hideUpNext()
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
                    val v = VideoHelper.selectBestVideoSource(details.video, Settings.instance.playback.getCurrentPreferredQualityPixelCount(), FutoVideoPlayerBase.PREFERED_VIDEO_CONTAINERS)
                    val a = VideoHelper.selectBestAudioSource(details.video, FutoVideoPlayerBase.PREFERED_AUDIO_CONTAINERS, Settings.instance.playback.getPrimaryLanguage(this@TvPlayerActivity))
                    require(v != null || a != null) { "no playable source" }
                    val resumeSec = StateHistory.instance.getHistoryPosition(details.url)
                    val resume = resumeSec > 10 && (details.duration <= 0 || resumeSec < details.duration - 30)
                    player.setSource(v, a, play = true, resume = resume)
                    if (resume) player.seekTo(resumeSec * 1000)
                }
                player.playerView.showController()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(TAG, "Failed to play $url", e)
                loading.visibility = View.VISIBLE
                findViewById<View>(R.id.tv_player_spinner).visibility = View.GONE
                message.text = getString(R.string.tv_player_failed, e.message ?: e.javaClass.simpleName)
                if (TvQueue.peek() != null) handler.postDelayed({ showUpNext() }, 2500)
            }
        }
    }

    private fun bindTitle(d: IPlatformVideoDetails) {
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
        if (event.action == KeyEvent.ACTION_DOWN && upNext.visibility != View.VISIBLE) {
            val exo = player.exoPlayer?.player
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { exo?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo?.pause(); return true }
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

    override fun onStop() {
        super.onStop()
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

    companion object {
        private const val TAG = "TvPlayerActivity"
        const val EXTRA_URL = "url"
        private const val UP_NEXT_SECONDS = 10
    }
}
