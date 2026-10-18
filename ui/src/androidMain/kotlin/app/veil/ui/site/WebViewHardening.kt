package app.veil.ui.site

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import app.veil.core.web.HostPolicy
import java.io.ByteArrayInputStream

/** Settings shared by every WebView in the app: JS for the site, nothing else it doesn't need. */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyHardening() {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@applyHardening, false)
    }
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = false
    }
}

/** True when the request may go out (https to an allowlisted Instagram host). */
internal fun WebResourceRequest.isAllowed(): Boolean =
    url.scheme == "https" && HostPolicy.isAllowed(url.host.orEmpty())

/** Empty response returned for anything outside the allowlist (trackers, third-party scripts). */
internal fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
