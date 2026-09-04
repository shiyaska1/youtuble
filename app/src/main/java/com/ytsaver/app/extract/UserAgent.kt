package com.ytsaver.app.extract

/**
 * A normal desktop-browser UA. Many CDNs reject requests carrying OkHttp's own
 * default UA (or silently serve an HTML error/captcha page instead of the
 * actual media with a 200/206 status), which is why every non-YouTube direct
 * link was failing while YouTube itself worked — NewPipeExtractor's requests
 * already carry a browser UA.
 */
const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
