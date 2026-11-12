package com.nullsignal.core.dns

/**
 * Минимальный разбор IPv4/UDP/DNS для DNS-перехватчика.
 * Всё на массивах байт — без зависимостей, тестируется на JVM.
 */
object DnsPacket {

    data class Query(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        /** DNS-сообщение целиком (UDP payload). */
        val dns: ByteArray,
        val id: Int,
        val name: String,
        val qtype: Int,
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /** IPv4 + UDP + DNS-запрос, иначе null. */
    fun parse(packet: ByteArray, length: Int = packet.size): Query? {
        if (length < 28) return null
        if (u8(packet, 0) shr 4 != 4) return null
        val ihl = (u8(packet, 0) and 0x0f) * 4
        if (u8(packet, 9) != 17 || length < ihl + 8) return null
        val total = minOf(u16(packet, 2), length)
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val dns = packet.copyOfRange(ihl + 8, total)
        if (dns.size < 12) return null
        val flags = u16(dns, 2)
        if (flags and 0x8000 != 0) return null // это ответ, не запрос
        if (u16(dns, 4) < 1) return null
        val (name, end) = readName(dns, 12) ?: return null
        if (end + 4 > dns.size) return null
        return Query(srcIp, dstIp, srcPort, dstPort, dns, u16(dns, 0), name, u16(dns, end))
    }

    /** Имя из меток: 3www6google3com0 → www.google.com. Сжатие в запросах не встречается. */
    fun readName(b: ByteArray, start: Int): Pair<String, Int>? {
        val parts = mutableListOf<String>()
        var i = start
        while (i < b.size) {
            val len = u8(b, i)
            if (len == 0) return parts.joinToString(".").lowercase() to i + 1
            if (len and 0xc0 != 0 || i + 1 + len > b.size || parts.size > 127) return null
            parts += String(b, i + 1, len, Charsets.US_ASCII)
            i += 1 + len
        }
        return null
    }

    /** DNS-ответ NXDOMAIN («такого домена нет») на запрос. */
    fun nxdomain(query: ByteArray): ByteArray {
        val (_, end) = readName(query, 12) ?: return query
        val questionEnd = end + 4
        val out = query.copyOfRange(0, questionEnd)
        val rd = u8(query, 2) and 0x01
        out[2] = (0x80 or rd).toByte()          // QR=1, RD как в запросе
        out[3] = 0x83.toByte()                   // RA=1, RCODE=3 (NXDOMAIN)
        out[6] = 0; out[7] = 0; out[8] = 0; out[9] = 0; out[10] = 0; out[11] = 0
        return out
    }

    /** Заворачивает DNS-ответ в IPv4/UDP-пакет обратно отправителю запроса. */
    fun wrapResponse(q: Query, dnsResponse: ByteArray): ByteArray {
        val total = 20 + 8 + dnsResponse.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40 // не фрагментировать
        p[8] = 64   // TTL
        p[9] = 17   // UDP
        q.dstIp.copyInto(p, 12) // от «DNS-сервера»
        q.srcIp.copyInto(p, 16) // к приложению
        val cs = checksum(p, 0, 20)
        p[10] = (cs shr 8).toByte(); p[11] = cs.toByte()
        p[20] = (q.dstPort shr 8).toByte(); p[21] = q.dstPort.toByte()
        p[22] = (q.srcPort shr 8).toByte(); p[23] = q.srcPort.toByte()
        val udpLen = 8 + dnsResponse.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // контрольная сумма UDP в IPv4 необязательна (0)
        dnsResponse.copyInto(p, 28)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) { sum += u16(b, i); i += 2 }
        if (len % 2 == 1) sum += u8(b, off + len - 1) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    /** Собрать DNS-запрос (для тестов и проверки). */
    fun buildQuery(id: Int, name: String, qtype: Int = 1): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id); out.write(0x01); out.write(0x00)
        out.write(0); out.write(1); repeat(6) { out.write(0) }
        name.split('.').filter { it.isNotEmpty() }.forEach { l -> out.write(l.length); out.write(l.toByteArray()) }
        out.write(0)
        out.write(qtype shr 8); out.write(qtype); out.write(0); out.write(1)
        return out.toByteArray()
    }

    val QTYPES = mapOf(1 to "A", 28 to "AAAA", 5 to "CNAME", 15 to "MX", 16 to "TXT", 65 to "HTTPS", 33 to "SRV", 12 to "PTR")
}
