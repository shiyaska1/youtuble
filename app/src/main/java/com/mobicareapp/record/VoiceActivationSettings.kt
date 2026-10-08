package com.mobicareapp.record

import android.content.Context

/** Persists the Record screen's "Voice-activated recording" toggle, so a widget or app-shortcut tap (which has no screen to ask on) uses whatever the user last chose in-app. */
object VoiceActivationSettings {
    private const val PREFS = "voice_activation"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
