package com.nullsignal.core.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Чат внутри своей сети Wi-Fi без интернета и серверов. «Комната» — это кодовое слово:
 * из него получается ключ AES-256, поэтому сообщения читают только те, кто знает слово.
 */
object ChatCrypto {
    private val MAGIC = "NSC1".toByteArray()
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

    /** Короткий отпечаток комнаты: если у двоих он совпал — кодовое слово одинаковое. */
    fun fingerprint(key: SecretKeySpec): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "%02X%02X-%02X%02X".format(h[0], h[1], h[2], h[3])
    }
}

class ChatNode(
    room: String,
    val nick: String,
    private val port: Int = DEFAULT_PORT,
    /** Куда слать пакеты: широковещательные адреса сети (или конкретные адреса — в тестах). */
    private val targets: () -> List<InetSocketAddress>,
    private val onMessage: (Message) -> Unit,
    private val onPeers: (List<String>) -> Unit = {},
) {
    class Message(val from: String, val text: String, val time: Long, val mine: Boolean)

    private val key = ChatCrypto.key(room)
    val fingerprint: String = ChatCrypto.fingerprint(key)
    private val random = SecureRandom()
    private val me = random.nextLong()
    private val seen = LinkedHashSet<Long>()
    private val peers = HashMap<Long, Pair<String, Long>>()
    private var socket: DatagramSocket? = null

    @Volatile private var running = false

    fun start() {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.bind(InetSocketAddress(port))
        socket = s
        running = true
        Thread({ receiveLoop(s) }, "chat-rx").apply { isDaemon = true }.start()
        Thread({
            while (running) {
                runCatching { transmit(pack('H', random.nextLong(), ""), 1) }
                prunePeers()
                try { Thread.sleep(4000) } catch (e: InterruptedException) { break }
            }
        }, "chat-hello").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { transmit(pack('B', random.nextLong(), ""), 1) }
        runCatching { socket?.close() }
        socket = null
    }

    /** Отправить сообщение всем в комнате. Каждый пакет уходит трижды: UDP не гарантирует доставку. */
    fun send(text: String) {
        val t = text.trim().take(MAX_TEXT)
        if (t.isEmpty() || !running) return
        val id = random.nextLong()
        synchronized(seen) { remember(id) }
        onMessage(Message(nick, t, System.currentTimeMillis(), true))
        val packet = pack('M', id, t)
        Thread({
            for (pause in longArrayOf(0, 150, 400)) {
                try { Thread.sleep(pause) } catch (e: InterruptedException) { return@Thread }
                runCatching { transmit(packet, 1) }
            }
        }, "chat-tx").apply { isDaemon = true }.start()
    }

    private fun remember(id: Long): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > 2000) seen.iterator().let { it.next(); it.remove() }
        return true
    }

    private fun pack(type: Char, id: Long, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(type.code)
            d.writeLong(me)
            d.writeLong(id)
            d.writeLong(System.currentTimeMillis())
            d.writeUTF(nick.take(24))
            d.writeUTF(text)
        }
        return ChatCrypto.seal(key, out.toByteArray(), random)
    }

    private fun transmit(packet: ByteArray, times: Int) {
        val s = socket ?: return
        repeat(times) { for (t in targets()) runCatching { s.send(DatagramPacket(packet, packet.size, t)) } }
    }

    private fun prunePeers() {
        val now = System.currentTimeMillis()
        val list = synchronized(peers) {
            peers.values.removeAll { now - it.second > 13_000 }
            peers.values.map { it.first }.sorted()
        }
        onPeers(list)
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: Exception) { if (!running) break else continue }
            val plain = ChatCrypto.open(key, buf, p.length) ?: continue
            runCatching {
                val d = DataInputStream(plain.inputStream())
                val type = d.readByte().toInt().toChar()
                val sender = d.readLong()
                val id = d.readLong()
                val time = d.readLong()
                val from = d.readUTF()
                val text = d.readUTF()
                if (sender == me) return@runCatching
                if (Math.abs(System.currentTimeMillis() - time) > 10 * 60_000) return@runCatching // старый пакет, записанный и пущенный заново
                when (type) {
                    'M' -> if (synchronized(seen) { remember(id) }) onMessage(Message(from, text, time, false))
                    'H' -> {
                        val fresh = synchronized(peers) { peers.put(sender, from to System.currentTimeMillis()) == null }
                        if (fresh) prunePeers()
                    }
                    'B' -> { synchronized(peers) { peers.remove(sender) }; prunePeers() }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 47474
        const val MAX_TEXT = 500

        /** Широковещательный адрес сети по IP и длине маски: 192.168.1.37/24 → 192.168.1.255. */
        fun broadcast(ip: String, prefix: Int): String? {
            val p = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p.any { it !in 0..255 } || prefix !in 1..30) return null
            val n = (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()
            val b = n or ((1L shl (32 - prefix)) - 1)
            return "${(b shr 24) and 255}.${(b shr 16) and 255}.${(b shr 8) and 255}.${b and 255}"
        }
    }
}
