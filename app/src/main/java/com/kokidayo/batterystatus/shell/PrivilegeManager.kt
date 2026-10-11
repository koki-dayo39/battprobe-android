package com.kokidayo.batterystatus.shell

import android.content.Context
import com.kokidayo.batterystatus.data.BackendPreference
import com.kokidayo.batterystatus.data.SettingsStore
import com.kokidayo.batterystatus.shizuku.ShizukuManager
import com.kokidayo.batterystatus.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Picks the active [ShellBackend] from the Shizuku service, root state and the
 * user's backend preference. Lives for the whole process so background workers
 * and widgets can use it too.
 */
object PrivilegeManager {
    private const val TAG = "PrivilegeManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsStore: SettingsStore
    private var initialized = false
    private val probing = MutableStateFlow(false)

    lateinit var backend: StateFlow<ShellBackend?>
        private set

    val rootProbing: StateFlow<Boolean> = probing

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        settingsStore = SettingsStore.get(context)
        ShizukuManager.init(context)

        backend = combine(
            ShizukuManager.service,
            RootShell.state,
            settingsStore.settings.map { it.backend }.distinctUntilChanged(),
        ) { service, rootState, pref ->
            val shizuku = service?.let { ShizukuBackend(it) }
            val root = RootShell.takeIf { rootState == RootState.GRANTED }
            when (pref) {
                BackendPreference.SHIZUKU -> shizuku
                BackendPreference.ROOT -> root
                BackendPreference.AUTO -> shizuku ?: root
            }
        }.distinctUntilChanged { a, b -> a?.label == b?.label }
            .stateIn(scope, SharingStarted.Eagerly, null)

        scope.launch {
            backend.collect { AppLog.i(TAG, "active backend: ${it?.label ?: "none"}") }
        }

        // Root was granted before: probe silently so AUTO/ROOT can use it without a tap.
        val pref = settingsStore.settings.value.backend
        if (settingsStore.rootGranted && pref != BackendPreference.SHIZUKU) requestRoot()
    }

    /** Explicitly asks for root (shows the su prompt on first use). */
    fun requestRoot() {
        if (probing.value) return
        probing.value = true
        scope.launch {
            try {
                val state = RootShell.probe()
                settingsStore.rootGranted = state == RootState.GRANTED
            } finally {
                probing.value = false
            }
        }
    }

    /** Waits briefly for a backend to become available (used by background workers). */
    suspend fun awaitBackend(timeoutMs: Long): ShellBackend? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            backend.value?.let { return it }
            kotlinx.coroutines.delay(200)
        }
        return backend.value
    }
}
