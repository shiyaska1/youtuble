package com.mobicareapp.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobicareapp.data.MediaType
import com.mobicareapp.download.DownloadService
import com.mobicareapp.download.HighQualityMuxDownloader
import com.mobicareapp.extract.DirectLinkFetcher
import com.mobicareapp.extract.FetchedStream
import com.mobicareapp.extract.GenericVideoFetcher
import com.mobicareapp.extract.MediaOption
import com.mobicareapp.extract.YoutubeStreamFetcher
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

    private val _muxingIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Links currently downloading+merging a high-quality video — that path has no byte-progress reporting like DownloadService's queue does, just a "this is happening" indicator. */
    val muxingIds: StateFlow<Set<Long>> = _muxingIds.asStateFlow()

    fun onUrlChanged(value: String) {
        _uiState.update { it.copy(urlText = value) }
    }

    fun addToQueue() {
        val url = _uiState.value.urlText.trim()
        if (url.isBlank()) return

        val id = nextId.incrementAndGet()
        _uiState.update { it.copy(urlText = "", queue = it.queue + QueuedLink(id, url, QueueStatus.Loading)) }
        fetchInto(id, url)
    }

    /** Re-runs the same fetch for an already-queued link — used after "Sign in" closes, once a session cookie may now exist. */
    fun retry(id: Long) {
        val url = _uiState.value.queue.find { it.id == id }?.urlText ?: return
        updateQueueItem(id) { QueueStatus.Loading }
        fetchInto(id, url)
    }

    private fun fetchInto(id: Long, url: String) {
        viewModelScope.launch {
            val result = if (DirectLinkFetcher.looksLikeYoutubeUrl(url)) {
                YoutubeStreamFetcher.fetch(url)
            } else {
                // Most pasted links are either the file itself (DirectLinkFetcher) or a normal
                // page that embeds a video (GenericVideoFetcher scrapes its meta tags) — try the
                // cheap direct check first and only fall back to fetching+parsing the whole page
                // when that link turns out not to be a raw file.
                DirectLinkFetcher.fetch(url).recoverCatching { GenericVideoFetcher.fetch(url).getOrThrow() }
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

    fun saveAudio(id: Long) {
        val item = _uiState.value.queue.find { it.id == id } ?: return
        val status = item.status as? QueueStatus.Ready ?: return
        val option = status.stream.audioOption ?: return
        startDownload(status, option, MediaType.AUDIO)
        removeFromQueue(id)
    }

    /** [option] is one entry from [FetchedStream.videoOptions] the user picked from the quality dialog — not necessarily the best/first one. */
    fun saveVideo(id: Long, option: MediaOption) {
        val item = _uiState.value.queue.find { it.id == id } ?: return
        val status = item.status as? QueueStatus.Ready ?: return

        if (!option.needsAudioMux) {
            startDownload(status, option, MediaType.VIDEO)
            removeFromQueue(id)
            return
        }

        // Above what "progressive" streams offer: video and audio are two separate downloads
        // that need muxing together afterward, which DownloadService's single-URL queue can't do
        // — handled here instead, with just a "this is happening" flag rather than the byte-level
        // progress the normal queue shows.
        val audioOption = status.stream.audioOption ?: return
        _muxingIds.update { it + id }
        viewModelScope.launch {
            val result = HighQualityMuxDownloader.downloadAndMux(
                context = getApplication(),
                videoOption = option,
                audioOption = audioOption,
                caption = status.caption.ifBlank { status.stream.title },
                sourceUrl = status.stream.sourceUrl,
                thumbnailUrl = status.stream.thumbnailUrl,
                durationSeconds = status.stream.durationSeconds
            )
            _muxingIds.update { it - id }
            result
                .onSuccess { removeFromQueue(id) }
                .onFailure { e -> updateQueueItem(id) { QueueStatus.Error(e.message ?: "Couldn't download that quality") } }
        }
    }

    private fun startDownload(status: QueueStatus.Ready, option: MediaOption, type: MediaType) {
        DownloadService.start(
            context = getApplication(),
            caption = status.caption.ifBlank { status.stream.title },
            sourceUrl = status.stream.sourceUrl,
            streamUrl = option.streamUrl,
            type = type,
            fileExtension = option.fileExtension,
            mimeType = option.mimeType,
            thumbnailUrl = status.stream.thumbnailUrl,
            durationSeconds = status.stream.durationSeconds,
            cookie = option.cookie,
            referer = option.referer
        )
    }

    fun dismissProgress() {
        DownloadService.clearProgress()
    }

    fun cancelCurrentDownload() {
        DownloadService.cancelCurrent()
    }
}
