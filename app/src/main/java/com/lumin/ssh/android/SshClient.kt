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
import java.util.concurrent.atomic.AtomicBoolean

class HostKeyRejectedException : CancellationException("主机密钥未接受")

enum class HostKeyAction { Cancel, AcceptOnce, AcceptAndSave }

class PeerTrustRejectedException(message: String) : CancellationException(message)

internal val SSH_HOST_KEY_ALGORITHMS = "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512"

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
    private val confirm: suspend (HostKeyConfirm) -> HostKeyAction,
) : HostKeyRepository {
    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val fingerprint = sshSha256Fingerprint(key)
        val saved = store.loadKnownHostFingerprint(this.host, port)
        if (saved == fingerprint) return HostKeyRepository.OK
        val action = runBlocking { confirm(HostKeyConfirm(this@LocalHostKeyRepository.host, port, fingerprint, saved.isNotBlank())) }
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

class SshShellSession(
    private val store: LocalStore,
    private val conn: Connection,
    private var onOutput: (ByteArray, Int) -> Unit,
    private val resolveText: (Int, Array<out Any>) -> String,
    private val onStatus: (String) -> Unit = {},
    private val confirmHostKey: suspend (HostKeyConfirm) -> HostKeyAction = { HostKeyAction.Cancel },
) : AutoCloseable {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var output: OutputStream? = null
    private val closed = AtomicBoolean(false)

    private fun text(id: Int, vararg args: Any): String = resolveText(id, args)

    suspend fun connect() = withContext(Dispatchers.IO) {
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
            hostKeyRepository = LocalHostKeyRepository(store, conn.host, conn.port, hostKeyRejected, confirmHostKey)
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
            setConfig("server_host_key", SSH_HOST_KEY_ALGORITHMS)
            setConfig("PreferredAuthentications", if (conn.authMethod == "privateKey") "publickey" else "keyboard-interactive,password")
            // 握手超时；连上后必须清零，否则 SO_TIMEOUT 会杀读线程
            timeout = 15000
        }
        session = nextSession
        onStatus(text(R.string.ssh_connecting_socket))
        if (closed.get()) throw CancellationException(text(R.string.ssh_connection_cancelled))
        runCatching { nextSession.connect(15000) }
            .getOrElse {
                AppLog.e("SSH", "session.connect failed", it)
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
        AppLog.i("SSH", "session connected, opening shell")
        onStatus(text(R.string.ssh_opening_shell))
        val nextChannel = nextSession.openChannel("shell") as ChannelShell
        nextChannel.setPty(true)
        // 仅 connect 前设一次保守尺寸；连上后再 setPtySize 在本机实测会断会话（含「只一次」）
        nextChannel.setPtyType("xterm-256color", 80, 24, 640, 384)
        val nextInput = nextChannel.inputStream
        val nextOutput = nextChannel.outputStream
        onStatus(text(R.string.ssh_connecting_channel))
        nextChannel.connect(15000)

        session = nextSession
        channel = nextChannel
        output = nextOutput
        onStatus(text(R.string.ssh_waiting_output))
        AppLog.i("SSH", "shell ready, start reader")
        startReader(nextInput)
    }

    fun setOnOutput(callback: (ByteArray, Int) -> Unit) {
        onOutput = callback
    }

    val isConnected: Boolean get() = !closed.get() && channel?.isConnected == true

    suspend fun sendRaw(text: String) = withContext(Dispatchers.IO) {
        val out = output
        if (out == null) {
            AppLog.w("SSH", "sendRaw while output=null len=${text.length} closed=${closed.get()} ch=${channel?.isConnected}")
            throw IllegalStateException(text(R.string.ssh_not_connected))
        }
        val bytes = text.toByteArray()
        out.write(bytes)
        out.flush()
    }

    /** 远程 WINCH 关闭（连上后 setPtySize 会断会话）；保留方法供 onResize 调用。 */
    fun resize(columns: Int, rows: Int) {
        // no-op：本地行列由 TermuxTerminalSurface 自行处理
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
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
