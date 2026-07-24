package com.lumin.ssh.android

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

internal fun normalizeWebDavUrl(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
}

/**
 * 从 PROPFIND href 得到文件名。
 * 禁止对整段使用 URLDecoder.decode：它会把 + 解成空格，导致 +0800 变成 " 0800"，同步读文件 404。
 */
internal fun webDavHrefFileName(href: String): String {
    var s = href.trim()
    if (s.isEmpty()) return ""
    s = s.substringBefore('#')
    s = s.substringBefore('?')
    val isCollection = s.endsWith('/')
    if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
        val afterScheme = s.substringAfter("://")
        s = afterScheme.substringAfter('/', missingDelimiterValue = "")
        s = "/$s"
    }
    if (isCollection || s.endsWith('/')) return ""
    s = s.trimEnd('/')
    if (s.isEmpty()) return ""
    val last = s.substringAfterLast('/', missingDelimiterValue = s)
    return decodeWebDavFileName(last)
}

/** 只解码 %XX；字面 + 保持为 +（时区 +0800）。 */
internal fun decodeWebDavFileName(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hex = raw.substring(i + 1, i + 3)
            val code = hex.toIntOrNull(16)
            if (code != null) {
                sb.append(code.toChar())
                i += 3
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString().trim()
}

/** 进程内共享，避免每次同步新建线程池/连接池 */
internal val SharedOkHttpClient = OkHttpClient()

class WebDavSync(
    url: String,
    private val username: String,
    private val password: String,
    private val remotePath: String,
) : SyncProvider {
    private val url = normalizeWebDavUrl(url)
    private val client = SharedOkHttpClient
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

    override fun backupConnections(connections: List<Connection>, credentials: List<Credential>, quickCommands: String, proxyNodes: List<ProxyNode>, aiProvidersRaw: String, aiGlobalSettingsRaw: String, snapshotTime: Long, maxBackups: Int, recoveryPassword: String, deletedConnections: List<SyncTombstone>, deletedCredentials: List<SyncTombstone>, tombstonePrunedBefore: Long): String {
        ensureRemoteDir()
        val snapshotJson = desktopSnapshotJson(connections, credentials, quickCommands, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, snapshotTime, deletedConnections, deletedCredentials, tombstonePrunedBefore)
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
        val target = joinUrl(url, normalizedRemotePath)
        // 目录存在但为空 → 返回 emptyList()（无备份，可上传本地）
        // 目录不存在 (404) → 必须抛错，前端才能弹出「重新创建并重试」
        val withBody = propfindBody(target, true)
        var names = parseBackupNames(withBody)
        if (names.isEmpty()) {
            val emptyBody = propfindBody(target, false)
            names = parseBackupNames(emptyBody)
        }
        return names
    }

    private fun propfindBody(target: String, withXmlBody: Boolean): String {
        val body = if (withXmlBody) {
            ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>")
                .toRequestBody("text/xml; charset=utf-8".toMediaType())
        } else {
            "".toRequestBody(null)
        }
        val builder = Request.Builder()
            .url(target)
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", body)
        if (withXmlBody) builder.header("Content-Type", "text/xml; charset=utf-8")
        client.newCall(builder.build()).execute().use { response ->
            // 404：同步目录本身不存在（被删），不是“有目录但无备份文件”
            if (response.code == 404) {
                throw IllegalStateException("WebDAV 列表失败: HTTP 404（远程目录可能不存在: $normalizedRemotePath）")
            }
            if (!response.isSuccessful && response.code != 207) {
                throw IllegalStateException("WebDAV 列表失败: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun parseBackupNames(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
            isNamespaceAware = true
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(xml.byteInputStream()) }.getOrElse { return emptyList() }
        val hrefs = doc.getElementsByTagNameNS("*", "href")
        val names = linkedSetOf<String>()
        for (i in 0 until hrefs.length) {
            val href = (hrefs.item(i) as Element).textContent.orEmpty()
            // webDavHrefFileName 内部已 decodeWebDavFileName，勿再 URLDecoder（会把 + 变空格）
            val decoded = webDavHrefFileName(href)
            if (isBackupName(decoded)) names += decoded
        }
        return names.sortedDescending()
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

    override fun ensureRemoteDir() {
        var current = "/"
        normalizedRemotePath.trim('/').split('/').filter { it.isNotEmpty() }.forEach { part ->
            current = joinPath(current, part) + "/"
            val request = Request.Builder()
                .url(joinUrl(url, current))
                .header("Authorization", auth)
                .method("MKCOL", "".toRequestBody(null))
                .build()
            client.newCall(request).execute().use { response ->
                // 201 创建成功；200/204 部分实现；405 已存在
                if (response.code !in 200..299 && response.code != 405) {
                    throw IllegalStateException("WebDAV 创建目录失败: HTTP ${response.code}")
                }
            }
        }
    }

    private fun joinUrl(base: String, path: String): String {
        val keepSlash = path.endsWith('/')
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val encoded = parts.joinToString("/") { encodeWebDavSegment(it) }
        val suffix = when {
            encoded.isEmpty() && keepSlash -> "/"
            encoded.isEmpty() -> ""
            keepSlash -> "/$encoded/"
            else -> "/$encoded"
        }
        return base.trimEnd('/') + suffix
    }

    private fun joinPath(dir: String, name: String) = dir.trimEnd('/') + "/" + name
}

/** 文件名里的 +0800 等：+ 必须编成 %2B，不能当空格。 */
internal fun encodeWebDavSegment(segment: String): String {
    val sb = StringBuilder(segment.length + 8)
    for (ch in segment) {
        when {
            ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~' -> sb.append(ch)
            else -> sb.append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}
