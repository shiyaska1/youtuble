package com.mobicareapp.ui.browser

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Some sites need a real login (username/password, not just Google) before they'll serve their
 * video — GenericVideoFetcher's plain HTTP fetch can't do that itself, so this hosts a real
 * WebView for the user to sign in with directly (the user types their own credentials into the
 * site's own page; the app never sees them). WebView's CookieManager is shared and persistent
 * app-wide, so once signed in here the same session cookie carries over to GenericVideoFetcher's
 * fetch without asking again next time.
 */
@Composable
fun SignInWebViewDialog(url: String, onDismiss: () -> Unit) {
    var popupWebView by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        // Without this, the dialog window is capped to the platform's default dialog width
        // (roughly wrap-content) instead of the full screen.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Done, retry")
                }
                Text("Sign in, then close this", style = MaterialTheme.typography.titleMedium)
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureAsDesktopBrowser(this)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                launchExternalUrlIfNeeded(view.context, request.url.toString())
                        }
                        webChromeClient = popupHostingWebChromeClient(
                            onPopupRequested = { popupWebView = it },
                            onPopupClosed = { popupWebView = null }
                        )
                        loadUrl(url)
                    }
                }
            )
        }
    }

    // Google's sign-in (and many other OAuth flows) opens via window.open() into a popup rather
    // than navigating the current page.
    popupWebView?.let { popup ->
        Dialog(
            onDismissRequest = { popupWebView = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                IconButton(onClick = { popupWebView = null }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { popup })
            }
        }
    }
}
