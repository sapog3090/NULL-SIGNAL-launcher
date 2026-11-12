package com.nullsignal.core

import com.nullsignal.core.dns.DnsPacket
import com.nullsignal.core.dns.Trackers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsTest {

    private fun ipUdp(dns: ByteArray, src: ByteArray, dst: ByteArray, sport: Int, dport: Int): ByteArray {
        val q = DnsPacket.Query(dst, src, dport, sport, dns, 0, "", 0) // обратное направление — wrapResponse поменяет местами
        return DnsPacket.wrapResponse(q, dns)
    }

    @Test fun `parse query from ip packet`() {
        val dns = DnsPacket.buildQuery(0x1234, "Ads.DoubleClick.net", 28)
        val pkt = ipUdp(dns, byteArrayOf(10, 111, 0, 2), byteArrayOf(10, 111, 0, 1), 40000, 53)
        val q = DnsPacket.parse(pkt)
        assertNotNull(q)
        q!!
        assertEquals("ads.doubleclick.net", q.name)
        assertEquals(28, q.qtype)
        assertEquals(0x1234, q.id)
        assertEquals(40000, q.srcPort)
        assertEquals(53, q.dstPort)
        assertArrayEquals(byteArrayOf(10, 111, 0, 2), q.srcIp)
        // контрольная сумма заголовка корректна: сумма по всему заголовку = 0
        assertEquals(0, DnsPacket.checksum(pkt, 0, 20))
    }

    @Test fun `nxdomain response`() {
        val dns = DnsPacket.buildQuery(7, "evil.example")
        val r = DnsPacket.nxdomain(dns)
        assertEquals(0x81, r[2].toInt() and 0xff) // QR + RD
        assertEquals(3, r[3].toInt() and 0x0f)   // NXDOMAIN
        assertEquals(dns.size, r.size)
        val q = DnsPacket.parse(ipUdp(dns, byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 0, 1), 5555, 53))!!
        val back = DnsPacket.wrapResponse(q, r)
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), back.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), back.copyOfRange(16, 20))
        assertEquals(53, ((back[20].toInt() and 0xff) shl 8) or (back[21].toInt() and 0xff))
        assertEquals(5555, ((back[22].toInt() and 0xff) shl 8) or (back[23].toInt() and 0xff))
    }

    @Test fun `responses and garbage are ignored`() {
        val dns = DnsPacket.buildQuery(1, "a.b")
        dns[2] = 0x81.toByte()
        assertNull(DnsPacket.parse(ipUdp(dns, ByteArray(4), ByteArray(4), 1, 53)))
        assertNull(DnsPacket.parse(ByteArray(10)))
    }

    @Test fun trackers() {
        assertEquals("Google Ads", Trackers.match("stats.g.doubleclick.net"))
        assertEquals("Яндекс.Метрика", Trackers.match("mc.yandex.ru"))
        assertNull(Trackers.match("yandex.ru"))
        assertNull(Trackers.match("github.com"))
    }
}
