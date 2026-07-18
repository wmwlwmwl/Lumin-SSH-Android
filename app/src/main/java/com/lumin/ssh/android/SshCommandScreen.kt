package com.lumin.ssh.android

import android.app.AlertDialog
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SshCommandScreen(store: LocalStore, conn: Connection, requestedSessionId: String?, quickCommandsRaw: String, onBack: () -> Unit, onManageQuickCommands: () -> Unit = {}, onFontSizeChanged: (Int) -> Unit = {}) {
    var sshConn by remember(conn.id) { mutableStateOf(conn) }
    var retryNonce by remember(conn.id) { mutableStateOf(0) }
    var command by remember { mutableStateOf("") }
    var showInputBar by remember { mutableStateOf(store.loadShowInputBar()) }
    var shell by remember { mutableStateOf<SshShellSession?>(null) }
    var shellReady by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf<TermuxTerminalSurface?>(null) }
    var keyboardText by remember { mutableStateOf(TextFieldValue("")) }
    var ignoreTerminalTapUntil by remember { mutableStateOf(0L) }
    val activity = LocalContext.current as? MainActivity
    val stateRef = remember {
        object {
            var shell: SshShellSession? = null
            var terminal: TermuxTerminalSurface? = null
        }
    }
    stateRef.shell = shell
    stateRef.terminal = terminal
    val cachedFontSize = remember { mutableStateOf(store.loadTerminalFontSize()) }
    DisposableEffect(Unit) {
        activity?.setVolumeKeyFontSizeCallback { increase ->
            if (stateRef.shell?.isConnected != true || stateRef.terminal == null) {
                return@setVolumeKeyFontSizeCallback false
            }
            val current = cachedFontSize.value
            val next = (current + if (increase) 1 else -1).coerceIn(1, 30)
            if (next == current) {
                Toast.makeText(activity, activity.getString(if (next == 1) R.string.minimum_font_size else R.string.maximum_font_size), Toast.LENGTH_SHORT).show()
                return@setVolumeKeyFontSizeCallback true
            }
            cachedFontSize.value = next
            store.saveTerminalFontSize(next)
            stateRef.terminal?.setFontSize(next * 21 / 8)
            onFontSizeChanged(next)
            Toast.makeText(activity, activity.getString(R.string.font_size_value, next), Toast.LENGTH_SHORT).show()
            true
        }
        onDispose { activity?.setVolumeKeyFontSizeCallback(null) }
    }
    var pendingSaveText by remember { mutableStateOf("") }
    var showQuickCommands by remember { mutableStateOf(false) }
    val quickCommands = remember(quickCommandsRaw) { quickCommandsFromJson(quickCommandsRaw) }
    val quickCommandTree = remember(quickCommandsRaw) { quickCommandTreeFromJson(quickCommandsRaw) }
    val collapsedQuickCommandFolders = remember { mutableStateListOf<String>() }
    var quickCommandSearch by remember { mutableStateOf("") }
    var pendingQuickCommand by remember { mutableStateOf<QuickCommand?>(null) }
    val quickCommandParamValues = remember { mutableStateListOf<String>() }
    val outputHistory = remember(conn.id) { ArrayList<ByteArray>() }
    val connectDetails = remember(conn.id) { mutableStateListOf<String>() }
    var detachedToBackground by remember(conn.id) { mutableStateOf(false) }
    var restoredBackgroundSessionId by remember(conn.id) { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var closingPage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.getString(R.string.connecting)) }
    var showShortcutBar by remember { mutableStateOf(true) }
    var keyboardOpenedByTerminal by remember { mutableStateOf(false) }
    var ptySized by remember { mutableStateOf(false) }
    var pendingColumns by remember { mutableStateOf(0) }
    var pendingRows by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val saveTranscriptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingSaveText.toByteArray()) }
            }.onSuccess {
                Toast.makeText(context, context.getString(R.string.transcript_saved), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.transcript_save_failed, context.userErrorText(it)), Toast.LENGTH_SHORT).show()
            }
        }
        pendingSaveText = ""
    }
    val maxOutputHistoryBytes = 2 * 1024 * 1024
    fun trimOutputHistory() {
        var totalBytes = outputHistory.sumOf { it.size }
        while (totalBytes > maxOutputHistoryBytes && outputHistory.isNotEmpty()) {
            totalBytes -= outputHistory.removeAt(0).size
        }
    }
    fun addOutput(bytes: ByteArray) {
        outputHistory.add(bytes)
        trimOutputHistory()
    }
    val detachToBackground = {
        val currentShell = shell
        if (currentShell != null && !connecting) {
            detachedToBackground = true
            val backgroundSessionId = BackgroundSshSessions.put(conn, currentShell, outputHistory)
            currentShell.setOnOutput { bytes, _ -> BackgroundSshSessions.append(backgroundSessionId, bytes) }
            SshBackgroundService.start(context, backgroundSessionId)
            onBack()
        }
    }
    val closeConnectionPage = {
        closingPage = true
        if (!detachedToBackground) shell?.close()
        shell = null
        shellReady = false
        connecting = false
        onBack()
    }
    val confirmExit = {
        if (connecting || shell == null) {
            closeConnectionPage()
        } else {
            AlertDialog.Builder(context)
            .setTitle("Lumin SSH")
            .setMessage(context.getString(R.string.background_session_prompt))
            .setNegativeButton(context.getString(R.string.cancel), null)
            .setNeutralButton(context.getString(R.string.exit)) { _, _ ->
                restoredBackgroundSessionId?.let { SshBackgroundService.release(context, it) }
                if (!detachedToBackground) shell?.close()
                shell = null
                onBack()
            }
            .setPositiveButton(context.getString(R.string.yes)) { _, _ -> detachToBackground() }
            .show()
        }
    }
    BackHandler(enabled = true) { confirmExit() }
    val showAuthFailedPrompt = {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setSingleLine(true)
            post { transformationMethod = PasswordTransformationMethod.getInstance() }
        }
        val showPasswordOption = CheckBox(context).apply {
            text = context.getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                input.transformationMethod = if (checked) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                input.setSelection(input.text.length)
            }
        }
        val rememberPassword = CheckBox(context).apply { text = context.getString(R.string.remember_password) }
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(showPasswordOption)
            addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(rememberPassword)
        }
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
            addView(input)
            addView(options)
        }
        var handled = false
        val cancelAuth = {
            if (!handled) {
                handled = true
                shell?.close()
                shell = null
                shellReady = false
                connecting = false
                onBack()
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.authentication_failed))
            .setMessage(context.getString(R.string.enter_user_password, conn.username))
            .setView(dialogView)
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> cancelAuth() }
            .setPositiveButton(context.getString(R.string.confirm), null)
            .show()
        dialog.apply {
            setCanceledOnTouchOutside(false)
            setOnCancelListener { cancelAuth() }
            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text.toString()
                if (password.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.enter_password), Toast.LENGTH_SHORT).show()
                } else {
                    handled = true
                    sshConn = conn.copy(password = password, authMethod = "password")
                    if (rememberPassword.isChecked) {
                        store.saveConnections(store.loadConnections().map {
                            if (it.id == conn.id) it.copy(password = password, authMethod = "password", lastModified = System.currentTimeMillis()) else it
                        })
                    }
                    shell = null
                    shellReady = false
                    connecting = false
                    connectDetails.clear()
                    status = context.getString(R.string.connecting)
                    retryNonce++
                    dismiss()
                }
            }
        }
    }
    suspend fun confirmHostKey(info: HostKeyConfirm): HostKeyAction = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<HostKeyAction>()
        val message = buildString {
            append(context.getString(if (info.changed) R.string.host_key_changed_prompt else R.string.host_key_first_prompt))
            append("\n\n${context.getString(R.string.host_and_port, info.host, info.port)}")
            append("\n\n${context.getString(R.string.key_fingerprint, info.fingerprint)}")
            append("\n\n${context.getString(R.string.host_key_accept_hint)}")
        }
        fun cancelNow() {
            closingPage = true
            shell?.close()
            shell = null
            shellReady = false
            connecting = false
            if (!result.isCompleted) result.complete(HostKeyAction.Cancel)
            onBack()
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.host_key_confirmation))
            .setMessage(message)
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> cancelNow() }
            .setNeutralButton(context.getString(R.string.accept_once)) { _, _ -> result.complete(HostKeyAction.AcceptOnce) }
            .setPositiveButton(context.getString(R.string.accept_and_save)) { _, _ -> result.complete(HostKeyAction.AcceptAndSave) }
            .show()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnCancelListener { cancelNow() }
            }
        result.await()
    }

    val showConnectionFailedPrompt = { errorText: String ->
        var handled = false
        val closePage = {
            if (!handled) {
                handled = true
                shell?.close()
                shell = null
                shellReady = false
                connecting = false
                onBack()
            }
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.connection_failed))
            .setMessage(context.getString(R.string.retry_question, errorText.ifBlank { context.getString(R.string.unknown) }))
            .setNegativeButton(context.getString(R.string.no)) { _, _ -> closePage() }
            .setPositiveButton(context.getString(R.string.yes)) { _, _ ->
                handled = true
                shell = null
                shellReady = false
                connecting = false
                connectDetails.clear()
                status = context.getString(R.string.connecting)
                retryNonce++
            }
            .show()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnCancelListener { closePage() }
            }
    }
    val showKeyboardFromTerminal = {
        if (System.currentTimeMillis() >= ignoreTerminalTapUntil) {
            showShortcutBar = !showShortcutBar
        }
        keyboardOpenedByTerminal = true
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val resizePtyOnce = { columns: Int, rows: Int ->
        pendingColumns = columns
        pendingRows = rows
        val currentShell = shell
        if (!ptySized && shellReady && currentShell != null) {
            ptySized = true
            currentShell.resize(columns, rows)
        }
    }
    val safeSend: (String) -> Unit = { text ->
        scope.launch {
            runCatching { shell?.sendRaw(text) }
                .onFailure { terminal?.append(context.getString(R.string.local_send_failed, context.userErrorText(it)).toByteArray()) }
        }
    }
    val sendPromptCommand = {
        val line = command
        if (line.isNotBlank()) {
            command = ""
            val currentShell = shell
            if (currentShell == null) {
                terminal?.append(context.getString(R.string.local_shell_not_ready).toByteArray())
            } else {
                scope.launch {
                    runCatching { currentShell.sendRaw("$line\r") }
                        .onFailure { terminal?.append(context.getString(R.string.local_send_failed, context.userErrorText(it)).toByteArray()) }
                }
            }
        }
    }

    fun openQuickCommandConfirm(item: QuickCommand) {
        pendingQuickCommand = item
        quickCommandParamValues.clear()
        repeat(quickCommandParams(item.command).maxOfOrNull { it.first } ?: 0) { quickCommandParamValues.add("") }
    }

    fun quickCommandFolderPaths(nodes: List<QuickCommandNode>): List<String> = nodes.flatMap { node ->
        when (node) {
            is QuickCommandNode.Command -> emptyList()
            is QuickCommandNode.Folder -> listOf(node.path) + quickCommandFolderPaths(node.children)
        }
    }

    fun openQuickCommands() {
        collapsedQuickCommandFolders.clear()
        collapsedQuickCommandFolders.addAll(quickCommandFolderPaths(quickCommandTree))
        showQuickCommands = true
    }

    fun sendQuickCommand(item: QuickCommand, filledCommand: String = item.command) {
        val currentShell = shell
        if (currentShell == null) {
            terminal?.append(context.getString(R.string.local_shell_not_ready).toByteArray())
        } else {
            showQuickCommands = false
            scope.launch {
                val text = if (item.addCR) "$filledCommand\r" else filledCommand
                runCatching { currentShell.sendRaw(text) }
                    .onFailure { terminal?.append(context.getString(R.string.local_send_failed, context.userErrorText(it)).toByteArray()) }
            }
        }
    }

    DisposableEffect(conn.id) {
        onDispose {
            terminal?.apply {
                onInput = {}
                onResize = { _, _ -> }
                onTap = {}
                onSaveTranscript = { _, _ -> }
            }
            terminal = null
            val currentShell = shell
            if (!detachedToBackground && !closingPage && currentShell != null) {
                val sessionId = BackgroundSshSessions.put(conn, currentShell, outputHistory)
                currentShell.setOnOutput { bytes, _ -> BackgroundSshSessions.append(sessionId, bytes) }
                SshBackgroundService.start(context, sessionId)
                detachedToBackground = true
            } else if (!detachedToBackground) {
                currentShell?.close()
            }
        }
    }

    LaunchedEffect(conn.id, terminal != null, retryNonce) {
        if (terminal == null || shell != null || connecting) return@LaunchedEffect
        val backgroundEntry = requestedSessionId?.let { BackgroundSshSessions.take(it) }
        if (backgroundEntry != null) {
            restoredBackgroundSessionId = backgroundEntry.sessionId
            SshBackgroundService.release(context, backgroundEntry.sessionId)
            outputHistory.clear()
            backgroundEntry.transcript.forEach { bytes ->
                addOutput(bytes)
                terminal?.append(bytes)
            }
            backgroundEntry.shell.setOnOutput { bytes, _ ->
                terminal?.post {
                    addOutput(bytes)
                    terminal?.append(bytes)
                }
            }
            shell = backgroundEntry.shell
            shellReady = true
            status = context.getString(R.string.background_session_restored)
            return@LaunchedEffect
        }
        connecting = true
        val nextShell = SshShellSession(
            store = store,
            conn = sshConn,
            onOutput = { bytes, _ ->
                terminal?.post {
                    addOutput(bytes)
                    terminal?.append(bytes)
                }
            },
            resolveText = { id, args -> context.getString(id, *args) },
            onStatus = { detail ->
                terminal?.post {
                    if (!closingPage) {
                        status = detail
                        connectDetails.add(detail)
                    }
                }
            },
            confirmHostKey = { confirmHostKey(it) },
        )
        shell = nextShell
        var keepConnectPage = false
        runCatching { nextShell.connect() }
            .onSuccess {
                if (closingPage) {
                    nextShell.close()
                    return@onSuccess
                }
                status = context.getString(R.string.connected)
                shellReady = true
                if (!ptySized && pendingColumns > 0 && pendingRows > 0) resizePtyOnce(pendingColumns, pendingRows)
            }
            .onFailure {
                if (!closingPage && it is HostKeyRejectedException) {
                    nextShell.close()
                    shell = null
                    shellReady = false
                    connecting = false
                    closeConnectionPage()
                } else if (!closingPage && it !is CancellationException) {
                    shellReady = false
                    shell = null
                    nextShell.close()
                    val rawErrorText = it.message.orEmpty()
                    val errorText = context.userErrorText(it)
                    val isAuthFailure = sshConn.authMethod == "password" && (rawErrorText.contains("Auth fail", ignoreCase = true) || rawErrorText.contains("认证失败"))
                    if (isAuthFailure) {
                        keepConnectPage = true
                        status = context.getString(R.string.authentication_failed)
                        connectDetails.add(context.getString(R.string.auth_failed_retry_password))
                        showAuthFailedPrompt()
                    } else {
                        keepConnectPage = true
                        status = context.getString(R.string.connection_failed)
                        connectDetails.add(context.getString(R.string.connection_failed_detail, errorText.ifBlank { context.getString(R.string.unknown) }))
                        showConnectionFailedPrompt(errorText)
                    }
                }
            }
        if (!keepConnectPage) connecting = false
    }

    Column(Modifier.fillMaxSize().background(LuminColors.TerminalBg)) {
        val terminalBgArgb = LuminColors.TerminalBg.toArgb()
        val terminalFgArgb = LuminColors.TerminalText.toArgb()
        val terminalCursorArgb = LuminColors.TerminalCursor.toArgb()
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { showKeyboardFromTerminal() }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TermuxTerminalSurface(context).apply {
                        setFontSize(store.loadTerminalFontSize() * 21 / 8)
                        setSurfaceColors(
                            background = terminalBgArgb,
                            foreground = terminalFgArgb,
                            cursor = terminalCursorArgb,
                        )
                        sessionHost = conn.host
                        onResize = { columns, rows -> resizePtyOnce(columns, rows) }
                        onInput = { text -> safeSend(text) }
                        onTap = { showKeyboardFromTerminal() }
                        onSaveTranscript = { fileName, text ->
                            pendingSaveText = text
                            saveTranscriptLauncher.launch(fileName)
                        }
                    }
                },
                update = { view ->
                    terminal = view
                    view.setFontSize(store.loadTerminalFontSize() * 21 / 8)
                    view.setSurfaceColors(
                        background = terminalBgArgb,
                        foreground = terminalFgArgb,
                        cursor = terminalCursorArgb,
                    )
                    view.sessionHost = conn.host
                    view.onResize = { columns, rows -> resizePtyOnce(columns, rows) }
                    view.onInput = { text -> safeSend(text) }
                    view.onTap = { showKeyboardFromTerminal() }
                    view.onSaveTranscript = { fileName, text ->
                        pendingSaveText = text
                        saveTranscriptLauncher.launch(fileName)
                    }
                },
            )
            BasicTextField(
                value = keyboardText,
                onValueChange = { newValue ->
                    val oldText = keyboardText.text
                    if (newValue.composition != null) {
                        keyboardText = newValue
                    } else {
                        val textToSend = when {
                            newValue.text.startsWith(oldText) -> newValue.text.removePrefix(oldText)
                            oldText.startsWith(newValue.text) -> "\u007F".repeat(oldText.length - newValue.text.length)
                            else -> newValue.text
                        }
                        val safeText = sanitizeSoftKeyboardInput(textToSend)
                        if (safeText.isNotEmpty()) {
                            if ('\r' in safeText || '\n' in safeText) ignoreTerminalTapUntil = System.currentTimeMillis() + 300
                            safeSend(safeText.replace("\n", "\r"))
                        }
                        keyboardText = TextFieldValue("")
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                keyboardText = TextFieldValue("")
                                ignoreTerminalTapUntil = System.currentTimeMillis() + 300
                                safeSend("\r")
                                true
                            }
                            Key.Backspace -> {
                                keyboardText = TextFieldValue(keyboardText.text.dropLast(1))
                                safeSend("\u007F")
                                true
                            }
                            Key.DirectionUp -> {
                                safeSend("\u001B[A")
                                true
                            }
                            Key.DirectionDown -> {
                                safeSend("\u001B[B")
                                true
                            }
                            Key.DirectionLeft -> {
                                safeSend("\u001B[D")
                                true
                            }
                            Key.DirectionRight -> {
                                safeSend("\u001B[C")
                                true
                            }
                            else -> false
                        }
                    },
            )
            ConnectStatusBar(connecting, conn, connectDetails)
        }
        if (!connecting) {
            TerminalToolbar(
                showShortcutBar = showShortcutBar,
                showInputBar = showInputBar,
                command = command,
                onCommandChange = { command = it },
                onSendPrompt = { sendPromptCommand() },
                onShortcut = { key -> safeSend(key) },
                onToggleInputBar = { val next = !showInputBar; showInputBar = next; store.saveShowInputBar(next) },
                onOpenQuickCommands = { openQuickCommands() },
            )
        }
    }

    QuickCommandPickerDialog(
        show = showQuickCommands,
        onDismiss = { showQuickCommands = false },
        onManage = { showQuickCommands = false; onManageQuickCommands() },
        quickCommands = quickCommands,
        quickCommandTree = quickCommandTree,
        collapsedFolders = collapsedQuickCommandFolders,
        searchQuery = quickCommandSearch,
        onSearchChange = { quickCommandSearch = it },
        onPick = { openQuickCommandConfirm(it) },
    )
    QuickCommandConfirmDialog(
        item = pendingQuickCommand,
        paramValues = quickCommandParamValues,
        onParamChange = { index, value -> quickCommandParamValues[index] = value },
        onConfirm = { item, filledCommand -> pendingQuickCommand = null; sendQuickCommand(item, filledCommand) },
        onCancel = { pendingQuickCommand = null },
    )
}
