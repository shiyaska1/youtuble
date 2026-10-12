package com.mobicareapp.handwriting

import android.content.Context
import com.mobicareapp.transcribe.SpellChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Repairs spelling and grammar in recognized handwriting. Uses LanguageTool's free public service
 * (the text is sent to languagetool.org to be checked); with no internet, or if that service
 * doesn't answer, it falls back to the phone's own offline spell checker, which fixes misspelled
 * words but not grammar.
 *
 * Only clear mistakes are changed — typos, grammar, capital letters, confused words, punctuation —
 * not stylistic suggestions, so the writer's own wording is left alone.
 */
object GrammarFixer {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val FIXABLE_CATEGORIES = setOf("TYPOS", "GRAMMAR", "CASING", "CONFUSED_WORDS", "PUNCTUATION", "COMPOUNDING")

    /** LanguageTool's free tier accepts about 20,000 characters per request. */
    private const val MAX_CHUNK = 15_000

    suspend fun fix(context: Context, text: String): String {
        if (text.isBlank()) return text
        val online = withContext(Dispatchers.IO) { runCatching { checkOnline(text) }.getOrNull() }
        return online ?: SpellChecker.correct(context, text)
    }

    private fun checkOnline(text: String): String {
        // Whole lines per request, so a correction never has to span a chunk boundary.
        val chunks = mutableListOf<StringBuilder>()
        for (line in text.split('\n')) {
            if (chunks.isEmpty() || chunks.last().length + line.length + 1 > MAX_CHUNK) chunks += StringBuilder()
            if (chunks.last().isNotEmpty()) chunks.last().append('\n')
            chunks.last().append(line)
        }
        return chunks.joinToString("\n") { applyMatches(it.toString()) }
    }

    private fun applyMatches(chunk: String): String {
        val body = FormBody.Builder().add("text", chunk).add("language", "en-US").build()
        val request = Request.Builder().url("https://api.languagetool.org/v2/check").post(body).build()
        val raw = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("LanguageTool HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val matches = JSONObject(raw).getJSONArray("matches")
        val builder = StringBuilder(chunk)
        var lowestApplied = Int.MAX_VALUE
        // Back to front, so earlier offsets stay valid as later text changes length.
        for (i in matches.length() - 1 downTo 0) {
            val match = matches.getJSONObject(i)
            val category = match.getJSONObject("rule").getJSONObject("category").getString("id")
            if (category !in FIXABLE_CATEGORIES) continue
            val replacements = match.getJSONArray("replacements")
            if (replacements.length() == 0) continue
            val offset = match.getInt("offset")
            val end = offset + match.getInt("length")
            if (end > lowestApplied || end > builder.length) continue // overlaps a fix already made
            builder.replace(offset, end, replacements.getJSONObject(0).getString("value"))
            lowestApplied = offset
        }
        return builder.toString()
    }
}
