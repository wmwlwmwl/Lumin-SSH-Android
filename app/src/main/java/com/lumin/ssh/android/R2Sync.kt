package com.lumin.ssh.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

class R2Sync(
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val bucket: String,
    endpoint: String,
    region: String,
    prefix: String,
) : SyncProvider {
    private val client = OkHttpClient()
    private val endpoint = endpoint.trim().trimEnd('/').removePrefix("https://").removePrefix("http://")
    private val prefix = prefix.ifBlank { "Lumin/" }.let { if (it.endsWith('/')) it else "$it/" }
    private val region = region.ifBlank { "auto" }
    private val service = "s3"

    override fun restoreSnapshot(name: String, recoveryPassword: String): SyncSnapshot {
        val text = request("GET", prefix + name).use { it.body?.string().orEmpty() }
        return parseSnapshotPayload(text, recoveryPassword.ifBlank { null })
    }

    fun restoreLatestConnections(): List<Connection> = restoreLatestSnapshot().connections

    override fun backupConnections(connections: List<Connection>, credentials: List<Credential>, quickCommands: String, proxyNodes: List<ProxyNode>, aiProvidersRaw: String, aiGlobalSettingsRaw: String, snapshotTime: Long, maxBackups: Int, recoveryPassword: String): String {
        val snapshotJson = desktopSnapshotJson(connections, credentials, quickCommands, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, snapshotTime)
        val encrypted = recoveryPassword.isNotBlank()
        val content = if (encrypted) encryptLumin2(snapshotJson, recoveryPassword) else snapshotJson
        val fileName = backupFileName(encrypted)
        request("PUT", prefix + fileName, content).use { }
        return fileName
    }

    override fun pruneOldBackups(maxBackups: Int) {
        listBackupNames().drop(maxBackups).forEach(::deleteBackup)
    }

    override fun deleteBackup(name: String) {
        request("DELETE", prefix + name).use { }
    }

    override fun listBackupNames(): List<String> {
        val xml = request("GET", "", query = mapOf("list-type" to "2", "prefix" to prefix)).use { it.body?.string().orEmpty() }
        val factory = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val keys = doc.getElementsByTagName("Key")
        val names = mutableListOf<String>()
        for (i in 0 until keys.length) {
            val name = (keys.item(i) as Element).textContent.removePrefix(prefix)
            if (isBackupName(name)) names += name
        }
        return names.sortedDescending()
    }

    fun testConnection() {
        request("HEAD", "").use { }
    }

    private fun request(method: String, key: String, body: String = "", query: Map<String, String> = emptyMap()) =
        client.newCall(signedRequest(method, key, body, query)).execute().also {
            if (!it.isSuccessful) {
                val message = it.body?.string().orEmpty().ifBlank { it.message }
                it.close()
                throw IllegalStateException("R2 请求失败: HTTP ${it.code} $message")
            }
        }

    private fun signedRequest(method: String, key: String, body: String, query: Map<String, String>): Request {
        val now = Date()
        val amzDate = utc("yyyyMMdd'T'HHmmss'Z'").format(now)
        val dateStamp = utc("yyyyMMdd").format(now)
        val payloadHash = sha256Hex(body.toByteArray())
        val canonicalUri = "/${awsEncode(bucket)}/${key.split('/').filter { it.isNotEmpty() }.joinToString("/") { awsEncode(it) }}"
        val canonicalQuery = query.toSortedMap().entries.joinToString("&") { "${awsEncode(it.key)}=${awsEncode(it.value)}" }
        val canonicalHeaders = "host:$endpoint\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = listOf(method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest.toByteArray())}"
        val signature = hmacHex(signingKey(dateStamp), stringToSign)
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
        val url = "https://$endpoint$canonicalUri" + if (canonicalQuery.isBlank()) "" else "?$canonicalQuery"
        return Request.Builder()
            .url(url)
            .header("Host", endpoint)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", authorization)
            .method(method, if (method == "GET" || method == "HEAD") null else body.toRequestBody("application/octet-stream".toMediaType()))
            .build()
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmacBytes(("AWS4$secretAccessKey").toByteArray(), dateStamp)
        val kRegion = hmacBytes(kDate, region)
        val kService = hmacBytes(kRegion, service)
        return hmacBytes(kService, "aws4_request")
    }

    private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun awsEncode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    private fun sha256Hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun hmacBytes(key: ByteArray, data: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())
    private fun hmacHex(key: ByteArray, data: String) = hmacBytes(key, data).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
