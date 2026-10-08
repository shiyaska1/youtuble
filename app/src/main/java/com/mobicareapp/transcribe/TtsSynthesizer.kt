package com.mobicareapp.transcribe

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reads transcribed text back out as a saved audio file, using Android's own built-in
 * text-to-speech engine — free and works offline, no API key needed (unlike the transcription
 * step itself). Writes straight to a .wav file via the engine's own synthesizeToFile(), so no
 * manual audio encoding is needed here.
 */
object TtsSynthesizer {
    private val LOCALE_BY_CODE = mapOf(
        "ml" to Locale("ml", "IN"),
        "en" to Locale.US,
        "hi" to Locale("hi", "IN"),
        "ta" to Locale("ta", "IN"),
        "te" to Locale("te", "IN"),
        "kn" to Locale("kn", "IN")
    )

    /** Synthesizes [text] into [outputFile] using the on-device voice for [languageCode] (device default if null/unmapped). Fails clearly if that language's voice isn't installed, rather than silently reading it in the wrong voice. */
    suspend fun synthesizeToFile(context: Context, text: String, languageCode: String?, outputFile: File): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Text-to-speech engine isn't available on this device")))
                    return@TextToSpeech
                }

                val locale = languageCode?.let { LOCALE_BY_CODE[it] }
                if (locale != null) {
                    val availability = engine.setLanguage(locale)
                    if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.shutdown()
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(IllegalStateException("This phone doesn't have a $locale voice installed — check Settings → Accessibility → Text-to-speech output to add one")))
                        }
                        return@TextToSpeech
                    }
                }

                val utteranceId = "ytsaver_tts_${System.currentTimeMillis()}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        engine.shutdown()
                        if (continuation.isActive) continuation.resume(Result.success(Unit))
                    }

                    @Deprecated("Deprecated in Java", ReplaceWith(""))
                    override fun onError(utteranceId: String?) {
                        engine.shutdown()
                        if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Text-to-speech failed")))
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        engine.shutdown()
                        if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Text-to-speech failed (code $errorCode)")))
                    }
                })

                val queued = engine.synthesizeToFile(text, Bundle(), outputFile, utteranceId)
                if (queued != TextToSpeech.SUCCESS) {
                    engine.shutdown()
                    if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Couldn't start text-to-speech")))
                }
            }
            continuation.invokeOnCancellation { runCatching { tts?.shutdown() } }
        }
}
