package com.mobicareapp.questionpaper

import android.content.Context

enum class QuestionType(val label: String, val promptName: String) {
    MCQ("Multiple choice", "mcq"),
    SHORT("Short answer", "short"),
    FILL_BLANK("Fill in the blank", "fill_blank");

    companion object {
        fun fromPrompt(name: String): QuestionType? = entries.firstOrNull { it.promptName.equals(name.trim(), ignoreCase = true) }
    }
}

data class Question(
    val type: QuestionType,
    val text: String,
    /** Only used by [QuestionType.MCQ] — exactly the answer choices, without "A." style prefixes. */
    val options: List<String>,
    val answer: String
)

data class QuestionPaper(
    val number: Int,
    val createdAt: Long,
    val usedAi: Boolean,
    val questions: List<Question>
)

/** Where the user's own free Google AI Studio (Gemini) API key lives — entered once, never sent anywhere except Google's own API. */
object QuestionPaperSettings {
    private const val PREFS = "question_paper_prefs"
    private const val KEY_GEMINI = "gemini_api_key"

    /** The most papers one batch of uploaded pages can produce — keeps the free API quota from being burned by accident. */
    const val MAX_PAPERS = 10
    const val MAX_QUESTIONS = 20

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GEMINI, key.trim()).apply()
    }
}
