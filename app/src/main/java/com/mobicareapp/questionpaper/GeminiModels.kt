package com.mobicareapp.questionpaper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Works out which Gemini models to try instead of hard-coding names — Google retires model names
 * regularly (a fixed "gemini-2.0-flash" started failing with "no longer available"), so the app
 * asks the API which models this key can use and tries the newest "flash" ones first. Shared by
 * the question-paper maker and the transcriber.
 */
internal object GeminiModels {
    private val FALLBACK = listOf("gemini-flash-latest", "gemini-2.5-flash")
    private val FLASH = Regex("^models/(gemini-(\\d+(?:\\.\\d+)?)-flash(?:-[a-z0-9.-]+)?)$")
    // Variants that can't do plain text/audio -> text generation, or that are experimental.
    private val EXCLUDED = Regex("lite|image|tts|live|audio|thinking|exp|robotics|computer|embedding|learnlm")

    @Volatile private var cached: Pair<Long, List<String>>? = null
    private const val CACHE_MILLIS = 60 * 60 * 1000L

    /** Blocking — call from a background dispatcher. Never throws; falls back to a fixed list. */
    fun candidates(client: OkHttpClient, apiKey: String): List<String> {
        cached?.takeIf { System.currentTimeMillis() - it.first < CACHE_MILLIS }?.let { return it.second }
        val discovered = runCatching { fetch(client, apiKey) }.getOrDefault(emptyList())
        val result = (discovered + FALLBACK).distinct()
        if (discovered.isNotEmpty()) cached = System.currentTimeMillis() to result
        return result
    }

    private fun fetch(client: OkHttpClient, apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .addHeader("x-goog-api-key", apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val models = JSONObject(response.body?.string().orEmpty()).optJSONArray("models") ?: return emptyList()
            val found = mutableListOf<Triple<String, Double, Boolean>>() // short name, version, isPreview
            for (i in 0 until models.length()) {
                val model = models.getJSONObject(i)
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                if ((0 until methods.length()).none { methods.getString(it) == "generateContent" }) continue
                val match = FLASH.matchEntire(model.optString("name")) ?: continue
                val shortName = match.groupValues[1]
                if (EXCLUDED.containsMatchIn(shortName)) continue
                found += Triple(shortName, match.groupValues[2].toDoubleOrNull() ?: 0.0, shortName.contains("preview"))
            }
            // Stable before preview, then newest version first; the top few are plenty to fall back through.
            return found.sortedWith(compareBy({ it.third }, { -it.second })).map { it.first }.take(4)
        }
    }
}
