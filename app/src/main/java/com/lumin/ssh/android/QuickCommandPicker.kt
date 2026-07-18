package com.lumin.ssh.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

fun quickCommandParams(command: String, fallbackLabel: (String) -> String = { "Parameter $it" }): List<Pair<Int, String>> {
    val regex = Regex("\\[p#(\\d)(?:\\s+([^\\]]*))?]")
    return regex.findAll(command)
        .mapNotNull { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val label = match.groupValues.getOrNull(2).orEmpty().ifBlank { fallbackLabel(num.toString()) }
            num to label
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
        .toList()
}

fun fillQuickCommandParams(command: String, values: List<String>): String {
    val regex = Regex("\\[p#(\\d)(?:\\s+([^\\]]*))?]")
    return regex.replace(command) { match -> values.getOrNull(match.groupValues[1].toIntOrNull()?.minus(1) ?: -1).orEmpty() }
}

@Composable
fun QuickCommandTree(
    nodes: List<QuickCommandNode>,
    collapsedFolders: MutableList<String>,
    onPick: (QuickCommand) -> Unit,
    depth: Int = 0,
) {
    nodes.forEach { node ->
        when (node) {
            is QuickCommandNode.Command -> {
                LuminSecondaryButton(
                    onClick = { onPick(node.item) },
                    modifier = Modifier.fillMaxWidth().padding(start = (depth * 14).dp),
                ) { Text(node.item.name) }
            }
            is QuickCommandNode.Folder -> {
                val collapsed = collapsedFolders.contains(node.path)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(start = (depth * 10).dp, top = 4.dp, bottom = 4.dp),
                    shape = LuminControlShape,
                    colors = CardDefaults.cardColors(containerColor = LuminColors.SurfaceSunken),
                    border = BorderStroke(1.dp, LuminColors.BorderSubtle),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (collapsed) "▸ ${node.name}" else "▾ ${node.name}",
                            color = LuminColors.Accent,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (collapsedFolders.contains(node.path)) {
                                    collapsedFolders.remove(node.path)
                                } else {
                                    collapsedFolders.add(node.path)
                                }
                            },
                        )
                        if (!collapsed) QuickCommandTree(node.children, collapsedFolders, onPick, depth + 1)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickCommandPickerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    quickCommands: List<QuickCommand>,
    quickCommandTree: List<QuickCommandNode>,
    collapsedFolders: MutableList<String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onPick: (QuickCommand) -> Unit,
) {
    if (!show) return
    val filtered = if (searchQuery.isBlank()) null else quickCommands.filter {
        it.name.contains(searchQuery, ignoreCase = true) || it.command.contains(searchQuery, ignoreCase = true)
    }
    Dialog(onDismissRequest = onDismiss) {
        LuminDialogCard {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_commands), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    LuminSecondaryButton(onClick = { onDismiss(); onManage() }) { Text(stringResource(R.string.manage)) }
                    Spacer(Modifier.width(8.dp))
                    LuminSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
                OutlinedTextField(
                    searchQuery,
                    onSearchChange,
                    label = { Text(stringResource(R.string.search_quick_commands)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = LuminControlShape,
                    colors = luminTextFieldColors(),
                    singleLine = true,
                )
                Column(Modifier.padding(top = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (quickCommands.isEmpty()) {
                        LuminEmptyHint(stringResource(R.string.no_synced_quick_commands))
                    }
                    if (filtered != null) {
                        filtered.forEach { cmd ->
                            LuminSecondaryButton(onClick = { onPick(cmd) }, modifier = Modifier.fillMaxWidth()) { Text(cmd.name) }
                        }
                    } else {
                        QuickCommandTree(quickCommandTree, collapsedFolders, onPick)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickCommandConfirmDialog(
    item: QuickCommand?,
    paramValues: List<String>,
    onParamChange: (Int, String) -> Unit,
    onConfirm: (QuickCommand, String) -> Unit,
    onCancel: () -> Unit,
) {
    if (item == null) return
    val context = LocalContext.current
    val params = quickCommandParams(item.command) { context.getString(R.string.parameter_fallback, it) }
    val filledCommand = fillQuickCommandParams(item.command, paramValues)
    Dialog(onDismissRequest = onCancel) {
        LuminDialogCard {
            Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.send_quick_command), style = MaterialTheme.typography.titleLarge)
                Text(item.name, color = LuminColors.Accent)
                params.forEach { (num, label) ->
                    val index = num - 1
                    OutlinedTextField(
                        value = paramValues.getOrNull(index).orEmpty(),
                        onValueChange = { value -> onParamChange(index, value) },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = LuminControlShape,
                        colors = luminTextFieldColors(),
                    )
                }
                Text(filledCommand, color = LuminColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    LuminPrimaryButton(onClick = { onConfirm(item, filledCommand) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.send)) }
                }
            }
        }
    }
}
