package com.mobicareapp.record

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Set by MainActivity when launched via an app shortcut (long-press the app icon → "Record
 * Audio"/"Record Video") — RecordScreen consumes this once to switch to the right mode and start
 * recording immediately, without the user having to navigate to the Record tab and tap Start
 * themselves. That's the whole point of a shortcut: skip the extra taps.
 */
object PendingQuickRecordAction {
    enum class Action { AUDIO, VIDEO }

    private val _pending = MutableStateFlow<Action?>(null)
    val pending = _pending.asStateFlow()

    fun set(action: Action) {
        _pending.value = action
    }

    fun consume() {
        _pending.value = null
    }
}
