package com.kokidayo.batterystatus.data

/** Values from Google's google_battery driver and the Maxim fuel gauge (Pixel phones). */
data class PixelInfo(
    /** AAFV: float-voltage reduction currently applied, in mV (positive = lowered). */
    val aafvOffsetMv: Double?,
    val aafvEnabled: Boolean?,
    /** "≤200: 0 mV · ≤400: −20 mV …" */
    val aafvProfile: String?,
    /** AACP opt-out flag: false = battery health assistance active. */
    val healthAssistanceOptedOut: Boolean?,
    val aacrEnabled: Boolean?,
    val aacrCycleGrace: Int?,
    val aacrCycleMax: Int?,
    val defenderEnabled: Boolean?,
    val defenderRechargeSoc: Int?,
    val adaptiveTargetSoc: Int?,
    val chargeLimit: Int?,
    val chargeDeadlineSec: Long?,
    val chargeStage: String?,
    val chargingSpeed: Int?,
    val shownSocPct: Int?,
    val gaugeSocPct: Double?,
    val gaugeFullCapMah: Double?,
    val gaugeImpedanceMilliOhm: Double?,
    val avgCurrentMa: Double?,
    val avgPowerMw: Double?,
    val swellingChargeSec: Long?,
    val swellingDischargeSec: Long?,
    /** Ten buckets, 0–10 % … 90–100 %. */
    val cycleHistogram: List<Int>?,
) {
    val isEmpty: Boolean
        get() = listOf(
            aafvOffsetMv, aafvProfile, healthAssistanceOptedOut, aacrEnabled, defenderEnabled,
            adaptiveTargetSoc, gaugeSocPct, gaugeFullCapMah, cycleHistogram, swellingChargeSec,
        ).all { it == null }
}

fun BatterySnapshot.pixelInfo(): PixelInfo? {
    val shell = shell ?: return null
    val b = shell.batterySupply ?: return null
    val fg = shell.powerSupplies["maxfg"]
    // Only Pixel's driver exposes these; bail out early on other devices.
    if (b["aafv_state"] == null && b["aacr_state"] == null && b["ssoc_details"] == null) return null

    fun flag(key: String): Boolean? = b[key].leadingNumber()?.let { it != 0.0 }
    fun int(key: String): Int? = b[key].leadingNumber()?.toInt()

    val ssoc = b["ssoc_details"]
    val shownSoc = ssoc?.let { Regex("l=(\\d+)%").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val gaugeSoc = ssoc?.let { Regex("gdf=([\\d.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

    val profile = b["aafv_profile"]?.let { raw ->
        Regex("<(\\d+)>:<(\\d+)>").findAll(raw).map { m ->
            val cycles = m.groupValues[1]
            val mv = m.groupValues[2]
            if (cycles == "1001") ">1000: −$mv mV" else "≤$cycles: " + (if (mv == "0") "0 mV" else "−$mv mV")
        }.joinToString(" · ").ifEmpty { null }
    }

    val swelling = b["swelling_data"]?.let { raw ->
        val tokens = raw.split(Regex("\\s+"))
        val idx = tokens.indexOf("30/90")
        if (idx >= 0 && idx + 2 < tokens.size) tokens[idx + 1].toLongOrNull() to tokens[idx + 2].toLongOrNull() else null
    }

    val histogram = b["cycle_counts"]?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.size == 10 }

    val impedance = (fg?.get("act_impedance") ?: fg?.get("resistance")).leadingNumber()
        ?.takeIf { it > 0 }?.let { if (it >= 10_000) it / 1000.0 else it }

    return PixelInfo(
        aafvOffsetMv = b["aafv_offset"].leadingNumber()?.let { if (it >= 1000) it / 1000.0 else it },
        aafvEnabled = flag("aafv_state"),
        aafvProfile = profile,
        healthAssistanceOptedOut = flag("aacp_opt_out"),
        aacrEnabled = flag("aacr_state"),
        aacrCycleGrace = int("aacr_cycle_grace"),
        aacrCycleMax = int("aacr_cycle_max"),
        defenderEnabled = flag("bd_trickle_enable"),
        defenderRechargeSoc = int("bd_trickle_recharge_soc"),
        adaptiveTargetSoc = int("ac_soc"),
        chargeLimit = int("charge_limit")?.takeIf { it >= 0 },
        chargeDeadlineSec = b["charge_deadline"].leadingNumber()?.toLong()?.takeIf { it > 0 },
        chargeStage = b["charge_stage"]?.takeIf { it.isNotBlank() },
        chargingSpeed = int("charging_speed"),
        shownSocPct = shownSoc,
        gaugeSocPct = gaugeSoc,
        gaugeFullCapMah = fg?.get("full_cap_rep").leadingNumber()?.capacityToMah(),
        gaugeImpedanceMilliOhm = impedance,
        avgCurrentMa = b["power_metrics_current"].leadingNumber(),
        avgPowerMw = b["power_metrics_power"].leadingNumber(),
        swellingChargeSec = swelling?.first,
        swellingDischargeSec = swelling?.second,
        cycleHistogram = histogram,
    ).takeIf { !it.isEmpty }
}
