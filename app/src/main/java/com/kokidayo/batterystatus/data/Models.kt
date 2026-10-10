package com.kokidayo.batterystatus.data

/** Values readable without any special permission (BatteryManager + sticky intent). */
data class LocalBattery(
    val level: Int,
    val scale: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val voltageMv: Int,
    val temperatureTenths: Int,
    val technology: String?,
    val present: Boolean,
    val currentNowUa: Int?,
    val currentAvgUa: Int?,
    val chargeCounterUah: Int?,
    val energyCounterNwh: Long?,
    val capacityPct: Int?,
    val chargeTimeRemainingMs: Long?,
    val cycleCount: Int?,
    val chargingStatus: Int?,
) {
    val percent: Float
        get() = if (scale > 0) level * 100f / scale else level.toFloat()

    val temperatureC: Float
        get() = temperatureTenths / 10f
}

/** Values collected through the Shizuku shell service. */
data class ShellData(
    val dumpsysBattery: Map<String, String>,
    val dumpsysRaw: String,
    /** power_supply name -> (sysfs file -> value) */
    val powerSupplies: Map<String, Map<String, String>>,
    val sysfsRaw: String,
    val batteryStats: Map<String, String>,
    val batteryStatsRaw: String,
)

data class BatterySnapshot(
    val timestamp: Long,
    val local: LocalBattery,
    val shell: ShellData?,
    /** Normalised battery current in mA: positive = charging, negative = discharging. */
    val currentMa: Double?,
    val currentAvgMa: Double?,
    val voltageV: Double,
    /** Battery-side power in W (sign follows [currentMa]). */
    val powerW: Double?,
)
