package com.musicplayer.presentation.browse.songs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.data.repository.MusicRepository
import com.musicplayer.domain.model.Song
import com.musicplayer.domain.model.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class SongsUiState(
    val songs: List<Song> = emptyList(),
    val sortOrder: SortOrder = SortOrder.TitleAsc,
    val isLoading: Boolean = true,
    val totalCount: Int = 0
)

@HiltViewModel
class SongsViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val sortOrder = MutableStateFlow<SortOrder>(SortOrder.TitleAsc)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SongsUiState> = sortOrder.flatMapLatest { order ->
        repository.getAllSongs().map { songs ->
            val sorted = when (order) {
                SortOrder.TitleAsc -> songs.sortedBy { it.title.lowercase() }
                SortOrder.TitleDesc -> songs.sortedByDescending { it.title.lowercase() }
                SortOrder.ArtistAsc -> songs.sortedBy { it.artist.lowercase() }
                SortOrder.DateAddedDesc -> songs.sortedByDescending { it.dateAdded }
                SortOrder.PlayCountDesc -> songs.sortedByDescending { it.playCount }
                else -> songs.sortedBy { it.title.lowercase() }
            }
            SongsUiState(
                songs = sorted,
                sortOrder = order,
                isLoading = false,
                totalCount = sorted.size
            )
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SongsUiState())

    fun setSortOrder(order: SortOrder) { sortOrder.value = order }

    fun refresh() {
        // Trigger a refresh of the uiState flow by re-setting the sort order
        sortOrder.value = sortOrder.value
    }
}
