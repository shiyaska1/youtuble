package com.mobicareapp.transcribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * More speech-to-text services that can be tried for free with the user's own key: Deepgram (free
 * credit for new accounts), Mistral Voxtral (free plan), Hugging Face (Whisper, free but rate-limited)
 * and Cloudflare Workers AI (Whisper, a free daily allowance). Each takes the whole file in one request
 * and answers with the text, like Groq does. Errors come back as plain messages for the window to show.
 */
object OtherTranscribers {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(
        engine: TranscribeSettings.Engine,
        key: String,
        file: File,
        languageCode: String?,
        onStatus: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onStatus("Transcribing with ${TranscribeSettings.engineName(engine)}…")
            val text = when (engine) {
                TranscribeSettings.Engine.DEEPGRAM -> deepgram(key, file, languageCode)
                TranscribeSettings.Engine.MISTRAL -> mistral(key, file, languageCode)
                TranscribeSettings.Engine.HUGGINGFACE -> huggingFace(key, file)
                TranscribeSettings.Engine.CLOUDFLARE -> cloudflare(key, file)
                else -> throw IllegalArgumentException("Not one of the extra engines")
            }
            Result.success(text.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- Deepgram: the raw file in the body, the key as "Token ..." ----
    private fun deepgram(key: String, file: File, languageCode: String?): String {
        val language = languageCode?.takeIf { it.isNotBlank() }
        val url = "https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true&punctuate=true" +
            if (language != null) "&language=$language" else "&detect_language=true"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $key")
            .post(file.asRequestBody(mimeTypeFor(file).toMediaType()))
            .build()
        val raw = send(request, "Deepgram")
        return JSONObject(raw).getJSONObject("results").getJSONArray("channels").getJSONObject(0)
            .getJSONArray("alternatives").getJSONObject(0).optString("transcript")
    }

    // ---- Mistral Voxtral: an OpenAI-style multipart upload ----
    private fun mistral(key: String, file: File, languageCode: String?): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "voxtral-mini-latest")
            .apply { languageCode?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) } }
            .addFormDataPart("file", file.name, file.asRequestBody(mimeTypeFor(file).toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://api.mistral.ai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()
        return JSONObject(send(request, "Mistral")).optString("text")
    }

    // ---- Hugging Face: Whisper through the Inference API, raw audio in the body ----
    private suspend fun huggingFace(key: String, file: File): String {
        val urls = listOf(
            "https://router.huggingface.co/hf-inference/models/openai/whisper-large-v3",
            "https://api-inference.huggingface.co/models/openai/whisper-large-v3"
        )
        var lastError: IOException? = null
        for (url in urls) {
            // A model that is still starting up answers 503: wait a little and ask again.
            repeat(4) { attempt ->
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("x-wait-for-model", "true")
                    .post(file.asRequestBody(mimeTypeFor(file).toMediaType()))
                    .build()
                try {
                    return JSONObject(send(request, "Hugging Face")).optString("text")
                } catch (e: Unavailable) {
                    lastError = e
                    if (e.code == 503) delay(8000L * (attempt + 1)) else return@repeat
                }
            }
        }
        throw lastError ?: IOException("Hugging Face did not answer. Try again in a minute.")
    }

    // ---- Cloudflare Workers AI: Whisper, the file as the body. The key is "ACCOUNT_ID:API_TOKEN". ----
    private fun cloudflare(key: String, file: File): String {
        val account = key.substringBefore(':', "").trim()
        val token = key.substringAfter(':', "").trim()
        if (account.isEmpty() || token.isEmpty()) {
            throw IOException("For Cloudflare, enter the key as ACCOUNT_ID:API_TOKEN (your account id, a colon, then the token).")
        }
        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/$account/ai/run/@cf/openai/whisper")
            .addHeader("Authorization", "Bearer $token")
            .post(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val json = JSONObject(send(request, "Cloudflare"))
        return json.optJSONObject("result")?.optString("text").orEmpty()
    }

    /** Sends the request and returns the body; a refusal becomes a readable message. */
    private fun send(request: Request, name: String): String {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.isSuccessful) return raw
            val message = describe(response.code, raw, name)
            if (response.code == 503 || response.code == 404) throw Unavailable(response.code, message)
            throw IOException(message)
        }
    }

    private fun describe(code: Int, body: String, name: String): String {
        val detail = runCatching {
            val json = JSONObject(body)
            json.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: json.optJSONObject("error")?.optString("message")
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("err_msg").takeIf { it.isNotBlank() }
                ?: json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (code) {
            401, 403 -> "That $name key isn't valid (or lacks permission)."
            402 -> "$name's free credit is used up."
            413 -> "This recording is too large for $name's free plan. Try a shorter one, or use Gemini."
            429 -> "$name's free limit is used up for now. Try again later."
            else -> detail ?: "$name error ($code)"
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

    private class Unavailable(val code: Int, message: String) : IOException(message)
}
