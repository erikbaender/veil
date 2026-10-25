package app.veil.android

import android.app.Application
import app.veil.core.settings.SettingsStore
import app.veil.core.settings.createSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VeilApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: SettingsStore by lazy { createSettingsStore(this, appScope) }
    val updater: AppUpdater by lazy { AppUpdater(this, appScope, settings) }
}
