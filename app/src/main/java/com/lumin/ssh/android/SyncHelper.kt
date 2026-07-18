package com.lumin.ssh.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

interface SyncProvider {
    fun listBackupNames(): List<String>
    fun deleteBackup(name: String)
    fun pruneOldBackups(maxBackups: Int)
    fun restoreSnapshot(name: String, recoveryPassword: String = ""): SyncSnapshot
    fun restoreLatestSnapshot(recoveryPassword: String = ""): SyncSnapshot {
        val name = listBackupNames().maxOrNull() ?: throw NoBackupException()
        return restoreSnapshot(name, recoveryPassword)
    }
    fun backupConnections(connections: List<Connection>, credentials: List<Credential>, quickCommands: String, proxyNodes: List<ProxyNode>, aiProvidersRaw: String, aiGlobalSettingsRaw: String, snapshotTime: Long, maxBackups: Int, recoveryPassword: String = ""): String
}

data class SyncTrustInteraction(
    val confirmHostKey: suspend (HostKeyConfirm) -> HostKeyAction,
    val confirmFtpsCertificate: suspend (FtpsCertificateConfirm) -> HostKeyAction,
)

class SyncInProgressException : IllegalStateException()

object SyncHelper {

    private val syncRunning = AtomicBoolean(false)

    fun markSnapshotRestored(snapshot: SyncSnapshot, time: Long): SyncSnapshot = snapshot.copy(
        connections = snapshot.connections.map { it.copy(lastModified = time) },
        credentials = snapshot.credentials.map { it.copy(lastModified = time) },
        proxyNodes = snapshot.proxyNodes.map { it.copy(updatedAt = time) },
        quickCommands = touchQuickCommands(snapshot.quickCommands, time),
        aiProvidersRaw = touchAiProviders(snapshot.aiProvidersRaw, time),
        aiGlobalSettingsRaw = touchAiGlobalSettings(snapshot.aiGlobalSettingsRaw, time),
        snapshotTime = time,
    )

    private fun touchQuickCommands(raw: String, time: Long): String {
        if (raw.isBlank()) return raw
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return raw
        touchQuickArray(array, time)
        return array.toString(2)
    }

    private fun touchQuickArray(array: JSONArray, time: Long) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            item.put("last_modified", time)
            item.optJSONArray("children")?.let { touchQuickArray(it, time) }
        }
    }

    private fun touchAiProviders(raw: String, time: Long): String {
        if (raw.isBlank()) return raw
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return raw
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.put("updatedAt", time)
        }
        return array.toString(2)
    }

    private fun touchAiGlobalSettings(raw: String, time: Long): String {
        if (raw.isBlank()) return raw
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        obj.put("updatedAt", time)
        return obj.toString(2)
    }

    data class SyncOutcome(
        val action: String,
        val mergedConnections: List<Connection>,
        val mergedCredentials: List<Credential>,
        val mergedQuickCommands: String,
        val mergedProxyNodes: List<ProxyNode>,
        val aiProvidersRaw: String = "",
        val aiGlobalSettingsRaw: String = "",
        val failure: Throwable? = null,
    ) {
        val error: String? get() = failure?.message
    }

    fun mergeConnections(local: List<Connection>, remote: List<Connection>, lastSyncTime: Long): List<Connection> {
        val remoteMap = remote.associateBy { it.id }
        val merged = mutableListOf<Connection>()
        val added = mutableSetOf<String>()

        for (lc in local) {
            if (lc.id in added) continue
            val rc = remoteMap[lc.id]
            if (rc != null) {
                merged += if (lc.lastModified >= rc.lastModified) lc else rc
            } else {
                if (lc.lastModified > lastSyncTime) merged += lc
            }
            added += lc.id
        }
        for (rc in remote) {
            if (rc.id !in added && rc.lastModified > lastSyncTime) {
                merged += rc
                added += rc.id
            }
        }

        val hostPortMap = mutableMapOf<Triple<String, Int, String>, Int>()
        val deduped = mutableListOf<Connection>()
        for (conn in merged) {
            val key = Triple(conn.host, conn.port, conn.username)
            val idx = hostPortMap[key]
            if (idx != null) {
                if (conn.lastModified > deduped[idx].lastModified) deduped[idx] = conn
            } else {
                hostPortMap[key] = deduped.size
                deduped += conn
            }
        }
        return deduped
    }

    fun mergeCredentials(local: List<Credential>, remote: List<Credential>, lastSyncTime: Long): List<Credential> {
        val remoteMap = remote.associateBy { it.id }
        val merged = mutableListOf<Credential>()
        val added = mutableSetOf<String>()

        for (lc in local) {
            if (lc.id in added) continue
            val rc = remoteMap[lc.id]
            if (rc != null) {
                merged += if (lc.lastModified >= rc.lastModified) lc else rc
            } else {
                if (lc.lastModified > lastSyncTime) merged += lc
            }
            added += lc.id
        }
        for (rc in remote) {
            if (rc.id !in added && rc.lastModified > lastSyncTime) {
                merged += rc
                added += rc.id
            }
        }
        return merged
    }

    fun mergeProxyNodes(local: List<ProxyNode>, remote: List<ProxyNode>, lastSyncTime: Long): List<ProxyNode> {
        val remoteMap = remote.associateBy { it.id }
        val merged = mutableListOf<ProxyNode>()
        val added = mutableSetOf<String>()

        for (ln in local) {
            if (ln.id in added) continue
            val rn = remoteMap[ln.id]
            if (rn != null) {
                merged += if (ln.updatedAt >= rn.updatedAt) ln else rn
            } else {
                if (ln.updatedAt > lastSyncTime) merged += ln
            }
            added += ln.id
        }
        for (rn in remote) {
            if (rn.id !in added && rn.updatedAt > lastSyncTime) {
                merged += rn
                added += rn.id
            }
        }
        return merged
    }

    fun mergeQuickCommands(localStr: String, remoteStr: String, lastSyncTime: Long): String {
        if (localStr.isBlank() && remoteStr.isBlank()) return localStr
        fun parseQuick(raw: String): JSONArray? = if (raw.isBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrNull()
        val local = parseQuick(localStr) ?: return localStr
        val remote = parseQuick(remoteStr) ?: return localStr

        val localMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until local.length()) {
            val m = local.optJSONObject(i) ?: continue
            localMap[cmdKey(m)] = m
        }
        val remoteMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until remote.length()) {
            val m = remote.optJSONObject(i) ?: continue
            remoteMap[cmdKey(m)] = m
        }

        // 顺序跟随 last_modified 较新的一边（移动后该边 max 更大）
        val baseIsRemote = maxQuickLastModified(remote) > maxQuickLastModified(local)
        val base = if (baseIsRemote) remote else local
        val other = if (baseIsRemote) local else remote
        val otherMap = if (baseIsRemote) localMap else remoteMap

        val result = JSONArray()
        val added = mutableSetOf<String>()

        for (i in 0 until base.length()) {
            val m = base.optJSONObject(i) ?: continue
            val key = cmdKey(m)
            if (key in added) continue
            val re = otherMap[key]
            if (re != null) {
                if (cmdLastModified(re) > cmdLastModified(m)) {
                    mergeChildrenInto(re, m, lastSyncTime)
                    result.put(re)
                } else {
                    mergeChildrenInto(m, re, lastSyncTime)
                    result.put(m)
                }
            } else {
                if (cmdLastModified(m) > lastSyncTime) result.put(m)
            }
            added += key
        }
        for (i in 0 until other.length()) {
            val m = other.optJSONObject(i) ?: continue
            val key = cmdKey(m)
            if (key !in added && cmdLastModified(m) > lastSyncTime) {
                result.put(m)
                added += key
            }
        }
        return result.toString(2)
    }

    private fun mergeChildrenInto(winner: JSONObject, loser: JSONObject, lastSyncTime: Long) {
        val lCh = winner.optJSONArray("children") ?: return
        val rCh = loser.optJSONArray("children") ?: return
        winner.put("children", mergeCmdChildren(lCh, rCh, lastSyncTime))
    }

    private fun mergeCmdChildren(localCh: JSONArray, remoteCh: JSONArray, lastSyncTime: Long): JSONArray {
        val lMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until localCh.length()) {
            val m = localCh.optJSONObject(i) ?: continue
            lMap[cmdKey(m)] = m
        }
        val rMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until remoteCh.length()) {
            val m = remoteCh.optJSONObject(i) ?: continue
            rMap[cmdKey(m)] = m
        }

        // 顺序跟随 last_modified 较新的一边
        val baseIsRemote = maxQuickLastModified(remoteCh) > maxQuickLastModified(localCh)
        val base = if (baseIsRemote) remoteCh else localCh
        val other = if (baseIsRemote) localCh else remoteCh
        val otherMap = if (baseIsRemote) lMap else rMap

        val result = JSONArray()
        val added = mutableSetOf<String>()
        for (i in 0 until base.length()) {
            val m = base.optJSONObject(i) ?: continue
            val key = cmdKey(m)
            if (key in added) continue
            val re = otherMap[key]
            if (re != null) {
                result.put(if (cmdLastModified(re) > cmdLastModified(m)) re else m)
            } else {
                if (cmdLastModified(m) > lastSyncTime) result.put(m)
            }
            added += key
        }
        for (i in 0 until other.length()) {
            val m = other.optJSONObject(i) ?: continue
            val key = cmdKey(m)
            if (key !in added && cmdLastModified(m) > lastSyncTime) {
                result.put(m)
                added += key
            }
        }
        return result
    }

    private fun maxQuickLastModified(arr: JSONArray): Long {
        var max = 0L
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val lm = cmdLastModified(m)
            if (lm > max) max = lm
            val ch = m.optJSONArray("children")
            if (ch != null) {
                val childMax = maxQuickLastModified(ch)
                if (childMax > max) max = childMax
            }
        }
        return max
    }

    private fun cmdKey(m: JSONObject): String = m.optString("name") + "|||" + m.optString("command")
    private fun cmdLastModified(m: JSONObject): Long = m.optLong("last_modified", 0L)

    internal fun mergeAiProvidersForImport(localRaw: String, importedRaw: String): String = mergeRawByUpdatedAt(localRaw, importedRaw, -1L)

    private fun mergeRawByUpdatedAt(localRaw: String, remoteRaw: String, lastSyncTime: Long): String {
        if (remoteRaw.isBlank()) return localRaw
        val remote = runCatching { JSONArray(remoteRaw) }.getOrNull() ?: return localRaw.ifBlank { remoteRaw }
        val local = runCatching { JSONArray(localRaw) }.getOrNull()
        if (local == null) {
            val result = JSONArray()
            for (i in 0 until remote.length()) {
                val item = remote.optJSONObject(i) ?: continue
                if (item.optLong("updatedAt", 0L) > lastSyncTime) result.put(item)
            }
            return result.toString(2)
        }
        val remoteMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until remote.length()) {
            val item = remote.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) remoteMap[id] = item
        }
        val result = JSONArray()
        val added = mutableSetOf<String>()
        for (i in 0 until local.length()) {
            val localItem = local.optJSONObject(i) ?: continue
            val id = localItem.optString("id")
            if (id.isBlank() || id in added) continue
            val remoteItem = remoteMap[id]
            if (remoteItem != null) {
                result.put(if (localItem.optLong("updatedAt", 0L) >= remoteItem.optLong("updatedAt", 0L)) localItem else remoteItem)
            } else if (localItem.optLong("updatedAt", 0L) > lastSyncTime) {
                result.put(localItem)
            }
            added += id
        }
        for ((id, remoteItem) in remoteMap) {
            if (id !in added && remoteItem.optLong("updatedAt", 0L) > lastSyncTime) {
                result.put(remoteItem)
            }
        }
        return result.toString(2)
    }

    private fun mergeRawObjectByUpdatedAt(localRaw: String, remoteRaw: String): String {
        if (remoteRaw.isBlank()) return localRaw
        if (localRaw.isBlank()) return remoteRaw
        val local = runCatching { JSONObject(localRaw) }.getOrNull() ?: return remoteRaw
        val remote = runCatching { JSONObject(remoteRaw) }.getOrNull() ?: return localRaw
        if (jsonObjectContentEqualIgnoringUpdatedAt(local, remote)) return localRaw
        return if (remote.optLong("updatedAt", 0L) > local.optLong("updatedAt", 0L)) remote.toString(2) else local.toString(2)
    }

    private fun rawObjectContentEqualIgnoringUpdatedAt(aRaw: String, bRaw: String): Boolean {
        if (aRaw == bRaw) return true
        if (aRaw.isBlank() || bRaw.isBlank()) return aRaw.isBlank() && bRaw.isBlank()
        val a = runCatching { JSONObject(aRaw) }.getOrNull() ?: return false
        val b = runCatching { JSONObject(bRaw) }.getOrNull() ?: return false
        return jsonObjectContentEqualIgnoringUpdatedAt(a, b)
    }

    private fun jsonObjectContentEqualIgnoringUpdatedAt(a: JSONObject, b: JSONObject): Boolean {
        val aa = JSONObject(a.toString())
        val bb = JSONObject(b.toString())
        aa.remove("updatedAt")
        bb.remove("updatedAt")
        return jsonValueEqual(aa, bb)
    }

    private fun mergeRawBySnapshotTime(localRaw: String, remoteRaw: String, localSnapshotTime: Long, remoteSnapshotTime: Long): String {
        if (remoteRaw.isBlank()) return localRaw
        if (localRaw.isBlank()) return remoteRaw
        return if (remoteSnapshotTime > localSnapshotTime) remoteRaw else localRaw
    }

    private fun latestSnapshotHasItem(updatedAt: Long, snapshots: List<SyncSnapshot>, contains: (SyncSnapshot) -> Boolean): Boolean {
        if (updatedAt <= 0L) return true
        val latest = snapshots.filter { it.snapshotTime > updatedAt }.maxByOrNull { it.snapshotTime }
        return latest == null || contains(latest)
    }

    private fun filterRemoteDeletedConnections(items: List<Connection>, snapshots: List<SyncSnapshot>): List<Connection> =
        items.filter { item -> latestSnapshotHasItem(item.lastModified, snapshots) { snap -> snap.connections.any { it.id == item.id } } }

    private fun filterRemoteDeletedCredentials(items: List<Credential>, snapshots: List<SyncSnapshot>): List<Credential> =
        items.filter { item -> latestSnapshotHasItem(item.lastModified, snapshots) { snap -> snap.credentials.any { it.id == item.id } } }

    private fun filterRemoteDeletedProxyNodes(items: List<ProxyNode>, snapshots: List<SyncSnapshot>): List<ProxyNode> =
        items.filter { item -> latestSnapshotHasItem(item.updatedAt, snapshots) { snap -> snap.proxyNodes.any { it.id == item.id } } }

    private fun filterRemoteDeletedRawByUpdatedAt(raw: String, snapshots: List<SyncSnapshot>, rawField: (SyncSnapshot) -> String): String {
        if (raw.isBlank()) return raw
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return raw
        val result = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val updatedAt = item.optLong("updatedAt", 0L)
            if (id.isBlank() || latestSnapshotHasItem(updatedAt, snapshots) { snap -> rawArrayHasId(rawField(snap), id) }) {
                result.put(item)
            }
        }
        return result.toString(2)
    }

    private fun rawArrayHasId(raw: String, id: String): Boolean {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        for (i in 0 until array.length()) {
            if (array.optJSONObject(i)?.optString("id") == id) return true
        }
        return false
    }

    private fun filterRemoteDeletedQuickCommands(raw: String, snapshots: List<SyncSnapshot>): String {
        if (raw.isBlank()) return raw
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return raw
        return filterQuickArrayRemoteDeleted(array, snapshots).toString(2)
    }

    private fun filterQuickArrayRemoteDeleted(array: JSONArray, snapshots: List<SyncSnapshot>): JSONArray {
        val result = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            item.optJSONArray("children")?.let { item.put("children", filterQuickArrayRemoteDeleted(it, snapshots)) }
            val key = cmdKey(item)
            val lastModified = cmdLastModified(item)
            if (latestSnapshotHasItem(lastModified, snapshots) { snap -> quickSnapshotHasKey(snap.quickCommands, key) }) {
                result.put(item)
            }
        }
        return result
    }

    private fun quickSnapshotHasKey(raw: String, key: String): Boolean {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        fun hasKey(arr: JSONArray): Boolean {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (cmdKey(item) == key) return true
                if (item.optJSONArray("children")?.let { hasKey(it) } == true) return true
            }
            return false
        }
        return hasKey(array)
    }

    internal data class SyncPlan(val snapshot: SyncSnapshot, val uploadIndexes: Set<Int>, val action: String)

    internal fun planSync(local: SyncSnapshot, remotes: List<SyncSnapshot?>, lastSyncTime: Long, syncTime: Long): SyncPlan {
        val snapshots = remotes.filterNotNull()
        val remote = snapshots.fold(SyncSnapshot(emptyList(), emptyList())) { merged, snapshot -> mergeSnapshotUnion(merged, listOf(snapshot)) }
        val merged = if (snapshots.isEmpty()) local else SyncSnapshot(
            connections = mergeConnections(local.connections, remote.connections, lastSyncTime),
            credentials = mergeCredentials(local.credentials, remote.credentials, lastSyncTime),
            proxyNodes = mergeProxyNodes(local.proxyNodes, remote.proxyNodes, lastSyncTime),
            quickCommands = mergeQuickCommands(local.quickCommands, remote.quickCommands, lastSyncTime),
            aiProvidersRaw = mergeRawByUpdatedAt(local.aiProvidersRaw, remote.aiProvidersRaw, lastSyncTime),
            aiGlobalSettingsRaw = mergeRawObjectByUpdatedAt(local.aiGlobalSettingsRaw, remote.aiGlobalSettingsRaw),
            snapshotTime = maxOf(local.snapshotTime, remote.snapshotTime),
        )
        val final = merged.copy(
            connections = filterRemoteDeletedConnections(merged.connections, snapshots),
            credentials = filterRemoteDeletedCredentials(merged.credentials, snapshots),
            proxyNodes = filterRemoteDeletedProxyNodes(merged.proxyNodes, snapshots),
            quickCommands = filterRemoteDeletedQuickCommands(merged.quickCommands, snapshots),
            aiProvidersRaw = filterRemoteDeletedRawByUpdatedAt(merged.aiProvidersRaw, snapshots) { it.aiProvidersRaw },
        )
        val uploadIndexes = remotes.indices.filterTo(linkedSetOf()) { remotes[it]?.let { remoteSnapshot -> !snapshotBusinessEqual(final, remoteSnapshot) } ?: true }
        val localChanged = !snapshotBusinessEqual(final, local)
        val cloudChanged = uploadIndexes.isNotEmpty()
        val action = when {
            localChanged && cloudChanged -> "merge"
            cloudChanged -> "upload"
            localChanged -> "download"
            else -> "skip"
        }
        return SyncPlan(final.copy(snapshotTime = if (cloudChanged) syncTime else final.snapshotTime), uploadIndexes, action)
    }

    internal fun snapshotBusinessEqual(a: SyncSnapshot, b: SyncSnapshot): Boolean =
        a.connections.associateBy { it.id } == b.connections.associateBy { it.id } &&
            a.credentials.associateBy { it.id } == b.credentials.associateBy { it.id } &&
            a.proxyNodes.associateBy { it.id } == b.proxyNodes.associateBy { it.id } &&
            jsonArrayEqual(a.quickCommands, b.quickCommands, ordered = true) &&
            jsonArrayEqual(a.aiProvidersRaw, b.aiProvidersRaw, ordered = false) &&
            jsonObjectEqual(a.aiGlobalSettingsRaw, b.aiGlobalSettingsRaw)

    private fun jsonArrayEqual(aRaw: String, bRaw: String, ordered: Boolean): Boolean {
        val a = if (aRaw.isBlank()) JSONArray() else runCatching { JSONArray(aRaw) }.getOrNull() ?: return aRaw == bRaw
        val b = if (bRaw.isBlank()) JSONArray() else runCatching { JSONArray(bRaw) }.getOrNull() ?: return aRaw == bRaw
        if (a.length() != b.length()) return false
        if (ordered) return (0 until a.length()).all { jsonValueEqual(a.opt(it), b.opt(it)) }
        val aById = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("id") }
        val bById = (0 until b.length()).mapNotNull { b.optJSONObject(it) }.associateBy { it.optString("id") }
        return aById.size == a.length() && bById.size == b.length() && aById.keys == bById.keys && aById.all { (id, value) -> jsonValueEqual(value, bById[id]) }
    }

    private fun jsonObjectEqual(aRaw: String, bRaw: String): Boolean {
        if (aRaw.isBlank() || bRaw.isBlank()) return aRaw.isBlank() && bRaw.isBlank()
        val a = runCatching { JSONObject(aRaw) }.getOrNull() ?: return aRaw == bRaw
        val b = runCatching { JSONObject(bRaw) }.getOrNull() ?: return aRaw == bRaw
        return jsonValueEqual(a, b)
    }

    private fun jsonValueEqual(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a == null || b == null || a == JSONObject.NULL || b == JSONObject.NULL -> a == b
        a is JSONObject && b is JSONObject -> {
            val aKeys = a.keys().asSequence().toSet()
            val bKeys = b.keys().asSequence().toSet()
            aKeys == bKeys && aKeys.all { jsonValueEqual(a.opt(it), b.opt(it)) }
        }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { jsonValueEqual(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toString().toBigDecimalOrNull()?.compareTo(b.toString().toBigDecimalOrNull()) == 0
        else -> a == b
    }

    fun providersFor(store: LocalStore): List<String> {
        val configured = allConfiguredProvidersFor(store)
        val mode = store.loadSyncMode()
        return if (mode == "all") configured else configured.filter { it == mode }
    }

    internal fun allConfiguredProvidersFor(store: LocalStore): List<String> {
        val result = mutableListOf<String>()
        val wd = store.loadWebDavConfig()
        val r2 = store.loadR2Config()
        val ftp = store.loadFtpConfig()
        val sftp = store.loadSftpConfig()

        if (wd.url.isNotBlank() && wd.username.isNotBlank()) result += "webdav"
        if (r2.accessKeyId.isNotBlank() && r2.secretAccessKey.isNotBlank() && r2.bucket.isNotBlank() && r2.endpoint.isNotBlank()) result += "r2"
        if (ftp.host.isNotBlank() && ftp.username.isNotBlank()) result += "ftp"
        if (sftp.host.isNotBlank() && sftp.username.isNotBlank()) result += "sftp"
        return result
    }

    suspend fun autoSync(
        store: LocalStore,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode> = store.loadProxyNodes(),
        aiProvidersRaw: String = store.loadAiProvidersRaw(),
        aiGlobalSettingsRaw: String = store.loadAiGlobalSettingsRaw(),
        trustInteraction: SyncTrustInteraction? = null,
    ): SyncOutcome = sync(store, store.loadRecoveryPassword(), false, connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction)

    suspend fun syncWithRecoveryPassword(
        store: LocalStore,
        recoveryPassword: String,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode> = store.loadProxyNodes(),
        aiProvidersRaw: String = store.loadAiProvidersRaw(),
        aiGlobalSettingsRaw: String = store.loadAiGlobalSettingsRaw(),
        trustInteraction: SyncTrustInteraction? = null,
    ): SyncOutcome = sync(store, recoveryPassword, true, connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction)

    private suspend fun sync(
        store: LocalStore,
        recoveryPassword: String,
        saveCandidatePassword: Boolean,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode>,
        aiProvidersRaw: String,
        aiGlobalSettingsRaw: String,
        trustInteraction: SyncTrustInteraction?,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        if (!syncRunning.compareAndSet(false, true)) {
            return@withContext SyncOutcome("skip", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, SyncInProgressException())
        }
        try {
            val providers = providersFor(store)
            if (providers.isEmpty()) {
                return@withContext SyncOutcome("skip", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, IllegalStateException("未配置同步后端"))
            }
            if (store.loadSyncMode() == "all") {
                syncAllProviders(store, providers, connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction, recoveryPassword, saveCandidatePassword)
            } else {
                syncFromProvider(store, providers.first(), connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, trustInteraction, recoveryPassword, saveCandidatePassword)
            }
        } finally {
            syncRunning.set(false)
        }
    }

    private fun syncAllProviders(
        store: LocalStore,
        providers: List<String>,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode>,
        aiProvidersRaw: String,
        aiGlobalSettingsRaw: String,
        trustInteraction: SyncTrustInteraction?,
        recoveryPassword: String,
        saveCandidatePassword: Boolean,
    ): SyncOutcome {
        val targets = try {
            providers.map { provider ->
                val (instance, maxBackups) = providerInstance(store, provider, trustInteraction)
                Triple(provider, instance, maxBackups)
            }
        } catch (failure: Throwable) {
            return SyncOutcome("error", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, failure)
        }
        val remotes = try {
            fetchAllStrict(targets.map { it.second }, recoveryPassword)
        } catch (failure: Throwable) {
            return SyncOutcome("error", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, failure)
        }
        val local = SyncSnapshot(connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw, store.loadSnapshotTime())
        val syncTime = System.currentTimeMillis()
        val plan = planSync(local, remotes, store.loadLastSyncTime(), syncTime)
        val uploaded = mutableListOf<Triple<SyncProvider, String, Int>>()
        try {
            plan.uploadIndexes.forEach { index ->
                val (_, instance, maxBackups) = targets[index]
                uploaded += Triple(instance, uploadSnapshot(instance, plan.snapshot, recoveryPassword), maxBackups)
            }
            check(store.saveSnapshot(plan.snapshot, syncTime)) { "本地同步快照保存失败" }
            if (saveCandidatePassword) store.saveRecoveryPassword(recoveryPassword)
        } catch (failure: Throwable) {
            uploaded.forEach { (instance, name, _) -> runCatching { instance.deleteBackup(name) } }
            return SyncOutcome("error", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, failure)
        }
        uploaded.forEach { (instance, _, maxBackups) -> if (maxBackups > 0) runCatching { instance.pruneOldBackups(maxBackups) } }
        return plan.snapshot.let {
            SyncOutcome(plan.action, it.connections, it.credentials, it.quickCommands, it.proxyNodes, it.aiProvidersRaw, it.aiGlobalSettingsRaw)
        }
    }

    private suspend fun syncFromProvider(
        store: LocalStore,
        provider: String,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode>,
        aiProvidersRaw: String,
        aiGlobalSettingsRaw: String,
        trustInteraction: SyncTrustInteraction?,
        recoveryPassword: String,
        saveCandidatePassword: Boolean,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val lastSyncTime = store.loadLastSyncTime()
        val (instance, maxBackups) = try {
            providerInstance(store, provider, trustInteraction)
        } catch (failure: Throwable) {
            return@withContext SyncOutcome("error", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, failure)
        }
        val remoteSnap = try {
            instance.restoreLatestSnapshot(recoveryPassword)
        } catch (_: NoBackupException) {
            null
        } catch (failure: Throwable) {
            return@withContext SyncOutcome("error", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, failure)
        }

        val local = SyncSnapshot(connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw, store.loadSnapshotTime())
        val syncTime = System.currentTimeMillis()
        val plan = planSync(local, listOf(remoteSnap), lastSyncTime, syncTime)
        var uploadedName: String? = null
        try {
            if (plan.uploadIndexes.isNotEmpty()) uploadedName = uploadSnapshot(instance, plan.snapshot, recoveryPassword)
            check(store.saveSnapshot(plan.snapshot, syncTime)) { "本地同步快照保存失败" }
            if (saveCandidatePassword) store.saveRecoveryPassword(recoveryPassword)
        } catch (failure: Throwable) {
            uploadedName?.let { runCatching { instance.deleteBackup(it) } }
            return@withContext SyncOutcome("error", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, failure)
        }
        if (uploadedName != null && maxBackups > 0) runCatching { instance.pruneOldBackups(maxBackups) }
        plan.snapshot.let {
            SyncOutcome(plan.action, it.connections, it.credentials, it.quickCommands, it.proxyNodes, it.aiProvidersRaw, it.aiGlobalSettingsRaw)
        }
    }

    suspend fun changeRecoveryPassword(
        store: LocalStore,
        password: String,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode>,
        aiProvidersRaw: String,
        aiGlobalSettingsRaw: String,
        trustInteraction: SyncTrustInteraction? = null,
    ): SyncSnapshot = withSyncLock {
        val normalizedPassword = normalizeRecoveryPassword(password)
        val targets = allConfiguredProvidersFor(store).map { providerInstance(store, it, trustInteraction).first }
        val oldPassword = store.loadRecoveryPassword()
        val remoteSnapshots = targets.mapNotNull { instance ->
            try {
                instance.restoreLatestSnapshot(oldPassword)
            } catch (_: NoBackupException) {
                null
            } catch (_: RecoveryPasswordException) {
                try {
                    instance.restoreLatestSnapshot(normalizedPassword)
                } catch (_: RecoveryPasswordException) {
                    throw RecoveryPasswordResetRequiredException()
                }
            }
        }
        val localSnapshot = SyncSnapshot(connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw, store.loadSnapshotTime())
        val mergedSnapshot = mergeSnapshotUnion(localSnapshot, remoteSnapshots).copy(snapshotTime = System.currentTimeMillis())
        rewriteRecoveryPassword(store, targets, normalizedPassword, mergedSnapshot)
    }

    suspend fun resetRecoveryPassword(
        store: LocalStore,
        password: String,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode>,
        aiProvidersRaw: String,
        aiGlobalSettingsRaw: String,
        trustInteraction: SyncTrustInteraction? = null,
    ): SyncSnapshot = withSyncLock {
        val normalizedPassword = normalizeRecoveryPassword(password)
        val snapshot = SyncSnapshot(connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw, System.currentTimeMillis())
        rewriteRecoveryPassword(
            store,
            allConfiguredProvidersFor(store).map { providerInstance(store, it, trustInteraction).first },
            normalizedPassword,
            snapshot,
        )
    }

    internal fun normalizeRecoveryPassword(password: String): String = if (password.isBlank()) "" else password

    internal fun mergeSnapshotUnion(local: SyncSnapshot, remotes: List<SyncSnapshot>): SyncSnapshot = remotes.fold(local) { merged, remote ->
        SyncSnapshot(
            connections = mergeConnections(merged.connections, remote.connections, -1L),
            credentials = mergeCredentials(merged.credentials, remote.credentials, -1L),
            proxyNodes = mergeProxyNodes(merged.proxyNodes, remote.proxyNodes, -1L),
            quickCommands = mergeQuickCommands(merged.quickCommands, remote.quickCommands, -1L),
            aiProvidersRaw = mergeRawByUpdatedAt(merged.aiProvidersRaw, remote.aiProvidersRaw, -1L),
            aiGlobalSettingsRaw = mergeRawObjectByUpdatedAt(merged.aiGlobalSettingsRaw, remote.aiGlobalSettingsRaw),
            snapshotTime = maxOf(merged.snapshotTime, remote.snapshotTime),
        )
    }

    private fun rewriteRecoveryPassword(store: LocalStore, providers: List<SyncProvider>, password: String, snapshot: SyncSnapshot): SyncSnapshot =
        rewriteRecoveryPasswordTransaction(
            providers = providers,
            snapshot = snapshot,
            recoveryPassword = password,
            saveSnapshot = { store.saveSnapshot(snapshot, System.currentTimeMillis()) },
            savePassword = { store.saveRecoveryPassword(password) },
        )

    internal fun rewriteRecoveryPasswordTransaction(
        providers: List<SyncProvider>,
        snapshot: SyncSnapshot,
        recoveryPassword: String,
        saveSnapshot: () -> Boolean,
        savePassword: () -> Unit,
    ): SyncSnapshot {
        val uploaded = mutableListOf<Pair<SyncProvider, String>>()
        try {
            providers.forEach { provider -> uploaded += provider to uploadSnapshot(provider, snapshot, recoveryPassword) }
            check(saveSnapshot()) { "本地同步快照保存失败" }
            savePassword()
            return snapshot
        } catch (failure: Throwable) {
            uploaded.forEach { (provider, name) -> runCatching { provider.deleteBackup(name) } }
            throw failure
        }
    }

    private suspend fun <T> withSyncLock(block: () -> T): T = withContext(Dispatchers.IO) {
        if (!syncRunning.compareAndSet(false, true)) throw SyncInProgressException()
        try {
            block()
        } finally {
            syncRunning.set(false)
        }
    }

    internal fun syncProvidersTransaction(
        providers: List<SyncProvider>,
        snapshot: SyncSnapshot,
        recoveryPassword: String,
    ): List<String> {
        val uploaded = mutableListOf<Pair<SyncProvider, String>>()
        try {
            providers.forEach { provider ->
                uploaded += provider to provider.backupConnections(
                    snapshot.connections, snapshot.credentials, snapshot.quickCommands, snapshot.proxyNodes,
                    snapshot.aiProvidersRaw, snapshot.aiGlobalSettingsRaw, snapshot.snapshotTime, 0, recoveryPassword,
                )
            }
            return uploaded.map { it.second }
        } catch (failure: Throwable) {
            uploaded.forEach { (provider, name) -> runCatching { provider.deleteBackup(name) } }
            throw failure
        }
    }

    internal fun fetchAllStrict(providers: List<SyncProvider>, recoveryPassword: String): List<SyncSnapshot?> =
        providers.map { provider ->
            try {
                provider.restoreLatestSnapshot(recoveryPassword)
            } catch (_: NoBackupException) {
                null
            }
        }

    internal fun providerInstance(store: LocalStore, provider: String, trustInteraction: SyncTrustInteraction? = null): Pair<SyncProvider, Int> = when (provider) {
        "webdav" -> store.loadWebDavConfig().let { WebDavSync(it.url, it.username, it.password, it.remotePath) to it.maxBackups }
        "r2" -> store.loadR2Config().let { R2Sync(it.accessKeyId, it.secretAccessKey, it.bucket, it.endpoint, it.region, it.prefix) to it.maxBackups }
        "ftp" -> store.loadFtpConfig().let { FtpSync(it.host, it.port, it.username, it.password, it.remoteDir, it.mode, store, trustInteraction?.confirmFtpsCertificate) to it.maxBackups }
        "sftp" -> store.loadSftpConfig().let { SftpSync(store, it.host, it.port, it.username, it.password, it.privateKey, it.passphrase, it.remoteDir, trustInteraction?.confirmHostKey) to it.maxBackups }
        else -> throw IllegalStateException("unknown provider: $provider")
    }

    private fun fetchSnapshot(store: LocalStore, provider: String, trustInteraction: SyncTrustInteraction?, recoveryPassword: String): SyncSnapshot =
        providerInstance(store, provider, trustInteraction).first.restoreLatestSnapshot(recoveryPassword)

    private fun uploadSnapshot(provider: SyncProvider, snapshot: SyncSnapshot, recoveryPassword: String): String = provider.backupConnections(
        snapshot.connections, snapshot.credentials, snapshot.quickCommands, snapshot.proxyNodes,
        snapshot.aiProvidersRaw, snapshot.aiGlobalSettingsRaw, snapshot.snapshotTime, 0, recoveryPassword,
    )
}
