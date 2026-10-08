package com.mobicareapp.ui.translate

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.transcribe.TranscriptionRouter
import com.mobicareapp.ui.transcribe.TranscribePickerDialog
import kotlinx.coroutines.launch
import java.io.File

// English as spoken in India — what the phone's speech recognizer should expect from the mic.
private const val VOICE_INPUT_LOCALE = "en-IN"

/**
 * English-only speech/text window: type English, tap the mic and speak English, or pick an audio
 * file (a recording in the Library, a WhatsApp voice note…) — whichever way it comes in, the result
 * is English text in the box, ready to copy. No language choice and no translation step; this
 * window used to be a Malayalam/Hindi/etc. translator and was simplified at the user's request.
 * The audio-file route runs on whichever transcription engine was last picked in the Transcribe
 * window (see [TranscriptionRouter]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateTextDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Picking a file / speaking leaves the app for a moment, and a transcription can take a while —
    // keep the app lock from swapping this window (and the text in it) out in between.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    var text by remember { mutableStateOf("") }
    var transcribingFile by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showAudioPicker by remember { mutableStateOf(false) }

    fun transcribeFileAndFill(file: File) {
        transcribingFile = true
        status = "Starting…"
        errorText = null
        scope.launch {
            val result = TranscriptionRouter.transcribe(context, file, "en") { status = it }
            transcribingFile = false
            status = null
            result.fold(
                onSuccess = { heard ->
                    if (heard.isBlank()) errorText = "No speech was recognized in that file."
                    else text = if (text.isBlank()) heard else text.trimEnd() + "\n" + heard
                },
                onFailure = { e -> errorText = e.message ?: "Couldn't transcribe that file" }
            )
        }
    }

    val voiceInputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            // Appended, so several short phrases in a row build up one text instead of replacing each other.
            if (!heard.isNullOrBlank()) text = if (text.isBlank()) heard else text.trimEnd() + " " + heard
        }
    }
    fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VOICE_INPUT_LOCALE)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak English…")
        }
        AppLockManager.suppressNextLock()
        runCatching { voiceInputLauncher.launch(intent) }
            .onFailure { errorText = "No speech recognizer app is available on this phone" }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Speak or upload (English)") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    label = { Text("English text") },
                    trailingIcon = {
                        IconButton(onClick = { launchVoiceInput() }, enabled = !transcribingFile) {
                            Icon(Icons.Default.Mic, contentDescription = "Speak English")
                        }
                    }
                )
                Text(
                    "Tap the mic and speak English, or type it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showAudioPicker = true },
                    enabled = !transcribingFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (transcribingFile) "Transcribing…" else "Upload audio file")
                }
                if (transcribingFile) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                    status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(text)) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                    OutlinedButton(
                        onClick = { text = ""; errorText = null },
                        enabled = text.isNotEmpty() && !transcribingFile,
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }

                errorText?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (showAudioPicker) {
        // Same picker "Transcribe a recording" uses in Record — lists audio already in this
        // app's own Library, with its own "Pick an audio file from this phone" option for
        // anything else (a WhatsApp voice note, etc.), rather than a separate file chooser here.
        TranscribePickerDialog(
            onDismiss = { showAudioPicker = false },
            onPick = { file ->
                showAudioPicker = false
                transcribeFileAndFill(file)
            }
        )
    }
}
