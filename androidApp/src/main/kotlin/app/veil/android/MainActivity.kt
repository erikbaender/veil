package app.veil.android

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import app.veil.core.web.IncomingLinks
import app.veil.core.web.SiteTab
import app.veil.core.web.LinkSanitizer
import app.veil.ui.VeilApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class MainActivity : ComponentActivity() {
    /** Instagram link handed to the app, waiting to be opened by the page. */
    private var incomingUrl by mutableStateOf<String?>(null)
    /** A tab to switch to (the Messages tab, from a notification). */
    private var incomingTab by mutableStateOf<SiteTab?>(null)

    /** Asked when the user turns message notifications on; without it the setting goes back off. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) lifecycleScope.launch { (application as VeilApplication).settings.update { it.copy(messageNotifications = false) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as VeilApplication
        val settings = app.settings
        val updater = app.updater
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

        // Updates: the daily check runs whenever the app comes to the front; the installer's
        // confirmation is shown only while the app is in front (it can't start from the background).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updater.checkIfDue()
                updater.confirmation.filterNotNull().collect { confirm ->
                    try {
                        startActivity(confirm)
                    } catch (_: ActivityNotFoundException) {
                        // No installer UI; the status stays at "installing" until the user retries.
                    }
                    updater.confirmationShown()
                }
            }
        }
        if (BuildConfig.DEBUG) registerUpdateDiagnostics(updater)

        // Message notifications: Android's permission is asked for when they are turned on; while
        // the app is in front its own badge stands in for them, and checks held after a sign-in
        // or security check may run again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                settings.settings.map { it.messageNotifications }.distinctUntilChanged().collect { on ->
                    if (on && !MessageAlerts.canNotify(this@MainActivity) && Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MessageAlerts.cancel(this@MainActivity)
                if (settings.load().messageChecksHeld) settings.update { it.copy(messageChecksHeld = false) }
            }
        }

        if (savedInstanceState == null) {
            incomingUrl = linkFrom(intent)
            incomingTab = tabFrom(intent)
        }
        // Read the stored settings once so the first page is the right tab (a small local file).
        val startTab = runBlocking { settings.load() }.startTab

        setContent {
            VeilApp(
                settingsRepository = settings,
                updates = updater,
                versionName = BuildConfig.VERSION_NAME,
                startTab = startTab,
                incomingUrl = incomingUrl,
                onIncomingUrlConsumed = { incomingUrl = null },
                incomingTab = incomingTab,
                onIncomingTabConsumed = { incomingTab = null },
                onOpenExternal = ::openInBrowser,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { incomingUrl = it }
        tabFrom(intent)?.let { incomingTab = it }
    }

    private fun tabFrom(intent: Intent?): SiteTab? =
        if (intent?.getBooleanExtra(MessageAlerts.EXTRA_OPEN_MESSAGES, false) == true) SiteTab.MESSAGES else null

    /**
     * Debug builds: `am broadcast -a app.veil.diag.CHECK_MESSAGES -p app.veil.client.debug` runs one
     * unread-message check now (it does nothing while the app is in front).
     * `am broadcast -a app.veil.diag.UPDATE_LOCAL --es file <name> --ei code <versionCode>
     * -p app.veil.client.debug` installs an APK placed in the app's cache directory. Only senders
     * holding DUMP (the shell) can reach it.
     */
    private fun registerUpdateDiagnostics(updater: AppUpdater) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == "app.veil.diag.CHECK_MESSAGES") return MessageAlerts.checkNow(c)
                val name = intent.getStringExtra("file")?.takeIf { '/' !in it } ?: return
                updater.installLocal(File(cacheDir, name), intent.getIntExtra("code", 0))
            }
        }
        val filter = IntentFilter("app.veil.diag.UPDATE_LOCAL").apply { addAction("app.veil.diag.CHECK_MESSAGES") }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null)
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = unregisterReceiver(receiver)
        })
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
