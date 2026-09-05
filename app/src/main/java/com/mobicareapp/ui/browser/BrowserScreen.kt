package com.mobicareapp.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.mobicareapp.data.MediaType
import com.mobicareapp.download.DownloadService
import com.mobicareapp.download.HlsResolver
import com.mobicareapp.extract.BROWSER_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

private data class DetectedMedia(
    val url: String,
    val type: MediaType,
    val extension: String,
    val mimeType: String,
    val pageUrl: String?,
    val isHls: Boolean = false
)

// .ts on its own is deliberately not matched here: sites that stream via HLS fire off dozens of
// tiny .ts segment requests, one per few seconds of video — matching those individually is what
// showed up as "multiple small KB videos" with no way to tell which one was the real thing. The
// .m3u8 playlist that references them all is matched instead (below) and resolved into the full,
// ordered segment list by HlsResolver when downloaded.
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "3gp")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg")

// A real Android Chrome UA (not a desktop one) — Google's sign-in flow serves a different,
// stricter page to what it thinks is a desktop browser embedded somewhere it shouldn't be.
private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

private val probeClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", BROWSER_USER_AGENT).build())
    }
    .build()

/**
 * Many sites embed their video/audio behind a normal webpage (no direct file link, no YouTube-
 * style page we can extract from) — the only way in is to actually load the page and watch what
 * it fetches. This wraps a WebView configured to behave like real mobile Chrome (so things like
 * Google Sign-In work — a plain WebView gets blocked by Google's "this browser may not be
 * secure" check because of the X-Requested-With header Android adds by default, and because
 * Google's sign-in flow opens in a popup window a bare WebView otherwise can't display) and
 * inspects every request it makes; anything that looks like a raw media file gets listed with its
 * file size (a page usually fires off several small unrelated video/audio requests — ads,
 * thumbnails, preview clips — so size is what tells those apart from the actual video) and offered
 * as a one-tap download via the same DownloadService used everywhere else in the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var addressText by remember { mutableStateOf("") }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val detected = remember { mutableStateListOf<DetectedMedia>() }
    val sizes = remember { mutableStateMapOf<String, Long?>() }
    val hlsSegmentCounts = remember { mutableStateMapOf<String, Int?>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentPageUrl = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var desktopMode by remember { mutableStateOf(false) }
    val progress by DownloadService.progress.collectAsState()
    val queuedDownloads by DownloadService.queueSize.collectAsState()

    // The skip-ad polling loop below is scheduled on this same handler; stop it when the screen
    // is left so it doesn't keep firing against a WebView that's no longer shown.
    DisposableEffect(Unit) {
        onDispose { mainHandler.removeCallbacksAndMessages(null) }
    }

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
                    IconButton(onClick = { desktopMode = !desktopMode }) {
                        Icon(
                            if (desktopMode) Icons.Default.PhoneAndroid else Icons.Default.DesktopWindows,
                            contentDescription = if (desktopMode) "Switch to mobile site" else "Switch to desktop site"
                        )
                    }
                    TextButton(onClick = { pendingUrl = normalizeUrl(addressText) }) { Text("Go") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            progress?.let { p ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = if (p.done) {
                                    if (p.error != null) "Failed: ${p.error}" else "Saved \"${p.caption}\""
                                } else {
                                    "Saving \"${p.caption}\"…" + if (queuedDownloads > 1) " (${queuedDownloads - 1} more queued)" else ""
                                }
                            )
                            if (p.done) {
                                IconButton(onClick = { DownloadService.clearProgress() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                                }
                            } else {
                                TextButton(onClick = { DownloadService.cancelCurrent() }) { Text("Cancel") }
                            }
                        }
                        if (!p.done) {
                            Spacer(Modifier.height(8.dp))
                            val fraction = if (p.totalBytes > 0) p.bytesDone.toFloat() / p.totalBytes else 0f
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (detected.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = "${detected.size} detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        detected.clear()
                        sizes.clear()
                        hlsSegmentCounts.clear()
                    }) { Text("Clear") }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(if (detected.size > 3) 260.dp else (detected.size * 84).dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        detected.sortedWith(compareByDescending<DetectedMedia> { it.isHls }.thenByDescending { sizes[it.url] ?: -1L }),
                        key = { it.url }
                    ) { media ->
                        LaunchedEffect(media.url) {
                            if (media.isHls) {
                                if (!hlsSegmentCounts.containsKey(media.url)) {
                                    hlsSegmentCounts[media.url] = runCatching {
                                        HlsResolver.resolve(media.url, CookieManager.getInstance().getCookie(media.url), media.pageUrl).segmentUrls.size
                                    }.getOrNull()
                                }
                            } else if (!sizes.containsKey(media.url)) {
                                sizes[media.url] = probeContentLength(media.url, media.pageUrl)
                            }
                        }
                        DetectedMediaRow(
                            media = media,
                            sizeBytes = sizes[media.url],
                            hlsSegmentCount = hlsSegmentCounts[media.url],
                            onDownload = {
                                DownloadService.start(
                                    context = context,
                                    caption = fileNameFromUrl(media.url).ifBlank { "Video" },
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
                                    referer = media.pageUrl,
                                    isHls = media.isHls
                                )
                            },
                            onDismiss = { detected.remove(media) }
                        )
                    }
                }
            }

            AndroidView(
                // Without weight(), this fillMaxSize() competes with the progress card and
                // detected-media list above it for the Column's full height instead of just the
                // space left over — the total ends up taller than the screen, clipping the
                // WebView's bottom instead of properly sizing it to what's left.
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureAsRealBrowser(this)
                        webViewClient = mediaSniffingWebViewClient(mainHandler, detected, currentPageUrl)
                        webChromeClient = popupHostingWebChromeClient(
                            onPopupRequested = { popupWebView = it },
                            onPopupClosed = { popupWebView = null }
                        )
                        addJavascriptInterface(ScrapedMediaBridge(mainHandler, detected, currentPageUrl), "YtSaverBridge")
                        startAutoSkipAdsLoop(this, mainHandler)
                        startEmbeddedVideoExtractionLoop(this, mainHandler)
                    }
                },
                update = { webView ->
                    val desiredUserAgent = if (desktopMode) BROWSER_USER_AGENT else MOBILE_CHROME_USER_AGENT
                    if (webView.settings.userAgentString != desiredUserAgent) {
                        webView.settings.userAgentString = desiredUserAgent
                        // Desktop sites also check the viewport meta tag, not just the UA string —
                        // this is the same trick real browsers use for "Request desktop site".
                        webView.settings.useWideViewPort = desktopMode
                        webView.settings.loadWithOverviewMode = desktopMode
                        webView.reload()
                    }
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
        Dialog(
            onDismissRequest = { popupWebView = null },
            // Without this, the dialog window is capped to the platform's default dialog width
            // (roughly wrap-content) instead of the full screen, so a popup sign-in page renders
            // cramped into a small box rather than as a proper full-screen popup.
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

@Composable
private fun DetectedMediaRow(
    media: DetectedMedia,
    sizeBytes: Long?,
    hlsSegmentCount: Int?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (media.type == MediaType.VIDEO) Icons.Default.Videocam else Icons.Default.MusicNote,
                contentDescription = null
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (media.isHls) "Full video stream" else fileNameFromUrl(media.url),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        media.isHls -> when (hlsSegmentCount) {
                            null -> "Checking stream…"
                            0 -> "Couldn't read stream"
                            else -> "$hlsSegmentCount parts — downloads the whole video"
                        }
                        sizeBytes == null -> "Checking size…"
                        sizeBytes <= 0 -> media.extension.uppercase()
                        else -> "${formatBytes(sizeBytes)} · ${media.extension.uppercase()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDownload, enabled = !media.isHls || (hlsSegmentCount ?: 0) > 0) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Not this one")
            }
        }
    }
}

// Looks for buttons/links that mention "skip" (by visible text, aria-label, class, or id — the
// common ways an ad player marks its skip control) and clicks them. Scoped to
// buttons/links/role="button" elements rather than every element on the page so it stays cheap
// enough to poll repeatedly.
private const val AUTO_SKIP_AD_SCRIPT = """
(function() {
  try {
    var els = document.querySelectorAll('button, [role="button"], a, [class*="skip" i], [id*="skip" i], [aria-label*="skip" i]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var text = ((el.innerText || el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
      var cls = (el.className || '').toString().toLowerCase();
      var id = (el.id || '').toLowerCase();
      if (text.indexOf('skip') !== -1 || cls.indexOf('skip') !== -1 || id.indexOf('skip') !== -1) {
        var rect = el.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) { el.click(); }
      }
    }
  } catch (e) {}
})();
"""
private const val AUTO_SKIP_AD_INTERVAL_MS = 1500L

private fun startAutoSkipAdsLoop(webView: WebView, handler: Handler) {
    val runnable = object : Runnable {
        override fun run() {
            runCatching { webView.evaluateJavascript(AUTO_SKIP_AD_SCRIPT, null) }
            handler.postDelayed(this, AUTO_SKIP_AD_INTERVAL_MS)
        }
    }
    handler.postDelayed(runnable, AUTO_SKIP_AD_INTERVAL_MS)
}

// Facebook and Instagram play video through in-page JavaScript (Media Source Extensions) rather
// than pointing a <video> tag at one loadable URL, so shouldInterceptRequest never sees a single
// request for "the whole video" to sniff — only the small fragments the player happens to fetch
// under the hood, which look complete on their own (their own Content-Length is accurate) but are
// only a slice of the real thing. This instead digs the actual per-post video URL out of the
// page's own embedded data: the JSON fields Facebook's server-rendered markup still carries for
// unauthenticated/legacy clients, and the schema.org VideoObject JSON-LD block many post pages
// (Facebook and Instagram alike) still embed for search engines, which points at a plain
// progressive file instead of the fragmented stream the page itself plays. This is inherently a
// best-effort scrape of an undocumented, unstable page structure — it can stop matching whenever
// either site changes their markup, unlike the public, stable HLS .m3u8 playlists the download
// path already understands.
private const val EXTRACT_EMBEDDED_VIDEO_SCRIPT = """
(function() {
  try {
    var seen = window.__ytsaverSeenUrls || (window.__ytsaverSeenUrls = {});
    function report(url) {
      if (!url || seen[url]) return;
      seen[url] = true;
      try { window.YtSaverBridge.reportVideoUrl(url); } catch (e) {}
    }
    function unescapeJson(s) {
      try { return JSON.parse('"' + s + '"'); } catch (e) { return s.replace(/\\\//g, '/'); }
    }
    var html = document.documentElement.outerHTML;
    var patterns = [
      /"playable_url_quality_hd":"([^"]+)"/g,
      /"playable_url":"([^"]+)"/g,
      /"browser_native_hd_url":"([^"]+)"/g,
      /"browser_native_sd_url":"([^"]+)"/g,
      /"video_url":"([^"]+)"/g
    ];
    patterns.forEach(function(re) {
      var m;
      while ((m = re.exec(html)) !== null) { report(unescapeJson(m[1])); }
    });
    document.querySelectorAll('script[type="application/ld+json"]').forEach(function(node) {
      try {
        var data = JSON.parse(node.textContent);
        var items = Array.isArray(data) ? data : [data];
        items.forEach(function(item) {
          var videos = item && item['@type'] === 'VideoObject'
            ? [item]
            : (item && item.video ? (Array.isArray(item.video) ? item.video : [item.video]) : []);
          videos.forEach(function(v) { if (v && v.contentUrl) report(v.contentUrl); });
        });
      } catch (e) {}
    });
  } catch (e) {}
})();
"""
private const val EXTRACT_EMBEDDED_VIDEO_INTERVAL_MS = 2000L

private fun startEmbeddedVideoExtractionLoop(webView: WebView, handler: Handler) {
    val runnable = object : Runnable {
        override fun run() {
            runCatching { webView.evaluateJavascript(EXTRACT_EMBEDDED_VIDEO_SCRIPT, null) }
            handler.postDelayed(this, EXTRACT_EMBEDDED_VIDEO_INTERVAL_MS)
        }
    }
    handler.postDelayed(runnable, EXTRACT_EMBEDDED_VIDEO_INTERVAL_MS)
}

/**
 * Receives URLs found by [EXTRACT_EMBEDDED_VIDEO_SCRIPT]. JS run via evaluateJavascript has no
 * way to return a value back to Kotlin, so the script calls into this bridge directly instead.
 * Runs on a WebView-owned thread (not necessarily the UI thread), so it only touches thread-safe
 * state and posts the actual list mutation back to [handler].
 */
private class ScrapedMediaBridge(
    private val handler: Handler,
    private val detected: androidx.compose.runtime.snapshots.SnapshotStateList<DetectedMedia>,
    private val currentPageUrl: java.util.concurrent.atomic.AtomicReference<String?>
) {
    @JavascriptInterface
    fun reportVideoUrl(url: String) {
        val pageUrl = currentPageUrl.get()
        val media = classifyMediaUrl(url, pageUrl) ?: DetectedMedia(url, MediaType.VIDEO, "mp4", "video/mp4", pageUrl)
        handler.post {
            if (detected.none { mediaContentKey(it.url) == mediaContentKey(media.url) }) detected.add(media)
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
    detected: androidx.compose.runtime.snapshots.SnapshotStateList<DetectedMedia>,
    currentPageUrl: java.util.concurrent.atomic.AtomicReference<String?>
): WebViewClient = object : WebViewClient() {
    // shouldInterceptRequest fires on a background thread, but WebView.getUrl() (view.url) is
    // only safe to call on the thread that owns the WebView — calling it here crashed the app on
    // literally every request. onPageStarted *does* run on the main thread, so track the current
    // page URL there instead of reading it off the WebView from the wrong thread. Shared with
    // ScrapedMediaBridge so it can tag its own finds with the right referer too.
    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        currentPageUrl.set(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        launchExternalUrlIfNeeded(view.context, request.url.toString())

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        classifyMediaUrl(request.url.toString(), currentPageUrl.get())?.let { media ->
            mainHandler.post {
                if (detected.none { mediaContentKey(it.url) == mediaContentKey(media.url) }) detected.add(media)
            }
        }
        return super.shouldInterceptRequest(view, request)
    }
}

/**
 * Some login/verification flows (Facebook's device-approval screen, payment redirects, etc.)
 * hand off to an `intent://` URL or another non-http(s) scheme meant to launch a native app or
 * system component instead of opening a new page/popup — a WebView can't render those itself,
 * and without this it just silently does nothing when tapped, looking like the popup or
 * redirect failed. Returns true (link handled) only when something was actually launched, so a
 * plain http(s) navigation still falls through to the WebView as normal.
 */
private fun launchExternalUrlIfNeeded(context: Context, url: String): Boolean {
    if (url.startsWith("http://") || url.startsWith("https://")) return false
    return runCatching {
        val intent = if (url.startsWith("intent://")) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

private fun popupHostingWebChromeClient(
    onPopupRequested: (WebView) -> Unit,
    onPopupClosed: () -> Unit
): WebChromeClient = object : WebChromeClient() {
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        val popup = WebView(view.context)
        configureAsRealBrowser(popup)
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                launchExternalUrlIfNeeded(view.context, request.url.toString())
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                onPopupClosed()
            }
        }
        onPopupRequested(popup)

        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }
}

/** A quiet best-effort HEAD probe just to learn file size — failures are fine, the row just shows no size. */
private suspend fun probeContentLength(url: String, referer: String?): Long? = withContext(Dispatchers.IO) {
    runCatching {
        val cookie = CookieManager.getInstance().getCookie(url)
        val request = Request.Builder().url(url).head().apply {
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.header("Content-Length")?.toLongOrNull()
        }
    }.getOrNull()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

/**
 * The same underlying video is often caught twice under two different-looking URLs — once via
 * network sniffing, once via [EXTRACT_EMBEDDED_VIDEO_SCRIPT] finding another quality field for
 * the same post — that only differ in per-request signed-token query parameters. Comparing on
 * the path instead of the full URL collapses those into one entry in the detected-media list.
 */
private fun mediaContentKey(url: String): String = url.substringBefore('?')

private fun classifyMediaUrl(url: String, pageUrl: String?): DetectedMedia? {
    val path = url.substringBefore('?').substringAfterLast('/')
    val extension = path.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "m3u8" -> DetectedMedia(url, MediaType.VIDEO, "ts", "video/mp2t", pageUrl, isHls = true)
        in VIDEO_EXTENSIONS -> DetectedMedia(url, MediaType.VIDEO, extension, "video/$extension", pageUrl)
        in AUDIO_EXTENSIONS -> DetectedMedia(url, MediaType.AUDIO, extension, if (extension == "mp3") "audio/mpeg" else "audio/$extension", pageUrl)
        else -> null
    }
}

private fun fileNameFromUrl(url: String): String {
    val path = url.substringBefore('?').substringAfterLast('/')
    val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
    return decoded.ifBlank { "Downloaded file" }
}
