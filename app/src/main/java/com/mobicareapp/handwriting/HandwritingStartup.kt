package com.mobicareapp.handwriting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A request to open the handwriting notepad straight away: made when the app is launched, and when
 * something is shared to the app. MainActivity raises it; the Scan tab (which hosts the notepad)
 * takes it exactly once, so rotating the phone or coming back to the tab later doesn't reopen it.
 */
object HandwritingStartup {
    private val requested = AtomicBoolean(false)
    private val _count = MutableStateFlow(0)
    /** Changes with every request, so screens that are already showing notice a new one. */
    val requestCount: StateFlow<Int> = _count.asStateFlow()

    fun request() {
        requested.set(true)
        _count.value = _count.value + 1
    }

    fun isRequested(): Boolean = requested.get()

    private val backgrounded = AtomicBoolean(false)

    /** The app really left the front (not just a picker or the lock hold): coming back should land on the notepad. */
    fun markBackgrounded() = backgrounded.set(true)

    /** True once after such a departure. */
    fun takeBackgrounded(): Boolean = backgrounded.getAndSet(false)

    /** True once, for whoever opens the notepad. */
    fun consume(): Boolean = requested.getAndSet(false)
}
