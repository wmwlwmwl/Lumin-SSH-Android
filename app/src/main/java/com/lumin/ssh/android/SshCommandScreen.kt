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
import java.util.ConcurrentModificationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SshCommandScreen(store: LocalStore, conn: Connection, requestedSessionId: String?, quickCommandsRaw: String, onBack: () -> Unit, onManageQuickCommands: () -> Unit = {}, onFontSizeChanged: (Int) -> Unit = {}, onConnectionReady: (Connection, Boolean) -> Unit = { _, _ -> }) {
    var sshConn by remember(conn.id) { mutableStateOf(conn) }
    var pendingRememberPassword by remember(conn.id) { mutableStateOf(false) }
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
    // 组合是否仍在：NonCancellable 连接结束后用它判断是否还能挂 UI
    val pageAlive = remember { AtomicBoolean(true) }
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
            stateRef.terminal?.setFontSize(next)
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
    var lastReconnectAtMs by remember(conn.id) { mutableStateOf(0L) }
    val context = LocalContext.current
    // applicationContext：后台会话/断开文案不钉住 Activity
    val appContext = remember(context) { context.applicationContext }
    val activeDialogs = remember { mutableListOf<AlertDialog>() }
    fun trackDialog(dialog: AlertDialog): AlertDialog {
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
        return dialog
    }
    fun dismissTrackedDialogs() {
        activeDialogs.toList().forEach { runCatching { it.dismiss() } }
        activeDialogs.clear()
    }
    /** @return sessionId；已满返回 null（shell 未改回调，调用方决定留在页或断开） */
    fun parkShellInBackground(currentShell: SshShellSession): String? {
        if (!BackgroundSshSessions.canAdd()) return null
        val sessionId = BackgroundSshSessions.put(
            conn,
            currentShell,
            synchronized(outputHistory) { ArrayList(outputHistory) },
        ) ?: return null
        // 只捕获 applicationContext，避免 ::localFun 把整个 Composable 作用域钉进后台会话
        val appCtx = appContext
        currentShell.detachUi { id, args -> appCtx.getString(id, *args) }
        currentShell.setOnOutput { bytes, _ -> BackgroundSshSessions.append(sessionId, bytes) }
        return sessionId
    }

    var showShortcutBar by remember { mutableStateOf(true) }
    // 粘滞 CTRL：亮起时下一个字符转控制符，发完自动灭（对齐 Termux）
    var ctrlMode by remember { mutableStateOf(false) }
    var keyboardOpenedByTerminal by remember { mutableStateOf(false) }
    var pendingColumns by remember { mutableStateOf(0) }
    var pendingRows by remember { mutableStateOf(0) }
    var lastLoggedLocalCols by remember { mutableStateOf(0) }
    var lastLoggedLocalRows by remember { mutableStateOf(0) }
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
    fun trimOutputHistoryLocked() {
        var totalBytes = outputHistory.sumOf { it.size }
        while (totalBytes > maxOutputHistoryBytes && outputHistory.isNotEmpty()) {
            totalBytes -= outputHistory.removeAt(0).size
        }
    }
    // reader 线程与主线程都会碰 history，必须串行
    fun addOutput(bytes: ByteArray) {
        synchronized(outputHistory) {
            outputHistory.add(bytes)
            trimOutputHistoryLocked()
        }
    }
    fun outputHistoryBytes(): Int = synchronized(outputHistory) { outputHistory.sumOf { it.size } }
    fun forEachOutputChunk(block: (ByteArray) -> Unit) {
        synchronized(outputHistory) {
            outputHistory.forEach(block)
        }
    }
    val detachToBackground = {
        val currentShell = shell
        if (currentShell != null && !connecting) {
            val backgroundSessionId = parkShellInBackground(currentShell)
            if (backgroundSessionId == null) {
                // 已满：不离开页面，让用户先断旧会话
                Toast.makeText(
                    context,
                    context.getString(R.string.background_session_limit_reached, BackgroundSshSessions.MAX_SESSIONS),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                detachedToBackground = true
                dismissTrackedDialogs()
                SshBackgroundService.start(context, backgroundSessionId)
                onBack()
            }
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
            trackDialog(
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
            )
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
        val dialog = trackDialog(
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.authentication_failed))
                .setMessage(context.getString(R.string.enter_user_password, conn.username))
                .setView(dialogView)
                .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> cancelAuth() }
                .setPositiveButton(context.getString(R.string.confirm), null)
                .show()
        )
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
                    pendingRememberPassword = rememberPassword.isChecked
                    shell = null
                    shellReady = false
                    connecting = false
                    connectDetails.clear()
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
        // 页面 dispose 只 dismiss 时也要 complete，避免握手 runBlocking 挂死
        fun completeIfPending(action: HostKeyAction) {
            if (!result.isCompleted) result.complete(action)
        }
        trackDialog(
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.host_key_confirmation))
                .setMessage(message)
                .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> cancelNow() }
                .setNeutralButton(context.getString(R.string.accept_once)) { _, _ -> completeIfPending(HostKeyAction.AcceptOnce) }
                .setPositiveButton(context.getString(R.string.accept_and_save)) { _, _ -> completeIfPending(HostKeyAction.AcceptAndSave) }
                .show()
        ).apply {
            setCanceledOnTouchOutside(false)
            setOnCancelListener { cancelNow() }
            setOnDismissListener {
                activeDialogs.remove(this)
                completeIfPending(HostKeyAction.Cancel)
            }
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
        trackDialog(
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
                    retryNonce++
                }
                .show()
        ).apply {
            setCanceledOnTouchOutside(false)
            setOnCancelListener { closePage() }
        }
    }
    val showKeyboardFromTerminal = {
        // 连接中/未就绪：点终端是误触，不要弹输入法
        // 已就绪后意外断开：shellReady 仍为 true，可弹键盘回车重连
        if (!shellReady || connecting) {
            AppLog.d("Term", "ignore terminal tap: ready=$shellReady connecting=$connecting")
        } else {
            if (System.currentTimeMillis() >= ignoreTerminalTapUntil) {
                showShortcutBar = !showShortcutBar
            }
            keyboardOpenedByTerminal = true
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    /** 本地尺寸变化 → 通知远端 WINCH（内部防抖，见 SshShellSession.resize）。 */
    fun resizePtyOnce(columns: Int, rows: Int) {
        if (columns < 20 || rows < 5) return
        // 上限只兜异常值：截断会让远端以为屏幕更矮，全屏程序底栏画在半屏处
        val safeCols = columns.coerceIn(20, 500)
        val safeRows = rows.coerceIn(5, 500)
        pendingColumns = safeCols
        pendingRows = safeRows
        (stateRef.shell ?: shell)?.resize(safeCols, safeRows)
        if (safeCols != lastLoggedLocalCols || safeRows != lastLoggedLocalRows) {
            lastLoggedLocalCols = safeCols
            lastLoggedLocalRows = safeRows
            AppLog.d("Term", "local size ${safeCols}x$safeRows")
        }
    }
    // 与 PC 一致：断开/失败后按回车触发重连
    fun requestReconnect() {
        if (connecting) return
        val current = stateRef.shell ?: shell
        if (current?.isConnected == true) return
        val now = System.currentTimeMillis()
        // 防连按回车连撞 retryNonce
        if (now - lastReconnectAtMs < 800) return
        lastReconnectAtMs = now
        AppLog.i("Term", "reconnect requested")
        current?.close()
        shell = null
        stateRef.shell = null
        shellReady = false
        connecting = false
        connectDetails.clear()
        retryNonce++
    }
    fun isShellLive(): Boolean {
        val current = stateRef.shell ?: shell
        return current != null && shellReady && current.isConnected
    }
    fun sendToShell(payload: String) {
        if (payload.isEmpty()) return
        if (!isShellLive()) {
            // 仅回车触发重连；其它输入在断开态忽略（对齐 PC）
            if (!connecting && ('\r' in payload || '\n' in payload)) {
                requestReconnect()
            } else {
                AppLog.w("Term", "send blocked live=false ready=$shellReady len=${payload.length}")
            }
            return
        }
        val currentShell = stateRef.shell ?: shell ?: return
        // 不打完整 payload（可能含密码）
        AppLog.d("Term", "send len=${payload.length} first=${payload.firstOrNull()?.code}")
        scope.launch {
            runCatching { currentShell.sendRaw(payload) }
                .onFailure {
                    AppLog.e("Term", "send failed ready=$shellReady connected=${currentShell.isConnected}", it)
                    // 不写 shellReady=false：保持可弹键盘，下次回车走 requestReconnect
                }
        }
    }
    /** 粘滞 CTRL 亮时把字符转成控制符并复位；转不了的（回车/退格/方向键序列）原样发。 */
    val safeSend: (String) -> Unit = { text ->
        if (ctrlMode) {
            ctrlMode = false
            sendToShell(ctrlKeyPayload(text) ?: text)
        } else {
            sendToShell(text)
        }
    }
    val sendPromptCommand = {
        if (!isShellLive()) {
            if (!connecting) requestReconnect()
        } else {
            val line = command
            if (line.isNotBlank()) {
                command = ""
                // 多行脚本：换行统一成 \r（PTY），末尾再补一个回车执行
                val payload = line.replace("\r\n", "\n").replace('\n', '\r').let { body ->
                    if (body.endsWith("\r")) body else "$body\r"
                }
                sendToShell(payload)
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
        if (!shellReady || shell == null) return
        showQuickCommands = false
        sendToShell(if (item.addCR) "$filledCommand\r" else filledCommand)
    }

    DisposableEffect(conn.id) {
        pageAlive.set(true)
        onDispose {
            pageAlive.set(false)
            dismissTrackedDialogs()
            terminal?.apply {
                dismissUiDialogs()
                onInput = {}
                onResize = { _, _ -> }
                onTap = {}
                onSaveTranscript = { _, _ -> }
            }
            terminal = null
            stateRef.terminal = null
            // 连接中 shell 状态可能还是 null，但 stateRef.shell 已指向 nextShell
            val currentShell = shell ?: stateRef.shell
            if (!detachedToBackground && !closingPage && currentShell != null && currentShell.isConnected) {
                // 已连上才尝试挂后台；满了或握手中途则直接关掉，避免孤儿会话
                val sessionId = parkShellInBackground(currentShell)
                if (sessionId != null) {
                    SshBackgroundService.start(context, sessionId)
                    detachedToBackground = true
                } else {
                    currentShell.close()
                }
            } else if (!detachedToBackground) {
                currentShell?.close()
            }
            // dispose 后不再经 stateRef 碰旧 shell（避免 reader post 到已拆 UI）
            stateRef.shell = null
        }
    }

    // 只跟 conn/retry 绑定，不要依赖 terminal（重组会取消 LaunchedEffect，导致已连上却 shellReady=false）
    LaunchedEffect(conn.id, retryNonce) {
        AppLog.d(
            "Term",
            "connect effect conn=${conn.id} term=${terminal != null} shell=${shell != null} ready=$shellReady connecting=$connecting retry=$retryNonce",
        )
        if (shellReady || connecting) return@LaunchedEffect
        if (shell != null && shell!!.isConnected) {
            shellReady = true
            AppLog.i("Term", "reuse already-connected shell")
            return@LaunchedEffect
        }

        val backgroundEntry = requestedSessionId?.let { BackgroundSshSessions.take(it) }
        if (backgroundEntry != null) {
            AppLog.i("Term", "restore background session=${backgroundEntry.sessionId}")
            restoredBackgroundSessionId = backgroundEntry.sessionId
            SshBackgroundService.release(context, backgroundEntry.sessionId)
            outputHistory.clear()
            backgroundEntry.transcript.forEach { bytes ->
                addOutput(bytes)
                (stateRef.terminal ?: terminal)?.append(bytes)
            }
            backgroundEntry.shell.setOnOutput { bytes, _ ->
                val view = stateRef.terminal ?: terminal
                view?.post {
                    addOutput(bytes)
                    view.append(bytes)
                }
            }
            shell = backgroundEntry.shell
            stateRef.shell = backgroundEntry.shell
            shellReady = true
            return@LaunchedEffect
        }

        connecting = true
        shellReady = false
        AppLog.i("Term", "connecting ${sshConn.username}@${sshConn.host}:${sshConn.port}")
        // 实时输出：只 append 到「当时」的 terminal，并给该 view 打标记，避免 history 再刷双份
        val nextShell = SshShellSession(
            store = store,
            conn = sshConn,
            onOutput = { bytes, _ ->
                // history 可在 reader 线程写（已同步）；UI 必须 post
                addOutput(bytes)
                val view = stateRef.terminal ?: terminal
                if (view != null) {
                    view.post {
                        view.append(bytes)
                        // 已实时写过的 view 不再 history flush
                        view.setTag(R.id.lumin_term_live_output, true)
                    }
                }
            },
            // 从一开始就用 applicationContext，避免会话在前台异常路径也钉住 Activity
            resolveText = { id, args -> appContext.getString(id, *args) },
            onStatus = { detail ->
                AppLog.i("Term", "status $detail")
                // SnapshotStateList 只能在主线程改；页面已 dispose 时绝不再写
                if (pageAlive.get() && !closingPage) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (pageAlive.get() && !closingPage) connectDetails.add(detail)
                    }
                }
            },
            confirmHostKey = { confirmHostKey(it) },
        )
        stateRef.shell = nextShell
        var keepConnectPage = false
        try {
            // 握手阶段用 NonCancellable 保护：重组取消协程时不要把已连上的会话当失败关掉
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                nextShell.connect()
            }
            // 页面已 dispose / 正在关闭：绝不能再把 shell 绑回已拆的 UI
            if (closingPage || !pageAlive.get() || detachedToBackground) {
                AppLog.w("Term", "connected but page gone closing=$closingPage alive=${pageAlive.get()} bg=$detachedToBackground")
                if (!detachedToBackground) nextShell.close()
                shell = null
                stateRef.shell = null
                shellReady = false
            } else {
                shell = nextShell
                stateRef.shell = nextShell
                shellReady = true
                onConnectionReady(sshConn, pendingRememberPassword)
                pendingRememberPassword = false
                AppLog.i(
                    "Term",
                    "shellReady=true terminal=${stateRef.terminal != null} historyBytes=${outputHistoryBytes()}",
                )
            }
        } catch (it: Throwable) {
            // 若取消时会话其实已连上，且页面仍在，保留并标记就绪（日志里的 LeftCompositionCancellationException）
            // 连上后仅日志/状态列表并发异常时也保留，避免 MOTD 已写完又被关掉导致双份欢迎语
            if (nextShell.isConnected && !closingPage && pageAlive.get() && !detachedToBackground && (
                    it is CancellationException || it is ConcurrentModificationException
                    )
            ) {
                AppLog.w("Term", "connect post-up exception, keeping session | ${it.javaClass.simpleName}: ${it.message}")
                shell = nextShell
                stateRef.shell = nextShell
                shellReady = true
                onConnectionReady(sshConn, pendingRememberPassword)
                pendingRememberPassword = false
            } else {
                AppLog.e("Term", "connect failed", it)
                if (!detachedToBackground) nextShell.close()
                shell = null
                stateRef.shell = null
                shellReady = false
                if (pageAlive.get() && !closingPage && it is HostKeyRejectedException) {
                    connecting = false
                    closeConnectionPage()
                    return@LaunchedEffect
                } else if (pageAlive.get() && !closingPage && it !is CancellationException) {
                    val rawErrorText = it.message.orEmpty()
                    val errorText = context.userErrorText(it)
                    val isAuthFailure = sshConn.authMethod == "password" && (
                        rawErrorText.contains("Auth fail", ignoreCase = true) ||
                            rawErrorText.contains("Auth cancel", ignoreCase = true) ||
                            rawErrorText.contains("认证失败")
                        )
                    if (isAuthFailure) {
                        keepConnectPage = true
                        connectDetails.add(context.getString(R.string.auth_failed_retry_password))
                        showAuthFailedPrompt()
                    } else {
                        keepConnectPage = true
                        connectDetails.add(
                            context.getString(
                                R.string.connection_failed_detail,
                                errorText.ifBlank { context.getString(R.string.unknown) },
                            ),
                        )
                        showConnectionFailedPrompt(errorText)
                    }
                }
            }
        } finally {
            if (!keepConnectPage) connecting = false
            AppLog.i("Term", "connect effect done ready=$shellReady connecting=$connecting")
        }
    }

    // terminal 后挂载且「从未实时写过」时才刷 history，避免 MOTD 双份
    LaunchedEffect(terminal, shellReady) {
        val view = terminal ?: return@LaunchedEffect
        stateRef.terminal = view
        val empty = synchronized(outputHistory) { outputHistory.isEmpty() }
        if (!shellReady || empty) return@LaunchedEffect
        if (view.getTag(R.id.lumin_term_live_output) == true) {
            AppLog.d("Term", "skip history flush: view already has live output")
            return@LaunchedEffect
        }
        val chunkCount = synchronized(outputHistory) { outputHistory.size }
        AppLog.i("Term", "flush history chunks=$chunkCount")
        forEachOutputChunk { view.append(it) }
        view.setTag(R.id.lumin_term_live_output, true)
    }

    // 连上后同步一次真实尺寸：pty 建连时开的是保守的 80x24，不纠正的话全屏程序按 24 行排版
    LaunchedEffect(shellReady) {
        if (!shellReady) return@LaunchedEffect
        if (pendingColumns <= 0 || pendingRows <= 0) return@LaunchedEffect
        (stateRef.shell ?: shell)?.resize(pendingColumns, pendingRows)
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
                        setFontSize(store.loadTerminalFontSize())
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
                    view.setFontSize(store.loadTerminalFontSize())
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
                    // 粘滞 CTRL 亮时不等 IME 组词：拿到单字符立刻转控制符发出（nano ^O/^X 等）
                    val ctrlChar = if (ctrlMode && newValue.text.length == oldText.length + 1 && newValue.text.startsWith(oldText)) {
                        newValue.text.substring(oldText.length)
                    } else {
                        null
                    }
                    if (ctrlChar != null) {
                        safeSend(ctrlChar)
                        keyboardText = TextFieldValue("")
                    } else if (newValue.composition != null) {
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
                ctrlMode = ctrlMode,
                onCommandChange = { command = it },
                onSendPrompt = { sendPromptCommand() },
                // 快捷键自带控制符/转义序列，不经粘滞 CTRL（避免 CTRL 被白吃）
                onShortcut = { key -> sendToShell(key) },
                onToggleCtrlMode = { ctrlMode = !ctrlMode },
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
