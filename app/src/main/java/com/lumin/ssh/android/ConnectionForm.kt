package com.lumin.ssh.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import java.util.UUID

@Composable
fun ConnectionForm(
    initial: Connection?,
    credentials: List<Credential>,
    proxyNodes: List<ProxyNode>,
    groups: List<String>,
    onCancel: () -> Unit,
    onManageCredentials: () -> Unit,
    onSave: (Connection) -> Unit,
) {
    BackHandler(onBack = onCancel)
    // 克隆时 id 为空，不能只靠 id 当 key，否则连点克隆不同服务器表单不刷新
    val formKey = initial?.id?.takeIf { it.isNotBlank() }
        ?: "new|${initial?.host}|${initial?.port}|${initial?.username}|${initial?.name}|${initial?.lastModified}|${initial?.credentialId}|${initial?.proxyNodeId}"
    var name by remember(formKey) { mutableStateOf(initial?.name ?: "") }
    var host by remember(formKey) { mutableStateOf(initial?.host ?: "") }
    var port by remember(formKey) { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember(formKey) { mutableStateOf(initial?.username ?: "") }
    var group by remember(formKey) { mutableStateOf(initial?.group ?: "") }
    var groupMenuOpen by remember { mutableStateOf(false) }
    var password by remember(formKey) { mutableStateOf(initial?.password ?: "") }
    var authMethod by remember(formKey) { mutableStateOf(initial?.authMethod ?: "password") }
    var privateKey by remember(formKey) { mutableStateOf(initial?.privateKey ?: "") }
    var passphrase by remember(formKey) { mutableStateOf(initial?.passphrase ?: "") }
    var credentialId by remember(formKey) { mutableStateOf(initial?.credentialId ?: "") }
    var useCredential by remember(formKey) { mutableStateOf(!initial?.credentialId.isNullOrBlank()) }
    var credentialMenuOpen by remember { mutableStateOf(false) }
    var proxyMode by remember(formKey) { mutableStateOf(initial?.proxyMode?.takeIf { it != "direct" && it != "none" } ?: "") }
    var proxyNodeId by remember(formKey) { mutableStateOf(initial?.proxyNodeId ?: "") }
    var proxyType by remember(formKey) { mutableStateOf(initial?.proxyType ?: "socks5") }
    var proxyHost by remember(formKey) { mutableStateOf(initial?.proxyHost ?: "") }
    var proxyPort by remember(formKey) { mutableStateOf((initial?.proxyPort ?: 1080).toString()) }
    var proxyUsername by remember(formKey) { mutableStateOf(initial?.proxyUsername ?: "") }
    var proxyPassword by remember(formKey) { mutableStateOf(initial?.proxyPassword ?: "") }
    var proxyMenuOpen by remember { mutableStateOf(false) }
    val selectedCredential = credentials.firstOrNull { it.id == credentialId }
    val selectedProxyNode = proxyNodes.firstOrNull { it.id == proxyNodeId }
    val canSave = host.isNotBlank() && username.isNotBlank() &&
        ((!useCredential && (authMethod == "password" || privateKey.isNotBlank())) || (useCredential && credentialId.isNotBlank())) &&
        (proxyMode.isBlank() || (proxyMode == "node" && proxyNodeId.isNotBlank()) || (proxyMode == "custom" && proxyHost.isNotBlank()))

    // id 为空 = 新增/克隆（与 PC 一致：克隆预填字段但 id 置空）
    val isNew = initial?.id.isNullOrBlank()

    fun submit() {
        onSave(
            Connection(
                id = initial?.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = name.ifBlank { host },
                host = host,
                port = port.toIntOrNull() ?: 22,
                username = username,
                password = password,
                authMethod = authMethod,
                privateKey = privateKey,
                passphrase = passphrase,
                group = group,
                os = if (isNew) "" else (initial?.os ?: ""),
                credentialId = if (useCredential) credentialId else "",
                proxyMode = proxyMode,
                proxyNodeId = if (proxyMode == "node") proxyNodeId else "",
                proxyType = if (proxyType == "http") "http" else "socks5",
                proxyHost = if (proxyMode == "custom") proxyHost else "",
                proxyPort = proxyPort.toIntOrNull() ?: 1080,
                proxyUsername = if (proxyMode == "custom") proxyUsername else "",
                proxyPassword = if (proxyMode == "custom") proxyPassword else "",
                lastModified = System.currentTimeMillis(),
            ),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LuminColors.SurfaceBase)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LuminPageHeader(
            title = if (isNew) stringResource(R.string.add_server) else stringResource(R.string.edit_server),
            onBack = onCancel,
            backLabel = stringResource(R.string.back),
        ) {
            LuminPrimaryButton(enabled = canSave, onClick = { submit() }) {
                Text(stringResource(R.string.save))
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LuminSectionTitle(stringResource(R.string.basic_info))
            LuminSoftPanel {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.proxy_name)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.host)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.port)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                Box {
                    OutlinedTextField(group, { group = it }, label = { Text(stringResource(R.string.group_folder_input)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                    DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ungrouped)) }, onClick = { group = ""; groupMenuOpen = false })
                        groups.forEach { groupName ->
                            DropdownMenuItem(text = { Text(groupName) }, onClick = { group = groupName; groupMenuOpen = false })
                        }
                    }
                }
                if (groups.isNotEmpty()) {
                    LuminSecondaryButton(onClick = { groupMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.select_existing_group))
                    }
                }
            }

            LuminSectionTitle(stringResource(R.string.auth_method))
            LuminSoftPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    LuminChoiceChip(if (!useCredential) stringResource(R.string.custom_auth_selected) else stringResource(R.string.custom_auth), !useCredential, { useCredential = false; credentialId = "" })
                    LuminChoiceChip(if (useCredential) stringResource(R.string.use_credential_selected) else stringResource(R.string.use_credential), useCredential, { useCredential = true })
                }
                if (useCredential) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.select_credential_required), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        LuminTextAction(onClick = onManageCredentials) { Text(stringResource(R.string.credential_management)) }
                    }
                    if (credentials.isEmpty()) {
                        Text(stringResource(R.string.no_credentials_hint), color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Box {
                            LuminSecondaryButton(onClick = { credentialMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selectedCredential?.let { "${it.name.ifBlank { it.id }} (${it.username})" } ?: stringResource(R.string.please_select_credential))
                            }
                            DropdownMenu(expanded = credentialMenuOpen, onDismissRequest = { credentialMenuOpen = false }) {
                                credentials.forEach { credential ->
                                    DropdownMenuItem(
                                        text = { Text("${credential.name.ifBlank { credential.id }} (${credential.username})") },
                                        onClick = { credentialId = credential.id; credentialMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.selected_credential_hint), color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        LuminChoiceChip(if (authMethod == "password") stringResource(R.string.password_login_selected) else stringResource(R.string.password_login), authMethod == "password", { authMethod = "password" })
                        LuminChoiceChip(if (authMethod == "privateKey") stringResource(R.string.private_key_login_selected) else stringResource(R.string.private_key_login), authMethod == "privateKey", { authMethod = "privateKey" })
                    }
                    if (authMethod == "password") {
                        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                    } else {
                        OutlinedTextField(privateKey, { privateKey = it }, label = { Text(stringResource(R.string.private_key_content)) }, modifier = Modifier.fillMaxWidth(), minLines = 4, shape = LuminControlShape, colors = luminTextFieldColors())
                        OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.private_key_passphrase_optional)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                    }
                }
            }

            LuminSectionTitle(stringResource(R.string.proxy_settings))
            LuminSoftPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    LuminChoiceChip(if (proxyMode.isBlank()) stringResource(R.string.no_proxy_selected) else stringResource(R.string.no_proxy), proxyMode.isBlank(), { proxyMode = ""; proxyNodeId = "" })
                    LuminChoiceChip(if (proxyMode == "node") stringResource(R.string.select_proxy_node_selected) else stringResource(R.string.select_proxy_node), proxyMode == "node", { proxyMode = "node" })
                    LuminChoiceChip(if (proxyMode == "custom") stringResource(R.string.custom_proxy_selected) else stringResource(R.string.custom_proxy), proxyMode == "custom", { proxyMode = "custom" })
                }
                if (proxyMode == "node") {
                    if (proxyNodes.isEmpty()) {
                        Text(stringResource(R.string.no_proxy_nodes_hint), color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Box {
                            LuminSecondaryButton(onClick = { proxyMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selectedProxyNode?.let { "${it.name.ifBlank { it.host }} (${it.type} ${it.host}:${it.port})" } ?: stringResource(R.string.please_select_proxy_node))
                            }
                            DropdownMenu(expanded = proxyMenuOpen, onDismissRequest = { proxyMenuOpen = false }) {
                                proxyNodes.forEach { node ->
                                    DropdownMenuItem(
                                        text = { Text("${node.name.ifBlank { node.host }} (${node.type} ${node.host}:${node.port})") },
                                        onClick = { proxyNodeId = node.id; proxyMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }
                } else if (proxyMode == "custom") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LuminChoiceChip("SOCKS5", proxyType == "socks5", { proxyType = "socks5" })
                        LuminChoiceChip("HTTP", proxyType == "http", { proxyType = "http" })
                    }
                    OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text(stringResource(R.string.proxy_host)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                    OutlinedTextField(proxyPort, { proxyPort = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.proxy_port)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                    OutlinedTextField(proxyUsername, { proxyUsername = it }, label = { Text(stringResource(R.string.proxy_username_optional)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                    OutlinedTextField(proxyPassword, { proxyPassword = it }, label = { Text(stringResource(R.string.proxy_password_optional)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                }
            }
        }
    }
}
