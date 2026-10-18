package app.veil.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/** [SettingsRepository] backed by a preferences DataStore; the whole [Settings] is one JSON value. */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : SettingsRepository {
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val settings: StateFlow<Settings> = dataStore.data
        .map { prefs -> decode(prefs[key]) }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    /** Reads the stored settings once (used at startup, before the first page loads). */
    suspend fun load(): Settings = decode(dataStore.data.first()[key])

    override suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(Settings.serializer(), transform(decode(prefs[key])))
        }
    }

    private fun decode(raw: String?): Settings =
        raw?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()
}
