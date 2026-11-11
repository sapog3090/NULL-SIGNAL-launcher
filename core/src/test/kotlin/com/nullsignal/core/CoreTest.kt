package com.nullsignal.core

import com.nullsignal.core.breach.Breach
import com.nullsignal.core.net.Subnet
import com.nullsignal.core.story.Progress
import com.nullsignal.core.story.QuestEngine
import com.nullsignal.core.story.Story
import com.nullsignal.core.story.Trigger
import com.nullsignal.core.terminal.Action
import com.nullsignal.core.terminal.Module
import com.nullsignal.core.terminal.Terminal
import com.nullsignal.core.terminal.TerminalEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestEngineTest {
    private val engine = QuestEngine()

    @Test fun `wrong trigger does nothing`() {
        val p = Progress()
        assertEquals(p, engine.on(p, Trigger.BREACH_WON))
    }

    @Test fun `story plays through in order`() {
        var p = Progress()
        for (t in listOf(Trigger.WHOAMI, Trigger.SCAN_DONE, Trigger.BREACH_WON, Trigger.SIGNAL_CMD)) {
            val before = p.chapter
            p = engine.on(p, t)
            assertEquals(before + 1, p.chapter)
        }
        assertNull(engine.current(p).trigger)
        assertEquals(Story.chapters.sumOf { it.reward }, p.followers)
        // На последней главе ничего не ломается
        assertEquals(p, engine.on(p, Trigger.SIGNAL_CMD))
    }

    @Test fun `unread counts new messages`() {
        var p = Progress()
        assertEquals(Story.chapters[0].messages.size, engine.unread(p))
        p = engine.markRead(p)
        assertEquals(0, engine.unread(p))
        p = engine.on(p, Trigger.WHOAMI)
        assertEquals(Story.chapters[1].messages.size, engine.unread(p))
    }
}

class BreachTest {
    @Test fun `score counts exact and misplaced without double counting`() {
        val a = Breach.score("A1B2", "A2B1")
        assertEquals(2, a.exact)
        assertEquals(2, a.misplaced)
        val b = Breach.score("AAB0", "AAAA")
        assertEquals(2, b.exact)
        assertEquals(0, b.misplaced)
        val c = Breach.score("1123", "3211")
        assertEquals(0, c.exact)
        assertEquals(4, c.misplaced)
    }

    @Test fun `win and loss`() {
        val g = Breach(secret = "beef")
        assertNull(g.guess("xyz1"))
        g.guess("0000")
        assertEquals(Breach.State.RUNNING, g.state)
        g.guess("BEEF")
        assertEquals(Breach.State.WON, g.state)
        assertNull(g.guess("0000"))

        val l = Breach(maxAttempts = 2, secret = "1234")
        l.guess("0000"); l.guess("0000")
        assertEquals(Breach.State.LOST, l.state)
    }
}

class SubnetTest {
    @Test fun `hosts of 24 exclude network broadcast and self`() {
        val h = Subnet.hosts("192.168.1.42", 24)
        assertEquals(253, h.size)
        assertEquals("192.168.1.1", h.first())
        assertEquals("192.168.1.254", h.last())
        assertFalse("192.168.1.42" in h)
    }

    @Test fun `wide subnets are trimmed to 24`() {
        assertEquals(253, Subnet.hosts("10.0.5.7", 8).size)
        assertTrue(Subnet.hosts("10.0.5.7", 8).all { it.startsWith("10.0.5.") })
    }

    @Test fun `private ranges`() {
        assertTrue(Subnet.isPrivate("10.1.2.3"))
        assertTrue(Subnet.isPrivate("172.20.0.1"))
        assertTrue(Subnet.isPrivate("192.168.0.10"))
        assertFalse(Subnet.isPrivate("8.8.8.8"))
        assertFalse(Subnet.isPrivate("172.32.0.1"))
    }
}

class TerminalTest {
    private val env = object : TerminalEnv {
        override val operator = "op"
        override val device = "Pixel"
        override val ip = "192.168.1.5"
        override val objective = "test"
        override val followers = 7
        override val apps = listOf("Chrome", "Camera", "Telegram")
        override fun now() = "now"
    }
    private val t = Terminal(env)

    @Test fun `whoami fires trigger`() = assertEquals(Trigger.WHOAMI, t.execute("whoami").trigger)

    @Test fun `run picks best app match`() {
        assertEquals(Action.LaunchApp("Camera"), t.execute("run cam").action)
        assertEquals(Action.LaunchApp("Telegram"), t.execute("run gram").action)
        assertNull(t.execute("run zzz").action)
    }

    @Test fun `open modules and unknown commands`() {
        assertEquals(Action.Open(Module.BREACH), t.execute("open breach").action)
        assertEquals(Action.Clear, t.execute("clear").action)
        assertTrue(t.execute("foo").lines.single().contains("не найдена"))
        assertEquals(listOf("open breach", "clear", "foo"), t.history)
    }
}

class ToolsTest {
    private val env = object : TerminalEnv {
        override val operator = "op"; override val device = "d"; override val ip: String? = null
        override val objective = ""; override val followers = 0; override val apps = emptyList<String>()
        override fun now() = ""
    }

    @Test fun `hash and base64`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", com.nullsignal.core.terminal.Tools.hash("md5", "hello"))
        assertNull(com.nullsignal.core.terminal.Tools.hash("crc", "x"))
        val t = Terminal(env)
        assertEquals("aGVsbG8=", t.execute("b64 enc hello").lines.single())
        assertEquals("hello", t.execute("b64 dec aGVsbG8=").lines.single())
    }

    @Test fun `password has all classes`() {
        val p = com.nullsignal.core.terminal.Tools.password(16)
        assertEquals(16, p.length)
        assertTrue(p.any { it.isLowerCase() } && p.any { it.isUpperCase() } && p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() })
    }

    @Test fun `exec commands route to android with usage check`() {
        val t = Terminal(env)
        assertEquals(Action.Exec("ping", listOf("8.8.8.8"), "8.8.8.8"), t.execute("ping 8.8.8.8").action)
        assertNull(t.execute("ping").action)
        assertEquals(Action.Exec("sh", listOf("ls", "-la", "/"), "ls -la /"), t.execute("sh ls -la /").action)
        assertEquals(Action.Exec("sysinfo", emptyList(), ""), t.execute("sysinfo").action)
        assertEquals(Action.Open(Module.BLE), t.execute("ble").action)
    }

    @Test fun `wifi helpers`() {
        assertEquals("WPA3", com.nullsignal.core.terminal.Tools.wifiSecurity("[RSN-SAE-CCMP][ESS]"))
        assertEquals("OPEN", com.nullsignal.core.terminal.Tools.wifiSecurity("[ESS]"))
        assertEquals(6, com.nullsignal.core.terminal.Tools.wifiChannel(2437))
        assertEquals(36, com.nullsignal.core.terminal.Tools.wifiChannel(5180))
        assertEquals(1.0, com.nullsignal.core.terminal.Tools.distanceMeters(-59), 0.001)
    }
}

class AcademyTest {
    private val A = com.nullsignal.core.academy.Academy
    private val L = A.lessons

    @Test fun `lessons are well formed`() {
        assertEquals(L.size, L.map { it.id }.toSet().size)
        L.forEach { l ->
            assertTrue(l.tasks.isNotEmpty())
            l.tasks.forEach { t ->
                if (t is com.nullsignal.core.academy.Task.Choice) assertTrue(t.correct in t.options.indices)
                if (t is com.nullsignal.core.academy.Task.Input) assertTrue(t.answers.isNotEmpty())
            }
        }
    }

    @Test fun `input answers are normalized`() {
        val t = com.nullsignal.core.academy.Task.Input("q", null, listOf("10.0.0.5:443"), "")
        assertTrue(A.check(t, "  \"10.0.0.5:443\" "))
        assertFalse(A.check(t, "10.0.0.5 443"))
        val c = L[0].tasks[0]
        assertTrue(A.check(c, "1"))
        assertFalse(A.check(c, "0"))
    }

    @Test fun `lessons unlock in order`() {
        assertTrue(A.unlocked(0, emptySet()))
        assertFalse(A.unlocked(1, emptySet()))
        assertTrue(A.unlocked(1, setOf(L[0].id)))
    }
}
