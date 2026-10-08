package com.mobicareapp.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transcribes an audio file to text via AssemblyAI's REST API (upload the file, start a
 * transcription job against the uploaded URL, poll until done). Chosen over on-device Whisper:
 * there's no genuinely working pre-built Android library for whisper.cpp (the commonly-referenced
 * one doesn't actually resolve as a dependency), and hand-building one via the NDK from raw C++
 * source would be a much bigger, riskier undertaking than anything else in this app.
 *
 * Needs the user's own free AssemblyAI API key (from assemblyai.com) — this app has no account or
 * key of its own. See [TranscribeSettings] for where that key is stored.
 */
object AssemblyAiTranscriber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Audio uploads can be tens of MB over a slow connection — the default write timeout is
        // far too short for that.
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * [languageCode] should almost always be passed explicitly (e.g. "ml" for Malayalam) — leaving
     * it null falls back to AssemblyAI's own automatic language detection, which in testing
     * confused Malayalam for Tamil (both Dravidian languages with real acoustic similarity).
     * Naming the language up front routes the request straight to the right model instead of
     * guessing.
     */
    suspend fun transcribe(apiKey: String, file: File, languageCode: String?, onStatus: (String) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                onStatus("Uploading…")
                val uploadUrl = upload(apiKey, file)
                onStatus("Transcribing…")
                val transcriptId = createTranscript(apiKey, uploadUrl, languageCode)
                pollUntilDone(apiKey, transcriptId, onStatus)
            }
        }

    private fun upload(apiKey: String, file: File): String {
        val request = Request.Builder()
            .url("https://api.assemblyai.com/v2/upload")
            .addHeader("authorization", apiKey)
            .post(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw IOException(describeError(response.code, body, "Couldn't upload the audio"))
            }
            return JSONObject(body).getString("upload_url")
        }
    }

    private fun createTranscript(apiKey: String, audioUrl: String, languageCode: String?): String {
        val json = JSONObject().apply {
            put("audio_url", audioUrl)
            if (languageCode != null) {
                put("language_code", languageCode)
                put("language_detection", false)
            } else {
                put("language_detection", true)
            }
        }
        val request = Request.Builder()
            .url("https://api.assemblyai.com/v2/transcript")
            .addHeader("authorization", apiKey)
            .addHeader("content-type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw IOException(describeError(response.code, body, "Couldn't start transcription"))
            }
            return JSONObject(body).getString("id")
        }
    }

    private suspend fun pollUntilDone(apiKey: String, transcriptId: String, onStatus: (String) -> Unit): String {
        val request = Request.Builder()
            .url("https://api.assemblyai.com/v2/transcript/$transcriptId")
            .addHeader("authorization", apiKey)
            .build()
        while (true) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    throw IOException(describeError(response.code, body, "Couldn't check transcription status"))
                }
                val json = JSONObject(body)
                when (json.optString("status")) {
                    "completed" -> return json.optString("text")
                    "error" -> throw IOException(json.optString("error", "Transcription failed"))
                    else -> onStatus("Transcribing…")
                }
            }
            delay(3000)
        }
    }

    /** AssemblyAI's error responses are a JSON body with an "error" field — surfaced when present, since "Server returned 401" alone doesn't tell the user their key is wrong. */
    private fun describeError(code: Int, body: String?, fallback: String): String {
        val detail = body?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
        return if (!detail.isNullOrBlank()) detail else "$fallback (server returned $code)"
    }
}
