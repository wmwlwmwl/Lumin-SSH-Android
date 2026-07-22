package com.lumin.ssh.android

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun LuminLiteApp(
    store: LocalStore,
    requestedSessionId: String?,
    appLanguage: String,
    appTheme: String,
    runStartupSync: Boolean,
    onAppLanguageChange: (String) -> Unit,
    onAppThemeChange: (String) -> Unit,
) {
    var connections by remember { mutableStateOf(store.loadConnections()) }
    var credentials by remember { mutableStateOf(store.loadCredentials()) }
    var proxyNodes by remember { mutableStateOf(store.loadProxyNodes()) }
    var quickCommandsRaw by remember { mutableStateOf(store.loadQuickCommandsRaw()) }
    var aiProvidersRaw by remember { mutableStateOf(store.loadAiProvidersRaw()) }
    var aiGlobalSettingsRaw by remember { mutableStateOf(store.loadAiGlobalSettingsRaw()) }
    var message by remember { mutableStateOf("") }
    var pendingExportContent by remember { mutableStateOf("") }
    var pendingEncryptedImport by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var recoveryPasswordInput by remember { mutableStateOf("") }
    var recoveryPasswordAttempt by remember { mutableStateOf(0) }
    var pendingRecoveryPasswordSync by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var syncBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recoveryPasswordFailedMessage = stringResource(R.string.recovery_password_failed_three_times)
    fun savePcRawFields(aiProviders: String, aiGlobalSettings: String) {
        aiProvidersRaw = aiProviders
        store.saveAiProvidersRaw(aiProviders)
        aiGlobalSettingsRaw = aiGlobalSettings
        store.saveAiGlobalSettingsRaw(aiGlobalSettings)
    }
    suspend fun syncWithPasswordPrompt(
        sync: suspend () -> SyncHelper.SyncOutcome,
        retry: suspend (String) -> SyncHelper.SyncOutcome,
    ): SyncHelper.SyncOutcome? {
        // 正在同步时返回可识别的 skip，避免 null 导致上层既不弹窗也不 Toast
        if (syncBusy) {
            return SyncHelper.SyncOutcome(
                "skip", connections, credentials, quickCommandsRaw, proxyNodes,
                aiProvidersRaw, aiGlobalSettingsRaw, SyncInProgressException(),
            )
        }
        syncBusy = true
        try {
            var outcome = sync()
            if (outcome.failure !is RecoveryPasswordException) return outcome
            repeat(3) { attempt ->
                recoveryPasswordAttempt = attempt
                recoveryPasswordInput = ""
                val request = CompletableDeferred<String?>()
                pendingRecoveryPasswordSync = request
                val password = request.await()
                pendingRecoveryPasswordSync = null
                recoveryPasswordInput = ""
                if (password == null) return null
                outcome = retry(password)
                if (outcome.failure !is RecoveryPasswordException) return outcome
            }
            message = recoveryPasswordFailedMessage
            return null
        } finally {
            pendingRecoveryPasswordSync = null
            recoveryPasswordInput = ""
            recoveryPasswordAttempt = 0
            syncBusy = false
        }
    }
    fun applySyncOutcome(outcome: SyncHelper.SyncOutcome, expectedQuickCommands: String? = null) {
        // 失败/需手动确认：不要用空合并结果覆盖界面上的服务器列表
        if (outcome.failure != null) return
        if (outcome.needsManualTombstoneConfirm) return
        if (expectedQuickCommands != null && store.loadQuickCommandsRaw() != expectedQuickCommands) return
        connections = outcome.mergedConnections
        credentials = outcome.mergedCredentials
        proxyNodes = outcome.mergedProxyNodes
        quickCommandsRaw = outcome.mergedQuickCommands
        aiProvidersRaw = outcome.aiProvidersRaw
        aiGlobalSettingsRaw = outcome.aiGlobalSettingsRaw
    }
    var pendingRemoteDirMissingError by remember { mutableStateOf<String?>(null) }
    var pendingRemoteDirRecreate by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingRemoteDirRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingRemoteDirCancel by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun showRemoteDirMissingDialog(detail: String) {
        AppLog.w("SyncUI", "showRemoteDirMissingDialog: $detail")
        // 用当前磁盘最新连接列表，避免内存/界面状态过期
        val conns = store.loadConnections().ifEmpty { connections }
        val creds = store.loadCredentials().ifEmpty { credentials }
        val quick = store.loadQuickCommandsRaw().ifBlank { quickCommandsRaw }
        val proxies = store.loadProxyNodes().ifEmpty { proxyNodes }
        val aiProviders = store.loadAiProvidersRaw().ifBlank { aiProvidersRaw }
        val aiGlobal = store.loadAiGlobalSettingsRaw().ifBlank { aiGlobalSettingsRaw }
        pendingRemoteDirMissingError = detail.ifBlank { "远程同步目录可能已删除 (404)" }

        // 重新创建并重试：建目录 + 强制上传本地
        pendingRemoteDirRecreate = {
            scope.launch {
                syncBusy = true
                try {
                    AppLog.i("SyncUI", "recreate_and_retry clicked localConns=${conns.size}")
                    val forceOutcome = runCatching {
                        SyncHelper.ensureRemoteDirAndUploadLocal(
                            store, conns, creds, quick, proxies, aiProviders, aiGlobal,
                        )
                    }.getOrElse {
                        SyncHelper.SyncOutcome(
                            "error", conns, creds, quick, proxies, aiProviders, aiGlobal, it,
                        )
                    }
                    forceOutcome.let(::applySyncOutcome)
                    if (forceOutcome.failure != null && forceOutcome.action == "error") {
                        message = context.getString(
                            R.string.recreate_remote_dir_failed,
                            context.userErrorText(forceOutcome.failure),
                        )
                    } else if (forceOutcome.failure != null) {
                        message = context.getString(
                            R.string.sync_failed,
                            context.userErrorText(forceOutcome.failure),
                        )
                    } else {
                        message = context.getString(R.string.remote_dir_recreated_sync_ok)
                    }
                } finally {
                    syncBusy = false
                }
            }
        }

        // 重试：不重建目录，普通 autoSync（应对暂时 404 误判）
        pendingRemoteDirRetry = {
            scope.launch {
                AppLog.i("SyncUI", "retry_only clicked localConns=${conns.size}")
                val outcome = syncWithPasswordPrompt(
                    sync = {
                        SyncHelper.autoSync(
                            store, conns, creds, quick, proxies, aiProviders, aiGlobal,
                        )
                    },
                    retry = { password ->
                        SyncHelper.syncWithRecoveryPassword(
                            store, password, conns, creds, quick, proxies, aiProviders, aiGlobal,
                        )
                    },
                )
                outcome?.let(::applySyncOutcome)
                if (outcome?.failure != null) {
                    // 仍失败则再弹创建对话框（可能真是目录没了）
                    // 注意：此处用局部逻辑，避免 forward-reference 本地函数
                    val fail = outcome.failure
                    val d = listOfNotNull(fail.message, context.userErrorText(fail))
                        .filter { it.isNotBlank() }.distinct().joinToString(" | ")
                    val looksMissing = SyncHelper.looksLikeMissingRemoteDir(fail)
                        || d.contains("404")
                        || d.contains("列表失败")
                        || d.contains("远程目录")
                        || d.contains("不存在")
                        || d.contains("not found", ignoreCase = true)
                    if (looksMissing) {
                        showRemoteDirMissingDialog(d)
                    } else {
                        message = context.getString(R.string.sync_failed, context.userErrorText(fail))
                    }
                } else if (outcome != null) {
                    message = context.getString(R.string.sync_completed, outcome.action)
                }
            }
        }

        pendingRemoteDirCancel = { }
        // 同时 toast，避免用户以为「什么都没发生」
        message = context.getString(R.string.cloud_sync_failed_title) + ": " + pendingRemoteDirMissingError
    }

    fun reportAutomaticSyncFailure(outcome: SyncHelper.SyncOutcome?) {
        AppLog.i(
            "SyncUI",
            "reportAutomaticSyncFailure outcome=${outcome?.action} fail=${outcome?.failure?.javaClass?.simpleName}:${outcome?.failure?.message} tombstone=${outcome?.needsManualTombstoneConfirm}",
        )
        if (outcome == null) {
            AppLog.w("SyncUI", "reportAutomaticSyncFailure: outcome=null")
            return
        }
        // 墓碑需手动确认：只 toast，不走远程目录弹窗
        if (outcome.needsManualTombstoneConfirm) {
            message = context.getString(R.string.sync_tombstone_needs_manual)
            return
        }
        val failure = outcome.failure
        if (failure == null) {
            // 无 failure 的 skip/success 不提示
            if (outcome.action == "error") {
                message = context.getString(R.string.cloud_sync_failed_title)
            }
            return
        }
        if (failure is RecoveryPasswordException) return
        // 同步进行中：轻提示即可
        if (failure is SyncInProgressException) {
            message = context.getString(R.string.sync_in_progress)
            return
        }
        val detail = listOfNotNull(failure.message, context.userErrorText(failure))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
        // 任何 404 / 列表失败 / 目录不存在，都弹重建（不要依赖 WebDAV 字样）
        val looksMissing = SyncHelper.looksLikeMissingRemoteDir(failure)
            || detail.contains("404")
            || detail.contains("列表失败")
            || detail.contains("远程目录")
            || detail.contains("不存在")
            || detail.contains("PROPFIND", ignoreCase = true)
            || detail.contains("not found", ignoreCase = true)
            || detail.contains("no such file", ignoreCase = true)
            || (detail.contains("WebDAV", ignoreCase = true) && (
                detail.contains("403") || detail.contains("404") || detail.contains("失败")
                ))
        AppLog.i("SyncUI", "looksMissing=$looksMissing detail=$detail")
        if (looksMissing) {
            showRemoteDirMissingDialog(detail)
            return
        }
        // 其它失败：至少 Toast，不能静默
        message = context.getString(R.string.automatic_sync_stopped, detail)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && pendingExportContent.isNotBlank()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingExportContent.toByteArray()) }
            }.onSuccess { message = context.getString(R.string.export_completed) }
                .onFailure { message = context.getString(R.string.export_failed, context.userErrorText(it)) }
        }
        pendingExportContent = ""
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }.orEmpty()
            }.onSuccess { text ->
                runCatching {
                    val snapshot = parseSnapshotPayload(text, store.loadRecoveryPassword().ifBlank { null })
                    SyncHelper.markSnapshotRestored(snapshot, System.currentTimeMillis())
                }.onSuccess { restored ->
                    val merged = mergeImportedSnapshot(restored, connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw, System.currentTimeMillis())
                    connections = merged.connections
                    credentials = merged.credentials
                    proxyNodes = merged.proxyNodes
                    quickCommandsRaw = merged.quickCommands
                    savePcRawFields(merged.aiProvidersRaw, merged.aiGlobalSettingsRaw)
                    store.saveConnections(connections)
                    store.saveCredentials(credentials)
                    store.saveProxyNodes(proxyNodes)
                    store.saveQuickCommandsRaw(quickCommandsRaw)
                    message = context.getString(R.string.import_completed_count, merged.imported, merged.skipped)
                }.onFailure { err ->
                    // 仅密码类失败再弹窗；格式错误直接报错，避免无意义要密码
                    if (err is RecoveryPasswordException) {
                        pendingEncryptedImport = text
                        importPassword = ""
                        showImportPasswordDialog = true
                    } else {
                        message = context.getString(R.string.import_failed, context.userErrorText(err))
                    }
                }
            }.onFailure { message = context.getString(R.string.read_failed, context.userErrorText(it)) }
        }
    }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            message = ""
        }
    }
    LaunchedEffect(Unit) {
        val autoOn = store.loadAutoSyncEnabled()
        AppLog.i("SyncUI", "startupSync runStartupSync=$runStartupSync autoSync=$autoOn")
        if (runStartupSync && autoOn) {
            // 稍晚于首帧，避免和其它启动逻辑抢 syncBusy；失败必须弹窗
            delay(800)
            val outcome = try {
                syncWithPasswordPrompt(
                    sync = {
                        SyncHelper.autoSync(
                            store,
                            store.loadConnections().ifEmpty { connections },
                            store.loadCredentials().ifEmpty { credentials },
                            store.loadQuickCommandsRaw().ifBlank { quickCommandsRaw },
                            store.loadProxyNodes().ifEmpty { proxyNodes },
                            store.loadAiProvidersRaw().ifBlank { aiProvidersRaw },
                            store.loadAiGlobalSettingsRaw().ifBlank { aiGlobalSettingsRaw },
                        )
                    },
                    retry = { password ->
                        SyncHelper.syncWithRecoveryPassword(
                            store,
                            password,
                            store.loadConnections().ifEmpty { connections },
                            store.loadCredentials().ifEmpty { credentials },
                            store.loadQuickCommandsRaw().ifBlank { quickCommandsRaw },
                            store.loadProxyNodes().ifEmpty { proxyNodes },
                            store.loadAiProvidersRaw().ifBlank { aiProvidersRaw },
                            store.loadAiGlobalSettingsRaw().ifBlank { aiGlobalSettingsRaw },
                        )
                    },
                )
            } catch (t: Throwable) {
                AppLog.e("SyncUI", "startupSync threw", t)
                SyncHelper.SyncOutcome(
                    "error", connections, credentials, quickCommandsRaw, proxyNodes,
                    aiProvidersRaw, aiGlobalSettingsRaw, t,
                )
            }
            AppLog.i(
                "SyncUI",
                "startupSync done action=${outcome?.action} fail=${outcome?.failure?.message}",
            )
            outcome?.let(::applySyncOutcome)
            reportAutomaticSyncFailure(outcome)
        }
    }
    // 启动后静默检查新版本（类似桌面延迟）；有更新时弹窗，结果缓存给关于页直接展示
    var knownUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var showStartupUpdateDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2500)
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: return@LaunchedEffect
        val repo = context.getString(R.string.github_repo)
        UpdateChecker.check(current, repo).onSuccess { info ->
            if (info.hasUpdate) {
                knownUpdateInfo = info
                showStartupUpdateDialog = true
            }
        }
    }
    fun triggerAutoSync(quickCommandsOverride: String = quickCommandsRaw) {
        if (store.loadAutoSyncEnabled()) {
            // 必须从磁盘读最新列表/墓碑；内存快照可能落后于刚写的删除
            val conns = store.loadConnections()
            val creds = store.loadCredentials()
            val quick = quickCommandsOverride.ifBlank { store.loadQuickCommandsRaw() }
            val proxies = store.loadProxyNodes()
            val aiProviders = store.loadAiProvidersRaw()
            val aiGlobalSettings = store.loadAiGlobalSettingsRaw()
            scope.launch {
                delay(250)
                val outcome = syncWithPasswordPrompt(
                    sync = { SyncHelper.autoSync(store, conns, creds, quick, proxies, aiProviders, aiGlobalSettings) },
                    retry = { password -> SyncHelper.syncWithRecoveryPassword(store, password, conns, creds, quick, proxies, aiProviders, aiGlobalSettings) },
                )
                outcome?.let {
                    if (it.needsManualTombstoneConfirm) {
                        message = context.getString(R.string.sync_tombstone_needs_manual)
                    } else {
                        applySyncOutcome(it, quick)
                    }
                }
                reportAutomaticSyncFailure(outcome)
            }
        }
    }
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var previousScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

    fun navigateTo(next: AppScreen) {
        previousScreen = screen
        screen = next
    }

    fun navigateBack(default: AppScreen = AppScreen.Home) {
        val next = previousScreen.takeIf { it != screen } ?: default
        screen = next
        previousScreen = AppScreen.Home
    }

    fun navigateHome() {
        screen = AppScreen.Home
        previousScreen = AppScreen.Home
    }
    var showDataManagement by remember { mutableStateOf(false) }
    var hideSensitive by remember { mutableStateOf(store.loadHideSensitive()) }
    var terminalFontSize by remember { mutableStateOf(store.loadTerminalFontSize()) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var editingCredential by remember { mutableStateOf<Credential?>(null) }
    var pendingDeleteConnection by remember { mutableStateOf<Connection?>(null) }
    var pendingDeleteCredential by remember { mutableStateOf<Credential?>(null) }
    BackHandler(enabled = screen != AppScreen.Home) {
        when (screen) {
            is AppScreen.ConnectionEdit -> {
                if (previousScreen == AppScreen.Credentials || previousScreen == AppScreen.Settings) navigateBack(AppScreen.Home)
                else navigateHome()
            }
            AppScreen.Credentials, AppScreen.ProxyNodes -> navigateBack(AppScreen.Settings)
            AppScreen.Settings, AppScreen.About, AppScreen.SyncSettings, AppScreen.QuickCommands -> navigateHome()
            else -> navigateHome()
        }
    }
    LaunchedEffect(requestedSessionId) {
        if (requestedSessionId != null) {
            val entry = BackgroundSshSessions.get(requestedSessionId)
            if (entry != null) navigateTo(AppScreen.Terminal(entry.conn, entry.sessionId))
        }
    }
    var groupOrder by remember { mutableStateOf(store.loadGroupOrder()) }
    val collapsedGroups = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }
    val filteredConnections = if (searchQuery.isBlank()) connections else connections.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
            it.host.contains(searchQuery, ignoreCase = true) ||
            it.username.contains(searchQuery, ignoreCase = true)
    }
    val ungroupedLabel = stringResource(R.string.ungrouped)
    val groupedConnections = filteredConnections.groupBy { it.group.ifBlank { ungroupedLabel } }
    val existingGroups = groupedConnections.keys.toList()
    val orderedGroups = groupOrder.filter { it in existingGroups } + existingGroups.filterNot { it in groupOrder }.sortedBy { if (it == ungroupedLabel) "" else it.lowercase() }

    fun withCredential(conn: Connection): Connection {
        val credential = credentials.firstOrNull { it.id == conn.credentialId }
        return if (credential == null) conn else conn.copy(
            username = credential.username.ifBlank { conn.username },
            password = credential.password,
            authMethod = credential.authMethod,
            privateKey = credential.privateKey,
            passphrase = credential.passphrase,
        )
    }

    var collapsedBeforeGroupDrag by remember { mutableStateOf<List<String>?>(null) }
    val homeLazyListState = rememberLazyListState()
    val homeReorderableState = rememberReorderableLazyListState(homeLazyListState) { from, to ->
        val fromKey = from.key as? String
        val toKey = to.key as? String
        if (fromKey == null || !fromKey.startsWith("group-") || toKey == null || !toKey.startsWith("group-")) return@rememberReorderableLazyListState
        val fromGroup = fromKey.removePrefix("group-")
        val toGroup = toKey.removePrefix("group-")
        val fromIndex = orderedGroups.indexOf(fromGroup)
        val toIndex = orderedGroups.indexOf(toGroup)
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        val list = orderedGroups.toMutableList()
        list.removeAt(fromIndex)
        list.add(toIndex, fromGroup)
        groupOrder = list
    }
    fun collapseAllGroupsForDrag() {
        if (collapsedBeforeGroupDrag == null) collapsedBeforeGroupDrag = collapsedGroups.toList()
        collapsedGroups.clear()
        orderedGroups.forEach { collapsedGroups.add(it) }
    }
    fun restoreGroupsAfterDrag() {
        collapsedBeforeGroupDrag?.let { previous ->
            collapsedGroups.clear()
            collapsedGroups.addAll(previous)
        }
        collapsedBeforeGroupDrag = null
        store.saveGroupOrder(groupOrder)
    }

    val visibleTerminalScreen = when {
        screen is AppScreen.Terminal -> screen as AppScreen.Terminal
        screen == AppScreen.QuickCommands && previousScreen is AppScreen.Terminal -> previousScreen as AppScreen.Terminal
        else -> null
    }
    if (visibleTerminalScreen != null) {
        val terminal = visibleTerminalScreen
        SshCommandScreen(
            store = store,
            conn = withCredential(terminal.connection),
            requestedSessionId = terminal.backgroundSessionId,
            quickCommandsRaw = quickCommandsRaw,
            onBack = {
                connections = store.loadConnections()
                quickCommandsRaw = store.loadQuickCommandsRaw()
                navigateHome()
            },
            onManageQuickCommands = { navigateTo(AppScreen.QuickCommands) },
            onFontSizeChanged = { size: Int -> terminalFontSize = size },
        )
    }

    if (showFontSizeDialog) {
        FontSizePickerDialog(
            current = terminalFontSize,
            onDismiss = { showFontSizeDialog = false },
            onConfirm = {
                terminalFontSize = it
                store.saveTerminalFontSize(it)
                showFontSizeDialog = false
                message = context.getString(R.string.font_size_set, it)
            },
        )
    }

    if (screen == AppScreen.Settings) {
        SettingsPage(
            appLanguage = appLanguage,
            appTheme = appTheme,
            terminalFontSize = terminalFontSize,
            appLogEnabled = store.loadAppLogEnabled(),
            appLogSizeLabel = AppLog.sizeLabel(),
            appLogMaxLabel = AppLog.maxSizeLabel(),
            onBack = { navigateHome() },
            onAppLanguageChange = onAppLanguageChange,
            onAppThemeChange = onAppThemeChange,
            onOpenFontSize = { showFontSizeDialog = true },
            onOpenCredentials = {
                editingCredential = null
                navigateTo(AppScreen.Credentials)
            },
            onOpenProxyManager = { navigateTo(AppScreen.ProxyNodes) },
            onOpenQuickCommands = { navigateTo(AppScreen.QuickCommands) },
            onOpenDataManagement = { showDataManagement = true },
            onOpenSyncSettings = { navigateTo(AppScreen.SyncSettings) },
            onOpenAbout = { navigateTo(AppScreen.About) },
            onAppLogEnabledChange = { on ->
                store.saveAppLogEnabled(on)
                AppLog.setEnabled(on)
                message = if (on) {
                    context.getString(R.string.app_log_enabled_on)
                } else {
                    context.getString(R.string.app_log_enabled_off)
                }
            },
            onShareAppLog = {
                val intent = AppLog.shareIntent(context)
                if (intent != null) {
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_app_log)))
                } else {
                    message = context.getString(R.string.app_log_empty)
                }
            },
            onClearAppLog = {
                AppLog.clear()
                message = context.getString(R.string.app_log_cleared)
            },
        )
    }

    if (showDataManagement) {
        Dialog(onDismissRequest = { showDataManagement = false }) {
            DataManagementPage(
                store = store,
                onClose = { showDataManagement = false },
                onExportPlain = {
                    pendingExportContent = desktopSnapshotJson(connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, System.currentTimeMillis())
                    exportLauncher.launch(backupFileName(false))
                },
                onExportCloudKey = {
                    val password = store.loadRecoveryPassword()
                    if (password.isBlank()) {
                        message = context.getString(R.string.set_recovery_password_first)
                    } else {
                        pendingExportContent = encryptLumin2(desktopSnapshotJson(connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, System.currentTimeMillis()), password)
                        exportLauncher.launch(backupFileName(true))
                    }
                },
                onExportCustom = {
                    exportPassword = ""
                    showExportPasswordDialog = true
                },
                onImport = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
            )
        }
    }

    if (showExportPasswordDialog) {
        PasswordPromptDialog(
            title = stringResource(R.string.custom_password_export),
            label = stringResource(R.string.encryption_password),
            value = exportPassword,
            onValueChange = { exportPassword = it },
            confirmLabel = stringResource(R.string.export),
            onConfirm = {
                if (exportPassword.isBlank()) {
                    message = context.getString(R.string.enter_encryption_password)
                } else {
                    pendingExportContent = encryptLumin2(desktopSnapshotJson(connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, System.currentTimeMillis()), exportPassword)
                    showExportPasswordDialog = false
                    exportLauncher.launch(backupFileName(true))
                }
            },
            onDismiss = { showExportPasswordDialog = false },
        )
    }

    pendingRecoveryPasswordSync?.let { request ->
        PasswordPromptDialog(
            title = stringResource(R.string.sync_requires_recovery_password),
            message = stringResource(if (recoveryPasswordAttempt == 0) R.string.enter_recovery_password_to_sync else R.string.recovery_password_incorrect_retry),
            label = stringResource(R.string.recovery_password),
            value = recoveryPasswordInput,
            onValueChange = { recoveryPasswordInput = it },
            confirmLabel = stringResource(R.string.continue_sync),
            onConfirm = {
                val password = recoveryPasswordInput
                recoveryPasswordInput = ""
                if (password.isBlank()) {
                    message = context.getString(R.string.enter_recovery_password)
                } else if (!request.isCompleted) {
                    request.complete(password)
                }
            },
            onDismiss = {
                recoveryPasswordInput = ""
                if (!request.isCompleted) request.complete(null)
            },
        )
    }

    if (showImportPasswordDialog) {
        PasswordPromptDialog(
            title = stringResource(R.string.input_import_password),
            message = stringResource(R.string.import_password_hint),
            label = stringResource(R.string.import_password),
            value = importPassword,
            onValueChange = { importPassword = it },
            confirmLabel = stringResource(R.string.import_action),
            onConfirm = {
                runCatching {
                    val restored = SyncHelper.markSnapshotRestored(parseSnapshotPayload(pendingEncryptedImport, importPassword), System.currentTimeMillis())
                    val merged = mergeImportedSnapshot(restored, connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw, System.currentTimeMillis())
                    connections = merged.connections
                    credentials = merged.credentials
                    proxyNodes = merged.proxyNodes
                    quickCommandsRaw = merged.quickCommands
                    savePcRawFields(merged.aiProvidersRaw, merged.aiGlobalSettingsRaw)
                    store.saveConnections(connections)
                    store.saveCredentials(credentials)
                    store.saveProxyNodes(proxyNodes)
                    store.saveQuickCommandsRaw(quickCommandsRaw)
                }.onSuccess {
                    showImportPasswordDialog = false
                    pendingEncryptedImport = ""
                    message = context.getString(R.string.import_completed)
                }.onFailure { message = context.getString(R.string.import_failed, context.userErrorText(it)) }
            },
            onDismiss = { showImportPasswordDialog = false },
        )
    }

    if (screen == AppScreen.About) {
        AboutPage(
            onBack = { navigateBack(AppScreen.Settings) },
            knownUpdate = knownUpdateInfo,
        )
    }

    // 放在所有 Screen 分支之后，避免被 About/Settings 等页面盖住
    pendingRemoteDirMissingError?.let { errMsg ->
        LaunchedEffect(errMsg) {
            AppLog.i("SyncUI", "AlertDialog visible for remote dir missing")
        }
        AlertDialog(
            onDismissRequest = {
                pendingRemoteDirCancel?.invoke()
                pendingRemoteDirMissingError = null
                pendingRemoteDirRecreate = null
                pendingRemoteDirRetry = null
                pendingRemoteDirCancel = null
            },
            title = { Text(stringResource(R.string.cloud_sync_failed_title)) },
            text = {
                Text(context.getString(R.string.remote_dir_missing_body, errMsg))
            },
            // Material3: confirm 在右；放「重新创建」作为主操作
            confirmButton = {
                TextButton(onClick = {
                    val cont = pendingRemoteDirRecreate
                    pendingRemoteDirMissingError = null
                    pendingRemoteDirRecreate = null
                    pendingRemoteDirRetry = null
                    pendingRemoteDirCancel = null
                    cont?.invoke()
                }) { Text(stringResource(R.string.recreate_and_retry)) }
            },
            // dismiss 区放「忽略 + 重试」，与 PC 三按钮一致
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingRemoteDirCancel?.invoke()
                        pendingRemoteDirMissingError = null
                        pendingRemoteDirRecreate = null
                        pendingRemoteDirRetry = null
                        pendingRemoteDirCancel = null
                    }) { Text(stringResource(R.string.ignore)) }
                    TextButton(onClick = {
                        val cont = pendingRemoteDirRetry
                        pendingRemoteDirMissingError = null
                        pendingRemoteDirRecreate = null
                        pendingRemoteDirRetry = null
                        pendingRemoteDirCancel = null
                        cont?.invoke()
                    }) { Text(stringResource(R.string.retry_only)) }
                }
            },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = true,
            ),
        )
    }

    if (screen == AppScreen.SyncSettings) {
        SyncSettingsPage(
            store = store,
            connections = connections,
            credentials = credentials,
            quickCommandsRaw = quickCommandsRaw,
            proxyNodes = proxyNodes,
            aiProvidersRaw = aiProvidersRaw,
            aiGlobalSettingsRaw = aiGlobalSettingsRaw,
            onBack = { navigateBack(AppScreen.Settings) },
            onRestored = { snapshot ->
                val restoreTime = System.currentTimeMillis()
                val restored = SyncHelper.markSnapshotRestored(snapshot, restoreTime)
                connections = restored.connections
                credentials = restored.credentials
                proxyNodes = restored.proxyNodes
                quickCommandsRaw = restored.quickCommands
                savePcRawFields(restored.aiProvidersRaw, restored.aiGlobalSettingsRaw)
                store.saveConnections(connections)
                store.saveCredentials(credentials)
                store.saveProxyNodes(proxyNodes)
                store.saveQuickCommandsRaw(quickCommandsRaw)
                store.saveSnapshotTime(restoreTime)
                store.saveLastSyncTime(restoreTime - 1)
                val restoreConns = connections
                val restoreCreds = credentials
                val restoreQuick = quickCommandsRaw
                val restoreProxies = proxyNodes
                val restoreAiProviders = aiProvidersRaw
                val restoreAiGlobalSettings = aiGlobalSettingsRaw
                scope.launch {
                    val outcome = syncWithPasswordPrompt(
                        sync = { SyncHelper.autoSync(store, restoreConns, restoreCreds, restoreQuick, restoreProxies, restoreAiProviders, restoreAiGlobalSettings) },
                        retry = { password -> SyncHelper.syncWithRecoveryPassword(store, password, restoreConns, restoreCreds, restoreQuick, restoreProxies, restoreAiProviders, restoreAiGlobalSettings) },
                    )
                    outcome?.let(::applySyncOutcome)
                    reportAutomaticSyncFailure(outcome)
                }
            },
            onSync = { sync, retry -> syncWithPasswordPrompt(sync, retry) },
            syncBusy = syncBusy,
            onSynced = { mergedConns, mergedCreds, mergedQuick, mergedProxies, aiProviders, aiGlobalSettings ->
                connections = mergedConns
                credentials = mergedCreds
                proxyNodes = mergedProxies
                quickCommandsRaw = mergedQuick
                aiProvidersRaw = aiProviders
                aiGlobalSettingsRaw = aiGlobalSettings
            },
            onMessage = { message = it },
        )
    }

    if (screen == AppScreen.ProxyNodes) {
        ProxyNodeManager(
            proxyNodes = proxyNodes,
            onClose = { navigateBack(AppScreen.Settings) },
            onSave = { node ->
                proxyNodes = (proxyNodes.filterNot { it.id == node.id } + node).sortedBy { it.name.ifBlank { it.host }.lowercase() }
                store.saveProxyNodes(proxyNodes)
                message = context.getString(R.string.saved_proxy, node.name.ifBlank { node.host })
                triggerAutoSync()
            },
            onDelete = { node ->
                proxyNodes = proxyNodes.filterNot { it.id == node.id }
                store.saveProxyNodes(proxyNodes)
                connections = connections.map { if (it.proxyNodeId == node.id) it.copy(proxyMode = "", proxyNodeId = "", lastModified = System.currentTimeMillis()) else it }
                store.saveConnections(connections)
                message = context.getString(R.string.deleted_proxy, node.name.ifBlank { node.host })
                triggerAutoSync()
            },
        )
    }

    if (screen == AppScreen.QuickCommands) {
        QuickCommandManagerPage(
            quickCommandsRaw = quickCommandsRaw,
            onBack = { navigateBack(AppScreen.Settings) },
            onSave = { updatedJson, syncNow ->
                quickCommandsRaw = updatedJson
                store.saveQuickCommandsRaw(updatedJson)
                if (syncNow) triggerAutoSync(updatedJson)
            },
        )
    }

    if (screen == AppScreen.Home) {
        HomePage(
            connections = connections,
            orderedGroups = orderedGroups,
            groupedConnections = groupedConnections,
            collapsedGroups = collapsedGroups,
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            hideSensitive = hideSensitive,
            onToggleHideSensitive = {
                hideSensitive = !hideSensitive
                store.saveHideSensitive(hideSensitive)
            },
            onOpenSettings = { navigateTo(AppScreen.Settings) },
            onAddServer = { navigateTo(AppScreen.ConnectionEdit(null)) },
            onOpenCredentials = {
                editingCredential = null
                navigateTo(AppScreen.Credentials)
            },
            onCollapseAll = {
                collapsedGroups.clear()
                collapsedGroups.addAll(orderedGroups)
            },
            onExpandAll = { collapsedGroups.clear() },
            onConnect = { conn -> navigateTo(AppScreen.Terminal(conn, null)) },
            onEdit = { conn -> navigateTo(AppScreen.ConnectionEdit(conn)) },
            // 与 PC 一致：克隆预填全部字段，id 置空走新增
            onClone = { conn -> navigateTo(AppScreen.ConnectionEdit(conn.copy(id = ""))) },
            onDelete = { conn -> pendingDeleteConnection = conn },
            onGroupDragStarted = { collapseAllGroupsForDrag() },
            onGroupDragStopped = { restoreGroupsAfterDrag() },
            homeLazyListState = homeLazyListState,
            homeReorderableState = homeReorderableState,
        )
    }

    if (screen is AppScreen.ConnectionEdit) {
        val editing = (screen as AppScreen.ConnectionEdit).connection
        ConnectionForm(
            initial = editing,
            credentials = credentials,
            proxyNodes = proxyNodes,
            groups = orderedGroups.filter { it != ungroupedLabel },
            onCancel = {
                if (previousScreen == AppScreen.Credentials) navigateBack(AppScreen.Home)
                else navigateHome()
            },
            onManageCredentials = {
                editingCredential = null
                navigateTo(AppScreen.Credentials)
            },
            onSave = { conn ->
                val isNew = editing?.id.isNullOrBlank()
                // 与 PC saveServerConfig 一致：host + port + username 唯一
                if (hasDuplicateConnection(connections, conn.host, conn.port, conn.username, excludeId = if (isNew) null else conn.id)) {
                    message = context.getString(R.string.duplicate_server)
                    return@ConnectionForm
                }
                connections = if (isNew) {
                    connections + conn
                } else {
                    connections.map { if (it.id == conn.id) conn else it }
                }.sortedBy { it.name.lowercase() }
                store.saveConnections(connections)
                message = context.getString(R.string.saved_server, conn.name)
                triggerAutoSync()
                navigateHome()
            },
        )
    }

    if (screen == AppScreen.Credentials) {
        CredentialManager(
            credentials = credentials,
            editing = editingCredential,
            onClose = {
                editingCredential = null
                // 从服务器编辑页进来则回去，否则回设置/首页
                when (previousScreen) {
                    is AppScreen.ConnectionEdit -> navigateBack(AppScreen.Home)
                    AppScreen.Settings -> navigateBack(AppScreen.Settings)
                    else -> navigateHome()
                }
            },
            onEdit = { editingCredential = it },
            onDelete = { credential -> pendingDeleteCredential = credential },
            onSave = { credential ->
                credentials = if (credentials.none { it.id == credential.id }) {
                    credentials + credential
                } else {
                    credentials.map { if (it.id == credential.id) credential else it }
                }
                credentials = credentials.sortedBy { it.name.ifBlank { it.id }.lowercase() }
                store.saveCredentials(credentials)
                editingCredential = null
                message = context.getString(R.string.saved_credential, credential.name.ifBlank { credential.id })
                triggerAutoSync()
            },
        )
    }

    pendingDeleteConnection?.let { conn ->
        ConfirmDialog(
            title = stringResource(R.string.delete_server_title),
            text = stringResource(R.string.delete_server_message, conn.name),
            onCancel = { pendingDeleteConnection = null },
            onConfirm = {
                connections = connections.filterNot { it.id == conn.id }
                store.saveConnections(connections)
                store.addConnectionTombstones(listOf(conn.id))
                pendingDeleteConnection = null
                message = context.getString(R.string.deleted_server, conn.name)
                triggerAutoSync()
            },
        )
    }

    pendingDeleteCredential?.let { credential ->
        ConfirmDialog(
            title = stringResource(R.string.delete_credential_title),
            text = stringResource(R.string.delete_credential_message, credential.name.ifBlank { credential.id }),
            onCancel = { pendingDeleteCredential = null },
            onConfirm = {
                credentials = credentials.filterNot { it.id == credential.id }
                store.saveCredentials(credentials)
                store.addCredentialTombstones(listOf(credential.id))
                connections = connections.map { if (it.credentialId == credential.id) it.copy(credentialId = "", lastModified = System.currentTimeMillis()) else it }
                store.saveConnections(connections)
                pendingDeleteCredential = null
                message = context.getString(R.string.deleted_credential, credential.name.ifBlank { credential.id })
                triggerAutoSync()
            },
        )
    }

    if (showStartupUpdateDialog) {
        val info = knownUpdateInfo
        if (info != null) {
            fun openUpdateUrl(url: String) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                showStartupUpdateDialog = false
            }
            Dialog(onDismissRequest = { showStartupUpdateDialog = false }) {
                LuminDialogCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            stringResource(R.string.update_available, info.latestVersion),
                            style = MaterialTheme.typography.titleLarge,
                            color = LuminColors.TextPrimary,
                        )
                        Text(
                            stringResource(R.string.update_dialog_body, info.latestVersion),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LuminColors.TextSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            LuminSecondaryButton(
                                onClick = { showStartupUpdateDialog = false },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.cancel)) }
                            LuminPrimaryButton(
                                onClick = {
                                    openUpdateUrl(info.apkUrl?.takeIf { it.isNotBlank() } ?: info.releaseUrl)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.go_update)) }
                        }
                        LuminSecondaryButton(
                            onClick = { openUpdateUrl(info.releaseUrl) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.open_release_page)) }
                    }
                }
            }
        }
    }
}
