package com.kokidayo.batterystatus.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kokidayo.batterystatus.data.SettingsStore
import com.kokidayo.batterystatus.util.AppLog

/** Manifest receiver: POWER_CONNECTED / DISCONNECTED are still delivered to background apps. */
class ChargerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                AppLog.i(TAG, "power connected")
                if (SettingsStore.get(context).settings.value.notifyOnConnect) {
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
        }
    }

    private companion object {
        const val TAG = "ChargerReceiver"
    }
}
