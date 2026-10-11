package io.github.koki_dayo39.batterystatus.data

object CurrentNormalizer {
    /** Raw BatteryManager reading -> mA in the device's own sign convention. */
    fun toDeviceMa(rawUa: Int?, settings: Settings): Double? {
        rawUa ?: return null
        return when (settings.currentUnit) {
            CurrentUnit.MICRO_AMP -> rawUa / 1000.0
            CurrentUnit.MILLI_AMP -> rawUa.toDouble()
        }
    }

    /** Applies the sign mode so that positive = charging. */
    fun applySign(deviceMa: Double, settings: Settings, learnedPolarity: Int): Double =
        when (settings.signMode) {
            SignMode.AS_REPORTED -> deviceMa
            SignMode.INVERTED -> -deviceMa
            SignMode.AUTO -> deviceMa * learnedPolarity
        }

    /** Live sysfs voltage when available, else the sticky-broadcast value. */
    fun voltageV(local: LocalBattery, shell: ShellData?): Double =
        shell?.batterySupply?.get("voltage_now").leadingNumber()
            ?.voltageToV()?.takeIf { it in 2.0..12.0 }
            ?: (local.voltageMv / 1000.0)

    fun snapshot(local: LocalBattery, shell: ShellData?, settings: Settings, polarity: Int): BatterySnapshot {
        val currentMa = toDeviceMa(local.currentNowUa, settings)?.let { applySign(it, settings, polarity) }
        val currentAvgMa = toDeviceMa(local.currentAvgUa, settings)?.let { applySign(it, settings, polarity) }
        val voltageV = voltageV(local, shell)
        return BatterySnapshot(
            timestamp = System.currentTimeMillis(),
            local = local,
            shell = shell,
            currentMa = currentMa,
            currentAvgMa = currentAvgMa,
            voltageV = voltageV,
            powerW = currentMa?.let { it / 1000.0 * voltageV },
        )
    }
}
