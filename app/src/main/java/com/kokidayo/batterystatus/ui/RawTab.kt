package com.kokidayo.batterystatus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kokidayo.batterystatus.R

@Composable
fun RawTab(state: UiState) {
    val shell = state.snapshot?.shell
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (shell == null) {
            Text(
                stringResource(R.string.raw_empty),
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        RawBlock("$ dumpsys battery", shell.dumpsysRaw)
        RawBlock("$ /sys/class/power_supply/*  (supply|file|value)", shell.sysfsRaw)
        if (shell.batteryStatsRaw.isNotBlank()) {
            RawBlock("$ dumpsys batterystats | grep …", shell.batteryStatsRaw)
        }
    }
}

@Composable
private fun RawBlock(title: String, body: String) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    body.ifBlank { stringResource(R.string.value_na) },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
