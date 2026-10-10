package com.kokidayo.batterystatus.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kokidayo.batterystatus.IShellService
import com.kokidayo.batterystatus.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BatteryRepository(private val context: Context) {

    private val batteryManager: BatteryManager =
        context.getSystemService(BatteryManager::class.java)

    private val collectScript: String by lazy {
        context.resources.openRawResource(R.raw.collect).bufferedReader().use { it.readText() }
    }

    fun readLocal(): LocalBattery {
        // Null receiver: just read the sticky ACTION_BATTERY_CHANGED broadcast.
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val bm = batteryManager

        fun intProp(id: Int): Int? = bm.getIntProperty(id).takeIf { it != Int.MIN_VALUE }
        fun longProp(id: Int): Long? = bm.getLongProperty(id).takeIf { it != Long.MIN_VALUE }

        val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else null
        val chargingStatus = if (Build.VERSION.SDK_INT >= 34) {
            intent?.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
        } else null
        val timeRemaining = if (Build.VERSION.SDK_INT >= 28) {
            bm.computeChargeTimeRemaining().takeIf { it >= 0 }
        } else null

        return LocalBattery(
            level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
            scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100,
            status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                ?: BatteryManager.BATTERY_STATUS_UNKNOWN,
            plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
            health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                ?: BatteryManager.BATTERY_HEALTH_UNKNOWN,
            voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            temperatureTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            present = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true,
            currentNowUa = intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAvgUa = intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterUah = intProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            energyCounterNwh = longProp(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            capacityPct = intProp(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            chargeTimeRemainingMs = timeRemaining,
            cycleCount = cycleCount,
            chargingStatus = chargingStatus,
        )
    }

    /**
     * Collects dumpsys + sysfs through the Shizuku shell service in one round trip.
     * `batterystats` is expensive, so it is only refreshed when [withStats] is set;
     * otherwise the previous stats from [previous] are carried over.
     */
    suspend fun readShell(
        service: IShellService,
        withStats: Boolean,
        previous: ShellData?,
    ): ShellData = withContext(Dispatchers.IO) {
        val script = "WITH_STATS=${if (withStats) 1 else 0}\n$collectScript"
        val output = service.exec(script)
        parseShellOutput(output, previous)
    }

    private fun parseShellOutput(output: String, previous: ShellData?): ShellData {
        val sections = splitSections(output)
        val dumpsysRaw = sections["DUMPSYS"].orEmpty().trim()
        val sysfsRaw = sections["SYSFS"].orEmpty().trim()
        val statsRaw = sections["STATS"]?.trim()

        val dumpsys = parseKeyValueLines(dumpsysRaw)
        val supplies = parseSysfs(sysfsRaw)
        val stats = statsRaw?.let { parseKeyValueLines(it) } ?: previous?.batteryStats.orEmpty()

        return ShellData(
            dumpsysBattery = dumpsys,
            dumpsysRaw = dumpsysRaw,
            powerSupplies = supplies,
            sysfsRaw = sysfsRaw,
            batteryStats = stats,
            batteryStatsRaw = statsRaw ?: previous?.batteryStatsRaw.orEmpty(),
        )
    }

    private fun splitSections(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var current: String? = null
        val buffer = StringBuilder()
        output.lineSequence().forEach { line ->
            if (line.startsWith("###")) {
                current?.let { result[it] = buffer.toString() }
                buffer.setLength(0)
                current = line.removePrefix("###").trim()
            } else {
                buffer.appendLine(line)
            }
        }
        current?.let { result[it] = buffer.toString() }
        return result
    }

    /** Parses `  key: value` lines (dumpsys style) into an ordered map. */
    private fun parseKeyValueLines(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty() && !map.containsKey(key)) map[key] = value
        }
        return map
    }

    /** Parses `supply|file|value` lines emitted by collect.sh. */
    private fun parseSysfs(text: String): Map<String, Map<String, String>> {
        val map = LinkedHashMap<String, LinkedHashMap<String, String>>()
        text.lineSequence().forEach { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size < 3) return@forEach
            val supply = parts[0].trim()
            val file = parts[1].trim()
            val value = parts[2].trim()
            if (supply.isEmpty() || file.isEmpty()) return@forEach
            map.getOrPut(supply) { LinkedHashMap() }[file] = value
        }
        return map
    }
}
