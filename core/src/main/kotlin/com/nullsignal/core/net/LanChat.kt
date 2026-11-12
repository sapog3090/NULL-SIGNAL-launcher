package com.nullsignal.core.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрочат. «Комната» — это кодовое слово: из него получается ключ AES-256, поэтому сообщения
 * читают только те, кто знает слово. Рядом пакеты идут по Wi-Fi без интернета, на расстоянии —
 * напрямую P2P (если NAT пускает) или через ретранслятор, который видит только шифр.
 */
object ChatCrypto {
    private val MAGIC = "NSC2".toByteArray()
    private val SALT = "nullsignal-lan-chat-v1".toByteArray()

    fun key(room: String): SecretKeySpec {
        val spec = PBEKeySpec(room.trim().toCharArray(), SALT, 30_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    fun seal(key: SecretKeySpec, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(MAGIC)
        return MAGIC + nonce + c.doFinal(plain)
    }

    /** null — пакет не наш или зашифрован другим кодовым словом. */
    fun open(key: SecretKeySpec, packet: ByteArray, length: Int = packet.size): ByteArray? {
        if (length < MAGIC.size + 12 + 16) return null
        for (i in MAGIC.indices) if (packet[i] != MAGIC[i]) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packet, MAGIC.size, 12))
            c.updateAAD(MAGIC)
            c.doFinal(packet, MAGIC.size + 12, length - MAGIC.size - 12)
        }.getOrNull()
    }

    private fun digest(label: String, key: SecretKeySpec): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray() + key.encoded)

    /** Короткий отпечаток комнаты: если у двоих он совпал — кодовое слово одинаковое. */
    fun fingerprint(key: SecretKeySpec): String {
        val h = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "%02X%02X-%02X%02X".format(h[0], h[1], h[2], h[3])
    }

    /** Имя «ящика» на ретрансляторе. Из него нельзя быстро получить кодовое слово: между ними PBKDF2. */
    fun topic(key: SecretKeySpec): String =
        "ns_" + digest("nullsignal-relay-v1|", key).take(16).joinToString("") { "%02x".format(it) }

    /**
     * Имя и пароль точки Wi-Fi Direct. Одинаковые у всех, кто знает кодовое слово, поэтому
     * один телефон поднимает точку, а остальные подключаются к ней сами, без роутера.
     */
    fun directGroup(key: SecretKeySpec): Pair<String, String> {
        val h = digest("nullsignal-p2p-v1|", key)
        val name = "DIRECT-NS-" + h.take(4).joinToString("") { "%02X".format(it) }
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val pass = (4 until 20).map { alphabet[(h[it].toInt() and 255) % alphabet.length] }.joinToString("")
        return name to pass
    }
}

/**
 * STUN (RFC 5389): спрашиваем у публичного сервера, каким адресом и портом нас видит интернет.
 * Этот адрес собеседник использует, чтобы «пробить» NAT и слать пакеты напрямую.
 */
object Stun {
    const val COOKIE = 0x2112A442

    fun request(tx: ByteArray): ByteArray {
        require(tx.size == 12)
        return ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(COOKIE).put(tx).array()
    }

    fun isStun(p: ByteArray, len: Int = p.size): Boolean =
        len >= 20 && (p[0].toInt() and 0xC0) == 0 && ByteBuffer.wrap(p, 4, 4).int == COOKIE

    fun transaction(p: ByteArray): ByteArray = p.copyOfRange(8, 20)

    /** Ответ сервера для тестов и самопроверки: XOR-MAPPED-ADDRESS с адресом спросившего. */
    fun response(tx: ByteArray, mapped: InetSocketAddress): ByteArray {
        val ip = mapped.address.address
        require(ip.size == 4)
        val b = ByteBuffer.allocate(32).putShort(0x0101).putShort(12).putInt(COOKIE).put(tx)
        b.putShort(0x0020).putShort(8).put(0).put(1).putShort((mapped.port xor (COOKIE ushr 16)).toShort())
        for (i in 0..3) b.put((ip[i].toInt() xor (COOKIE ushr (24 - 8 * i))).toByte())
        return b.array()
    }

    /** Разбирает успешный ответ. tx — номер нашего запроса: чужие ответы отбрасываются. */
    fun parse(p: ByteArray, len: Int = p.size, tx: ByteArray? = null): InetSocketAddress? {
        if (!isStun(p, len)) return null
        val bb = ByteBuffer.wrap(p, 0, len)
        if (bb.short.toInt() and 0xFFFF != 0x0101) return null
        val end = minOf(len, 20 + (bb.short.toInt() and 0xFFFF))
        bb.int
        val t = ByteArray(12).also { bb.get(it) }
        if (tx != null && !t.contentEquals(tx)) return null
        var plain: InetSocketAddress? = null
        while (bb.position() + 4 <= end) {
            val type = bb.short.toInt() and 0xFFFF
            val size = bb.short.toInt() and 0xFFFF
            val at = bb.position()
            if (at + size > end) break
            if ((type == 0x0020 || type == 0x8020 || type == 0x0001) && size >= 8 && p[at + 1].toInt() == 1) {
                var port = ((p[at + 2].toInt() and 255) shl 8) or (p[at + 3].toInt() and 255)
                val ip = ByteArray(4) { p[at + 4 + it] }
                if (type == 0x0001) {
                    plain = InetSocketAddress(InetAddress.getByAddress(ip), port)
                } else {
                    port = port xor (COOKIE ushr 16)
                    for (i in 0..3) ip[i] = (ip[i].toInt() xor (COOKIE ushr (24 - 8 * i))).toByte()
                    return InetSocketAddress(InetAddress.getByAddress(ip), port)
                }
            }
            bb.position(at + ((size + 3) and 3.inv()))
        }
        return plain
    }
}

class RelayException(val code: Int, message: String) : IOException(message)

/** Ретранслятор: хранит и раздаёт зашифрованные пакеты. Видит только шифр, IP и время. */
interface ChatRelay {
    val name: String
    fun publish(topic: String, packet: ByteArray)

    /** Держит подписку, пока сервер не закроет поток. since — «12h» или unix-время в секундах. */
    fun listen(topic: String, since: String, onOpen: () -> Unit, onPacket: (ByteArray) -> Unit)

    /** Оборвать подписку (из другого потока). */
    fun close()
}

/**
 * Ретранслятор на протоколе ntfy: открытый сервер ntfy.sh или свой (ntfy ставится одной командой).
 * Накопленные за 12 часов пакеты сервер отдаёт при входе — сообщение дойдёт, даже если собеседник был офлайн.
 */
class NtfyRelay(base: String) : ChatRelay {
    val base: String = normalize(base)
    override val name: String get() = base.substringAfter("://")
    @Volatile private var conn: HttpURLConnection? = null

    override fun publish(topic: String, packet: ByteArray) {
        val c = URL("$base/$topic").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "text/plain")
            c.setRequestProperty("X-Firebase", "no") // не пересылать пакеты в Google
            c.setRequestProperty("User-Agent", "nullsignal-chat")
            val body = Base64.getEncoder().encodeToString(packet).toByteArray()
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) throw RelayException(code, "сервер ответил $code")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    override fun listen(topic: String, since: String, onOpen: () -> Unit, onPacket: (ByteArray) -> Unit) {
        val c = URL("$base/$topic/json?since=" + URLEncoder.encode(since, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 80_000 // сервер шлёт keepalive раз в 45 секунд
        c.setRequestProperty("User-Agent", "nullsignal-chat")
        conn = c
        try {
            val code = c.responseCode
            if (code !in 200..299) throw RelayException(code, "сервер ответил $code")
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    when (jsonString(line, "event")) {
                        "open" -> onOpen()
                        "message" -> jsonString(line, "message")
                            ?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
                            ?.let(onPacket)
                    }
                }
            }
        } finally {
            conn = null
            c.disconnect()
        }
    }

    override fun close() {
        runCatching { conn?.disconnect() }
    }

    companion object {
        const val DEFAULT = "https://ntfy.sh"

        fun normalize(s: String): String {
            var t = s.trim().trimEnd('/')
            if (t.isEmpty()) t = DEFAULT
            if (!t.contains("://")) t = "https://$t"
            require(t.startsWith("http://") || t.startsWith("https://")) { "адрес сервера должен начинаться с https://" }
            return t
        }

        /** Значение строкового поля из строки JSON (без библиотек; ntfy присылает плоские объекты). */
        fun jsonString(json: String, field: String): String? {
            var i = json.indexOf("\"$field\"")
            while (i >= 0) {
                var j = i + field.length + 2
                while (j < json.length && json[j] == ' ') j++
                if (j < json.length && json[j] == ':') {
                    j++
                    while (j < json.length && json[j] == ' ') j++
                    if (j >= json.length || json[j] != '"') return null
                    val sb = StringBuilder()
                    j++
                    while (j < json.length) {
                        val ch = json[j]
                        when {
                            ch == '"' -> return sb.toString()
                            ch == '\\' && j + 1 < json.length -> {
                                val e = json[j + 1]
                                j++
                                when (e) {
                                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                                    'u' -> if (j + 4 < json.length) {
                                        sb.append(json.substring(j + 1, j + 5).toInt(16).toChar()); j += 4
                                    }
                                    else -> sb.append(e)
                                }
                            }
                            else -> sb.append(ch)
                        }
                        j++
                    }
                    return null
                }
                i = json.indexOf("\"$field\"", i + 1)
            }
            return null
        }
    }
}

class ChatNode(
    room: String,
    val nick: String,
    private val port: Int = DEFAULT_PORT,
    /** Куда слать пакеты по Wi-Fi: широковещательные адреса сетей (или конкретные адреса — в тестах). */
    private val targets: () -> List<InetSocketAddress>,
    private val onMessage: (Message) -> Unit,
    private val onPeers: (List<Peer>) -> Unit = {},
    /** Дальняя связь: ретранслятор. null — только Wi-Fi рядом. */
    private val relay: ChatRelay? = null,
    /** STUN-серверы «хост:порт» для прямого канала P2P через NAT. */
    private val stunServers: List<String> = emptyList(),
    private val onStatus: (Status) -> Unit = {},
    /** Сообщение с этим id принял ретранслятор (галочка «доставлено на сервер»). */
    private val onSent: (Long) -> Unit = {},
    /** Постоянный номер узла: свои сообщения из истории сервера тогда показываются как свои. */
    selfId: Long = 0L,
    private val historyHours: Int = 12,
) {
    enum class Via(val label: String) { SELF("ты"), LAN("Wi-Fi"), P2P("P2P"), RELAY("сервер") }
    enum class RelayState { OFF, CONNECTING, ONLINE, ERROR }

    class Message(val id: Long, val from: String, val text: String, val time: Long, val mine: Boolean, val via: Via)
    data class Peer(val nick: String, val via: Via)
    data class Status(
        val relay: RelayState = RelayState.OFF,
        val relayName: String? = null,
        /** Внешний адрес по STUN. */
        val publicAddr: String? = null,
        /** Что известно про NAT: пробьётся ли прямой канал. */
        val nat: String? = null,
        /** Этот телефон сейчас передаёт сообщения между Wi-Fi и интернетом. */
        val bridge: Boolean = false,
        val error: String? = null,
    )

    private class PeerInfo(var nick: String) {
        var relayOnline = false
        var lan = 0L
        var p2p = 0L
        var net = 0L

        fun via(now: Long): Via? = when {
            now - p2p < P2P_TTL -> Via.P2P
            now - lan < LAN_TTL -> Via.LAN
            now - net < NET_TTL -> Via.RELAY
            else -> null
        }
    }

    private class Direct(var addr: InetSocketAddress, var seen: Long)

    private val key = ChatCrypto.key(room)
    val fingerprint: String = ChatCrypto.fingerprint(key)
    val topic: String = ChatCrypto.topic(key)
    private val random = SecureRandom()
    val me: Long = if (selfId != 0L) selfId else random.nextLong()
    private val lock = Any()
    private val seen = LinkedHashSet<Long>()
    private val peers = HashMap<Long, PeerInfo>()
    private val directs = HashMap<Long, Direct>()
    private val stunAnswers = HashMap<String, InetSocketAddress>()
    private var cachedTargets: List<InetSocketAddress> = emptyList()
    private var cachedAt = 0L
    private var socket: DatagramSocket? = null
    private var outbox: ExecutorService? = null

    @Volatile private var running = false
    @Volatile private var status = Status(if (relay != null) RelayState.CONNECTING else RelayState.OFF, relay?.name)
    @Volatile private var publicAddr: InetSocketAddress? = null
    @Volatile private var lastRelayHello = 0L

    val currentStatus: Status get() = status

    fun start() {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.bind(InetSocketAddress(port))
        socket = s
        running = true
        daemon("chat-rx") { receiveLoop(s) }
        daemon("chat-hello") {
            while (running) {
                runCatching { sendUdp(pack('H', random.nextLong(), ""), udpTargets()) }
                prunePeers()
                if (status.relay == RelayState.ONLINE && System.currentTimeMillis() - lastRelayHello > RELAY_HELLO_MS) relayHello()
                if (!pause(4000)) break
            }
        }
        if (relay != null) {
            outbox = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-relay-tx").apply { isDaemon = true } }
            daemon("chat-relay") { relayLoop(relay) }
            if (stunServers.isNotEmpty()) daemon("chat-stun") {
                while (running) {
                    runCatching { discover() }
                    if (!pause(STUN_EVERY_MS)) break
                }
            }
        }
        emitStatus()
    }

    fun stop() {
        if (!running) return
        running = false
        val bye = runCatching { pack('B', random.nextLong(), "") }.getOrNull()
        if (bye != null) runCatching { sendUdp(bye, udpTargets()) }
        relay?.let { r ->
            runCatching { r.close() }
            if (bye != null && status.relay == RelayState.ONLINE) runCatching { r.publish(topic, bye) }
        }
        outbox?.shutdownNow()
        runCatching { socket?.close() }
        socket = null
    }

    /** Отправить сообщение всем в комнате: по Wi-Fi (трижды — UDP теряет пакеты), напрямую P2P и через сервер. */
    fun send(text: String) {
        val t = text.trim().take(MAX_TEXT)
        if (t.isEmpty() || !running) return
        val id = random.nextLong()
        synchronized(lock) { remember(id) }
        onMessage(Message(id, nick, t, System.currentTimeMillis(), true, Via.SELF))
        val packet = pack('M', id, t)
        daemon("chat-tx") {
            for (pause in longArrayOf(0, 150, 400)) {
                if (!pause(pause)) return@daemon
                runCatching { sendUdp(packet, udpTargets()) }
            }
        }
        publish(packet, id)
    }

    // ---------- отправка ----------

    private fun pack(type: Char, id: Long, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(type.code)
            d.writeLong(me)
            d.writeLong(id)
            d.writeLong(System.currentTimeMillis())
            d.writeByte(if (status.relay == RelayState.ONLINE) 1 else 0)
            d.writeUTF(nick.take(24))
            d.writeUTF(text)
        }
        return ChatCrypto.seal(key, out.toByteArray(), random)
    }

    private fun lanTargets(): List<InetSocketAddress> = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now - cachedAt > 3000 || cachedAt == 0L) {
            cachedTargets = runCatching(targets).getOrDefault(emptyList())
            cachedAt = now
        }
        cachedTargets
    }

    private fun liveDirects(): List<InetSocketAddress> = synchronized(lock) {
        val now = System.currentTimeMillis()
        directs.values.filter { now - it.seen < NET_TTL }.map { it.addr }
    }

    private fun udpTargets() = (lanTargets() + liveDirects()).distinct()

    private fun sendUdp(packet: ByteArray, to: List<InetSocketAddress>) {
        val s = socket ?: return
        for (t in to) runCatching { s.send(DatagramPacket(packet, packet.size, t)) }
    }

    private fun publish(packet: ByteArray, msgId: Long?) {
        val r = relay ?: return
        val ex = outbox ?: return
        runCatching {
            ex.execute {
                for (wait in longArrayOf(0, 2000, 6000)) {
                    if (!pause(wait)) return@execute
                    try {
                        r.publish(topic, packet)
                        if (msgId != null) onSent(msgId)
                        return@execute
                    } catch (e: Exception) {
                        if (e is InterruptedException) return@execute
                        update { it.copy(error = describe(e)) }
                    }
                }
            }
        }
    }

    private fun relayHello() {
        lastRelayHello = System.currentTimeMillis()
        val ep = publicAddr?.let { "ep=" + it.address.hostAddress + ":" + it.port } ?: ""
        publish(pack('H', random.nextLong(), ep), null)
    }

    private fun punch(to: InetSocketAddress) = daemon("chat-punch") {
        for (wait in longArrayOf(0, 300, 900, 2000)) {
            if (!pause(wait)) return@daemon
            runCatching { sendUdp(pack('H', random.nextLong(), ""), listOf(to)) }
        }
    }

    // ---------- приём ----------

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: Exception) { if (!running) break else continue }
            if (Stun.isStun(buf, p.length)) {
                onStun(buf, p.length)
                continue
            }
            runCatching { handle(buf, p.length, p.socketAddress as InetSocketAddress) }
        }
    }

    private fun relayLoop(r: ChatRelay) {
        var since = "${historyHours}h"
        var backoff = 2000L
        while (running) {
            update { it.copy(relay = RelayState.CONNECTING) }
            try {
                r.listen(topic, since, onOpen = {
                    backoff = 2000L
                    update { it.copy(relay = RelayState.ONLINE, error = null) }
                    relayHello()
                }) { pkt -> runCatching { handle(pkt, pkt.size, null) } }
                if (running) update { it.copy(relay = RelayState.CONNECTING) }
            } catch (e: Exception) {
                if (!running) break
                update { it.copy(relay = RelayState.ERROR, error = describe(e)) }
            }
            if (!running) break
            since = (System.currentTimeMillis() / 1000 - 90).toString() // догнать то, что пришло, пока связи не было
            if (!pause(backoff)) break
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /** src == null — пакет пришёл через ретранслятор. */
    private fun handle(buf: ByteArray, len: Int, src: InetSocketAddress?) {
        val plain = ChatCrypto.open(key, buf, len) ?: return
        val d = DataInputStream(plain.inputStream())
        val type = d.readByte().toInt().toChar()
        val sender = d.readLong()
        val id = d.readLong()
        val time = d.readLong()
        val flags = d.readByte().toInt()
        val from = d.readUTF()
        val text = d.readUTF()
        val now = System.currentTimeMillis()
        val age = now - time
        if (age < -10 * 60_000L) return // из «будущего» — часы врут или пакет подделан
        if (sender == me) {
            // свои сообщения из истории сервера после перезапуска
            if (type == 'M' && src == null && age < MAX_AGE && synchronized(lock) { remember(id) }) {
                onMessage(Message(id, from, text, time, true, Via.SELF))
            }
            return
        }
        val via = when {
            src == null -> Via.RELAY
            synchronized(lock) { directs[sender]?.addr?.address == src.address } || !isLocal(src.address) -> Via.P2P
            else -> Via.LAN
        }
        when (type) {
            'M' -> {
                if (age > MAX_AGE) return
                if (!synchronized(lock) { remember(id) }) return
                onMessage(Message(id, from, text, time, false, via))
                forward(buf.copyOf(len), via, flags and 1 != 0)
            }
            'H' -> if (age < PRESENCE_AGE) hello(sender, from, flags and 1 != 0, via, src, text)
            'B' -> if (age < PRESENCE_AGE) {
                synchronized(lock) { peers.remove(sender); directs.remove(sender) }
                prunePeers()
            }
        }
    }

    private fun hello(sender: Long, from: String, relayOnline: Boolean, via: Via, src: InetSocketAddress?, text: String) {
        val now = System.currentTimeMillis()
        var changed: Boolean
        var reply = false
        var punchTo: InetSocketAddress? = null
        synchronized(lock) {
            val known = peers[sender]
            val p = known ?: PeerInfo(from).also { peers[sender] = it }
            val before = p.via(now)
            p.nick = from
            p.relayOnline = relayOnline
            when (via) {
                Via.LAN -> p.lan = now
                Via.P2P -> p.p2p = now
                Via.RELAY -> p.net = now
                Via.SELF -> {}
            }
            changed = known == null || before != p.via(now)
            if (via == Via.P2P && src != null) {
                val dd = directs[sender]
                if (dd == null) directs[sender] = Direct(src, now) else { dd.addr = src; dd.seen = now }
            }
            if (via == Via.RELAY) {
                val ep = if (text.startsWith("ep=")) parseEndpoint(text.substring(3)) else null
                if (ep != null) {
                    val dd = directs[sender]
                    if (dd == null || (dd.addr != ep && now - p.p2p > P2P_TTL)) {
                        directs[sender] = Direct(ep, now)
                        punchTo = ep
                    } else dd.seen = now
                }
                // новенький мог не застать наше приветствие — повторим, чтобы он нас увидел и пробил NAT к нам
                if ((before == null || punchTo != null) && now - lastRelayHello > 20_000) reply = true
            }
        }
        if (changed) prunePeers()
        punchTo?.let { punch(it) }
        if (reply) relayHello()
    }

    /**
     * Мост: телефон, у которого есть и Wi-Fi-соседи, и сервер, пересылает сообщения между ними.
     * Из нескольких таких телефонов мостом работает один — с наименьшим номером.
     */
    private fun forward(raw: ByteArray, via: Via, senderOnRelay: Boolean) {
        val now = System.currentTimeMillis()
        when (via) {
            Via.LAN -> {
                // телефон в двух сетях сразу (Wi-Fi + своя точка) связывает их между собой
                val nets = lanTargets().mapNotNull { it.address?.hostAddress }.filter { it != "255.255.255.255" }.distinct()
                if (nets.size > 1) sendUdp(raw, lanTargets())
                if (!senderOnRelay && isBridge(now)) publish(raw, null)
            }
            Via.RELAY, Via.P2P -> if (isBridge(now) && lanHasOffline(now)) daemon("chat-fwd") {
                for (wait in longArrayOf(0, 200)) {
                    if (!pause(wait)) return@daemon
                    sendUdp(raw, lanTargets())
                }
            }
            Via.SELF -> {}
        }
    }

    private fun isBridge(now: Long): Boolean = relay != null && status.relay == RelayState.ONLINE &&
        synchronized(lock) { peers.none { (id, p) -> p.relayOnline && now - p.lan < LAN_TTL && id < me } }

    private fun lanHasOffline(now: Long): Boolean =
        synchronized(lock) { peers.values.any { now - it.lan < LAN_TTL && !it.relayOnline } }

    private fun remember(id: Long): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > 5000) seen.iterator().let { it.next(); it.remove() }
        return true
    }

    private fun prunePeers() {
        val now = System.currentTimeMillis()
        val list = synchronized(lock) {
            peers.values.removeAll { it.via(now) == null }
            directs.values.removeAll { now - it.seen > NET_TTL }
            peers.values.map { Peer(it.nick, it.via(now)!!) }.sortedBy { it.nick }
        }
        onPeers(list)
        emitStatus()
    }

    // ---------- STUN ----------

    private fun onStun(buf: ByteArray, len: Int) {
        val tx = Stun.transaction(buf).joinToString("") { "%02x".format(it) }
        val addr = Stun.parse(buf, len) ?: return
        synchronized(stunAnswers) { if (stunAnswers.containsKey(tx)) stunAnswers[tx] = addr }
    }

    private fun discover() {
        val s = socket ?: return
        val asked = stunServers.take(2).mapNotNull { hp ->
            val server = parseHostPort(hp) ?: return@mapNotNull null
            val tx = ByteArray(12).also(random::nextBytes)
            val hex = tx.joinToString("") { "%02x".format(it) }
            synchronized(stunAnswers) { stunAnswers[hex] = UNKNOWN }
            Triple(server, tx, hex)
        }
        if (asked.isEmpty()) return
        val deadline = System.currentTimeMillis() + 2500
        var sent = 0
        while (running && System.currentTimeMillis() < deadline) {
            val pending = synchronized(stunAnswers) { asked.filter { stunAnswers[it.third] === UNKNOWN } }
            if (pending.isEmpty()) break
            if (sent < 3) for ((server, tx) in pending) runCatching { s.send(DatagramPacket(Stun.request(tx), 20, server)) }
            sent++
            if (!pause(500)) return
        }
        val got = synchronized(stunAnswers) { asked.mapNotNull { stunAnswers.remove(it.third)?.takeIf { a -> a !== UNKNOWN } } }
        if (got.isEmpty()) {
            update { it.copy(nat = "STUN не ответил — прямой канал недоступен, связь через сервер") }
            return
        }
        val first = got[0]
        val symmetric = got.size > 1 && got.any { it != first }
        val changed = publicAddr != first
        publicAddr = first
        update {
            it.copy(
                publicAddr = first.address.hostAddress + ":" + first.port,
                nat = if (symmetric) "строгий NAT (симметричный): прямой канал вряд ли пробьётся, сообщения пойдут через сервер"
                else "NAT пропускает: пробую прямой канал P2P",
            )
        }
        if (changed && status.relay == RelayState.ONLINE) relayHello()
    }

    // ---------- служебное ----------

    private inline fun update(f: (Status) -> Status) {
        synchronized(lock) { status = f(status) }
        emitStatus()
    }

    private fun emitStatus() {
        val now = System.currentTimeMillis()
        val bridge = isBridge(now) && lanHasOffline(now)
        if (bridge != status.bridge) synchronized(lock) { status = status.copy(bridge = bridge) }
        onStatus(status)
    }

    private fun pause(ms: Long): Boolean {
        if (ms <= 0) return running
        return try { Thread.sleep(ms); running } catch (e: InterruptedException) { false }
    }

    private fun daemon(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.start()

    companion object {
        const val DEFAULT_PORT = 47474
        const val MAX_TEXT = 500
        const val LAN_TTL = 13_000L
        const val P2P_TTL = 15_000L
        const val NET_TTL = 6 * 60_000L + 30_000L
        const val RELAY_HELLO_MS = 5 * 60_000L
        const val STUN_EVERY_MS = 5 * 60_000L
        const val PRESENCE_AGE = 2 * 60_000L
        /** Сообщения старше суток не принимаем: так нельзя «прокрутить» записанный старый пакет. */
        const val MAX_AGE = 24 * 3600_000L
        val STUN_SERVERS = listOf("stun.l.google.com:19302", "stun.cloudflare.com:3478", "stun1.l.google.com:19302")
        private val UNKNOWN = InetSocketAddress(0)

        /** Широковещательный адрес сети по IP и длине маски: 192.168.1.37/24 → 192.168.1.255. */
        fun broadcast(ip: String, prefix: Int): String? {
            val p = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p.any { it !in 0..255 } || prefix !in 1..30) return null
            val n = (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()
            val b = n or ((1L shl (32 - prefix)) - 1)
            return "${(b shr 24) and 255}.${(b shr 16) and 255}.${(b shr 8) and 255}.${b and 255}"
        }

        /** Широковещательные адреса всех сетей телефона: Wi-Fi, своя точка доступа, Wi-Fi Direct. */
        fun localBroadcasts(port: Int = DEFAULT_PORT): List<InetSocketAddress> {
            val out = ArrayList<InetSocketAddress>()
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val b = ia.broadcast ?: continue
                        if (ia.address is Inet4Address) out += InetSocketAddress(b, port)
                    }
                }
            }
            out += InetSocketAddress("255.255.255.255", port)
            return out.distinctBy { it.address?.hostAddress }
        }

        /** Частные и служебные адреса: пакеты с них — соседи по Wi-Fi, а не интернет. */
        fun isLocal(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress) return true
            val b = a.address
            return b.size == 4 && (b[0].toInt() and 255) == 100 && (b[1].toInt() and 0xC0) == 64 // 100.64.0.0/10
        }

        fun parseEndpoint(s: String): InetSocketAddress? {
            val m = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})""").matchEntire(s.trim()) ?: return null
            val parts = m.groupValues[1].split('.').map { it.toInt() }
            val port = m.groupValues[2].toInt()
            if (parts.any { it > 255 } || port !in 1..65535) return null
            return InetSocketAddress(InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() }), port)
        }

        private fun parseHostPort(s: String): InetSocketAddress? {
            val host = s.substringBeforeLast(':')
            val port = s.substringAfterLast(':').toIntOrNull() ?: return null
            return runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
        }

        fun describe(e: Throwable): String = when (e) {
            is UnknownHostException -> "нет интернета или сервер не найден"
            is SocketTimeoutException -> "сервер не отвечает"
            is RelayException -> when (e.code) {
                429 -> "сервер просит не спешить: слишком много запросов, подожди минуту"
                401, 403 -> "сервер закрыт паролем — нужен свой ретранслятор"
                else -> e.message ?: "ошибка сервера"
            }
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
