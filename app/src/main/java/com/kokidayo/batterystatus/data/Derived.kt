package com.kokidayo.batterystatus.data

import android.os.BatteryManager
import kotlin.math.abs

/** Parses the leading number out of strings like "4384 mAh" or "4000000". */
fun String?.leadingNumber(): Double? {
    if (this == null) return null
    val match = NUMBER_REGEX.find(this.trim()) ?: return null
    return match.value.toDoubleOrNull()
}

private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")

/** sysfs capacities are usually µAh; some vendors use mAh. Normalise to mAh. */
fun Double.capacityToMah(): Double = if (abs(this) >= 100_000) this / 1000.0 else this

/** sysfs currents are usually µA; some vendors use mA. Normalise to mA. */
fun Double.currentToMa(): Double = if (abs(this) >= 20_000) this / 1000.0 else this

/** sysfs voltages are usually µV; some vendors use mV. Normalise to V. */
fun Double.voltageToV(): Double = when {
    abs(this) >= 100_000 -> this / 1_000_000.0
    abs(this) >= 100 -> this / 1000.0
    else -> this
}

val ShellData.batterySupply: Map<String, String>?
    get() = powerSupplies["battery"]
        ?: powerSupplies.entries.firstOrNull { it.value["type"].equals("Battery", true) }?.value

val ShellData.usbSupply: Map<String, String>?
    get() = powerSupplies["usb"]
        ?: powerSupplies.entries.firstOrNull { it.value["type"].equals("USB", true) }?.value

val ShellData.wirelessSupply: Map<String, String>?
    get() = powerSupplies["wireless"]
        ?: powerSupplies["dc"]
        ?: powerSupplies.entries.firstOrNull { it.value["type"].equals("Wireless", true) }?.value

val ShellData.acSupply: Map<String, String>?
    get() = powerSupplies["ac"]
        ?: powerSupplies.entries.firstOrNull { it.value["type"].equals("Mains", true) }?.value

/** The supply currently feeding the device, if we can tell. */
fun ShellData.activeChargerSupply(plugged: Int): Map<String, String>? {
    val preferred = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> wirelessSupply
        BatteryManager.BATTERY_PLUGGED_AC -> acSupply ?: usbSupply
        else -> usbSupply
    }
    if (preferred != null && preferred["online"]?.trim() != "0") return preferred
    return listOfNotNull(usbSupply, acSupply, wirelessSupply)
        .firstOrNull { it["online"]?.trim() == "1" } ?: preferred
}

/** Values like "[SDP] CDP DCP PD" -> "SDP". */
fun String?.selectedOption(): String? {
    if (this == null) return null
    val m = Regex("\\[([^\\]]+)]").find(this)
    return (m?.groupValues?.get(1) ?: this).trim().ifEmpty { null }
}

fun BatterySnapshot.designCapacityMah(): Double? {
    val b = shell?.batterySupply
    b?.get("charge_full_design").leadingNumber()?.let { return it.capacityToMah() }
    b?.get("energy_full_design").leadingNumber()?.let { wh ->
        // µWh -> mAh using nominal voltage of 3.85 V
        return (wh / 1000.0) / 3.85
    }
    return null
}

fun BatterySnapshot.fullCapacityMah(): Double? {
    val b = shell?.batterySupply
    b?.get("charge_full").leadingNumber()?.let { return it.capacityToMah() }
    b?.get("energy_full").leadingNumber()?.let { wh -> return (wh / 1000.0) / 3.85 }
    shell?.batteryStats?.get("Last learned battery capacity").leadingNumber()?.let { return it }
    return null
}

fun BatterySnapshot.estimatedCapacityMah(): Double? =
    shell?.batteryStats?.get("Estimated battery capacity").leadingNumber()

fun BatterySnapshot.capacityHealthPercent(): Double? {
    val design = designCapacityMah() ?: return null
    val full = fullCapacityMah() ?: return null
    if (design <= 0) return null
    return full / design * 100.0
}

fun BatterySnapshot.cycleCount(): Int? {
    local.cycleCount?.let { return it }
    val b = shell?.batterySupply ?: return null
    return listOf("cycle_count", "battery_cycle", "batt_cycle", "fg_cycle")
        .firstNotNullOfOrNull { b[it].leadingNumber()?.toInt() }
        ?.takeIf { it >= 0 }
}

fun BatterySnapshot.maxChargingCurrentA(): Double? =
    shell?.dumpsysBattery?.get("Max charging current").leadingNumber()
        ?.takeIf { it > 0 }?.let { it / 1_000_000.0 }

fun BatterySnapshot.maxChargingVoltageV(): Double? =
    shell?.dumpsysBattery?.get("Max charging voltage").leadingNumber()
        ?.takeIf { it > 0 }?.let { it / 1_000_000.0 }

fun BatterySnapshot.maxChargingPowerW(): Double? {
    val a = maxChargingCurrentA() ?: return null
    val v = maxChargingVoltageV() ?: return null
    return a * v
}

data class ChargerInfo(
    val voltageV: Double?,
    val currentMa: Double?,
    val powerW: Double?,
    val inputCurrentLimitMa: Double?,
    val chargerType: String?,
    val typecMode: String?,
    val chargePhase: String?,
)

fun BatterySnapshot.chargerInfo(): ChargerInfo? {
    val shell = shell ?: return null
    val charger = shell.activeChargerSupply(local.plugged)
    val battery = shell.batterySupply

    val voltage = charger?.get("voltage_now").leadingNumber()?.takeIf { it > 0 }?.voltageToV()
    val current = charger?.get("current_now").leadingNumber()?.let { abs(it).currentToMa() }
        ?.takeIf { it > 0 }
    val power = if (voltage != null && current != null) voltage * current / 1000.0 else null
    val inputLimit = (charger?.get("input_current_limit") ?: charger?.get("current_max"))
        .leadingNumber()?.takeIf { it > 0 }?.currentToMa()

    val chargerType = listOf("real_type", "charger_type", "usb_type", "type")
        .firstNotNullOfOrNull { key -> charger?.get(key).selectedOption() }
        ?: battery?.get("batt_charging_source")
    val typecMode = charger?.get("typec_mode").selectedOption()
        ?: charger?.get("typec_cc_orientation")?.let { "CC$it" }
    val chargePhase = battery?.get("charge_type").selectedOption()
        ?.takeIf { !it.equals("N/A", true) && !it.equals("Unknown", true) }

    val allNull = listOf(voltage, current, inputLimit, chargerType, typecMode, chargePhase).all { it == null }
    if (allNull && charger == null) return null
    return ChargerInfo(voltage, current, power, inputLimit, chargerType, typecMode, chargePhase)
}

fun BatterySnapshot.sysfsTemperatureC(): Double? =
    shell?.batterySupply?.get("temp").leadingNumber()?.let { it / 10.0 }
