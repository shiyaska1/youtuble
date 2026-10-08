package com.mobicareapp.transcribe

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Best-effort spelling correction using Android's own on-device spell checker — the same service
 * behind the keyboard's red-squiggly-underline suggestions, invoked programmatically here instead
 * of interactively. Catches misspelled individual words; it does not fix sentence-level grammar
 * (word order, tense, phrasing) — that would need a much heavier NLP tool this app doesn't have.
 */
object SpellChecker {

    suspend fun correct(context: Context, text: String): String {
        if (text.isBlank()) return text
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
            ?: return text
        return suspendCancellableCoroutine { cont ->
            var session: SpellCheckerSession? = null
            val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
                    // Unused — only the sentence-based call below is made.
                }

                override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                    val corrected = runCatching { applyCorrections(text, results) }.getOrDefault(text)
                    session?.close()
                    if (cont.isActive) cont.resume(corrected)
                }
            }
            session = manager.newSpellCheckerSession(null, null, listener, true)
            val activeSession = session
            if (activeSession == null) {
                if (cont.isActive) cont.resume(text)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { activeSession.close() }
            activeSession.getSentenceSuggestions(arrayOf(TextInfo(text)), 3)
        }
    }

    /** Replacements are collected first and applied back-to-front so earlier offsets in [original] stay valid as later ones shift the string. */
    private fun applyCorrections(original: String, results: Array<out SentenceSuggestionsInfo>?): String {
        val info = results?.firstOrNull() ?: return original
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        for (i in 0 until info.suggestionsCount) {
            val suggestions = info.getSuggestionsInfoAt(i)
            val looksLikeTypo = (suggestions.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
            if (looksLikeTypo && suggestions.suggestionsCount > 0) {
                val offset = info.getOffsetAt(i)
                val length = info.getLengthAt(i)
                replacements += Triple(offset, offset + length, suggestions.getSuggestionAt(0))
            }
        }
        val builder = StringBuilder(original)
        replacements.sortedByDescending { it.first }.forEach { (start, end, replacement) ->
            if (start in 0..builder.length && end in start..builder.length) {
                builder.replace(start, end, replacement)
            }
        }
        return builder.toString()
    }
}
