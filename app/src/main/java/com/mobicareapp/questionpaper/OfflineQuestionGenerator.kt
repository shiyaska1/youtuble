package com.mobicareapp.questionpaper

/**
 * No-AI fallback for when there's no internet or no API key: builds questions straight from the
 * book's own sentences. Far less clever than [GeminiQuestionGenerator] — it blanks out a key word
 * for fill-in-the-blank and multiple-choice questions, and turns a sentence into a "what does the
 * text say about…" short answer — but it's free, instant and works offline.
 */
object OfflineQuestionGenerator {

    fun generate(sourceText: String, count: Int, types: List<QuestionType>, alreadyAsked: List<String>): List<Question> {
        val asked = alreadyAsked.toHashSet()
        val sentences = sourceText
            .split(Regex("(?<=[.!?।])\\s+|\\n{2,}"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 40..240 }
            .distinct()
            .shuffled()
        if (sentences.isEmpty()) return emptyList()

        // Word pool for multiple-choice distractors: other key terms from the same material, so
        // wrong options look like they belong to the book rather than being random words.
        val keyTerms = sentences.mapNotNull { keyTermOf(it) }.distinct()

        val result = mutableListOf<Question>()
        var typeIndex = 0
        for (sentence in sentences) {
            if (result.size >= count) break
            val type = types[typeIndex % types.size]
            val question = build(type, sentence, keyTerms) ?: continue
            if (question.text in asked) continue
            result += question
            typeIndex++
        }
        return result
    }

    private fun build(type: QuestionType, sentence: String, keyTerms: List<String>): Question? {
        val term = keyTermOf(sentence) ?: return null
        val blanked = sentence.replaceFirst(term, "______")
        return when (type) {
            QuestionType.FILL_BLANK -> Question(type, blanked, emptyList(), term)
            QuestionType.MCQ -> {
                val distractors = keyTerms.filter { !it.equals(term, ignoreCase = true) }.shuffled().take(3)
                if (distractors.size < 3) return null
                Question(type, blanked, (distractors + term).shuffled(), term)
            }
            QuestionType.SHORT -> Question(type, "Write what the text says about \"$term\".", emptyList(), sentence)
        }
    }

    /** The most "important-looking" word: prefers capitalised mid-sentence words (names, terms), otherwise the longest word. */
    private fun keyTermOf(sentence: String): String? {
        val words = Regex("[\\p{L}\\p{N}]{5,}").findAll(sentence).map { it.value }.toList()
        if (words.isEmpty()) return null
        val firstWord = sentence.takeWhile { !it.isWhitespace() }
        return words.firstOrNull { it[0].isUpperCase() && it != firstWord }
            ?: words.maxByOrNull { it.length }
    }
}
