package io.github.koki_dayo39.battprobe.diag

import io.github.koki_dayo39.battprobe.data.ShellData

data class UnknownNode(val supply: String, val name: String, val value: String)

/**
 * Decides which sysfs nodes the app does not understand. Anything that is not a
 * standard Linux power_supply property and not a vendor key the app already
 * interprets counts as unknown, so users can report it and we can add support.
 */
object KnownSysfs {

    /** Standard property names from the kernel's power_supply class. */
    private val STANDARD = setOf(
        "status", "charge_type", "charge_types", "charge_behaviour", "health", "present", "online",
        "authentic", "technology", "cycle_count", "voltage_max", "voltage_min", "voltage_max_design",
        "voltage_min_design", "voltage_now", "voltage_avg", "voltage_ocv", "voltage_boot", "current_max",
        "current_now", "current_avg", "current_boot", "power_now", "power_avg", "charge_full_design",
        "charge_empty_design", "charge_full", "charge_empty", "charge_now", "charge_avg", "charge_counter",
        "constant_charge_current", "constant_charge_current_max", "constant_charge_voltage",
        "constant_charge_voltage_max", "charge_control_limit", "charge_control_limit_max",
        "charge_control_start_threshold", "charge_control_end_threshold", "input_current_limit",
        "input_voltage_limit", "input_power_limit", "energy_full_design", "energy_empty_design",
        "energy_full", "energy_empty", "energy_now", "energy_avg", "capacity", "capacity_alert_min",
        "capacity_alert_max", "capacity_error_margin", "capacity_level", "temp", "temp_max", "temp_min",
        "temp_alert_min", "temp_alert_max", "temp_ambient", "temp_ambient_alert_min",
        "temp_ambient_alert_max", "time_to_empty_now", "time_to_empty_avg", "time_to_full_now",
        "time_to_full_avg", "type", "usb_type", "scope", "precharge_current", "charge_term_current",
        "calibrate", "manufacture_year", "manufacture_month", "manufacture_day", "model_name",
        "manufacturer", "serial_number", "name", "uevent",
    )

    /** Vendor keys the app reads somewhere (see data/Derived.kt). */
    private val INTERPRETED = setOf(
        "health_index", "health_capacity_index", "health_impedance_index", "resistance", "resistance_avg",
        "manufacturing_date", "first_usage_date", "charger_type", "real_type", "typec_mode",
        "typec_cc_orientation", "battery_cycle", "batt_cycle", "fg_cycle", "batt_charging_source",
        "batt_health", "batt_temp",
    )

    /** Pixel (google_battery / Maxim fuel gauge) nodes we understand; shown on the Pixel card or known debug dumps. */
    private val PIXEL_EXACT = setOf(
        "ac_soc", "charge_limit", "charge_deadline", "charge_stage", "charge_to_limit", "charging_speed",
        "charging_state", "charging_policy", "charger_state", "chg_profile_switch", "charge_full_estimate",
        "ssoc_details", "cycle_counts", "swelling_data", "csi_stats", "charge_details", "charge_stats_actual",
        "ttf_stats", "ttf_details", "dev_sn", "pairing_state", "sr_state", "temp_filter_enable",
        "force_fcr_update_ops", "full_cap_rep", "act_impedance", "fg_learning_events", "gmsr",
        "registers_dump", "rc_switch_enable", "resistance_id",
        // bare feature names (the prefixed variants are covered by PIXEL_PREFIXES)
        "aacc", "aacr", "aafv", "aacp", "aact", "aacv",
    )
    private val PIXEL_PREFIXES = listOf(
        "aafv_", "aacr_", "aacc_", "aacp_", "aact_", "aacv_", "bd_trickle_", "health_", "capacity_level_",
        "power_metrics_", "bypass_chargelimit_",
    )

    fun isKnown(name: String): Boolean =
        name in STANDARD || name in INTERPRETED || name in PIXEL_EXACT ||
            name.startsWith("hwmon") || name.startsWith("wakeup") ||
            PIXEL_PREFIXES.any { name.startsWith(it) }

    fun unknownNodes(shell: ShellData): List<UnknownNode> =
        shell.sysfsFull.flatMap { (supply, values) ->
            values.filterKeys { !isKnown(it) }.map { (name, value) -> UnknownNode(supply, name, value) }
        }
}
