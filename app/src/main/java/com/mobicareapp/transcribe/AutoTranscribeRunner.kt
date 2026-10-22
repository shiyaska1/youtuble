package com.mobicareapp.transcribe

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mobicareapp.MainActivity
import com.mobicareapp.YtSaverApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Fires a transcription for a just-finished audio recording with no UI involved — used when a
 * recording is stopped from the home-screen widget or an app shortcut, where there's no Record
 * screen open to show the usual "Recording saved — Transcribe" card on. Runs on its own
 * independent scope (not the recording service's, which is already stopping itself by the time
 * this starts) and reports the result as a notification, since that's the only way to reach the
 * user without any app screen open.
 *
 * Uses whichever language was last picked in the Transcribe dialog (defaults to Malayalam — see
 * [TranscribeSettings]), not AssemblyAI's own auto-detect: leaving the language unspecified is
 * exactly what caused Malayalam to come back transcribed as Tamil in testing, so a "no language
 * chosen" flow here would repeat that, not avoid it.
 */
object AutoTranscribeRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val NOTIFICATION_ID = 61

    fun runInBackground(context: Context, filePath: String) {
        val appContext = context.applicationContext
        // Can't ask for an API key with no dialog to show it in — still worth a notification
        // rather than doing nothing with zero feedback, which just looks like the whole feature
        // silently failed instead of "you haven't set this up yet."
        val languageCode = TranscribeSettings.getLanguageCode(appContext)
        val engine = TranscribeSettings.getEngine(appContext)
        val apiKey = when (engine) {
            TranscribeSettings.Engine.GEMINI -> com.mobicareapp.questionpaper.QuestionPaperSettings.getApiKey(appContext)
            TranscribeSettings.Engine.GROQ -> TranscribeSettings.getGroqKey(appContext)
            TranscribeSettings.Engine.SARVAM -> TranscribeSettings.getSarvamKey(appContext)
            TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeSettings.getApiKey(appContext)
            else -> TranscribeSettings.keyFor(appContext, engine)
        }
        if (apiKey == null) {
            notify(appContext, "Recording saved. Set up an API key (Library → ⋮ → Transcribe) to auto-transcribe next time.")
            return
        }
        val englishOnly = TranscribeSettings.getEnglishOnly(appContext)
        scope.launch {
            val audio = File(filePath)
            val result = when (engine) {
                TranscribeSettings.Engine.GEMINI -> GeminiTranscriber.transcribe(apiKey, audio, languageCode, {}, englishOnly)
                TranscribeSettings.Engine.GROQ -> GroqTranscriber.transcribe(apiKey, audio, languageCode, {}, englishOnly)
                TranscribeSettings.Engine.SARVAM -> SarvamTranscriber.transcribe(apiKey, audio, appContext.cacheDir, languageCode, {}, englishOnly)
                TranscribeSettings.Engine.ASSEMBLYAI -> AssemblyAiTranscriber.transcribe(apiKey, audio, languageCode) {}
                else -> OtherTranscribers.transcribe(engine, apiKey, audio, languageCode) {}
            }
            result.fold(
                onSuccess = { text -> notify(appContext, if (text.isBlank()) "No speech recognized in that recording" else text) },
                onFailure = { e -> notify(appContext, "Couldn't transcribe: ${e.message ?: "unknown error"}") }
            )
        }
    }

    private fun notify(context: Context, text: String) {
        val openAppIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, YtSaverApp.RECORD_CHANNEL_ID)
            .setContentTitle("Transcription ready")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
