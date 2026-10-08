package com.mobicareapp.ui.upload

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.upload.UploadWebSettings

/**
 * The Record screen's "Upload" page: the user's own upload website inside an in-app browser that
 * behaves like Chrome for it — file choosers (the page's "choose file" button), downloads (saved to
 * the phone's Downloads folder with a notification), camera/microphone/location prompts, cookies,
 * plus a back button, zoom in/out and refresh in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadWebScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Choosing a file (or granting a permission) leaves the app for a moment; without this the
    // app-lock could swap this page out mid-upload.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Upload") }
    var menuOpen by remember { mutableStateOf(false) }
    var showAddressDialog by remember { mutableStateOf(false) }

    // The page's own "choose file" button: the callback has to be answered exactly once, even if
    // the user backs out of the picker (with null), or the page's file input stops working.
    val fileCallback = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
    val fileChooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileCallback[0]?.onReceiveValue(uris)
        fileCallback[0] = null
    }

    // Camera / microphone requests from the page: ask Android first, then answer the page.
    val pendingWebRequest = remember { arrayOfNulls<PermissionRequest>(1) }
    val webPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingWebRequest[0]?.let { request ->
            val granted = request.resources.filter { resource ->
                androidPermissionFor(resource)?.let { hasPermission(context, it) } == true
            }
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        }
        pendingWebRequest[0] = null
    }

    // Location requests (e.g. a page that asks "allow location?").
    val pendingGeo = remember { arrayOfNulls<Pair<String, GeolocationPermissions.Callback>>(1) }
    val geoPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        pendingGeo[0]?.let { (origin, callback) -> callback.invoke(origin, results.values.any { it }, false) }
        pendingGeo[0] = null
    }

    // The page's own history first; only when there's none left does Back leave this screen.
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    if (showAddressDialog) {
        var input by remember { mutableStateOf(webView?.url ?: UploadWebSettings.getUrl(context)) }
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text("Web address") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        UploadWebSettings.setUrl(context, input)
                        webView?.loadUrl(UploadWebSettings.getUrl(context))
                        showAddressDialog = false
                    }
                ) { Text("Save and open") }
            },
            dismissButton = { TextButton(onClick = { showAddressDialog = false }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(pageTitle, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = {
                    val view = webView
                    if (view != null && view.canGoBack()) view.goBack() else onBack()
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { webView?.zoomOut() }) { Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out") }
                IconButton(onClick = { webView?.zoomIn() }) { Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in") }
                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Change address") }, onClick = { menuOpen = false; showAddressDialog = true })
                    DropdownMenuItem(
                        text = { Text("Open in Chrome") },
                        onClick = {
                            menuOpen = false
                            val target = webView?.url ?: UploadWebSettings.getUrl(context)
                            AppLockManager.suppressNextLock()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                        }
                    )
                    DropdownMenuItem(text = { Text("Close") }, onClick = { menuOpen = false; onBack() })
                }
            }
        )
        if (progress < 100) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    configureForUploads(this)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (url.startsWith("http://") || url.startsWith("https://")) return false
                            // mailto:, tel:, intent:// and the like belong to other apps.
                            AppLockManager.suppressNextLock()
                            return runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)); true }.getOrDefault(true)
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canGoBack = view.canGoBack()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView, title: String?) {
                            if (!title.isNullOrBlank()) pageTitle = title
                        }

                        override fun onShowFileChooser(
                            view: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams
                        ): Boolean {
                            // A second chooser opened before the first answered would leave the first page input hanging.
                            fileCallback[0]?.onReceiveValue(null)
                            fileCallback[0] = callback
                            return try {
                                AppLockManager.suppressNextLock()
                                fileChooserLauncher.launch(params.createIntent())
                                true
                            } catch (e: Exception) {
                                fileCallback[0] = null
                                callback.onReceiveValue(null)
                                Toast.makeText(ctx, "Couldn't open the file picker", Toast.LENGTH_SHORT).show()
                                true
                            }
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                val needed = request.resources.mapNotNull { androidPermissionFor(it) }.distinct()
                                if (needed.isEmpty()) {
                                    request.deny()
                                } else if (needed.all { hasPermission(ctx, it) }) {
                                    request.grant(request.resources)
                                } else {
                                    pendingWebRequest[0]?.deny()
                                    pendingWebRequest[0] = request
                                    AppLockManager.suppressNextLock()
                                    webPermissionLauncher.launch(needed.toTypedArray())
                                }
                            }
                        }

                        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                            val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (permissions.any { hasPermission(ctx, it) }) {
                                callback.invoke(origin, true, false)
                            } else {
                                pendingGeo[0] = origin to callback
                                AppLockManager.suppressNextLock()
                                geoPermissionLauncher.launch(permissions)
                            }
                        }
                    }
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        downloadFile(ctx, url, userAgent, contentDisposition, mimeType)
                    }
                    webView = this
                    loadUrl(UploadWebSettings.getUrl(ctx))
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // Answer a still-open file chooser so the page isn't left waiting on it.
            fileCallback[0]?.onReceiveValue(null)
            fileCallback[0] = null
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}

/** Chrome-like settings for a page that uploads and downloads files. */
private fun configureForUploads(view: WebView) {
    com.mobicareapp.ui.browser.configureAsRealBrowser(view)
    view.settings.apply {
        // A link with target="_blank" or window.open() then simply loads in this same view.
        setSupportMultipleWindows(false)
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = true
        allowContentAccess = true
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        setGeolocationEnabled(true)
    }
}

private fun androidPermissionFor(webResource: String): String? = when (webResource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
    else -> null
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Hands a download to Android's DownloadManager — it shows progress in the notification shade and saves into the Downloads folder, with the page's cookies so logged-in downloads work. */
private fun downloadFile(context: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        Toast.makeText(context, "This kind of download can't be saved directly. Use the site's own download link.", Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            mimeType?.let { setMimeType(it) }
            userAgent?.let { addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(context, "Downloading $fileName — see the notification", Toast.LENGTH_LONG).show()
    }.onFailure {
        Toast.makeText(context, "Couldn't start the download", Toast.LENGTH_LONG).show()
    }
}
