package com.mobicareapp.record

import com.mobicareapp.data.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which capture path produced the recording — MediaType alone can't tell VideoRecordService (camera) and ScreenRecordService (screen) apart, both save as MediaType.VIDEO. */
enum class RecordingSource { MIC, CAMERA, SCREEN }

data class ActiveRecording(val type: MediaType, val source: RecordingSource, val startedAtMillis: Long)

/** A just-saved recording's type and where it landed — RecordScreen uses this to offer transcribing it right away, and LibraryScreen uses it to switch its category filter to match, since a freshly-recorded video would otherwise be invisible under Library's default Audio-only filter. */
data class FinishedRecording(val type: MediaType, val filePath: String)

/** Shared by AudioRecordService/VideoRecordService/ScreenRecordService so RecordScreen doesn't need to know which one is running. */
object RecordingStatus {
    private val _active = MutableStateFlow<ActiveRecording?>(null)
    val active = _active.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _lastFinished = MutableStateFlow<FinishedRecording?>(null)
    /** Each distinct value is meant to be reacted to once — collectors key their LaunchedEffect on the value itself, not a separate "consumed" flag, so more than one screen can react to the same finish without racing to clear it first. */
    val lastFinished = _lastFinished.asStateFlow()

    fun started(type: MediaType, source: RecordingSource) {
        _active.value = ActiveRecording(type, source, System.currentTimeMillis())
    }

    fun stopped() {
        _active.value = null
    }

    fun finished(type: MediaType, filePath: String) {
        _lastFinished.value = FinishedRecording(type, filePath)
    }

    fun reportError(message: String) {
        _error.value = message
    }

    fun errorShown() {
        _error.value = null
    }
}
