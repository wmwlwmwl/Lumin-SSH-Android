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
import androidx.compose.runtime.LaunchedEffect
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
fun CredentialManager(
    credentials: List<Credential>,
    editing: Credential?,
    onClose: () -> Unit,
    onEdit: (Credential?) -> Unit,
    onDelete: (Credential) -> Unit,
    onSave: (Credential) -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(LuminColors.SurfaceBase)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LuminPageHeader(
            title = stringResource(R.string.credential_management),
            onBack = onClose,
            backLabel = stringResource(R.string.back),
        ) {
            LuminPrimaryButton(onClick = { onEdit(Credential(id = UUID.randomUUID().toString())) }) {
                Text(stringResource(R.string.add))
            }
        }
        if (credentials.isEmpty()) {
            LuminEmptyState(
                title = stringResource(R.string.no_credentials_hint),
                actionLabel = stringResource(R.string.add_credential),
                onAction = { onEdit(Credential(id = UUID.randomUUID().toString())) },
            )
        }
        credentials.forEach { credential ->
            Card(
                Modifier.fillMaxWidth(),
                shape = LuminControlShape,
                colors = CardDefaults.cardColors(containerColor = LuminColors.SurfaceSunken),
                border = BorderStroke(1.dp, LuminColors.BorderSubtle),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(credential.name.ifBlank { credential.id }, color = LuminColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${credential.username} · ${if (credential.authMethod == "privateKey") stringResource(R.string.private_key_auth) else stringResource(R.string.password_auth)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LuminColors.TextMuted,
                        )
                    }
                    LuminSecondaryButton(onClick = { onEdit(credential) }) { Text(stringResource(R.string.edit)) }
                    LuminDangerButton(onClick = { onDelete(credential) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }

    editing?.let { current ->
        CredentialEditDialog(
            initial = current,
            isNew = credentials.none { it.id == current.id },
            onDismiss = { onEdit(null) },
            onSave = { saved ->
                onSave(saved)
                onEdit(null)
            },
        )
    }
}

@Composable
private fun CredentialEditDialog(
    initial: Credential,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Credential) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var username by remember(initial.id) { mutableStateOf(initial.username) }
    var password by remember(initial.id) { mutableStateOf(initial.password) }
    var authMethod by remember(initial.id) { mutableStateOf(initial.authMethod.ifBlank { "password" }) }
    var privateKey by remember(initial.id) { mutableStateOf(initial.privateKey) }
    var passphrase by remember(initial.id) { mutableStateOf(initial.passphrase) }
    val canSave = name.isNotBlank() && username.isNotBlank() && (authMethod == "password" || privateKey.isNotBlank())

    Dialog(onDismissRequest = onDismiss) {
        LuminDialogCard {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isNew) stringResource(R.string.new_credential) else stringResource(R.string.edit_credential),
                    style = MaterialTheme.typography.titleLarge,
                    color = LuminColors.TextPrimary,
                )
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.credential_name)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminChoiceChip(stringResource(R.string.password), authMethod == "password", { authMethod = "password" })
                    LuminChoiceChip(stringResource(R.string.private_key), authMethod == "privateKey", { authMethod = "privateKey" })
                }
                if (authMethod == "password") {
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                } else {
                    OutlinedTextField(privateKey, { privateKey = it }, label = { Text(stringResource(R.string.private_key_content)) }, modifier = Modifier.fillMaxWidth(), minLines = 4, shape = LuminControlShape, colors = luminTextFieldColors())
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.private_key_passphrase_optional)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors(), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    LuminPrimaryButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                Credential(
                                    id = initial.id.ifBlank { UUID.randomUUID().toString() },
                                    name = name,
                                    username = username,
                                    authMethod = authMethod,
                                    password = password,
                                    privateKey = privateKey,
                                    passphrase = passphrase,
                                    lastModified = System.currentTimeMillis(),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.save_credential)) }
                }
            }
        }
    }
}
