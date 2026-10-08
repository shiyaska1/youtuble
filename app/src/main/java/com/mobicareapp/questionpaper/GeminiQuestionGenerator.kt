package com.mobicareapp.questionpaper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Writes a question paper from book text using Google's Gemini API — chosen because Google AI
 * Studio hands out API keys with a free tier (no card needed), so the app costs the user nothing
 * to run. Needs the user's own key (see [QuestionPaperSettings]); this app has no key of its own.
 * Free-tier limits are Google's to change, which is why a few model names are tried in turn
 * (each model has its own quota) and the real error message is surfaced when all of them fail.
 */
object GeminiQuestionGenerator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()


    /** Keeps the request well inside the free tier's per-minute token limit; roughly 15–20 dense book pages. */
    private const val MAX_SOURCE_CHARS = 30_000
    private const val MAX_AVOID_QUESTIONS = 60

    suspend fun generate(
        apiKey: String,
        sourceText: String,
        questionCount: Int,
        types: List<QuestionType>,
        alreadyAsked: List<String>
    ): Result<List<Question>> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = buildPrompt(sourceText.take(MAX_SOURCE_CHARS), questionCount, types, alreadyAsked)
            val errors = mutableListOf<String>()
            for (model in GeminiModels.candidates(client, apiKey)) {
                try {
                    val questions = callModel(apiKey, model, prompt)
                    if (questions.isEmpty()) throw IOException("The AI returned no usable questions — try again.")
                    return@runCatching questions.take(questionCount)
                } catch (e: ModelUnavailableException) {
                    errors += "$model: ${e.message}"
                }
            }
            throw IOException(errors.joinToString("; ").ifEmpty { "Couldn't reach the AI service" })
        }
    }

    /** Spreads [count] across [types] up front, so the paper reliably has the mix the user picked instead of leaving it to the model. */
    private fun buildPrompt(text: String, count: Int, types: List<QuestionType>, alreadyAsked: List<String>): String {
        val perType = types.mapIndexed { index, type ->
            type to (count / types.size + if (index < count % types.size) 1 else 0)
        }.filter { it.second > 0 }
        val mix = perType.joinToString("\n") { (type, n) ->
            val rule = when (type) {
                QuestionType.MCQ -> "\"mcq\": 4 options in \"options\", and \"answer\" is the exact text of the one correct option"
                QuestionType.SHORT -> "\"short\": a question answerable in 1-3 sentences; \"options\" is []; \"answer\" is a model answer"
                QuestionType.FILL_BLANK -> "\"fill_blank\": a sentence from the material with one key term replaced by ______; \"options\" is []; \"answer\" is the missing term"
            }
            "- $n × $rule"
        }
        val avoid = alreadyAsked.takeLast(MAX_AVOID_QUESTIONS).let { asked ->
            if (asked.isEmpty()) "" else
                "\nThese questions were already used in earlier papers. Do NOT repeat or rephrase them — cover different parts of the material:\n" +
                    asked.joinToString("\n") { "- $it" }
        }
        return """
            You are a teacher writing an exam paper. Use ONLY the book material below — never add outside facts.
            Write the questions in the same language as the material.
            Write exactly $count questions in total, in this mix:
            $mix
            $avoid

            Reply with JSON only, in exactly this shape:
            {"questions":[{"type":"mcq|short|fill_blank","question":"...","options":["..."],"answer":"..."}]}

            BOOK MATERIAL:
            $text
        """.trimIndent()
    }

    private fun callModel(apiKey: String, model: String, prompt: String): List<Question> {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.9))
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful || raw == null) {
                val message = describeError(response.code, raw)
                // 404 = this model name no longer exists, 429/503 = this model's free quota is used
                // up or it's overloaded — all worth trying the next model for. Anything else (a bad
                // key, a malformed request) would fail identically on every model.
                if (response.code == 404 || response.code == 429 || response.code == 503) throw ModelUnavailableException(message)
                throw IOException(message)
            }
            val text = JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text")
                ?: throw IOException("The AI returned an empty reply — try again.")
            return parseQuestions(text)
        }
    }

    private fun parseQuestions(json: String): List<Question> {
        val cleaned = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = try {
            JSONObject(cleaned).optJSONArray("questions")
        } catch (_: Exception) {
            runCatching { JSONArray(cleaned) }.getOrNull()
        } ?: throw IOException("The AI's reply wasn't in the expected format — try again.")

        val result = mutableListOf<Question>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = QuestionType.fromPrompt(item.optString("type")) ?: continue
            val text = item.optString("question").trim()
            val answer = item.optString("answer").trim()
            if (text.isEmpty() || answer.isEmpty()) continue
            val options = item.optJSONArray("options")?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
            if (type == QuestionType.MCQ && options.size < 2) continue
            result += Question(type, text, if (type == QuestionType.MCQ) options else emptyList(), answer)
        }
        return result
    }

    private fun describeError(code: Int, body: String?): String {
        val apiMessage = runCatching { JSONObject(body.orEmpty()).getJSONObject("error").getString("message") }.getOrNull()
        return when {
            code == 400 && apiMessage?.contains("API key", ignoreCase = true) == true -> "That API key isn't valid — check it in Settings."
            code == 403 -> "The API key was rejected (${apiMessage ?: "forbidden"}). Check it in Settings."
            code == 429 -> "Free daily limit reached. Try again later, or tomorrow."
            else -> apiMessage ?: "AI service error ($code)"
        }
    }

    private class ModelUnavailableException(message: String) : IOException(message)
}
