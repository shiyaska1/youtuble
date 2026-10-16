package com.mobicareapp.handwriting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Something another app (WhatsApp, the gallery, a file manager) shared to this app: a copy of the file now kept in the app's own storage, and its type. */
data class SharedItem(val file: File, val mime: String)

/**
 * Shared voice notes, pictures and PDFs wait here until the handwriting notepad is open and takes
 * them: voices are transcribed onto the first free screen, pictures and PDF pages each get a free screen.
 */
object SharedContentInbox {
    private val queue = ConcurrentLinkedQueue<SharedItem>()
    private val _version = MutableStateFlow(0)
    /** Changes whenever something is added, so the notepad knows to look. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun add(items: List<SharedItem>) {
        if (items.isEmpty()) return
        queue.addAll(items)
        _version.value = _version.value + 1
    }

    fun hasPending(): Boolean = queue.isNotEmpty()

    /** Takes everything waiting (each item is handed out exactly once). */
    fun drain(): List<SharedItem> {
        val out = mutableListOf<SharedItem>()
        while (true) out += queue.poll() ?: break
        return out
    }
}
