package io.github.koki_dayo39.batterystatus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.koki_dayo39.batterystatus.MainActivity
import io.github.koki_dayo39.batterystatus.R
import io.github.koki_dayo39.batterystatus.data.BatterySnapshot
import io.github.koki_dayo39.batterystatus.data.chargerInfo
import io.github.koki_dayo39.batterystatus.data.maxChargingCurrentA
import io.github.koki_dayo39.batterystatus.data.maxChargingPowerW
import io.github.koki_dayo39.batterystatus.data.maxChargingVoltageV
import io.github.koki_dayo39.batterystatus.ui.fmt
import io.github.koki_dayo39.batterystatus.ui.fmtSigned
import io.github.koki_dayo39.batterystatus.ui.pluggedLabelPlain
import io.github.koki_dayo39.batterystatus.ui.statusLabelPlain
import java.text.DateFormat
import java.util.Date

/** Renders the charge-negotiation widget from a snapshot, in a layout that fits its size. */
object ChargeWidget {

    enum class Size(val layout: Int, val minWidthDp: Float, val minHeightDp: Float) {
        SMALL(R.layout.widget_charge_small, 40f, 40f),
        MEDIUM(R.layout.widget_charge, 140f, 100f),
        LARGE(R.layout.widget_charge_large, 250f, 180f),
    }

    private var lastSnapshot: BatterySnapshot? = null
    private var lastContentKey: String? = null

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ChargeWidgetProvider::class.java))

    fun hasWidgets(context: Context): Boolean = widgetIds(context).isNotEmpty()

    /** Pushes a snapshot to every widget; identical content is skipped unless [force]. */
    @Synchronized
    fun render(context: Context, snapshot: BatterySnapshot, force: Boolean = false) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        lastSnapshot = snapshot
        val content = Content.from(context, snapshot)
        if (!force && content.key == lastContentKey) return
        lastContentKey = content.key
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id -> manager.updateAppWidget(id, buildFor(context, manager.getAppWidgetOptions(id), content)) }
    }

    /** Re-renders one widget with the last snapshot (after a resize), or returns false if none yet. */
    @Synchronized
    fun rerender(context: Context, appWidgetId: Int, options: Bundle): Boolean {
        val snapshot = lastSnapshot ?: return false
        val content = Content.from(context, snapshot)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, buildFor(context, options, content))
        return true
    }

    private fun buildFor(context: Context, options: Bundle, content: Content): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            // The system picks the largest entry that fits the widget's current size.
            val map = Size.entries.associate { SizeF(it.minWidthDp, it.minHeightDp) to build(context, it, content) }
            return RemoteViews(map)
        }
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val size = Size.entries.lastOrNull { minW >= it.minWidthDp && minH >= it.minHeightDp } ?: Size.SMALL
        return build(context, size, content)
    }

    /** The strings shown in the widget, so identical snapshots can be skipped. */
    private class Content(
        val headline: String,
        val compact: String,
        val sub: String,
        val battery: String,
        val details: String,
        val updated: String,
    ) {
        val key: String get() = "$headline|$sub|$battery|$details"

        companion object {
            fun from(context: Context, snapshot: BatterySnapshot): Content {
                val local = snapshot.local
                val plugged = local.plugged != 0
                val maxV = snapshot.maxChargingVoltageV()
                val maxA = snapshot.maxChargingCurrentA()
                val maxW = snapshot.maxChargingPowerW()
                val charger = snapshot.chargerInfo()

                val headline: String
                val compact: String
                val sub: String
                when {
                    !plugged -> {
                        headline = context.getString(R.string.widget_unplugged)
                        compact = snapshot.powerW?.let { "${it.fmtSigned(1)} W" } ?: headline
                        sub = snapshot.currentMa?.let { "${it.fmtSigned(0)} mA" } ?: ""
                    }
                    maxV != null && maxA != null -> {
                        headline = "${maxV.fmt(2)} V × ${maxA.fmt(2)} A"
                        compact = "${maxV.fmt(1)}V×${maxA.fmt(1)}A"
                        sub = buildString {
                            maxW?.let { append("${it.fmt(1)} W ").append(context.getString(R.string.widget_negotiated)) }
                            charger?.chargerType?.let { append(" · ").append(it) }
                            charger?.powerW?.let { append(" · ").append(context.getString(R.string.widget_actual, it.fmt(1))) }
                        }
                    }
                    snapshot.shell == null -> {
                        headline = pluggedLabelPlain(context, local.plugged)
                        compact = snapshot.powerW?.let { "${it.fmtSigned(1)} W" } ?: headline
                        sub = context.getString(R.string.widget_needs_privilege)
                    }
                    else -> {
                        headline = pluggedLabelPlain(context, local.plugged)
                        compact = snapshot.powerW?.let { "${it.fmtSigned(1)} W" } ?: headline
                        sub = charger?.powerW?.let { context.getString(R.string.widget_actual, it.fmt(1)) } ?: ""
                    }
                }
                val battery = buildString {
                    append(local.percent.toInt()).append("%")
                    snapshot.currentMa?.let { append(" · ").append(it.fmtSigned(0)).append(" mA") }
                    snapshot.powerW?.let { append(" · ").append(it.fmtSigned(2)).append(" W") }
                }
                val details = buildList {
                    add("${statusLabelPlain(context, local.status)} · ${snapshot.voltageV.fmt(3)} V · ${local.temperatureC.toDouble().fmt(1)} °C")
                    if (plugged && charger != null) {
                        val line = buildString {
                            charger.voltageV?.let { append(it.fmt(2)).append(" V") }
                            charger.currentMa?.let { if (isNotEmpty()) append(" × "); append(it.fmt(0)).append(" mA") }
                            charger.chargePhase?.let { if (isNotEmpty()) append(" · "); append(it) }
                            charger.pdCapabilities?.let { if (isNotEmpty()) append(" · "); append(it) }
                        }
                        if (line.isNotBlank()) add(line)
                    }
                    local.chargeTimeRemainingMs?.takeIf { plugged && it >= 60_000 }?.let {
                        add(context.getString(R.string.label_time_to_full) + ": " + io.github.koki_dayo39.batterystatus.ui.formatDuration(it))
                    }
                }.joinToString("\n")
                val updated = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.timestamp))
                return Content(headline, compact, sub, battery, details, updated)
            }
        }
    }

    private fun build(context: Context, size: Size, content: Content): RemoteViews {
        val views = RemoteViews(context.packageName, size.layout)
        views.setTextViewText(R.id.widget_headline, if (size == Size.SMALL) content.compact else content.headline)
        views.setTextViewText(R.id.widget_battery, content.battery)
        if (size != Size.SMALL) {
            views.setTextViewText(R.id.widget_sub, content.sub)
            views.setViewVisibility(R.id.widget_sub, if (content.sub.isBlank()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_updated, content.updated)
        }
        if (size == Size.LARGE) {
            views.setTextViewText(R.id.widget_details, content.details)
        }

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
        if (size != Size.SMALL) views.setOnClickPendingIntent(R.id.widget_open, open)
        return views
    }
}
