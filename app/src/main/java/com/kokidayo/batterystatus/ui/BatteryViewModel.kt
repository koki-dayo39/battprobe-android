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
import com.kokidayo.batterystatus.data.batterySupply
import com.kokidayo.batterystatus.data.leadingNumber
import com.kokidayo.batterystatus.data.voltageToV
import com.kokidayo.batterystatus.shizuku.ShizukuManager
import com.kokidayo.batterystatus.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
        val shellData = MutableStateFlow<ShellData?>(null)
        val shellError = MutableStateFlow<String?>(null)
        val shellUid = MutableStateFlow<Int?>(null)

        // Shell loop: runs on its own cadence so a slow sysfs scan never delays the UI tick.
        launch {
            var lastFullAt = 0L
            var lastStatsAt = 0L
            var stats: Pair<Map<String, String>, String>? = null
            var statsJob: Job? = null
            while (true) {
                val service = ShizukuManager.service.value
                if (service == null) {
                    shellData.value = null
                    shellError.value = null
                    shellUid.value = null
                    stats = null
                    lastFullAt = 0L
                    lastStatsAt = 0L
                    delay(1_000)
                    continue
                }
                val started = SystemClock.elapsedRealtime()
                try {
                    val wantFull = started - lastFullAt > FULL_SYSFS_INTERVAL_MS
                    val data = repository.readShell(service, shellData.value, wantFull, SHELL_TIMEOUT_MS)
                    if (wantFull && data.sysfsFullAt > 0) lastFullAt = started
                    val merged = stats?.let { data.copy(batteryStats = it.first, batteryStatsRaw = it.second) } ?: data
                    shellData.value = merged
                    shellError.value = null
                    if (shellUid.value == null) shellUid.value = runCatching { service.uid }.getOrNull()

                    // batterystats is slow (seconds to tens of seconds); refresh it in the background.
                    if (statsJob?.isActive != true && started - lastStatsAt > STATS_INTERVAL_MS) {
                        lastStatsAt = started
                        statsJob = launch {
                            runCatching { repository.readBatteryStats(service, STATS_TIMEOUT_MS) }
                                .onSuccess { result ->
                                    if (result.second.isNotBlank()) {
                                        stats = result
                                        shellData.value = shellData.value
                                            ?.copy(batteryStats = result.first, batteryStatsRaw = result.second)
                                    }
                                }
                        }
                    }
                } catch (e: Exception) {
                    shellError.value = e.message ?: e.javaClass.simpleName
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                delay((settings.value.intervalMs - elapsed).coerceAtLeast(MIN_SHELL_GAP_MS))
            }
        }

        // UI loop: BatteryManager is cheap, so this always runs at the configured interval.
        var polarity = 1
        val history = ArrayDeque<Float>()
        while (true) {
            val currentSettings = settings.value
            val local = repository.readLocal()
            val shell = shellData.value

            val deviceMa = normaliseCurrent(local.currentNowUa, currentSettings)
            if (deviceMa != null) polarity = updatePolarity(local, deviceMa, polarity)
            val currentMa = deviceMa?.let { applySign(it, currentSettings, polarity) }
            val currentAvgMa = normaliseCurrent(local.currentAvgUa, currentSettings)
                ?.let { applySign(it, currentSettings, polarity) }
            // The sticky broadcast's voltage only changes when the system re-broadcasts
            // (every few seconds); prefer the live sysfs reading when we have it.
            val voltageV = shell?.batterySupply?.get("voltage_now").leadingNumber()
                ?.voltageToV()?.takeIf { it in 2.0..12.0 }
                ?: (local.voltageMv / 1000.0)
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

            send(Tick(snapshot, history.toList(), shellError.value, shellUid.value))
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
        const val FULL_SYSFS_INTERVAL_MS = 15_000L
        const val MIN_SHELL_GAP_MS = 200L
        const val STATS_TIMEOUT_MS = 90_000L
        const val SHELL_TIMEOUT_MS = 6_000L
        const val POLARITY_THRESHOLD_MA = 5.0
    }
}
