package com.nullsignal.core

import com.nullsignal.core.security.Pwned
import com.nullsignal.core.security.SpywareCheck
import com.nullsignal.core.security.TrackerDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiStalkTest {
    @Test fun `tracker classification`() {
        val airtag = ByteArray(27).also { it[0] = 0x12; it[1] = 0x19 }
        assertTrue(TrackerDetector.classify(mapOf(0x004C to airtag), emptySet())!!.contains("AirTag"))
        assertEquals("Samsung SmartTag", TrackerDetector.classify(emptyMap(), setOf(0xFD5A)))
        assertEquals("Tile", TrackerDetector.classify(emptyMap(), setOf(0xFEED)))
        assertNull(TrackerDetector.classify(mapOf(0x004C to byteArrayOf(0x07, 0x19)), emptySet())) // AirPods рядом с владельцем
        assertTrue(TrackerDetector.following(0, 11 * 60_000L))
        assertFalse(TrackerDetector.following(0, 5 * 60_000L))
    }

    @Test fun `spyware judging`() {
        val base = SpywareCheck.AppFacts("com.example.app", "App", false, true, emptyList(), false, false, false)
        assertEquals(0, SpywareCheck.judge(base).level)
        assertEquals(2, SpywareCheck.judge(base.copy(pkg = "com.mspy.lite")).level)
        assertEquals(2, SpywareCheck.judge(base.copy(hasLauncherIcon = false, dangerous = listOf("микрофон"))).level)
        assertEquals(1, SpywareCheck.judge(base.copy(accessibility = true)).level)
        assertEquals(2, SpywareCheck.judge(base.copy(accessibility = true, notificationAccess = true)).level)
        assertEquals(0, SpywareCheck.judge(base.copy(system = true, accessibility = true)).level)
    }

    @Test fun `pwned k-anonymity`() {
        // SHA1("password") = 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
        val (prefix, suffix) = Pwned.split("password")
        assertEquals("5BAA6", prefix)
        assertEquals("1E4C9B93F3F0682250B6CF8331B7EE68FD8", suffix)
        val body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9545824\r\n"
        assertEquals(9545824, Pwned.count(body, suffix))
        assertEquals(0, Pwned.count(body, "FFFF"))
    }
}
