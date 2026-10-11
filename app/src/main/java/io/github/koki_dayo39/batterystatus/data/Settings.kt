package io.github.koki_dayo39.batterystatus.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CurrentUnit { MICRO_AMP, MILLI_AMP }

enum class SignMode { AUTO, AS_REPORTED, INVERTED }

/** What the charger notification does. */
enum class NotifyMode { OFF, CONNECT_30S, WHILE_CHARGING }

/** Which privileged backend to use for dumpsys / sysfs. */
enum class BackendPreference { AUTO, SHIZUKU, ROOT }

data class Settings(
    val intervalMs: Long = 1000L,
    val currentUnit: CurrentUnit = CurrentUnit.MICRO_AMP,
    val signMode: SignMode = SignMode.AUTO,
    val backend: BackendPreference = BackendPreference.AUTO,
    val notifyMode: NotifyMode = NotifyMode.OFF,
) {
    val notifyOnConnect: Boolean get() = notifyMode != NotifyMode.OFF

    companion object {
        val INTERVAL_OPTIONS = listOf(500L, 1000L, 2000L, 5000L)
    }
}

class SettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings = Settings(
        intervalMs = prefs.getLong(KEY_INTERVAL, 1000L),
        currentUnit = enumOrDefault(prefs.getString(KEY_UNIT, null), CurrentUnit.MICRO_AMP),
        signMode = enumOrDefault(prefs.getString(KEY_SIGN, null), SignMode.AUTO),
        backend = enumOrDefault(prefs.getString(KEY_BACKEND, null), BackendPreference.AUTO),
        notifyMode = enumOrDefault(prefs.getString(KEY_NOTIFY_MODE, null),
            if (prefs.getBoolean(KEY_NOTIFY, false)) NotifyMode.CONNECT_30S else NotifyMode.OFF),
    )

    /** +1 if the device reports charging current as positive (Android convention), -1 if inverted. */
    var learnedPolarity: Int
        get() = prefs.getInt(KEY_POLARITY, 1).let { if (it < 0) -1 else 1 }
        set(value) = prefs.edit { putInt(KEY_POLARITY, if (value < 0) -1 else 1) }

    /**
     * True while the current plug-in session has already shown its notification, so the
     * system-driven charging job does not show it again. Expires after 12 h in case the
     * unplug was never observed.
     */
    var notifiedThisSession: Boolean
        get() = prefs.getBoolean(KEY_NOTIFIED_SESSION, false) &&
            System.currentTimeMillis() - prefs.getLong(KEY_NOTIFIED_AT, 0L) < 12 * 60 * 60 * 1000L
        set(value) = prefs.edit {
            putBoolean(KEY_NOTIFIED_SESSION, value)
            putLong(KEY_NOTIFIED_AT, if (value) System.currentTimeMillis() else 0L)
        }

    /** Remembered so AUTO can fall back to root without prompting on every launch. */
    var rootGranted: Boolean
        get() = prefs.getBoolean(KEY_ROOT_GRANTED, false)
        set(value) = prefs.edit { putBoolean(KEY_ROOT_GRANTED, value) }

    fun update(settings: Settings) {
        _settings.value = settings
        prefs.edit {
            putLong(KEY_INTERVAL, settings.intervalMs)
            putString(KEY_UNIT, settings.currentUnit.name)
            putString(KEY_SIGN, settings.signMode.name)
            putString(KEY_BACKEND, settings.backend.name)
            putBoolean(KEY_NOTIFY, settings.notifyOnConnect)
            putString(KEY_NOTIFY_MODE, settings.notifyMode.name)
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_UNIT = "current_unit"
        private const val KEY_SIGN = "sign_mode"
        private const val KEY_POLARITY = "learned_polarity"
        private const val KEY_BACKEND = "backend"
        private const val KEY_NOTIFY = "notify_on_connect"
        private const val KEY_ROOT_GRANTED = "root_granted"
        private const val KEY_NOTIFY_MODE = "notify_mode"
        private const val KEY_NOTIFIED_SESSION = "notified_session"
        private const val KEY_NOTIFIED_AT = "notified_at"

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
