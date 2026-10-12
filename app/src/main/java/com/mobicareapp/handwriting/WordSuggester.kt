package com.mobicareapp.handwriting

import android.content.Context
import java.util.Locale

/**
 * Offers whole-word completions for the first letters of a word, from a bundled list of the ~10,000
 * most common English words (assets/words_en.txt, most common first — derived from Google's Web
 * Trillion Word Corpus, licensed CC BY 3.0). Common words are suggested before rare ones, so a
 * couple of letters usually puts the word you want at the top.
 */
object WordSuggester {
    @Volatile private var words: List<String>? = null

    private fun load(context: Context): List<String> =
        words ?: context.assets.open("words_en.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toList()
        }.also { words = it }

    /** Is [word] in the common-word list? Used to tell whether two pieces of writing are one word split across lines ("me" + "rge" = "merge"). */
    fun isWord(context: Context, word: String): Boolean {
        val letters = word.filter { it.isLetter() }.lowercase(Locale.ROOT)
        if (letters.isEmpty()) return false
        return letters in dictionarySet(context)
    }

    @Volatile private var wordSet: Set<String>? = null

    private fun dictionarySet(context: Context): Set<String> =
        wordSet ?: load(context).toHashSet().also { wordSet = it }

    /**
     * Up to [limit] suggestions for the letters recognized so far. [candidates] are the recognizer's
     * guesses, best first: a hastily written "hel" may also read as "hal", so completions of the top
     * guess come first and the others fill any remaining places. Capitalised like the writing is.
     */
    fun suggest(context: Context, candidates: List<String>, limit: Int = 4): List<String> {
        val dictionary = load(context)
        val prefixes = candidates
            .map { candidate -> candidate to candidate.filter { it.isLetter() }.lowercase(Locale.ROOT) }
            .filter { (_, prefix) -> prefix.length >= MIN_LETTERS }
        if (prefixes.isEmpty()) return emptyList()

        val result = linkedSetOf<String>()
        // Best guess gets most of the places; the rest each add a couple.
        prefixes.forEachIndexed { index, (original, prefix) ->
            val quota = if (index == 0) limit else 2
            var taken = 0
            for (word in dictionary) {
                if (taken >= quota || result.size >= limit) break
                if (word.startsWith(prefix)) {
                    val shown = if (original.firstOrNull()?.isUpperCase() == true) word.replaceFirstChar { it.uppercase() } else word
                    if (result.add(shown)) taken++
                }
            }
        }
        return result.toList()
    }

    /** Suggestions start from two letters — one letter matches far too many words to be useful. */
    private const val MIN_LETTERS = 2
}
