package com.musicplayer.presentation.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.data.repository.ThemeRepository
import com.musicplayer.data.repository.ThemeUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeRepository: ThemeRepository
) : ViewModel() {

    val themeState: StateFlow<ThemeUiState> = themeRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeUiState())
}
