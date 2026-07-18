package com.lumin.ssh.android

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.w3c.dom.Element
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

internal fun normalizeWebDavUrl(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
}

class WebDavSync(
    url: String,
    private val username: String,
    private val password: String,
    private val remotePath: String,
) : SyncProvider {
    private val url = normalizeWebDavUrl(url)
    private val client = OkHttpClient()
    private val auth = Credentials.basic(username, password)
    private val normalizedRemotePath = remotePath.ifBlank { "/Lumin/" }.let { if (it.endsWith("/")) it else "$it/" }

    fun testConnection() {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 207) throw IllegalStateException("WebDAV 连接测试失败: HTTP ${response.code}")
        }
    }

    override fun restoreSnapshot(name: String, recoveryPassword: String): SyncSnapshot {
        val text = readText(joinPath(normalizedRemotePath, name))
        return parseSnapshotPayload(text, recoveryPassword.ifBlank { null })
    }

    fun restoreLatestConnections(): List<Connection> = restoreLatestSnapshot().connections

    override fun backupConnections(connections: List<Connection>, credentials: List<Credential>, quickCommands: String, proxyNodes: List<ProxyNode>, aiProvidersRaw: String, aiGlobalSettingsRaw: String, snapshotTime: Long, maxBackups: Int, recoveryPassword: String): String {
        ensureRemoteDir()
        val snapshotJson = desktopSnapshotJson(connections, credentials, quickCommands, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, snapshotTime)
        val encrypted = recoveryPassword.isNotBlank()
        val content = if (encrypted) encryptLumin2(snapshotJson, recoveryPassword) else snapshotJson
        val fileName = backupFileName(encrypted)
        writeText(joinPath(normalizedRemotePath, fileName), content)
        return fileName
    }

    override fun pruneOldBackups(maxBackups: Int) {
        val names = listBackupNames()
        names.sortedDescending().drop(maxBackups).forEach(::deleteBackup)
    }

    override fun deleteBackup(name: String) = deleteFile(joinPath(normalizedRemotePath, name))

    private fun deleteFile(path: String) {
        val request = Request.Builder()
            .url(joinUrl(url, path))
            .header("Authorization", auth)
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("WebDAV 删除失败: HTTP ${response.code}")
        }
    }

    override fun listBackupNames(): List<String> {
        val body = "".toRequestBody(null)
        val request = Request.Builder()
            .url(joinUrl(url, normalizedRemotePath))
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("WebDAV 列表失败: HTTP ${response.code}")
            val xml = response.body?.string().orEmpty()
            val factory = DocumentBuilderFactory.newInstance().apply {
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val hrefs = doc.getElementsByTagNameNS("*", "href")
            val names = mutableListOf<String>()
            for (i in 0 until hrefs.length) {
                val href = (hrefs.item(i) as Element).textContent
                val decoded = URLDecoder.decode(href.substringAfterLast('/'), "UTF-8")
                if (isBackupName(decoded)) names += decoded
            }
            return names
        }
    }

    private fun readText(path: String): String {
        val request = Request.Builder()
            .url(joinUrl(url, path))
            .header("Authorization", auth)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("WebDAV 读取失败: HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun writeText(path: String, text: String) {
        val request = Request.Builder()
            .url(joinUrl(url, path))
            .header("Authorization", auth)
            .put(text.toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("WebDAV 上传失败: HTTP ${response.code}")
        }
    }

    private fun ensureRemoteDir() {
        var current = "/"
        normalizedRemotePath.trim('/').split('/').filter { it.isNotEmpty() }.forEach { part ->
            current = joinPath(current, part) + "/"
            val request = Request.Builder()
                .url(joinUrl(url, current))
                .header("Authorization", auth)
                .method("MKCOL", "".toRequestBody(null))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299 && response.code != 405) {
                    throw IllegalStateException("WebDAV 创建目录失败: HTTP ${response.code}")
                }
            }
        }
    }

    private fun joinUrl(base: String, path: String) = base.trimEnd('/') + "/" + path.trimStart('/')
    private fun joinPath(dir: String, name: String) = dir.trimEnd('/') + "/" + name
}
