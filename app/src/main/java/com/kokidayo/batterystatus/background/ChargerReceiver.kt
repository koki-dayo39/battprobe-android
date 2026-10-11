package com.kokidayo.batterystatus.background

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest receiver. POWER_CONNECTED/DISCONNECTED are kept for ROMs that still
 * deliver them to manifest receivers; stock Android does not. BOOT_COMPLETED and
 * our own cancel alarm are what this receiver is really for.
 */
class ChargerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> ChargerEvents.onPluggedChanged(context, true, "manifest-receiver")
            Intent.ACTION_POWER_DISCONNECTED -> ChargerEvents.onPluggedChanged(context, false, "manifest-receiver")
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ChargingWork.armChargingWatch(context)
                ChargingWork.refreshWidgetNow(context)
            }
            ACTION_CANCEL_NOTIFICATION ->
                context.getSystemService(NotificationManager::class.java).cancel(ChargeNotification.ID)
        }
    }

    companion object {
        const val ACTION_CANCEL_NOTIFICATION = "com.kokidayo.batterystatus.CANCEL_NOTIFICATION"
    }
}
