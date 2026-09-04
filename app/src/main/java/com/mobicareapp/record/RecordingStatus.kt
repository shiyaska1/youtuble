package com.mobicareapp.record

import com.mobicareapp.data.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveRecording(val type: MediaType, val startedAtMillis: Long)

/** Shared by AudioRecordService/VideoRecordService so RecordScreen doesn't need to know which one is running. */
object RecordingStatus {
    private val _active = MutableStateFlow<ActiveRecording?>(null)
    val active = _active.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun started(type: MediaType) {
        _active.value = ActiveRecording(type, System.currentTimeMillis())
    }

    fun stopped() {
        _active.value = null
    }

    fun reportError(message: String) {
        _error.value = message
    }

    fun errorShown() {
        _error.value = null
    }
}
