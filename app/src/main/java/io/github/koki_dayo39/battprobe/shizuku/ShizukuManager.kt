package io.github.koki_dayo39.battprobe.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.koki_dayo39.battprobe.util.AppLog
import io.github.koki_dayo39.battprobe.BuildConfig
import io.github.koki_dayo39.battprobe.IShellService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    UNSUPPORTED_VERSION,
    READY,
}

/**
 * Tracks the Shizuku binder / permission lifecycle and keeps a bound
 * [IShellService] (our user service running with Shizuku's privileges).
 */
object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val REQUEST_CODE = 0xB477
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_URL = "https://shizuku.rikka.app/"

    private lateinit var appContext: Context
    private var initialized = false
    private var binding = false

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _service = MutableStateFlow<IShellService?>(null)
    val service: StateFlow<IShellService?> = _service.asStateFlow()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder != null && binder.pingBinder()) {
                _service.value = IShellService.Stub.asInterface(binder)
                AppLog.i(TAG, "Shell service connected")
            } else {
                AppLog.w(TAG, "Shell service binder invalid")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            _service.value = null
            AppLog.i(TAG, "Shell service disconnected")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _service.value = null
        binding = false
        refresh()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == REQUEST_CODE) refresh()
        }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun refresh() {
        if (!initialized) return
        val newState = computeState()
        if (newState != _state.value) AppLog.i(TAG, "state ${_state.value} -> $newState (shizuku v${runCatching { Shizuku.getVersion() }.getOrDefault(-1)})")
        _state.value = newState
        if (newState == ShizukuState.READY) {
            bindService()
        } else {
            _service.value = null
        }
    }

    private fun computeState(): ShizukuState {
        if (!Shizuku.pingBinder()) {
            return if (isInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        if (Shizuku.isPreV11()) return ShizukuState.UNSUPPORTED_VERSION
        return try {
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.PERMISSION_DENIED
                else -> ShizukuState.PERMISSION_REQUIRED
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "checkSelfPermission failed", e)
            ShizukuState.NOT_RUNNING
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            AppLog.w(TAG, "requestPermission failed", e)
        }
    }

    private fun bindService() {
        if (_service.value != null || binding) return
        binding = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            binding = false
            AppLog.w(TAG, "bindUserService failed", e)
        }
    }

    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (e: Exception) {
            AppLog.w(TAG, "unbindUserService failed", e)
        }
        _service.value = null
        binding = false
    }
}
