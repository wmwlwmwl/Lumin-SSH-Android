package com.lumin.ssh.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncSettingsPage(
    store: LocalStore,
    connections: List<Connection>,
    credentials: List<Credential>,
    quickCommandsRaw: String,
    proxyNodes: List<ProxyNode>,
    aiProvidersRaw: String,
    aiGlobalSettingsRaw: String,
    onBack: () -> Unit,
    onRestored: (SyncSnapshot) -> Unit,
    onSync: suspend (
        sync: suspend () -> SyncHelper.SyncOutcome,
        retry: suspend (String) -> SyncHelper.SyncOutcome,
    ) -> SyncHelper.SyncOutcome?,
    syncBusy: Boolean,
    onSynced: (List<Connection>, List<Credential>, String, List<ProxyNode>, String, String) -> Unit,
    onMessage: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    var provider by remember { mutableStateOf("webdav") }
    val scope = rememberCoroutineScope()
    var webdavEditing by remember { mutableStateOf(false) }
    var r2Editing by remember { mutableStateOf(false) }
    var autoSyncEnabled by remember { mutableStateOf(store.loadAutoSyncEnabled()) }
    var syncMode by remember { mutableStateOf(store.loadSyncMode()) }
    var syncing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var passwordChanging by remember { mutableStateOf(false) }
    val recoveryPassword = store.loadRecoveryPassword()
    var recoveryPasswordDraft by remember { mutableStateOf("") }
    var showRecoveryPasswordDialog by remember { mutableStateOf(false) }
    var showDisableEncryptionDialog by remember { mutableStateOf(false) }
    var pendingRecoveryPasswordReset by remember { mutableStateOf<String?>(null) }
    var backupList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedBackup by remember { mutableStateOf<String?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showRestoreProviderDialog by remember { mutableStateOf(false) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restorePasswordInput by remember { mutableStateOf("") }
    var restoreProvider by remember { mutableStateOf<String?>(null) }
    var failedRestoreProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingHostKey by remember { mutableStateOf<Pair<HostKeyConfirm, CompletableDeferred<HostKeyAction>>?>(null) }
    var pendingCertificate by remember { mutableStateOf<Pair<FtpsCertificateConfirm, CompletableDeferred<HostKeyAction>>?>(null) }
    val providerLabels = listOf("webdav" to "WebDAV", "r2" to "R2 (S3)", "ftp" to "FTP", "sftp" to "SFTP")
    val context = LocalContext.current
    val trustInteraction = remember {
        SyncTrustInteraction(
            confirmHostKey = { info ->
                withContext(Dispatchers.Main) {
                    val result = CompletableDeferred<HostKeyAction>()
                    pendingHostKey = info to result
                    result.await()
                }
            },
            confirmFtpsCertificate = { info ->
                withContext(Dispatchers.Main) {
                    val result = CompletableDeferred<HostKeyAction>()
                    pendingCertificate = info to result
                    result.await()
                }
            },
        )
    }

    fun loadRestoreBackups(providerId: String) {
        if (syncBusy || passwordChanging) return
        restoring = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    SyncHelper.providerInstance(store, providerId, trustInteraction).first.listBackupNames()
                }
            }.onSuccess { names ->
                if (names.isEmpty()) {
                    failedRestoreProviders = failedRestoreProviders + providerId
                    onMessage(context.getString(R.string.no_cloud_backups_retry))
                    showRestoreProviderDialog = syncMode == "all" && SyncHelper.providersFor(store).any { it !in failedRestoreProviders }
                } else {
                    restoreProvider = providerId
                    backupList = names
                    selectedBackup = names.first()
                    showRestoreProviderDialog = false
                    showRestoreDialog = true
                }
            }.onFailure {
                failedRestoreProviders = failedRestoreProviders + providerId
                onMessage(context.getString(R.string.fetch_backup_list_failed_retry, context.userErrorText(it)))
                showRestoreProviderDialog = syncMode == "all" && SyncHelper.providersFor(store).any { it !in failedRestoreProviders }
            }
            restoring = false
        }
    }

    fun restoreSelectedBackup(password: String, promptOnPasswordFailure: Boolean) {
        val name = selectedBackup ?: return
        val providerId = restoreProvider ?: return
        restoring = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    SyncHelper.providerInstance(store, providerId, trustInteraction).first.restoreSnapshot(name, password)
                }
            }.onSuccess { snapshot ->
                onRestored(snapshot)
                onMessage(context.getString(R.string.restore_completed, snapshot.connections.size, snapshot.credentials.size))
                showRestoreDialog = false
                showRestorePasswordDialog = false
                restorePasswordInput = ""
            }.onFailure {
                if (promptOnPasswordFailure && it is RecoveryPasswordException) {
                    restorePasswordInput = ""
                    showRestoreDialog = false
                    showRestorePasswordDialog = true
                } else {
                    failedRestoreProviders = failedRestoreProviders + providerId
                    showRestoreDialog = false
                    showRestorePasswordDialog = false
                    restorePasswordInput = ""
                    showRestoreProviderDialog = syncMode == "all" && SyncHelper.providersFor(store).any { it !in failedRestoreProviders }
                    onMessage(context.getString(R.string.restore_failed_retry, context.userErrorText(it)))
                }
            }
            restoring = false
        }
    }

    fun changeRecoveryPassword(password: String) {
        passwordChanging = true
        scope.launch {
            runCatching {
                SyncHelper.changeRecoveryPassword(
                    store, password, connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction,
                )
            }.onSuccess { snapshot ->
                onSynced(snapshot.connections, snapshot.credentials, snapshot.quickCommands, snapshot.proxyNodes, snapshot.aiProvidersRaw, snapshot.aiGlobalSettingsRaw)
                recoveryPasswordDraft = ""
                showRecoveryPasswordDialog = false
                showDisableEncryptionDialog = false
                onMessage(context.getString(if (password.trim().isBlank()) R.string.recovery_password_cleared else R.string.recovery_password_saved))
            }.onFailure {
                if (it is RecoveryPasswordResetRequiredException) {
                    pendingRecoveryPasswordReset = password
                    showRecoveryPasswordDialog = false
                    showDisableEncryptionDialog = false
                } else {
                    onMessage(context.getString(if (password.isBlank()) R.string.clear_recovery_password_failed else R.string.save_recovery_password_failed, context.userErrorText(it)))
                }
            }
            passwordChanging = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(LuminColors.SurfaceBase).padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LuminPageHeader(
            title = stringResource(R.string.sync_and_cloud),
            subtitle = stringResource(R.string.sync_subtitle),
            onBack = onBack,
            backLabel = stringResource(R.string.back),
        )

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auto_sync), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = autoSyncEnabled, enabled = !syncBusy && !passwordChanging, onCheckedChange = {
                        autoSyncEnabled = it
                        store.saveAutoSyncEnabled(it)
                    })
                }
                Text(stringResource(R.string.auto_sync_hint), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                Text(stringResource(R.string.auto_sync_mode), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.auto_sync_mode_hint), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("webdav" to "WebDAV", "r2" to "R2 (S3)", "ftp" to "FTP", "sftp" to "SFTP", "all" to stringResource(R.string.all)).forEach { (id, label) ->
                        if (syncMode == id) {
                            LuminPrimaryButton(enabled = !syncBusy && !passwordChanging, onClick = { syncMode = id; store.saveSyncMode(id) }){ Text(label) }
                        } else {
                            LuminSecondaryButton(enabled = !syncBusy && !passwordChanging, onClick = { syncMode = id; store.saveSyncMode(id) }){ Text(label) }
                        }
                    }
                }
            }
        }

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sync_encryption), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (recoveryPassword.isBlank()) stringResource(R.string.unencrypted) else stringResource(R.string.encrypted), color = if (recoveryPassword.isBlank()) LuminColors.Danger else LuminColors.Accent)
                }
                Text(
                    if (recoveryPassword.isBlank()) stringResource(R.string.sync_encryption_off_hint)
                    else stringResource(R.string.sync_encryption_on_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LuminColors.Accent,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminPrimaryButton(enabled = !syncBusy && !passwordChanging && !syncing && !restoring, onClick = {
                        recoveryPasswordDraft = ""
                        showRecoveryPasswordDialog = true
                    }){ Text(if (recoveryPassword.isBlank()) stringResource(R.string.enable_encryption) else stringResource(R.string.change_password)) }
                    if (recoveryPassword.isNotBlank()) {
                        LuminSecondaryButton(enabled = !syncBusy && !passwordChanging && !syncing && !restoring, onClick = { showDisableEncryptionDialog = true }){ Text(stringResource(R.string.disable_encryption)) }
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("webdav" to "WebDAV", "r2" to "R2 (S3)", "ftp" to "FTP", "sftp" to "SFTP").forEach { (id, label) ->
                if (provider == id) {
                    LuminPrimaryButton(enabled = !syncBusy && !passwordChanging && !syncing && !restoring, onClick = { provider = id }){ Text(label) }
                } else {
                    LuminSecondaryButton(enabled = !syncBusy && !passwordChanging && !syncing && !restoring, onClick = { provider = id }){ Text(label) }
                }
            }
        }

        when (provider) {
            "webdav" -> {
                WebDavConfigCard(store, webdavEditing, { webdavEditing = it }, onMessage)
                if (store.loadWebDavConfig().url.trim().startsWith("http://", true)) Text(stringResource(R.string.webdav_http_warning), color = LuminColors.Danger)
            }
            "r2" -> R2ConfigCard(store, r2Editing, { r2Editing = it }, onMessage)
            "ftp" -> store.loadFtpConfig().let { c ->
                HostPortConfigCard(
                    title = "FTP", defaultPort = 21,
                    initialHost = c.host, initialPort = c.port, initialUsername = c.username,
                    initialPassword = c.password, initialRemoteDir = c.remoteDir, initialMaxBackups = c.maxBackups,
                    initialFtpMode = c.mode, supportsFtpMode = true,
                    onTest = { h, p, u, pw, d, _, _, _, mode ->
                        withContext(Dispatchers.IO) { FtpSync(h, p, u, pw, d, mode, store, trustInteraction.confirmFtpsCertificate).testConnection() }
                    },
                    onSave = { h, p, u, pw, d, m, _, _, mode ->
                        withContext(Dispatchers.IO) { FtpSync(h, p, u, pw, d, mode, store, trustInteraction.confirmFtpsCertificate).testConnection() }
                        store.saveFtpConfig(FtpConfig(mode, h, p, u, pw, d, m))
                    },
                    onMessage = onMessage,
                )
                Text(if (c.mode == FTP_MODE_PLAIN) stringResource(R.string.ftp_plain_warning_short) else stringResource(R.string.ftps_current_short), color = if (c.mode == FTP_MODE_PLAIN) LuminColors.Danger else LuminColors.Accent, style = MaterialTheme.typography.bodySmall)
            }
            "sftp" -> store.loadSftpConfig().let { c ->
                HostPortConfigCard(
                    title = "SFTP", defaultPort = 22,
                    initialHost = c.host, initialPort = c.port, initialUsername = c.username,
                    initialPassword = c.password, initialRemoteDir = c.remoteDir, initialMaxBackups = c.maxBackups,
                    initialPrivateKey = c.privateKey, initialPassphrase = c.passphrase, supportsKeyAuth = true,
                    onTest = { h, p, u, pw, d, _, pk, pp, _ ->
                        withContext(Dispatchers.IO) { SftpSync(store, h, p, u, pw, pk, pp, d, trustInteraction.confirmHostKey).testConnection() }
                    },
                    onSave = { h, p, u, pw, d, m, pk, pp, _ ->
                        withContext(Dispatchers.IO) { SftpSync(store, h, p, u, pw, pk, pp, d, trustInteraction.confirmHostKey).testConnection() }
                        store.saveSftpConfig(SftpConfig(h, p, u, pw, pk, pp, d, m))
                    },
                    onMessage = onMessage,
                )
            }
        }

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cloud_sync), style = MaterialTheme.typography.titleMedium)
                Text(if (recoveryPassword.isNotBlank()) stringResource(R.string.cloud_sync_encrypted_hint) else stringResource(R.string.cloud_sync_plain_hint), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)

                if (autoSyncEnabled) {
                    Text(stringResource(R.string.auto_cloud_backup_enabled), color = LuminColors.Success, style = MaterialTheme.typography.bodySmall)
                }

                val lastSync = store.loadLastSyncTime()
                if (lastSync > 0) {
                    Text(stringResource(R.string.last_sync_time, formatSyncTime(lastSync)), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminPrimaryButton(
                        enabled = !syncBusy && !syncing && !restoring && !passwordChanging,
                        onClick = {
                            syncing = true
                            scope.launch {
                                val outcome = onSync(
                                    { SyncHelper.autoSync(store, connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction) },
                                    { password -> SyncHelper.syncWithRecoveryPassword(store, password, connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction) },
                                )
                                syncing = false
                                if (outcome?.failure != null) {
                                    onMessage(context.getString(R.string.sync_failed, context.userErrorText(outcome.failure)))
                                } else if (outcome?.action == "skip") {
                                    onSynced(outcome.mergedConnections, outcome.mergedCredentials, outcome.mergedQuickCommands, outcome.mergedProxyNodes, outcome.aiProvidersRaw, outcome.aiGlobalSettingsRaw)
                                    onMessage(context.getString(R.string.sync_already_current))
                                } else if (outcome != null) {
                                    onSynced(outcome.mergedConnections, outcome.mergedCredentials, outcome.mergedQuickCommands, outcome.mergedProxyNodes, outcome.aiProvidersRaw, outcome.aiGlobalSettingsRaw)
                                    onMessage(context.getString(R.string.sync_completed, outcome.action))
                                }
                            }
                        }
                    ){ Text(if (syncing) stringResource(R.string.syncing) else stringResource(R.string.merge_sync)) }

                    LuminPrimaryButton(
                        enabled = !syncBusy && !syncing && !restoring && !passwordChanging,
                        onClick = {
                            failedRestoreProviders = emptySet()
                            if (syncMode == "all") {
                                val availableProviders = SyncHelper.providersFor(store)
                                if (availableProviders.size == 1) loadRestoreBackups(availableProviders.first()) else showRestoreProviderDialog = true
                            } else {
                                loadRestoreBackups(syncMode)
                            }
                        }
                    ){ Text(if (restoring) stringResource(R.string.fetching) else stringResource(R.string.restore_from_cloud)) }
                }
            }
        }
    }

    pendingHostKey?.let { (info, result) ->
        AlertDialog(
            onDismissRequest = { if (!result.isCompleted) result.complete(HostKeyAction.Cancel); pendingHostKey = null },
            title = { Text(if (info.changed) stringResource(R.string.sftp_host_key_changed_title) else stringResource(R.string.sftp_host_key_confirm_title)) },
            text = { Text(stringResource(if (info.changed) R.string.sftp_host_key_message_changed else R.string.sftp_host_key_message_first, info.host, info.port, info.fingerprint)) },
            confirmButton = { TextButton(onClick = { result.complete(HostKeyAction.AcceptAndSave); pendingHostKey = null }) { Text(stringResource(R.string.accept_and_save)) } },
            dismissButton = { TextButton(onClick = { result.complete(HostKeyAction.Cancel); pendingHostKey = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pendingCertificate?.let { (info, result) ->
        AlertDialog(
            onDismissRequest = { if (!result.isCompleted) result.complete(HostKeyAction.Cancel); pendingCertificate = null },
            title = { Text(if (info.previousFingerprint.isNotBlank()) stringResource(R.string.ftps_cert_changed_title) else stringResource(R.string.ftps_cert_confirm_title)) },
            text = { Text(stringResource(if (info.previousFingerprint.isNotBlank()) R.string.ftps_cert_message_changed else R.string.ftps_cert_message_untrusted, info.host, info.port, info.fingerprint, info.subject, info.issuer, info.notBefore, info.notAfter)) },
            confirmButton = { TextButton(onClick = { result.complete(HostKeyAction.AcceptAndSave); pendingCertificate = null }) { Text(stringResource(R.string.accept_and_save)) } },
            dismissButton = { TextButton(onClick = { result.complete(HostKeyAction.Cancel); pendingCertificate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showRecoveryPasswordDialog) {
        AlertDialog(
            onDismissRequest = { if (!passwordChanging) { recoveryPasswordDraft = ""; showRecoveryPasswordDialog = false } },
            title = { Text(if (recoveryPassword.isBlank()) stringResource(R.string.enable_sync_encryption) else stringResource(R.string.change_sync_encryption_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.save_password_warning))
                    OutlinedTextField(
                        value = recoveryPasswordDraft,
                        onValueChange = { recoveryPasswordDraft = it },
                        label = { Text(stringResource(R.string.recovery_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !passwordChanging, onClick = {
                    if (recoveryPasswordDraft.isBlank()) {
                        onMessage(context.getString(R.string.enter_recovery_password))
                    } else {
                        changeRecoveryPassword(recoveryPasswordDraft)
                    }
                }) { Text(if (passwordChanging) stringResource(R.string.saving) else stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(enabled = !passwordChanging, onClick = { recoveryPasswordDraft = ""; showRecoveryPasswordDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showDisableEncryptionDialog) {
        AlertDialog(
            onDismissRequest = { if (!passwordChanging) showDisableEncryptionDialog = false },
            title = { Text(stringResource(R.string.disable_sync_encryption_title)) },
            text = { Text(stringResource(R.string.disable_sync_encryption_warning)) },
            confirmButton = {
                TextButton(enabled = !passwordChanging, onClick = { changeRecoveryPassword("") }) {
                    Text(if (passwordChanging) stringResource(R.string.clearing) else stringResource(R.string.disable_encryption))
                }
            },
            dismissButton = { TextButton(enabled = !passwordChanging, onClick = { showDisableEncryptionDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pendingRecoveryPasswordReset?.let { password ->
        AlertDialog(
            onDismissRequest = { if (!passwordChanging) pendingRecoveryPasswordReset = null },
            title = { Text(stringResource(R.string.confirm_force_reset_recovery_password)) },
            text = { Text(stringResource(R.string.force_reset_recovery_password_warning)) },
            confirmButton = {
                TextButton(enabled = !passwordChanging, onClick = {
                    passwordChanging = true
                    scope.launch {
                        runCatching {
                            SyncHelper.resetRecoveryPassword(
                                store, password, connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction,
                            )
                        }.onSuccess { snapshot ->
                            onSynced(snapshot.connections, snapshot.credentials, snapshot.quickCommands, snapshot.proxyNodes, snapshot.aiProvidersRaw, snapshot.aiGlobalSettingsRaw)
                            pendingRecoveryPasswordReset = null
                            recoveryPasswordDraft = ""
                            onMessage(context.getString(if (password.trim().isBlank()) R.string.recovery_password_cleared else R.string.recovery_password_saved))
                        }.onFailure {
                            onMessage(context.getString(if (password.isBlank()) R.string.clear_recovery_password_failed else R.string.save_recovery_password_failed, context.userErrorText(it)))
                        }
                        passwordChanging = false
                    }
                }) { Text(if (passwordChanging) stringResource(R.string.resetting) else stringResource(R.string.overwrite_cloud_with_local_data)) }
            },
            dismissButton = {
                TextButton(enabled = !passwordChanging, onClick = { pendingRecoveryPasswordReset = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showRestoreProviderDialog) {
        val availableProviders = SyncHelper.providersFor(store).filter { it !in failedRestoreProviders }
        AlertDialog(
            onDismissRequest = { if (!syncBusy && !passwordChanging && !restoring) showRestoreProviderDialog = false },
            title = { Text(stringResource(R.string.select_restore_provider)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableProviders.forEach { id ->
                        val label = providerLabels.firstOrNull { it.first == id }?.second ?: id
                        LuminSecondaryButton(onClick = { loadRestoreBackups(id) }, enabled = !syncBusy && !passwordChanging && !restoring, modifier = Modifier.fillMaxWidth()){ Text(label) }
                    }
                    if (availableProviders.isEmpty()) Text(stringResource(R.string.no_available_cloud_provider))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(enabled = !syncBusy && !passwordChanging && !restoring, onClick = { showRestoreProviderDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { if (!syncBusy && !passwordChanging && !restoring) showRestoreDialog = false },
            title = { Text(stringResource(R.string.select_cloud_backup)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    backupList.forEach { name ->
                        if (selectedBackup == name) {
                            LuminPrimaryButton(onClick = { selectedBackup = name }, modifier = Modifier.fillMaxWidth()){ Text(name) }
                        } else {
                            LuminSecondaryButton(onClick = { selectedBackup = name }, modifier = Modifier.fillMaxWidth()){ Text(name) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !syncBusy && !passwordChanging && !restoring && selectedBackup != null,
                    onClick = { restoreSelectedBackup(store.loadRecoveryPassword(), promptOnPasswordFailure = true) },
                ) { Text(if (restoring) stringResource(R.string.restoring) else stringResource(R.string.restore)) }
            },
            dismissButton = {
                TextButton(enabled = !syncBusy && !passwordChanging && !restoring, onClick = { showRestoreDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showRestorePasswordDialog) {
        AlertDialog(
            onDismissRequest = { if (!syncBusy && !passwordChanging && !restoring) { restorePasswordInput = ""; showRestorePasswordDialog = false } },
            title = { Text(stringResource(R.string.enter_restore_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restore_password_hint))
                    OutlinedTextField(
                        value = restorePasswordInput,
                        onValueChange = { restorePasswordInput = it },
                        label = { Text(stringResource(R.string.recovery_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !syncBusy && !passwordChanging && !restoring,
                    onClick = {
                        if (restorePasswordInput.isBlank()) {
                            restorePasswordInput = ""
                            onMessage(context.getString(R.string.enter_recovery_password))
                        } else {
                            restoreSelectedBackup(restorePasswordInput, promptOnPasswordFailure = false)
                        }
                    },
                ) { Text(if (restoring) stringResource(R.string.restoring) else stringResource(R.string.restore)) }
            },
            dismissButton = { TextButton(enabled = !syncBusy && !passwordChanging && !restoring, onClick = { restorePasswordInput = ""; showRestorePasswordDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun WebDavConfigCard(store: LocalStore, editing: Boolean, onEditingChange: (Boolean) -> Unit, onMessage: (String) -> Unit) {
    val cfg = store.loadWebDavConfig()
    val configured = cfg.url.isNotBlank() && cfg.username.isNotBlank()

    var url by remember(cfg) { mutableStateOf(cfg.url) }
    var username by remember(cfg) { mutableStateOf(cfg.username) }
    var password by remember(cfg) { mutableStateOf(cfg.password) }
    var remotePath by remember(cfg) { mutableStateOf(cfg.remotePath) }
    var maxBackups by remember(cfg) { mutableStateOf(cfg.maxBackups.toString()) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LuminCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WebDAV", style = MaterialTheme.typography.titleMedium)

            if (configured && !editing) {
                Text(stringResource(R.string.endpoint, cfg.url), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                Text(stringResource(R.string.user_label, cfg.username), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                Text(stringResource(R.string.directory_label, cfg.remotePath), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                LuminPrimaryButton(onClick = { onEditingChange(true) }){ Text(stringResource(R.string.change_config)) }
            } else {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.webdav_url)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password_or_token)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(remotePath, { remotePath = it }, label = { Text(stringResource(R.string.remote_directory)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(maxBackups, { maxBackups = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.backup_retention_count)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminSecondaryButton(enabled = !testing && !saving, onClick = {
                        testing = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { WebDavSync(url, username, password, remotePath).testConnection() } }
                                .onSuccess { onMessage(context.getString(R.string.webdav_test_success)) }
                                .onFailure { onMessage(context.getString(R.string.webdav_test_failed, context.userErrorText(it))) }
                            testing = false
                        }
                    }){ Text(if (testing) stringResource(R.string.testing) else stringResource(R.string.test_connection)) }
                    LuminPrimaryButton(enabled = !testing && !saving, onClick = {
                        saving = true
                        store.saveWebDavConfig(WebDavConfig(url, username, password, remotePath, maxBackups.toIntOrNull() ?: 0))
                        onEditingChange(false)
                        onMessage(context.getString(R.string.webdav_config_saved))
                        saving = false
                    }){ Text(stringResource(R.string.save_config)) }
                }
                if (editing) {
                    LuminSecondaryButton(enabled = !testing && !saving, onClick = { onEditingChange(false) }){ Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
fun R2ConfigCard(store: LocalStore, editing: Boolean, onEditingChange: (Boolean) -> Unit, onMessage: (String) -> Unit) {
    val cfg = store.loadR2Config()
    val configured = cfg.accessKeyId.isNotBlank() && cfg.secretAccessKey.isNotBlank() && cfg.bucket.isNotBlank() && cfg.endpoint.isNotBlank()

    var accessKeyId by remember(cfg) { mutableStateOf(cfg.accessKeyId) }
    var secretAccessKey by remember(cfg) { mutableStateOf(cfg.secretAccessKey) }
    var bucket by remember(cfg) { mutableStateOf(cfg.bucket) }
    var endpoint by remember(cfg) { mutableStateOf(cfg.endpoint) }
    var region by remember(cfg) { mutableStateOf(cfg.region) }
    var prefix by remember(cfg) { mutableStateOf(cfg.prefix) }
    var maxBackups by remember(cfg) { mutableStateOf(cfg.maxBackups.toString()) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LuminCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("R2 (S3)", style = MaterialTheme.typography.titleMedium)

            if (configured && !editing) {
                Text(stringResource(R.string.r2_bucket_label, cfg.bucket), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                Text(stringResource(R.string.endpoint, cfg.endpoint), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                Text(stringResource(R.string.prefix_label, cfg.prefix), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                LuminPrimaryButton(onClick = { onEditingChange(true) }){ Text(stringResource(R.string.change_config)) }
            } else {
                OutlinedTextField(accessKeyId, { accessKeyId = it }, label = { Text(stringResource(R.string.access_key_id)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secretAccessKey, { secretAccessKey = it }, label = { Text(stringResource(R.string.secret_access_key)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bucket, { bucket = it }, label = { Text(stringResource(R.string.bucket)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.endpoint_address)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(region, { region = it }, label = { Text(stringResource(R.string.region)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(prefix, { prefix = it }, label = { Text(stringResource(R.string.prefix)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(maxBackups, { maxBackups = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.backup_retention_count)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminSecondaryButton(enabled = !testing && !saving, onClick = {
                        testing = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { R2Sync(accessKeyId, secretAccessKey, bucket, endpoint, region, prefix).testConnection() } }
                                .onSuccess { onMessage(context.getString(R.string.r2_test_success)) }
                                .onFailure { onMessage(context.getString(R.string.r2_test_failed, context.userErrorText(it))) }
                            testing = false
                        }
                    }){ Text(if (testing) stringResource(R.string.testing) else stringResource(R.string.test_connection)) }
                    LuminPrimaryButton(enabled = !testing && !saving, onClick = {
                        saving = true
                        store.saveR2Config(R2Config(accessKeyId, secretAccessKey, bucket, endpoint, region, prefix, maxBackups.toIntOrNull() ?: 0))
                        onEditingChange(false)
                        onMessage(context.getString(R.string.r2_config_saved))
                        saving = false
                    }){ Text(stringResource(R.string.save_config)) }
                }
                if (editing) {
                    LuminSecondaryButton(enabled = !testing && !saving, onClick = { onEditingChange(false) }){ Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

private fun formatSyncTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

@Composable
fun HostPortConfigCard(
    title: String,
    defaultPort: Int,
    initialHost: String,
    initialPort: Int,
    initialUsername: String,
    initialPassword: String,
    initialRemoteDir: String,
    initialMaxBackups: Int,
    initialPrivateKey: String = "",
    initialPassphrase: String = "",
    initialFtpMode: String = FTP_MODE_EXPLICIT_TLS,
    supportsKeyAuth: Boolean = false,
    supportsFtpMode: Boolean = false,
    onTest: suspend (host: String, port: Int, username: String, password: String, remoteDir: String, maxBackups: Int, privateKey: String, passphrase: String, ftpMode: String) -> Unit,
    onSave: suspend (host: String, port: Int, username: String, password: String, remoteDir: String, maxBackups: Int, privateKey: String, passphrase: String, ftpMode: String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var port by remember(initialPort) { mutableStateOf(initialPort.toString()) }
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }
    var privateKey by remember(initialPrivateKey) { mutableStateOf(initialPrivateKey) }
    var passphrase by remember(initialPassphrase) { mutableStateOf(initialPassphrase) }
    var useKeyAuth by remember { mutableStateOf(initialPrivateKey.isNotBlank()) }
    var ftpMode by remember(initialFtpMode) { mutableStateOf(normalizeFtpMode(initialFtpMode)) }
    var remoteDir by remember(initialRemoteDir) { mutableStateOf(initialRemoteDir) }
    var maxBackups by remember(initialMaxBackups) { mutableStateOf(initialMaxBackups.toString()) }
    var editing by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configured = initialHost.isNotBlank() && initialUsername.isNotBlank()

    LuminCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            if (configured && !editing) {
                Text(stringResource(R.string.host_label, initialHost), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                Text(stringResource(R.string.user_label, initialUsername), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                Text(stringResource(R.string.directory_label, initialRemoteDir), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                if (supportsFtpMode) {
                    Text(stringResource(R.string.mode_label, if (initialFtpMode == FTP_MODE_PLAIN) stringResource(R.string.plain_ftp_unsafe) else stringResource(R.string.explicit_ftps_recommended)), style = MaterialTheme.typography.bodySmall, color = if (initialFtpMode == FTP_MODE_PLAIN) LuminColors.Danger else LuminColors.Accent)
                }
                if (supportsKeyAuth && initialPrivateKey.isNotBlank()) {
                    Text(stringResource(R.string.auth_key_label), style = MaterialTheme.typography.bodySmall, color = LuminColors.Accent)
                }
                LuminPrimaryButton(onClick = { editing = true }){ Text(stringResource(R.string.change_config)) }
            } else {
                if (supportsFtpMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = ftpMode == FTP_MODE_EXPLICIT_TLS, onClick = { ftpMode = FTP_MODE_EXPLICIT_TLS }, label = { Text(stringResource(R.string.explicit_ftps_recommended)) })
                        FilterChip(selected = ftpMode == FTP_MODE_PLAIN, onClick = { ftpMode = FTP_MODE_PLAIN }, label = { Text(stringResource(R.string.plain_ftp_unsafe)) })
                    }
                    if (ftpMode == FTP_MODE_PLAIN) {
                        Text(stringResource(R.string.plain_ftp_warning), color = LuminColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.server_address)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.port)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth())
                if (supportsKeyAuth) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useKeyAuth, onClick = { useKeyAuth = false }, label = { Text(stringResource(R.string.password)) })
                        FilterChip(selected = useKeyAuth, onClick = { useKeyAuth = true }, label = { Text(stringResource(R.string.key)) })
                    }
                }
                if (supportsKeyAuth && useKeyAuth) {
                    OutlinedTextField(privateKey, { privateKey = it }, label = { Text(stringResource(R.string.private_key_pem)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.private_key_passphrase_optional_parentheses)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(remoteDir, { remoteDir = it }, label = { Text(stringResource(R.string.remote_directory)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(maxBackups, { maxBackups = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.backup_retention_count)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminSecondaryButton(enabled = !testing && !saving, onClick = {
                        testing = true
                        scope.launch {
                            runCatching {
                                onTest(host, port.toIntOrNull() ?: defaultPort, username, if (useKeyAuth) "" else password, remoteDir, maxBackups.toIntOrNull() ?: 0, if (useKeyAuth) privateKey else "", if (useKeyAuth) passphrase else "", ftpMode)
                            }.onSuccess { onMessage(context.getString(R.string.test_success, title)) }
                                .onFailure { onMessage(context.getString(R.string.test_failed, title, context.userErrorText(it))) }
                            testing = false
                        }
                    }){ Text(if (testing) stringResource(R.string.testing) else stringResource(R.string.test_connection)) }
                LuminPrimaryButton(enabled = !saving && !testing, onClick = {
                    saving = true
                    scope.launch {
                        runCatching {
                            onSave(host, port.toIntOrNull() ?: defaultPort, username, if (useKeyAuth) "" else password, remoteDir, maxBackups.toIntOrNull() ?: 0, if (useKeyAuth) privateKey else "", if (useKeyAuth) passphrase else "", ftpMode)
                        }.onSuccess {
                            editing = false
                            onMessage(context.getString(R.string.config_saved, title))
                        }.onFailure { onMessage(context.getString(R.string.config_save_failed, title, context.userErrorText(it))) }
                        saving = false
                    }
                }){ Text(if (saving) stringResource(R.string.validating) else stringResource(R.string.save_config)) }
                }
                if (editing) {
                    LuminSecondaryButton(enabled = !testing && !saving, onClick = { editing = false }){ Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}
