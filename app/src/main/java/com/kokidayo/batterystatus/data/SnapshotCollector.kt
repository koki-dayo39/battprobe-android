package com.kokidayo.batterystatus.data

import android.content.Context
import com.kokidayo.batterystatus.shell.PrivilegeManager
import com.kokidayo.batterystatus.util.AppLog

/** Collects a single snapshot outside the UI (widget refresh, charger notification). */
class SnapshotCollector(context: Context) {
    private val repository = BatteryRepository(context)
    private val settingsStore = SettingsStore.get(context)
    private var previousShell: ShellData? = null

    suspend fun collect(backendWaitMs: Long = 0L): BatterySnapshot {
        val local = repository.readLocal()
        val backend = if (backendWaitMs > 0) PrivilegeManager.awaitBackend(backendWaitMs) else PrivilegeManager.backend.value
        val shell = if (backend != null) {
            try {
                repository.readShell(backend, previousShell, fullSysfs = false, timeoutMs = SHELL_TIMEOUT_MS)
                    .also { previousShell = it }
            } catch (e: Exception) {
                AppLog.w(TAG, "background shell read failed", e)
                previousShell
            }
        } else null
        return CurrentNormalizer.snapshot(local, shell, settingsStore.settings.value, settingsStore.learnedPolarity)
    }

    private companion object {
        const val TAG = "SnapshotCollector"
        const val SHELL_TIMEOUT_MS = 5_000L
    }
}
