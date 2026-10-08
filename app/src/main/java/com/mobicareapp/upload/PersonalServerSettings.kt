package com.mobicareapp.upload

import android.content.Context

/** Persists the URL/secret for the user's own upload.php-style server (see server/upload.php in the project) — a simple always-overwrite-one-file relay, used when a proper cloud service isn't wanted. */
object PersonalServerSettings {
    private const val PREFS = "personal_server"
    private const val KEY_URL = "upload_url"
    private const val KEY_SECRET = "secret"

    fun getUploadUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    fun setUploadUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, url.trim()).apply()
    }

    fun getSecret(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SECRET, "") ?: ""

    fun setSecret(context: Context, secret: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SECRET, secret).apply()
    }

    fun isConfigured(context: Context): Boolean = getUploadUrl(context) != null
}
