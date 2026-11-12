package com.nullsignal.core

import com.nullsignal.core.apps.Traces
import com.nullsignal.core.apps.Traces.Event
import com.nullsignal.core.apps.Traces.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TracesTest {
    private val events = listOf(
        Event(1000, Type.SCREEN_ON),
        Event(1500, Type.UNLOCK),
        Event(2000, Type.APP_OPEN, "org.telegram"),
        Event(5000, Type.APP_CLOSE, "org.telegram"),
        Event(5000, Type.APP_OPEN, "com.android.chrome"),
        Event(9000, Type.SCREEN_OFF),
        Event(20000, Type.SCREEN_ON),
        Event(25000, Type.SCREEN_OFF),
        Event(30000, Type.SCREEN_ON),
        Event(31000, Type.UNLOCK),
        Event(32000, Type.APP_OPEN, "com.camera"),
    )

    @Test fun sessions() {
        val s = Traces.sessions(events, now = 40000)
        assertEquals(3, s.size)
        // свежие сверху
        assertEquals(30000, s[0].start)
        assertEquals(40000, s[0].end)
        assertTrue(s[0].unlocked)
        assertEquals(listOf("com.camera"), s[0].apps)
        assertFalse(s[1].unlocked) // экран просто включили и выключили
        assertEquals(listOf("org.telegram", "com.android.chrome"), s[2].apps)
        assertEquals(8000, s[2].durationMs)
    }

    @Test fun foreground() {
        val t = Traces.foregroundTime(events, now = 40000)
        assertEquals(3000L, t["org.telegram"])
        assertEquals(4000L, t["com.android.chrome"])
        assertEquals(8000L, t["com.camera"])
    }

    @Test fun boot() {
        val s = Traces.sessions(listOf(Event(100, Type.BOOT), Event(200, Type.UNLOCK), Event(300, Type.SCREEN_OFF)), 1000)
        assertTrue(s.single().boot)
        assertEquals("5 мин", Traces.human(5 * 60_000))
        assertTrue(Traces.atNight(s.single()) { 3 })
    }
}
