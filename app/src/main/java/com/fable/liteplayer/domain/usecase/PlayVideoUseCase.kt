package com.fable.liteplayer.domain.usecase

import com.fable.liteplayer.domain.model.Video
import com.fable.liteplayer.domain.repository.PlayerRepository
import javax.inject.Inject

class PlayVideoUseCase @Inject constructor(
    private val playerRepository: PlayerRepository
) {
    suspend operator fun invoke(video: Video): Result<Unit> {
        return playerRepository.playVideo(video)
    }
}
