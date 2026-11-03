package com.futo.platformplayer.tv

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.futo.platformplayer.R
import com.futo.platformplayer.states.StatePlayer
import com.futo.platformplayer.views.video.FutoVideoPlayerBase

/** Full-screen player for TV: the shared ExoPlayer, a D-pad controller, no touch gestures. */
@OptIn(UnstableApi::class)
class TvVideoPlayer(context: Context, attrs: AttributeSet? = null) : FutoVideoPlayerBase(STATE_NAME, context, attrs) {
    val playerView: PlayerView

    init {
        LayoutInflater.from(context).inflate(R.layout.tv_player_view, this, true)
        playerView = findViewById(R.id.tv_player_view)
        playerView.controllerShowTimeoutMs = 4000
        playerView.setShowFastForwardButton(true)
        playerView.setShowRewindButton(true)
        // Captions follow the size and style chosen in the TV's accessibility settings.
        playerView.subtitleView?.apply { setUserDefaultStyle(); setUserDefaultTextSize() }
    }

    fun attach() {
        val manager = StatePlayer.instance.getPlayerOrCreate(context)
        manager.attach(playerView, STATE_NAME)
        changePlayer(manager)
    }

    fun detach() {
        exoPlayer?.detach()
        changePlayer(null)
    }

    companion object { const val STATE_NAME = "TvPlayer" }
}
