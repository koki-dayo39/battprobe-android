package io.github.koki_dayo39.batterystatus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.koki_dayo39.batterystatus.R
import io.github.koki_dayo39.batterystatus.data.BackendPreference
import io.github.koki_dayo39.batterystatus.data.CurrentUnit
import io.github.koki_dayo39.batterystatus.data.NotifyMode
import io.github.koki_dayo39.batterystatus.data.Settings
import io.github.koki_dayo39.batterystatus.data.SignMode

class DiagnosticsActions(
    val share: () -> Unit,
    val save: () -> Unit,
    val copy: () -> Unit,
    val clearLog: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    onChange: (Settings) -> Unit,
    onNotifyMode: (NotifyMode) -> Unit,
    onTestNotification: () -> Unit,
    diagnostics: DiagnosticsActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_interval), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.INTERVAL_OPTIONS.forEach { ms ->
                        FilterChip(
                            selected = settings.intervalMs == ms,
                            onClick = { onChange(settings.copy(intervalMs = ms)) },
                            label = { Text(if (ms < 1000) "${ms} ms" else "${ms / 1000} s") },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_backend), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_backend_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RadioRow(stringResource(R.string.backend_auto), settings.backend == BackendPreference.AUTO) {
                    onChange(settings.copy(backend = BackendPreference.AUTO))
                }
                RadioRow(stringResource(R.string.backend_shizuku), settings.backend == BackendPreference.SHIZUKU) {
                    onChange(settings.copy(backend = BackendPreference.SHIZUKU))
                }
                RadioRow(stringResource(R.string.backend_root), settings.backend == BackendPreference.ROOT) {
                    onChange(settings.copy(backend = BackendPreference.ROOT))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_notify), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_notify_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RadioRow(stringResource(R.string.notify_off), settings.notifyMode == NotifyMode.OFF) { onNotifyMode(NotifyMode.OFF) }
                RadioRow(stringResource(R.string.notify_connect_30s), settings.notifyMode == NotifyMode.CONNECT_30S) { onNotifyMode(NotifyMode.CONNECT_30S) }
                RadioRow(stringResource(R.string.notify_while_charging), settings.notifyMode == NotifyMode.WHILE_CHARGING) { onNotifyMode(NotifyMode.WHILE_CHARGING) }
                TextButton(onClick = onTestNotification, enabled = settings.notifyMode != NotifyMode.OFF) {
                    Text(stringResource(R.string.action_test_notification))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_current_unit), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_current_unit_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RadioRow(stringResource(R.string.unit_micro), settings.currentUnit == CurrentUnit.MICRO_AMP) {
                    onChange(settings.copy(currentUnit = CurrentUnit.MICRO_AMP))
                }
                RadioRow(stringResource(R.string.unit_milli), settings.currentUnit == CurrentUnit.MILLI_AMP) {
                    onChange(settings.copy(currentUnit = CurrentUnit.MILLI_AMP))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_sign), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_sign_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RadioRow(stringResource(R.string.sign_auto), settings.signMode == SignMode.AUTO) {
                    onChange(settings.copy(signMode = SignMode.AUTO))
                }
                RadioRow(stringResource(R.string.sign_as_is), settings.signMode == SignMode.AS_REPORTED) {
                    onChange(settings.copy(signMode = SignMode.AS_REPORTED))
                }
                RadioRow(stringResource(R.string.sign_inverted), settings.signMode == SignMode.INVERTED) {
                    onChange(settings.copy(signMode = SignMode.INVERTED))
                }
            }
            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_diagnostics_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = diagnostics.share) { Text(stringResource(R.string.action_share_diagnostics)) }
                    OutlinedButton(onClick = diagnostics.save) { Text(stringResource(R.string.action_save_diagnostics)) }
                    OutlinedButton(onClick = diagnostics.copy) { Text(stringResource(R.string.action_copy_diagnostics)) }
                }
                TextButton(onClick = diagnostics.clearLog) { Text(stringResource(R.string.action_clear_log)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
