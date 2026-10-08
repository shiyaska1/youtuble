package com.mobicareapp.record

import android.content.Context

/** Persists the Record screen's "Keep screen on while recording" checkbox (video mode) — off by default, since normally letting the screen lock is preferred for battery life; only turned on when someone specifically wants to watch the live preview the whole time. */
object KeepScreenOnSettings {
    private const val PREFS = "keep_screen_on"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
