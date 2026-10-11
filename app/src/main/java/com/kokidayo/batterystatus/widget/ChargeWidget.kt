package com.kokidayo.batterystatus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.view.View
import android.widget.RemoteViews
import com.kokidayo.batterystatus.MainActivity
import com.kokidayo.batterystatus.R
import com.kokidayo.batterystatus.data.BatterySnapshot
import com.kokidayo.batterystatus.data.chargerInfo
import com.kokidayo.batterystatus.data.maxChargingCurrentA
import com.kokidayo.batterystatus.data.maxChargingPowerW
import com.kokidayo.batterystatus.data.maxChargingVoltageV
import com.kokidayo.batterystatus.ui.fmt
import com.kokidayo.batterystatus.ui.fmtSigned
import java.text.DateFormat
import java.util.Date

/** Renders the charge-negotiation widget from a snapshot. */
object ChargeWidget {

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ChargeWidgetProvider::class.java))

    fun hasWidgets(context: Context): Boolean = widgetIds(context).isNotEmpty()

    fun render(context: Context, snapshot: BatterySnapshot) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val views = build(context, snapshot)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    fun build(context: Context, snapshot: BatterySnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_charge)
        val local = snapshot.local
        val plugged = local.plugged != 0
        val maxV = snapshot.maxChargingVoltageV()
        val maxA = snapshot.maxChargingCurrentA()
        val maxW = snapshot.maxChargingPowerW()
        val charger = snapshot.chargerInfo()

        val headline: String
        val sub: String
        when {
            !plugged -> {
                headline = context.getString(R.string.widget_unplugged)
                sub = snapshot.currentMa?.let { "${it.fmtSigned(0)} mA" } ?: ""
            }
            maxV != null && maxA != null -> {
                headline = "${maxV.fmt(2)} V × ${maxA.fmt(2)} A"
                sub = buildString {
                    maxW?.let { append("${it.fmt(1)} W ").append(context.getString(R.string.widget_negotiated)) }
                    charger?.chargerType?.let { append(" · ").append(it) }
                    charger?.powerW?.let { append(" · ").append(context.getString(R.string.widget_actual, it.fmt(1))) }
                }
            }
            snapshot.shell == null -> {
                headline = sourceLabel(context, local.plugged)
                sub = context.getString(R.string.widget_needs_privilege)
            }
            else -> {
                headline = sourceLabel(context, local.plugged)
                sub = charger?.powerW?.let { context.getString(R.string.widget_actual, it.fmt(1)) } ?: ""
            }
        }

        val battery = buildString {
            append(local.percent.toInt()).append("%")
            snapshot.currentMa?.let { append(" · ").append(it.fmtSigned(0)).append(" mA") }
            snapshot.powerW?.let { append(" · ").append(it.fmtSigned(2)).append(" W") }
        }

        views.setTextViewText(R.id.widget_headline, headline)
        views.setTextViewText(R.id.widget_sub, sub)
        views.setViewVisibility(R.id.widget_sub, if (sub.isBlank()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.widget_battery, battery)
        views.setTextViewText(
            R.id.widget_updated,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.timestamp)),
        )

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val refresh = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ChargeWidgetProvider::class.java).setAction(ChargeWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, refresh)
        views.setOnClickPendingIntent(R.id.widget_open, open)
        return views
    }

    private fun sourceLabel(context: Context, plugged: Int): String = context.getString(
        when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> R.string.plugged_ac
            BatteryManager.BATTERY_PLUGGED_USB -> R.string.plugged_usb
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.plugged_wireless
            8 -> R.string.plugged_dock
            else -> R.string.plugged_none
        }
    )
}
