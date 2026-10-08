package com.mobicareapp.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobicareapp.YtSaverApp
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.download.DownloadService
import com.mobicareapp.download.HlsResolver
import com.mobicareapp.extract.BROWSER_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

// YouTube and Facebook are deliberately left off this list — both hide their actual video behind
// encrypted/DASH streams or signed, obfuscated URLs that Browse's request-sniffing approach can't
// reliably catch, so a quick-access shortcut to them here just invites trying something that won't
// work. YouTube already has its own proper downloader on the Home screen (paste the link there
// instead); Facebook has no equivalent yet, so its only route is pasting the link into Browse
// directly, same as any other site.
private val QUICK_LINKS = listOf(
    "Instagram" to "https://www.instagram.com"
)

private val probeClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", BROWSER_USER_AGENT).build())
    }
    .build()

/**
 * Holds the Browse screen's state outside the composable itself so leaving the screen (back
 * button, navigating to Library, etc.) and returning doesn't lose the current page, the
 * detected-media list, or the address bar text — Compose disposes BrowserScreen's own `remember`
 * state (and the WebView it hosts) the moment its route leaves composition, which otherwise made
 * every return to Browse start over from a blank page.
 */
private object BrowserSession {
    var addressText by mutableStateOf("")
    var pendingUrl by mutableStateOf<String?>(null)
    var desktopMode by mutableStateOf(false)
    val detected = mutableStateListOf<DetectedMedia>()
    val sizes = mutableStateMapOf<String, Long?>()
    val hlsSegmentCounts = mutableStateMapOf<String, Int?>()
    val currentPageUrl = java.util.concurrent.atomic.AtomicReference<String?>(null)
    // Temporary diagnostic — counts every request shouldInterceptRequest actually sees, regardless
    // of whether it matched a video/audio extension. Media detection reportedly stopped working
    // entirely (even on a plain direct video file), and this is the quickest way to tell apart
    // "the WebView isn't handing us requests at all anymore" from "it is, but nothing matches" —
    // the fix is very different depending on which one it turns out to be.
    var requestsSeen by mutableStateOf(0)
}

/**
 * Lets another screen (Home, when a pasted link fails outright — often a sign-in wall or bot
 * check no plain HTTP fetch can get past) hand a URL straight to Browse's real WebView instead of
 * the user having to retype it after navigating over.
 */
fun openInBrowse(url: String) {
    BrowserSession.addressText = url
    BrowserSession.pendingUrl = url
}

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
fun BrowserScreen(
    onBack: () -> Unit,
    onOpenVideo: (queue: List<SavedMedia>, startIndex: Int, loop: Boolean) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var addressText by BrowserSession::addressText
    var pendingUrl by BrowserSession::pendingUrl
    val detected = BrowserSession.detected
    val sizes = BrowserSession.sizes
    val hlsSegmentCounts = BrowserSession.hlsSegmentCounts
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentPageUrl = BrowserSession.currentPageUrl
    // currentPageUrl only updates on a full page navigation (WebViewClient.onPageStarted) — a
    // site that changes "page" via JS history.pushState instead of a real navigation (mobile
    // YouTube switching between videos without reloading is exactly this) never fires that, so
    // it goes stale and "Copy page link" would keep copying whichever video was open first.
    // WebView.getUrl() itself doesn't have that problem — it always reflects the live document
    // location — and reading it here (a Compose click handler) is on the main thread, same as
    // the property itself requires.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var desktopMode by BrowserSession::desktopMode
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
                    IconButton(onClick = {
                        val link = webViewRef?.url?.takeIf { it.isNotBlank() }
                            ?: currentPageUrl.get()?.takeIf { it.isNotBlank() }
                            ?: addressText
                        if (link.isNotBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Page link", link))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy page link")
                    }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QUICK_LINKS.forEach { (label, url) ->
                    AssistChip(
                        onClick = {
                            addressText = url
                            pendingUrl = url
                        },
                        label = { Text(label) }
                    )
                }
                // Some sites still detect and block even a WebView disguised as real Chrome (or
                // behave subtly differently) — this hands the current page straight to the actual
                // Chrome app, which already has whatever login session the user has there.
                // There's no way to pull that session into this embedded WebView instead (Android
                // keeps every app's cookies in its own private, sandboxed storage — even this app
                // can't read Chrome's), so this is the practical equivalent: use real Chrome itself
                // for a page rather than trying to borrow its login state.
                AssistChip(
                    onClick = {
                        val target = webViewRef?.url?.takeIf { it.isNotBlank() }
                            ?: currentPageUrl.get()?.takeIf { it.isNotBlank() }
                            ?: addressText.takeIf { it.isNotBlank() }
                            ?: QUICK_LINKS.first().second
                        openInSystemChrome(context, target)
                    },
                    label = { Text("Open in Chrome") }
                )
            }

            // Temporary diagnostic line — see the comment on BrowserSession.requestsSeen.
            Text(
                "Requests seen: ${BrowserSession.requestsSeen}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

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
                        // Lets you check the file actually saved correctly right away, and remove
                        // it on the spot if it didn't, instead of having to go find it in Library.
                        val saved = p.savedMedia
                        if (p.done && p.error == null && saved != null) {
                            Spacer(Modifier.height(8.dp))
                            Row {
                                if (saved.type == MediaType.VIDEO) {
                                    TextButton(onClick = { onOpenVideo(listOf(saved), 0, false) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Play")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                TextButton(onClick = {
                                    coroutineScope.launch {
                                        MediaAccess.delete(context, saved.filePath)
                                        (context.applicationContext as YtSaverApp).database.savedMediaDao().delete(saved)
                                        DownloadService.clearProgress()
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Not OK, delete")
                                }
                            }
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
                        addJavascriptInterface(
                            VideoSrcBridge { src ->
                                probeAndAddMedia(src, currentPageUrl.get(), mainHandler, detected)
                            },
                            "YtSaverBridge"
                        )
                        startAutoSkipAdsLoop(this, mainHandler)
                        webViewRef = this
                        // Leaving Browse (back button, opening Library, etc.) disposes this whole
                        // composable, so a fresh WebView is created next time it's opened — reload
                        // whatever page was open before instead of starting blank every time.
                        currentPageUrl.get()?.let { loadUrl(it) }
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
                    // Consumed immediately rather than compared against webView.url — a redirect
                    // or an added trailing slash means the live URL never matches the string the
                    // user typed, which was re-triggering loadUrl() on every recomposition
                    // (several a second while detected-media state is updating) and looked like
                    // the page kept refreshing itself.
                    pendingUrl?.let { url ->
                        webView.loadUrl(url)
                        pendingUrl = null
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
// buttons/role="button" elements (plain <a> tags only count if given role="button", a common
// pattern for custom ad-player controls) rather than every element on the page, both to stay
// cheap enough to poll repeatedly and — more importantly — to leave plain links alone: a bare
// <a>"Skip to content"</a> accessibility link matches "skip" too, and clicking it every 1.5s
// looked like the whole page kept refreshing itself.
private const val AUTO_SKIP_AD_SCRIPT = """
(function() {
  try {
    var els = document.querySelectorAll('button, [role="button"], [class*="skip" i], [id*="skip" i], [aria-label*="skip" i]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.tagName === 'A' && !el.getAttribute('role')) continue;
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

// Sites like desimaal.live fetch the real video URL from an encrypted API and assign it to
// <video>.src in JS, and that URL is often a signed/CDN link with no .mp4 extension at all, so the
// extension-based sniffing in classifyMediaUrl never matches it. The <video> element itself always
// knows what it's playing, so ask it directly. blob: URLs (MSE players) can't be fetched from
// outside the page and are skipped.
private const val REPORT_VIDEO_SRC_SCRIPT = """
(function() {
  try {
    var vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      var srcs = [v.currentSrc, v.src];
      var sources = v.getElementsByTagName('source');
      for (var j = 0; j < sources.length; j++) srcs.push(sources[j].src);
      for (var k = 0; k < srcs.length; k++) {
        var u = srcs[k];
        if (u && /^https?:/i.test(u)) {
          YtSaverBridge.reportVideo(u);
        }
      }
    }
  } catch (e) {}
})();
"""

private class VideoSrcBridge(private val onVideoSrc: (String) -> Unit) {
    @JavascriptInterface
    fun reportVideo(url: String?) {
        if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) onVideoSrc(url)
    }
}

private fun startAutoSkipAdsLoop(webView: WebView, handler: Handler) {
    val runnable = object : Runnable {
        override fun run() {
            runCatching { webView.evaluateJavascript(AUTO_SKIP_AD_SCRIPT, null) }
            runCatching { webView.evaluateJavascript(REPORT_VIDEO_SRC_SCRIPT, null) }
            handler.postDelayed(this, AUTO_SKIP_AD_INTERVAL_MS)
        }
    }
    handler.postDelayed(runnable, AUTO_SKIP_AD_INTERVAL_MS)
}

private fun mediaSniffingWebViewClient(
    mainHandler: Handler,
    detected: androidx.compose.runtime.snapshots.SnapshotStateList<DetectedMedia>,
    currentPageUrl: java.util.concurrent.atomic.AtomicReference<String?>
): WebViewClient = object : WebViewClient() {
    // shouldInterceptRequest fires on a background thread, but WebView.getUrl() (view.url) is
    // only safe to call on the thread that owns the WebView — calling it here crashed the app on
    // literally every request. onPageStarted *does* run on the main thread, so track the current
    // page URL there instead of reading it off the WebView from the wrong thread.
    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        currentPageUrl.set(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        launchExternalUrlIfNeeded(view.context, request.url.toString())

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // A page that embeds its player in an iframe (Dailymotion and similar sites) fetches its
        // video from a CDN that checks the Referer against the iframe's own URL, not the outer
        // page's — currentPageUrl only ever tracks the top-level page's onPageStarted. The
        // request's own Referer header (set by the browser engine per w3c referrer-policy rules)
        // already reflects whichever document actually issued it, iframe or not, so prefer that
        // over our own tracking whenever it's present.
        mainHandler.post { BrowserSession.requestsSeen++ }
        val referer = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
            ?: currentPageUrl.get()
        val classified = classifyMediaUrl(request.url.toString(), referer)
        if (classified != null) {
            mainHandler.post {
                if (detected.none { mediaContentKey(it.url) == mediaContentKey(classified.url) }) detected.add(classified)
            }
        } else if (request.method == "GET" &&
            request.requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }
        ) {
            // A <video>/<audio> element always asks for "Range: bytes=0-", and many CDNs serve the
            // file from a path with no extension — the Content-Type is the only giveaway.
            probeAndAddMedia(request.url.toString(), referer, mainHandler, detected)
        }
        return super.shouldInterceptRequest(view, request)
    }
}

private val probedMediaKeys: MutableSet<String> =
    java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

/**
 * For URLs whose path says nothing about the format: fetch the first byte and decide from the
 * response Content-Type whether it's really video/audio/HLS, adding it to [detected] if so.
 */
private fun probeAndAddMedia(
    url: String,
    referer: String?,
    mainHandler: Handler,
    detected: androidx.compose.runtime.snapshots.SnapshotStateList<DetectedMedia>
) {
    val key = mediaContentKey(url)
    if (!url.startsWith("http", ignoreCase = true) || !probedMediaKeys.add(key)) return
    val cookie = CookieManager.getInstance().getCookie(url)
    val request = runCatching {
        Request.Builder().url(url).header("Range", "bytes=0-0").apply {
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
    }.getOrNull() ?: return
    probeClient.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {
            probedMediaKeys.remove(key)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val contentType = it.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val media = when {
                    contentType.contains("mpegurl") ->
                        DetectedMedia(url, MediaType.VIDEO, "ts", "video/mp2t", referer, isHls = true)
                    contentType.startsWith("video/") ->
                        DetectedMedia(url, MediaType.VIDEO, extensionForMime(contentType), contentType, referer)
                    contentType.startsWith("audio/") ->
                        DetectedMedia(url, MediaType.AUDIO, extensionForMime(contentType), contentType, referer)
                    else -> null
                } ?: return
                mainHandler.post {
                    if (detected.none { d -> mediaContentKey(d.url) == key }) detected.add(media)
                }
            }
        }
    })
}

private fun extensionForMime(mime: String): String = when (mime.substringAfter('/')) {
    "quicktime" -> "mov"
    "x-matroska" -> "mkv"
    "mpeg" -> if (mime.startsWith("audio/")) "mp3" else "mpg"
    "mp4a-latm", "x-m4a" -> "m4a"
    "x-wav" -> "wav"
    else -> mime.substringAfter('/').removePrefix("x-").ifBlank { "mp4" }
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

/** Opens [url] in the real Chrome app if it's installed, falling back to whatever the device's default browser is otherwise. */
private fun openInSystemChrome(context: Context, url: String) {
    val normalized = normalizeUrl(url)
    val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).setPackage("com.android.chrome")
    runCatching { context.startActivity(chromeIntent) }
        .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized))) }
}

/**
 * The same underlying video is sometimes caught twice under two different-looking URLs that only
 * differ in per-request signed-token query parameters. Comparing on the path instead of the full
 * URL collapses those into one entry in the detected-media list.
 */
private fun mediaContentKey(url: String): String = url.substringBefore('?')

private fun classifyMediaUrl(url: String, pageUrl: String?): DetectedMedia? {
    val path = url.substringBefore('?').substringAfterLast('/')
    val extension = path.substringAfterLast('.', "").lowercase()
    val byExtension = when (extension) {
        "m3u8" -> DetectedMedia(url, MediaType.VIDEO, "ts", "video/mp2t", pageUrl, isHls = true)
        in VIDEO_EXTENSIONS -> DetectedMedia(url, MediaType.VIDEO, extension, "video/$extension", pageUrl)
        in AUDIO_EXTENSIONS -> DetectedMedia(url, MediaType.AUDIO, extension, if (extension == "mp3") "audio/mpeg" else "audio/$extension", pageUrl)
        else -> null
    }
    return byExtension ?: classifyByMimeQueryParam(url, pageUrl)
}

/**
 * Some CDNs (YouTube's googlevideo.com among the biggest) serve the actual video/audio from a
 * path with no file extension at all — just "/videoplayback" — and identify the format only
 * through a "mime" query parameter instead. The plain extension check above never matches these,
 * which is exactly why detection could see plenty of requests on a page like that and still
 * classify none of them as media.
 */
private fun classifyByMimeQueryParam(url: String, pageUrl: String?): DetectedMedia? {
    val mime = runCatching { Uri.parse(url).getQueryParameter("mime") }.getOrNull()?.lowercase() ?: return null
    val subtype = mime.substringAfter('/', "").ifBlank { return null }
    return when {
        mime.startsWith("video/") -> DetectedMedia(url, MediaType.VIDEO, subtype, mime, pageUrl)
        mime.startsWith("audio/") -> DetectedMedia(url, MediaType.AUDIO, subtype, mime, pageUrl)
        else -> null
    }
}

private fun fileNameFromUrl(url: String): String {
    val path = url.substringBefore('?').substringAfterLast('/')
    val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
    return decoded.ifBlank { "Downloaded file" }
}
