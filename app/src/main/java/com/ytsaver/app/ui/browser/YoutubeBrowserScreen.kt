package com.ytsaver.app.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

private const val START_URL = "https://m.youtube.com"

/**
 * A plain in-app browser tab pointed at YouTube's own mobile site, so videos
 * play through YouTube's normal player - no extraction, no stream-URL
 * fetching, none of the bot-detection issues that affect the downloader.
 * The copy-link button reads the WebView's current URL (the page you're on)
 * so it can be pasted into the Save tab to download it.
 */
@Composable
fun YoutubeBrowserScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("YouTube", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = {
                    val url = webViewRef?.url
                    if (url != null) {
                        clipboard.setText(AnnotatedString(url))
                        scope.launch { snackbarHostState.showSnackbar("Link copied — paste it in the Save tab") }
                    }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy this video's link")
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> createWebView(ctx) { canGoBack = it } },
                update = { webView -> webViewRef = webView }
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.padding(16.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(context: Context, onNavigated: (canGoBack: Boolean) -> Unit): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                onNavigated(view.canGoBack())
            }
        }
        loadUrl(START_URL)
    }
