package app.veil.android

import android.app.Application
import app.veil.core.settings.SettingsStore
import app.veil.core.settings.createSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VeilApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: SettingsStore by lazy { createSettingsStore(this, appScope) }
    val updater: AppUpdater by lazy { AppUpdater(this, appScope, settings) }

    override fun onCreate() {
        super.onCreate()
        // The background check for unread messages follows its setting.
        appScope.launch {
            MessageAlerts.schedule(this@VeilApplication, settings.load().messageNotifications)
            settings.settings.drop(1).map { it.messageNotifications }.distinctUntilChanged().collect { enabled ->
                MessageAlerts.schedule(this@VeilApplication, enabled)
            }
        }
    }
}
