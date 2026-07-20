package com.lumin.ssh.android

import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

const val FTP_MODE_EXPLICIT_TLS = "explicit_tls"
const val FTP_MODE_PLAIN = "plain"
internal val FTP_TLS_PROTOCOLS = arrayOf("TLSv1.2")

internal fun normalizeFtpMode(mode: String): String = when (mode.trim()) {
    "", FTP_MODE_EXPLICIT_TLS -> FTP_MODE_EXPLICIT_TLS
    FTP_MODE_PLAIN -> FTP_MODE_PLAIN
    else -> throw IllegalArgumentException("不支持的 FTP 连接模式: $mode")
}

data class FtpsCertificateConfirm(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val previousFingerprint: String,
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
)

private fun certificateFingerprint(certificate: X509Certificate): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    return "SHA256:" + Base64.encodeToString(hash, Base64.NO_WRAP).trimEnd('=')
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

private class FtpsPinTrustManager(
    private val store: LocalStore,
    private val host: String,
    private val port: Int,
    private val confirm: (suspend (FtpsCertificateConfirm) -> HostKeyAction)?,
) : X509TrustManager {
    private val system = systemTrustManager()
    private var acceptedFingerprint = ""
    private var saveRequested = false

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificates = chain?.takeIf { it.isNotEmpty() } ?: throw CertificateException("FTPS 服务器未提供证书")
        val leaf = certificates[0]
        leaf.checkValidity()
        runCatching { system.checkServerTrusted(certificates, authType) }.onSuccess { return }

        val fingerprint = certificateFingerprint(leaf)
        if (acceptedFingerprint == fingerprint) return
        val saved = store.loadFtpsCertificatePin(host, port)
        if (saved == fingerprint) return
        val action = confirm?.let {
            runBlocking {
                it(FtpsCertificateConfirm(host, port, fingerprint, saved, leaf.subjectX500Principal.name, leaf.issuerX500Principal.name, leaf.notBefore.toString(), leaf.notAfter.toString()))
            }
        } ?: HostKeyAction.Cancel
        when (action) {
            HostKeyAction.Cancel -> throw PeerTrustRejectedException("FTPS 证书未接受: $host:$port")
            HostKeyAction.AcceptOnce -> acceptedFingerprint = fingerprint
            HostKeyAction.AcceptAndSave -> {
                acceptedFingerprint = fingerprint
                saveRequested = true
            }
        }
    }

    fun persistAfterSuccessfulLogin() {
        if (saveRequested && acceptedFingerprint.isNotBlank()) {
            store.saveFtpsCertificatePin(host, port, acceptedFingerprint)
            saveRequested = false
        }
    }
}

class FtpSync(
    private val host: String,
    private val port: Int = 21,
    private val username: String,
    private val password: String,
    private val remoteDir: String = "/Lumin/",
    mode: String = FTP_MODE_EXPLICIT_TLS,
    private val store: LocalStore? = null,
    private val confirmCertificate: (suspend (FtpsCertificateConfirm) -> HostKeyAction)? = null,
) : SyncProvider {
    private val mode = normalizeFtpMode(mode)

    private fun connect(ensureRemoteDir: Boolean = true): FTPClient {
        val targetHost = host.trim().removePrefix("ftp://").removePrefix("ftps://")
        var trustManager: FtpsPinTrustManager? = null
        val client: FTPClient = if (mode == FTP_MODE_PLAIN) {
            FTPClient()
        } else {
            val localStore = store ?: throw IllegalStateException("FTPS 证书存储不可用")
            trustManager = FtpsPinTrustManager(localStore, targetHost, port, confirmCertificate)
            FTPSClient("TLS", false).apply {
                setTrustManager(trustManager)
                setEndpointCheckingEnabled(true)
                enabledProtocols = FTP_TLS_PROTOCOLS.copyOf()
            }
        }
        try {
            client.connect(targetHost, port)
            if (!client.login(username, password)) throw IllegalStateException("FTP 登录失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
            trustManager?.persistAfterSuccessfulLogin()
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IllegalStateException("FTP 切换二进制模式失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
            }
            client.setUseEPSVwithIPv4(true)
            client.enterLocalPassiveMode()
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
        try {
            if (ensureRemoteDir) ensureDir(client, remoteDir)
            return client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    fun testConnection() {
        val client = connect(ensureRemoteDir = false)
        try {
            if (!client.sendNoOp()) throw IllegalStateException("FTP 连接测试失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun ensureDir(client: FTPClient, dir: String) {
        val parts = dir.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            if (!client.changeWorkingDirectory(current)) {
                if (!client.makeDirectory(current) || !client.changeWorkingDirectory(current)) {
                    throw IllegalStateException("FTP 创建目录失败: $current (${client.replyCode} ${client.replyString.orEmpty().trim()})")
                }
            }
        }
    }

    override fun listBackupNames(): List<String> {
        val client = connect()
        try {
            return client.listFiles(remoteDir)
                .filter { isBackupName(it.name) }
                .map { it.name }
                .sortedDescending()
        } finally {
            runCatching { client.disconnect() }
        }
    }

    override fun restoreSnapshot(name: String, recoveryPassword: String): SyncSnapshot {
        val client = connect()
        try {
            val out = ByteArrayOutputStream()
            if (!client.retrieveFile("${remoteDir.trimEnd('/')}/$name", out)) {
                throw IllegalStateException("FTP 下载失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
            }
            return parseSnapshotPayload(out.toString(), recoveryPassword.ifBlank { null })
        } finally {
            runCatching { client.disconnect() }
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
        val client = connect()
        try {
            val path = "${remoteDir.trimEnd('/')}/$fileName"
            val output = client.storeFileStream(path)
                ?: throw IllegalStateException("FTP 上传启动失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
            output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (!client.completePendingCommand()) {
                throw IllegalStateException("FTP 上传失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
            }
        } finally {
            runCatching { client.disconnect() }
        }
        return fileName
    }

    override fun deleteBackup(name: String) {
        val client = connect()
        try {
            if (!client.deleteFile("${remoteDir.trimEnd('/')}/$name")) {
                throw IllegalStateException("FTP 删除失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    override fun pruneOldBackups(maxBackups: Int) {
        val client = connect()
        try {
            cleanupOldBackups(client, maxBackups)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun cleanupOldBackups(client: FTPClient, maxBackups: Int) {
        val files = client.listFiles(remoteDir).filter { f ->
            isBackupName(f.name)
        }
        files.sortedByDescending { it.timestamp?.timeInMillis ?: 0L }.drop(maxBackups).forEach { f ->
            if (!client.deleteFile("${remoteDir.trimEnd('/')}/${f.name}")) {
                throw IllegalStateException("FTP 删除失败: ${client.replyCode} ${client.replyString.orEmpty().trim()}")
            }
        }
    }
}
