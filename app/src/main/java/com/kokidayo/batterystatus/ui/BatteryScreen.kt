package com.kokidayo.batterystatus.ui

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kokidayo.batterystatus.R
import com.kokidayo.batterystatus.data.BatterySnapshot
import com.kokidayo.batterystatus.data.capacityHealthPercent
import com.kokidayo.batterystatus.data.chargerInfo
import com.kokidayo.batterystatus.data.cycleCount
import com.kokidayo.batterystatus.data.designCapacityMah
import com.kokidayo.batterystatus.data.estimatedCapacityMah
import com.kokidayo.batterystatus.data.firstUsageDateEpoch
import com.kokidayo.batterystatus.data.fullCapacityMah
import com.kokidayo.batterystatus.data.internalResistanceMilliOhm
import com.kokidayo.batterystatus.data.manufacturingDateEpoch
import com.kokidayo.batterystatus.data.maxChargingCurrentA
import com.kokidayo.batterystatus.data.maxChargingPowerW
import com.kokidayo.batterystatus.data.maxChargingVoltageV
import com.kokidayo.batterystatus.data.sysfsTemperatureC
import com.kokidayo.batterystatus.data.vendorHealthIndex
import com.kokidayo.batterystatus.diag.Diagnostics
import com.kokidayo.batterystatus.diag.DiagnosticsReport
import com.kokidayo.batterystatus.diag.KnownSysfs
import com.kokidayo.batterystatus.diag.UnknownNode
import com.kokidayo.batterystatus.util.AppLog
import kotlinx.coroutines.launch
import com.kokidayo.batterystatus.shizuku.ShizukuManager
import com.kokidayo.batterystatus.shizuku.ShizukuState

private val TAB_TITLES = listOf(R.string.tab_overview, R.string.tab_details, R.string.tab_raw)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryApp(viewModel: BatteryViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.diagnostics_saved)
    val failedMessage = stringResource(R.string.diagnostics_failed)
    val copiedMessage = stringResource(R.string.copied)

    var pendingReport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val report = pendingReport ?: return@rememberLauncherForActivityResult
        pendingReport = null
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
                ?: error("openOutputStream returned null")
        }
        scope.launch {
            snackbar.showSnackbar(result.fold({ savedMessage }, { failedMessage.format(it.message ?: it.javaClass.simpleName) }))
        }
    }
    val diagnostics = remember(viewModel) {
        DiagnosticsActions(
            share = {
                val result = Diagnostics.share(context, viewModel.buildDiagnostics())
                result.exceptionOrNull()?.let { e ->
                    scope.launch { snackbar.showSnackbar(failedMessage.format(e.message ?: e.javaClass.simpleName)) }
                }
            },
            save = {
                pendingReport = viewModel.buildDiagnostics()
                saveLauncher.launch(DiagnosticsReport.fileName())
            },
            copy = {
                Diagnostics.copy(context, "battery-status-diagnostics", viewModel.buildDiagnostics())
                scope.launch { snackbar.showSnackbar(copiedMessage) }
            },
            clearLog = { AppLog.clear() },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { viewModel.refreshShizuku() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_retry))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(stringResource(title)) },
                    )
                }
            }
            when (tab) {
                0 -> OverviewTab(state, viewModel)
                1 -> DetailsTab(state)
                else -> RawTab(state)
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onChange = viewModel::updateSettings,
            diagnostics = diagnostics,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun OverviewTab(state: UiState, viewModel: BatteryViewModel) {
    val snapshot = state.snapshot
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ShizukuCard(state, viewModel) }
        if (snapshot == null) {
            item {
                Text(
                    stringResource(R.string.waiting_for_data),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            item { HeroCard(snapshot) }
            item { PowerCard(snapshot, state.history) }
            item { ChargerCard(snapshot) }
            item { HealthCard(snapshot) }
            item { InfoCard(snapshot) }
            snapshot.shell?.let { shell ->
                val unknown = KnownSysfs.unknownNodes(shell)
                if (unknown.isNotEmpty()) item { UnknownSysfsCard(unknown) }
            }
            item {
                Text(
                    stringResource(R.string.footer_updated, formatTime(snapshot.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ShizukuCard(state: UiState, viewModel: BatteryViewModel) {
    val context = LocalContext.current
    val (title, desc) = when (state.shizuku) {
        ShizukuState.NOT_INSTALLED -> R.string.shizuku_not_installed to R.string.shizuku_not_installed_desc
        ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running to R.string.shizuku_not_running_desc
        ShizukuState.PERMISSION_REQUIRED -> R.string.shizuku_permission_required to R.string.shizuku_permission_required_desc
        ShizukuState.PERMISSION_DENIED -> R.string.shizuku_permission_denied to R.string.shizuku_permission_denied_desc
        ShizukuState.UNSUPPORTED_VERSION -> R.string.shizuku_unsupported to R.string.shizuku_unsupported_desc
        ShizukuState.READY -> R.string.shizuku_title to null
    }
    val ready = state.shizuku == ShizukuState.READY
    val containerColor = when {
        ready && state.shellConnected -> MaterialTheme.colorScheme.secondaryContainer
        ready -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.errorContainer
    }

    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            when {
                ready && state.shellConnected -> Text(
                    if (state.shellUid != null) stringResource(R.string.shizuku_ready, state.shellUid)
                    else stringResource(R.string.shizuku_ready_basic),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ready -> Text(stringResource(R.string.shizuku_connecting), style = MaterialTheme.typography.bodyMedium)
                desc != null -> Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium)
            }
            state.shellError?.let {
                Text(
                    stringResource(R.string.shell_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.shizuku) {
                    ShizukuState.NOT_INSTALLED -> Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuManager.SHIZUKU_URL)))
                    }) { Text(stringResource(R.string.action_install_shizuku)) }

                    ShizukuState.NOT_RUNNING, ShizukuState.PERMISSION_DENIED, ShizukuState.UNSUPPORTED_VERSION -> {
                        Button(onClick = {
                            context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
                                ?.let { context.startActivity(it) }
                        }) { Text(stringResource(R.string.action_open_shizuku)) }
                        OutlinedButton(onClick = { viewModel.refreshShizuku() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }

                    ShizukuState.PERMISSION_REQUIRED -> Button(onClick = { viewModel.requestShizukuPermission() }) {
                        Text(stringResource(R.string.action_request_permission))
                    }

                    ShizukuState.READY -> if (!state.shellConnected || state.shellError != null) {
                        OutlinedButton(onClick = { viewModel.refreshShizuku() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(snapshot: BatterySnapshot) {
    val local = snapshot.local
    val charging = local.status == BatteryManager.BATTERY_STATUS_CHARGING
    val status = statusLabel(local.status)
    val plugged = pluggedLabel(local.plugged)
    Card {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${local.percent.toInt()}%",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (local.plugged != 0) "$status · $plugged" else status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (local.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = if (charging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
            local.chargeTimeRemainingMs?.takeIf { charging && it >= 60_000 }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${stringResource(R.string.label_time_to_full)}: ${formatDuration(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PowerCard(snapshot: BatterySnapshot, history: List<Float>) {
    val na = stringResource(R.string.value_na)
    val powerW = snapshot.powerW
    val lineColor = when {
        powerW == null -> MaterialTheme.colorScheme.outline
        powerW >= 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.card_power), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigStat(
                    label = stringResource(R.string.label_power),
                    value = powerW?.let { it.fmtSigned(2) } ?: na,
                    unit = "W",
                    color = lineColor,
                )
                BigStat(
                    label = stringResource(R.string.label_current),
                    value = snapshot.currentMa?.let { it.fmtSigned(0) } ?: na,
                    unit = "mA",
                )
                BigStat(
                    label = stringResource(R.string.label_voltage),
                    value = snapshot.voltageV.fmt(3),
                    unit = "V",
                )
            }
            powerW?.let {
                Text(
                    if (it >= 0) stringResource(R.string.charging_into_battery)
                    else stringResource(R.string.discharging_from_battery),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.card_history, UiState.HISTORY_SIZE),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Sparkline(
                values = history,
                capacity = UiState.HISTORY_SIZE,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                lineColor = lineColor,
                zeroLineColor = MaterialTheme.colorScheme.outlineVariant,
            )
            snapshot.currentAvgMa?.let {
                KeyValueRow(stringResource(R.string.label_avg_current), "${it.fmtSigned(0)} mA")
            }
        }
    }
}

@Composable
private fun ChargerCard(snapshot: BatterySnapshot) {
    val na = stringResource(R.string.value_na)
    val charger = snapshot.chargerInfo()
    val maxA = snapshot.maxChargingCurrentA()
    val maxV = snapshot.maxChargingVoltageV()
    val maxW = snapshot.maxChargingPowerW()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.card_charger), style = MaterialTheme.typography.titleMedium)
            KeyValueRow(stringResource(R.string.label_source), pluggedLabel(snapshot.local.plugged))
            if (snapshot.shell == null) {
                Text(
                    stringResource(R.string.charger_needs_shizuku),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            KeyValueRow(stringResource(R.string.label_max_charging_current), maxA?.let { "${it.fmt(2)} A" } ?: na)
            KeyValueRow(stringResource(R.string.label_max_charging_voltage), maxV?.let { "${it.fmt(2)} V" } ?: na)
            KeyValueRow(stringResource(R.string.label_max_charging_power), maxW?.let { "${it.fmt(1)} W" } ?: na)
            if (charger != null) {
                HorizontalDivider()
                charger.voltageV?.let { KeyValueRow(stringResource(R.string.label_charger_voltage), "${it.fmt(2)} V") }
                charger.currentMa?.let { KeyValueRow(stringResource(R.string.label_charger_current), "${it.fmt(0)} mA") }
                charger.powerW?.let { KeyValueRow(stringResource(R.string.label_charger_power), "${it.fmt(1)} W") }
                charger.inputCurrentLimitMa?.let {
                    KeyValueRow(stringResource(R.string.label_input_current_limit), "${it.fmt(0)} mA")
                }
                charger.chargerType?.let { KeyValueRow(stringResource(R.string.label_charger_type), it) }
                charger.typecMode?.let { KeyValueRow(stringResource(R.string.label_typec_mode), it) }
                charger.chargePhase?.let { KeyValueRow(stringResource(R.string.label_charge_type), it) }
                charger.pdCapabilities?.let { KeyValueRow(stringResource(R.string.label_pd_capabilities), it) }
            }
        }
    }
}

@Composable
private fun HealthCard(snapshot: BatterySnapshot) {
    val na = stringResource(R.string.value_na)
    val local = snapshot.local
    val design = snapshot.designCapacityMah()
    val full = snapshot.fullCapacityMah()
    val estimated = snapshot.estimatedCapacityMah()
    val healthPct = snapshot.capacityHealthPercent()
    val cycles = snapshot.cycleCount()
    val sysfsTemp = snapshot.sysfsTemperatureC()
    val vendorHealth = snapshot.vendorHealthIndex()
    val resistance = snapshot.internalResistanceMilliOhm()
    val manufactured = snapshot.manufacturingDateEpoch()
    val firstUse = snapshot.firstUsageDateEpoch()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.card_health), style = MaterialTheme.typography.titleMedium)
            KeyValueRow(
                stringResource(R.string.label_temperature),
                buildString {
                    append(local.temperatureC.toDouble().fmt(1)).append(" °C")
                    if (sysfsTemp != null && kotlin.math.abs(sysfsTemp - local.temperatureC) >= 0.5) {
                        append("  (sysfs ").append(sysfsTemp.fmt(1)).append(" °C)")
                    }
                },
            )
            KeyValueRow(stringResource(R.string.label_health), healthLabel(local.health))
            KeyValueRow(stringResource(R.string.label_cycle_count), cycles?.toString() ?: na)
            if (snapshot.shell == null && design == null && cycles == null) {
                Text(
                    stringResource(R.string.health_needs_shizuku),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (design != null || full != null || estimated != null) {
                HorizontalDivider()
                KeyValueRow(stringResource(R.string.label_capacity_design), design?.let { "${it.fmt(0)} mAh" } ?: na)
                KeyValueRow(stringResource(R.string.label_capacity_full), full?.let { "${it.fmt(0)} mAh" } ?: na)
                KeyValueRow(stringResource(R.string.label_capacity_estimated), estimated?.let { "${it.fmt(0)} mAh" } ?: na)
                healthPct?.let {
                    KeyValueRow(stringResource(R.string.label_battery_health_pct), "${it.fmt(1)} %")
                    LinearProgressIndicator(
                        progress = { (it / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (vendorHealth != null || resistance != null || manufactured != null || firstUse != null) {
                HorizontalDivider()
                vendorHealth?.let { KeyValueRow(stringResource(R.string.label_vendor_health_index), "$it") }
                resistance?.let { KeyValueRow(stringResource(R.string.label_internal_resistance), "${it.fmt(1)} mΩ") }
                manufactured?.let { KeyValueRow(stringResource(R.string.label_manufacturing_date), formatDate(it)) }
                firstUse?.let { KeyValueRow(stringResource(R.string.label_first_usage_date), formatDate(it)) }
            }
        }
    }
}

@Composable
private fun InfoCard(snapshot: BatterySnapshot) {
    val na = stringResource(R.string.value_na)
    val local = snapshot.local
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.card_info), style = MaterialTheme.typography.titleMedium)
            KeyValueRow(stringResource(R.string.label_technology), local.technology ?: na)
            KeyValueRow(stringResource(R.string.label_level_raw), "${local.level} / ${local.scale}")
            KeyValueRow(
                stringResource(R.string.label_charge_counter),
                local.chargeCounterUah?.let { "${(it / 1000.0).fmt(0)} mAh" } ?: na,
            )
            KeyValueRow(
                stringResource(R.string.label_energy_counter),
                local.energyCounterNwh?.takeIf { it != 0L }?.let { "${(it / 1_000_000.0).fmt(1)} mWh" } ?: na,
            )
            KeyValueRow(stringResource(R.string.label_present), local.present.toString())
            chargingStatusLabel(local.chargingStatus)?.let {
                KeyValueRow(stringResource(R.string.label_charging_status), it)
            }
        }
    }
}

@Composable
private fun UnknownSysfsCard(unknown: List<UnknownNode>) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val copied = stringResource(R.string.copied)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.card_unknown_sysfs, unknown.size), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.unknown_sysfs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.action_hide else R.string.action_show))
                }
                OutlinedButton(onClick = {
                    val text = unknown.joinToString("\n") { "${it.supply}/${it.name} = ${it.value}" }
                    Diagnostics.copy(context, "unknown-sysfs-nodes", text)
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.action_copy)) }
            }
            if (expanded) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        unknown.groupBy { it.supply }.forEach { (supply, nodes) ->
                            Text(supply, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            nodes.forEach {
                                Text(
                                    "${it.name} = ${it.value}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = color)
            Text(
                " $unit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
