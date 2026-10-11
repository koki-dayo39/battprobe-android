package io.github.koki_dayo39.battprobe.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.koki_dayo39.battprobe.background.ChargingWork

class ChargeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ChargingWork.refreshWidgetNow(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Resized: redraw immediately with the last data in the layout for the new size.
        if (!ChargeWidget.rerender(context, appWidgetId, newOptions)) ChargingWork.refreshWidgetNow(context)
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
        const val ACTION_REFRESH = "io.github.koki_dayo39.battprobe.widget.REFRESH"
    }
}
