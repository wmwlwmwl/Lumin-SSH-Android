@file:OptIn(ExperimentalFoundationApi::class)

package com.lumin.ssh.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun CompactConnectionRow(conn: Connection, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, hideSensitive: Boolean = false) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = LuminControlShape,
        colors = CardDefaults.cardColors(containerColor = LuminColors.SurfaceSunken),
        border = BorderStroke(1.dp, LuminColors.BorderSubtle),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onConnect, onLongClick = { showMenu = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LuminDot(LuminColors.Accent)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conn.name, style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                Text(
                    if (hideSensitive) "•••@•••:•••" else "${conn.username}@${conn.host}:${conn.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LuminColors.TextMuted,
                )
            }
            Box {
                Text(
                    "⋮",
                    color = LuminColors.TextMuted,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .combinedClickable(onClick = { showMenu = true }, onLongClick = { showMenu = true })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.connect)) }, onClick = { showMenu = false; onConnect() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { showMenu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { showMenu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun ConnectionCard(conn: Connection, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    LuminCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(conn.name, style = MaterialTheme.typography.titleLarge, color = LuminColors.TextPrimary)
            Text("${conn.username}@${conn.host}:${conn.port}", color = LuminColors.Accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                LuminPrimaryButton(onClick = onConnect) { Text(stringResource(R.string.connect)) }
                LuminSecondaryButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                LuminDangerButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}
