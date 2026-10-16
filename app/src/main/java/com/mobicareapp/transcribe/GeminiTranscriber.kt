package com.mobicareapp.transcribe

import android.util.Base64
import com.mobicareapp.questionpaper.GeminiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transcribes an audio file with Google's Gemini API — a second engine alongside
 * [AssemblyAiTranscriber], added because AssemblyAI's queue-and-poll flow can take very long.
 * Gemini answers in a single request, handles Malayalam/Hindi/Tamil etc., and uses the same free
 * Google AI Studio key as the question-paper maker (no card needed), so there is no new signup.
 *
 * Short recordings are sent inline in the request; longer ones go through Gemini's Files API first
 * (an inline request is capped at about 20 MB in total).
 */
object GeminiTranscriber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        // Transcribing a long recording is one slow request, not a poll loop.
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private const val BASE = "https://generativelanguage.googleapis.com"

    /** Base64 inflates data by a third, so this keeps the whole request under the ~20 MB inline limit. */
    private const val MAX_INLINE_BYTES = 14L * 1024 * 1024

    private val LANGUAGE_NAMES = mapOf(
        "ml" to "Malayalam", "en" to "English", "hi" to "Hindi",
        "ta" to "Tamil", "te" to "Telugu", "kn" to "Kannada"
    )

    /** [englishOnly] makes the result English text whatever language is spoken (translated in the same step), instead of the spoken language's own script. */
    suspend fun transcribe(
        apiKey: String,
        file: File,
        languageCode: String?,
        onStatus: (String) -> Unit,
        englishOnly: Boolean = false
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mimeType = mimeTypeFor(file)
                val prompt = buildPrompt(languageCode, englishOnly)

                var uploadedName: String? = null
                try {
                    val audioPart: JSONObject = if (file.length() <= MAX_INLINE_BYTES) {
                        onStatus("Reading recording…")
                        val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        JSONObject().put("inline_data", JSONObject().put("mime_type", mimeType).put("data", data))
                    } else {
                        onStatus("Uploading recording…")
                        val uploaded = uploadFile(apiKey, file, mimeType)
                        uploadedName = uploaded.first
                        onStatus("Preparing audio…")
                        waitUntilActive(apiKey, uploaded.first)
                        JSONObject().put("file_data", JSONObject().put("mime_type", mimeType).put("file_uri", uploaded.second))
                    }

                    onStatus("Transcribing…")
                    val errors = mutableListOf<String>()
                    for (model in GeminiModels.candidates(client, apiKey)) {
                        try {
                            return@runCatching generate(apiKey, model, prompt, audioPart)
                        } catch (e: ModelUnavailableException) {
                            errors += "$model: ${e.message}"
                        }
                    }
                    throw IOException(errors.joinToString("; ").ifEmpty { "Couldn't reach the AI service" })
                } finally {
                    // Uploaded files are deleted by Google after 48 hours anyway; removing it now just keeps things tidy.
                    uploadedName?.let { runCatching { deleteFile(apiKey, it) } }
                }
            }
        }

    private fun buildPrompt(languageCode: String?, englishOnly: Boolean): String {
        val language = languageCode?.let { LANGUAGE_NAMES[it] }
        if (englishOnly) {
            val spoken = if (language != null) "The speech is in $language. " else ""
            return "Listen to this audio and write what is said as natural English text. $spoken" +
                "Translate it into English — the output must contain English only, with no Malayalam, Hindi or other scripts. " +
                "Do not summarize or add any commentary, labels or timestamps. Output only the English text. " +
                "If there is no speech at all, reply with exactly: NO_SPEECH"
        }
        val languageRule = if (language != null) {
            "The speech is in $language. Write it in $language's own script."
        } else {
            "Write it in the language that is spoken, using that language's own script."
        }
        return "Transcribe this audio exactly as spoken. $languageRule " +
            "Do not translate, summarize, or add any commentary, labels or timestamps. " +
            "Output only the transcript text. If there is no speech at all, reply with exactly: NO_SPEECH"
    }

    /** Gemini decides how to decode by MIME type, and the app saves recordings as AAC-in-MP4 (.m4a) or WAV. */
    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mp3"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        else -> "audio/mp4"
    }

    private fun generate(apiKey: String, model: String, prompt: String, audioPart: JSONObject): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)).put(audioPart))))
            put("generationConfig", JSONObject().put("temperature", 0))
        }
        val request = Request.Builder()
            .url("$BASE/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful || raw == null) {
                val message = describeError(response.code, raw)
                // 404 = model name gone, 429/503 = this model's free quota is used up or it's busy —
                // each is worth trying the next model for; anything else would fail the same way.
                if (response.code == 404 || response.code == 429 || response.code == 503) throw ModelUnavailableException(message)
                throw IOException(message)
            }
            val json = JSONObject(raw)
            json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
                throw IOException("The AI declined to transcribe this recording ($it).")
            }
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
                if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            return if (text == "NO_SPEECH") "" else text
        }
    }

    /** Gemini's Files API upload is two steps: ask for an upload URL, then send the bytes to it. Returns (file name like "files/abc", file URI). */
    private fun uploadFile(apiKey: String, file: File, mimeType: String): Pair<String, String> {
        val startRequest = Request.Builder()
            .url("$BASE/upload/v1beta/files")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(JSONObject().put("file", JSONObject().put("display_name", "recording")).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException(describeError(response.code, response.body?.string()))
            response.header("x-goog-upload-url") ?: throw IOException("The AI service didn't accept the upload")
        }

        val sendRequest = Request.Builder()
            .url(uploadUrl)
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()
        client.newCall(sendRequest).execute().use { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful || raw == null) throw IOException(describeError(response.code, raw))
            val info = JSONObject(raw).getJSONObject("file")
            return info.getString("name") to info.getString("uri")
        }
    }

    /** Audio files can briefly sit in a PROCESSING state after upload; they can't be used until ACTIVE. */
    private suspend fun waitUntilActive(apiKey: String, name: String) {
        repeat(60) {
            val request = Request.Builder().url("$BASE/v1beta/$name").addHeader("x-goog-api-key", apiKey).get().build()
            val state = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(describeError(response.code, response.body?.string()))
                JSONObject(response.body?.string().orEmpty()).optString("state")
            }
            when (state) {
                "ACTIVE", "" -> return
                "FAILED" -> throw IOException("The AI service couldn't read this recording's format.")
            }
            delay(2000)
        }
        throw IOException("The AI service took too long to prepare the recording.")
    }

    private fun deleteFile(apiKey: String, name: String) {
        val request = Request.Builder().url("$BASE/v1beta/$name").addHeader("x-goog-api-key", apiKey).delete().build()
        client.newCall(request).execute().close()
    }

    private fun describeError(code: Int, body: String?): String {
        val apiMessage = runCatching { JSONObject(body.orEmpty()).getJSONObject("error").getString("message") }.getOrNull()
        return when {
            code == 400 && apiMessage?.contains("API key", ignoreCase = true) == true -> "That Gemini API key isn't valid."
            code == 403 -> "The Gemini API key was rejected (${apiMessage ?: "forbidden"})."
            code == 429 -> "Free daily limit reached for the AI. Try again later, or switch to AssemblyAI."
            else -> apiMessage ?: "AI service error ($code)"
        }
    }

    private class ModelUnavailableException(message: String) : IOException(message)
}
