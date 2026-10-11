package io.github.koki_dayo39.batterystatus.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.koki_dayo39.batterystatus.data.BatteryRepository
import io.github.koki_dayo39.batterystatus.data.BatterySnapshot
import io.github.koki_dayo39.batterystatus.data.CurrentNormalizer
import io.github.koki_dayo39.batterystatus.data.LocalBattery
import io.github.koki_dayo39.batterystatus.data.NotifyMode
import io.github.koki_dayo39.batterystatus.data.Settings
import io.github.koki_dayo39.batterystatus.data.SettingsStore
import io.github.koki_dayo39.batterystatus.data.ShellData
import io.github.koki_dayo39.batterystatus.diag.DiagnosticsReport
import io.github.koki_dayo39.batterystatus.shell.PrivilegeManager
import io.github.koki_dayo39.batterystatus.shell.RootShell
import io.github.koki_dayo39.batterystatus.shell.RootState
import io.github.koki_dayo39.batterystatus.shizuku.ShizukuManager
import io.github.koki_dayo39.batterystatus.shizuku.ShizukuState
import io.github.koki_dayo39.batterystatus.util.AppLog
import io.github.koki_dayo39.batterystatus.background.ChargerEvents
import io.github.koki_dayo39.batterystatus.background.ChargingWork
import io.github.koki_dayo39.batterystatus.widget.ChargeWidget
import kotlinx.coroutines.CancellationException
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

    fun testNotification() = ChargingWork.startConnectNotification(getApplication(), test = true)

    fun setNotifyMode(mode: NotifyMode) {
        settingsStore.update(settings.value.copy(notifyMode = mode))
        ChargingWork.armChargingWatch(getApplication())
        if (mode == NotifyMode.OFF) ChargingWork.cancelConnectNotification(getApplication())
    }

    fun buildDiagnostics(): String =
        DiagnosticsReport.build(getApplication(), uiState.value, settings.value)

    private val ticker = channelFlow {
        val shellData = MutableStateFlow<ShellData?>(null)
        val shellError = MutableStateFlow<String?>(null)
        val shellUid = MutableStateFlow<Int?>(null)

        // Shell loop: runs on its own cadence so slow reads never delay the UI tick.
        launch {
            var lastFullAt = 0L
            var lastStatsAt = 0L
            var lastTimingLogAt = 0L
            var stats: Pair<Map<String, String>, String>? = null
            var full: Pair<Map<String, Map<String, String>>, String>? = null
            var fullAt = 0L
            var statsJob: Job? = null
            var fullJob: Job? = null
            var lastBackendLabel: String? = null
            while (true) {
                val backend = PrivilegeManager.backend.value
                if (backend == null) {
                    shellData.value = null
                    shellError.value = null
                    shellUid.value = null
                    stats = null
                    full = null
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
                    stats = null
                    full = null
                    lastFullAt = 0L
                    lastStatsAt = 0L
                }
                val started = SystemClock.elapsedRealtime()
                try {
                    val base = shellData.value?.let { prev ->
                        full?.let { prev.copy(sysfsFull = it.first, sysfsRaw = it.second, sysfsFullAt = fullAt) } ?: prev
                    }
                    val data = repository.readShell(backend, base, SHELL_TIMEOUT_MS)
                    val took = SystemClock.elapsedRealtime() - started
                    val timings = repository.lastFastTimings
                    if (shellData.value == null) {
                        AppLog.i(TAG, "${backend.label}: first shell read ${took} ms (${timings}), ${data.powerSupplies.size} supplies")
                    } else if (took > 1_500 || started - lastTimingLogAt > TIMING_LOG_INTERVAL_MS) {
                        lastTimingLogAt = started
                        AppLog.i(TAG, "shell read ${took} ms ($timings)")
                    }
                    val merged = data.copy(
                        sysfsFull = full?.first ?: data.sysfsFull,
                        sysfsRaw = full?.second ?: data.sysfsRaw,
                        sysfsFullAt = if (full != null) fullAt else data.sysfsFullAt,
                        powerSupplies = full?.let { mergeSupplies(data.powerSupplies, it.first) } ?: data.powerSupplies,
                        batteryStats = stats?.first ?: data.batteryStats,
                        batteryStatsRaw = stats?.second ?: data.batteryStatsRaw,
                    )
                    shellData.value = merged
                    shellError.value = null
                    if (shellUid.value == null) shellUid.value = backend.uid

                    // Full per-file scan: slow on some devices, so it runs beside the fast path.
                    if (fullJob?.isActive != true && started - lastFullAt > FULL_SYSFS_INTERVAL_MS) {
                        lastFullAt = started
                        fullJob = launch {
                            val t0 = SystemClock.elapsedRealtime()
                            runCatching { repository.readFullSysfs(backend, FULL_TIMEOUT_MS) }
                                .onFailure { if (it !is CancellationException) AppLog.w(TAG, "sysfs scan failed", it) }
                                .onSuccess { result ->
                                    val ms = SystemClock.elapsedRealtime() - t0
                                    if (result == null) {
                                        AppLog.w(TAG, "sysfs scan timed out after $ms ms (${repository.lastScanTimings})")
                                    } else {
                                        if (full == null) AppLog.i(TAG, "sysfs scan: $ms ms, ${result.first.values.sumOf { it.size }} nodes (${repository.lastScanTimings})")
                                        full = result
                                        fullAt = System.currentTimeMillis()
                                        shellData.value = shellData.value?.let { prev ->
                                            prev.copy(
                                                sysfsFull = result.first, sysfsRaw = result.second, sysfsFullAt = fullAt,
                                                powerSupplies = mergeSupplies(prev.powerSupplies, result.first),
                                            )
                                        }
                                    }
                                }
                        }
                    }

                    // batterystats is slow (seconds to tens of seconds); refresh it in the background.
                    if (statsJob?.isActive != true && started - lastStatsAt > STATS_INTERVAL_MS) {
                        lastStatsAt = started
                        statsJob = launch {
                            val t0 = SystemClock.elapsedRealtime()
                            runCatching { repository.readBatteryStats(backend, STATS_TIMEOUT_MS) }
                                .onFailure { if (it !is CancellationException) AppLog.w(TAG, "batterystats failed", it) }
                                .onSuccess { result ->
                                    val ms = SystemClock.elapsedRealtime() - t0
                                    if (result.first.isEmpty()) {
                                        AppLog.w(TAG, "batterystats: $ms ms, no matching lines; raw=" + result.second.take(200).replace('\n', ' '))
                                    } else {
                                        AppLog.i(TAG, "batterystats: $ms ms, ${result.first.size} keys")
                                        stats = result
                                        shellData.value = shellData.value
                                            ?.copy(batteryStats = result.first, batteryStatsRaw = result.second)
                                    }
                                }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
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
        var lastPlugged: Int? = null
        while (true) {
            val currentSettings = settings.value
            val local = repository.readLocal()
            val shell = shellData.value

            // Instant plug-in detection while the app is open (the system broadcast cannot wake us).
            if (lastPlugged != null && lastPlugged != local.plugged) {
                ChargerEvents.onPluggedChanged(getApplication(), local.plugged != 0, "app")
            }
            lastPlugged = local.plugged
            if (local.plugged == 0) ChargerEvents.syncUnplugged(getApplication())

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

    private fun mergeSupplies(
        base: Map<String, Map<String, String>>,
        overlay: Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>> {
        if (overlay.isEmpty()) return base
        val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
        base.forEach { (name, values) -> result[name] = LinkedHashMap(values) }
        overlay.forEach { (name, values) -> result.getOrPut(name) { LinkedHashMap() }.putAll(values) }
        return result
    }

    private companion object {
        const val TAG = "BatteryViewModel"
        const val FULL_TIMEOUT_MS = 30_000L
        const val TIMING_LOG_INTERVAL_MS = 60_000L
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
