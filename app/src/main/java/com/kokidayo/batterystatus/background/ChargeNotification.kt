package com.kokidayo.batterystatus.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kokidayo.batterystatus.MainActivity
import com.kokidayo.batterystatus.R
import com.kokidayo.batterystatus.data.BatterySnapshot
import com.kokidayo.batterystatus.data.NotifyMode
import com.kokidayo.batterystatus.data.chargerInfo
import com.kokidayo.batterystatus.data.maxChargingCurrentA
import com.kokidayo.batterystatus.data.maxChargingPowerW
import com.kokidayo.batterystatus.data.maxChargingVoltageV
import com.kokidayo.batterystatus.ui.fmt
import com.kokidayo.batterystatus.ui.fmtSigned

object ChargeNotification {
    const val CHANNEL_ID = "charging_info"
    const val ID = 1001

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_charging),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_charging_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, snapshot: BatterySnapshot?, secondsLeft: Int, mode: NotifyMode = NotifyMode.CONNECT_30S): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text, big) = content(context, snapshot)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setSubText(
                if (mode == NotifyMode.WHILE_CHARGING) context.getString(R.string.notification_while_charging)
                else context.getString(R.string.notification_seconds_left, secondsLeft)
            )
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun content(context: Context, snapshot: BatterySnapshot?): Triple<String, String, String> {
        if (snapshot == null) {
            val t = context.getString(R.string.notification_connected)
            return Triple(t, context.getString(R.string.waiting_for_data), context.getString(R.string.waiting_for_data))
        }
        val maxV = snapshot.maxChargingVoltageV()
        val maxA = snapshot.maxChargingCurrentA()
        val maxW = snapshot.maxChargingPowerW()
        val charger = snapshot.chargerInfo()
        val title = if (maxV != null && maxA != null) {
            "⚡ ${maxV.fmt(2)} V × ${maxA.fmt(2)} A" + (maxW?.let { " = ${it.fmt(1)} W" } ?: "")
        } else {
            context.getString(R.string.notification_connected)
        }
        val batteryLine = buildString {
            append(context.getString(R.string.notification_battery_line, snapshot.local.percent.toInt()))
            snapshot.currentMa?.let { append(" ").append(it.fmtSigned(0)).append(" mA") }
            append(" / ").append(snapshot.voltageV.fmt(2)).append(" V")
            snapshot.powerW?.let { append(" = ").append(it.fmtSigned(2)).append(" W") }
        }
        val chargerLine = buildString {
            charger?.chargerType?.let { append(it) }
            charger?.chargePhase?.let { if (isNotEmpty()) append(" · "); append(it) }
            charger?.powerW?.let { if (isNotEmpty()) append(" · "); append(context.getString(R.string.widget_actual, it.fmt(1))) }
            charger?.pdCapabilities?.let { if (isNotEmpty()) append(" · "); append(it) }
        }
        val text = if (snapshot.shell == null) context.getString(R.string.widget_needs_privilege) else batteryLine
        val big = listOf(batteryLine, chargerLine, if (snapshot.shell == null) context.getString(R.string.widget_needs_privilege) else "")
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return Triple(title, text, big)
    }
}
