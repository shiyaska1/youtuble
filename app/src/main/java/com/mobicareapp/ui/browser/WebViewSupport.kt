package com.mobicareapp.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.mobicareapp.extract.BROWSER_USER_AGENT

// A real Android Chrome UA (not a desktop one) — Google's sign-in flow serves a different,
// stricter page to what it thinks is a desktop browser embedded somewhere it shouldn't be.
internal const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Shared by the Browse screen and the sign-in dialog: makes a WebView behave like real mobile
 * Chrome so things like Google Sign-In work — a plain WebView gets blocked by Google's "this
 * browser may not be secure" check because of the X-Requested-With header Android adds by
 * default, and because Google's sign-in flow opens in a popup window a bare WebView otherwise
 * can't display.
 */
internal fun configureAsRealBrowser(webView: WebView) {
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

/**
 * Same as [configureAsRealBrowser] but rendered as a desktop site — many sites' login forms are
 * cramped or missing fields entirely on their mobile layout. Only meant for a page the user
 * navigates directly (the sign-in dialog's main WebView); an OAuth popup it spawns (Google
 * Sign-In, etc.) should stay on [configureAsRealBrowser]'s mobile UA, since that's specifically
 * what gets it past Google's "this browser may not be secure" block.
 */
internal fun configureAsDesktopBrowser(webView: WebView) {
    configureAsRealBrowser(webView)
    webView.settings.userAgentString = BROWSER_USER_AGENT
    webView.settings.useWideViewPort = true
    webView.settings.loadWithOverviewMode = true
}

/**
 * Some login/verification flows (Facebook's device-approval screen, payment redirects, etc.)
 * hand off to an `intent://` URL or another non-http(s) scheme meant to launch a native app or
 * system component instead of opening a new page/popup — a WebView can't render those itself,
 * and without this it just silently does nothing when tapped, looking like the popup or
 * redirect failed. Returns true (link handled) only when something was actually launched, so a
 * plain http(s) navigation still falls through to the WebView as normal.
 */
internal fun launchExternalUrlIfNeeded(context: Context, url: String): Boolean {
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

/**
 * Google's sign-in (and many other OAuth flows) opens via window.open() into a popup rather than
 * navigating the current page — a bare WebView silently drops that. This hands the popup's own
 * WebView back to the caller (to host in a Dialog on top of the page that requested it), which is
 * what lets the flow complete.
 */
internal fun popupHostingWebChromeClient(
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
