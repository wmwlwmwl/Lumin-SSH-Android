package com.lumin.ssh.android

import org.json.JSONArray
import org.json.JSONObject

data class Connection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val authMethod: String = "password",
    val privateKey: String = "",
    val passphrase: String = "",
    val group: String = "",
    val os: String = "",
    val credentialId: String = "",
    val proxyMode: String = "",
    val proxyNodeId: String = "",
    val proxyType: String = "socks5",
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val lastModified: Long = System.currentTimeMillis(),
)

data class Credential(
    val id: String,
    val name: String = "",
    val authMethod: String = "password",
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val lastModified: Long = System.currentTimeMillis(),
)

data class ProxyNode(
    val id: String,
    val name: String = "",
    val type: String = "socks5",
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

fun Connection.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("host", host)
    put("port", port)
    put("username", username)
    put("password", password)
    put("authMethod", authMethod)
    put("privateKey", privateKey)
    put("passphrase", passphrase)
    put("group", group)
    put("os", os)
    put("credentialId", credentialId)
    put("proxyMode", proxyMode)
    put("proxyNodeId", proxyNodeId)
    put("proxyType", proxyType)
    put("proxyHost", proxyHost)
    put("proxyPort", proxyPort)
    put("proxyUsername", proxyUsername)
    put("proxyPassword", proxyPassword)
    put("last_modified", lastModified)
}

fun JSONObject.toConnection() = Connection(
    id = optString("id"),
    name = optString("name", optString("host")),
    host = optString("host"),
    port = optInt("port", 22),
    username = optString("username"),
    password = optString("password"),
    authMethod = optString("authMethod", "password"),
    privateKey = optString("privateKey"),
    passphrase = optString("passphrase"),
    group = optString("group"),
    os = optString("os"),
    credentialId = optString("credentialId"),
    proxyMode = optString("proxyMode"),
    proxyNodeId = optString("proxyNodeId"),
    proxyType = if (optString("proxyType") == "http") "http" else "socks5",
    proxyHost = optString("proxyHost"),
    proxyPort = optInt("proxyPort", 1080),
    proxyUsername = optString("proxyUsername"),
    proxyPassword = optString("proxyPassword"),
    lastModified = optLong("last_modified", System.currentTimeMillis()),
)

fun connectionsToJson(connections: List<Connection>) = JSONArray().apply {
    connections.forEach { put(it.toJson()) }
}.toString()

fun connectionsFromJson(json: String): List<Connection> {
    if (json.isBlank()) return emptyList()
    val array = JSONArray(json)
    return List(array.length()) { array.getJSONObject(it).toConnection() }
}

fun Credential.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("authMethod", authMethod)
    put("username", username)
    put("password", password)
    put("privateKey", privateKey)
    put("passphrase", passphrase)
    put("last_modified", lastModified)
}

fun JSONObject.toCredential() = Credential(
    id = optString("id"),
    name = optString("name", optString("id")),
    authMethod = optString("authMethod", "password"),
    username = optString("username"),
    password = optString("password"),
    privateKey = optString("privateKey"),
    passphrase = optString("passphrase"),
    lastModified = optLong("last_modified", System.currentTimeMillis()),
)

fun credentialsToJson(credentials: List<Credential>) = JSONArray().apply {
    credentials.forEach { put(it.toJson()) }
}.toString()

fun credentialsFromJson(json: String): List<Credential> {
    if (json.isBlank()) return emptyList()
    val array = JSONArray(json)
    return List(array.length()) { array.getJSONObject(it).toCredential() }
}

fun ProxyNode.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("type", if (type == "http") "http" else "socks5")
    put("host", host)
    put("port", port)
    put("username", username)
    put("password", password)
    put("updatedAt", updatedAt)
}

fun JSONObject.toProxyNode() = ProxyNode(
    id = optString("id"),
    name = optString("name"),
    type = if (optString("type") == "http") "http" else "socks5",
    host = optString("host"),
    port = optInt("port", 1080),
    username = optString("username"),
    password = optString("password"),
    updatedAt = optLong("updatedAt", System.currentTimeMillis()),
)

fun proxyNodesToJson(proxyNodes: List<ProxyNode>) = JSONArray().apply {
    proxyNodes.forEach { put(it.toJson()) }
}.toString()

fun proxyNodesFromJson(json: String): List<ProxyNode> {
    if (json.isBlank()) return emptyList()
    val array = JSONArray(json)
    return List(array.length()) { array.getJSONObject(it).toProxyNode() }
}

data class QuickCommand(
    val name: String,
    val command: String,
    val group: String = "",
    val addCR: Boolean = true,
)

sealed class QuickCommandNode {
    data class Folder(val path: String, val name: String, val children: List<QuickCommandNode>, val indexPath: List<Int>) : QuickCommandNode()
    data class Command(val item: QuickCommand, val indexPath: List<Int>) : QuickCommandNode()
}

data class SyncTombstone(
    val id: String,
    val deletedAt: Long,
)

data class SyncSnapshot(
    val connections: List<Connection>,
    val credentials: List<Credential>,
    val proxyNodes: List<ProxyNode> = emptyList(),
    val quickCommands: String = "",
    val aiProvidersRaw: String = "",
    val aiGlobalSettingsRaw: String = "",
    val deletedConnections: List<SyncTombstone> = emptyList(),
    val deletedCredentials: List<SyncTombstone> = emptyList(),
    // 清理删除记录水位线：合并时丢弃 deleted_at 早于此值的远端墓碑
    val tombstonePrunedBefore: Long = 0,
    val snapshotTime: Long = 0,
)

fun SyncTombstone.toJson() = JSONObject().apply {
    put("id", id)
    put("deleted_at", deletedAt)
}

fun JSONObject.toSyncTombstone(): SyncTombstone? {
    val id = optString("id").trim()
    val deletedAt = optLong("deleted_at", 0L)
    if (id.isBlank() || deletedAt <= 0L) return null
    return SyncTombstone(id, deletedAt)
}

fun tombstonesFromJsonArray(array: JSONArray?): List<SyncTombstone> {
    if (array == null) return emptyList()
    val out = ArrayList<SyncTombstone>(array.length())
    for (i in 0 until array.length()) {
        array.optJSONObject(i)?.toSyncTombstone()?.let { out += it }
    }
    return mergeTombstoneLists(emptyList(), out)
}

fun tombstonesToJsonArray(list: List<SyncTombstone>) = JSONArray().apply {
    mergeTombstoneLists(emptyList(), list).forEach { put(it.toJson()) }
}

fun mergeTombstoneMaps(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val out = a.toMutableMap()
    for ((id, at) in b) {
        val prev = out[id]
        if (prev == null || at > prev) out[id] = at
    }
    return out
}

fun tombstoneMap(list: List<SyncTombstone>): Map<String, Long> {
    val out = linkedMapOf<String, Long>()
    for (t in list) {
        val id = t.id.trim()
        if (id.isBlank() || t.deletedAt <= 0L) continue
        val prev = out[id]
        if (prev == null || t.deletedAt > prev) out[id] = t.deletedAt
    }
    return out
}

fun tombstonesFromMap(map: Map<String, Long>): List<SyncTombstone> =
    map.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .map { SyncTombstone(it.key, it.value) }

fun mergeTombstoneLists(local: List<SyncTombstone>, remote: List<SyncTombstone>): List<SyncTombstone> =
    tombstonesFromMap(mergeTombstoneMaps(tombstoneMap(local), tombstoneMap(remote)))

fun filterTombstonesNotBefore(list: List<SyncTombstone>, prunedBefore: Long): List<SyncTombstone> {
    if (prunedBefore <= 0L || list.isEmpty()) return list
    val map = tombstoneMap(list).toMutableMap()
    for ((id, at) in map.toList()) {
        if (at < prunedBefore) map.remove(id)
    }
    return tombstonesFromMap(map)
}

fun maxTombstoneDeletedAt(vararg lists: List<SyncTombstone>): Long {
    var max = 0L
    for (list in lists) {
        for (t in list) if (t.deletedAt > max) max = t.deletedAt
    }
    return max
}

/** 合并墓碑并应用清理水位线，防止「清理删除记录」后又被对端旧墓碑并回来。 */
fun mergeTombsWithPruneWatermark(
    localConn: List<SyncTombstone>,
    remoteConn: List<SyncTombstone>,
    localCred: List<SyncTombstone>,
    remoteCred: List<SyncTombstone>,
    localPB: Long,
    remotePB: Long,
): Triple<List<SyncTombstone>, List<SyncTombstone>, Long> {
    val prunedBefore = maxOf(localPB, remotePB)
    val conn = filterTombstonesNotBefore(mergeTombstoneLists(localConn, remoteConn), prunedBefore)
    val cred = filterTombstonesNotBefore(mergeTombstoneLists(localCred, remoteCred), prunedBefore)
    return Triple(conn, cred, prunedBefore)
}

fun syncSnapshotFromJson(root: JSONObject): SyncSnapshot {
    val connectionArray = root.optJSONArray("connections") ?: throw IllegalStateException("备份中没有服务器列表")
    val credentialArray = root.optJSONArray("credentials")
    val proxyNodeArray = root.optJSONArray("proxy_nodes")
    return SyncSnapshot(
        connections = List(connectionArray.length()) { connectionArray.getJSONObject(it).toConnection() },
        credentials = if (credentialArray == null) emptyList() else List(credentialArray.length()) { credentialArray.getJSONObject(it).toCredential() },
        proxyNodes = if (proxyNodeArray == null) emptyList() else List(proxyNodeArray.length()) { proxyNodeArray.getJSONObject(it).toProxyNode() },
        quickCommands = root.optString("quick_commands"),
        aiProvidersRaw = root.optJSONArray("ai_providers")?.toString() ?: "",
        aiGlobalSettingsRaw = root.optJSONObject("ai_global_settings")?.toString() ?: "",
        deletedConnections = tombstonesFromJsonArray(root.optJSONArray("deleted_connections")),
        deletedCredentials = tombstonesFromJsonArray(root.optJSONArray("deleted_credentials")),
        tombstonePrunedBefore = root.optLong("tombstone_pruned_before", 0),
        snapshotTime = root.optLong("snapshot_time", 0),
    )
}

data class ImportMergeResult(
    val connections: List<Connection>,
    val credentials: List<Credential>,
    val proxyNodes: List<ProxyNode>,
    val quickCommands: String,
    val aiProvidersRaw: String,
    val aiGlobalSettingsRaw: String,
    val imported: Int,
    val skipped: Int,
)

fun mergeImportedSnapshot(
    snapshot: SyncSnapshot,
    localConnections: List<Connection>,
    localCredentials: List<Credential>,
    localProxyNodes: List<ProxyNode>,
    localQuickCommands: String,
    localAiProvidersRaw: String,
    localAiGlobalSettingsRaw: String,
    now: Long,
): ImportMergeResult {
    val existingKeys = localConnections.map { Triple(it.host, it.port, it.username) }.toMutableSet()
    val usedIds = localConnections.map { it.id }.toMutableSet()
    var skipped = 0
    val added = snapshot.connections.mapNotNull { imported ->
        val key = Triple(imported.host, imported.port, imported.username)
        if (!existingKeys.add(key)) {
            skipped++
            null
        } else {
            var id = imported.id
            if (id.isBlank() || !usedIds.add(id)) {
                do id = java.util.UUID.randomUUID().toString() while (!usedIds.add(id))
            }
            imported.copy(id = id, lastModified = now)
        }
    }
    val referencedCredentialIds = added.map { it.credentialId }.filter { it.isNotBlank() }.toSet()
    val existingCredentialIds = localCredentials.map { it.id }.toSet()
    val addedCredentials = snapshot.credentials
        .filter { it.id in referencedCredentialIds && it.id !in existingCredentialIds }
        .distinctBy { it.id }.map { it.copy(lastModified = now) }
    val referencedProxyIds = added.filter { it.proxyMode == "node" }.map { it.proxyNodeId }.filter { it.isNotBlank() }.toSet()
    val existingProxyIds = localProxyNodes.map { it.id }.toSet()
    val addedProxies = snapshot.proxyNodes
        .filter { it.id in referencedProxyIds && it.id !in existingProxyIds }
        .distinctBy { it.id }.map { it.copy(updatedAt = now) }
    return ImportMergeResult(
        localConnections + added,
        localCredentials + addedCredentials,
        localProxyNodes + addedProxies,
        SyncHelper.mergeQuickCommands(localQuickCommands, snapshot.quickCommands, -1L),
        SyncHelper.mergeAiProvidersForImport(localAiProvidersRaw, snapshot.aiProvidersRaw),
        if (localAiGlobalSettingsRaw.isBlank()) snapshot.aiGlobalSettingsRaw else localAiGlobalSettingsRaw,
        added.size,
        skipped,
    )
}

fun applyCredentials(connections: List<Connection>, root: JSONObject): List<Connection> {
    val credentials = syncSnapshotFromJson(root).credentials.associateBy { it.id }
    return connections.map { conn ->
        val cred = credentials[conn.credentialId]
        if (cred == null) conn else conn.copy(
            username = cred.username.ifBlank { conn.username },
            password = cred.password,
            authMethod = cred.authMethod,
            privateKey = cred.privateKey,
            passphrase = cred.passphrase,
        )
    }
}

fun quickCommandTreeFromJson(json: String): List<QuickCommandNode> {
    if (json.isBlank()) return emptyList()
    fun collect(array: JSONArray, group: String = "", parentPath: List<Int> = emptyList()): List<QuickCommandNode> {
        val result = mutableListOf<QuickCommandNode>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val indexPath = parentPath + i
            val children = item.optJSONArray("children")
            if (children != null) {
                val name = item.optString("name")
                val path = listOf(group, name).filter { it.isNotBlank() }.joinToString(" / ")
                result += QuickCommandNode.Folder(path = path, name = name, children = collect(children, path, indexPath), indexPath = indexPath)
            } else {
                val command = item.optString("command").trim()
                if (command.isNotBlank()) {
                    result += QuickCommandNode.Command(
                        QuickCommand(
                            name = item.optString("name", command),
                            command = command,
                            group = group,
                            addCR = item.optBoolean("addCR", true),
                        ),
                        indexPath = indexPath,
                    )
                }
            }
        }
        return result
    }
    return runCatching { collect(JSONArray(json)) }.getOrDefault(emptyList())
}

fun quickCommandsFromJson(json: String): List<QuickCommand> {
    if (json.isBlank()) return emptyList()
    fun collect(array: JSONArray, group: String = ""): List<QuickCommand> {
        val result = mutableListOf<QuickCommand>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val children = item.optJSONArray("children")
            if (children != null) {
                val name = item.optString("name")
                val path = listOf(group, name).filter { it.isNotBlank() }.joinToString(" / ")
                result += collect(children, path)
            } else {
                val command = item.optString("command").trim()
                if (command.isNotBlank()) {
                    result += QuickCommand(
                        name = item.optString("name", command),
                        command = command,
                        group = group,
                        addCR = item.optBoolean("addCR", true),
                    )
                }
            }
        }
        return result
    }
    return runCatching { collect(JSONArray(json)) }.getOrDefault(emptyList())
}

fun desktopSnapshotJson(
    connections: List<Connection>,
    credentials: List<Credential> = emptyList(),
    quickCommands: String = "",
    proxyNodes: List<ProxyNode> = emptyList(),
    aiProvidersRaw: String = "",
    aiGlobalSettingsRaw: String = "",
    snapshotTime: Long,
    deletedConnections: List<SyncTombstone> = emptyList(),
    deletedCredentials: List<SyncTombstone> = emptyList(),
    tombstonePrunedBefore: Long = 0,
) = JSONObject().apply {
    put("connections", JSONArray().apply { connections.forEach { put(it.toJson()) } })
    put("credentials", JSONArray().apply { credentials.forEach { put(it.toJson()) } })
    put("proxy_nodes", JSONArray().apply { proxyNodes.forEach { put(it.toJson()) } })
    put("quick_commands", quickCommands)
    if (aiProvidersRaw.isNotBlank()) put("ai_providers", JSONArray(aiProvidersRaw))
    if (aiGlobalSettingsRaw.isNotBlank()) put("ai_global_settings", JSONObject(aiGlobalSettingsRaw))
    // 始终写出字段，避免旧客户端/对端用「字段缺失」当无墓碑语义；空数组表示明确无删除记录
    put("deleted_connections", tombstonesToJsonArray(deletedConnections))
    put("deleted_credentials", tombstonesToJsonArray(deletedCredentials))
    if (tombstonePrunedBefore > 0L) put("tombstone_pruned_before", tombstonePrunedBefore)
    put("snapshot_time", snapshotTime)
}.toString(2)
