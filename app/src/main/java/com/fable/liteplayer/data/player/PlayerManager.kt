package com.fable.liteplayer.data.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.fable.liteplayer.domain.repository.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var exoPlayer: ExoPlayer? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        // Ultra-optimized LoadControl for zero-lag performance
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,    // minBufferMs - instant playback start
                10000,  // maxBufferMs - reduced from 50s default
                500,    // bufferForPlaybackMs
                1000    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true) // Prioritize responsiveness
            .build()

        // Hardware-accelerated rendering
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // Track selector for optimal quality selection
        val trackSelector = DefaultTrackSelector(context)

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_IDLE -> _playbackState.value = PlaybackState.Idle
                            Player.STATE_BUFFERING -> _playbackState.value = PlaybackState.Buffering
                            Player.STATE_READY -> {
                                val position = currentPosition
                                val duration = duration
                                _playbackState.value = if (isPlaying) {
                                    PlaybackState.Playing(position, duration)
                                } else {
                                    PlaybackState.Paused(position, duration)
                                }
                            }
                            Player.STATE_ENDED -> _playbackState.value = PlaybackState.Idle
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        _playbackState.value = PlaybackState.Error(error.message ?: "Unknown error")
                    }
                })
            }
    }

    fun play(url: String) {
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
    }

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }

    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0L
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}
