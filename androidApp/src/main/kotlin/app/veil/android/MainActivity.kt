package app.veil.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.veil.core.web.IncomingLinks
import app.veil.core.web.LinkSanitizer
import app.veil.ui.VeilApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    /** Instagram link handed to the app, waiting to be opened by the page. */
    private var incomingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = (application as VeilApplication).settings
        // Page inspection over adb for development; never enabled in release builds.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // Screenshot blocking follows the setting.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                settings.settings.map { it.secureScreen }.distinctUntilChanged().collect { secure ->
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        if (savedInstanceState == null) incomingUrl = linkFrom(intent)
        // Read the stored settings once so the first page is the right tab (a small local file).
        val startTab = runBlocking { settings.load() }.startTab

        setContent {
            VeilApp(
                settingsRepository = settings,
                versionName = BuildConfig.VERSION_NAME,
                startTab = startTab,
                incomingUrl = incomingUrl,
                onIncomingUrlConsumed = { incomingUrl = null },
                onOpenExternal = ::openInBrowser,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { incomingUrl = it }
    }

    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> IncomingLinks.instagramUrl(intent.dataString)
        Intent.ACTION_SEND -> IncomingLinks.instagramUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        else -> null
    }

    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LinkSanitizer.clean(url))).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser installed; nothing to do.
        }
    }
}
