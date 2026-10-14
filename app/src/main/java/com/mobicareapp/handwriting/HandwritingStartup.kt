package com.mobicareapp.handwriting

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A one-time request, made when the app is launched, to open the handwriting notepad straight away.
 * MainActivity raises it; the Scan tab (which hosts the notepad) takes it exactly once, so rotating
 * the phone or coming back to the tab later doesn't reopen the notepad.
 */
object HandwritingStartup {
    private val requested = AtomicBoolean(false)

    fun request() = requested.set(true)

    fun isRequested(): Boolean = requested.get()

    /** True once, for whoever opens the notepad. */
    fun consume(): Boolean = requested.getAndSet(false)
}
