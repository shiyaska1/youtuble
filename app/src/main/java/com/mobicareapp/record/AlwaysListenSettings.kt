package com.mobicareapp.record

import android.content.Context

/**
 * Persists the "Always listen in background" toggle — a 24/7 mode that keeps the microphone open
 * (permanent notification, extra battery use) waiting for speech with no trigger needed at all.
 * Read by [BootCompletedReceiver] to resume it after a restart, and by the Record screen switch
 * that turns it on/off. Turning it off is the actual battery-saving action: it stops the service
 * outright, releasing the mic and removing the notification, not just pausing it.
 */
object AlwaysListenSettings {
    private const val PREFS = "always_listen"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
