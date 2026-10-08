package com.mobicareapp.transcribe

import android.content.Context

/** Stores the user's own AssemblyAI API key and last-picked transcription language locally — entered once in the transcribe dialog, never sent anywhere except directly to AssemblyAI's own API. */
object TranscribeSettings {
    private const val PREFS = "transcribe_prefs"
    private const val KEY_API_KEY = "assemblyai_api_key"
    private const val KEY_LANGUAGE = "assemblyai_language_code"

    private const val KEY_ENGINE = "engine"
    private const val KEY_ENGLISH_ONLY = "english_only"

    /** Which speech-to-text service the Transcribe dialog uses. Gemini is the default: it answers in one request (AssemblyAI queues and polls, which can take very long) and shares the free Gemini key already used for question papers. */
    enum class Engine { GEMINI, GROQ, SARVAM, ASSEMBLYAI }

    private const val KEY_SARVAM = "sarvam_api_key"

    fun getSarvamKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SARVAM, null)?.takeIf { it.isNotBlank() }

    fun setSarvamKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SARVAM, key.trim()).apply()
    }

    private const val KEY_GROQ = "groq_api_key"

    fun getGroqKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }

    fun setGroqKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GROQ, key.trim()).apply()
    }

    fun getEngine(context: Context): Engine =
        runCatching { Engine.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, null) ?: "") }
            .getOrDefault(Engine.GEMINI)

    fun setEngine(context: Context, engine: Engine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine.name).apply()
    }

    /** Gemini only: write the transcript in English whatever language is spoken. */
    fun getEnglishOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENGLISH_ONLY, false)

    fun setEnglishOnly(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENGLISH_ONLY, value).apply()
    }

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    /** Null means "auto-detect" — remembered so a repeat transcription in the same language doesn't need re-picking it each time. */
    fun getLanguageCode(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "ml")

    fun setLanguageCode(context: Context, code: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, code).apply()
    }
}
