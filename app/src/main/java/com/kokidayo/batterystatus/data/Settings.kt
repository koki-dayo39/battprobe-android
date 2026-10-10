package com.kokidayo.batterystatus.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CurrentUnit { MICRO_AMP, MILLI_AMP }

enum class SignMode { AUTO, AS_REPORTED, INVERTED }

data class Settings(
    val intervalMs: Long = 1000L,
    val currentUnit: CurrentUnit = CurrentUnit.MICRO_AMP,
    val signMode: SignMode = SignMode.AUTO,
) {
    companion object {
        val INTERVAL_OPTIONS = listOf(500L, 1000L, 2000L, 5000L)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings = Settings(
        intervalMs = prefs.getLong(KEY_INTERVAL, 1000L),
        currentUnit = enumOrDefault(prefs.getString(KEY_UNIT, null), CurrentUnit.MICRO_AMP),
        signMode = enumOrDefault(prefs.getString(KEY_SIGN, null), SignMode.AUTO),
    )

    /** +1 if the device reports charging current as positive (Android convention), -1 if inverted. */
    var learnedPolarity: Int
        get() = prefs.getInt(KEY_POLARITY, 1).let { if (it < 0) -1 else 1 }
        set(value) = prefs.edit { putInt(KEY_POLARITY, if (value < 0) -1 else 1) }

    fun update(settings: Settings) {
        _settings.value = settings
        prefs.edit {
            putLong(KEY_INTERVAL, settings.intervalMs)
            putString(KEY_UNIT, settings.currentUnit.name)
            putString(KEY_SIGN, settings.signMode.name)
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_INTERVAL = "interval_ms"
        const val KEY_UNIT = "current_unit"
        const val KEY_SIGN = "sign_mode"
        const val KEY_POLARITY = "learned_polarity"
    }
}
