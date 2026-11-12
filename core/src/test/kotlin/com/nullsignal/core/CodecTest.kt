package com.nullsignal.core

import com.nullsignal.core.util.Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {
    private fun run(id: String, s: String) = Codec.show(Codec.op(id).apply(s.toByteArray()))

    @Test fun roundTrips() {
        val text = "Встреча в 23:00, key=42 & <b>"
        for ((enc, dec) in listOf("b64" to "unb64", "hex" to "unhex", "url" to "unurl", "bin" to "unbin", "dec" to "undec", "b32" to "unb32", "uni" to "ununi")) {
            val coded = Codec.op(enc).apply(text.toByteArray())
            assertEquals(enc, text, String(Codec.op(dec).apply(coded)))
        }
        assertEquals("SGk=", run("b64", "Hi"))
        assertEquals("48 69", run("hex", "Hi"))
        assertEquals("Hi", run("unhex", "0x48 0x69"))
        assertEquals("Hi", run("unhex", "48:69"))
        assertEquals("Hi", run("unb64", "SGk"))
        assertEquals("JBUQ====", run("b32", "Hi"))
        assertEquals("01001000 01101001", run("bin", "Hi"))
        assertEquals("72 105", run("dec", "Hi"))
        assertEquals("a b&c", run("unurl", "a%20b%26c"))
        assertEquals("<a> & A", run("unhtml", "&lt;a&gt; &amp; &#65;"))
        assertEquals("Привет", run("ununi", "\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442"))
        assertEquals("Uryyb", run("rot13", "Hello"))
        assertEquals("SOS", run("unmorse", "... --- ..."))
        assertEquals("5d41402abc4b2a76b9719d911017c592", run("md5", "hello"))
        assertEquals("3610a686", run("crc32", "hello"))
        val gz = Codec.op("gzip").apply("сжатый текст".toByteArray())
        assertEquals("сжатый текст", String(Codec.op("ungzip").apply(Codec.op("unb64").apply(gz))))
        assertEquals(Codec.ops.size, Codec.ops.map { it.id }.toSet().size)
    }

    @Test fun badInputExplained() {
        for ((id, s) in listOf("unhex" to "xyz", "unb64" to "при вет", "unbin" to "0102", "unurl" to "plain", "ungzip" to "abc", "jwt" to "a.b", "json" to "{oops")) {
            val e = runCatching { Codec.op(id).apply(s.toByteArray()) }.exceptionOrNull()
            assertTrue(id, e is Codec.Bad && e.message!!.isNotEmpty())
        }
        assertTrue(Codec.show(byteArrayOf(0, 1, 2, -1)).startsWith("[не текст, 4 байт] 00 01 02 ff"))
    }

    @Test fun autoDetect() {
        fun best(s: String) = Codec.detect(s).firstOrNull()
        assertEquals("Hello, world", best("SGVsbG8sIHdvcmxk")!!.text)
        assertEquals(listOf("из Base64"), best("SGVsbG8sIHdvcmxk")!!.steps)
        assertEquals("Hello", best("48656c6c6f")!!.text)
        // hex внутри Base64: два шага
        val nested = String(Codec.op("b64").apply("70617373776f72643a2071776572747931".toByteArray()))
        val f = best(nested)!!
        assertEquals("password: qwerty1", f.text)
        assertEquals(listOf("из Base64", "из hex"), f.steps)
        assertEquals("привет это ключ", best("%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82+%D1%8D%D1%82%D0%BE+%D0%BA%D0%BB%D1%8E%D1%87")!!.text)
        assertEquals("the secret key", best("gur frperg xrl")!!.text)
        assertEquals("SOS", best("... --- ...")!!.text)
        assertEquals("Hi there", best("01001000 01101001 00100000 01110100 01101000 01100101 01110010 01100101")!!.text)
        // обычный текст и числа ни во что не «раскодируются»
        assertNull(best("просто обычная фраза"))
        assertNull(best("1234"))
        assertNull(best("deadbeef"))
        assertNull(best("hello world"))
    }

    @Test fun jwtAndHashes() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val out = Codec.jwt(token)
        assertTrue(out, out.contains("HS256") && out.contains("John Doe") && out.contains("нет срока действия"))
        assertTrue(Codec.detect(token).first().steps == listOf("JWT"))
        assertTrue(Codec.jwt("eyJhbGciOiJub25lIn0.eyJleHAiOjF9.x").contains("без подписи"))
        assertTrue(Codec.hashKind("5d41402abc4b2a76b9719d911017c592")!!.startsWith("MD5"))
        assertTrue(Codec.hashKind("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")!!.startsWith("SHA-256"))
        assertTrue(Codec.hashKind("\$2b\$12\$abcdefghijklmnopqrstuv")!!.startsWith("bcrypt"))
        assertNull(Codec.hashKind("hello"))
        assertNotNull(Codec.utf8("ok".toByteArray()))
        assertArrayEquals(byteArrayOf(-1, 0), Codec.fromHex("ff00"))
    }
}

class StoryCipherTest {
    @Test fun insiderMessageDecodes() {
        val ch = com.nullsignal.core.story.Story.chapters.first { it.id == "insider" }
        val coded = ch.messages.first { it.from == com.nullsignal.core.story.Member.GHOST }.text
        val f = Codec.detect(coded).first()
        assertTrue(f.text, f.text.contains("helix знает про сойку"))
        assertEquals(listOf("из Base64"), f.steps)
        val ids = com.nullsignal.core.story.Story.chapters.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun morseRiddleAndAnswers() {
        val story = com.nullsignal.core.story.Story.chapters
        val ch = story.first { it.id == "morse" }
        val code = ch.messages.first { it.text.startsWith(".") }.text
        val plain = com.nullsignal.core.spy.Morse.decode(code)
        assertEquals("LOCKER 17", plain.uppercase())
        // ДЕКОДЕР распознаёт морзянку сам
        assertTrue(Codec.detect(code).any { it.text.uppercase().contains("LOCKER 17") })
        val engine = com.nullsignal.core.story.QuestEngine()
        val p = com.nullsignal.core.story.Progress(chapter = story.indexOf(ch))
        assertEquals(true, engine.check(p, plain))
        assertEquals(true, engine.check(p, "  Ячейка-17 "))
        assertEquals(false, engine.check(p, "locker 71"))
        assertEquals(false, engine.check(p, ""))
        org.junit.Assert.assertNull(engine.check(com.nullsignal.core.story.Progress(), "locker 17"))
        // через терминал: верный ответ двигает сюжет
        val env = object : com.nullsignal.core.terminal.TerminalEnv {
            override val operator = "op"
            override val device = "test"
            override val ip: String? = null
            override val objective = engine.current(p).objective
            override val followers = 0
            override val apps = emptyList<String>()
            override fun now() = "now"
            override fun answer(text: String) = engine.check(p, text)
        }
        val t = com.nullsignal.core.terminal.Terminal(env)
        assertEquals(com.nullsignal.core.story.Trigger.ANSWER, t.execute("answer locker 17").trigger)
        assertEquals(null, t.execute("answer helix").trigger)
        val next = engine.on(p, com.nullsignal.core.story.Trigger.ANSWER)
        assertEquals("channel", engine.current(next).id)
        // финал — развилка: двигается выбором ответа, у последней главы нет задачи
        val reveal = story.first { it.id == "reveal" }
        assertEquals(com.nullsignal.core.story.Trigger.REPLIED, reveal.trigger)
        assertTrue(reveal.replies.size >= 2)
        assertEquals(null, story.last().trigger)
        assertTrue(engine.finished(com.nullsignal.core.story.Progress(chapter = story.lastIndex)))
    }
}
