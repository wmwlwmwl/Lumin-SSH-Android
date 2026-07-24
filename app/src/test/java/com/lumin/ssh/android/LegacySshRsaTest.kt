package com.lumin.ssh.android

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacySshRsaTest {
    @Test
    fun hostKeyAlgorithmsOnlyAppendSshRsaWhenEnabled() {
        assertEquals(SSH_HOST_KEY_ALGORITHMS, hostKeyAlgorithmsForConnection(false))
        assertFalse(hostKeyAlgorithmsForConnection(false).contains("ssh-rsa"))
        assertEquals("$SSH_HOST_KEY_ALGORITHMS,ssh-rsa", hostKeyAlgorithmsForConnection(true))
        assertTrue(hostKeyAlgorithmsForConnection(true).endsWith(",ssh-rsa"))
    }

    @Test
    fun connectionJsonRoundTripPreservesLegacyFlag() {
        val source = Connection(
            id = "legacy-1",
            name = "legacy",
            host = "192.0.2.10",
            username = "root",
            allowLegacySshRsa = true,
            lastModified = 1_700_000_000_000L,
        )
        val json = source.toJson()
        assertTrue(json.optBoolean("allowLegacySshRsa"))

        val restored = json.toConnection()
        assertTrue(restored.allowLegacySshRsa)

        val defaultJson = Connection(
            id = "modern-1",
            name = "modern",
            host = "192.0.2.11",
            username = "root",
            lastModified = 1_700_000_000_001L,
        ).toJson()
        assertFalse(defaultJson.has("allowLegacySshRsa"))
        assertFalse(JSONObject("""{"id":"old","host":"h","username":"u"}""").toConnection().allowLegacySshRsa)
    }
}
