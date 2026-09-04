package com.ytsaver.app.ui.browser

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.download.DownloadService
import com.ytsaver.app.extract.BROWSER_USER_AGENT
import java.net.URLDecoder

private data class DetectedMedia(val url: String, val type: MediaType, val extension: String, val mimeType: String)

private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "3gp", "ts")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg")

/**
 * Many sites embed their video/audio behind a normal webpage (no direct file link, no YouTube-
 * style page we can extract from) — the only way in is to actually load the page and watch what
 * it fetches. This wraps a plain WebView and inspects every request it makes; anything that looks
 * like a raw media file gets offered as a one-tap download via the same DownloadService used
 * everywhere else in the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var addressText by remember { mutableStateOf("") }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val detected = remember { mutableStateListOf<DetectedMedia>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste a page URL") }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { pendingUrl = normalizeUrl(addressText) }) { Text("Go") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (detected.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(detected, key = { it.url }) { media ->
                        AssistChip(
                            onClick = {
                                DownloadService.start(
                                    context = context,
                                    caption = fileNameFromUrl(media.url),
                                    sourceUrl = media.url,
                                    streamUrl = media.url,
                                    type = media.type,
                                    fileExtension = media.extension,
                                    mimeType = media.mimeType,
                                    thumbnailUrl = null,
                                    durationSeconds = 0
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(if (media.type == MediaType.VIDEO) "Video found" else "Audio found") }
                        )
                    }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = BROWSER_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                classifyMediaUrl(request.url.toString())?.let { media ->
                                    mainHandler.post {
                                        if (detected.none { it.url == media.url }) detected.add(media)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }
                },
                update = { webView ->
                    pendingUrl?.let { url ->
                        if (webView.url != url) webView.loadUrl(url)
                    }
                }
            )
        }
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

private fun classifyMediaUrl(url: String): DetectedMedia? {
    val path = url.substringBefore('?').substringAfterLast('/')
    val extension = path.substringAfterLast('.', "").lowercase()
    return when (extension) {
        in VIDEO_EXTENSIONS -> DetectedMedia(url, MediaType.VIDEO, extension, "video/$extension")
        in AUDIO_EXTENSIONS -> DetectedMedia(url, MediaType.AUDIO, extension, if (extension == "mp3") "audio/mpeg" else "audio/$extension")
        else -> null
    }
}

private fun fileNameFromUrl(url: String): String {
    val path = url.substringBefore('?').substringAfterLast('/')
    val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
    return decoded.substringBeforeLast('.').ifBlank { "Downloaded file" }
}
