package com.lumin.ssh.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val remotePath: String = "/Lumin/",
    val maxBackups: Int = 0,
)

data class R2Config(
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val endpoint: String = "",
    val region: String = "auto",
    val prefix: String = "Lumin/",
    val maxBackups: Int = 0,
)

data class FtpConfig(
    val mode: String = FTP_MODE_EXPLICIT_TLS,
    val host: String = "",
    val port: Int = 21,
    val username: String = "",
    val password: String = "",
    val remoteDir: String = "/Lumin/",
    val maxBackups: Int = 0,
)

data class SftpConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val remoteDir: String = "/Lumin/",
    val maxBackups: Int = 0,
)

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumin_lite", Context.MODE_PRIVATE)

    private fun keystoreSecretKey(): SecretKey {
        val alias = "lumin_sync_recovery_password"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptSecret(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreSecretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptSecret(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 28) { "加密数据格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreSecretKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
    }

    private fun loadSensitive(key: String): String {
        prefs.getString("${key}_enc", "").orEmpty().takeIf { it.isNotBlank() }?.let { return decryptSecret(it) }
        val legacy = prefs.getString(key, "").orEmpty()
        if (legacy.isNotBlank()) saveSensitive(key, legacy)
        return legacy
    }

    private fun saveSensitive(key: String, value: String) {
        check(prefs.edit().putString("${key}_enc", if (value.isBlank()) "" else encryptSecret(value)).remove(key).commit()) {
            "本地数据保存失败"
        }
    }

    fun saveSnapshot(snapshot: SyncSnapshot, lastSyncTime: Long? = null): Boolean {
        val editor = prefs.edit()
            .putString("connections_enc", encryptSecret(connectionsToJson(snapshot.connections)))
            .putString("credentials_enc", encryptSecret(credentialsToJson(snapshot.credentials)))
            .putString("proxy_nodes_enc", encryptSecret(proxyNodesToJson(snapshot.proxyNodes)))
            .putString("quick_commands_enc", snapshot.quickCommands.takeIf { it.isNotBlank() }?.let(::encryptSecret).orEmpty())
            .putString("ai_providers_enc", snapshot.aiProvidersRaw.takeIf { it.isNotBlank() }?.let(::encryptSecret).orEmpty())
            .putString("ai_global_settings_enc", snapshot.aiGlobalSettingsRaw.takeIf { it.isNotBlank() }?.let(::encryptSecret).orEmpty())
            .putLong("snapshot_time", snapshot.snapshotTime)
            .remove("connections").remove("credentials").remove("proxy_nodes")
            .remove("quick_commands").remove("ai_providers").remove("ai_global_settings")
        lastSyncTime?.let { editor.putLong("last_sync_time", it) }
        return editor.commit()
    }

    fun loadConnections(): List<Connection> = connectionsFromJson(loadSensitive("connections"))

    fun saveConnections(connections: List<Connection>) = saveSensitive("connections", connectionsToJson(connections))

    fun loadCredentials(): List<Credential> = credentialsFromJson(loadSensitive("credentials"))

    fun saveCredentials(credentials: List<Credential>) = saveSensitive("credentials", credentialsToJson(credentials))

    fun loadProxyNodes(): List<ProxyNode> = proxyNodesFromJson(loadSensitive("proxy_nodes"))

    fun saveProxyNodes(proxyNodes: List<ProxyNode>) = saveSensitive("proxy_nodes", proxyNodesToJson(proxyNodes))

    fun loadQuickCommandsRaw(): String = loadSensitive("quick_commands")

    fun saveQuickCommandsRaw(json: String) = saveSensitive("quick_commands", json)

    fun loadAiProvidersRaw(): String = loadSensitive("ai_providers")

    fun saveAiProvidersRaw(raw: String) = saveSensitive("ai_providers", raw)

    fun loadAiGlobalSettingsRaw(): String = loadSensitive("ai_global_settings")

    fun saveAiGlobalSettingsRaw(raw: String) = saveSensitive("ai_global_settings", raw)

    fun loadGroupOrder(): List<String> {
        val json = prefs.getString("group_order", "") ?: ""
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }

    fun saveGroupOrder(groups: List<String>) {
        prefs.edit().putString("group_order", JSONArray(groups).toString()).apply()
    }

    fun loadAppLanguage(): String = normalizeAppLanguage(prefs.getString("app_language", APP_LANGUAGE_ZH_CN))

    fun saveAppLanguage(language: String) {
        prefs.edit().putString("app_language", normalizeAppLanguage(language)).apply()
    }

    fun loadAppTheme(): String = normalizeAppTheme(prefs.getString("app_theme", THEME_SYSTEM))

    fun saveAppTheme(theme: String) {
        prefs.edit().putString("app_theme", normalizeAppTheme(theme)).apply()
    }

    fun loadShowInputBar() = prefs.getBoolean("terminal_show_input_bar", true)

    fun saveShowInputBar(show: Boolean) {
        prefs.edit().putBoolean("terminal_show_input_bar", show).apply()
    }

    fun loadHideSensitive() = prefs.getBoolean("hide_sensitive", false)

    fun saveHideSensitive(hide: Boolean) {
        prefs.edit().putBoolean("hide_sensitive", hide).apply()
    }

    fun loadTerminalFontSize() = prefs.getInt("terminal_font_size", 8)

    fun saveTerminalFontSize(size: Int) {
        prefs.edit().putInt("terminal_font_size", size.coerceIn(1, 30)).apply()
    }

    fun loadWebDavConfig() = WebDavConfig(
        url = loadSensitive("webdav_url"),
        username = loadSensitive("webdav_username"),
        password = loadSensitive("webdav_password"),
        remotePath = prefs.getString("webdav_remote_path", "/Lumin/") ?: "/Lumin/",
        maxBackups = prefs.getInt("webdav_max_backups", 0),
    )

    fun saveWebDavConfig(config: WebDavConfig) {
        saveSensitive("webdav_url", config.url)
        saveSensitive("webdav_username", config.username)
        saveSensitive("webdav_password", config.password)
        prefs.edit().putString("webdav_remote_path", config.remotePath).putInt("webdav_max_backups", config.maxBackups).apply()
    }

    fun loadR2Config() = R2Config(
        accessKeyId = loadSensitive("r2_access_key_id"),
        secretAccessKey = loadSensitive("r2_secret_access_key"),
        bucket = prefs.getString("r2_bucket", "") ?: "",
        endpoint = prefs.getString("r2_endpoint", "") ?: "",
        region = prefs.getString("r2_region", "auto") ?: "auto",
        prefix = prefs.getString("r2_prefix", "Lumin/") ?: "Lumin/",
        maxBackups = prefs.getInt("r2_max_backups", 0),
    )

    fun saveR2Config(config: R2Config) {
        saveSensitive("r2_access_key_id", config.accessKeyId)
        saveSensitive("r2_secret_access_key", config.secretAccessKey)
        prefs.edit()
            .putString("r2_bucket", config.bucket)
            .putString("r2_endpoint", config.endpoint)
            .putString("r2_region", config.region)
            .putString("r2_prefix", config.prefix)
            .putInt("r2_max_backups", config.maxBackups)
            .apply()
    }

    fun loadLastSyncTime(): Long = prefs.getLong("last_sync_time", 0L)
    fun saveLastSyncTime(time: Long) = prefs.edit().putLong("last_sync_time", time).apply()

    fun loadSyncMode(): String = prefs.getString("sync_mode", "all") ?: "all"
    fun saveSyncMode(mode: String) = prefs.edit().putString("sync_mode", mode).apply()

    fun loadAutoSyncEnabled(): Boolean = prefs.getBoolean("auto_sync_enabled", false)
    fun saveAutoSyncEnabled(enabled: Boolean) = prefs.edit().putBoolean("auto_sync_enabled", enabled).apply()

    fun loadRecoveryPassword(): String = loadSensitive("sync_recovery_password")

    fun saveRecoveryPassword(password: String) = saveSensitive("sync_recovery_password", password)

    fun hasRecoveryPassword(): Boolean = loadRecoveryPassword().isNotBlank()

    fun loadSnapshotTime(): Long = prefs.getLong("snapshot_time", 0L)
    fun saveSnapshotTime(time: Long) = prefs.edit().putLong("snapshot_time", time).apply()

    fun loadFtpConfig() = FtpConfig(
        mode = normalizeFtpMode(prefs.getString("ftp_mode", FTP_MODE_EXPLICIT_TLS).orEmpty()),
        host = loadSensitive("ftp_host"),
        port = prefs.getInt("ftp_port", 21),
        username = loadSensitive("ftp_username"),
        password = loadSensitive("ftp_password"),
        remoteDir = prefs.getString("ftp_remote_dir", "/Lumin/") ?: "/Lumin/",
        maxBackups = prefs.getInt("ftp_max_backups", 0),
    )

    fun saveFtpConfig(config: FtpConfig) {
        saveSensitive("ftp_host", config.host)
        saveSensitive("ftp_username", config.username)
        saveSensitive("ftp_password", config.password)
        prefs.edit()
            .putString("ftp_mode", normalizeFtpMode(config.mode))
            .putInt("ftp_port", config.port)
            .putString("ftp_remote_dir", config.remoteDir)
            .putInt("ftp_max_backups", config.maxBackups)
            .apply()
    }

    fun loadSftpConfig() = SftpConfig(
        host = loadSensitive("sftp_host"),
        port = prefs.getInt("sftp_port", 22),
        username = loadSensitive("sftp_username"),
        password = loadSensitive("sftp_password"),
        privateKey = loadSensitive("sftp_private_key"),
        passphrase = loadSensitive("sftp_passphrase"),
        remoteDir = prefs.getString("sftp_remote_dir", "/Lumin/") ?: "/Lumin/",
        maxBackups = prefs.getInt("sftp_max_backups", 0),
    )

    fun saveSftpConfig(config: SftpConfig) {
        saveSensitive("sftp_host", config.host)
        saveSensitive("sftp_username", config.username)
        saveSensitive("sftp_password", config.password)
        saveSensitive("sftp_private_key", config.privateKey)
        saveSensitive("sftp_passphrase", config.passphrase)
        prefs.edit()
            .putInt("sftp_port", config.port)
            .putString("sftp_remote_dir", config.remoteDir)
            .putInt("sftp_max_backups", config.maxBackups)
            .apply()
    }

    fun loadKnownHostFingerprint(host: String, port: Int): String = loadEndpointFingerprint("ssh_known_host_fingerprints", host, port)

    fun saveKnownHostFingerprint(host: String, port: Int, fingerprint: String) = saveEndpointFingerprint("ssh_known_host_fingerprints", host, port, fingerprint)

    fun loadFtpsCertificatePin(host: String, port: Int): String = loadEndpointFingerprint("ftps_certificate_pins", host, port)

    fun saveFtpsCertificatePin(host: String, port: Int, fingerprint: String) = saveEndpointFingerprint("ftps_certificate_pins", host, port, fingerprint)

    private fun loadEndpointFingerprint(prefKey: String, host: String, port: Int): String {
        val json = prefs.getString(prefKey, "{}") ?: "{}"
        val obj = JSONObject(json)
        val normalized = endpointTrustKey(host, port)
        obj.optString(normalized, "").takeIf { it.isNotBlank() }?.let { return it }
        val legacy = "${host.trim()}:$port"
        val value = obj.optString(legacy, "")
        if (value.isNotBlank() && legacy != normalized) saveEndpointFingerprint(prefKey, host, port, value)
        return value
    }

    private fun saveEndpointFingerprint(prefKey: String, host: String, port: Int, fingerprint: String) {
        val json = prefs.getString(prefKey, "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.put(endpointTrustKey(host, port), fingerprint)
        prefs.edit().putString(prefKey, obj.toString()).apply()
    }

    internal fun endpointTrustKey(host: String, port: Int): String {
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        val bracketed = if (normalizedHost.contains(':')) "[$normalizedHost]" else normalizedHost
        return "$bracketed:$port"
    }
}
