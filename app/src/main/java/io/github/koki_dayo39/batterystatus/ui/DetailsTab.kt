package io.github.koki_dayo39.batterystatus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.koki_dayo39.batterystatus.R

@Composable
fun DetailsTab(state: UiState) {
    val snapshot = state.snapshot
    val na = stringResource(R.string.value_na)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (snapshot == null) {
            item {
                Text(
                    stringResource(R.string.waiting_for_data),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
            return@LazyColumn
        }
        val local = snapshot.local
        item {
            Section(
                stringResource(R.string.section_battery_manager),
                listOf(
                    "level" to local.level.toString(),
                    "scale" to local.scale.toString(),
                    "status" to local.status.toString(),
                    "plugged" to local.plugged.toString(),
                    "health" to local.health.toString(),
                    "voltage (mV)" to local.voltageMv.toString(),
                    "temperature (0.1°C)" to local.temperatureTenths.toString(),
                    "technology" to (local.technology ?: na),
                    "present" to local.present.toString(),
                    "CURRENT_NOW (raw)" to (local.currentNowUa?.toString() ?: na),
                    "CURRENT_AVERAGE (raw)" to (local.currentAvgUa?.toString() ?: na),
                    "CHARGE_COUNTER (µAh)" to (local.chargeCounterUah?.toString() ?: na),
                    "ENERGY_COUNTER (nWh)" to (local.energyCounterNwh?.toString() ?: na),
                    "CAPACITY (%)" to (local.capacityPct?.toString() ?: na),
                    "chargeTimeRemaining (ms)" to (local.chargeTimeRemainingMs?.toString() ?: na),
                    "cycleCount" to (local.cycleCount?.toString() ?: na),
                    "chargingStatus" to (local.chargingStatus?.toString() ?: na),
                    "normalised current (mA)" to (snapshot.currentMa?.fmt(1) ?: na),
                    "power (W)" to (snapshot.powerW?.fmt(3) ?: na),
                ),
            )
        }
        val shell = snapshot.shell ?: return@LazyColumn
        item { Section(stringResource(R.string.section_dumpsys_battery), shell.dumpsysBattery.toList()) }
        shell.powerSupplies.forEach { (name, values) ->
            item(key = "ps-$name") {
                Section(stringResource(R.string.section_power_supply, name), values.toList())
            }
        }
        if (shell.batteryStats.isNotEmpty()) {
            item { Section(stringResource(R.string.section_batterystats), shell.batteryStats.toList()) }
        }
    }
}

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { (k, v) -> KeyValueRow(k, v) }
                }
            }
        }
    }
}
