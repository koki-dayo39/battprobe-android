package com.kokidayo.batterystatus.background

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.kokidayo.batterystatus.data.BatteryRepository
import com.kokidayo.batterystatus.data.CurrentNormalizer
import com.kokidayo.batterystatus.data.NotifyMode
import com.kokidayo.batterystatus.data.SettingsStore
import com.kokidayo.batterystatus.util.AppLog

/**
 * Plug / unplug handling shared by every detection path.
 *
 * ACTION_POWER_CONNECTED is not on the implicit-broadcast exemption list, so a
 * manifest receiver never gets it on Android 8+. We therefore detect plug-ins by:
 *  - a runtime-registered receiver while our process is alive (instant),
 *  - the UI loop while the app is open (instant),
 *  - a JobScheduler job with the charging constraint (system-driven, may lag until
 *    the battery level has risen by 1%), which also re-arms itself.
 */
object ChargerEvents {
    private const val TAG = "ChargerEvents"
    private var runtimeReceiver: BroadcastReceiver? = null

    fun registerRuntimeReceiver(context: Context) {
        if (runtimeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> onPluggedChanged(ctx, true, "runtime-receiver")
                    Intent.ACTION_POWER_DISCONNECTED -> onPluggedChanged(ctx, false, "runtime-receiver")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.applicationContext.registerReceiver(receiver, filter)
        runtimeReceiver = receiver
    }

    /** Any code that observes "unplugged" clears the session flag so the next plug-in notifies. */
    fun syncUnplugged(context: Context) {
        val store = SettingsStore.get(context)
        if (store.notifiedThisSession) store.notifiedThisSession = false
    }

    @Synchronized
    fun onPluggedChanged(context: Context, plugged: Boolean, source: String) {
        val store = SettingsStore.get(context)
        val mode = store.settings.value.notifyMode
        if (plugged) {
            // Called on a real plug-in edge, so this is a new session even if a stale flag says otherwise.
            val canPost = ChargeNotification.canPost(context)
            AppLog.i(TAG, "power connected via $source (mode=$mode, permission=$canPost)")
            if (mode == NotifyMode.OFF || !canPost) {
                ChargingWork.refreshWidgetNow(context)
                return
            }
            store.notifiedThisSession = true
            // Post right away with what BatteryManager knows; the job refines it with shell data.
            runCatching {
                val local = BatteryRepository(context).readLocal()
                val snapshot = CurrentNormalizer.snapshot(local, null, store.settings.value, store.learnedPolarity)
                context.getSystemService(NotificationManager::class.java)
                    .notify(ChargeNotification.ID, ChargeNotification.build(context, snapshot, 30, mode))
            }.onFailure { AppLog.w(TAG, "immediate notification failed", it) }
            ChargingWork.startConnectNotification(context)
        } else {
            AppLog.i(TAG, "power disconnected via $source")
            store.notifiedThisSession = false
            ChargingWork.cancelConnectNotification(context)
            ChargingWork.refreshWidgetNow(context)
            ChargingWork.armChargingWatch(context)
        }
    }
}
