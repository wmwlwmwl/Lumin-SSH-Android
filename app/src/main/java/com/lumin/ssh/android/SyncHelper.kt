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
    fun backupConnections(connections: List<Connection>, credentials: List<Credential>, quickCommands: String, proxyNodes: List<ProxyNode>, aiProvidersRaw: String, aiGlobalSettingsRaw: String, snapshotTime: Long, maxBackups: Int, recoveryPassword: String = "", deletedConnections: List<SyncTombstone> = emptyList(), deletedCredentials: List<SyncTombstone> = emptyList(), tombstonePrunedBefore: Long = 0): String
    /** 远端同步目录被删/404 时重建；对象存储可 no-op。 */
    fun ensureRemoteDir() {}
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
        /** AutoSync 首次接触后端且墓碑会静默删远端项时跳过，需用户手动合并确认 */
        val needsManualTombstoneConfirm: Boolean = false,
        val skipProvider: String = "",
    ) {
        val error: String? get() = failure?.message
    }

    private fun remoteHasTombstoneConflicts(
        remote: SyncSnapshot?,
        connTombs: List<SyncTombstone>,
        credTombs: List<SyncTombstone>,
    ): Boolean {
        if (remote == null) return false
        val connMap = tombstoneMap(connTombs)
        val credMap = tombstoneMap(credTombs)
        if (connMap.isEmpty() && credMap.isEmpty()) return false
        for (rc in remote.connections) {
            val at = connMap[rc.id] ?: continue
            if (at >= rc.lastModified) return true
        }
        for (rc in remote.credentials) {
            val at = credMap[rc.id] ?: continue
            if (at >= rc.lastModified) return true
        }
        return false
    }

    private fun shouldSkipAutoSyncForTombstoneConflict(
        store: LocalStore,
        provider: String,
        remote: SyncSnapshot?,
        localConnTombs: List<SyncTombstone>,
        localCredTombs: List<SyncTombstone>,
    ): Boolean {
        if (store.loadLastSyncTime(provider) != 0L) return false
        return remoteHasTombstoneConflicts(remote, localConnTombs, localCredTombs)
    }

    data class ConnectionMergeResult(
        val connections: List<Connection>,
        val dedupTombs: List<SyncTombstone> = emptyList(),
    )

    private fun shouldDrop(id: String, lastModified: Long, deleted: Map<String, Long>): Boolean {
        val at = deleted[id] ?: return false
        // 墓碑时间 >= 节点时间即删除；相等时也算删除，避免同毫秒/整表 touch 后复活
        return at >= lastModified
    }

    private fun dedupTombstoneAt(a: Long, b: Long): Long {
        val maxLm = maxOf(a, b)
        val now = System.currentTimeMillis()
        return if (now <= maxLm) maxLm + 1 else now
    }

    // 启发式删除（单侧独有且 LM<=lastSync）必须落墓碑，否则会上传「人没了、墓碑也空」的包，对端又复活。
    private fun inferredDeleteTombstoneAt(lastModified: Long, lastSyncTime: Long): Long {
        val floor = maxOf(lastModified, lastSyncTime)
        val now = System.currentTimeMillis()
        return if (now <= floor) floor + 1 else now
    }

    data class CredentialMergeResult(
        val credentials: List<Credential>,
        val inferredTombs: List<SyncTombstone> = emptyList(),
    )

    fun mergeConnections(
        local: List<Connection>,
        remote: List<Connection>,
        lastSyncTime: Long,
        vararg tombstones: List<SyncTombstone>,
    ): ConnectionMergeResult {
        var deleted = emptyMap<String, Long>()
        for (list in tombstones) deleted = mergeTombstoneMaps(deleted, tombstoneMap(list))
        val remoteMap = remote.associateBy { it.id }
        val merged = mutableListOf<Connection>()
        val added = mutableSetOf<String>()
        val inferredTombs = mutableListOf<SyncTombstone>()
        fun noteInferredDelete(id: String, lm: Long) {
            val key = id.trim()
            if (key.isBlank()) return
            val at = inferredDeleteTombstoneAt(lm, lastSyncTime)
            val prev = deleted[key]
            if (prev != null && prev >= at) return
            deleted = deleted + (key to at)
            inferredTombs += SyncTombstone(key, at)
        }

        for (lc in local) {
            if (lc.id in added) continue
            val rc = remoteMap[lc.id]
            if (rc != null) {
                val chosen = if (rc.lastModified > lc.lastModified) rc else lc
                if (!shouldDrop(chosen.id, chosen.lastModified, deleted)) merged += chosen
            } else if (lc.lastModified > lastSyncTime && !shouldDrop(lc.id, lc.lastModified, deleted)) {
                merged += lc
            } else if (!shouldDrop(lc.id, lc.lastModified, deleted)) {
                noteInferredDelete(lc.id, lc.lastModified)
            }
            added += lc.id
        }
        for (rc in remote) {
            if (rc.id !in added) {
                if (rc.lastModified > lastSyncTime && !shouldDrop(rc.id, rc.lastModified, deleted)) {
                    merged += rc
                } else if (!shouldDrop(rc.id, rc.lastModified, deleted)) {
                    noteInferredDelete(rc.id, rc.lastModified)
                }
                added += rc.id
            }
        }

        val hostPortMap = mutableMapOf<Triple<String, Int, String>, Int>()
        val deduped = mutableListOf<Connection>()
        val dedupTombs = mutableListOf<SyncTombstone>()
        for (conn in merged) {
            val key = conn.identityKey()
            val idx = hostPortMap[key]
            if (idx != null) {
                val kept = deduped[idx]
                if (conn.lastModified > kept.lastModified) {
                    if (kept.id.isNotBlank() && kept.id != conn.id) {
                        dedupTombs += SyncTombstone(kept.id, dedupTombstoneAt(kept.lastModified, conn.lastModified))
                    }
                    deduped[idx] = conn
                } else if (conn.id.isNotBlank() && conn.id != kept.id) {
                    dedupTombs += SyncTombstone(conn.id, dedupTombstoneAt(conn.lastModified, kept.lastModified))
                }
            } else {
                hostPortMap[key] = deduped.size
                deduped += conn
            }
        }
        return ConnectionMergeResult(deduped, mergeTombstoneLists(inferredTombs, dedupTombs))
    }

    fun mergeCredentials(
        local: List<Credential>,
        remote: List<Credential>,
        lastSyncTime: Long,
        vararg tombstones: List<SyncTombstone>,
    ): CredentialMergeResult {
        var deleted = emptyMap<String, Long>()
        for (list in tombstones) deleted = mergeTombstoneMaps(deleted, tombstoneMap(list))
        val remoteMap = remote.associateBy { it.id }
        val merged = mutableListOf<Credential>()
        val added = mutableSetOf<String>()
        val inferredTombs = mutableListOf<SyncTombstone>()
        fun noteInferredDelete(id: String, lm: Long) {
            val key = id.trim()
            if (key.isBlank()) return
            val at = inferredDeleteTombstoneAt(lm, lastSyncTime)
            val prev = deleted[key]
            if (prev != null && prev >= at) return
            deleted = deleted + (key to at)
            inferredTombs += SyncTombstone(key, at)
        }

        for (lc in local) {
            if (lc.id in added) continue
            val rc = remoteMap[lc.id]
            if (rc != null) {
                val chosen = if (rc.lastModified > lc.lastModified) rc else lc
                if (!shouldDrop(chosen.id, chosen.lastModified, deleted)) merged += chosen
            } else if (lc.lastModified > lastSyncTime && !shouldDrop(lc.id, lc.lastModified, deleted)) {
                merged += lc
            } else if (!shouldDrop(lc.id, lc.lastModified, deleted)) {
                noteInferredDelete(lc.id, lc.lastModified)
            }
            added += lc.id
        }
        for (rc in remote) {
            if (rc.id !in added) {
                if (rc.lastModified > lastSyncTime && !shouldDrop(rc.id, rc.lastModified, deleted)) {
                    merged += rc
                } else if (!shouldDrop(rc.id, rc.lastModified, deleted)) {
                    noteInferredDelete(rc.id, rc.lastModified)
                }
                added += rc.id
            }
        }
        return CredentialMergeResult(merged, mergeTombstoneLists(emptyList(), inferredTombs))
    }

    fun pruneConnectionTombstones(tombs: List<SyncTombstone>, conns: List<Connection>): List<SyncTombstone> {
        val alive = conns.associate { it.id to it.lastModified }
        val map = tombstoneMap(tombs).toMutableMap()
        for ((id, at) in map.toList()) {
            val lm = alive[id]
            // 仅节点严格新于删除时间才清墓碑
            if (lm != null && lm > at) map.remove(id)
        }
        return tombstonesFromMap(map)
    }

    fun pruneCredentialTombstones(tombs: List<SyncTombstone>, creds: List<Credential>): List<SyncTombstone> {
        val alive = creds.associate { it.id to it.lastModified }
        val map = tombstoneMap(tombs).toMutableMap()
        for ((id, at) in map.toList()) {
            val lm = alive[id]
            if (lm != null && lm > at) map.remove(id)
        }
        return tombstonesFromMap(map)
    }

    fun tombstonesEqual(a: List<SyncTombstone>, b: List<SyncTombstone>): Boolean =
        tombstoneMap(a) == tombstoneMap(b)

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
        val remotePrunedBefore = snapshots.maxOfOrNull { it.tombstonePrunedBefore } ?: remote.tombstonePrunedBefore
        val (baseConnTombs, baseCredTombs, mergedPrunedBefore) = mergeTombsWithPruneWatermark(
            local.deletedConnections, remote.deletedConnections,
            local.deletedCredentials, remote.deletedCredentials,
            local.tombstonePrunedBefore, remotePrunedBefore,
        )
        // 远程无备份时：本地为权威。
        // 切勿用 lastSyncTime 推断「远程没有=本地应删」——否则空 /Lumin/ 会把未改过的服务器整表推断成墓碑后清空再上传。
        val emptyRemoteLastSync = -1L
        val connMerge = if (snapshots.isEmpty()) {
            mergeConnections(local.connections, emptyList(), emptyRemoteLastSync, baseConnTombs)
        } else {
            mergeConnections(local.connections, remote.connections, lastSyncTime, baseConnTombs)
        }
        val mergedConnTombs = pruneConnectionTombstones(
            filterTombstonesNotBefore(mergeTombstoneLists(baseConnTombs, connMerge.dedupTombs), mergedPrunedBefore),
            connMerge.connections,
        )
        val credMerge = if (snapshots.isEmpty()) {
            mergeCredentials(local.credentials, emptyList(), emptyRemoteLastSync, baseCredTombs)
        } else {
            mergeCredentials(local.credentials, remote.credentials, lastSyncTime, baseCredTombs)
        }
        val mergedCreds = credMerge.credentials
        val mergedCredTombs = pruneCredentialTombstones(
            filterTombstonesNotBefore(mergeTombstoneLists(baseCredTombs, credMerge.inferredTombs), mergedPrunedBefore),
            mergedCreds,
        )
        var merged = if (snapshots.isEmpty()) local.copy(
            connections = connMerge.connections,
            credentials = mergedCreds,
            deletedConnections = mergedConnTombs,
            deletedCredentials = mergedCredTombs,
            tombstonePrunedBefore = mergedPrunedBefore,
        ) else SyncSnapshot(
            connections = connMerge.connections,
            credentials = mergedCreds,
            proxyNodes = mergeProxyNodes(local.proxyNodes, remote.proxyNodes, lastSyncTime),
            quickCommands = mergeQuickCommands(local.quickCommands, remote.quickCommands, lastSyncTime),
            aiProvidersRaw = mergeRawByUpdatedAt(local.aiProvidersRaw, remote.aiProvidersRaw, lastSyncTime),
            aiGlobalSettingsRaw = mergeRawObjectByUpdatedAt(local.aiGlobalSettingsRaw, remote.aiGlobalSettingsRaw),
            deletedConnections = mergedConnTombs,
            deletedCredentials = mergedCredTombs,
            tombstonePrunedBefore = mergedPrunedBefore,
            snapshotTime = maxOf(local.snapshotTime, remote.snapshotTime),
        )
        // 连接/凭据删除只信墓碑；快捷命令/AI/代理仍用旧 snapshot_time 启发式
        if (snapshots.isNotEmpty()) {
            merged = merged.copy(
                proxyNodes = filterRemoteDeletedProxyNodes(merged.proxyNodes, snapshots),
                quickCommands = filterRemoteDeletedQuickCommands(merged.quickCommands, snapshots),
                aiProvidersRaw = filterRemoteDeletedRawByUpdatedAt(merged.aiProvidersRaw, snapshots) { it.aiProvidersRaw },
            )
        }
        // 上传包规范化连接/凭据，与 PC uploadSnapshot 一致，打断 omitempty vs 默认字段乒乓
        val final = merged.copy(
            connections = merged.connections.map { it.normalizedForSync() },
            credentials = merged.credentials.map { it.normalizedForSync() },
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
        a.connections.map { it.normalizedForSync() }.associateBy { it.id } ==
            b.connections.map { it.normalizedForSync() }.associateBy { it.id } &&
            a.credentials.map { it.normalizedForSync() }.associateBy { it.id } ==
            b.credentials.map { it.normalizedForSync() }.associateBy { it.id } &&
            a.proxyNodes.associateBy { it.id } == b.proxyNodes.associateBy { it.id } &&
            jsonArrayEqual(a.quickCommands, b.quickCommands, ordered = true, ignoreExpanded = true) &&
            jsonArrayEqual(a.aiProvidersRaw, b.aiProvidersRaw, ordered = false) &&
            jsonObjectEqual(a.aiGlobalSettingsRaw, b.aiGlobalSettingsRaw) &&
            tombstonesEqual(a.deletedConnections, b.deletedConnections) &&
            tombstonesEqual(a.deletedCredentials, b.deletedCredentials) &&
            a.tombstonePrunedBefore == b.tombstonePrunedBefore

    private fun jsonArrayEqual(aRaw: String, bRaw: String, ordered: Boolean, ignoreExpanded: Boolean = false): Boolean {
        val a = if (aRaw.isBlank()) JSONArray() else runCatching { JSONArray(aRaw) }.getOrNull() ?: return aRaw == bRaw
        val b = if (bRaw.isBlank()) JSONArray() else runCatching { JSONArray(bRaw) }.getOrNull() ?: return aRaw == bRaw
        if (a.length() != b.length()) return false
        if (ordered) return (0 until a.length()).all { jsonValueEqual(a.opt(it), b.opt(it), ignoreExpanded) }
        val aById = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("id") }
        val bById = (0 until b.length()).mapNotNull { b.optJSONObject(it) }.associateBy { it.optString("id") }
        return aById.size == a.length() && bById.size == b.length() && aById.keys == bById.keys &&
            aById.all { (id, value) -> jsonValueEqual(value, bById[id], ignoreExpanded) }
    }

    private fun jsonObjectEqual(aRaw: String, bRaw: String): Boolean {
        if (aRaw.isBlank() || bRaw.isBlank()) return aRaw.isBlank() && bRaw.isBlank()
        val a = runCatching { JSONObject(aRaw) }.getOrNull() ?: return aRaw == bRaw
        val b = runCatching { JSONObject(bRaw) }.getOrNull() ?: return aRaw == bRaw
        return jsonValueEqual(a, b, ignoreExpanded = false)
    }

    private fun jsonValueEqual(a: Any?, b: Any?, ignoreExpanded: Boolean = false): Boolean = when {
        a === b -> true
        a == null || b == null || a == JSONObject.NULL || b == JSONObject.NULL -> a == b
        a is JSONObject && b is JSONObject -> {
            val aKeys = a.keys().asSequence().filter { !(ignoreExpanded && it == "expanded") }.toSet()
            val bKeys = b.keys().asSequence().filter { !(ignoreExpanded && it == "expanded") }.toSet()
            aKeys == bKeys && aKeys.all { jsonValueEqual(a.opt(it), b.opt(it), ignoreExpanded) }
        }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { jsonValueEqual(a.opt(it), b.opt(it), ignoreExpanded) }
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
        val tombStore = store.loadTombstoneStore()
        val localConnTombs = filterTombstonesNotBefore(tombStore.connections, tombStore.prunedBefore)
        val localCredTombs = filterTombstonesNotBefore(tombStore.credentials, tombStore.prunedBefore)
        // AutoSync 首次接触某后端且墓碑会静默删远端：跳过，请用户手动合并确认
        if (!saveCandidatePassword) {
            for ((idx, providerName) in providers.withIndex()) {
                val remote = remotes.getOrNull(idx)
                if (shouldSkipAutoSyncForTombstoneConflict(store, providerName, remote, localConnTombs, localCredTombs)) {
                    return SyncOutcome(
                        "skip", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw,
                        needsManualTombstoneConfirm = true,
                        skipProvider = providerName,
                    )
                }
            }
        }
        val local = SyncSnapshot(
            connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw,
            localConnTombs, localCredTombs, tombStore.prunedBefore, store.loadSnapshotTime(),
        )
        val syncTime = System.currentTimeMillis()
        val lastSyncTime = store.loadLastSyncTimeMin(providers)
        val plan = planSync(local, remotes, lastSyncTime, syncTime)
        val uploaded = mutableListOf<Triple<SyncProvider, String, Int>>()
        try {
            plan.uploadIndexes.forEach { index ->
                val (_, instance, maxBackups) = targets[index]
                uploaded += Triple(instance, uploadSnapshot(instance, plan.snapshot, recoveryPassword), maxBackups)
            }
            check(store.saveSnapshot(plan.snapshot, syncTime)) { "本地同步快照保存失败" }
            store.saveLastSyncTimes(providers, syncTime)
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
        val lastSyncTime = store.loadLastSyncTime(provider)
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
            AppLog.e("Sync", "restoreLatestSnapshot failed provider=$provider", failure)
            return@withContext SyncOutcome("error", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw, failure)
        }

        val tombStore = store.loadTombstoneStore()
        val localConnTombs = filterTombstonesNotBefore(tombStore.connections, tombStore.prunedBefore)
        val localCredTombs = filterTombstonesNotBefore(tombStore.credentials, tombStore.prunedBefore)
        // AutoSync 首次接触该后端且墓碑会静默删远端：跳过，请用户手动合并确认
        if (!saveCandidatePassword && shouldSkipAutoSyncForTombstoneConflict(store, provider, remoteSnap, localConnTombs, localCredTombs)) {
            return@withContext SyncOutcome(
                "skip", connections, credentials, quickCommandsRaw, proxyNodes, aiProvidersRaw, aiGlobalSettingsRaw,
                needsManualTombstoneConfirm = true,
                skipProvider = provider,
            )
        }
        val local = SyncSnapshot(
            connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw,
            localConnTombs, localCredTombs, tombStore.prunedBefore, store.loadSnapshotTime(),
        )
        val syncTime = System.currentTimeMillis()
        val plan = planSync(local, listOf(remoteSnap), lastSyncTime, syncTime)
        var uploadedName: String? = null
        try {
            if (plan.uploadIndexes.isNotEmpty()) uploadedName = uploadSnapshot(instance, plan.snapshot, recoveryPassword)
            check(store.saveSnapshot(plan.snapshot, syncTime, provider)) { "本地同步快照保存失败" }
            store.saveLastSyncTime(provider, syncTime)
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
        // 只动当前同步模式对应后端，避免未选用的 FTP 坏包卡死关加密
        val targetNames = providersFor(store)
        val targets = targetNames.map { providerInstance(store, it, trustInteraction).first }
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
            } catch (failure: Throwable) {
                if (isUnreadableBackupContentError(failure)) null else throw failure
            }
        }
        val tombStore = store.loadTombstoneStore()
        val localSnapshot = SyncSnapshot(
            connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw,
            filterTombstonesNotBefore(tombStore.connections, tombStore.prunedBefore),
            filterTombstonesNotBefore(tombStore.credentials, tombStore.prunedBefore),
            tombStore.prunedBefore,
            store.loadSnapshotTime(),
        )
        val mergedSnapshot = mergeSnapshotUnion(localSnapshot, remoteSnapshots).copy(snapshotTime = System.currentTimeMillis())
        rewriteRecoveryPassword(store, targets, normalizedPassword, mergedSnapshot, targetNames)
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
        val tombStore = store.loadTombstoneStore()
        val snapshot = SyncSnapshot(
            connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw,
            filterTombstonesNotBefore(tombStore.connections, tombStore.prunedBefore),
            filterTombstonesNotBefore(tombStore.credentials, tombStore.prunedBefore),
            tombStore.prunedBefore,
            System.currentTimeMillis(),
        )
        val targetNames = providersFor(store)
        rewriteRecoveryPassword(
            store,
            targetNames.map { providerInstance(store, it, trustInteraction).first },
            normalizedPassword,
            snapshot,
            targetNames,
        )
    }

    internal fun normalizeRecoveryPassword(password: String): String = if (password.isBlank()) "" else password

    internal fun mergeSnapshotUnion(local: SyncSnapshot, remotes: List<SyncSnapshot>): SyncSnapshot = remotes.fold(local) { merged, remote ->
        val (baseConnTombs, baseCredTombs, mergedPrunedBefore) = mergeTombsWithPruneWatermark(
            merged.deletedConnections, remote.deletedConnections,
            merged.deletedCredentials, remote.deletedCredentials,
            merged.tombstonePrunedBefore, remote.tombstonePrunedBefore,
        )
        val connMerge = mergeConnections(merged.connections, remote.connections, -1L, baseConnTombs)
        val conns = connMerge.connections
        val finalConnTombs = pruneConnectionTombstones(
            filterTombstonesNotBefore(mergeTombstoneLists(baseConnTombs, connMerge.dedupTombs), mergedPrunedBefore),
            conns,
        )
        val credMerge = mergeCredentials(merged.credentials, remote.credentials, -1L, baseCredTombs)
        val creds = credMerge.credentials
        val finalCredTombs = pruneCredentialTombstones(
            filterTombstonesNotBefore(mergeTombstoneLists(baseCredTombs, credMerge.inferredTombs), mergedPrunedBefore),
            creds,
        )
        SyncSnapshot(
            connections = conns,
            credentials = creds,
            proxyNodes = mergeProxyNodes(merged.proxyNodes, remote.proxyNodes, -1L),
            quickCommands = mergeQuickCommands(merged.quickCommands, remote.quickCommands, -1L),
            aiProvidersRaw = mergeRawByUpdatedAt(merged.aiProvidersRaw, remote.aiProvidersRaw, -1L),
            aiGlobalSettingsRaw = mergeRawObjectByUpdatedAt(merged.aiGlobalSettingsRaw, remote.aiGlobalSettingsRaw),
            deletedConnections = finalConnTombs,
            deletedCredentials = finalCredTombs,
            tombstonePrunedBefore = mergedPrunedBefore,
            snapshotTime = maxOf(merged.snapshotTime, remote.snapshotTime),
        )
    }

    private fun rewriteRecoveryPassword(
        store: LocalStore,
        providers: List<SyncProvider>,
        password: String,
        snapshot: SyncSnapshot,
        providerNames: List<String> = emptyList(),
    ): SyncSnapshot =
        rewriteRecoveryPasswordTransaction(
            providers = providers,
            snapshot = snapshot,
            recoveryPassword = password,
            saveSnapshot = {
                val ts = System.currentTimeMillis()
                val ok = store.saveSnapshot(snapshot, ts)
                if (ok && providerNames.isNotEmpty()) store.saveLastSyncTimes(providerNames, ts)
                ok
            },
            savePassword = { store.saveRecoveryPassword(password) },
        )

    private fun isUnreadableBackupContentError(err: Throwable): Boolean {
        val msg = err.message.orEmpty()
        return msg.contains("LUMIN2 Base64") ||
            msg.contains("LUMIN2 数据长度不足") ||
            msg.contains("缺少 LUMIN2") ||
            msg.contains("不支持的 LUMIN2") ||
            msg.contains("illegal base64", ignoreCase = true) ||
            msg.contains("Unexpected end") ||
            msg.contains("截断")
    }

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
                    snapshot.deletedConnections, snapshot.deletedCredentials, snapshot.tombstonePrunedBefore,
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

    /** 错误是否像「远程同步目录不存在/被删」——可提示重建目录。 */
    fun looksLikeMissingRemoteDir(error: Throwable?): Boolean {
        if (error == null) return false
        val parts = buildList {
            var cur: Throwable? = error
            var depth = 0
            while (cur != null && depth < 6) {
                add(cur.message.orEmpty())
                add(cur.javaClass.simpleName)
                cur = cur.cause
                depth++
            }
        }.joinToString(" ")
        val msg = parts.lowercase()
        return msg.contains("404")
            || msg.contains("propfind")
            || msg.contains("mkcol")
            || msg.contains("no such file")
            || msg.contains("not found")
            || msg.contains("does not exist")
            || msg.contains("目录不存在")
            || msg.contains("列表失败")
            || msg.contains("创建目录失败")
            || msg.contains("读取远程目录")
            || msg.contains("webdav 列表")
            || msg.contains("webdav 上传")
            || (msg.contains("webdav") && (msg.contains("403") || msg.contains("404")))
    }

    /**
     * 重建当前同步目标的远程目录（WebDAV/SFTP/FTP），R2 无目录概念会跳过。
     * 供「重新创建并重试」在再次 autoSync 前调用。
     */
    suspend fun ensureRemoteDirs(store: LocalStore, trustInteraction: SyncTrustInteraction? = null) = withContext(Dispatchers.IO) {
        val names = providersFor(store)
        val errors = mutableListOf<String>()
        for (name in names) {
            runCatching {
                providerInstance(store, name, trustInteraction).first.ensureRemoteDir()
            }.onFailure { errors += "$name: ${it.message ?: it}" }
        }
        if (errors.isNotEmpty()) {
            throw IllegalStateException(errors.joinToString("; "))
        }
    }

    /**
     * 远程目录被删后的恢复路径：
     * 1) 重建远程目录
     * 2) **强制上传当前本地完整快照**（不读远程、不走墓碑跳过）
     *
     * 解决：仅 ensure 后再 autoSync 可能因 tombstone / 空列表异常导致「看起来成功/跳过但没上传」。
     */
    suspend fun ensureRemoteDirAndUploadLocal(
        store: LocalStore,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode> = store.loadProxyNodes(),
        aiProvidersRaw: String = store.loadAiProvidersRaw(),
        aiGlobalSettingsRaw: String = store.loadAiGlobalSettingsRaw(),
        trustInteraction: SyncTrustInteraction? = null,
        recoveryPassword: String = store.loadRecoveryPassword(),
    ): SyncOutcome = withContext(Dispatchers.IO) {
        if (!syncRunning.compareAndSet(false, true)) {
            return@withContext SyncOutcome(
                "skip", connections, credentials, quickCommandsRaw, proxyNodes,
                aiProvidersRaw, aiGlobalSettingsRaw, SyncInProgressException(),
            )
        }
        try {
            val names = providersFor(store)
            if (names.isEmpty()) {
                return@withContext SyncOutcome(
                    "error", connections, credentials, quickCommandsRaw, proxyNodes,
                    aiProvidersRaw, aiGlobalSettingsRaw, IllegalStateException("未配置同步后端"),
                )
            }
            val targets = names.map { name ->
                val (instance, maxBackups) = providerInstance(store, name, trustInteraction)
                Triple(name, instance, maxBackups)
            }
            // 1) 重建目录
            val dirErrors = mutableListOf<String>()
            for ((name, instance, _) in targets) {
                runCatching { instance.ensureRemoteDir() }
                    .onFailure { dirErrors += "$name: ${it.message ?: it}" }
            }
            if (dirErrors.isNotEmpty()) {
                return@withContext SyncOutcome(
                    "error", connections, credentials, quickCommandsRaw, proxyNodes,
                    aiProvidersRaw, aiGlobalSettingsRaw,
                    IllegalStateException(dirErrors.joinToString("; ")),
                )
            }
            // 2) 强制上传本地（含当前连接列表，即使远程为空/无备份）
            val tombStore = store.loadTombstoneStore()
            val syncTime = System.currentTimeMillis()
            val snapshot = SyncSnapshot(
                connections = connections.map { it.normalizedForSync() },
                credentials = credentials.map { it.normalizedForSync() },
                proxyNodes = proxyNodes,
                quickCommands = quickCommandsRaw,
                aiProvidersRaw = aiProvidersRaw,
                aiGlobalSettingsRaw = aiGlobalSettingsRaw,
                deletedConnections = filterTombstonesNotBefore(tombStore.connections, tombStore.prunedBefore),
                deletedCredentials = filterTombstonesNotBefore(tombStore.credentials, tombStore.prunedBefore),
                tombstonePrunedBefore = tombStore.prunedBefore,
                snapshotTime = syncTime,
            )
            val uploaded = mutableListOf<Pair<SyncProvider, String>>()
            val uploadErrors = mutableListOf<String>()
            for ((name, instance, maxBackups) in targets) {
                runCatching {
                    val fileName = uploadSnapshot(instance, snapshot, recoveryPassword)
                    uploaded += instance to fileName
                    if (maxBackups > 0) runCatching { instance.pruneOldBackups(maxBackups) }
                }.onFailure { uploadErrors += "$name: ${it.message ?: it}" }
            }
            if (uploaded.isEmpty()) {
                return@withContext SyncOutcome(
                    "error", connections, credentials, quickCommandsRaw, proxyNodes,
                    aiProvidersRaw, aiGlobalSettingsRaw,
                    IllegalStateException(uploadErrors.joinToString("; ").ifBlank { "上传失败" }),
                )
            }
            // 本地快照时间与 last_sync 对齐，避免下次又当「需下载空云」
            val primaryProvider = names.firstOrNull()
            check(store.saveSnapshot(snapshot, syncTime, primaryProvider)) { "本地同步快照保存失败" }
            store.saveLastSyncTimes(names, syncTime)
            if (uploadErrors.isNotEmpty()) {
                // 部分成功也返回 upload，并带上警告信息
                return@withContext SyncOutcome(
                    "upload", snapshot.connections, snapshot.credentials, snapshot.quickCommands,
                    snapshot.proxyNodes, snapshot.aiProvidersRaw, snapshot.aiGlobalSettingsRaw,
                    IllegalStateException("部分上传成功，失败：${uploadErrors.joinToString("; ")}"),
                )
            }
            SyncOutcome(
                "upload", snapshot.connections, snapshot.credentials, snapshot.quickCommands,
                snapshot.proxyNodes, snapshot.aiProvidersRaw, snapshot.aiGlobalSettingsRaw,
            )
        } finally {
            syncRunning.set(false)
        }
    }

    private fun fetchSnapshot(store: LocalStore, provider: String, trustInteraction: SyncTrustInteraction?, recoveryPassword: String): SyncSnapshot =
        providerInstance(store, provider, trustInteraction).first.restoreLatestSnapshot(recoveryPassword)

    private fun uploadSnapshot(provider: SyncProvider, snapshot: SyncSnapshot, recoveryPassword: String): String = provider.backupConnections(
        snapshot.connections, snapshot.credentials, snapshot.quickCommands, snapshot.proxyNodes,
        snapshot.aiProvidersRaw, snapshot.aiGlobalSettingsRaw, snapshot.snapshotTime, 0, recoveryPassword,
        snapshot.deletedConnections, snapshot.deletedCredentials, snapshot.tombstonePrunedBefore,
    )

    data class TombstoneConflictItem(val id: String, val name: String, val host: String = "")

    data class TombstoneConflictPreview(
        val providers: List<String> = emptyList(),
        val wouldDeleteConnections: List<TombstoneConflictItem> = emptyList(),
        val wouldDeleteCredentials: List<TombstoneConflictItem> = emptyList(),
    ) {
        val hasConflicts: Boolean get() = wouldDeleteConnections.isNotEmpty() || wouldDeleteCredentials.isNotEmpty()
    }

    /**
     * 合并同步前：先读目标云最新备份，列出本地墓碑将删掉的远端项。
     * 无远端备份或无冲突时返回空列表。
     */
    suspend fun previewTombstoneConflicts(
        store: LocalStore,
        trustInteraction: SyncTrustInteraction? = null,
    ): TombstoneConflictPreview = withContext(Dispatchers.IO) {
        val targetNames = providersFor(store)
        if (targetNames.isEmpty()) return@withContext TombstoneConflictPreview()
        val tombStore = store.loadTombstoneStore()
        val connTombs = filterTombstonesNotBefore(tombStore.connections, tombStore.prunedBefore)
        val credTombs = filterTombstonesNotBefore(tombStore.credentials, tombStore.prunedBefore)
        if (connTombs.isEmpty() && credTombs.isEmpty()) return@withContext TombstoneConflictPreview(providers = targetNames)
        val connMap = tombstoneMap(connTombs)
        val credMap = tombstoneMap(credTombs)
        val seenConn = mutableSetOf<String>()
        val seenCred = mutableSetOf<String>()
        val delConns = mutableListOf<TombstoneConflictItem>()
        val delCreds = mutableListOf<TombstoneConflictItem>()
        val password = store.loadRecoveryPassword()
        for (name in targetNames) {
            val remote = try {
                providerInstance(store, name, trustInteraction).first.restoreLatestSnapshot(password)
            } catch (_: NoBackupException) {
                null
            }
            if (remote == null) continue
            for (rc in remote.connections) {
                val at = connMap[rc.id] ?: continue
                if (rc.id in seenConn) continue
                if (at < rc.lastModified) continue
                seenConn += rc.id
                delConns += TombstoneConflictItem(rc.id, rc.name.ifBlank { rc.host }, rc.host)
            }
            for (rc in remote.credentials) {
                val at = credMap[rc.id] ?: continue
                if (rc.id in seenCred) continue
                if (at < rc.lastModified) continue
                seenCred += rc.id
                delCreds += TombstoneConflictItem(rc.id, rc.name.ifBlank { rc.id })
            }
        }
        TombstoneConflictPreview(targetNames, delConns, delCreds)
    }

    fun clearTombstoneConflicts(store: LocalStore, connectionIds: Collection<String>, credentialIds: Collection<String>) {
        store.clearConnectionTombstones(connectionIds)
        store.clearCredentialTombstones(credentialIds)
    }

    /**
     * 按天数清理墓碑并上传到当前同步模式后端。
     * days <= 0 表示清理全部。
     * @return Triple(removedConnections, removedCredentials, uploadedCount)
     */
    suspend fun pruneSyncTombstones(
        store: LocalStore,
        days: Int,
        connections: List<Connection>,
        credentials: List<Credential>,
        quickCommandsRaw: String,
        proxyNodes: List<ProxyNode>,
        aiProvidersRaw: String,
        aiGlobalSettingsRaw: String,
        trustInteraction: SyncTrustInteraction? = null,
    ): Triple<Int, Int, Int> = withSyncLock {
        val storeBefore = store.loadTombstoneStore()
        val connTombs = storeBefore.connections
        val credTombs = storeBefore.credentials
        val clearAll = days <= 0
        val cutoff = if (clearAll) 0L else System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
        fun prune(list: List<SyncTombstone>): Pair<List<SyncTombstone>, Int> {
            if (list.isEmpty()) return emptyList<SyncTombstone>() to 0
            if (clearAll) return emptyList<SyncTombstone>() to tombstoneMap(list).size
            val kept = list.filter { it.deletedAt >= cutoff }
            val removed = tombstoneMap(list).size - tombstoneMap(kept).size
            return mergeTombstoneLists(emptyList(), kept) to removed
        }
        val (keptConn, removedConn) = prune(connTombs)
        val (keptCred, removedCred) = prune(credTombs)
        if (removedConn == 0 && removedCred == 0) return@withSyncLock Triple(0, 0, 0)
        // 推进清理水位线：之后合并时丢弃更早的远端墓碑
        var prunedBefore = storeBefore.prunedBefore
        val pb = if (clearAll) {
            val now = System.currentTimeMillis()
            val m = maxTombstoneDeletedAt(connTombs, credTombs)
            if (m >= now) m + 1 else now
        } else cutoff
        if (pb > prunedBefore) prunedBefore = pb
        store.saveSyncTombstones(keptConn, keptCred, prunedBefore)
        val snapshot = SyncSnapshot(
            connections, credentials, proxyNodes, quickCommandsRaw, aiProvidersRaw, aiGlobalSettingsRaw,
            keptConn, keptCred, prunedBefore, System.currentTimeMillis(),
        )
        val targetNames = providersFor(store)
        var uploaded = 0
        val failures = mutableListOf<String>()
        for (name in targetNames) {
            try {
                val (instance, maxBackups) = providerInstance(store, name, trustInteraction)
                uploadSnapshot(instance, snapshot, store.loadRecoveryPassword())
                if (maxBackups > 0) runCatching { instance.pruneOldBackups(maxBackups) }
                store.saveLastSyncTime(name, snapshot.snapshotTime)
                uploaded++
            } catch (e: Throwable) {
                failures += "$name: ${e.message ?: e}"
            }
        }
        check(store.saveSnapshot(snapshot, snapshot.snapshotTime)) { "本地同步快照保存失败" }
        if (failures.isNotEmpty() && uploaded == 0) {
            throw IllegalStateException("清理后上传失败：${failures.joinToString("; ")}")
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("已清理并上传 $uploaded 个目标，部分失败：${failures.joinToString("; ")}")
        }
        Triple(removedConn, removedCred, uploaded)
    }
}
