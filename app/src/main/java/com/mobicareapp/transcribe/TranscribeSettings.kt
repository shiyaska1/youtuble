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
    enum class Engine { GEMINI, GROQ, SARVAM, ASSEMBLYAI, DEEPGRAM, MISTRAL, HUGGINGFACE, CLOUDFLARE }

    /** The four extra engines share one way of keeping their key. */
    fun isExtra(engine: Engine): Boolean = engine == Engine.DEEPGRAM || engine == Engine.MISTRAL || engine == Engine.HUGGINGFACE || engine == Engine.CLOUDFLARE

    fun engineName(engine: Engine): String = when (engine) {
        Engine.GEMINI -> "Gemini"
        Engine.GROQ -> "Groq"
        Engine.SARVAM -> "Sarvam"
        Engine.ASSEMBLYAI -> "AssemblyAI"
        Engine.DEEPGRAM -> "Deepgram"
        Engine.MISTRAL -> "Mistral"
        Engine.HUGGINGFACE -> "Hugging Face"
        Engine.CLOUDFLARE -> "Cloudflare"
    }

    /** What to type in the key box for an engine. */
    fun engineKeyLabel(engine: Engine): String =
        if (engine == Engine.CLOUDFLARE) "ACCOUNT_ID:API_TOKEN" else "${engineName(engine)} API key"

    /** How to get a free key, for the engines added later. */
    fun engineHelp(engine: Engine): String = when (engine) {
        Engine.DEEPGRAM -> "Deepgram gives new accounts free credit (no card) for its fast speech-to-text. Sign up at console.deepgram.com, create an API key, and paste it here once. It's saved on this phone only."
        Engine.MISTRAL -> "Mistral's Voxtral speech-to-text has a free plan (no card). Sign up at console.mistral.ai, create an API key, and paste it here once. It's saved on this phone only."
        Engine.HUGGINGFACE -> "Hugging Face runs Whisper for free (a little slower, rate-limited). Sign up at huggingface.co, open Settings, Access Tokens, create a token that can use inference, and paste it here once. It's saved on this phone only."
        Engine.CLOUDFLARE -> "Cloudflare Workers AI runs Whisper with a free daily allowance. In dash.cloudflare.com copy your Account ID and create an API token with the Workers AI permission. Paste them here as ACCOUNT_ID:API_TOKEN (a colon between). Saved on this phone only."
        else -> ""
    }

    /** The saved key of any engine (null if not set). */
    fun keyFor(context: Context, engine: Engine): String? = when (engine) {
        Engine.GEMINI -> com.mobicareapp.questionpaper.QuestionPaperSettings.getApiKey(context)
        Engine.GROQ -> getGroqKey(context)
        Engine.SARVAM -> getSarvamKey(context)
        Engine.ASSEMBLYAI -> getApiKey(context)
        else -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key_${engine.name}", null)?.takeIf { it.isNotBlank() }
    }

    fun setKeyFor(context: Context, engine: Engine, key: String) {
        when (engine) {
            Engine.GEMINI -> com.mobicareapp.questionpaper.QuestionPaperSettings.setApiKey(context, key)
            Engine.GROQ -> setGroqKey(context, key)
            Engine.SARVAM -> setSarvamKey(context, key)
            Engine.ASSEMBLYAI -> setApiKey(context, key)
            else -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key_${engine.name}", key.trim()).apply()
        }
    }

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
