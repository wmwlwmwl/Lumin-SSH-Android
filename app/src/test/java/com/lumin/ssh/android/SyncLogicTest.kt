package com.lumin.ssh.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeSyncProvider(
    private val backups: MutableMap<String, SyncSnapshot> = linkedMapOf(),
    private val passwords: MutableMap<String, String> = mutableMapOf(),
    private val uploadFailure: Throwable? = null,
    private val downloadFailure: Throwable? = null,
) : SyncProvider {
    val deleted = mutableListOf<String>()
    val uploadedSnapshots = mutableListOf<SyncSnapshot>()
    val uploadedPasswords = mutableListOf<String>()
    var sequence = 0

    override fun listBackupNames(): List<String> = downloadFailure?.let { throw it } ?: backups.keys.toList()
    override fun pruneOldBackups(maxBackups: Int) = Unit
    override fun deleteBackup(name: String) {
        deleted += name
        backups.remove(name)
        passwords.remove(name)
    }
    override fun restoreSnapshot(name: String, recoveryPassword: String): SyncSnapshot {
        downloadFailure?.let { throw it }
        if (passwords[name].orEmpty() != recoveryPassword) throw RecoveryPasswordException()
        return backups.getValue(name)
    }
    override fun backupConnections(connections: List<Connection>, credentials: List<Credential>, quickCommands: String, proxyNodes: List<ProxyNode>, aiProvidersRaw: String, aiGlobalSettingsRaw: String, snapshotTime: Long, maxBackups: Int, recoveryPassword: String): String {
        uploadFailure?.let { throw it }
        val name = "connections_backup_${++sequence}.json"
        val snapshot = SyncSnapshot(connections, credentials, proxyNodes, quickCommands, aiProvidersRaw, aiGlobalSettingsRaw, snapshotTime)
        backups[name] = snapshot
        passwords[name] = recoveryPassword
        uploadedSnapshots += snapshot
        uploadedPasswords += recoveryPassword
        return name
    }
}

class SyncLogicTest {
    @Test
    fun lumin2FixedVectorMatchesGo() {
        val password = "跨端-password-🔐"
        val payload = """{"connections":[{"id":"vector","host":"example.com","port":22,"username":"root"}],"snapshot_time":1700000000000}"""
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 16).toByte() }
        val expected = "LUMIN2:AgADNFAAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobuSS2sCUnXOM1UV1g4ZCENiXBLVh7tzhcV8HkJjqqVdjqjtgc92HbU3EU7+BTIH/QY2lRWwWuHVNiSGCjeIWbJ6o/J5CiWGel3ziScbUDW+RH8VGAgEcPQoj2WgSwzsG2ablk02o/U5EJDWs3NJcrLRpFNoaAwNh3OeGLct1sA/w="

        assertEquals(expected, encryptLumin2WithSaltNonce(payload, password, salt, nonce))
        assertEquals(payload, decryptLumin2(expected, password))
    }

    @Test
    fun completeSnapshotImportOnlyAddsData() {
        val localConnection = Connection("local", "local", "local.example", username = "root")
        val localCredential = Credential("local-cred", password = "secret")
        val localProxy = ProxyNode("local-proxy", host = "proxy.example")
        val addedConnection = Connection("remote", "remote", "remote.example", username = "root", credentialId = "remote-cred", proxyMode = "node", proxyNodeId = "remote-proxy")
        val snapshot = SyncSnapshot(
            connections = listOf(localConnection.copy(id = "duplicate"), addedConnection),
            credentials = listOf(Credential("remote-cred", password = "remote-secret")),
            proxyNodes = listOf(ProxyNode("remote-proxy", host = "remote-proxy.example")),
        )

        val result = mergeImportedSnapshot(snapshot, listOf(localConnection), listOf(localCredential), listOf(localProxy), "", "", "local-settings", 10)

        assertEquals(listOf("local", "remote"), result.connections.map { it.id })
        assertEquals(listOf("local-cred", "remote-cred"), result.credentials.map { it.id })
        assertEquals(listOf("local-proxy", "remote-proxy"), result.proxyNodes.map { it.id })
        assertEquals("local-settings", result.aiGlobalSettingsRaw)
        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
    }

    @Test
    fun multiProviderUnionDoesNotDeleteProviderOnlyItem() {
        val a = Connection("a", "a", "a.example", username = "root", lastModified = 10)
        val b = Connection("b", "b", "b.example", username = "root", lastModified = 20)
        val merged = SyncHelper.mergeConnections(SyncHelper.mergeConnections(emptyList(), listOf(a), -1), listOf(b), -1)
        assertEquals(setOf("a", "b"), merged.map { it.id }.toSet())
    }

    @Test
    fun lumin2RoundTripAndWrongPassword() {
        val password = "correct horse battery staple"
        val payload = """{"connections":[],"snapshot_time":1}"""
        val encrypted = encryptLumin2(payload, password)

        assertTrue(encrypted.startsWith("LUMIN2:"))
        assertEquals(payload, decryptLumin2(encrypted, password))
        assertTrue(runCatching { decryptLumin2(encrypted, "wrong") }.isFailure)
    }

    @Test
    fun pcLegacyHexStillImports() {
        val password = "legacy password"
        val payload = """{"connections":[],"snapshot_time":1}"""
        val encrypted = encryptDesktopHexSnapshot(payload, sha256(password))

        assertEquals(payload, decryptDesktopHexSnapshot(encrypted, sha256(password)))
        assertEquals(1, parseSnapshotPayload(encrypted, password).snapshotTime)
    }

    @Test
    fun explicitFtpsUsesTls12ForDataChannelCompatibility() {
        assertEquals(listOf("TLSv1.2"), FTP_TLS_PROTOCOLS.toList())
    }

    @Test
    fun ftpModeDefaultsToExplicitTlsAndRejectsUnknownValues() {
        assertEquals(FTP_MODE_EXPLICIT_TLS, normalizeFtpMode(""))
        assertEquals(FTP_MODE_EXPLICIT_TLS, normalizeFtpMode(FTP_MODE_EXPLICIT_TLS))
        assertEquals(FTP_MODE_PLAIN, normalizeFtpMode(FTP_MODE_PLAIN))
        assertFailsWith<IllegalArgumentException> { normalizeFtpMode("implicit_tls") }
    }

    @Test
    fun encryptedBackupNamesUseLumin2AndRecognizeLegacyEnc() {
        assertTrue(backupFileName(true).endsWith(".lumin2"))
        assertTrue(isBackupName("connections_backup_20260713_120000.000_+0800.lumin2"))
        assertTrue(isBackupName("connections_backup_20260713_120000.000_+0800.enc"))
        assertTrue(isBackupName("connections_backup_20260713_120000.000_+0800.json"))
    }

    @Test
    fun restoreLatestIsStrictAndDoesNotFallBackToOldBackup() {
        val old = SyncSnapshot(listOf(Connection("old", "old", "old.example", username = "root")), emptyList())
        val latest = SyncSnapshot(listOf(Connection("latest", "latest", "latest.example", username = "root")), emptyList())
        val provider = FakeSyncProvider(
            linkedMapOf("connections_backup_1.json" to old, "connections_backup_2.json" to latest),
            mutableMapOf("connections_backup_1.json" to "candidate", "connections_backup_2.json" to "current"),
        )

        assertFailsWith<RecoveryPasswordException> { provider.restoreLatestSnapshot("candidate") }
    }

    @Test
    fun allDownloadFailsClosedExceptNoBackup() {
        val available = FakeSyncProvider(linkedMapOf("connections_backup_1.json" to SyncSnapshot(emptyList(), emptyList())))
        val broken = FakeSyncProvider(downloadFailure = IllegalStateException("下载中断"))

        assertFailsWith<IllegalStateException> { SyncHelper.fetchAllStrict(listOf(available, broken), "") }
        assertEquals(listOf<SyncSnapshot?>(null), SyncHelper.fetchAllStrict(listOf(FakeSyncProvider()), ""))
    }

    @Test
    fun multiProviderUploadRollsBackAlreadyUploadedBackup() {
        val first = FakeSyncProvider()
        val failure = IllegalStateException("第二后端上传失败")
        val second = FakeSyncProvider(uploadFailure = failure)
        val snapshot = SyncSnapshot(listOf(Connection("local", "local", "local.example", username = "root")), emptyList(), snapshotTime = 10)

        val actual = assertFailsWith<IllegalStateException> {
            SyncHelper.syncProvidersTransaction(listOf(first, second), snapshot, "new-password")
        }

        assertEquals(failure, actual)
        assertEquals(listOf("connections_backup_1.json"), first.deleted)
        assertFailsWith<NoBackupException> { first.restoreLatestSnapshot("new-password") }
    }

    @Test
    fun passwordRewritePersistsSnapshotAndReturnsIt() {
        val provider = FakeSyncProvider()
        val snapshot = SyncSnapshot(
            listOf(Connection("local", "local", "local.example", username = "root")),
            emptyList(),
            snapshotTime = 42,
        )
        var snapshotSaved = false
        var savedPassword = ""

        val result = SyncHelper.rewriteRecoveryPasswordTransaction(
            listOf(provider), snapshot, "new-password",
            saveSnapshot = { snapshotSaved = true; true },
            savePassword = { savedPassword = "new-password" },
        )

        assertEquals(snapshot, result)
        assertTrue(snapshotSaved)
        assertEquals("new-password", savedPassword)
    }

    @Test
    fun passwordRewriteRollsBackCloudWhenUploadFails() {
        val first = FakeSyncProvider()
        val failure = IllegalStateException("第二后端上传失败")
        val second = FakeSyncProvider(uploadFailure = failure)
        val snapshot = SyncSnapshot(emptyList(), emptyList(), snapshotTime = 42)
        var snapshotSaved = false
        var passwordSaved = false

        val actual = assertFailsWith<IllegalStateException> {
            SyncHelper.rewriteRecoveryPasswordTransaction(
                listOf(first, second), snapshot, "new-password",
                saveSnapshot = { snapshotSaved = true; true },
                savePassword = { passwordSaved = true },
            )
        }

        assertEquals(failure, actual)
        assertEquals(listOf("connections_backup_1.json"), first.deleted)
        assertTrue(!snapshotSaved)
        assertTrue(!passwordSaved)
    }

    @Test
    fun passwordRewriteRollsBackCloudWhenLocalSnapshotSaveFails() {
        val provider = FakeSyncProvider()
        val snapshot = SyncSnapshot(emptyList(), emptyList(), snapshotTime = 42)
        var passwordSaved = false

        assertFailsWith<IllegalStateException> {
            SyncHelper.rewriteRecoveryPasswordTransaction(
                listOf(provider), snapshot, "new-password",
                saveSnapshot = { false },
                savePassword = { passwordSaved = true },
            )
        }

        assertEquals(listOf("connections_backup_1.json"), provider.deleted)
        assertTrue(!passwordSaved)
    }

    @Test
    fun passwordRewriteRollsBackCloudWhenPasswordSaveFails() {
        val provider = FakeSyncProvider()
        val snapshot = SyncSnapshot(emptyList(), emptyList(), snapshotTime = 42)
        var snapshotSaved = false
        val failure = IllegalStateException("密码保存失败")

        val actual = assertFailsWith<IllegalStateException> {
            SyncHelper.rewriteRecoveryPasswordTransaction(
                listOf(provider), snapshot, "new-password",
                saveSnapshot = { snapshotSaved = true; true },
                savePassword = { throw failure },
            )
        }

        assertEquals(failure, actual)
        assertTrue(snapshotSaved)
        assertEquals(listOf("connections_backup_1.json"), provider.deleted)
    }

    @Test
    fun snapshotPayloadUsesStableTypedFailures() {
        val encrypted = encryptLumin2("""{"connections":[],"snapshot_time":1}""", "right")
        val malformed = "LUMIN2:" + java.util.Base64.getEncoder().encodeToString(ByteArray(10))

        assertIs<RecoveryPasswordException>(runCatching { parseSnapshotPayload(encrypted, null) }.exceptionOrNull())
        assertIs<RecoveryPasswordException>(runCatching { parseSnapshotPayload(encrypted, "wrong") }.exceptionOrNull())
        assertIs<SnapshotFormatException>(runCatching { parseSnapshotPayload("LUMIN2:not-base64", "right") }.exceptionOrNull())
        assertIs<SnapshotFormatException>(runCatching { parseSnapshotPayload(malformed, "right") }.exceptionOrNull())
        assertIs<SnapshotFormatException>(runCatching { parseSnapshotPayload("abc", "right") }.exceptionOrNull())
        assertIs<SnapshotFormatException>(runCatching { parseSnapshotPayload("not a snapshot", null) }.exceptionOrNull())
        assertIs<SnapshotFormatException>(runCatching { parseSnapshotPayload("{broken", null) }.exceptionOrNull())
    }

    @Test
    fun recoveryPasswordOnlyNormalizesPureWhitespace() {
        assertEquals("", SyncHelper.normalizeRecoveryPassword(" \t\r\n "))
        assertEquals("  password  ", SyncHelper.normalizeRecoveryPassword("  password  "))
    }

    @Test
    fun passwordChangeUnionStartsWithCompleteLocalSnapshot() {
        val local = SyncSnapshot(
            connections = listOf(Connection("local", "local", "local.example", username = "root", lastModified = 1)),
            credentials = listOf(Credential("local-credential", password = "secret", lastModified = 1)),
            proxyNodes = listOf(ProxyNode("local-proxy", host = "proxy.example", updatedAt = 1)),
            quickCommands = """[{"name":"local","command":"date","last_modified":1}]""",
            aiProvidersRaw = """[{"id":"local-ai","updatedAt":1}]""",
            aiGlobalSettingsRaw = """{"currentProviderId":"local-ai","updatedAt":1}""",
            snapshotTime = 1,
        )
        val first = SyncSnapshot(
            connections = listOf(Connection("remote-a", "a", "a.example", username = "root", lastModified = 2)),
            credentials = emptyList(),
            snapshotTime = 2,
        )
        val second = SyncSnapshot(
            connections = listOf(Connection("remote-b", "b", "b.example", username = "root", lastModified = 3)),
            credentials = emptyList(),
            snapshotTime = 3,
        )

        val merged = SyncHelper.mergeSnapshotUnion(local, listOf(first, second))

        assertEquals(setOf("local", "remote-a", "remote-b"), merged.connections.map { it.id }.toSet())
        assertEquals(listOf("local-credential"), merged.credentials.map { it.id })
        assertEquals(listOf("local-proxy"), merged.proxyNodes.map { it.id })
        assertTrue(merged.quickCommands.contains("local"))
        assertTrue(merged.aiProvidersRaw.contains("local-ai"))
        assertTrue(merged.aiGlobalSettingsRaw.contains("local-ai"))
    }

    @Test
    fun passwordRewriteUploadsSameMergedSnapshotOncePerProvider() {
        val first = FakeSyncProvider()
        val second = FakeSyncProvider()
        val snapshot = SyncSnapshot(
            listOf(Connection("merged", "merged", "merged.example", username = "root")),
            emptyList(),
            snapshotTime = 42,
        )

        SyncHelper.syncProvidersTransaction(listOf(first, second), snapshot, "  spaced password  ")

        assertEquals(listOf(snapshot), first.uploadedSnapshots)
        assertEquals(listOf(snapshot), second.uploadedSnapshots)
        assertEquals(listOf("  spaced password  "), first.uploadedPasswords)
        assertEquals(listOf("  spaced password  "), second.uploadedPasswords)
    }

    @Test
    fun businessComparisonIgnoresSnapshotTimeStableIdOrderAndJsonObjectKeyOrder() {
        val first = SyncSnapshot(
            connections = listOf(
                Connection("a", "a", "a.example", username = "root", lastModified = 1),
                Connection("b", "b", "b.example", username = "root", lastModified = 2),
            ),
            credentials = emptyList(),
            aiProvidersRaw = """[{"id":"one","nested":{"a":1,"b":2}},{"id":"two"}]""",
            aiGlobalSettingsRaw = """{"a":1,"nested":{"x":true,"y":null}}""",
            snapshotTime = 1,
        )
        val second = first.copy(
            connections = first.connections.reversed(),
            aiProvidersRaw = """[{"id":"two"},{"nested":{"b":2,"a":1},"id":"one"}]""",
            aiGlobalSettingsRaw = """{"nested":{"y":null,"x":true},"a":1}""",
            snapshotTime = 999,
        )

        assertTrue(SyncHelper.snapshotBusinessEqual(first, second))
        assertTrue(!SyncHelper.snapshotBusinessEqual(first.copy(quickCommands = """[{"name":"a"},{"name":"b"}]"""), second.copy(quickCommands = """[{"name":"b"},{"name":"a"}]""")))
    }

    @Test
    fun planUploadsOnlyMissingOrDifferentProvidersAndKeepsFourActions() {
        val localItem = Connection("local", "local", "local.example", username = "root", lastModified = 20)
        val remoteItem = Connection("remote", "remote", "remote.example", username = "root", lastModified = 30)
        val local = SyncSnapshot(listOf(localItem), emptyList(), snapshotTime = 10)
        val same = local.copy(snapshotTime = 1)
        val different = SyncSnapshot(listOf(remoteItem), emptyList(), snapshotTime = 15)

        assertEquals("skip", SyncHelper.planSync(local, listOf(same), 10, 100).action)
        assertEquals("upload", SyncHelper.planSync(local, listOf(null), 10, 100).action)
        assertEquals("download", SyncHelper.planSync(local, listOf(SyncSnapshot(listOf(remoteItem), emptyList(), snapshotTime = 40)), 25, 100).action)
        val merged = SyncHelper.planSync(local, listOf(same, different, null), 10, 100)
        assertEquals("merge", merged.action)
        assertEquals(setOf(0, 1, 2), merged.uploadIndexes)
        assertEquals(setOf("local", "remote"), merged.snapshot.connections.map { it.id }.toSet())
    }

    @Test
    fun newerCloudSnapshotPropagatesDeletionIntoFinalSnapshot() {
        val deleted = Connection("deleted", "deleted", "deleted.example", username = "root", lastModified = 10)
        val kept = Connection("kept", "kept", "kept.example", username = "root", lastModified = 20)
        val local = SyncSnapshot(listOf(deleted, kept), emptyList(), snapshotTime = 20)
        val olderCloud = local.copy(snapshotTime = 21)
        val newerCloud = SyncSnapshot(listOf(kept), emptyList(), snapshotTime = 30)

        val plan = SyncHelper.planSync(local, listOf(olderCloud, newerCloud), 20, 40)

        assertEquals(listOf("kept"), plan.snapshot.connections.map { it.id })
        assertEquals(setOf(0), plan.uploadIndexes)
        assertEquals("merge", plan.action)
    }
}
