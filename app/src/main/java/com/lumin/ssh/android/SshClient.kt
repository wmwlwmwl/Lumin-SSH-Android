package com.lumin.ssh.android

import android.util.Base64
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HostKeyRejectedException : CancellationException("主机密钥未接受")

enum class HostKeyAction { Cancel, AcceptOnce, AcceptAndSave }

class PeerTrustRejectedException(message: String) : CancellationException(message)

internal val SSH_HOST_KEY_ALGORITHMS = "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512"

internal fun hostKeyAlgorithmsForConnection(allowLegacySshRsa: Boolean): String =
    if (allowLegacySshRsa) "$SSH_HOST_KEY_ALGORITHMS,ssh-rsa" else SSH_HOST_KEY_ALGORITHMS

data class HostKeyConfirm(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val changed: Boolean,
)

internal class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String = password
    override fun promptPassword(message: String?): Boolean = true
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = true
    override fun showMessage(message: String?) {}
    override fun promptKeyboardInteractive(
        destination: String?, name: String?, instruction: String?,
        prompt: Array<out String>?, echo: BooleanArray?,
    ): Array<String> = Array(prompt?.size ?: 1) { password }
}

internal class LocalHostKeyRepository(
    private val store: LocalStore,
    private val host: String,
    private val port: Int,
    private val rejected: AtomicBoolean,
    /** 用引用持有，便于会话挂后台后 detachUi 丢掉 Activity 回调 */
    private val confirmRef: AtomicReference<suspend (HostKeyConfirm) -> HostKeyAction>,
) : HostKeyRepository {
    constructor(
        store: LocalStore,
        host: String,
        port: Int,
        rejected: AtomicBoolean,
        confirm: suspend (HostKeyConfirm) -> HostKeyAction,
    ) : this(store, host, port, rejected, AtomicReference(confirm))

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val fingerprint = sshSha256Fingerprint(key)
        val saved = store.loadKnownHostFingerprint(this.host, port)
        if (saved == fingerprint) return HostKeyRepository.OK
        val action = runBlocking {
            confirmRef.get().invoke(HostKeyConfirm(this@LocalHostKeyRepository.host, port, fingerprint, saved.isNotBlank()))
        }
        return when (action) {
            HostKeyAction.Cancel -> {
                rejected.set(true)
                HostKeyRepository.OK
            }
            HostKeyAction.AcceptOnce -> HostKeyRepository.OK
            HostKeyAction.AcceptAndSave -> {
                store.saveKnownHostFingerprint(this.host, port, fingerprint)
                HostKeyRepository.OK
            }
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "lumin-local"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private fun sshSha256Fingerprint(key: ByteArray): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(key)
    return "SHA256:" + Base64.encodeToString(hash, Base64.NO_WRAP).trimEnd('=')
}

internal class SerializedShellIo {
    private val lock = ReentrantLock()

    fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

class SshShellSession(
    private val store: LocalStore,
    private val conn: Connection,
    private var onOutput: (ByteArray, Int) -> Unit,
    private var resolveText: (Int, Array<out Any>) -> String,
    private var onStatus: (String) -> Unit = {},
    confirmHostKey: suspend (HostKeyConfirm) -> HostKeyAction = { HostKeyAction.Cancel },
) : AutoCloseable {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var output: OutputStream? = null
    private val closed = AtomicBoolean(false)
    private val io = SerializedShellIo()
    private val sendSequence = AtomicInteger(0)
    private val confirmHostKeyRef = AtomicReference(confirmHostKey)
    // WINCH 防抖：键盘动画每帧一次 resize，压成一次发送（见 resize）
    private val pendingSize = AtomicReference<Pair<Int, Int>?>(null)
    private val lastSentSize = AtomicReference<Pair<Int, Int>?>(null)
    private val resizeGeneration = AtomicInteger(0)
    private val resizeExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lumin-winch").apply { isDaemon = true } }

    private fun text(id: Int, vararg args: Any): String = resolveText(id, args)

    suspend fun connect() = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0
        AppLog.i("SSH", "connect begin ${conn.username}@${conn.host}:${conn.port} auth=${conn.authMethod} proxy=${conn.proxyMode}")
        onStatus(text(R.string.ssh_preparing_connection, conn.host, conn.port))
        if (conn.authMethod == "privateKey" && conn.privateKey.isBlank()) {
            AppLog.e("SSH", "missing private key")
            throw IllegalStateException(text(R.string.ssh_missing_private_key))
        }
        if (conn.authMethod != "privateKey" && conn.password.isBlank()) {
            AppLog.e("SSH", "missing password")
            throw IllegalStateException(text(R.string.ssh_missing_password))
        }

        onStatus(text(R.string.ssh_creating_session))
        val hostKeyRejected = AtomicBoolean(false)
        val jsch = JSch().apply {
            hostKeyRepository = LocalHostKeyRepository(store, conn.host, conn.port, hostKeyRejected, confirmHostKeyRef)
        }
        if (conn.authMethod == "privateKey") {
            jsch.addIdentity(
                "lumin-${conn.id}",
                conn.privateKey.toByteArray(),
                null,
                conn.passphrase.ifBlank { null }?.toByteArray(),
            )
        }
        val nextSession = jsch.getSession(conn.username, conn.host, conn.port).apply {
            resolveProxy()?.let { setProxy(it) }
            if (conn.authMethod != "privateKey") {
                setPassword(conn.password)
                setUserInfo(PasswordUserInfo(conn.password))
            }
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("server_host_key", hostKeyAlgorithmsForConnection(conn.allowLegacySshRsa))
            setConfig("PreferredAuthentications", if (conn.authMethod == "privateKey") "publickey" else "keyboard-interactive,password")
            // 握手超时；连上后必须清零，否则 SO_TIMEOUT 会杀读线程
            timeout = 15000
        }
        io.withLock {
            if (closed.get()) throw CancellationException(text(R.string.ssh_connection_cancelled))
            session = nextSession
        }
        onStatus(text(R.string.ssh_connecting_socket))
        val tSock = System.currentTimeMillis()
        runCatching { nextSession.connect(15000) }
            .getOrElse {
                AppLog.e("SSH", "session.connect failed after ${elapsed()}ms", it)
                if (hostKeyRejected.get() || it is CancellationException || it.cause is HostKeyRejectedException || it.message.orEmpty().contains("主机密钥未接受")) {
                    throw HostKeyRejectedException()
                }
                val message = it.message.orEmpty()
                if (message.contains("Auth fail", ignoreCase = true)) {
                    throw IllegalStateException(text(R.string.ssh_authentication_error, message), it)
                } else {
                    throw IllegalStateException(text(R.string.ssh_connection_error, message.ifBlank { it.javaClass.simpleName }), it)
                }
            }
        AppLog.i("SSH", "session connected in ${System.currentTimeMillis() - tSock}ms (total ${elapsed()}ms), opening shell")

        if (hostKeyRejected.get()) {
            runCatching { nextSession.disconnect() }
            AppLog.w("SSH", "host key rejected")
            throw HostKeyRejectedException()
        }
        // 连上后取消读超时，并开 keepalive
        nextSession.timeout = 0
        runCatching {
            nextSession.setServerAliveInterval(30_000)
            nextSession.serverAliveCountMax = 3
        }
        onStatus(text(R.string.ssh_opening_shell))
        val tShell = System.currentTimeMillis()
        val nextChannel = nextSession.openChannel("shell") as ChannelShell
        nextChannel.setPty(true)
        // 仅 connect 前设一次保守尺寸；连上后再 setPtySize 在本机实测会断会话（含「只一次」）
        nextChannel.setPtyType("xterm-256color", 80, 24, 640, 384)
        val nextInput = nextChannel.inputStream
        val nextOutput = nextChannel.outputStream
        onStatus(text(R.string.ssh_connecting_channel))
        nextChannel.connect(15000)
        AppLog.i("SSH", "shell ready in ${System.currentTimeMillis() - tShell}ms (total ${elapsed()}ms), start reader")

        io.withLock {
            // close() 可能在 NonCancellable 连接途中先跑完：不得再挂上新 channel/reader
            if (closed.get()) {
                runCatching { nextChannel.disconnect() }
                runCatching { nextSession.disconnect() }
                throw CancellationException(text(R.string.ssh_connection_cancelled))
            }
            session = nextSession
            channel = nextChannel
            output = nextOutput
        }
        onStatus(text(R.string.ssh_waiting_output))
        startReader(nextInput)
    }

    fun setOnOutput(callback: (ByteArray, Int) -> Unit) {
        onOutput = callback
    }

    /**
     * 挂后台前调用：丢掉 UI/Activity 相关回调。
     * JSch HostKeyRepository 仍引用 confirmHostKeyRef，故必须把 ref 置空，不能只清字段。
     */
    fun detachUi(safeResolveText: (Int, Array<out Any>) -> String) {
        resolveText = safeResolveText
        onStatus = {}
        confirmHostKeyRef.set { HostKeyAction.Cancel }
    }

    val isConnected: Boolean get() = !closed.get() && channel?.isConnected == true

    suspend fun sendRaw(text: String) = withContext(Dispatchers.IO) {
        io.withLock {
            if (closed.get()) throw IllegalStateException(this@SshShellSession.text(R.string.ssh_not_connected))
            val out = output
            if (out == null || channel?.isConnected != true) {
                AppLog.w("SSH", "sendRaw unavailable len=${text.length} closed=${closed.get()} ch=${channel?.isConnected}")
                throw IllegalStateException(this@SshShellSession.text(R.string.ssh_not_connected))
            }
            val bytes = text.toByteArray()
            val sequence = sendSequence.incrementAndGet()
            AppLog.d("SSH", "send begin seq=$sequence len=${bytes.size}")
            try {
                out.write(bytes)
                out.flush()
                AppLog.d("SSH", "send done seq=$sequence len=${bytes.size}")
            } catch (error: Exception) {
                AppLog.e("SSH", "send failed seq=$sequence len=${bytes.size}", error)
                throw error
            }
        }
    }

    /**
     * 远程 WINCH。必须发：本地 resize 后不通知远端，nano 等用备用屏幕（无回滚区）的程序
     * 收不到重画信号，内容就真丢了。
     *
     * ponytail: setPtySize 是网络 I/O，绝不能在调用方（布局主线程）上同步做，
     * 否则 NetworkOnMainThreadException 会炸穿并带走连接——这大概就是原「一调就断」的成因。
     * 故内部自带单线程 + 防抖：键盘动画每帧一次 resize 会被压成一次发送。
     * 限制：防抖窗口内只保留最后一次尺寸；失败仅记日志不重试（下次 resize 自然覆盖）。
     * 防抖 + 网络往返期间本地已按新行列重排、远端尚未重画，全屏程序会闪一下（无法完全消除）。
     */
    fun resize(columns: Int, rows: Int) {
        if (columns < 20 || rows < 5 || closed.get()) return
        pendingSize.set(columns to rows)
        val generation = resizeGeneration.incrementAndGet()
        resizeExecutor.schedule(
            {
                // 窗口内又来了新尺寸：让最后那次去发
                if (generation != resizeGeneration.get()) return@schedule
                val (cols, rws) = pendingSize.get() ?: return@schedule
                val ch = channel
                if (closed.get() || ch?.isConnected != true) return@schedule
                // 尺寸没变就不打扰远端（如键盘弹出又收起，回到原值）
                val size = cols to rws
                if (lastSentSize.getAndSet(size) == size) return@schedule
                io.withLock {
                    if (closed.get() || ch.isConnected != true) return@withLock
                    runCatching { ch.setPtySize(cols, rws, cols * 8, rws * 16) }
                        .onSuccess { AppLog.d("SSH", "WINCH ${cols}x$rws") }
                        .onFailure { AppLog.w("SSH", "WINCH ${cols}x$rws failed: ${it.javaClass.simpleName}: ${it.message}") }
                }
            },
            // 本地已在 TermuxTerminalSurface 压过一轮动画，这里只兜住偶发连续调用。
            // 延迟不能太长：本地快照已经开始，WINCH 越晚，旧画面停留越久，反而像闪回。
            30,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 帮助 GC：断开后不再通过回调链钉住 UI/Compose 状态
        onOutput = { _, _ -> }
        onStatus = {}
        resolveText = { _, _ -> "" }
        confirmHostKeyRef.set { HostKeyAction.Cancel }
        runCatching { resizeExecutor.shutdownNow() }
        io.withLock {
            output = null
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
            channel = null
            session = null
        }
    }

    private fun resolveProxy(): Proxy? {
        val node = when (conn.proxyMode) {
            "node" -> store.loadProxyNodes().firstOrNull { it.id == conn.proxyNodeId }
            "custom" -> ProxyNode(
                id = "custom",
                type = conn.proxyType,
                host = conn.proxyHost,
                port = conn.proxyPort,
                username = conn.proxyUsername,
                password = conn.proxyPassword,
            )
            else -> null
        } ?: return null
        if (node.host.isBlank()) return null
        return if (node.type == "http") {
            ProxyHTTP(node.host, node.port).apply {
                if (node.username.isNotBlank()) setUserPasswd(node.username, node.password)
            }
        } else {
            ProxySOCKS5(node.host, node.port).apply {
                if (node.username.isNotBlank()) setUserPasswd(node.username, node.password)
            }
        }
    }

    private fun startReader(stream: InputStream) {
        Thread({
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (!closed.get()) {
                val count = try {
                    stream.read(buffer)
                } catch (err: java.net.SocketTimeoutException) {
                    AppLog.d("SSH", "reader socket timeout (ignored)")
                    0
                } catch (err: Exception) {
                    AppLog.e("SSH", "reader error", err)
                    -1
                }
                if (count < 0) break
                if (count == 0) continue
                total += count
                try {
                    onOutput(buffer.copyOf(count), count)
                } catch (err: Exception) {
                    // UI 回调异常绝不能杀掉读线程/会话
                    AppLog.e("SSH", "onOutput callback error", err)
                }
            }
            val chConnected = runCatching { channel?.isConnected }.getOrNull()
            val sessConnected = runCatching { session?.isConnected }.getOrNull()
            AppLog.i(
                "SSH",
                "reader exit totalBytes=$total closed=${closed.get()} ch=$chConnected sess=$sessConnected",
            )
            if (!closed.get()) {
                // 控制符必须在代码里发：strings.xml 常丢掉 \r
                emit("\r\n[31m${text(R.string.ssh_disconnected)}[0m\r\n")
            }
        }, "ssh-reader-${conn.host}").apply {
            isDaemon = true
            start()
        }
    }

    private fun emit(text: String) {
        val bytes = text.toByteArray()
        onOutput(bytes, bytes.size)
    }
}
