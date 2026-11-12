package com.nullsignal.core

import com.nullsignal.core.hw.Spectrum
import com.nullsignal.core.net.ChatCrypto
import com.nullsignal.core.net.ChatNode
import com.nullsignal.core.net.DropServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin

class SpectrumTest {
    private fun tone(hz: Double, amp: Double, n: Int, noise: Double = 0.0, seed: Long = 1): ShortArray {
        val r = java.util.Random(seed)
        return ShortArray(n) { ((sin(2 * PI * hz * it / 44100) * amp + r.nextGaussian() * noise) * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    @Test fun findsTones() {
        val a = Spectrum.Analyzer(4096, 44100)
        var db = a.analyze(tone(1000.0, 0.5, 4096))
        var p = Spectrum.peaks(db, 44100)
        assertEquals(1000.0, p[0].hz, 6.0)
        assertEquals(-6.0, p[0].db.toDouble(), 1.5)
        val mix = tone(440.0, 0.3, 4096).zip(tone(5000.0, 0.1, 4096)) { x, y -> (x + y).toShort() }.toShortArray()
        db = a.analyze(mix)
        p = Spectrum.peaks(db, 44100, count = 2)
        assertEquals(440.0, p[0].hz, 6.0)
        assertEquals(5000.0, p[1].hz, 6.0)
        assertEquals("Ля 4", Spectrum.note(440.0))
        assertEquals("До 4", Spectrum.note(261.63))
        assertNull(Spectrum.note(19000.0))
        assertEquals(1000.0, a.hz(a.bin(1000.0)), 11.0)
    }

    @Test fun ultrasonicBeacon() {
        val a = Spectrum.Analyzer(4096, 44100)
        fun run(pcm: ShortArray): List<Spectrum.BeaconWatch.Beacon> {
            val w = Spectrum.BeaconWatch(44100)
            var last = emptyList<Spectrum.BeaconWatch.Beacon>()
            var off = 0
            while (off + 4096 <= pcm.size) { last = w.feed(a.analyze(pcm, off), off / 44100.0); off += 2048 }
            return last
        }
        // тихий тон 18.5 кГц поверх шума, 3 секунды
        val beacon = run(tone(18500.0, 0.02, 44100 * 3, noise = 0.003))
        assertEquals(1, beacon.size)
        assertEquals(18500.0, beacon[0].hz, 30.0)
        assertTrue(beacon[0].seconds >= 1.5)
        // только шум, громкая музыка в слышимой области, короткий писк — не маяк
        assertTrue(run(tone(0.0, 0.0, 44100 * 3, noise = 0.01)).isEmpty())
        assertTrue(run(tone(3000.0, 0.6, 44100 * 3, noise = 0.003)).isEmpty())
        val blip = tone(18500.0, 0.02, 44100 / 3, noise = 0.003) + tone(0.0, 0.0, 44100 * 2, noise = 0.003, seed = 2)
        assertTrue(run(blip).isEmpty())
    }
}

class DropServerTest {
    private fun req(url: String, method: String = "GET", cookie: String? = null, body: ByteArray? = null, type: String? = null): Triple<Int, Map<String, List<String>>, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        cookie?.let { c.setRequestProperty("Cookie", it) }
        if (body != null) {
            c.doOutput = true
            type?.let { c.setRequestProperty("Content-Type", it) }
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val data = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Triple(code, c.headerFields.filterKeys { it != null }, data)
    }

    @Test fun shareAndReceive() {
        val content = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
        val got = Collections.synchronizedMap(HashMap<String, ByteArrayOutputStream>())
        val log = Collections.synchronizedList(ArrayList<String>())
        val server = DropServer(
            "123456",
            { listOf(DropServer.Shared("отчёт 1.bin", content.size.toLong()) { ByteArrayInputStream(content) }) },
            { name -> ByteArrayOutputStream().also { got[name] = it } },
            { log += it },
        )
        val base = "http://127.0.0.1:${server.start(0)}"
        try {
            assertTrue(String(req("$base/").third).contains("PIN"))
            assertEquals(303, req("$base/get/0").first)
            assertEquals(403, req("$base/login", "POST", body = "pin=000000".toByteArray(), type = "application/x-www-form-urlencoded").first)
            val login = req("$base/login", "POST", body = "pin=123456".toByteArray(), type = "application/x-www-form-urlencoded")
            assertEquals(303, login.first)
            val cookie = login.second.entries.first { it.key.equals("Set-Cookie", true) }.value.first().substringBefore(';')
            assertTrue(cookie.startsWith("ns="))
            assertTrue(String(req("$base/", cookie = cookie).third).contains("отчёт 1.bin"))
            val dl = req("$base/get/0", cookie = cookie)
            assertEquals(200, dl.first)
            assertArrayEquals(content, dl.third)
            assertEquals(404, req("$base/get/7", cookie = cookie).first)

            // загрузка двух файлов; во втором внутри лежит кусок, похожий на разделитель
            val boundary = "----ns7MA4YWxk"
            val tricky = "начало\r\n--${boundary.dropLast(1)}-почти\r\n--\r\n----ns7MA4YWx конец".toByteArray() + ByteArray(5000) { (it % 251).toByte() }
            val body = ByteArrayOutputStream()
            fun part(name: String, data: ByteArray) {
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"f\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                body.write(data)
                body.write("\r\n".toByteArray())
            }
            part("../../etc/заметка.txt", "привет".toByteArray())
            part("data.bin", tricky)
            body.write("--$boundary--\r\n".toByteArray())
            val up = req("$base/put", "POST", cookie, body.toByteArray(), "multipart/form-data; boundary=$boundary")
            assertEquals(303, up.first)
            assertEquals("/?ok=2", up.second.entries.first { it.key.equals("Location", true) }.value.first())
            assertEquals("привет", got["заметка.txt"]!!.toString("UTF-8"))
            assertArrayEquals(tricky, got["data.bin"]!!.toByteArray())
            assertEquals(303, req("$base/put", "POST", null, body.toByteArray(), "multipart/form-data; boundary=$boundary").first)
            // оборванная загрузка (нет завершающего разделителя) не подвешивает сервер
            val cut = body.toByteArray().copyOf(body.size() - 40)
            assertEquals("/?ok=1", req("$base/put", "POST", cookie, cut, "multipart/form-data; boundary=$boundary").second.entries.first { it.key.equals("Location", true) }.value.first())
            assertTrue(log.any { it.startsWith("получен: data.bin") } && log.any { it.startsWith("скачан") })

            // пять неверных PIN подряд — вход закрывается
            repeat(5) { req("$base/login", "POST", body = "pin=1".toByteArray(), type = "application/x-www-form-urlencoded") }
            assertEquals(429, req("$base/login", "POST", body = "pin=123456".toByteArray(), type = "application/x-www-form-urlencoded").first)
        } finally {
            server.stop()
        }
    }

    @Test fun helpers() {
        val s = DropServer("1", { emptyList() }, { null })
        fun cut(data: String, d: String): Pair<Boolean, String> {
            val out = ByteArrayOutputStream()
            val ok = s.copyUntil(ByteArrayInputStream(data.toByteArray()), d.toByteArray(), out)
            return ok to out.toString()
        }
        assertEquals(true to "a", cut("aabababXrest", "abab"))
        assertEquals(true to "aab--", cut("aab--abab", "abab"))
        assertEquals(true to "", cut("ababrest", "abab"))
        assertEquals(false to "abaXaba", cut("abaXaba", "abab"))
        assertEquals(true to "xaaa", cut("xaaaaab", "aab"))
        assertEquals("заметка.txt", DropServer.safeName("C:\\Users\\..\\заметка.txt"))
        assertEquals("a b.txt", DropServer.safeName("/tmp/a<>|b.txt".replace("<>|", " ")))
        assertTrue(Regex("\\d{6}").matches(DropServer.newPin()))
    }
}

class LanChatTest {
    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    @Test fun crypto() {
        val k = ChatCrypto.key("красный клён")
        val p = ChatCrypto.seal(k, "привет".toByteArray())
        assertEquals("привет", String(ChatCrypto.open(k, p)!!))
        assertNull(ChatCrypto.open(ChatCrypto.key("другое слово"), p))
        assertNull(ChatCrypto.open(k, p.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertNull(ChatCrypto.open(k, "просто мусор из сети".toByteArray()))
        assertTrue(Regex("[0-9A-F]{4}-[0-9A-F]{4}").matches(ChatCrypto.fingerprint(k)))
        assertEquals(ChatCrypto.fingerprint(k), ChatCrypto.fingerprint(ChatCrypto.key("  красный клён ")))
        assertEquals("192.168.1.255", ChatNode.broadcast("192.168.1.37", 24))
        assertEquals("10.0.15.255", ChatNode.broadcast("10.0.5.9", 20))
        assertNull(ChatNode.broadcast("нет", 24))
    }

    @Test fun twoPhonesOneRoom() {
        val pa = freePort(); val pb = freePort(); val pc = freePort()
        fun addr(p: Int) = InetSocketAddress("127.0.0.1", p)
        val atA = Collections.synchronizedList(ArrayList<ChatNode.Message>())
        val atB = Collections.synchronizedList(ArrayList<ChatNode.Message>())
        val atC = Collections.synchronizedList(ArrayList<ChatNode.Message>())
        val peersB = Collections.synchronizedList(ArrayList<String>())
        val a = ChatNode("комната-1", "ворон", pa, { listOf(addr(pb), addr(pc)) }, { atA += it })
        val b = ChatNode("комната-1", "сойка", pb, { listOf(addr(pa), addr(pc)) }, { atB += it }, { peersB.clear(); peersB.addAll(it) })
        val c = ChatNode("чужая", "helix", pc, { listOf(addr(pa), addr(pb)) }, { atC += it })
        try {
            a.start(); b.start(); c.start()
            assertEquals(a.fingerprint, b.fingerprint)
            a.send("привет из эфира")
            b.send("слышу тебя")
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && (atB.none { !it.mine } || atA.none { !it.mine } || peersB.isEmpty())) Thread.sleep(50)
            Thread.sleep(700) // ждём повторные отправки: они не должны задвоить сообщение
            assertEquals(listOf("привет из эфира"), atB.filter { !it.mine }.map { it.text })
            assertEquals("ворон", atB.first { !it.mine }.from)
            assertEquals(listOf("слышу тебя"), atA.filter { !it.mine }.map { it.text })
            assertEquals(1, atA.count { it.mine })
            assertTrue(atC.isEmpty())
            assertEquals(listOf("ворон"), peersB.toList())
        } finally {
            a.stop(); b.stop(); c.stop()
        }
        assertNotNull(a.fingerprint)
    }
}
