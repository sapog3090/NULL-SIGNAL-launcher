package com.nullsignal.core

import com.nullsignal.core.apps.DexScan
import com.nullsignal.core.dns.Blocklist
import com.nullsignal.core.hw.BleVendors
import com.nullsignal.core.hw.Ir
import com.nullsignal.core.hw.WifiChannels
import com.nullsignal.core.net.DeviceKind
import com.nullsignal.core.net.HtmlTitle
import com.nullsignal.core.net.NetBios
import com.nullsignal.core.net.Ssdp
import com.nullsignal.core.net.Upnp
import com.nullsignal.core.net.WakeOnLan
import com.nullsignal.core.net.WsDiscovery
import com.nullsignal.core.osint.Breaches
import com.nullsignal.core.osint.DomainIntel
import com.nullsignal.core.osint.IpIntel
import com.nullsignal.core.osint.MailSecurity
import com.nullsignal.core.osint.TechDetect
import com.nullsignal.core.osint.UsernameSearch
import com.nullsignal.core.spy.Base32
import com.nullsignal.core.spy.InvisibleInk
import com.nullsignal.core.spy.Morse
import com.nullsignal.core.spy.Stego
import com.nullsignal.core.spy.Totp
import com.nullsignal.core.terminal.Action
import com.nullsignal.core.terminal.Module
import com.nullsignal.core.terminal.Terminal
import com.nullsignal.core.terminal.TerminalEnv
import com.nullsignal.core.util.Calc
import com.nullsignal.core.util.DevTools
import com.nullsignal.core.util.Speed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class TotpTest {
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test fun `rfc 6238 sha1 vectors`() {
        assertEquals("94287082", Totp.code(rfcSecret, 59, digits = 8))
        assertEquals("07081804", Totp.code(rfcSecret, 1111111109, digits = 8))
        assertEquals("14050471", Totp.code(rfcSecret, 1111111111, digits = 8))
        assertEquals("89005924", Totp.code(rfcSecret, 1234567890, digits = 8))
        assertEquals("69279037", Totp.code(rfcSecret, 2000000000, digits = 8))
        assertEquals("65353130", Totp.code(rfcSecret, 20000000000, digits = 8))
    }

    @Test fun base32() {
        val raw = Base32.decode("JBSWY3DPEHPK3PXP")
        assertArrayEquals(byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21, 0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()), raw)
        assertEquals("JBSWY3DPEHPK3PXP", Base32.encode(raw))
        assertEquals(Base32.encode(rfcSecret), "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertFalse(Base32.isValid("not base32!"))
    }

    @Test fun `otpauth uri`() {
        val a = Totp.parseUri("otpauth://totp/GitHub:octo%40mail.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=6&period=30")!!
        assertEquals("GitHub", a.issuer)
        assertEquals("octo@mail.com", a.name)
        assertEquals("JBSWY3DPEHPK3PXP", a.secret)
        assertNull(Totp.parseUri("https://example.com"))
        assertEquals(a, Totp.deserialize(Totp.serialize(a)))
        assertEquals(6, Totp.code(a, 0).length)
        assertEquals(30, Totp.secondsLeft(60))
    }
}

class SpyTest {
    @Test fun `stego roundtrip`() {
        val r = Random(1)
        val pixels = IntArray(200 * 120) { r.nextInt() }
        val secret = "Встреча в полночь у старого моста. ns1:abc".toByteArray()
        val out = Stego.embed(pixels, secret)
        assertArrayEquals(secret, Stego.extract(out))
        // картинка почти не изменилась: каждый канал отличается не больше чем на 1
        for (i in pixels.indices) for (sh in intArrayOf(16, 8, 0)) {
            assertTrue(kotlin.math.abs(((pixels[i] shr sh) and 0xff) - ((out[i] shr sh) and 0xff)) <= 1)
        }
        assertNull(Stego.extract(IntArray(1000) { r.nextInt() }))
        assertEquals(Stego.capacity(100), 100 * 3 / 8 - 7)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stego too long`() { Stego.embed(IntArray(10), ByteArray(100)) }

    @Test fun `invisible ink`() {
        val text = InvisibleInk.hide("Привет, как дела?", "секрет: пароль 42")
        assertTrue(text.length > "Привет, как дела?".length)
        assertEquals("Привет, как дела?", InvisibleInk.strip(text))
        assertEquals("секрет: пароль 42", InvisibleInk.reveal(text))
        assertTrue(InvisibleInk.detect(text) > 0)
        assertNull(InvisibleInk.reveal("обычный текст"))
    }

    @Test fun morse() {
        assertEquals("... --- ...", Morse.encode("SOS"))
        assertEquals("SOS HELP", Morse.decode(Morse.encode("sos help")))
        assertEquals("ПРИВЕТ", Morse.decode(Morse.encode("привет"), cyrillic = true))
        val t = Morse.timings(".- -", 100)
        assertEquals(listOf(true to 100, false to 100, true to 300, false to 300, true to 300), t)
    }
}

class IrTest {
    @Test fun `nec pattern`() {
        val s = Ir.nec(0x04, 0x08)
        assertEquals(38000, s.carrierHz)
        assertEquals(67, s.pattern.size)
        assertEquals(9000, s.pattern[0])
        assertEquals(4500, s.pattern[1])
        // первый бит адреса 0x04 (LSB) = 0 → пауза 560
        assertEquals(560, s.pattern[3])
    }

    @Test fun `samsung and sony`() {
        val sam = Ir.samsung(0x07, 0x02)
        assertEquals(4500, sam.pattern[0])
        assertEquals(4500, sam.pattern[1])
        val sony = Ir.sony(1, 21)
        assertEquals(40000, sony.carrierHz)
        assertEquals(3 * 45000, sony.durationUs)
        assertEquals(2400, sony.pattern[0])
    }

    @Test fun `rc5 starts with mark`() {
        val s = Ir.rc5(0, 12)
        assertEquals(36000, s.carrierHz)
        assertTrue(s.pattern.isNotEmpty())
        assertEquals(0, s.pattern.size % 2)
        assertTrue(s.pattern.all { it > 0 })
        assertEquals(5, Ir.ALL_POWER.size)
    }
}

class DexScanTest {
    /** Минимальный DEX: заголовок, таблица смещений строк, сами строки. */
    private fun fakeDex(vararg strings: String): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(0x70)
        "dex\n035\u0000".toByteArray().copyInto(header)
        val idsOff = 0x70
        val dataOff = idsOff + strings.size * 4
        fun putU32(b: ByteArray, at: Int, v: Int) { for (k in 0..3) b[at + k] = (v shr (8 * k)).toByte() }
        putU32(header, 0x38, strings.size)
        putU32(header, 0x3C, idsOff)
        out.write(header)
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val off = dataOff + data.size()
            data.write(s.length) // uleb128 < 128
            data.write(s.toByteArray())
            data.write(0)
            off
        }
        offsets.forEach { o -> val b = ByteArray(4); putU32(b, 0, o); out.write(b) }
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    @Test fun `finds trackers in dex`() {
        val dex = fakeDex("Ljava/lang/Object;", "Lcom/appsflyer/AppsFlyerLib;", "Lcom/yandex/metrica/YandexMetrica;", "Lcom/google/android/gms/ads/AdView;", "hello")
        val found = mutableSetOf<DexScan.Tracker>()
        assertEquals(5, DexScan.scanDex(dex, found))
        val names = found.map { it.name }.toSet()
        assertTrue("AppsFlyer" in names)
        assertTrue("Яндекс AppMetrica" in names)
        assertTrue("Google AdMob" in names)
        assertEquals(3, found.size)
        assertEquals(0, DexScan.scanDex(ByteArray(10), mutableSetOf()))
    }

    @Test fun `scans apk zip`() {
        val f = File.createTempFile("test", ".apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex")); z.write(fakeDex("Lcom/facebook/appevents/AppEventsLogger;")); z.closeEntry()
            z.putNextEntry(ZipEntry("classes2.dex")); z.write(fakeDex("Lio/huq/sourcekit/HISourceKit;")); z.closeEntry()
            z.putNextEntry(ZipEntry("res/raw/x.txt")); z.write("Lcom/appsflyer/".toByteArray()); z.closeEntry()
        }
        val r = DexScan.scanApk(f)
        f.delete()
        assertEquals(2, r.dexFiles)
        assertEquals(setOf("Facebook Analytics", "Huq"), r.trackers.map { it.name }.toSet())
    }
}

class DiscoveryTest {
    @Test fun `ssdp headers`() {
        val h = Ssdp.parseHeaders("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:1900/rootDesc.xml\r\nSERVER: Linux UPnP/1.0\r\nST: upnp:rootdevice\r\n\r\n")
        assertEquals("http://192.168.1.1:1900/rootDesc.xml", h["location"])
        assertTrue(Ssdp.searchRequest().startsWith("M-SEARCH"))
    }

    @Test fun `upnp description and soap`() {
        val xml = """<?xml version="1.0"?><root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
            <friendlyName>Keenetic Giga</friendlyName><manufacturer>Keenetic Ltd.</manufacturer><modelName>KN-1011</modelName>
            <presentationURL>/</presentationURL><deviceList><device><serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl/IPConn</controlURL>
            </service></serviceList></device></deviceList></device></root>"""
        val d = Upnp.parseDescription(xml, "http://192.168.1.1:1900/rootDesc.xml")
        assertEquals("Keenetic Giga", d.friendlyName)
        assertEquals("http://192.168.1.1:1900/ctl/IPConn", d.wanControlUrl)
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", d.wanServiceType)
        assertTrue(d.title.contains("Keenetic Ltd. KN-1011"))
        assertTrue(Upnp.soapEnvelope("urn:x", "GetExternalIPAddress").contains("<u:GetExternalIPAddress"))
        assertEquals("1.2.3.4", Upnp.parseExternalIp("<s:Body><u:R><NewExternalIPAddress>1.2.3.4</NewExternalIPAddress></u:R></s:Body>"))
        val m = Upnp.parseMapping("<NewRemoteHost></NewRemoteHost><NewExternalPort>25565</NewExternalPort><NewProtocol>TCP</NewProtocol><NewInternalPort>25565</NewInternalPort><NewInternalClient>192.168.1.50</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>Minecraft</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration>")!!
        assertEquals(25565, m.external)
        assertEquals("192.168.1.50", m.internalClient)
        assertEquals("Minecraft", m.description)
    }

    @Test fun netbios() {
        assertEquals(50, NetBios.nodeStatusRequest().size)
        // ответ: заголовок 12 + имя 34 + тип/класс/ttl/длина 10 + число имён + 2 имени по 18 + MAC 6
        val resp = ByteArray(12 + 34 + 10 + 1 + 36 + 6 + 40)
        var p = 12 + 34 + 10
        resp[p++] = 2
        "DESKTOP-K1LL   ".toByteArray().copyInto(resp, p); resp[p + 15] = 0x00; resp[p + 16] = 0x04; p += 18
        "WORKGROUP      ".toByteArray().copyInto(resp, p); resp[p + 15] = 0x00; resp[p + 16] = 0x84.toByte(); p += 18
        byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E).copyInto(resp, p)
        val info = NetBios.parseNodeStatus(resp)!!
        assertEquals("DESKTOP-K1LL", info.name)
        assertEquals("WORKGROUP", info.group)
        assertEquals("00:1A:2B:3C:4D:5E", info.mac)
    }

    @Test fun onvif() {
        val xml = """<SOAP-ENV:Envelope><SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch>
            <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/HIKVISION%20DS-2CD onvif://www.onvif.org/hardware/DS-2CD2143G0-I</d:Scopes>
            <d:XAddrs>http://192.168.1.64/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
        val c = WsDiscovery.parse(xml)!!
        assertEquals(listOf("http://192.168.1.64/onvif/device_service"), c.addresses)
        assertEquals("HIKVISION DS-2CD", c.name)
        assertEquals("DS-2CD2143G0-I", c.hardware)
        assertTrue(WsDiscovery.probe("x").contains("NetworkVideoTransmitter"))
    }

    @Test fun `wake on lan`() {
        val mac = WakeOnLan.parseMac("aa-bb-cc-dd-ee-ff")!!
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.formatMac(mac))
        val p = WakeOnLan.magicPacket(mac)
        assertEquals(102, p.size)
        assertEquals(0xAA.toByte(), p[6])
        assertEquals(0xFF.toByte(), p[101])
        assertNull(WakeOnLan.parseMac("zz:11"))
        assertEquals("192.168.1.255", WakeOnLan.broadcast("192.168.1.42", 24))
    }

    @Test fun `title and device kind`() {
        assertEquals("TP-Link Archer C6", HtmlTitle.extract("<html><head><TITLE>\n TP-Link &amp; Archer C6 </TITLE>".replace("&amp; ", "")))
        assertEquals(DeviceKind.Kind.ROUTER, DeviceKind.guess(true, emptyList()))
        assertEquals(DeviceKind.Kind.CAMERA, DeviceKind.guess(false, listOf(80, 554)))
        assertEquals(DeviceKind.Kind.PRINTER, DeviceKind.guess(false, listOf(9100)))
        assertEquals(DeviceKind.Kind.PC, DeviceKind.guess(false, listOf(445), "DESKTOP-K1LL"))
        assertEquals(DeviceKind.Kind.TV, DeviceKind.guess(false, emptyList(), "Samsung Smart TV"))
        assertEquals(DeviceKind.Kind.UNKNOWN, DeviceKind.guess(false, emptyList()))
    }
}

class OsintTest {
    @Test fun `username judging`() {
        val gh = UsernameSearch.SITES.first { it.name == "GitHub" }
        assertEquals(UsernameSearch.Verdict.FOUND, UsernameSearch.judge(gh, 200, "{}"))
        assertEquals(UsernameSearch.Verdict.NOT_FOUND, UsernameSearch.judge(gh, 404, ""))
        assertEquals(UsernameSearch.Verdict.UNKNOWN, UsernameSearch.judge(gh, 429, ""))
        val gl = UsernameSearch.SITES.first { it.name == "GitLab" }
        assertEquals(UsernameSearch.Verdict.NOT_FOUND, UsernameSearch.judge(gl, 200, "[]"))
        assertEquals(UsernameSearch.Verdict.FOUND, UsernameSearch.judge(gl, 200, "[{\"id\":1}]"))
        val cf = UsernameSearch.SITES.first { it.name == "Codeforces" }
        assertEquals(UsernameSearch.Verdict.NOT_FOUND, UsernameSearch.judge(cf, 400, "{\"status\":\"FAILED\"}"))
        assertTrue(UsernameSearch.valid("k1ll_er.99"))
        assertFalse(UsernameSearch.valid("a b"))
        assertTrue(UsernameSearch.SITES.size >= 25)
        assertEquals("https://api.github.com/users/neo", UsernameSearch.fill(gh.api, "neo"))
    }

    @Test fun `ip intel`() {
        val g = IpIntel.parseIpWho("""{"ip":"8.8.8.8","success":true,"type":"IPv4","country":"United States","country_code":"US","region":"California","city":"Mountain View","latitude":37.38,"longitude":-122.08,"connection":{"asn":15169,"org":"Google LLC","isp":"Google LLC"},"timezone":{"id":"America/Los_Angeles"}}""")!!
        assertEquals("Mountain View", g.city)
        assertEquals("AS15169", g.asn)
        assertEquals(37.38, g.lat!!, 0.001)
        assertNull(IpIntel.parseIpWho("""{"success":false,"message":"Invalid IP address"}"""))
        assertEquals("Invalid IP address", IpIntel.ipwhoError("""{"success":false,"message":"Invalid IP address"}"""))
        val e = IpIntel.parseInternetDb("""{"cpes":["cpe:/a:nginx:nginx"],"hostnames":["dns.google"],"ip":"8.8.8.8","ports":[443,53],"tags":[],"vulns":["CVE-2021-1"]}""")!!
        assertEquals(listOf(53, 443), e.ports)
        assertEquals(listOf("CVE-2021-1"), e.vulns)
        assertNull(IpIntel.parseInternetDb("""{"detail":"No information available"}"""))
    }

    @Test fun `domain intel`() {
        val subs = DomainIntel.parseCrtSh("""[{"common_name":"example.com","name_value":"example.com\nwww.example.com"},{"name_value":"*.dev.example.com\nmail.example.com\nevil.com"}]""", "example.com")
        assertEquals(listOf("dev.example.com", "example.com", "mail.example.com", "www.example.com"), subs)
        assertEquals("example.com", DomainIntel.clean("https://www.Example.com/path"))
        assertTrue(DomainIntel.validDomain("sub.example.co.uk"))
        assertFalse(DomainIntel.validDomain("nodots"))
        val s = DomainIntel.parseWaybackClosest("""{"archived_snapshots":{"closest":{"available":true,"url":"http://web.archive.org/web/2020/x","timestamp":"20200102030405"}}}""")!!
        assertEquals("02.01.2020", DomainIntel.formatTimestamp(s.timestamp))
        assertEquals("19990125000000", DomainIntel.parseCdxFirst("""[["timestamp","original"],["19990125000000","http://example.com/"]]""")!!.timestamp)
    }

    @Test fun `tech detect`() {
        val t = TechDetect.detect(
            mapOf("Server" to "nginx/1.18", "Set-Cookie" to "PHPSESSID=1; path=/", "CF-RAY" to "abc"),
            "<html><link href='/wp-content/themes/x.css'><script src='https://mc.yandex.ru/metrika/tag.js'></script><meta name=\"generator\" content=\"WordPress 6.4\">",
        ).map { it.name }
        assertTrue("nginx" in t)
        assertTrue("PHP" in t)
        assertTrue("Cloudflare" in t)
        assertTrue("WordPress" in t)
        assertTrue("Яндекс.Метрика" in t)
        assertEquals(1, t.count { it == "WordPress" || it.startsWith("WordPress ") })
    }

    @Test fun `mail security`() {
        val bad = MailSecurity.analyze(listOf("mx.example.com"), listOf("v=spf1 include:_spf.example.com ~all"), emptyList())
        assertTrue(MailSecurity.spoofable(bad))
        val good = MailSecurity.analyze(listOf("mx.example.com"), listOf("\"v=spf1 -all\""), listOf("v=DMARC1; p=reject; rua=mailto:x@example.com"))
        assertFalse(MailSecurity.spoofable(good))
    }

    @Test fun breaches() {
        val x = Breaches.parseXposedOrNot("""{"breaches":[["Adobe","Canva"]]}""")!!
        assertTrue(x.found)
        assertEquals(listOf("Adobe", "Canva"), x.sources)
        assertFalse(Breaches.parseXposedOrNot("""{"Error":"Not found"}""")!!.found)
        val l = Breaches.parseLeakCheck("""{"success":true,"found":2,"fields":["username"],"sources":[{"name":"Canva.com","date":"2019-05"},{"name":"VK.com","date":""}]}""")!!
        assertEquals(listOf("Canva.com (2019-05)", "VK.com"), l.sources)
        assertFalse(Breaches.parseLeakCheck("""{"success":false,"error":"Not found"}""")!!.found)
        assertTrue(Breaches.validEmail("neo@matrix.io"))
        assertFalse(Breaches.validEmail("neo@"))
    }
}

class UtilTest {
    @Test fun calc() {
        assertEquals("1032", Calc.format(Calc.eval("2^10 + 8")))
        assertEquals("14", Calc.format(Calc.eval("2 + 3 * 4")))
        assertEquals("20", Calc.format(Calc.eval("(2 + 3) * 4")))
        assertEquals("-8", Calc.format(Calc.eval("-2^3")))
        assertEquals("-4", Calc.format(Calc.eval("-2^2")))
        assertEquals("512", Calc.format(Calc.eval("2^3^2")))
        assertEquals("0.5", Calc.format(Calc.eval("2^-1")))
        assertEquals("255", Calc.format(Calc.eval("0xff")))
        assertEquals("4", Calc.format(Calc.eval("sqrt(16)")))
        assertEquals("0.5", Calc.format(Calc.eval("1/2")))
        assertEquals("3.141592654", Calc.format(Calc.eval("pi")))
        assertEquals("1.5e+20", Calc.format(Calc.eval("1.5e20")))
        assertEquals("2.5", Calc.format(Calc.eval("5,0 / 2")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calc error`() { Calc.eval("2 +* 3)") }

    @Test fun bases() {
        assertEquals("11111111", DevTools.convertBase("ff", 16, 2))
        assertEquals("dec  255", DevTools.allBases("0xff")[0])
        assertTrue(DevTools.subnet("192.168.1.10/24").any { "192.168.1.255" in it })
        assertTrue(DevTools.subnet("10.0.0.1/30").any { "адресов     2" in it })
        assertTrue(DevTools.jsonPretty("{\"a\":1}").contains("\n"))
    }

    @Test fun speed() {
        assertEquals(8.0, Speed.mbps(1_000_000, 1000), 0.001)
        assertEquals(2.0, Speed.median(listOf(3.0, 1.0, 2.0)), 0.001)
        assertEquals(5.0, Speed.jitter(listOf(10.0, 15.0, 10.0)), 0.001)
    }

    @Test fun blocklist() {
        val raw = """
            # comment
            0.0.0.0 ads.example.com
            127.0.0.1 tracker.net
            ||metrics.site.ru^
            ||bad.com^${'$'}third-party
            plain.org
            0.0.0.0 localhost
            *.wild.io
        """.trimIndent()
        val list = Blocklist(Blocklist.parse(raw))
        assertEquals(5, list.size)
        assertTrue(list.contains("ads.example.com"))
        assertTrue(list.contains("x.y.tracker.net"))
        assertTrue(list.contains("metrics.site.ru"))
        assertTrue(list.contains("cdn.wild.io"))
        assertFalse(list.contains("example.com"))
        assertFalse(list.contains("bad.com"))
        assertEquals("tracker.net", list.match("a.tracker.net"))
    }

    @Test fun radio() {
        val nets = listOf(WifiChannels.Net(1, -40, 2412), WifiChannels.Net(1, -45, 2412), WifiChannels.Net(6, -80, 2437))
        assertEquals(11, WifiChannels.best24(nets))
        assertTrue(WifiChannels.wps("[WPA2-PSK-CCMP][WPS][ESS]"))
        assertEquals("Apple", BleVendors.name(0x004C))
        assertEquals("Find My / AirTag", BleVendors.appleKind(byteArrayOf(0x12, 0x19)))
    }

    @Test fun `terminal new commands`() {
        val env = object : TerminalEnv {
            override val operator = "op"; override val device = "d"; override val ip: String? = null
            override val objective = ""; override val followers = 0; override val apps = emptyList<String>()
            override fun now() = ""
        }
        val t = Terminal(env)
        assertEquals("= 7", t.execute("calc 1+2*3").lines.single())
        assertEquals(Action.OpenWith(Module.SSH, "root@10.0.0.2"), t.execute("ssh root@10.0.0.2").action)
        assertEquals(Action.Open(Module.XRAY), t.execute("xray").action)
        assertNotNull(t.execute("ipinfo 8.8.8.8").action)
        assertNull(t.execute("ipinfo").action)
        assertEquals("... --- ...", t.execute("morse sos").lines.single())
        assertTrue(t.execute("totp JBSWY3DPEHPK3PXP").lines.single().length > 6)
        assertTrue(t.execute("help").lines.size > 60)
    }
}
