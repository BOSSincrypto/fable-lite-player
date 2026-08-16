package com.fable.player.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes

/**
 * Manages Picture-in-Picture (PiP) mode for the media player activity.
 *
 * Supports Android 8.0+ (API 26). On older versions all operations are no-ops.
 *
 * Usage:
 * 1. Instantiate in your Activity and call [onStart] / [onStop].
 * 2. Forward [onUserLeaveHint] from the Activity.
 * 3. Forward [onPictureInPictureModeChanged] from the Activity.
 * 4. Call [updateVideoSize] whenever the video dimensions are known.
 * 5. Call [updatePlaybackState] on play/pause transitions.
 */
class PipManager(
    private val activity: Activity,
    private val listener: PipListener,
) {

    //-------------------------------------------------------------------------
    // Constants
    //-------------------------------------------------------------------------

    companion object {
        const val ACTION_PIP_CONTROL = "com.fable.player.pip.ACTION_PIP_CONTROL"
        const val EXTRA_CONTROL_TYPE = "control_type"

        const val CONTROL_PLAY_PAUSE = 0
        const val CONTROL_PREVIOUS = 1
        const val CONTROL_NEXT = 2

        private const val REQUEST_CODE_PLAY_PAUSE = 100
        private const val REQUEST_CODE_PREVIOUS = 101
        private const val REQUEST_CODE_NEXT = 102

        // Fallback aspect ratio (16:9) used before video dimensions are known
        private val DEFAULT_ASPECT_RATIO = Rational(16, 9)

        // PiP window bounds enforced by Android: min 1:2.39, max 2.39:1
        private const val ASPECT_MIN_FLOAT = 1.0f / 2.39f
        private const val ASPECT_MAX_FLOAT = 2.39f
    }

    //-------------------------------------------------------------------------
    // State
    //-------------------------------------------------------------------------

    private var isPlaying: Boolean = false
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var isInPipMode: Boolean = false
    private var pipControlsReceiver: PipControlsReceiver? = null

    val isActive: Boolean get() = isInPipMode

    //-------------------------------------------------------------------------
    // Lifecycle
    //-------------------------------------------------------------------------

    /**
     * Call from Activity.onStart(). Registers the PiP controls broadcast receiver.
     */
    fun onStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        registerReceiver()
    }

    /**
     * Call from Activity.onStop(). Unregisters the PiP controls broadcast receiver.
     */
    fun onStop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        unregisterReceiver()
    }

    /**
     * Call from Activity.onUserLeaveHint().
     * Automatically enters PiP when the user presses Home while playback is active.
     */
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isPlaying) {
            enterPipMode()
        }
    }

    /**
     * Call from Activity.onPictureInPictureModeChanged().
     * Updates internal state and notifies the listener.
     */
    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            listener.onEnterPip()
        } else {
            listener.onExitPip()
        }
    }

    //-------------------------------------------------------------------------
    // Public API
    //-------------------------------------------------------------------------

    /**
     * Updates the video dimensions used for aspect-ratio calculation.
     * Call whenever the video source changes or dimensions become known.
     */
    fun updateVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        if (isInPipMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.setPictureInPictureParams(buildPipParams())
        }
    }

    /**
     * Reflects the current playback state in the PiP controls.
     * The play/pause button icon and title update in real time.
     */
    fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
            activity.setPictureInPictureParams(buildPipParams())
        }
    }

    /**
     * Explicitly requests PiP mode. No-op on API < 26 or if already in PiP.
     * Returns true if the transition was requested.
     */
    fun enterPipMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isInPipMode) return false
        return activity.enterPictureInPictureMode(buildPipParams())
    }

    //-------------------------------------------------------------------------
    // PiP params builder
    //-------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(resolveAspectRatio())
            .setActions(buildActions())

        // Android 12+: seamless resize and auto-enter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
            builder.setAutoEnterEnabled(isPlaying)
        }

        // Android 13+: custom title and subtitle in PiP window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setTitle("Video")
            builder.setSubtitle("Playing")
        }

        return builder.build()
    }

    /**
     * Calculates and clamps the video aspect ratio to Android's enforced bounds.
     * Android enforces: min 1:2.39, max 2.39:1
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun resolveAspectRatio(): Rational {
        if (videoWidth <= 0 || videoHeight <= 0) return DEFAULT_ASPECT_RATIO

        val rawRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val clampedRatio = rawRatio.coerceIn(ASPECT_MIN_FLOAT, ASPECT_MAX_FLOAT)

        // Return exact Rational if no clamping occurred
        return if (clampedRatio == rawRatio) {
            Rational(videoWidth, videoHeight)
        } else {
            // Convert clamped float to Rational with denominator ≤ 10000
            val denominator = 1000
            Rational((clampedRatio * denominator).toInt(), denominator)
        }
    }

    //-------------------------------------------------------------------------
    // Remote actions
    //-------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildActions(): List<RemoteAction> {
        return listOf(
            buildAction(
                controlType = CONTROL_PREVIOUS,
                iconRes = android.R.drawable.ic_media_previous,
                titleRes = android.R.string.previous,
                requestCode = REQUEST_CODE_PREVIOUS,
            ),
            buildPlayPauseAction(),
            buildAction(
                controlType = CONTROL_NEXT,
                iconRes = android.R.drawable.ic_media_next,
                titleRes = android.R.string.next,
                requestCode = REQUEST_CODE_NEXT,
            ),
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPlayPauseAction(): RemoteAction {
        return if (isPlaying) {
            buildAction(
                controlType = CONTROL_PLAY_PAUSE,
                iconRes = android.R.drawable.ic_media_pause,
                titleRes = android.R.string.pause,
                requestCode = REQUEST_CODE_PLAY_PAUSE,
            )
        } else {
            buildAction(
                controlType = CONTROL_PLAY_PAUSE,
                iconRes = android.R.drawable.ic_media_play,
                titleRes = android.R.string.play,
                requestCode = REQUEST_CODE_PLAY_PAUSE,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildAction(
        controlType: Int,
        @DrawableRes iconRes: Int,
        @StringRes titleRes: Int,
        requestCode: Int,
    ): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).apply {
            putExtra(EXTRA_CONTROL_TYPE, controlType)
            setPackage(activity.packageName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = activity.getString(titleRes)
        val action = RemoteAction(
            Icon.createWithResource(activity, iconRes),
            title,
            title,
            pendingIntent,
        )
        action.isEnabled = true
        return action
    }

    //-------------------------------------------------------------------------
    // Broadcast receiver for PiP controls
    //-------------------------------------------------------------------------

    private fun registerReceiver() {
        if (pipControlsReceiver != null) return

        val receiver = PipControlsReceiver()
        val filter = IntentFilter(ACTION_PIP_CONTROL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(receiver, filter)
        }

        pipControlsReceiver = receiver
    }

    private fun unregisterReceiver() {
        pipControlsReceiver?.let {
            try {
                activity.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered; safe to ignore
            }
            pipControlsReceiver = null
        }
    }

    /**
     * BroadcastReceiver for handling PiP control button taps.
     * Routes control events to the listener callback interface.
     */
    private inner class PipControlsReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_CONTROL) return

            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, -1)) {
                CONTROL_PLAY_PAUSE -> listener.onPipPlayPause()
                CONTROL_PREVIOUS -> listener.onPipPrevious()
                CONTROL_NEXT -> listener.onPipNext()
            }
        }
    }

    //-------------------------------------------------------------------------
    // Listener interface
    //-------------------------------------------------------------------------

    /**
     * Callbacks from PiP manager to the hosting Activity or ViewModel.
     * All callbacks are invoked on the main thread.
     *
     * Implementers should:
     * - Hide non-essential UI on [onEnterPip]
     * - Restore full UI on [onExitPip]
     * - Update playback state on control events
     */
    interface PipListener {
        /**
         * Called when the window has entered PiP mode.
         * Hide player controls, progress bar, and non-essential UI.
         */
        fun onEnterPip()

        /**
         * Called when the window has exited PiP mode.
         * Restore the full player UI.
         */
        fun onExitPip()

        /**
         * The play/pause button inside the PiP window was tapped.
         * Toggle playback state and call [updatePlaybackState] to sync the UI.
         */
        fun onPipPlayPause()

        /**
         * The skip-previous button inside the PiP window was tapped.
         * Seek to the previous media item or beginning of current item.
         */
        fun onPipPrevious()

        /**
         * The skip-next button inside the PiP window was tapped.
         * Seek to the next media item.
         */
        fun onPipNext()
    }
}
