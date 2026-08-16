package com.fable.liteplayer.domain.repository

import com.fable.liteplayer.domain.model.Video
import kotlinx.coroutines.flow.Flow

interface PlayerRepository {
    suspend fun playVideo(video: Video): Result<Unit>
    suspend fun pause()
    suspend fun resume()
    suspend fun seekTo(position: Long)
    fun observePlaybackState(): Flow<PlaybackState>
}

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data object Buffering : PlaybackState()
    data class Playing(val position: Long, val duration: Long) : PlaybackState()
    data class Paused(val position: Long, val duration: Long) : PlaybackState()
    data class Error(val message: String) : PlaybackState()
}
