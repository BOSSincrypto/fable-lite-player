package com.fable.liteplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ultra-optimized ExoPlayer service for zero-lag video playback.
 *
 * Features:
 * - Hardware-accelerated rendering with decoder fallback
 * - Aggressive buffer configuration for instant seeking (2.5s-10s)
 * - Exact frame-accurate seeking
 * - Playback speed control (0.25x - 2.0x)
 * - Lifecycle-aware with automatic resource cleanup
 * - State management via Kotlin Flow
 */
@Singleton
class PlayerService @Inject constructor(
    @ApplicationContext private val context: Context
) : DefaultLifecycleObserver {

    private var exoPlayer: ExoPlayer? = null

    // State flows for reactive UI updates
    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Initialize ExoPlayer with ultra-optimized configuration.
     * Call this before using the player.
     */
    @OptIn(UnstableApi::class)
    fun initialize() {
        if (exoPlayer != null) return

        // Configure hardware-accelerated rendering with fallback
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }

        // Aggressive buffer configuration for zero-lag performance
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2500,  // minBufferMs: minimum stable buffer for instant seeking
                10000, // maxBufferMs: reduced from 50s default for lower memory and seek latency
                500,   // bufferForPlaybackMs: near-instant playback start
                1000   // bufferForPlaybackAfterRebufferMs: quick recovery
            )
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.EXACT) // Frame-accurate seeking
            .build()
            .apply {
                addListener(playerListener)
            }

        _playbackState.value = PlaybackState.Ready
    }

    /**
     * Prepare and load a local video file.
     *
     * @param videoPath Absolute path to local video file
     */
    fun loadVideo(videoPath: String) {
        val player = exoPlayer ?: run {
            _error.value = "Player not initialized. Call initialize() first."
            return
        }

        try {
            val mediaItem = MediaItem.fromUri("file://$videoPath")
            player.setMediaItem(mediaItem)
            player.prepare()
            _playbackState.value = PlaybackState.Buffering
            _error.value = null
        } catch (e: Exception) {
            _error.value = "Failed to load video: ${e.message}"
            _playbackState.value = PlaybackState.Error
        }
    }

    /**
     * Start or resume playback.
     */
    fun play() {
        exoPlayer?.play()
    }

    /**
     * Pause playback.
     */
    fun pause() {
        exoPlayer?.pause()
    }

    /**
     * Toggle play/pause state.
     */
    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) pause() else play()
        }
    }

    /**
     * Seek to specific position with exact frame accuracy.
     *
     * @param positionMs Target position in milliseconds
     */
    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    /**
     * Seek relative to current position.
     *
     * @param deltaMs Offset in milliseconds (positive for forward, negative for backward)
     */
    fun seekRelative(deltaMs: Long) {
        exoPlayer?.let {
            val targetPosition = (it.currentPosition + deltaMs).coerceIn(0, it.duration)
            it.seekTo(targetPosition)
        }
    }

    /**
     * Set playback speed.
     *
     * @param speed Playback speed multiplier (0.25 - 2.0)
     */
    fun setPlaybackSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0.25f, 2.0f)
        exoPlayer?.playbackParameters = PlaybackParameters(clampedSpeed)
        _playbackSpeed.value = clampedSpeed
    }

    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    /**
     * Get total duration in milliseconds.
     */
    fun getDuration(): Long = exoPlayer?.duration ?: 0L

    /**
     * Check if player is currently playing.
     */
    fun isPlaying(): Boolean = exoPlayer?.isPlaying ?: false

    /**
     * Get the underlying ExoPlayer instance.
     * Use this to attach to SurfaceView: surfaceView.player = playerService.getPlayer()
     */
    fun getPlayer(): Player? = exoPlayer

    /**
     * Release player resources.
     * Called automatically on lifecycle destroy, but can be called manually.
     */
    fun release() {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        _playbackState.value = PlaybackState.Idle
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _error.value = null
    }

    // Lifecycle callbacks
    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    override fun onPause(owner: LifecycleOwner) {
        // Optional: pause playback when app goes to background
        // Commented out to allow background playback
        // pause()
    }

    // Player event listener
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> {
                    _playbackState.value = PlaybackState.Idle
                }
                Player.STATE_BUFFERING -> {
                    _playbackState.value = PlaybackState.Buffering
                }
                Player.STATE_READY -> {
                    _playbackState.value = PlaybackState.Ready
                    exoPlayer?.let {
                        _duration.value = it.duration
                    }
                }
                Player.STATE_ENDED -> {
                    _playbackState.value = PlaybackState.Ended
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = "Playback error: ${error.message}"
            _playbackState.value = PlaybackState.Error
        }
    }

    /**
     * Start periodic position updates.
     * Call this to enable reactive position updates via currentPosition flow.
     * Remember to stop updates when not needed to save resources.
     */
    fun startPositionUpdates() {
        // Implementation would use coroutine with delay loop
        // Intentionally omitted - implement based on your coroutine scope strategy
    }

    /**
     * Stop periodic position updates.
     */
    fun stopPositionUpdates() {
        // Implementation would cancel the coroutine
    }

    /**
     * Playback state sealed class for type-safe state management.
     */
    sealed class PlaybackState {
        object Idle : PlaybackState()
        object Buffering : PlaybackState()
        object Ready : PlaybackState()
        object Ended : PlaybackState()
        object Error : PlaybackState()
    }
}
