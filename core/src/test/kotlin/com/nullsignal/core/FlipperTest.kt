package com.nullsignal.core

import com.nullsignal.core.hw.Hid
import com.nullsignal.core.hw.Ndef
import com.nullsignal.core.hw.NdefT4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlipperTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test fun ndefRecords() {
        assertArrayEquals(hex("D1 01 0C 55 04") + "example.com".toByteArray(), Ndef.uri("https://example.com"))
        assertArrayEquals(hex("D1 01 05 54 02") + "ruhi".toByteArray(), Ndef.text("hi"))
        val long = Ndef.text("x".repeat(300))
        assertEquals(0xC1, long[0].toInt() and 0xFF)
        assertEquals(303, ((long[4].toInt() and 0xFF) shl 8) or (long[5].toInt() and 0xFF))
    }

    @Test fun type4TagReadout() {
        val msg = Ndef.uri("https://example.com")
        val tag = NdefT4(msg)
        val ok = hex("90 00")
        assertArrayEquals(hex("6A 82"), tag.respond(hex("00 A4 04 00 07 A0 00 00 00 03 10 10 00")))
        assertArrayEquals(hex("6A 82"), tag.respond(hex("00 A4 00 0C 02 E1 03")))
        assertArrayEquals(ok, tag.respond(hex("00 A4 04 00 07 D2 76 00 00 85 01 01 00")))
        assertArrayEquals(ok, tag.respond(hex("00 A4 00 0C 02 E1 03")))
        val cc = tag.respond(hex("00 B0 00 00 0F"))
        assertEquals(17, cc.size)
        assertEquals(msg.size + 2, ((cc[11].toInt() and 0xFF) shl 8) or (cc[12].toInt() and 0xFF))
        assertArrayEquals(ok, tag.respond(hex("00 A4 00 0C 02 E1 04")))
        val len = tag.respond(hex("00 B0 00 00 02"))
        assertArrayEquals(byteArrayOf(0, msg.size.toByte()) + ok, len)
        val body = tag.respond(hex("00 B0 00 02 ${"%02X".format(msg.size)}"))
        assertArrayEquals(msg + ok, body)
        assertArrayEquals(hex("6D 00"), tag.respond(hex("00 D6 00 00 01 00")))
    }

    @Test fun hidKeys() {
        assertEquals(0 to 0x04, Hid.key('a'))
        assertEquals(Hid.SHIFT to 0x1D, Hid.key('Z'))
        assertEquals(0 to 0x27, Hid.key('0'))
        assertEquals(Hid.SHIFT to 0x1E, Hid.key('!'))
        assertEquals(Hid.SHIFT to 0x27, Hid.key(')'))
        assertEquals(Hid.SHIFT to 0x1F, Hid.key('@'))
        assertEquals(Hid.SHIFT to 0x38, Hid.key('?'))
        assertEquals(0 to Hid.ENTER, Hid.key('\n'))
        assertNull(Hid.key('я'))
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0), Hid.mediaReport(Hid.VOL_UP))
        assertArrayEquals(byteArrayOf(1, 127, -127, 0), Hid.mouseReport(1, 500, -500))
        // дескриптор: скобки коллекций сбалансированы
        val d = Hid.DESCRIPTOR.map { it.toInt() and 0xFF }
        var i = 0
        var depth = 0
        while (i < d.size) {
            val size = d[i] and 3
            if (d[i] == 0xA1) depth++
            if (d[i] == 0xC0) depth--
            i += 1 + (if (size == 3) 4 else size)
        }
        assertEquals(0, depth)
        assertEquals(d.size, i)
    }
}
