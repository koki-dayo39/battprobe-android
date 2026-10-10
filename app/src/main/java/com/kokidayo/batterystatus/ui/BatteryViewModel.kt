package com.kokidayo.batterystatus.ui

import android.app.Application
import android.os.BatteryManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kokidayo.batterystatus.data.BatteryRepository
import com.kokidayo.batterystatus.data.BatterySnapshot
import com.kokidayo.batterystatus.data.CurrentUnit
import com.kokidayo.batterystatus.data.LocalBattery
import com.kokidayo.batterystatus.data.Settings
import com.kokidayo.batterystatus.data.SettingsStore
import com.kokidayo.batterystatus.data.ShellData
import com.kokidayo.batterystatus.data.SignMode
import com.kokidayo.batterystatus.shizuku.ShizukuManager
import com.kokidayo.batterystatus.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

data class UiState(
    val snapshot: BatterySnapshot? = null,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    val shellConnected: Boolean = false,
    val shellUid: Int? = null,
    val shellError: String? = null,
    /** Battery power history in W, oldest first, at most [HISTORY_SIZE] samples. */
    val history: List<Float> = emptyList(),
) {
    companion object {
        const val HISTORY_SIZE = 120
    }
}

private data class Tick(
    val snapshot: BatterySnapshot,
    val history: List<Float>,
    val shellError: String?,
    val shellUid: Int?,
)

class BatteryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = BatteryRepository(app)
    private val settingsStore = SettingsStore(app)

    val settings: StateFlow<Settings> = settingsStore.settings

    fun updateSettings(settings: Settings) = settingsStore.update(settings)

    fun requestShizukuPermission() = ShizukuManager.requestPermission()

    fun refreshShizuku() = ShizukuManager.refresh()

    private val ticker = channelFlow {
        var polarity = 1
        var lastShell: ShellData? = null
        var lastUid: Int? = null
        var stats: Pair<Map<String, String>, String>? = null
        var statsJob: Job? = null
        var lastStatsAt = 0L
        val history = ArrayDeque<Float>()

        while (true) {
            val currentSettings = settings.value
            val service = ShizukuManager.service.value
            val local = repository.readLocal()

            var shellError: String? = null
            val shell: ShellData? = if (service != null) {
                try {
                    val data = repository.readShell(service, lastShell, SHELL_TIMEOUT_MS)
                    if (lastUid == null) lastUid = runCatching { service.uid }.getOrNull()
                    val merged = stats?.let { data.copy(batteryStats = it.first, batteryStatsRaw = it.second) } ?: data
                    lastShell = merged

                    // batterystats is slow (seconds to tens of seconds); refresh it in the
                    // background so the live readings keep ticking.
                    val now = SystemClock.elapsedRealtime()
                    if (statsJob?.isActive != true && now - lastStatsAt > STATS_INTERVAL_MS) {
                        lastStatsAt = now
                        statsJob = launch {
                            runCatching { repository.readBatteryStats(service, STATS_TIMEOUT_MS) }
                                .onSuccess { result -> if (result.second.isNotBlank()) stats = result }
                        }
                    }
                    merged
                } catch (e: Exception) {
                    shellError = e.message ?: e.javaClass.simpleName
                    lastShell
                }
            } else {
                lastShell = null
                lastUid = null
                stats = null
                null
            }

            val deviceMa = normaliseCurrent(local.currentNowUa, currentSettings)
            if (deviceMa != null) polarity = updatePolarity(local, deviceMa, polarity)
            val currentMa = deviceMa?.let { applySign(it, currentSettings, polarity) }
            val currentAvgMa = normaliseCurrent(local.currentAvgUa, currentSettings)
                ?.let { applySign(it, currentSettings, polarity) }
            val voltageV = local.voltageMv / 1000.0
            val powerW = currentMa?.let { it / 1000.0 * voltageV }

            val snapshot = BatterySnapshot(
                timestamp = System.currentTimeMillis(),
                local = local,
                shell = shell,
                currentMa = currentMa,
                currentAvgMa = currentAvgMa,
                voltageV = voltageV,
                powerW = powerW,
            )

            if (powerW != null) {
                history.addLast(powerW.toFloat())
                while (history.size > UiState.HISTORY_SIZE) history.removeFirst()
            }

            send(Tick(snapshot, history.toList(), shellError, lastUid))
            delay(currentSettings.intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<UiState> = combine(
        ticker,
        ShizukuManager.state,
        ShizukuManager.service,
    ) { tick, shizukuState, service ->
        UiState(
            snapshot = tick.snapshot,
            shizuku = shizukuState,
            shellConnected = service != null,
            shellUid = tick.shellUid,
            shellError = tick.shellError,
            history = tick.history,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        UiState(shizuku = ShizukuManager.state.value, shellConnected = ShizukuManager.service.value != null),
    )

    /** Raw reading -> mA, in the device's own sign convention. */
    private fun normaliseCurrent(raw: Int?, settings: Settings): Double? {
        raw ?: return null
        return when (settings.currentUnit) {
            CurrentUnit.MICRO_AMP -> raw / 1000.0
            CurrentUnit.MILLI_AMP -> raw.toDouble()
        }
    }

    /**
     * Learns whether the device reports charging current as positive (Android
     * convention) or negative. Only updates on unambiguous states.
     */
    private fun updatePolarity(local: LocalBattery, deviceMa: Double, previous: Int): Int {
        if (abs(deviceMa) < POLARITY_THRESHOLD_MA) return previous
        return when (local.status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> if (deviceMa > 0) 1 else -1
            BatteryManager.BATTERY_STATUS_DISCHARGING -> if (deviceMa < 0) 1 else -1
            else -> previous
        }
    }

    private fun applySign(deviceMa: Double, settings: Settings, polarity: Int): Double =
        when (settings.signMode) {
            SignMode.AS_REPORTED -> deviceMa
            SignMode.INVERTED -> -deviceMa
            SignMode.AUTO -> deviceMa * polarity
        }

    override fun onCleared() {
        super.onCleared()
        ShizukuManager.unbindService()
    }

    private companion object {
        const val STATS_INTERVAL_MS = 60_000L
        const val STATS_TIMEOUT_MS = 90_000L
        const val SHELL_TIMEOUT_MS = 6_000L
        const val POLARITY_THRESHOLD_MA = 5.0
    }
}
