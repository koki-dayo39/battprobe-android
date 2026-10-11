package com.kokidayo.batterystatus.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.kokidayo.batterystatus.background.ChargingWork

class ChargeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ChargingWork.refreshWidgetNow(context)
    }

    override fun onEnabled(context: Context) {
        ChargingWork.refreshWidgetNow(context)
    }

    override fun onDisabled(context: Context) {
        ChargingWork.cancelWidgetLoop(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) ChargingWork.refreshWidgetNow(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.kokidayo.batterystatus.widget.REFRESH"
    }
}
