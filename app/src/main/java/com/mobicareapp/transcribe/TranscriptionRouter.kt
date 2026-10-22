package com.mobicareapp.transcribe

import android.content.Context
import com.mobicareapp.questionpaper.QuestionPaperSettings
import java.io.File

/**
 * Runs a transcription with whichever engine the user last picked in the Transcribe window
 * ([TranscribeSettings.getEngine]), so other screens that need "audio file -> text" (like the
 * English speech window) don't each hard-code one service. The speech is English there, so no
 * translation step is involved.
 */
object TranscriptionRouter {

    /** Which engine would run, and whether its API key has been set up yet. */
    fun missingKeyMessage(context: Context): String? {
        val engine = TranscribeSettings.getEngine(context)
        val hasKey = when (engine) {
            TranscribeSettings.Engine.GEMINI -> QuestionPaperSettings.getApiKey(context) != null
            TranscribeSettings.Engine.GROQ -> TranscribeSettings.getGroqKey(context) != null
            TranscribeSettings.Engine.SARVAM -> TranscribeSettings.getSarvamKey(context) != null
            TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeSettings.getApiKey(context) != null
            else -> TranscribeSettings.keyFor(context, engine) != null
        }
        if (hasKey) return null
        val name = when (engine) {
            TranscribeSettings.Engine.GEMINI -> "Gemini"
            TranscribeSettings.Engine.GROQ -> "Groq"
            TranscribeSettings.Engine.SARVAM -> "Sarvam"
            TranscribeSettings.Engine.ASSEMBLYAI -> "AssemblyAI"
            else -> TranscribeSettings.engineName(engine)
        }
        return "Set up your $name API key first: open Library, tap ⋮ on a recording, choose Transcribe, and pick $name there."
    }

    suspend fun transcribe(context: Context, file: File, languageCode: String?, onStatus: (String) -> Unit): Result<String> {
        missingKeyMessage(context)?.let { return Result.failure(IllegalStateException(it)) }
        val chosen = TranscribeSettings.getEngine(context)
        return when (chosen) {
            TranscribeSettings.Engine.GEMINI ->
                GeminiTranscriber.transcribe(QuestionPaperSettings.getApiKey(context)!!, file, languageCode, onStatus, englishOnly = false)
            TranscribeSettings.Engine.GROQ ->
                GroqTranscriber.transcribe(TranscribeSettings.getGroqKey(context)!!, file, languageCode, onStatus, englishOnly = false)
            TranscribeSettings.Engine.SARVAM ->
                SarvamTranscriber.transcribe(TranscribeSettings.getSarvamKey(context)!!, file, context.cacheDir, languageCode, onStatus, englishOnly = false)
            TranscribeSettings.Engine.ASSEMBLYAI ->
                AssemblyAiTranscriber.transcribe(TranscribeSettings.getApiKey(context)!!, file, languageCode, onStatus)
            else ->
                OtherTranscribers.transcribe(chosen, TranscribeSettings.keyFor(context, chosen)!!, file, languageCode, onStatus)
        }
    }
}
