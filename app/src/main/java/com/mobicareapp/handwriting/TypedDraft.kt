package com.mobicareapp.handwriting

import android.content.Context

/**
 * What is half-done on the first screen, kept so that nothing is lost if the app crashes, is swiped
 * away or is closed: the text typed (or transcribed) in the Type text window that has not been put on
 * the page yet, whether that window was open, and a voice recording whose transcription had not finished.
 */
object TypedDraft {
    private const val PREFS = "hw_typed_draft"
    private const val KEY_TEXT = "text"
    private const val KEY_OPEN = "open"
    private const val KEY_VOICE = "voice"
    private const val KEY_PAD_VOICE = "pad_voice"
    private const val KEY_PAD_VOICE_FIRST = "pad_voice_first"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun text(context: Context): String = prefs(context).getString(KEY_TEXT, "") ?: ""

    /** Written at once (commit), not in the background: a kill right after must not lose it. */
    fun setText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_TEXT, text).commit()
    }

    /** True while the Type text window is open; stays true if the app dies with it open, so it opens again next time. */
    fun isOpen(context: Context): Boolean = prefs(context).getBoolean(KEY_OPEN, false) && text(context).isNotBlank()

    fun setOpen(context: Context, open: Boolean) {
        prefs(context).edit().putBoolean(KEY_OPEN, open).commit()
    }

    /** The text went onto the page: the draft is done with. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TEXT).putBoolean(KEY_OPEN, false).commit()
    }

    /** A recording being transcribed from the Type text window (null when none). */
    fun pendingVoice(context: Context): String? = prefs(context).getString(KEY_VOICE, null)

    fun setPendingVoice(context: Context, path: String?) {
        prefs(context).edit().also { if (path == null) it.remove(KEY_VOICE) else it.putString(KEY_VOICE, path) }.commit()
    }

    /** A voice being transcribed onto a blank screen of the pad: its file, and whether it goes on the first blank screen. */
    fun padVoice(context: Context): Pair<String, Boolean>? =
        prefs(context).getString(KEY_PAD_VOICE, null)?.let { it to prefs(context).getBoolean(KEY_PAD_VOICE_FIRST, false) }

    fun setPadVoice(context: Context, path: String, firstBlank: Boolean) {
        prefs(context).edit().putString(KEY_PAD_VOICE, path).putBoolean(KEY_PAD_VOICE_FIRST, firstBlank).commit()
    }

    fun clearPadVoice(context: Context) {
        prefs(context).edit().remove(KEY_PAD_VOICE).remove(KEY_PAD_VOICE_FIRST).commit()
    }
}
