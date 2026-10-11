package com.kokidayo.batterystatus.diag

import android.content.Context
import android.os.Build
import com.kokidayo.batterystatus.BuildConfig
import com.kokidayo.batterystatus.data.Settings
import com.kokidayo.batterystatus.ui.UiState
import com.kokidayo.batterystatus.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the plain-text report behind the "share / save diagnostics" buttons. */
object DiagnosticsReport {

    fun fileName(): String =
        "battery-status-diag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    fun build(context: Context, state: UiState, settings: Settings): String = buildString {
        section("Battery Status diagnostics")
        line("generated", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        line("app", "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
            if (BuildConfig.DEBUG) " debug" else "")
        line("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.DISPLAY}")
        line("locale", context.resources.configuration.locales[0].toString())

        section("settings")
        line("intervalMs", settings.intervalMs.toString())
        line("currentUnit", settings.currentUnit.name)
        line("signMode", settings.signMode.name)

        section("privileged access")
        line("backend", state.backendLabel ?: "none")
        line("shizuku", state.shizuku.name)
        line("root", state.root.name)
        line("backendPreference", settings.backend.name)
        line("notifyOnConnect", settings.notifyOnConnect.toString())
        line("shellUid", state.shellUid?.toString() ?: "-")
        line("shellError", state.shellError ?: "-")

        val snapshot = state.snapshot
        if (snapshot == null) {
            section("snapshot")
            appendLine("(no snapshot yet)")
        } else {
            val local = snapshot.local
            section("BatteryManager / ACTION_BATTERY_CHANGED")
            line("level/scale", "${local.level}/${local.scale}")
            line("status", local.status.toString())
            line("plugged", local.plugged.toString())
            line("health", local.health.toString())
            line("voltage_mV", local.voltageMv.toString())
            line("temperature_0.1C", local.temperatureTenths.toString())
            line("technology", local.technology ?: "-")
            line("present", local.present.toString())
            line("CURRENT_NOW_raw", local.currentNowUa?.toString() ?: "-")
            line("CURRENT_AVERAGE_raw", local.currentAvgUa?.toString() ?: "-")
            line("CHARGE_COUNTER_uAh", local.chargeCounterUah?.toString() ?: "-")
            line("ENERGY_COUNTER_nWh", local.energyCounterNwh?.toString() ?: "-")
            line("CAPACITY_pct", local.capacityPct?.toString() ?: "-")
            line("chargeTimeRemaining_ms", local.chargeTimeRemainingMs?.toString() ?: "-")
            line("cycleCount", local.cycleCount?.toString() ?: "-")
            line("chargingStatus", local.chargingStatus?.toString() ?: "-")
            line("normalised_current_mA", snapshot.currentMa?.toString() ?: "-")
            line("voltage_V", snapshot.voltageV.toString())
            line("power_W", snapshot.powerW?.toString() ?: "-")

            val shell = snapshot.shell
            if (shell == null) {
                section("shell")
                appendLine("(Shizuku shell data not available)")
            } else {
                section("dumpsys battery")
                appendLine(shell.dumpsysRaw.ifBlank { "(empty)" })

                section("power_supply uevent")
                appendLine(shell.ueventRaw.ifBlank { "(empty)" })

                section("power_supply full scan (supply|file|value)")
                appendLine(shell.sysfsRaw.ifBlank { "(not scanned yet)" })

                val unknown = KnownSysfs.unknownNodes(shell)
                section("unknown sysfs nodes (${unknown.size})")
                if (unknown.isEmpty()) appendLine("(none)")
                unknown.forEach { appendLine("${it.supply}/${it.name} = ${it.value}") }

                section("dumpsys batterystats (excerpt)")
                appendLine(shell.batteryStatsRaw.ifBlank { "(not fetched yet)" })
            }
        }

        section("app log")
        val log = AppLog.snapshot()
        if (log.isEmpty()) appendLine("(empty)")
        log.forEach { appendLine(it) }
    }

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) appendLine()
        appendLine("===== $title =====")
    }

    private fun StringBuilder.line(key: String, value: String) {
        appendLine("$key: $value")
    }
}
