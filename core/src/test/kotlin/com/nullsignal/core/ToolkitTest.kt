package com.nullsignal.core

import com.nullsignal.core.games.CipherGame
import com.nullsignal.core.games.Ciphers
import com.nullsignal.core.games.Nodes
import com.nullsignal.core.games.VulnHunt
import com.nullsignal.core.security.Audit
import com.nullsignal.core.security.AuditInput
import com.nullsignal.core.security.Crypto
import com.nullsignal.core.security.PasswordStrength
import com.nullsignal.core.security.UrlInspector
import com.nullsignal.core.security.WebAudit
import com.nullsignal.core.story.Story
import com.nullsignal.core.terminal.Action
import com.nullsignal.core.terminal.Terminal
import com.nullsignal.core.terminal.TerminalEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ToolkitTest {

    @Test fun `password strength`() {
        assertEquals(0, PasswordStrength.check("qwerty").level)
        assertTrue(PasswordStrength.check("qwerty").issues.any { "популярных" in it })
        assertTrue(PasswordStrength.check("Tr0ub4dor&3-horse-Battery!").level >= 3)
        assertTrue(PasswordStrength.hasSequence("xx123yy"))
        assertFalse(PasswordStrength.hasSequence("x1y2z9"))
        assertEquals("мгновенно", PasswordStrength.humanTime(0.1))
    }

    @Test fun `aes roundtrip and wrong password`() {
        val blob = Crypto.encrypt("встреча в полночь", "pw")
        assertTrue(Crypto.isEncrypted(blob))
        assertEquals("встреча в полночь", Crypto.decrypt(blob, "pw"))
        assertNull(Crypto.decrypt(blob, "wrong"))
        assertTrue(blob != Crypto.encrypt("встреча в полночь", "pw")) // случайная соль
    }

    @Test fun `url inspector`() {
        assertTrue(UrlInspector.inspect("https://store.steampowered.com/app/1").safeLooking)
        assertTrue(UrlInspector.inspect("https://steamcommunlty-gift.ru/login").warnings.any { "steam" in it })
        assertTrue(UrlInspector.inspect("http://example.com").warnings.any { "http" in it })
        assertTrue(UrlInspector.inspect("https://google.com@evil.example/").warnings.any { "@" in it })
        assertTrue(UrlInspector.inspect("bit.ly/abc").warnings.any { "сокращ" in it })
        assertEquals(mapOf("T" to "WPA", "S" to "home", "P" to "p;w"), UrlInspector.parseWifiQr("WIFI:T:WPA;S:home;P:p\\;w;;"))
    }

    @Test fun `audit scoring`() {
        val good = Audit.run(AuditInput(true, true, false, false, 30, 34, emptyList(), 0))
        assertEquals(100, Audit.score(good))
        val bad = Audit.run(AuditInput(false, false, true, true, 900, 26, listOf("su"), 5))
        assertEquals(0, Audit.score(bad))
    }

    @Test fun `security headers`() {
        val c = WebAudit.check(mapOf("Strict-Transport-Security" to "max-age=1", "Content-Security-Policy" to "frame-ancestors 'none'"))
        assertEquals(3, c.count { it.present })
        assertEquals("C", WebAudit.grade(c))
        assertEquals(1, WebAudit.leaks(mapOf("Server" to "nginx/1.18.0")).size)
        val (h, p) = WebAudit.decodeJwt("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig")!!
        assertEquals("{\"alg\":\"HS256\"}", h)
        assertEquals("{\"sub\":\"1\"}", p)
    }

    @Test fun `ciphers roundtrip`() {
        assertEquals("сигнал", Ciphers.caesar(Ciphers.caesar("сигнал", 5), -5))
        assertEquals("Hello", Ciphers.rot13(Ciphers.rot13("Hello")))
        assertEquals("abc", Ciphers.atbash(Ciphers.atbash("abc")))
        assertEquals("helix", Ciphers.hexDecode(Ciphers.hexEncode("helix")))
        assertEquals("key", Ciphers.binDecode(Ciphers.binEncode("key")))
        CipherGame.levels.forEach { assertTrue(it.title, CipherGame.check(it, it.answer.uppercase() + " ")) }
    }

    @Test fun `vuln snippets valid`() {
        VulnHunt.snippets.forEach { assertTrue(it.vulnerable in it.lines.indices) }
    }

    @Test fun `nodes puzzle is solvable and scrambled`() {
        repeat(50) { seed ->
            val n = Nodes(5, Random(seed))
            assertFalse(n.solved())
        }
        assertEquals(5, Nodes.rotate(5, 4))
        assertEquals(10, Nodes.rotate(5, 1)) // верх+низ → право+лево
    }

    @Test fun `nodes can be solved by rotations`() {
        // перебором поворотов маленькое поле всегда собирается
        val n = Nodes(2, Random(7))
        val start = n.tiles.copyOf()
        var found = false
        loop@ for (a in 0..3) for (b in 0..3) for (c in 0..3) for (d in 0..3) {
            val rot = intArrayOf(a, b, c, d)
            for (i in 0..3) n.tiles[i] = Nodes.rotate(start[i], rot[i])
            if (n.solved()) { found = true; break@loop }
        }
        assertTrue(found)
    }

    @Test fun `new chapters and terminal commands`() {
        assertTrue(Story.chapters.size >= 9)
        val env = object : TerminalEnv {
            override val operator = "op"; override val device = "d"; override val ip: String? = null
            override val objective = ""; override val followers = 0; override val apps = emptyList<String>()
            override fun now() = ""
        }
        val t = Terminal(env)
        assertEquals(Action.Exec("portscan", listOf("192.168.1.1"), "192.168.1.1"), t.execute("portscan 192.168.1.1").action)
        assertNull(t.execute("portscan 8.8.8.8").action)
        assertEquals("ok", t.execute("hex dec 6f 6b").lines.single())
        assertEquals(Action.ToggleMatrix, t.execute("matrix").action)
        assertNotNull(t.execute("dig example.com MX").action)
        assertTrue(t.execute("pwcheck 123456").lines.first().contains("СЛАБЫЙ"))
    }
}
