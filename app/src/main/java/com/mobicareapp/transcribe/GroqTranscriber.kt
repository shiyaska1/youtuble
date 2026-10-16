package com.mobicareapp.transcribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transcribes with OpenAI's Whisper model as hosted by Groq — a third engine, for when AssemblyAI
 * is slow. Groq's free tier needs only an email signup (no card) and is very fast, since the
 * whole file goes up in one request and the text comes straight back (no queue to poll). The
 * catch: the free tier takes files up to 25 MB, and Whisper's Malayalam is only moderately
 * accurate (its English, Hindi and Tamil are better).
 *
 * "English only" uses Groq's separate translation endpoint, which writes English text whatever
 * language is spoken.
 */
object GroqTranscriber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private const val MAX_BYTES = 25L * 1024 * 1024
    // Tried in order: the full model is the most accurate for non-English speech; the turbo one has its own separate quota.
    private val MODELS = listOf("whisper-large-v3", "whisper-large-v3-turbo")

    suspend fun transcribe(
        apiKey: String,
        file: File,
        languageCode: String?,
        onStatus: (String) -> Unit,
        englishOnly: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (file.length() > MAX_BYTES) {
                throw IOException("This recording is larger than 25 MB, the most Groq's free plan accepts. Use Gemini for long recordings.")
            }
            onStatus("Transcribing…")
            val endpoint = if (englishOnly) "translations" else "transcriptions"
            val errors = mutableListOf<String>()
            for (model in MODELS) {
                try {
                    return@withContext Result.success(call(apiKey, endpoint, model, file, languageCode.takeIf { !englishOnly }))
                } catch (e: ModelUnavailableException) {
                    errors += "$model: ${e.message}"
                }
            }
            throw IOException(errors.joinToString("; "))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun call(apiKey: String, endpoint: String, model: String, file: File, languageCode: String?): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (languageCode != null) addFormDataPart("language", languageCode) }
            .addFormDataPart("file", file.name, file.asRequestBody(mimeTypeFor(file).toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/$endpoint")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = describeError(response.code, raw)
                // 404/429/503: this model is gone, out of free quota, or busy — the other one may still work.
                if (response.code == 404 || response.code == 429 || response.code == 503) throw ModelUnavailableException(message)
                throw IOException(message)
            }
            return JSONObject(raw).optString("text").trim()
        }
    }

    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        else -> "audio/mp4"
    }

    private fun describeError(code: Int, body: String): String {
        val apiMessage = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            401 -> "That Groq API key isn't valid."
            413 -> "This recording is too large for Groq's free plan (25 MB)."
            429 -> "Groq's free limit is used up for now. Try again in a few minutes."
            else -> apiMessage ?: "Groq error ($code)"
        }
    }

    private class ModelUnavailableException(message: String) : IOException(message)
}
