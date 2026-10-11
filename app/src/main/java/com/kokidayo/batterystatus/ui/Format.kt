package com.kokidayo.batterystatus.ui

import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kokidayo.batterystatus.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun Double.fmt(digits: Int = 2): String = String.format(Locale.US, "%.${digits}f", this)

fun Double.fmtSigned(digits: Int = 2): String = (if (this > 0) "+" else "") + fmt(digits)

fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

fun formatTime(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(epochMs))

fun formatDate(epochSeconds: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

@Composable
fun statusLabel(status: Int): String = stringResource(
    when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> R.string.status_charging
        BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.status_discharging
        BatteryManager.BATTERY_STATUS_FULL -> R.string.status_full
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.status_not_charging
        else -> R.string.status_unknown
    }
)

@Composable
fun pluggedLabel(plugged: Int): String = stringResource(
    when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> R.string.plugged_ac
        BatteryManager.BATTERY_PLUGGED_USB -> R.string.plugged_usb
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.plugged_wireless
        8 /* BATTERY_PLUGGED_DOCK, API 33 */ -> R.string.plugged_dock
        0 -> R.string.plugged_none
        else -> R.string.status_unknown
    }
)

@Composable
fun healthLabel(health: Int): String = stringResource(
    when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> R.string.health_good
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.health_overheat
        BatteryManager.BATTERY_HEALTH_DEAD -> R.string.health_dead
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.health_over_voltage
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.health_failure
        BatteryManager.BATTERY_HEALTH_COLD -> R.string.health_cold
        else -> R.string.health_unknown
    }
)

fun statusLabelPlain(context: android.content.Context, status: Int): String = context.getString(
    when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> R.string.status_charging
        BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.status_discharging
        BatteryManager.BATTERY_STATUS_FULL -> R.string.status_full
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.status_not_charging
        else -> R.string.status_unknown
    }
)

fun pluggedLabelPlain(context: android.content.Context, plugged: Int): String = context.getString(
    when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> R.string.plugged_ac
        BatteryManager.BATTERY_PLUGGED_USB -> R.string.plugged_usb
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.plugged_wireless
        8 -> R.string.plugged_dock
        0 -> R.string.plugged_none
        else -> R.string.status_unknown
    }
)

fun chargingStatusLabel(status: Int?): String? = when (status) {
    null -> null
    1 -> "Normal"
    2 -> "Too cold"
    3 -> "Too hot"
    4 -> "Long life"
    5 -> "Adaptive"
    else -> "Unknown ($status)"
}
