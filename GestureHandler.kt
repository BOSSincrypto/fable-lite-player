package com.fablelite.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sign

/**
 * Gesture handler for video player with zero impact on rendering performance.
 *
 * Supported gestures:
 * - Horizontal swipe: seek forward/backward with time overlay
 * - Vertical swipe left side: brightness control
 * - Vertical swipe right side: volume control
 * - Double tap left: rewind 10 seconds
 * - Double tap right: forward 10 seconds
 * - Single tap: show/hide controls
 *
 * All gesture processing is decoupled from video rendering pipeline.
 */
@Composable
fun Modifier.videoGestureDetector(
    playerManager: PlayerManager,
    onShowControls: () -> Unit,
    onHideControls: () -> Unit,
    onSeekPreview: (deltaMs: Long) -> Unit,
    onSeekComplete: () -> Unit,
    onBrightnessChange: (brightness: Float) -> Unit,
    onVolumeChange: (volume: Int) -> Unit,
    enabled: Boolean = true
): Modifier {
    if (!enabled) return this

    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val gestureState = remember { GestureState() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { offset ->
                    handleDoubleTap(offset, size.width.toFloat(), playerManager)
                },
                onTap = {
                    handleSingleTap(gestureState, onShowControls, onHideControls)
                }
            )
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    handlePointerEvent(
                        event = event,
                        gestureState = gestureState,
                        density = density,
                        screenWidth = size.width.toFloat(),
                        screenHeight = size.height.toFloat(),
                        context = context,
                        audioManager = audioManager,
                        playerManager = playerManager,
                        onSeekPreview = onSeekPreview,
                        onSeekComplete = onSeekComplete,
                        onBrightnessChange = onBrightnessChange,
                        onVolumeChange = onVolumeChange
                    )
                }
            }
        }
}

/**
 * State holder for gesture tracking - lightweight and non-blocking
 */
private class GestureState {
    var gestureType: GestureType = GestureType.None
    var startPosition: Offset = Offset.Zero
    var lastPosition: Offset = Offset.Zero
    var accumulatedDelta: Float = 0f
    var initialBrightness: Float = 0f
    var initialVolume: Int = 0
    var initialSeekPosition: Long = 0L
    var isControlsVisible: Boolean = false
    var lastTapTime: Long = 0L
}

/**
 * Gesture type classification
 */
private enum class GestureType {
    None,
    HorizontalSeek,
    VerticalBrightness,
    VerticalVolume,
    Tap
}

/**
 * Handle pointer events for swipe gestures
 */
private fun handlePointerEvent(
    event: PointerEvent,
    gestureState: GestureState,
    density: Density,
    screenWidth: Float,
    screenHeight: Float,
    context: Context,
    audioManager: AudioManager,
    playerManager: PlayerManager,
    onSeekPreview: (Long) -> Unit,
    onSeekComplete: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Int) -> Unit
) {
    val change = event.changes.firstOrNull() ?: return

    when (event.type) {
        PointerEventType.Press -> {
            gestureState.startPosition = change.position
            gestureState.lastPosition = change.position
            gestureState.accumulatedDelta = 0f
            gestureState.gestureType = GestureType.None
        }

        PointerEventType.Move -> {
            if (gestureState.gestureType == GestureType.None) {
                // Determine gesture type based on initial movement
                val deltaX = change.position.x - gestureState.startPosition.x
                val deltaY = change.position.y - gestureState.startPosition.y

                val minSwipeThreshold = with(density) { 20.dp.toPx() }

                if (abs(deltaX) > minSwipeThreshold || abs(deltaY) > minSwipeThreshold) {
                    gestureState.gestureType = classifyGesture(
                        deltaX = deltaX,
                        deltaY = deltaY,
                        startX = gestureState.startPosition.x,
                        screenWidth = screenWidth
                    )

                    // Initialize gesture-specific state
                    when (gestureState.gestureType) {
                        GestureType.HorizontalSeek -> {
                            gestureState.initialSeekPosition = playerManager.getCurrentPosition()
                        }
                        GestureType.VerticalBrightness -> {
                            gestureState.initialBrightness = getCurrentBrightness(context)
                        }
                        GestureType.VerticalVolume -> {
                            gestureState.initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                        else -> {}
                    }
                }
            }

            // Process ongoing gesture
            when (gestureState.gestureType) {
                GestureType.HorizontalSeek -> {
                    handleHorizontalSeek(
                        change = change,
                        gestureState = gestureState,
                        screenWidth = screenWidth,
                        playerManager = playerManager,
                        onSeekPreview = onSeekPreview
                    )
                }
                GestureType.VerticalBrightness -> {
                    handleVerticalBrightness(
                        change = change,
                        gestureState = gestureState,
                        screenHeight = screenHeight,
                        onBrightnessChange = onBrightnessChange
                    )
                }
                GestureType.VerticalVolume -> {
                    handleVerticalVolume(
                        change = change,
                        gestureState = gestureState,
                        screenHeight = screenHeight,
                        audioManager = audioManager,
                        onVolumeChange = onVolumeChange
                    )
                }
                else -> {}
            }

            gestureState.lastPosition = change.position
            change.consume()
        }

        PointerEventType.Release -> {
            // Complete gesture
            when (gestureState.gestureType) {
                GestureType.HorizontalSeek -> {
                    onSeekComplete()
                }
                else -> {}
            }

            gestureState.gestureType = GestureType.None
            gestureState.accumulatedDelta = 0f
        }

        else -> {}
    }
}

/**
 * Classify gesture based on initial movement direction and position
 */
private fun classifyGesture(
    deltaX: Float,
    deltaY: Float,
    startX: Float,
    screenWidth: Float
): GestureType {
    val isHorizontal = abs(deltaX) > abs(deltaY) * 1.5f
    val isVertical = abs(deltaY) > abs(deltaX) * 1.5f

    return when {
        isHorizontal -> GestureType.HorizontalSeek
        isVertical && startX < screenWidth * 0.4f -> GestureType.VerticalBrightness
        isVertical && startX > screenWidth * 0.6f -> GestureType.VerticalVolume
        else -> GestureType.None
    }
}

/**
 * Handle horizontal swipe for seeking
 * Sensitivity: 1% of screen width = 1 second of video
 */
private fun handleHorizontalSeek(
    change: PointerInputChange,
    gestureState: GestureState,
    screenWidth: Float,
    playerManager: PlayerManager,
    onSeekPreview: (Long) -> Unit
) {
    val deltaX = change.position.x - gestureState.lastPosition.x
    gestureState.accumulatedDelta += deltaX

    // Calculate seek delta: 1% of screen width = 1 second
    val seekDeltaMs = (gestureState.accumulatedDelta / screenWidth * 100 * 1000).toLong()

    // Calculate target position
    val currentPosition = gestureState.initialSeekPosition
    val targetPosition = (currentPosition + seekDeltaMs).coerceIn(0L, playerManager.getDuration())

    // Update preview (non-blocking)
    onSeekPreview(seekDeltaMs)

    // Perform actual seek (every 100ms to avoid overwhelming the player)
    if (abs(seekDeltaMs) % 100 < 10) {
        playerManager.seekTo(targetPosition)
    }
}

/**
 * Handle vertical swipe on left side for brightness control
 */
private fun handleVerticalBrightness(
    change: PointerInputChange,
    gestureState: GestureState,
    screenHeight: Float,
    onBrightnessChange: (Float) -> Unit
) {
    val deltaY = change.position.y - gestureState.lastPosition.y
    val brightnessDelta = -deltaY / screenHeight // Negative because swiping up increases brightness

    val newBrightness = (gestureState.initialBrightness + (change.position.y - gestureState.startPosition.y) / screenHeight * -1f)
        .coerceIn(0f, 1f)

    gestureState.initialBrightness = newBrightness
    onBrightnessChange(newBrightness)
}

/**
 * Handle vertical swipe on right side for volume control
 */
private fun handleVerticalVolume(
    change: PointerInputChange,
    gestureState: GestureState,
    screenHeight: Float,
    audioManager: AudioManager,
    onVolumeChange: (Int) -> Unit
) {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val deltaY = change.position.y - gestureState.lastPosition.y
    val volumeDelta = (-deltaY / screenHeight * maxVolume).toInt()

    // Calculate new volume based on total gesture distance
    val totalDeltaY = change.position.y - gestureState.startPosition.y
    val totalVolumeDelta = (-totalDeltaY / screenHeight * maxVolume).toInt()
    val newVolume = (gestureState.initialVolume + totalVolumeDelta).coerceIn(0, maxVolume)

    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
    onVolumeChange(newVolume)
}

/**
 * Handle double tap gesture
 */
private fun handleDoubleTap(
    offset: Offset,
    screenWidth: Float,
    playerManager: PlayerManager
) {
    val isLeftSide = offset.x < screenWidth * 0.4f
    val isRightSide = offset.x > screenWidth * 0.6f

    when {
        isLeftSide -> {
            // Rewind 10 seconds
            val currentPosition = playerManager.getCurrentPosition()
            val targetPosition = (currentPosition - 10000L).coerceAtLeast(0L)
            playerManager.seekTo(targetPosition)
            Timber.d("Double tap left: rewind to $targetPosition ms")
        }
        isRightSide -> {
            // Forward 10 seconds
            val currentPosition = playerManager.getCurrentPosition()
            val duration = playerManager.getDuration()
            val targetPosition = (currentPosition + 10000L).coerceAtMost(duration)
            playerManager.seekTo(targetPosition)
            Timber.d("Double tap right: forward to $targetPosition ms")
        }
        else -> {
            // Center area - optional: play/pause toggle
            if (playerManager.isPlaying()) {
                playerManager.pause()
            } else {
                playerManager.play()
            }
        }
    }
}

/**
 * Handle single tap gesture for showing/hiding controls
 */
private fun handleSingleTap(
    gestureState: GestureState,
    onShowControls: () -> Unit,
    onHideControls: () -> Unit
) {
    if (gestureState.isControlsVisible) {
        onHideControls()
        gestureState.isControlsVisible = false
    } else {
        onShowControls()
        gestureState.isControlsVisible = true
    }
}

/**
 * Get current screen brightness
 */
private fun getCurrentBrightness(context: Context): Float {
    return try {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS
        ) / 255f
    } catch (e: Exception) {
        Timber.e(e, "Failed to get current brightness")
        0.5f // Default to 50%
    }
}

/**
 * Gesture overlay data for UI feedback
 */
data class GestureOverlay(
    val type: GestureOverlayType,
    val value: String,
    val percentage: Float
)

enum class GestureOverlayType {
    Seek,
    Brightness,
    Volume,
    Rewind,
    Forward
}

/**
 * Extension function to format time in milliseconds to human-readable format
 */
fun Long.formatTime(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
