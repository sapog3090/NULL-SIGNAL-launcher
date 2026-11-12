package com.nullsignal.core

import com.nullsignal.core.net.ChatCrypto
import com.nullsignal.core.net.ChatNode
import com.nullsignal.core.net.ChatNode.Via
import com.nullsignal.core.net.NtfyRelay
import com.nullsignal.core.net.Stun
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Мини-ntfy для тестов: POST кладёт пакет, GET /…/json отдаёт историю и затем живой поток. */
private class FakeNtfy : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val log = ArrayList<Pair<String, String>>()
    private val subs = CopyOnWriteArrayList<Pair<String, LinkedBlockingQueue<String>>>()
    @Volatile private var closed = false
    val published = AtomicInteger()
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.trim('/')
            if (ex.requestMethod == "POST") {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                published.incrementAndGet()
                synchronized(log) {
                    log += path to body
                    subs.filter { it.first == path }.forEach { it.second.offer(body) }
                }
                ex.sendResponseHeaders(200, 2)
                ex.responseBody.use { it.write("{}".toByteArray()) }
            } else if (path.endsWith("/json")) {
                val topic = path.removeSuffix("/json")
                val q = LinkedBlockingQueue<String>()
                ex.sendResponseHeaders(200, 0)
                val out = ex.responseBody
                fun line(s: String) { out.write((s + "\n").toByteArray()); out.flush() }
                val me = topic to q
                try {
                    line("""{"id":"o","time":1,"event":"open","topic":"$topic"}""")
                    synchronized(log) {
                        log.filter { it.first == topic }.forEach { line(msg(it.second)) }
                        subs += me
                    }
                    while (!closed) {
                        val m = q.poll(150, TimeUnit.MILLISECONDS) ?: continue
                        line(msg(m))
                    }
                } catch (e: IOException) {
                } finally {
                    subs -= me
                    runCatching { out.close() }
                }
            } else {
                ex.sendResponseHeaders(404, -1)
                ex.close()
            }
        }
        server.start()
    }

    private fun msg(body: String) = """{"id":"m","time":1,"event":"message","topic":"t","message":"$body"}"""

    override fun close() {
        closed = true
        server.stop(0)
    }
}

/** STUN-сервер на localhost: отвечает адресом, с которого пришёл запрос. */
private class FakeStun : AutoCloseable {
    private val sock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val hostPort: String get() = "127.0.0.1:${sock.localPort}"

    init {
        Thread {
            val buf = ByteArray(512)
            while (!sock.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (e: Exception) { break }
                if (!Stun.isStun(buf, p.length)) continue
                val r = Stun.response(Stun.transaction(buf), p.socketAddress as InetSocketAddress)
                runCatching { sock.send(DatagramPacket(r, r.size, p.socketAddress)) }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = sock.close()
}

class ChatTest {
    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }
    private fun addr(p: Int) = InetSocketAddress("127.0.0.1", p)

    private fun waitFor(ms: Long = 8000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(40)
        }
        return cond()
    }

    private class Probe {
        val msgs: MutableList<ChatNode.Message> = Collections.synchronizedList(ArrayList())
        @Volatile var peers: List<ChatNode.Peer> = emptyList()
        @Volatile var status = ChatNode.Status()
        val sent: MutableList<Long> = Collections.synchronizedList(ArrayList())
        fun got(text: String) = synchronized(msgs) { msgs.firstOrNull { !it.mine && it.text == text } }
    }

    private fun node(
        nick: String, probe: Probe, port: Int = freePort(), lan: List<InetSocketAddress> = emptyList(),
        relay: FakeNtfy? = null, stun: FakeStun? = null, room: String = "старый маяк на холме", selfId: Long = 0L,
    ) = ChatNode(
        room, nick, port, { lan }, { probe.msgs += it }, { probe.peers = it },
        relay = relay?.let { NtfyRelay(it.url) }, stunServers = listOfNotNull(stun?.hostPort),
        onStatus = { probe.status = it }, onSent = { probe.sent += it }, selfId = selfId,
    )

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

        // ящик на сервере и точка Wi-Fi Direct одинаковы у знающих слово и не выдают его
        val topic = ChatCrypto.topic(k)
        assertTrue(Regex("ns_[0-9a-f]{32}").matches(topic))
        assertEquals(topic, ChatCrypto.topic(ChatCrypto.key("красный клён")))
        assertNotEquals(topic, ChatCrypto.topic(ChatCrypto.key("красный клен")))
        val (ssid, pass) = ChatCrypto.directGroup(k)
        assertTrue(ssid, Regex("DIRECT-NS-[0-9A-F]{8}").matches(ssid))
        assertTrue(pass.length in 8..63 && pass.all { it.isLetterOrDigit() })
        assertFalse(topic.contains("клён"))
    }

    @Test fun helpers() {
        assertEquals("ok", NtfyRelay.jsonString("""{"event":"message","message":"ok"}""", "message"))
        assertEquals("message", NtfyRelay.jsonString("""{"id":"1", "event" : "message"}""", "event"))
        assertEquals("a/b\"cé", NtfyRelay.jsonString("""{"message":"a\/b\"cé"}""", "message"))
        assertNull(NtfyRelay.jsonString("""{"time":12}""", "message"))
        assertEquals("https://ntfy.sh", NtfyRelay.normalize(" "))
        assertEquals("https://my.relay.org", NtfyRelay.normalize("my.relay.org/"))
        assertEquals("http://10.0.0.2:8080", NtfyRelay.normalize("http://10.0.0.2:8080"))

        assertEquals(InetSocketAddress("8.8.4.4", 5000), ChatNode.parseEndpoint("8.8.4.4:5000"))
        assertNull(ChatNode.parseEndpoint("300.1.1.1:5"))
        assertNull(ChatNode.parseEndpoint("example.com:80"))
        assertTrue(ChatNode.isLocal(InetAddress.getByName("192.168.49.1")))
        assertTrue(ChatNode.isLocal(InetAddress.getByName("100.72.1.9"))) // NAT оператора
        assertFalse(ChatNode.isLocal(InetAddress.getByName("93.184.216.34")))
        assertTrue(ChatNode.localBroadcasts().any { it.address.hostAddress == "255.255.255.255" })
    }

    @Test fun stunPackets() {
        val tx = ByteArray(12) { (it * 7).toByte() }
        val req = Stun.request(tx)
        assertEquals(20, req.size)
        assertTrue(Stun.isStun(req))
        assertTrue(tx.contentEquals(Stun.transaction(req)))
        val mapped = InetSocketAddress("203.0.113.77", 61000)
        val resp = Stun.response(tx, mapped)
        assertEquals(mapped, Stun.parse(resp, resp.size, tx))
        assertNull(Stun.parse(resp, resp.size, ByteArray(12))) // чужой ответ
        assertNull(Stun.parse(req)) // запрос — не ответ
        assertFalse(Stun.isStun("NSC2-это-наш-пакет-чата".toByteArray()))
        // старый формат MAPPED-ADDRESS без XOR (так отвечают древние серверы)
        val plain = java.nio.ByteBuffer.allocate(32).putShort(0x0101).putShort(12).putInt(Stun.COOKIE).put(tx)
            .putShort(0x0001).putShort(8).put(0).put(1).putShort(4242).put(byteArrayOf(198.toByte(), 51, 100, 9)).array()
        assertEquals(InetSocketAddress("198.51.100.9", 4242), Stun.parse(plain, plain.size, tx))
    }

    @Test fun wifiRoom() {
        val pa = freePort(); val pb = freePort(); val pc = freePort()
        val atA = Probe(); val atB = Probe(); val atC = Probe()
        val a = node("ворон", atA, pa, listOf(addr(pb), addr(pc)))
        val b = node("сойка", atB, pb, listOf(addr(pa), addr(pc)))
        val c = node("helix", atC, pc, listOf(addr(pa), addr(pb)), room = "чужая комната")
        try {
            a.start(); b.start(); c.start()
            assertEquals(a.fingerprint, b.fingerprint)
            a.send("привет из эфира")
            b.send("слышу тебя")
            assertTrue(waitFor { atB.got("привет из эфира") != null && atA.got("слышу тебя") != null && atB.peers.isNotEmpty() })
            Thread.sleep(700) // повторные отправки не должны задвоить сообщение
            assertEquals(listOf("привет из эфира"), atB.msgs.filter { !it.mine }.map { it.text })
            assertEquals("ворон", atB.got("привет из эфира")!!.from)
            assertEquals(Via.LAN, atB.got("привет из эфира")!!.via)
            assertEquals(1, atA.msgs.count { it.mine })
            assertTrue(atC.msgs.isEmpty())
            assertEquals(listOf(ChatNode.Peer("ворон", Via.LAN)), atB.peers)
            assertEquals(ChatNode.RelayState.OFF, atA.status.relay)
        } finally {
            a.stop(); b.stop(); c.stop()
        }
    }

    @Test fun relayKeepsHistoryForLateJoiner() {
        FakeNtfy().use { relay ->
            val atA = Probe(); val atB = Probe(); val atX = Probe()
            val a = node("ворон", atA, relay = relay)
            val x = node("helix", atX, relay = relay, room = "подбираю слово")
            var b: ChatNode? = null
            try {
                a.start(); x.start()
                assertTrue(waitFor { atA.status.relay == ChatNode.RelayState.ONLINE })
                a.send("кто-нибудь здесь?")
                assertTrue("сервер должен принять сообщение", waitFor { atA.sent.isNotEmpty() })
                b = node("сойка", atB, relay = relay).also { it.start() }
                assertTrue("опоздавший получает историю", waitFor { atB.got("кто-нибудь здесь?") != null })
                assertEquals(Via.RELAY, atB.got("кто-нибудь здесь?")!!.via)
                b.send("я тут, был офлайн")
                assertTrue(waitFor { atA.got("я тут, был офлайн") != null })
                assertTrue(waitFor { atA.peers.contains(ChatNode.Peer("сойка", Via.RELAY)) })
                assertTrue("чужая комната ничего не видит", atX.msgs.isEmpty() && atX.peers.isEmpty())
            } finally {
                a.stop(); x.stop(); b?.stop()
            }
            // перезапуск с тем же номером узла: свои сообщения из истории остаются своими
            val atA2 = Probe()
            val a2 = node("ворон", atA2, relay = relay, selfId = a.me)
            try {
                a2.start()
                assertTrue(waitFor { atA2.msgs.any { it.mine && it.text == "кто-нибудь здесь?" } })
                assertTrue(waitFor { atA2.got("я тут, был офлайн") != null })
            } finally {
                a2.stop()
            }
        }
    }

    @Test fun bridgeJoinsWifiWithInternet() {
        FakeNtfy().use { relay ->
            val pa = freePort(); val pb = freePort()
            val atA = Probe(); val atB = Probe(); val atC = Probe()
            val a = node("подвал", atA, pa, listOf(addr(pb)))                 // только Wi-Fi, интернета нет
            val b = node("мост", atB, pb, listOf(addr(pa)), relay = relay)    // Wi-Fi + мобильный интернет
            val c = node("город", atC, relay = relay)                          // на другом конце города
            try {
                a.start(); b.start(); c.start()
                assertTrue(waitFor {
                    atB.peers.contains(ChatNode.Peer("подвал", Via.LAN)) &&
                        atB.status.relay == ChatNode.RelayState.ONLINE && atC.status.relay == ChatNode.RelayState.ONLINE
                })
                assertTrue(waitFor { atB.status.bridge })
                a.send("из подвала, без интернета")
                assertTrue(waitFor { atC.got("из подвала, без интернета") != null })
                assertEquals("подвал", atC.got("из подвала, без интернета")!!.from)
                c.send("принято, город на связи")
                assertTrue(waitFor { atA.got("принято, город на связи") != null })
                assertEquals(Via.LAN, atA.got("принято, город на связи")!!.via)
                Thread.sleep(600)
                assertEquals(1, atC.msgs.count { it.text == "из подвала, без интернета" })
                assertEquals(1, atA.msgs.count { it.text == "принято, город на связи" })
            } finally {
                a.stop(); b.stop(); c.stop()
            }
        }
    }

    @Test fun p2pSurvivesRelayDown() {
        val stun = FakeStun()
        val relay = FakeNtfy()
        val atA = Probe(); val atB = Probe()
        val a = node("ворон", atA, relay = relay, stun = stun)
        val b = node("сойка", atB, relay = relay, stun = stun)
        try {
            a.start(); b.start()
            assertTrue("оба узнали свой внешний адрес", waitFor { atA.status.publicAddr != null && atB.status.publicAddr != null })
            assertTrue(
                "прямой канал пробит: ${atA.peers} / ${atB.peers}",
                waitFor(10_000) { atA.peers.contains(ChatNode.Peer("сойка", Via.P2P)) && atB.peers.contains(ChatNode.Peer("ворон", Via.P2P)) },
            )
            relay.close() // сервер упал или его заблокировали
            a.send("сервер лёг, а мы нет")
            assertTrue(waitFor { atB.got("сервер лёг, а мы нет") != null })
            assertEquals(Via.P2P, atB.got("сервер лёг, а мы нет")!!.via)
        } finally {
            a.stop(); b.stop(); stun.close(); relay.close()
        }
    }
}
