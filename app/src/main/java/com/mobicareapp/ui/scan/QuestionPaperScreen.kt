package com.mobicareapp.ui.scan

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.questionpaper.Question
import com.mobicareapp.questionpaper.QuestionPaper
import com.mobicareapp.questionpaper.QuestionPaperSettings
import com.mobicareapp.questionpaper.QuestionPaperViewModel
import com.mobicareapp.questionpaper.QuestionType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionPaperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: QuestionPaperViewModel = viewModel()
    val pageTexts by viewModel.pageTexts.collectAsState()
    val status by viewModel.status.collectAsState()
    val error by viewModel.error.collectAsState()
    val papers by viewModel.papers.collectAsState()

    var questionCount by remember { mutableStateOf(5f) }
    val selectedTypes = remember { mutableStateListOf(QuestionType.MCQ, QuestionType.SHORT, QuestionType.FILL_BLANK) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(QuestionPaperSettings.getApiKey(context) != null) }

    // suppressNextLock() goes right before each launch, not in the result callback — the picker
    // briefly backgrounds the app, and the app-lock would otherwise tear down this screen (and
    // every page already read) before the result arrives.
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.addSources(uris)
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addSources(uris)
    }

    fun share(paper: QuestionPaper, includeAnswers: Boolean) {
        scope.launch {
            viewModel.exportPdf(paper, includeAnswers)
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    AppLockManager.suppressNextLock()
                    context.startActivity(Intent.createChooser(intent, "Share question paper"))
                }
                .onFailure { Toast.makeText(context, "Couldn't create the PDF", Toast.LENGTH_SHORT).show() }
        }
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            onDismiss = { showKeyDialog = false },
            onSave = { key ->
                QuestionPaperSettings.setApiKey(context, key)
                hasKey = key.isNotBlank()
                showKeyDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Question paper maker") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { showKeyDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "AI key settings") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1. Add book pages", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (pageTexts.isEmpty()) "Pick photos of the pages, or a PDF of the book." else "${pageTexts.size} page(s) read. You can add more.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = status == null, onClick = {
                                AppLockManager.suppressNextLock()
                                photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Photos")
                            }
                            OutlinedButton(enabled = status == null, onClick = {
                                AppLockManager.suppressNextLock()
                                pdfLauncher.launch(arrayOf("application/pdf"))
                            }) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("PDF")
                            }
                            if (pageTexts.isNotEmpty()) {
                                TextButton(enabled = status == null, onClick = viewModel::clearPages) { Text("Clear all") }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("2. Choose the paper", style = MaterialTheme.typography.titleMedium)
                        Text("Number of questions: ${questionCount.toInt()}", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = questionCount,
                            onValueChange = { questionCount = it },
                            valueRange = 1f..QuestionPaperSettings.MAX_QUESTIONS.toFloat(),
                            steps = QuestionPaperSettings.MAX_QUESTIONS - 2
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuestionType.entries.forEach { type ->
                                FilterChip(
                                    selected = type in selectedTypes,
                                    onClick = {
                                        // At least one type must stay selected — nothing could be generated otherwise.
                                        if (type in selectedTypes) { if (selectedTypes.size > 1) selectedTypes.remove(type) } else selectedTypes.add(type)
                                    },
                                    label = { Text(type.label) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canGenerate = pageTexts.isNotEmpty() && status == null &&
                        papers.size < QuestionPaperSettings.MAX_PAPERS
                    Button(
                        onClick = { if (hasKey) viewModel.generate(questionCount.toInt(), selectedTypes.toList(), useAi = true) else showKeyDialog = true },
                        enabled = canGenerate,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Make paper with AI (${papers.size}/${QuestionPaperSettings.MAX_PAPERS} made)") }
                    OutlinedButton(
                        onClick = { viewModel.generate(questionCount.toInt(), selectedTypes.toList(), useAi = false) },
                        enabled = canGenerate,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Make without AI (offline, simple)") }
                    if (!hasKey) {
                        Text(
                            "AI needs a free Gemini key — tap the gear icon at the top to add one.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    status?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            items(papers.asReversed(), key = { it.createdAt }) { paper ->
                PaperCard(paper, onShare = { includeAnswers -> share(paper, includeAnswers) })
            }
        }
    }
}

@Composable
private fun PaperCard(paper: QuestionPaper, onShare: (includeAnswers: Boolean) -> Unit) {
    var showAnswers by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Paper ${paper.number} · ${paper.questions.size} questions · ${if (paper.usedAi) "AI" else "offline"}",
                style = MaterialTheme.typography.titleMedium
            )
            paper.questions.forEachIndexed { index, question -> QuestionRow(index + 1, question, showAnswers) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showAnswers = !showAnswers }) { Text(if (showAnswers) "Hide answers" else "Show answers") }
                Button(onClick = { onShare(false) }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("PDF")
                }
                TextButton(onClick = { onShare(true) }) { Text("PDF + answers") }
            }
        }
    }
}

@Composable
private fun QuestionRow(number: Int, question: Question, showAnswer: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$number. ${question.text}", style = MaterialTheme.typography.bodyLarge)
        question.options.forEachIndexed { index, option ->
            Text("${'A' + index}. $option", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
        }
        if (showAnswer) {
            Text("Answer: ${question.answer}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun ApiKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var key by remember { mutableStateOf(QuestionPaperSettings.getApiKey(context).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Free Gemini API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The AI is Google's Gemini, which has a free tier. Sign in with a Google account, create a key (no card needed), and paste it here. It stays on this phone.")
                TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) { Text("Open aistudio.google.com/apikey") }
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
