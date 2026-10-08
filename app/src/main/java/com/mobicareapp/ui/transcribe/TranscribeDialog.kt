package com.mobicareapp.ui.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.data.MediaType
import com.mobicareapp.record.RecordFileStore
import com.mobicareapp.record.RecordTarget
import com.mobicareapp.questionpaper.QuestionPaperSettings
import com.mobicareapp.transcribe.AssemblyAiTranscriber
import com.mobicareapp.transcribe.GeminiTranscriber
import com.mobicareapp.transcribe.GroqTranscriber
import com.mobicareapp.transcribe.SarvamTranscriber
import com.mobicareapp.transcribe.SpellChecker
import com.mobicareapp.transcribe.TranscribeSettings
import com.mobicareapp.transcribe.TtsSynthesizer
import com.mobicareapp.translate.ManglishTransliterator
import com.mobicareapp.translate.MyMemoryTranslator
import com.mobicareapp.upload.ImageCombiner
import com.mobicareapp.upload.PersonalServerSettings
import com.mobicareapp.upload.PersonalServerUploader
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private sealed class TranscribeState {
    data object NeedsApiKey : TranscribeState()
    data object NeedsGeminiKey : TranscribeState()
    data object NeedsGroqKey : TranscribeState()
    data object NeedsSarvamKey : TranscribeState()
    data object SelectingLanguage : TranscribeState()
    data class Running(val status: String) : TranscribeState()
    data class Done(val text: String) : TranscribeState()
    data class Failed(val message: String) : TranscribeState()
}

// AssemblyAI is the one engine — its free tier (185 hrs/month) needs just an email signup, no
// card. Two alternatives were tried and removed: the "free Google" play-and-listen workaround
// never actually worked (echo cancellation blocks it from hearing its own speaker), and Azure's
// free tier needs card/identity verification during signup, which defeats the point of a
// no-card-needed backup.

/** null means "auto-detect" — kept as an explicit option since it's a legitimate choice for a language not listed, but it's not the default: auto-detect confused Malayalam for Tamil in testing (both Dravidian languages), so naming the language up front is what actually gets picked whenever it's known. */
private val LANGUAGE_OPTIONS = listOf(
    "Malayalam" to "ml",
    "English" to "en",
    "Hindi" to "hi",
    "Tamil" to "ta",
    "Telugu" to "te",
    "Kannada" to "kn",
    "Auto-detect" to null
)

/**
 * Transcribes [file] (an audio recording) to text via AssemblyAI — the user's own free API key
 * is required (asked for once, then remembered), since this app doesn't have — and won't have —
 * an API key of its own to share across every install.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranscribeDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Locking swaps the whole nav graph out for the lock screen, which disposes this dialog's
    // state — the running transcription and the transcribed/edited text would be lost the moment
    // the app is minimized and reopened. Held for as long as this dialog is open, so leaving the app
    // and coming back lands on the very same window. (Closing the dialog releases it again.)
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    var state by remember { mutableStateOf<TranscribeState>(TranscribeState.SelectingLanguage) }
    var apiKeyInput by remember { mutableStateOf("") }
    // This window is English-only: the speaker speaks English and the text comes out in English, so
    // there is no language to pick and no translation step. Saved too, so recordings that are
    // transcribed automatically in the background (widget/shortcut) use English as well.
    val languageCode: String = "en"
    remember { TranscribeSettings.setLanguageCode(context, "en"); TranscribeSettings.setEnglishOnly(context, false) }
    var checkingSpelling by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf(TranscribeSettings.getEngine(context)) }
    val englishOnly = false
    var geminiKeyInput by remember { mutableStateOf("") }
    var groqKeyInput by remember { mutableStateOf("") }
    var sarvamKeyInput by remember { mutableStateOf("") }

    fun runTranscription() {
        val key = when (engine) {
            TranscribeSettings.Engine.GEMINI -> QuestionPaperSettings.getApiKey(context)
            TranscribeSettings.Engine.GROQ -> TranscribeSettings.getGroqKey(context)
            TranscribeSettings.Engine.SARVAM -> TranscribeSettings.getSarvamKey(context)
            TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeSettings.getApiKey(context)
        } ?: return
        state = TranscribeState.Running("Starting…")
        scope.launch {
            val onStatus: (String) -> Unit = { status -> state = TranscribeState.Running(status) }
            val result = when (engine) {
                TranscribeSettings.Engine.GEMINI -> GeminiTranscriber.transcribe(key, file, languageCode, onStatus, englishOnly)
                TranscribeSettings.Engine.GROQ -> GroqTranscriber.transcribe(key, file, languageCode, onStatus, englishOnly)
                TranscribeSettings.Engine.SARVAM -> SarvamTranscriber.transcribe(key, file, context.cacheDir, languageCode, onStatus, englishOnly)
                TranscribeSettings.Engine.ASSEMBLYAI -> AssemblyAiTranscriber.transcribe(key, file, languageCode, onStatus)
            }
            state = result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) TranscribeState.Failed("No speech was recognized in this recording.")
                    else TranscribeState.Done(text)
                },
                onFailure = { e -> TranscribeState.Failed(e.message ?: "Couldn't transcribe that recording") }
            )
        }
    }


    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Transcribe") },
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
                when (val current = state) {
                    is TranscribeState.NeedsApiKey -> {
                        Text(
                            "Transcription uses AssemblyAI's speech-to-text service — you'll need your own free API key from assemblyai.com (185 hours/month free, no card required). Paste it here once; it's saved on this device only and sent only to AssemblyAI.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("AssemblyAI API key") }
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                TranscribeSettings.setApiKey(context, apiKeyInput)
                                runTranscription()
                            },
                            enabled = apiKeyInput.isNotBlank()
                        ) { Text("Save") }
                    }

                    is TranscribeState.NeedsGeminiKey -> {
                        Text(
                            "This uses Google's Gemini, which has a free tier. Create a free key at aistudio.google.com/apikey (Google sign-in, no card) and paste it here once. It's saved on this phone only. The same key also powers the question paper maker.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = geminiKeyInput,
                            onValueChange = { geminiKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Gemini API key") }
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                QuestionPaperSettings.setApiKey(context, geminiKeyInput)
                                runTranscription()
                            },
                            enabled = geminiKeyInput.isNotBlank()
                        ) { Text("Save and transcribe") }
                    }

                    is TranscribeState.NeedsGroqKey -> {
                        Text(
                            "Groq's speech-to-text is free (no card) and very fast. Sign up at console.groq.com, create an API key, and paste it here once. It's saved on this phone only. Free plan: recordings up to 25 MB (about 20 minutes).",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = groqKeyInput,
                            onValueChange = { groqKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Groq API key") }
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                TranscribeSettings.setGroqKey(context, groqKeyInput)
                                runTranscription()
                            },
                            enabled = groqKeyInput.isNotBlank()
                        ) { Text("Save and transcribe") }
                    }

                    is TranscribeState.NeedsSarvamKey -> {
                        Text(
                            "Sarvam AI is built for Indian languages (Malayalam, Hindi, Tamil…) and its free signup needs no card. Sign up at dashboard.sarvam.ai, create an API key, and paste it here once. It's saved on this phone only.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = sarvamKeyInput,
                            onValueChange = { sarvamKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Sarvam API key") }
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                TranscribeSettings.setSarvamKey(context, sarvamKeyInput)
                                runTranscription()
                            },
                            enabled = sarvamKeyInput.isNotBlank()
                        ) { Text("Save and transcribe") }
                    }

                    is TranscribeState.SelectingLanguage -> {
                        Text("Transcribe with", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = engine == TranscribeSettings.Engine.GEMINI,
                                onClick = { engine = TranscribeSettings.Engine.GEMINI; TranscribeSettings.setEngine(context, engine) },
                                label = { Text("Gemini (free)") }
                            )
                            FilterChip(
                                selected = engine == TranscribeSettings.Engine.GROQ,
                                onClick = { engine = TranscribeSettings.Engine.GROQ; TranscribeSettings.setEngine(context, engine) },
                                label = { Text("Groq (free, very fast)") }
                            )
                            FilterChip(
                                selected = engine == TranscribeSettings.Engine.SARVAM,
                                onClick = { engine = TranscribeSettings.Engine.SARVAM; TranscribeSettings.setEngine(context, engine) },
                                label = { Text("Sarvam (Indian languages)") }
                            )
                            FilterChip(
                                selected = engine == TranscribeSettings.Engine.ASSEMBLYAI,
                                onClick = { engine = TranscribeSettings.Engine.ASSEMBLYAI; TranscribeSettings.setEngine(context, engine) },
                                label = { Text("AssemblyAI") }
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("English", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Speak English. The recording is written as English text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = {
                            val missingKey = when (engine) {
                                TranscribeSettings.Engine.GEMINI -> QuestionPaperSettings.getApiKey(context) == null
                                TranscribeSettings.Engine.GROQ -> TranscribeSettings.getGroqKey(context) == null
                                TranscribeSettings.Engine.SARVAM -> TranscribeSettings.getSarvamKey(context) == null
                                TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeSettings.getApiKey(context) == null
                            }
                            if (!missingKey) runTranscription()
                            else state = when (engine) {
                                TranscribeSettings.Engine.GEMINI -> TranscribeState.NeedsGeminiKey
                                TranscribeSettings.Engine.GROQ -> TranscribeState.NeedsGroqKey
                                TranscribeSettings.Engine.SARVAM -> TranscribeState.NeedsSarvamKey
                                TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeState.NeedsApiKey
                            }
                        }) { Text("Transcribe") }
                    }

                    is TranscribeState.Running -> {
                        Spacer(Modifier.height(32.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.status, style = MaterialTheme.typography.bodyMedium)
                    }

                    is TranscribeState.Done -> {
                        var text by remember(current) { mutableStateOf(current.text) }
                        var synthesizing by remember(current) { mutableStateOf(false) }
                        var voiceFile by remember(current) { mutableStateOf<File?>(null) }
                        var voiceError by remember(current) { mutableStateOf<String?>(null) }
                        var attachments by remember(current) { mutableStateOf<List<Uri>>(emptyList()) }
                        var uploadingAttachment by remember(current) { mutableStateOf(false) }
                        var uploadStatus by remember(current) { mutableStateOf<String?>(null) }
                        var translating by remember(current) { mutableStateOf(false) }
                        var translatedText by remember(current) { mutableStateOf<String?>(null) }
                        var translateError by remember(current) { mutableStateOf<String?>(null) }

                        val mediaPicker = rememberLauncherForActivityResult(
                            ActivityResultContracts.PickMultipleVisualMedia()
                        ) { uris -> attachments = attachments + uris }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    checkingSpelling = true
                                    scope.launch {
                                        text = SpellChecker.correct(context, text)
                                        checkingSpelling = false
                                    }
                                },
                                enabled = !checkingSpelling,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (checkingSpelling) "Checking…" else "Check spelling") }
                            Button(
                                onClick = { clipboard.setText(AnnotatedString(text)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy text")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (attachments.isEmpty()) "Attach photos/videos" else "Add more photos/videos")
                        }

                        if (attachments.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(attachments) { uri ->
                                    val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        if (isVideo) {
                                            Icon(
                                                Icons.Default.Videocam,
                                                contentDescription = "Video attachment",
                                                modifier = Modifier.size(28.dp).align(Alignment.Center)
                                            )
                                        } else {
                                            AsyncImage(
                                                model = uri,
                                                contentDescription = "Image attachment",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        IconButton(
                                            onClick = { attachments = attachments - uri },
                                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // A separate button from "Copy text" — Android's clipboard holds one
                            // thing at a time, so copying text then this (or vice versa) replaces
                            // whatever was copied before, same as copying anything twice normally.
                            Button(
                                onClick = {
                                    val clipItems = attachments.map { ClipData.Item(it) }
                                    val clipData = ClipData("YT Saver attachments", arrayOf("image/*", "video/*"), clipItems.first())
                                    clipItems.drop(1).forEach { clipData.addItem(it) }
                                    val manager = context.getSystemService(ClipboardManager::class.java)
                                    manager.setPrimaryClip(clipData)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy attachments only (${attachments.size})")
                            }

                            // Only shown once something is actually attached — with no attachments
                            // there's nothing to upload, and no URL needs to be generated at all.
                            if (PersonalServerSettings.isConfigured(context)) {
                                val imageCount = attachments.count { context.contentResolver.getType(it)?.startsWith("image") == true }
                                if (imageCount > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (imageCount > 1) {
                                            "$imageCount images picked — they'll be stacked into one tall picture with the transcript text underneath, before uploading."
                                        } else {
                                            "The transcript text will be added underneath the picture before uploading."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        uploadingAttachment = true
                                        uploadStatus = null
                                        scope.launch {
                                            val images = attachments.filter { context.contentResolver.getType(it)?.startsWith("image") == true }
                                            val videos = attachments - images.toSet()
                                            var lastError: String? = null
                                            var uploadedImage = false
                                            var uploadedVideo = false

                                            if (images.isNotEmpty()) {
                                                // Always combined (even a single image) so the
                                                // transcript's text gets baked into the same image
                                                // as its own block underneath — the point being
                                                // that the one uploaded file carries both the
                                                // picture(s) and the words together.
                                                ImageCombiner.combineVerticallyWithText(context, images, text).fold(
                                                    onSuccess = { combinedFile ->
                                                        PersonalServerUploader.uploadFile(context, combinedFile, "image/jpeg", isVideo = false)
                                                            .onSuccess { uploadedImage = true }
                                                            .onFailure { e -> lastError = e.message ?: "Upload failed" }
                                                        combinedFile.delete()
                                                    },
                                                    onFailure = { e -> lastError = e.message ?: "Couldn't combine the images" }
                                                )
                                            }
                                            // Each video overwrites the separate 1.mp4 slot (not
                                            // 1.jpg) — if more than one is picked, only the last one
                                            // uploaded remains, same as before this had two slots.
                                            for (uri in videos) {
                                                val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
                                                PersonalServerUploader.upload(context, uri, mimeType, isVideo = true)
                                                    .onSuccess { uploadedVideo = true }
                                                    .onFailure { e -> lastError = e.message ?: "Upload failed" }
                                            }

                                            uploadingAttachment = false
                                            if (lastError != null && !uploadedImage && !uploadedVideo) {
                                                uploadStatus = lastError
                                            } else {
                                                val serverUrl = PersonalServerSettings.getUploadUrl(context) ?: ""
                                                val folder = serverUrl.substringBeforeLast('/')
                                                val lines = mutableListOf<String>()
                                                if (uploadedImage) lines.add("Image: $folder/1.jpg")
                                                if (uploadedVideo) lines.add("Video: $folder/1.mp4")
                                                val message = "Uploaded to the following URL(s):\n" + lines.joinToString("\n")
                                                uploadStatus = message
                                                val manager = context.getSystemService(ClipboardManager::class.java)
                                                manager.setPrimaryClip(ClipData.newPlainText("Uploaded URL", message))
                                            }
                                        }
                                    },
                                    enabled = !uploadingAttachment,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (uploadingAttachment) "Uploading…" else "Upload to my server")
                                }
                                uploadStatus?.let {
                                    Spacer(Modifier.height(8.dp))
                                    if (it.startsWith("Uploaded to the following URL")) {
                                        OutlinedTextField(
                                            value = it,
                                            onValueChange = {},
                                            readOnly = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            label = { Text("Uploaded URL (already copied)") },
                                            trailingIcon = {
                                                IconButton(onClick = {
                                                    val manager = context.getSystemService(ClipboardManager::class.java)
                                                    manager.setPrimaryClip(ClipData.newPlainText("Uploaded URL", it))
                                                }) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                                }
                                            }
                                        )
                                    } else {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                                    }
                                }
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Set up your server under \"Copy images/videos to clipboard\" in Record to also enable uploading from here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Spelling only — this catches misspelled words, not sentence-level grammar or phrasing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { state = TranscribeState.SelectingLanguage }) {
                            Text("Wrong language? Transcribe again as something else")
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                translating = true
                                translateError = null
                                translatedText = null
                                scope.launch {
                                    // Some transcripts come back as real Malayalam script; others
                                    // (depending on the recognizer) come back as Malayalam written
                                    // in English letters — "Manglish" — which MyMemory's translator
                                    // can't read directly. Checking for actual Malayalam Unicode
                                    // characters (U+0D00–U+0D7F) tells which case this is, and only
                                    // romanized text gets the extra transliteration step first.
                                    val hasMalayalamScript = text.any { it.code in 0x0D00..0x0D7F }
                                    val result = if (!hasMalayalamScript && languageCode == "ml") {
                                        ManglishTransliterator.toMalayalam(text).fold(
                                            onSuccess = { malayalamText -> MyMemoryTranslator.translate(malayalamText, "ml", "en") },
                                            onFailure = { e -> Result.failure(e) }
                                        )
                                    } else {
                                        MyMemoryTranslator.translate(text, languageCode ?: "ml", "en")
                                    }
                                    translating = false
                                    result.fold(
                                        onSuccess = { translatedText = it },
                                        onFailure = { e -> translateError = e.message ?: "Couldn't translate" }
                                    )
                                }
                            },
                            enabled = !translating && text.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (translating) "Translating…" else "Translate to English")
                        }
                        translatedText?.let { english ->
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = english,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("English translation") }
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { clipboard.setText(AnnotatedString(english)) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy translation")
                            }
                        }
                        translateError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                synthesizing = true
                                voiceFile = null
                                voiceError = null
                                scope.launch {
                                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                                    dir.mkdirs()
                                    val caption = "TTS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
                                    val outFile = File(dir, "$caption.wav")
                                    val result = TtsSynthesizer.synthesizeToFile(context, text, languageCode, outFile)
                                    synthesizing = false
                                    result.fold(
                                        onSuccess = {
                                            // Tagged "tts" (not "recording") so it shows up as a normal Library
                                            // item instead of falling under the hidden-recordings gesture.
                                            RecordFileStore.finalizeAndSave(
                                                context, RecordTarget.LegacyFile(outFile), MediaType.AUDIO, caption,
                                                fileExtension = "wav", sourceUrl = "tts"
                                            )
                                            voiceFile = outFile
                                        },
                                        onFailure = { e -> voiceError = e.message ?: "Couldn't create voice" }
                                    )
                                }
                            },
                            enabled = !synthesizing && text.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (synthesizing) "Creating voice…" else "Convert text to voice")
                        }

                        voiceFile?.let { file ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Voice saved — also in Library under Audio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share voice"))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share to WhatsApp or anyone")
                            }
                        }
                        voiceError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }

                    is TranscribeState.Failed -> {
                        Text(current.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { runTranscription() }) { Text("Try again") }
                            TextButton(onClick = { state = TranscribeState.SelectingLanguage }) { Text("Change engine") }
                            TextButton(onClick = {
                                state = when (engine) {
                                    TranscribeSettings.Engine.GEMINI -> TranscribeState.NeedsGeminiKey
                                    TranscribeSettings.Engine.GROQ -> TranscribeState.NeedsGroqKey
                                    TranscribeSettings.Engine.SARVAM -> TranscribeState.NeedsSarvamKey
                                    TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeState.NeedsApiKey
                                }
                            }) { Text("Change API key") }
                        }
                    }
                }
            }
        }
    }
}
