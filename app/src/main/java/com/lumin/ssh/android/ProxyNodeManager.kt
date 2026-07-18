package com.lumin.ssh.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.UUID

@Composable
fun ProxyNodeManager(
    proxyNodes: List<ProxyNode>,
    onClose: () -> Unit,
    onSave: (ProxyNode) -> Unit,
    onDelete: (ProxyNode) -> Unit,
) {
    BackHandler(onBack = onClose)
    var editing by remember { mutableStateOf<ProxyNode?>(null) }
    var creating by remember { mutableStateOf(false) }
    val formTarget = editing
    val showForm = creating || formTarget != null

    Column(
        Modifier
            .fillMaxSize()
            .background(LuminColors.SurfaceBase)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LuminPageHeader(
            title = stringResource(R.string.proxy_node_management),
            onBack = onClose,
            backLabel = stringResource(R.string.back),
        ) {
            LuminPrimaryButton(onClick = { editing = null; creating = true }) {
                Text(stringResource(R.string.add))
            }
        }
        if (proxyNodes.isEmpty()) {
            LuminEmptyState(
                title = stringResource(R.string.no_proxy_nodes_hint),
                actionLabel = stringResource(R.string.add_proxy_node),
                onAction = { editing = null; creating = true },
            )
        }
        proxyNodes.forEach { node ->
            Card(
                Modifier.fillMaxWidth(),
                shape = LuminControlShape,
                colors = CardDefaults.cardColors(containerColor = LuminColors.SurfaceSunken),
                border = BorderStroke(1.dp, LuminColors.BorderSubtle),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(node.name.ifBlank { node.host }, color = LuminColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("${node.type.uppercase()}  ${node.host}:${node.port}", style = MaterialTheme.typography.bodySmall, color = LuminColors.TextMuted)
                    }
                    LuminSecondaryButton(onClick = { creating = false; editing = node }) { Text(stringResource(R.string.edit)) }
                    LuminDangerButton(onClick = { onDelete(node) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }

    if (showForm) {
        ProxyEditDialog(
            initial = formTarget,
            onDismiss = { editing = null; creating = false },
            onSave = { node ->
                onSave(node)
                editing = null
                creating = false
            },
        )
    }
}

@Composable
private fun ProxyEditDialog(
    initial: ProxyNode?,
    onDismiss: () -> Unit,
    onSave: (ProxyNode) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: "socks5") }
    var host by remember(initial?.id) { mutableStateOf(initial?.host ?: "") }
    var port by remember(initial?.id) { mutableStateOf((initial?.port ?: 1080).toString()) }
    var username by remember(initial?.id) { mutableStateOf(initial?.username ?: "") }
    var password by remember(initial?.id) { mutableStateOf(initial?.password ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        LuminDialogCard {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (initial == null) stringResource(R.string.new_proxy_node) else stringResource(R.string.edit_proxy_node),
                    style = MaterialTheme.typography.titleLarge,
                    color = LuminColors.TextPrimary,
                )
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.proxy_name)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminChoiceChip("SOCKS5", type == "socks5", { type = "socks5" })
                    LuminChoiceChip("HTTP", type == "http", { type = "http" })
                }
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.proxy_host)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.proxy_port)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.proxy_username_optional)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.proxy_password_optional)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    LuminPrimaryButton(
                        enabled = host.isNotBlank(),
                        onClick = {
                            onSave(
                                ProxyNode(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = name,
                                    type = if (type == "http") "http" else "socks5",
                                    host = host,
                                    port = port.toIntOrNull() ?: 1080,
                                    username = username,
                                    password = password,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.save_proxy)) }
                }
            }
        }
    }
}
