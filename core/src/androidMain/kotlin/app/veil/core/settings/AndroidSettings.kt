package app.veil.core.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import okio.Path.Companion.toPath

/** Settings stored in the app's private files directory (excluded from backups). */
fun createSettingsStore(context: Context, scope: CoroutineScope): SettingsStore {
    val dataStore = PreferenceDataStoreFactory.createWithPath(
        produceFile = { context.filesDir.resolve("settings.preferences_pb").absolutePath.toPath() },
    )
    return SettingsStore(dataStore, scope)
}
