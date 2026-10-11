package com.kokidayo.batterystatus.background

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kokidayo.batterystatus.data.BatteryRepository
import com.kokidayo.batterystatus.data.CurrentNormalizer
import com.kokidayo.batterystatus.data.SettingsStore
import com.kokidayo.batterystatus.util.AppLog

/** Manifest receiver: POWER_CONNECTED / DISCONNECTED are still delivered to background apps. */
class ChargerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                val store = SettingsStore.get(context)
                val enabled = store.settings.value.notifyOnConnect
                val canPost = ChargeNotification.canPost(context)
                AppLog.i(TAG, "power connected (notify=$enabled, permission=$canPost)")
                if (enabled && canPost) {
                    // Post right away with what BatteryManager knows; the job refines it with shell data.
                    runCatching {
                        val local = BatteryRepository(context).readLocal()
                        val snapshot = CurrentNormalizer.snapshot(local, null, store.settings.value, store.learnedPolarity)
                        context.getSystemService(NotificationManager::class.java)
                            .notify(ChargeNotification.ID, ChargeNotification.build(context, snapshot, 30))
                    }.onFailure { AppLog.w(TAG, "immediate notification failed", it) }
                    ChargingWork.startConnectNotification(context)
                } else {
                    ChargingWork.refreshWidgetNow(context)
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                AppLog.i(TAG, "power disconnected")
                ChargingWork.cancelConnectNotification(context)
                ChargingWork.refreshWidgetNow(context)
            }
            ACTION_CANCEL_NOTIFICATION -> {
                context.getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
            }
        }
    }

    companion object {
        private const val TAG = "ChargerReceiver"
        const val ACTION_CANCEL_NOTIFICATION = "com.kokidayo.batterystatus.CANCEL_NOTIFICATION"
    }
}
