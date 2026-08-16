package com.fable.liteplayer.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fable.liteplayer.domain.usecase.PlayVideoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playVideoUseCase: PlayVideoUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun playVideo(url: String) {
        viewModelScope.launch {
            // Implementation
        }
    }

    fun pause() {
        viewModelScope.launch {
            // Implementation
        }
    }

    fun resume() {
        viewModelScope.launch {
            // Implementation
        }
    }
}

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null
)
