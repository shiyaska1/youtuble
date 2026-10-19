package com.mobicareapp.upload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class UploadStatus { WAITING, UPLOADING, DONE, FAILED }

/** One file shared to "Upload to my server": what it is called there, how far it has got, and the result. */
data class ShareUploadItem(
    val label: String,
    val serverName: String,
    val size: Long,
    val sent: Long = 0,
    val status: UploadStatus = UploadStatus.WAITING,
    val url: String? = null,
    val error: String? = null
)

/** What the share-to-server screen shows. Written by [ShareUploadService], read by the screen and the notification. */
object ShareUploadState {
    private val _items = MutableStateFlow<List<ShareUploadItem>>(emptyList())
    val items: StateFlow<List<ShareUploadItem>> = _items.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setRunning(running: Boolean) { _running.value = running }

    /** Adds files to the list; returns the index of the first new one. */
    fun append(newItems: List<ShareUploadItem>): Int {
        var first = 0
        _items.update { old -> first = old.size; old + newItems }
        return first
    }

    /** A new batch replaces what an earlier finished one left on the screen. */
    fun startFresh() {
        if (!_running.value) _items.value = emptyList()
    }

    fun update(index: Int, change: (ShareUploadItem) -> ShareUploadItem) {
        _items.update { list -> list.mapIndexed { i, item -> if (i == index) change(item) else item } }
    }

    /** The addresses of every file that went through, one per line. */
    fun finishedUrls(): List<String> = _items.value.filter { it.status == UploadStatus.DONE }.mapNotNull { it.url }

    /** Puts the finished files' addresses on the clipboard; true if there was anything to copy. */
    fun copyUrls(context: Context): Boolean {
        val urls = finishedUrls()
        if (urls.isEmpty()) return false
        return runCatching {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Uploaded URL", urls.joinToString("\n")))
        }.isSuccess
    }
}
