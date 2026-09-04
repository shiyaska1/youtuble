package com.mobicareapp.ui.browser

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.mobicareapp.data.MediaType
import com.mobicareapp.download.DownloadService
import java.net.URLDecoder

private data class DetectedMedia(
    val url: String,
    val type: MediaType,
    val extension: String,
    val mimeType: String,
    val pageUrl: String?
)

private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "3gp", "ts")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg")

// A real Android Chrome UA (not a desktop one) — Google's sign-in flow serves a different,
// stricter page to what it thinks is a desktop browser embedded somewhere it shouldn't be.
private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Many sites embed their video/audio behind a normal webpage (no direct file link, no YouTube-
 * style page we can extract from) — the only way in is to actually load the page and watch what
 * it fetches. This wraps a WebView configured to behave like real mobile Chrome (so things like
 * Google Sign-In work — a plain WebView gets blocked by Google's "this browser may not be
 * secure" check because of the X-Requested-With header Android adds by default, and because
 * Google's sign-in flow opens in a popup window a bare WebView otherwise can't display) and
 * inspects every request it makes; anything that looks like a raw media file gets offered as a
 * one-tap download via the same DownloadService used everywhere else in the app.
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
    var popupWebView by remember { mutableStateOf<WebView?>(null) }

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
                                    durationSeconds = 0,
                                    // Some sites only serve the file to the same signed-in session
                                    // (cookies) and/or a referer matching the page that requested
                                    // it — without these a background fetch can come back empty.
                                    cookie = CookieManager.getInstance().getCookie(media.url),
                                    referer = media.pageUrl
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
                        configureAsRealBrowser(this)
                        webViewClient = mediaSniffingWebViewClient(mainHandler, detected)
                        webChromeClient = popupHostingWebChromeClient(
                            onPopupRequested = { popupWebView = it },
                            onPopupClosed = { popupWebView = null }
                        )
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

    // Google's sign-in (and many other OAuth flows) opens via window.open() into a popup rather
    // than navigating the current page — a bare WebView silently drops that. Hosting the popup's
    // WebView in its own dialog, on top of the page that requested it, is what lets it complete.
    popupWebView?.let { popup ->
        Dialog(onDismissRequest = { popupWebView = null }) {
            Column(modifier = Modifier.fillMaxSize()) {
                IconButton(onClick = { popupWebView = null }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { popup })
            }
        }
    }
}

private fun configureAsRealBrowser(webView: WebView) {
    val settings: WebSettings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.userAgentString = MOBILE_CHROME_USER_AGENT
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)

    // The stock WebView tags every request with "X-Requested-With: <our package name>" by
    // default, which is exactly what Google's servers check for to block sign-in inside an
    // embedded WebView ("This browser or app may not be secure"). An empty allow-list means
    // no origin gets that header, so the request looks like it came from a normal browser.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
    }
}

private fun mediaSniffingWebViewClient(
    mainHandler: Handler,
    detected: androidx.compose.runtime.snapshots.SnapshotStateList<DetectedMedia>
): WebViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        classifyMediaUrl(request.url.toString(), view.url)?.let { media ->
            mainHandler.post {
                if (detected.none { it.url == media.url }) detected.add(media)
            }
        }
        return super.shouldInterceptRequest(view, request)
    }
}

private fun popupHostingWebChromeClient(
    onPopupRequested: (WebView) -> Unit,
    onPopupClosed: () -> Unit
): WebChromeClient = object : WebChromeClient() {
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val popup = WebView(view.context)
        configureAsRealBrowser(popup)
        popup.webViewClient = WebViewClient()
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                onPopupClosed()
            }
        }
        onPopupRequested(popup)

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

private fun classifyMediaUrl(url: String, pageUrl: String?): DetectedMedia? {
    val path = url.substringBefore('?').substringAfterLast('/')
    val extension = path.substringAfterLast('.', "").lowercase()
    return when (extension) {
        in VIDEO_EXTENSIONS -> DetectedMedia(url, MediaType.VIDEO, extension, "video/$extension", pageUrl)
        in AUDIO_EXTENSIONS -> DetectedMedia(url, MediaType.AUDIO, extension, if (extension == "mp3") "audio/mpeg" else "audio/$extension", pageUrl)
        else -> null
    }
}

private fun fileNameFromUrl(url: String): String {
    val path = url.substringBefore('?').substringAfterLast('/')
    val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
    return decoded.substringBeforeLast('.').ifBlank { "Downloaded file" }
}
