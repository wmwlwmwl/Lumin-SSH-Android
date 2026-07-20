package com.lumin.ssh.android

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Vector
import java.util.concurrent.atomic.AtomicBoolean

class SftpSync(
    private val store: LocalStore,
    private val host: String,
    private val port: Int = 22,
    private val username: String,
    private val password: String,
    private val privateKey: String = "",
    private val passphrase: String = "",
    private val remoteDir: String = "/Lumin/",
    private val confirmHostKey: (suspend (HostKeyConfirm) -> HostKeyAction)? = null,
) : SyncProvider {
    private var session: com.jcraft.jsch.Session? = null

    private fun connect(ensureRemoteDir: Boolean = true): ChannelSftp {
        val rejected = AtomicBoolean(false)
        val jsch = JSch().apply {
            hostKeyRepository = LocalHostKeyRepository(store, host, port, rejected) { info ->
                confirmHostKey?.invoke(info) ?: HostKeyAction.Cancel
            }
        }
        if (privateKey.isNotBlank()) {
            jsch.addIdentity(
                "lumin-sftp-sync",
                privateKey.toByteArray(),
                null,
                passphrase.ifBlank { null }?.toByteArray(),
            )
        }
        val s = jsch.getSession(username, host, port).apply {
            if (privateKey.isBlank()) setPassword(password)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("server_host_key", SSH_HOST_KEY_ALGORITHMS)
            if (privateKey.isNotBlank()) {
                setConfig("PreferredAuthentications", "publickey")
            } else {
                setConfig("PreferredAuthentications", "keyboard-interactive,password")
                setUserInfo(PasswordUserInfo(password))
            }
        }
        session = s
        try {
            s.connect(15000)
            if (rejected.get()) throw PeerTrustRejectedException("SFTP 主机密钥未接受: $host:$port")
            val channel = s.openChannel("sftp") as ChannelSftp
            channel.connect()
            if (ensureRemoteDir) ensureDir(channel, remoteDir)
            return channel
        } catch (e: Exception) {
            s.disconnect()
            session = null
            if (rejected.get() && e !is PeerTrustRejectedException) {
                throw PeerTrustRejectedException("SFTP 主机密钥未接受: $host:$port")
            }
            throw e
        }
    }

    fun testConnection() {
        val channel = connect(ensureRemoteDir = false)
        try {
            channel.pwd()
        } finally {
            channel.disconnect()
            session?.disconnect()
        }
    }

    private fun ensureDir(channel: ChannelSftp, dir: String) {
        val parts = dir.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            runCatching { channel.cd(current) }.onFailure {
                channel.mkdir(current); channel.cd(current)
            }
        }
        channel.cd("/")
    }

    override fun listBackupNames(): List<String> {
        val channel = connect()
        try {
            @Suppress("UNCHECKED_CAST")
            val files = channel.ls(remoteDir) as Vector<ChannelSftp.LsEntry>
            return files
                .filter { isBackupName(it.filename) }
                .map { it.filename }
                .sortedDescending()
        } finally {
            channel.disconnect()
            session?.disconnect()
        }
    }

    override fun restoreSnapshot(name: String, recoveryPassword: String): SyncSnapshot {
        val channel = connect()
        try {
            val out = ByteArrayOutputStream()
            channel.get("${remoteDir.trimEnd('/')}/$name", out)
            return parseSnapshotPayload(out.toString(Charsets.UTF_8.name()), recoveryPassword.ifBlank { null })
        } finally {
            channel.disconnect()
            session?.disconnect()
        }
    }

    override fun backupConnections(
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommands: String,
        proxyNodes: List<ProxyNode>,
        aiProvidersRaw: String,
        aiGlobalSettingsRaw: String,
        snapshotTime: Long,
        maxBackups: Int,
        recoveryPassword: String,
        deletedConnections: List<SyncTombstone>,
        deletedCredentials: List<SyncTombstone>,
        tombstonePrunedBefore: Long,
    ): String {
        val snapshotJson = desktopSnapshotJson(connections, credentials, quickCommands, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, snapshotTime, deletedConnections, deletedCredentials, tombstonePrunedBefore)
        val encrypted = recoveryPassword.isNotBlank()
        val content = if (encrypted) encryptLumin2(snapshotJson, recoveryPassword) else snapshotJson
        val fileName = backupFileName(encrypted)
        val channel = connect()
        try {
            channel.put(ByteArrayInputStream(content.toByteArray()), "${remoteDir.trimEnd('/')}/$fileName")
        } finally {
            channel.disconnect()
            session?.disconnect()
        }
        return fileName
    }

    override fun deleteBackup(name: String) {
        val channel = connect()
        try {
            channel.rm("${remoteDir.trimEnd('/')}/$name")
        } finally {
            channel.disconnect()
            session?.disconnect()
        }
    }

    override fun pruneOldBackups(maxBackups: Int) {
        val channel = connect()
        try {
            cleanupOldBackups(channel, maxBackups)
        } finally {
            channel.disconnect()
            session?.disconnect()
        }
    }

    private fun cleanupOldBackups(channel: ChannelSftp, maxBackups: Int) {
        @Suppress("UNCHECKED_CAST")
        val files = channel.ls(remoteDir) as Vector<ChannelSftp.LsEntry>
        val backups = files.filter { isBackupName(it.filename) }
        backups.sortedByDescending { it.attrs.mTime }.drop(maxBackups).forEach { f ->
            channel.rm("${remoteDir.trimEnd('/')}/${f.filename}")
        }
    }
}
