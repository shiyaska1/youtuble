package com.mobicareapp.upload

import android.content.Context

/** The web page the Record screen's "Upload" button opens. The user's own server page; changeable inside the screen (⋮ → Change address) so a new link needs no new app build. */
object UploadWebSettings {
    const val DEFAULT_URL = "https://eschoolsolutions.xyz/phpmailer/ai/upload.php"

    private const val PREFS = "upload_web_prefs"
    private const val KEY_URL = "url"

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, normalize(url)).apply()
    }

    /** Adds https:// when the user typed just a host/path, so "example.com/upload.php" works. */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
