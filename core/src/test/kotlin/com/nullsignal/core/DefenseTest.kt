package com.nullsignal.core

import com.nullsignal.core.hw.UsbInfo
import com.nullsignal.core.security.Mitm
import com.nullsignal.core.spy.SoundModem
import com.nullsignal.core.spy.SoundNote
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class UsbTest {
    private val kb = UsbInfo.Iface(3, 1, 1)
    private val storage = UsbInfo.Iface(8, 6, 0x50)
    private val cdc = UsbInfo.Iface(2, 2, 1)

    @Test fun badusb() {
        assertEquals(2, UsbInfo.assess(0x0781, 0x5567, listOf(storage, kb)).level)       // «флешка» с клавиатурой
        assertEquals(2, UsbInfo.assess(0x2341, 0x8036, listOf(cdc, kb)).level)            // Leonardo-клавиатура
        assertEquals(1, UsbInfo.assess(0x046D, 0xC31C, listOf(kb)).level)                 // обычная клавиатура
        assertEquals(0, UsbInfo.assess(0x0781, 0x5567, listOf(storage)).level)            // обычная флешка
        assertEquals("Arduino Uno", UsbInfo.board(0x2341, 0x0043))
        assertTrue(UsbInfo.isSerial(0x1A86, 0x7523, emptyList()))
        assertFalse(UsbInfo.isSerial(0x0781, 0x5567, listOf(storage)))
        assertEquals("клавиатура", UsbInfo.hidKind(kb))
    }
}

class MitmTest {
    @Test fun issuers() {
        assertTrue(Mitm.trustedIssuer("CN=WR2, O=Google Trust Services, C=US"))
        assertTrue(Mitm.trustedIssuer("CN=R11, O=Let's Encrypt, C=US"))
        assertFalse(Mitm.trustedIssuer("CN=Kaspersky Anti-Virus Personal Root, O=AO Kaspersky Lab"))
        assertEquals("AO Kaspersky Lab", Mitm.issuerOrg("CN=Kaspersky Anti-Virus Personal Root, O=AO Kaspersky Lab"))
        assertEquals("Example, Inc", Mitm.issuerOrg("CN=x, O=\"Example, Inc\", C=US"))
    }

    @Test fun http() {
        assertEquals(Mitm.HttpVerdict.CLEAN, Mitm.checkExample(200, "<h1>Example Domain</h1>"))
        assertEquals(Mitm.HttpVerdict.INJECTED, Mitm.checkExample(200, "<h1>Example Domain</h1><script src=//ads></script>"))
        assertEquals(Mitm.HttpVerdict.REPLACED, Mitm.checkExample(200, "<h1>Войдите в сеть</h1>"))
        assertEquals(Mitm.HttpVerdict.FAILED, Mitm.checkExample(-1, null))
        assertTrue(Mitm.randomDomain().endsWith(".com"))
    }
}

class SoundModemTest {
    private val message = "Встреча в 23:00 у моста. ns1:Zm9v".toByteArray()

    @Test fun clean() {
        val pcm = SoundModem.encode(message)
        assertArrayEquals(message, SoundModem.decode(pcm))
    }

    @Test fun noiseAndOffset() {
        val r = Random(42)
        val signal = SoundModem.encode(message, volume = 0.4)
        val pre = 7777
        val total = ShortArray(pre + signal.size + 5000)
        for (i in total.indices) {
            val s = if (i >= pre && i < pre + signal.size) signal[i - pre].toInt() else 0
            total[i] = (s + r.nextGaussian() * 2500).toInt().coerceIn(-32768, 32767).toShort()
        }
        assertArrayEquals(message, SoundModem.decode(total))
    }

    @Test fun quietBand() {
        val pcm = SoundModem.encode("ok".toByteArray(), SoundModem.QUIET)
        assertArrayEquals("ok".toByteArray(), SoundModem.decode(pcm, SoundModem.QUIET))
        assertNull(SoundModem.decode(pcm, SoundModem.LOUD))
    }

    @Test fun noiseOnly() {
        val r = Random(1)
        assertNull(SoundModem.decode(ShortArray(44100 * 3) { (r.nextGaussian() * 5000).toInt().toShort() }))
        assertEquals(0x07, SoundModem.crc8(byteArrayOf(1)))
    }
}

class SoundStreamTest {
    private fun noisy(signal: ShortArray, pre: Int, post: Int, seed: Long, noise: Double = 1500.0): ShortArray {
        val r = Random(seed)
        return ShortArray(pre + signal.size + post) { i ->
            val s = if (i >= pre && i < pre + signal.size) signal[i - pre].toInt() else 0
            (s + r.nextGaussian() * noise).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun stream(pcm: ShortArray, rx: SoundModem.Receiver, chunk: Int = 1764): List<SoundModem.Receiver.Event> {
        val out = mutableListOf<SoundModem.Receiver.Event>()
        var i = 0
        while (i < pcm.size) {
            val k = minOf(chunk, pcm.size - i)
            out += rx.feed(pcm.copyOfRange(i, i + k), k)
            i += k
        }
        return out
    }

    @Test fun findsFrameInStream() {
        val msg = SoundNote.plain("привет из эфира")
        val pcm = noisy(SoundModem.encode(msg, volume = 0.5), 30_000, 20_000, 7)
        val ev = stream(pcm, SoundModem.Receiver())
        assertEquals(1, ev.size)
        assertArrayEquals(msg, (ev[0] as SoundModem.Receiver.Event.Frame).data)
    }

    @Test fun twoFramesInARow() {
        val a = "раз".toByteArray()
        val b = "два-два".toByteArray()
        val pcm = noisy(SoundModem.encode(a, volume = 0.5) + ShortArray(9000) + SoundModem.encode(b, volume = 0.5), 12_345, 15_000, 3)
        val frames = stream(pcm, SoundModem.Receiver()).filterIsInstance<SoundModem.Receiver.Event.Frame>()
        assertEquals(listOf("раз", "два-два"), frames.map { String(it.data) })
    }

    @Test fun quietBandStream() {
        val pcm = noisy(SoundModem.encode("ultra".toByteArray(), SoundModem.QUIET, 0.5), 5000, 10_000, 11)
        val frames = stream(pcm, SoundModem.Receiver(SoundModem.QUIET), 4096).filterIsInstance<SoundModem.Receiver.Event.Frame>()
        assertEquals("ultra", String(frames.single().data))
    }

    @Test fun cutTransmissionThenGoodOne() {
        val long = SoundModem.encode(ByteArray(120) { it.toByte() }, volume = 0.5)
        val cut = long.copyOf(long.size / 2)
        val good = SoundModem.encode("ok".toByteArray(), volume = 0.5)
        val pcm = noisy(cut + ShortArray(44100) + good, 4000, 12_000, 5)
        val ev = stream(pcm, SoundModem.Receiver())
        assertTrue(ev.first() is SoundModem.Receiver.Event.Broken)
        assertEquals("ok", String((ev.last() as SoundModem.Receiver.Event.Frame).data))
    }

    @Test fun silenceProducesNothing() {
        val r = Random(9)
        val pcm = ShortArray(44100 * 6) { (r.nextGaussian() * 3000).toInt().toShort() }
        assertTrue(stream(pcm, SoundModem.Receiver()).isEmpty())
    }

    @Test fun spectrumShowsTone() {
        val pcm = SoundModem.encode(byteArrayOf(0x0F), volume = 0.8)
        val n = SoundModem.samplesPerSymbol(SoundModem.LOUD)
        val sp = SoundModem.spectrum(pcm, n, n) // первый символ преамбулы — тон 0
        assertEquals(0, sp.indices.maxByOrNull { sp[it] })
        assertTrue(sp[0] > 0.5f)
    }

    @Test fun noteRoundTrip() {
        assertEquals(SoundNote.Opened.Text("hi", false), SoundNote.open(SoundNote.plain("hi")))
        val sealed = SoundNote.seal("секрет", "пароль1")
        assertTrue(SoundNote.isSealed(sealed))
        assertEquals(SoundNote.Opened.NeedPassword, SoundNote.open(sealed))
        assertEquals(SoundNote.Opened.WrongPassword, SoundNote.open(sealed, "не тот"))
        assertEquals(SoundNote.Opened.Text("секрет", true), SoundNote.open(sealed, "пароль1"))
        assertEquals(SoundModem.MAX_PAYLOAD - 37, SoundNote.capacity(true))
        assertEquals(SoundNote.Opened.Unknown, SoundNote.open(byteArrayOf(0x33, 1, 2)))
    }
}

class WifiTwinTest {
    private fun ap(ssid: String, bssid: String, sec: String, level: Int = -60) = com.nullsignal.core.hw.WifiChannels.Ap(ssid, bssid, sec, level)

    @Test fun openCopyOfSecuredNetwork() {
        val t = com.nullsignal.core.hw.WifiChannels.twins(
            listOf(
                ap("Home_5G", "aa:bb:cc:00:00:01", "WPA2"),
                ap("Home_5G", "12:34:56:00:00:09", "OPEN", -40),
                ap("Mesh", "aa:00:00:00:00:01", "WPA2"), ap("Mesh", "aa:00:00:00:00:02", "WPA3"),
                ap("Cafe", "bb:00:00:00:00:01", "OPEN"), ap("Cafe", "bb:00:00:00:00:02", "OPEN"),
                ap("<скрытая сеть>", "cc:00:00:00:00:01", "OPEN"), ap("<скрытая сеть>", "cc:00:00:00:00:02", "WPA2"),
            ),
        )
        assertEquals(listOf("Home_5G"), t.map { it.ssid })
        assertEquals("12:34:56:00:00:09", t.single().open.single().bssid)
    }
}
