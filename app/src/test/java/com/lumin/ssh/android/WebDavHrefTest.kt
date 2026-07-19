package com.lumin.ssh.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavHrefTest {
    @Test
    fun keepsPlusInTimezone() {
        assertEquals(
            "connections_backup_20260719_140703.013_+0800.lumin2",
            webDavHrefFileName("/dav/Lumin/connections_backup_20260719_140703.013_%2B0800.lumin2"),
        )
        assertEquals(
            "connections_backup_20260719_140703.013_+0800.lumin2",
            webDavHrefFileName("/dav/Lumin/connections_backup_20260719_140703.013_+0800.lumin2"),
        )
        val name = webDavHrefFileName("/dav/Lumin/connections_backup_x_+0800.lumin2")
        assertFalse(name.contains(" "), name)
        assertTrue(name.contains("+"), name)
    }

    @Test
    fun encodePlusAsPercent2B() {
        assertEquals(
            "connections_backup_x_%2B0800.lumin2",
            encodeWebDavSegment("connections_backup_x_+0800.lumin2"),
        )
    }

    @Test
    fun timestampMatchesPcStyle() {
        val ts = backupTimestamp()
        // PC: 20060102_150405.000_-0700  → may contain + or - for zone
        assertTrue(ts.matches(Regex("""\d{8}_\d{6}\.\d{3}_[+-]\d{4}""")), ts)
        assertFalse(ts.contains(" "), ts)
    }

    @Test
    fun decodeDoesNotTurnPlusIntoSpace() {
        assertEquals("a+b", decodeWebDavFileName("a+b"))
        assertEquals("a+b", decodeWebDavFileName("a%2Bb"))
        assertEquals("a b", decodeWebDavFileName("a%20b"))
    }
}
