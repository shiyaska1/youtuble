package com.ytsaver.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.download.DownloadService
import com.ytsaver.app.extract.FetchedStream
import com.ytsaver.app.extract.MediaOption
import com.ytsaver.app.extract.YoutubeStreamFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val urlText: String = "",
    val loading: Boolean = false,
    val fetched: FetchedStream? = null,
    val caption: String = "",
    val error: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val downloadProgress = DownloadService.progress

    fun onUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(urlText = value, error = null)
    }

    fun fetch() {
        val url = _uiState.value.urlText.trim()
        if (url.isBlank()) return

        _uiState.value = _uiState.value.copy(loading = true, error = null, fetched = null)
        viewModelScope.launch {
            YoutubeStreamFetcher.fetch(url)
                .onSuccess { stream ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        fetched = stream,
                        caption = stream.title
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = e.message ?: "Couldn't read that link. Check it's a valid YouTube URL."
                    )
                }
        }
    }

    fun onCaptionChanged(value: String) {
        _uiState.value = _uiState.value.copy(caption = value)
    }

    fun saveAs(type: MediaType) {
        val state = _uiState.value
        val stream = state.fetched ?: return
        val option: MediaOption = (if (type == MediaType.VIDEO) stream.videoOption else stream.audioOption)
            ?: return
        val caption = state.caption.ifBlank { stream.title }

        DownloadService.start(
            context = getApplication(),
            caption = caption,
            sourceUrl = stream.sourceUrl,
            streamUrl = option.streamUrl,
            type = type,
            fileExtension = option.fileExtension,
            thumbnailUrl = stream.thumbnailUrl,
            durationSeconds = stream.durationSeconds
        )
        _uiState.value = HomeUiState()
    }

    fun dismissProgress() {
        DownloadService.clearProgress()
    }
}
