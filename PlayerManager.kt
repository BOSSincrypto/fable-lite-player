package com.fablelite.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.defaultLoadControl.DefaultLoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoProcessor
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Optimized ExoPlayer core player manager with minimal latency configuration,
 * hardware video decoding, and complete state management.
 *
 * Key optimizations:
 * - Hardware video decoder for efficient playback
 * - Aggressive buffer settings for minimal latency
 * - Exact seek parameters for frame-accurate seeking
 * - SurfaceView for lower battery usage and better performance
 * - MediaSession integration for lock screen controls
 * - Audio focus management
 * - Complete state tracking and error handling
 */
@OptIn(UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val surfaceView: SurfaceView
) {

    // Player instances and components
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: Any? = null // AudioFocusRequest for Android 8+

    // State management
    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // Listeners and callbacks
    private val playerListeners = mutableListOf<PlayerListener>()
    private val stateChangeHandler = Handler(Looper.getMainLooper())
    private var positionUpdateRunnable: Runnable? = null

    init {
        initializePlayer()
        setupAudioAttributes()
        setupPositionUpdates()
    }

    /**
     * Initialize ExoPlayer with optimized settings for minimal latency
     */
    private fun initializePlayer() {
        try {
            // Create optimized load control for minimal latency
            val loadControl = createMinimalLatencyLoadControl()

            // Create track selector with hardware decoder preference
            val trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters(context)
                        .setPreferredVideoMimeTypes(
                            // Prefer hardware-decodable formats
                            "video/avc", // H.264
                            "video/hevc", // H.265
                            "video/vp8",
                            "video/vp9"
                        )
                        .build()
                )
            }

            // Build ExoPlayer with optimizations
            exoPlayer = ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setSeekParameters(SeekParameters.EXACT) // Frame-accurate seeking
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                .setPauseAtEndOfMediaItems(true)
                .build()
                .apply {
                    // Use SurfaceView for lower battery and better performance
                    setVideoSurfaceView(surfaceView)

                    // Configure audio attributes for proper audio focus handling
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        false // Don't handle audio focus automatically; we'll do it manually
                    )

                    // Add player listener for state changes
                    addListener(createPlayerListener())
                }

            // Create MediaSession for lock screen controls
            createMediaSession()

            _playerState.value = PlayerState.Ready
            Timber.d("ExoPlayer initialized successfully")

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize ExoPlayer")
            _playerState.value = PlayerState.Error(e.message ?: "Initialization failed")
        }
    }

    /**
     * Create load control optimized for minimal latency
     * These settings prioritize responsiveness over buffering
     */
    private fun createMinimalLatencyLoadControl(): LoadControl {
        return DefaultLoadControl.Builder()
            // Initial buffer before playback starts (aggressive for low latency)
            .setBufferDurationsMs(
                300, // min buffer duration (default: 50000)
                1000, // max buffer duration (default: 50000)
                300, // buffer for playback (default: 2500)
                500 // buffer for playback after rebuffering (default: 5000)
            )
            // Prioritize live playback (reduces latency)
            .setTargetBufferBytes(C.DEFAULT_BUFFER_FOR_PLAYBACK_MS)
            // Disable backoff strategy for instant response
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * Setup audio attributes for proper audio handling
     */
    private fun setupAudioAttributes() {
        exoPlayer?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true // Handle audio focus
        )
    }

    /**
     * Create MediaSession for lock screen controls and remote control
     */
    private fun createMediaSession() {
        try {
            exoPlayer?.let { player ->
                mediaSession = MediaSession.Builder(context, player).build()
                Timber.d("MediaSession created for lock screen controls")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create MediaSession")
        }
    }

    /**
     * Create player event listener for state tracking
     */
    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updatePlayerState(state)
                notifyListeners { it.onPlaybackStateChanged(state) }
                Timber.d("Playback state changed: $state")
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _isPlaying.value = playWhenReady
                notifyListeners { it.onPlayWhenReadyChanged(playWhenReady, reason) }
                Timber.d("Play when ready: $playWhenReady, reason: $reason")
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorMessage = "Playback error: ${error.message}"
                _playerState.value = PlayerState.Error(errorMessage)
                notifyListeners { it.onPlayerError(error) }
                Timber.e(error, errorMessage)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                notifyListeners { it.onPositionDiscontinuity(reason) }
                Timber.d("Position discontinuity: old=${oldPosition.positionMs}, new=${newPosition.positionMs}, reason=$reason")
            }

            override fun onBufferingStateChanged(state: Int, playbackPositionMs: Int) {
                if (state == Player.STATE_BUFFERING) {
                    _playerState.value = PlayerState.Buffering
                }
                notifyListeners { it.onBufferingStateChanged(state) }
                Timber.d("Buffering state: $state")
            }

            override fun onLoadingChanged(isLoading: Boolean) {
                if (isLoading && _playerState.value !is PlayerState.Error) {
                    _playerState.value = PlayerState.Buffering
                }
                notifyListeners { it.onLoadingChanged(isLoading) }
            }

            override fun onVideoSizeChanged(videoSize: com.androidx.media3.common.video.VideoSize) {
                notifyListeners { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
                Timber.d("Video size changed: ${videoSize.width}x${videoSize.height}")
            }
        }
    }

    /**
     * Update player state based on ExoPlayer state
     */
    private fun updatePlayerState(state: Int) {
        val newState = when (state) {
            Player.STATE_IDLE -> PlayerState.Idle
            Player.STATE_BUFFERING -> PlayerState.Buffering
            Player.STATE_READY -> PlayerState.Ready
            Player.STATE_ENDED -> PlayerState.Ended
            else -> PlayerState.Unknown
        }
        _playerState.value = newState
    }

    /**
     * Setup periodic position updates
     */
    private fun setupPositionUpdates() {
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    _playbackPosition.value = player.currentPosition
                    _bufferedPosition.value = player.bufferedPosition
                    _duration.value = player.duration.takeIf { it > 0 } ?: 0L
                }
                stateChangeHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
            }
        }
        stateChangeHandler.post(positionUpdateRunnable!!)
    }

    /**
     * Load and prepare media for playback
     */
    fun loadMedia(mediaUri: String) {
        try {
            exoPlayer?.let { player ->
                val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)
                val mediaSource: MediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(mediaUri))

                player.setMediaSource(mediaSource)
                player.prepare()

                _playerState.value = PlayerState.Ready
                Timber.d("Media loaded: $mediaUri")
                notifyListeners { it.onMediaLoaded(mediaUri) }

            } ?: throw IllegalStateException("ExoPlayer not initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load media: $mediaUri")
            _playerState.value = PlayerState.Error(e.message ?: "Failed to load media")
            notifyListeners { it.onMediaLoadError(e) }
        }
    }

    /**
     * Start playback
     */
    fun play() {
        try {
            if (!requestAudioFocus()) {
                Timber.w("Audio focus request denied")
                return
            }
            exoPlayer?.play()
            Timber.d("Playback started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start playback")
            _playerState.value = PlayerState.Error(e.message ?: "Failed to start playback")
        }
    }

    /**
     * Pause playback
     */
    fun pause() {
        try {
            exoPlayer?.pause()
            Timber.d("Playback paused")
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause playback")
        }
    }

    /**
     * Seek to specific position (frame-accurate with EXACT seek parameters)
     */
    fun seekTo(positionMs: Long) {
        try {
            exoPlayer?.seekTo(positionMs)
            Timber.d("Seeked to: $positionMs ms")
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek to: $positionMs ms")
        }
    }

    /**
     * Set playback speed
     */
    fun setPlaybackSpeed(speed: Float) {
        try {
            exoPlayer?.setPlaybackSpeed(speed)
            _playbackSpeed.value = speed
            Timber.d("Playback speed set to: $speed")
        } catch (e: Exception) {
            Timber.e(e, "Failed to set playback speed: $speed")
        }
    }

    /**
     * Request audio focus for playback
     */
    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+: Use AudioFocusRequest
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            audioFocusRequest = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN
            )
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest as android.media.AudioFocusRequest)
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            // Pre-Android 8: Use legacy method
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange -> handleAudioFocusChange(focusChange) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * Abandon audio focus
     */
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it as android.media.AudioFocusRequest)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    /**
     * Handle audio focus changes
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                exoPlayer?.play()
                Timber.d("Audio focus gained")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
                Timber.d("Audio focus lost")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pause()
                Timber.d("Audio focus lost temporarily")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Optionally reduce volume instead of pausing
                exoPlayer?.volume = 0.3f
                Timber.d("Audio focus ducked")
            }
        }
    }

    /**
     * Register player listener for callbacks
     */
    fun addPlayerListener(listener: PlayerListener) {
        playerListeners.add(listener)
    }

    /**
     * Unregister player listener
     */
    fun removePlayerListener(listener: PlayerListener) {
        playerListeners.remove(listener)
    }

    /**
     * Notify all listeners of changes
     */
    private inline fun notifyListeners(action: (PlayerListener) -> Unit) {
        playerListeners.forEach { action(it) }
    }

    /**
     * Get current playback position
     */
    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    /**
     * Get total duration
     */
    fun getDuration(): Long = exoPlayer?.duration?.takeIf { it > 0 } ?: 0L

    /**
     * Check if currently playing
     */
    fun isPlaying(): Boolean = exoPlayer?.isPlaying ?: false

    /**
     * Get current playback speed
     */
    fun getPlaybackSpeed(): Float = exoPlayer?.playbackParameters?.speed ?: 1f

    /**
     * Release player resources
     */
    fun release() {
        try {
            stateChangeHandler.removeCallbacks(positionUpdateRunnable ?: return)

            exoPlayer?.let {
                it.stop()
                it.release()
            }
            exoPlayer = null

            mediaSession?.release()
            mediaSession = null

            abandonAudioFocus()

            playerListeners.clear()

            _playerState.value = PlayerState.Idle
            _isPlaying.value = false

            Timber.d("PlayerManager released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing PlayerManager")
        }
    }

    /**
     * Player state sealed class for comprehensive state tracking
     */
    sealed class PlayerState {
        object Idle : PlayerState()
        object Ready : PlayerState()
        object Buffering : PlayerState()
        object Ended : PlayerState()
        object Unknown : PlayerState()
        data class Error(val message: String) : PlayerState()
    }

    /**
     * Player event listener interface
     */
    interface PlayerListener {
        fun onPlaybackStateChanged(state: Int) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onPositionDiscontinuity(reason: Int) {}
        fun onBufferingStateChanged(state: Int) {}
        fun onLoadingChanged(isLoading: Boolean) {}
        fun onVideoSizeChanged(width: Int, height: Int) {}
        fun onMediaLoaded(uri: String) {}
        fun onMediaLoadError(error: Exception) {}
    }

    companion object {
        // Position update interval for progress tracking
        private const val POSITION_UPDATE_INTERVAL_MS = 100L

        // Load control constants for minimal latency
        private const val MIN_BUFFER_DURATION_MS = 300
        private const val MAX_BUFFER_DURATION_MS = 1000
        private const val BUFFER_FOR_PLAYBACK_MS = 300
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500
    }
}
