package com.musicplayer.presentation.browse.songs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.musicplayer.data.local.entities.SongEntity
import com.musicplayer.data.local.toDomain
import com.musicplayer.data.repository.MusicRepository
import com.musicplayer.domain.model.Song
import com.musicplayer.domain.model.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SongsViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val _sortOrder = MutableStateFlow<SortOrder>(SortOrder.TitleAsc)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val totalCount: StateFlow<Int> = repository.getSongCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Only the visible window of the songs table is loaded/mapped — a full-table
    // load + in-memory sort on every launch was the main startup cost for large
    // libraries.
    @OptIn(ExperimentalCoroutinesApi::class)
    val songs: Flow<PagingData<Song>> = _sortOrder.flatMapLatest { order ->
        Pager(PagingConfig(pageSize = 100, enablePlaceholders = false)) {
            repository.pagedSongs(order)
        }.flow.map { pagingData -> pagingData.map(SongEntity::toDomain) }
    }.cachedIn(viewModelScope)

    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }

    // Full sorted list for playback — used when a song is tapped, since the
    // paged list on screen is only a partial window and playback needs the
    // whole ordered queue.
    suspend fun buildQueue(): List<Song> = repository.getSortedSongs(_sortOrder.value)

    // Full unsorted list for shuffle — order doesn't matter since it's
    // randomized, so this skips the ORDER BY entirely.
    suspend fun songsForShuffle(): List<Song> = repository.getAllSongsList()
}
