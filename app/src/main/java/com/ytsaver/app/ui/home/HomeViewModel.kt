package com.ytsaver.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.download.DownloadService
import com.ytsaver.app.extract.DirectLinkFetcher
import com.ytsaver.app.extract.FetchedStream
import com.ytsaver.app.extract.MediaOption
import com.ytsaver.app.extract.YoutubeStreamFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

sealed class QueueStatus {
    data object Loading : QueueStatus()
    data class Ready(val stream: FetchedStream, val caption: String) : QueueStatus()
    data class Error(val message: String) : QueueStatus()
}

data class QueuedLink(
    val id: Long,
    val urlText: String,
    val status: QueueStatus
)

data class HomeUiState(
    val urlText: String = "",
    val queue: List<QueuedLink> = emptyList()
)

/**
 * Lets you paste several links in a row while on Wi-Fi — each is fetched
 * independently and queued here; tapping Save on a ready item hands it to
 * DownloadService's own queue, which saves them one after another so they're
 * all available offline afterward.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val downloadProgress = DownloadService.progress
    val queuedDownloads = DownloadService.queueSize

    private val nextId = AtomicLong(0)

    fun onUrlChanged(value: String) {
        _uiState.update { it.copy(urlText = value) }
    }

    fun addToQueue() {
        val url = _uiState.value.urlText.trim()
        if (url.isBlank()) return

        val id = nextId.incrementAndGet()
        _uiState.update { it.copy(urlText = "", queue = it.queue + QueuedLink(id, url, QueueStatus.Loading)) }

        viewModelScope.launch {
            val result = if (DirectLinkFetcher.looksLikeYoutubeUrl(url)) {
                YoutubeStreamFetcher.fetch(url)
            } else {
                DirectLinkFetcher.fetch(url)
            }
            result
                .onSuccess { stream -> updateQueueItem(id) { QueueStatus.Ready(stream, stream.title) } }
                .onFailure { e -> updateQueueItem(id) { QueueStatus.Error(e.message ?: "Couldn't read that link.") } }
        }
    }

    private fun updateQueueItem(id: Long, status: (QueuedLink) -> QueueStatus) {
        _uiState.update { state ->
            state.copy(queue = state.queue.map { if (it.id == id) it.copy(status = status(it)) else it })
        }
    }

    fun onCaptionChanged(id: Long, caption: String) {
        _uiState.update { state ->
            state.copy(
                queue = state.queue.map { item ->
                    val status = item.status
                    if (item.id == id && status is QueueStatus.Ready) item.copy(status = status.copy(caption = caption))
                    else item
                }
            )
        }
    }

    fun removeFromQueue(id: Long) {
        _uiState.update { it.copy(queue = it.queue.filterNot { q -> q.id == id }) }
    }

    fun saveAs(id: Long, type: MediaType) {
        val item = _uiState.value.queue.find { it.id == id } ?: return
        val status = item.status as? QueueStatus.Ready ?: return
        val option: MediaOption = (if (type == MediaType.VIDEO) status.stream.videoOption else status.stream.audioOption)
            ?: return

        DownloadService.start(
            context = getApplication(),
            caption = status.caption.ifBlank { status.stream.title },
            sourceUrl = status.stream.sourceUrl,
            streamUrl = option.streamUrl,
            type = type,
            fileExtension = option.fileExtension,
            mimeType = option.mimeType,
            thumbnailUrl = status.stream.thumbnailUrl,
            durationSeconds = status.stream.durationSeconds
        )
        removeFromQueue(id)
    }

    fun dismissProgress() {
        DownloadService.clearProgress()
    }

    fun cancelCurrentDownload() {
        DownloadService.cancelCurrent()
    }
}
