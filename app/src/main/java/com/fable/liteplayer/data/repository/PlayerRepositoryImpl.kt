package com.fable.liteplayer.data.repository

import com.fable.liteplayer.data.player.PlayerManager
import com.fable.liteplayer.domain.model.Video
import com.fable.liteplayer.domain.repository.PlayerRepository
import com.fable.liteplayer.domain.repository.PlaybackState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class PlayerRepositoryImpl @Inject constructor(
    private val playerManager: PlayerManager
) : PlayerRepository {

    override suspend fun playVideo(video: Video): Result<Unit> {
        return try {
            playerManager.play(video.url)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pause() {
        playerManager.pause()
    }

    override suspend fun resume() {
        playerManager.resume()
    }

    override suspend fun seekTo(position: Long) {
        playerManager.seekTo(position)
    }

    override fun observePlaybackState(): Flow<PlaybackState> {
        return playerManager.playbackState
    }
}
