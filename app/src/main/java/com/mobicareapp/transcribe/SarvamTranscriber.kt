package com.mobicareapp.transcribe

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transcribes with Sarvam AI, an Indian company whose speech models are built for Indian
 * languages (Malayalam, Hindi, Tamil, Telugu, Kannada…) and cope with Malayalam–English mixed
 * speech — a fourth engine, for people whose work is mostly Indian-language audio. Its free signup
 * needs no card. Its translate mode writes English text from Indian-language speech in one step.
 *
 * Sarvam's quick API only accepts clips under 30 seconds, so the recording is first cut into
 * 25-second pieces with FFmpeg (already part of this app), sent a few at a time, and the texts are
 * joined in order. A cut can fall in the middle of a word, so a word at a joint is occasionally
 * clipped; it is the trade-off for not needing a separate batch-job flow.
 */
object SarvamTranscriber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private const val ENDPOINT = "https://api.sarvam.ai/speech-to-text"
    private const val SEGMENT_SECONDS = 25
    private const val PARALLEL_REQUESTS = 3

    private val LANGUAGE_CODES = mapOf(
        "ml" to "ml-IN", "en" to "en-IN", "hi" to "hi-IN",
        "ta" to "ta-IN", "te" to "te-IN", "kn" to "kn-IN"
    )

    suspend fun transcribe(
        apiKey: String,
        file: File,
        cacheDir: File,
        languageCode: String?,
        onStatus: (String) -> Unit,
        englishOnly: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val workDir = File(cacheDir, "sarvam_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            onStatus("Preparing audio…")
            val parts = split(file, workDir)
            val language = languageCode?.let { LANGUAGE_CODES[it] } ?: "unknown"
            val mode = if (englishOnly) "translate" else "transcribe"

            // The first piece also settles which model name the API accepts, so the rest don't each retry.
            var model: String? = "saaras:v3"
            val done = AtomicInteger(0)
            val texts = arrayOfNulls<String>(parts.size)
            val first = parts.first()
            texts[0] = try {
                callChunk(apiKey, first, model, language, mode)
            } catch (e: ModelRejectedException) {
                model = null
                callChunk(apiKey, first, model, language, mode)
            }
            onStatus("Transcribing… 1 of ${parts.size}")
            done.set(1)

            val semaphore = Semaphore(PARALLEL_REQUESTS)
            coroutineScope {
                (1 until parts.size).map { index ->
                    async {
                        semaphore.withPermit {
                            texts[index] = callChunkWithRetry(apiKey, parts[index], model, language, mode)
                            onStatus("Transcribing… ${done.incrementAndGet()} of ${parts.size}")
                        }
                    }
                }.awaitAll()
            }
            Result.success(texts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ").trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Converts to 16 kHz mono WAV and cuts into [SEGMENT_SECONDS]-second files — a format and length the API takes. */
    private fun split(input: File, workDir: File): List<File> {
        val pattern = File(workDir, "part_%03d.wav").absolutePath
        val session = FFmpegKit.execute(
            "-y -i \"${input.absolutePath}\" -vn -ar 16000 -ac 1 -c:a pcm_s16le " +
                "-f segment -segment_time $SEGMENT_SECONDS -reset_timestamps 1 \"$pattern\""
        )
        if (!ReturnCode.isSuccess(session.returnCode)) throw IOException("Couldn't read this recording's audio.")
        val parts = workDir.listFiles { f -> f.name.startsWith("part_") }?.sortedBy { it.name }.orEmpty()
        if (parts.isEmpty()) throw IOException("No audio found in this recording.")
        return parts
    }

    /** A free-tier rate limit shows up as 429 when pieces go out in parallel — wait a moment and try again rather than failing the whole recording. */
    private suspend fun callChunkWithRetry(apiKey: String, part: File, model: String?, language: String, mode: String): String {
        var attempt = 0
        while (true) {
            try {
                return callChunk(apiKey, part, model, language, mode)
            } catch (e: RateLimitedException) {
                if (++attempt >= 4) throw IOException("Sarvam's free limit is busy right now. Try again in a minute.")
                delay(2000L * attempt)
            }
        }
    }

    private fun callChunk(apiKey: String, part: File, model: String?, language: String, mode: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("language_code", language)
            .addFormDataPart("mode", mode)
            .apply { if (model != null) addFormDataPart("model", model) }
            .addFormDataPart("file", part.name, part.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("api-subscription-key", apiKey)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = describeError(raw)
                when {
                    response.code == 401 || response.code == 403 -> throw IOException("That Sarvam API key isn't valid.")
                    response.code == 429 -> throw RateLimitedException()
                    // A 400/404/422 that names the model means this model name isn't accepted.
                    model != null && detail.contains("model", ignoreCase = true) -> throw ModelRejectedException(detail)
                    else -> throw IOException(detail.ifBlank { "Sarvam error (${response.code})" })
                }
            }
            return JSONObject(raw).optString("transcript").trim()
        }
    }

    private fun describeError(raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.take(160)
        val error = json.opt("error")
        return when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> json.optString("message").ifBlank { json.optString("detail") }
        }.take(200)
    }

    private class RateLimitedException : IOException("rate limited")
    private class ModelRejectedException(message: String) : IOException(message)
}
