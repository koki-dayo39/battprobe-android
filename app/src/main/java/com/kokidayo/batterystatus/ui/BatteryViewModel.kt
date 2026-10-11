package com.kokidayo.batterystatus.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kokidayo.batterystatus.data.BatteryRepository
import com.kokidayo.batterystatus.data.BatterySnapshot
import com.kokidayo.batterystatus.data.CurrentNormalizer
import com.kokidayo.batterystatus.data.LocalBattery
import com.kokidayo.batterystatus.data.Settings
import com.kokidayo.batterystatus.data.SettingsStore
import com.kokidayo.batterystatus.data.ShellData
import com.kokidayo.batterystatus.diag.DiagnosticsReport
import com.kokidayo.batterystatus.shell.PrivilegeManager
import com.kokidayo.batterystatus.shell.RootShell
import com.kokidayo.batterystatus.shell.RootState
import com.kokidayo.batterystatus.shizuku.ShizukuManager
import com.kokidayo.batterystatus.shizuku.ShizukuState
import com.kokidayo.batterystatus.util.AppLog
import com.kokidayo.batterystatus.widget.ChargeWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

data class UiState(
    val snapshot: BatterySnapshot? = null,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    val root: RootState = RootState.UNKNOWN,
    val rootProbing: Boolean = false,
    /** Label of the backend currently feeding shell data ("Shizuku" / "Root"), null if none. */
    val backendLabel: String? = null,
    val shellUid: Int? = null,
    val shellError: String? = null,
    /** Battery power history in W, oldest first, at most [HISTORY_SIZE] samples. */
    val history: List<Float> = emptyList(),
) {
    val shellConnected: Boolean get() = backendLabel != null

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
    private val settingsStore = SettingsStore.get(app)

    val settings: StateFlow<Settings> = settingsStore.settings

    fun updateSettings(settings: Settings) = settingsStore.update(settings)

    fun requestShizukuPermission() = ShizukuManager.requestPermission()

    fun refreshShizuku() = ShizukuManager.refresh()

    fun requestRoot() = PrivilegeManager.requestRoot()

    fun buildDiagnostics(): String =
        DiagnosticsReport.build(getApplication(), uiState.value, settings.value)

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
            var lastBackendLabel: String? = null
            while (true) {
                val backend = PrivilegeManager.backend.value
                if (backend == null) {
                    shellData.value = null
                    shellError.value = null
                    shellUid.value = null
                    stats = null
                    lastFullAt = 0L
                    lastStatsAt = 0L
                    lastBackendLabel = null
                    delay(1_000)
                    continue
                }
                if (backend.label != lastBackendLabel) {
                    // Backend switched: start over so stale data from the other one is not shown.
                    lastBackendLabel = backend.label
                    shellData.value = null
                    shellUid.value = null
                    lastFullAt = 0L
                    lastStatsAt = 0L
                }
                val started = SystemClock.elapsedRealtime()
                try {
                    val wantFull = started - lastFullAt > FULL_SYSFS_INTERVAL_MS
                    val data = repository.readShell(backend, shellData.value, wantFull, SHELL_TIMEOUT_MS)
                    val took = SystemClock.elapsedRealtime() - started
                    if (wantFull && data.sysfsFullAt > 0) {
                        lastFullAt = started
                        AppLog.i(TAG, "${backend.label}: shell read with full sysfs scan: $took ms, " +
                            "${data.sysfsFull.values.sumOf { it.size }} nodes in ${data.sysfsFull.size} supplies")
                    } else if (shellData.value == null) {
                        AppLog.i(TAG, "${backend.label}: first shell read: $took ms, ${data.powerSupplies.size} supplies via uevent")
                    } else if (took > SHELL_TIMEOUT_MS / 2) {
                        AppLog.w(TAG, "slow shell read: $took ms")
                    }
                    val merged = stats?.let { data.copy(batteryStats = it.first, batteryStatsRaw = it.second) } ?: data
                    shellData.value = merged
                    shellError.value = null
                    if (shellUid.value == null) shellUid.value = backend.uid

                    // batterystats is slow (seconds to tens of seconds); refresh it in the background.
                    if (statsJob?.isActive != true && started - lastStatsAt > STATS_INTERVAL_MS) {
                        lastStatsAt = started
                        statsJob = launch {
                            val t0 = SystemClock.elapsedRealtime()
                            runCatching { repository.readBatteryStats(backend, STATS_TIMEOUT_MS) }
                                .onFailure { AppLog.w(TAG, "batterystats failed", it) }
                                .onSuccess { result ->
                                    AppLog.i(TAG, "batterystats: ${SystemClock.elapsedRealtime() - t0} ms, ${result.first.size} keys")
                                    if (result.second.isNotBlank()) {
                                        stats = result
                                        shellData.value = shellData.value
                                            ?.copy(batteryStats = result.first, batteryStatsRaw = result.second)
                                    }
                                }
                        }
                    }
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    if (shellError.value != message) AppLog.w(TAG, "shell read failed", e)
                    shellError.value = message
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                delay((settings.value.intervalMs - elapsed).coerceAtLeast(MIN_SHELL_GAP_MS))
            }
        }

        // UI loop: BatteryManager is cheap, so this always runs at the configured interval.
        var polarity = settingsStore.learnedPolarity
        val polarityLearner = PolarityLearner()
        val history = ArrayDeque<Float>()
        var lastWidgetPush = 0L
        while (true) {
            val currentSettings = settings.value
            val local = repository.readLocal()
            val shell = shellData.value

            val deviceMa = CurrentNormalizer.toDeviceMa(local.currentNowUa, currentSettings)
            if (deviceMa != null) {
                val learned = polarityLearner.update(local, deviceMa, SystemClock.elapsedRealtime())
                if (learned != null && learned != polarity) {
                    AppLog.i(TAG, "current polarity learned: $polarity -> $learned " +
                        "(plugged=${local.plugged}, status=${local.status}, reported=$deviceMa mA)")
                    polarity = learned
                    settingsStore.learnedPolarity = learned
                }
            }

            val snapshot = CurrentNormalizer.snapshot(local, shell, currentSettings, polarity)
            snapshot.powerW?.let {
                history.addLast(it.toFloat())
                while (history.size > UiState.HISTORY_SIZE) history.removeFirst()
            }

            send(Tick(snapshot, history.toList(), shellError.value, shellUid.value))

            val now = SystemClock.elapsedRealtime()
            if (now - lastWidgetPush > WIDGET_PUSH_INTERVAL_MS) {
                lastWidgetPush = now
                ChargeWidget.render(getApplication(), snapshot)
            }
            delay(currentSettings.intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<UiState> = combine(
        ticker,
        ShizukuManager.state,
        RootShell.state,
        PrivilegeManager.rootProbing,
        PrivilegeManager.backend,
    ) { tick, shizukuState, rootState, probing, backend ->
        UiState(
            snapshot = tick.snapshot,
            shizuku = shizukuState,
            root = rootState,
            rootProbing = probing,
            backendLabel = backend?.label,
            shellUid = tick.shellUid,
            shellError = tick.shellError,
            history = tick.history,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        UiState(
            shizuku = ShizukuManager.state.value,
            root = RootShell.state.value,
            backendLabel = PrivilegeManager.backend.value?.label,
        ),
    )

    /**
     * Learns whether the device reports charging current as positive (Android
     * convention, +1) or negative (-1). Two independent signals:
     *  - Unplugged: the battery can only be discharging, so the reported current
     *    must be negative in Android convention.
     *  - Charge counter: integrate the reported current between samples and
     *    compare its sign with the counter's actual change. This also works on a
     *    weak charger where the system says "charging" while the battery drains.
     */
    private class PolarityLearner {
        private var lastTime = 0L
        private var lastCounterUah: Int? = null
        private var integratedMah = 0.0

        fun update(local: LocalBattery, deviceMa: Double, nowMs: Long): Int? {
            var result: Int? = null

            if (local.plugged == 0 && abs(deviceMa) >= POLARITY_THRESHOLD_MA) {
                result = if (deviceMa < 0) 1 else -1
            }

            val counter = local.chargeCounterUah
            if (counter != null) {
                val prevCounter = lastCounterUah
                if (prevCounter != null && lastTime > 0L) {
                    val dtHours = (nowMs - lastTime) / 3_600_000.0
                    integratedMah += deviceMa * dtHours
                    val deltaMah = (counter - prevCounter) / 1000.0
                    if (abs(deltaMah) >= COUNTER_DELTA_MAH && abs(integratedMah) >= COUNTER_DELTA_MAH / 2) {
                        result = if ((deltaMah > 0) == (integratedMah > 0)) 1 else -1
                        integratedMah = 0.0
                        lastCounterUah = counter
                    }
                } else {
                    lastCounterUah = counter
                }
            }
            lastTime = nowMs
            return result
        }
    }

    override fun onCleared() {
        super.onCleared()
        ShizukuManager.unbindService()
    }

    private companion object {
        const val TAG = "BatteryViewModel"
        const val STATS_INTERVAL_MS = 60_000L
        const val STATS_TIMEOUT_MS = 90_000L
        const val SHELL_TIMEOUT_MS = 6_000L
        const val FULL_SYSFS_INTERVAL_MS = 15_000L
        const val MIN_SHELL_GAP_MS = 200L
        const val WIDGET_PUSH_INTERVAL_MS = 5_000L
        const val POLARITY_THRESHOLD_MA = 5.0
        const val COUNTER_DELTA_MAH = 8.0
    }
}
