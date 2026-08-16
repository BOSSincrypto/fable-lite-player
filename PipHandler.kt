package com.fable.liteplayer

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

@RequiresApi(Build.VERSION_CODES.O)
class PipHandler(
    private val activity: AppCompatActivity,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val isPlaying: () -> Boolean
) {

    companion object {
        private const val ACTION_PLAY_PAUSE = "com.fable.liteplayer.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.fable.liteplayer.NEXT"
        private const val ACTION_PREVIOUS = "com.fable.liteplayer.PREVIOUS"

        private const val REQUEST_PLAY_PAUSE = 1
        private const val REQUEST_NEXT = 2
        private const val REQUEST_PREVIOUS = 3

        const val EXTRA_CONTROL_TYPE = "control_type"
    }

    private var pipReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    /**
     * Enter Picture-in-Picture mode with current video aspect ratio
     */
    fun enterPipMode(aspectRatioWidth: Int = 16, aspectRatioHeight: Int = 9): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        return try {
            val params = createPipParams(aspectRatioWidth, aspectRatioHeight)
            activity.enterPictureInPictureMode(params)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Update PiP parameters (e.g., when playback state changes)
     */
    fun updatePipParams(aspectRatioWidth: Int = 16, aspectRatioHeight: Int = 9) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        if (activity.isInPictureInPictureMode) {
            try {
                val params = createPipParams(aspectRatioWidth, aspectRatioHeight)
                activity.setPictureInPictureParams(params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Create PiP parameters with actions and aspect ratio
     */
    private fun createPipParams(aspectRatioWidth: Int, aspectRatioHeight: Int): PictureInPictureParams {
        val aspectRatio = Rational(
            aspectRatioWidth.coerceIn(1, 239),
            aspectRatioHeight.coerceIn(1, 239)
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)

        // Add actions for API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val actions = createPipActions()
            builder.setActions(actions)
        }

        // Auto-enter PiP for API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }

        // Seamless resize for API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    /**
     * Create remote actions for PiP controls
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipActions(): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()

        // Previous action
        actions.add(createRemoteAction(
            ACTION_PREVIOUS,
            REQUEST_PREVIOUS,
            R.drawable.ic_skip_previous,
            "Previous",
            "Skip to previous track"
        ))

        // Play/Pause action
        val playPauseIcon = if (isPlaying()) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play
        }
        val playPauseTitle = if (isPlaying()) "Pause" else "Play"

        actions.add(createRemoteAction(
            ACTION_PLAY_PAUSE,
            REQUEST_PLAY_PAUSE,
            playPauseIcon,
            playPauseTitle,
            "$playPauseTitle playback"
        ))

        // Next action
        actions.add(createRemoteAction(
            ACTION_NEXT,
            REQUEST_NEXT,
            R.drawable.ic_skip_next,
            "Next",
            "Skip to next track"
        ))

        return actions
    }

    /**
     * Create a single remote action
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRemoteAction(
        action: String,
        requestCode: Int,
        iconResId: Int,
        title: String,
        description: String
    ): RemoteAction {
        val intent = Intent(action).apply {
            setPackage(activity.packageName)
            putExtra(EXTRA_CONTROL_TYPE, requestCode)
        }

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            activity,
            requestCode,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val icon = Icon.createWithResource(activity, iconResId)

        return RemoteAction(icon, title, description, pendingIntent)
    }

    /**
     * Register broadcast receiver for PiP actions
     */
    fun registerReceiver() {
        if (isReceiverRegistered) {
            return
        }

        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return

                when (intent.action) {
                    ACTION_PLAY_PAUSE -> onPlayPause()
                    ACTION_NEXT -> onNext()
                    ACTION_PREVIOUS -> onPrevious()
                }

                // Update PiP UI after action
                updatePipParams()
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
        }

        ContextCompat.registerReceiver(
            activity,
            pipReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        isReceiverRegistered = true
    }

    /**
     * Unregister broadcast receiver
     */
    fun unregisterReceiver() {
        if (isReceiverRegistered && pipReceiver != null) {
            try {
                activity.unregisterReceiver(pipReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isReceiverRegistered = false
            pipReceiver = null
        }
    }

    /**
     * Handle user leaving the app (to trigger PiP)
     */
    fun onUserLeaveHint(aspectRatioWidth: Int = 16, aspectRatioHeight: Int = 9) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only enter PiP if currently playing
            if (isPlaying()) {
                enterPipMode(aspectRatioWidth, aspectRatioHeight)
            }
        }
    }

    /**
     * Handle PiP mode changes
     */
    fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        onEnterPip: () -> Unit = {},
        onExitPip: () -> Unit = {}
    ) {
        if (isInPictureInPictureMode) {
            // Entered PiP mode
            onEnterPip()
        } else {
            // Exited PiP mode
            onExitPip()
        }
    }

    /**
     * Check if PiP is supported on this device
     */
    fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        unregisterReceiver()
    }
}
