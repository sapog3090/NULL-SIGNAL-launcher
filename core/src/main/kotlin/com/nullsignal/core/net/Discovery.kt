package com.nullsignal.core.net

import java.net.URI
import java.net.URLDecoder

/** SSDP / UPnP: устройства сами рассказывают, кто они (роутеры, ТВ, приставки, медиасерверы). */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900

    fun searchRequest(st: String = "ssdp:all", mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $ADDRESS:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\n\r\n"

    /** Заголовки ответа (ключи в нижнем регистре). */
    fun parseHeaders(response: String): Map<String, String> = response.lines().drop(1)
        .filter { ':' in it }
        .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
}

/** Описание UPnP-устройства из его XML. */
data class UpnpDevice(
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val deviceType: String?,
    val presentationUrl: String?,
    /** controlURL сервиса WANIPConnection / WANPPPConnection (только у роутеров). */
    val wanControlUrl: String?,
    val wanServiceType: String?,
) {
    val title: String
        get() = listOfNotNull(friendlyName, listOfNotNull(manufacturer, modelName).joinToString(" ").ifBlank { null })
            .distinct().joinToString(" · ")
}

object Upnp {
    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            ?.let { unescape(it) }?.takeIf { it.isNotBlank() }

    fun unescape(s: String): String = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")

    fun resolve(base: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching { URI(base).resolve(path.trim()).toString() }.getOrNull()
    }

    /** Разбирает device description; [location] — URL, откуда XML скачан. */
    fun parseDescription(xml: String, location: String): UpnpDevice {
        val base = tag(xml, "URLBase") ?: location
        // ищем сервис подключения к интернету
        val services = Regex("<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml).map { it.groupValues[1] }.toList()
        val wan = services.firstOrNull { s ->
            val t = tag(s, "serviceType") ?: ""
            "WANIPConnection" in t || "WANPPPConnection" in t
        }
        return UpnpDevice(
            friendlyName = tag(xml, "friendlyName"),
            manufacturer = tag(xml, "manufacturer"),
            modelName = tag(xml, "modelName"),
            modelNumber = tag(xml, "modelNumber"),
            deviceType = tag(xml, "deviceType"),
            presentationUrl = resolve(base, tag(xml, "presentationURL")),
            wanControlUrl = wan?.let { resolve(base, tag(it, "controlURL")) },
            wanServiceType = wan?.let { tag(it, "serviceType") },
        )
    }

    fun soapEnvelope(serviceType: String, action: String, args: Map<String, String> = emptyMap()): String {
        val body = args.entries.joinToString("") { "<${it.key}>${it.value}</${it.key}>" }
        return "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$body</u:$action></s:Body></s:Envelope>"
    }

    data class PortMapping(
        val external: Int,
        val protocol: String,
        val internalClient: String,
        val internalPort: Int,
        val description: String,
        val enabled: Boolean,
        val leaseSeconds: Long,
    )

    fun parseExternalIp(xml: String): String? = tag(xml, "NewExternalIPAddress")

    fun parseMapping(xml: String): PortMapping? {
        val ext = tag(xml, "NewExternalPort")?.toIntOrNull() ?: return null
        return PortMapping(
            external = ext,
            protocol = tag(xml, "NewProtocol") ?: "?",
            internalClient = tag(xml, "NewInternalClient") ?: "?",
            internalPort = tag(xml, "NewInternalPort")?.toIntOrNull() ?: 0,
            description = tag(xml, "NewPortMappingDescription") ?: "",
            enabled = tag(xml, "NewEnabled") != "0",
            leaseSeconds = tag(xml, "NewLeaseDuration")?.toLongOrNull() ?: 0,
        )
    }
}

/** NetBIOS: имена Windows-компьютеров и их MAC-адрес (запрос состояния узла, UDP 137). */
object NetBios {
    fun nodeStatusRequest(id: Int = 0x4E53): ByteArray {
        val name = "CK" + "A".repeat(30) // «*» в кодировке NetBIOS
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xff)
        out.write(0); out.write(0)            // флаги
        out.write(0); out.write(1)            // 1 вопрос
        repeat(6) { out.write(0) }
        out.write(32); out.write(name.toByteArray(Charsets.US_ASCII)); out.write(0)
        out.write(0); out.write(0x21)         // NBSTAT
        out.write(0); out.write(1)            // IN
        return out.toByteArray()
    }

    data class Info(val name: String?, val group: String?, val mac: String?)

    fun parseNodeStatus(b: ByteArray, len: Int = b.size): Info? {
        if (len < 57) return null
        // имя в ответе: либо полное (34 байта), либо указатель (2 байта)
        val nameLen = if ((b[12].toInt() and 0xC0) == 0xC0) 2 else 34
        var p = 12 + nameLen + 10 // тип(2) класс(2) TTL(4) длина(2)
        if (p >= len) return null
        val count = b[p].toInt() and 0xff
        p++
        var name: String? = null
        var group: String? = null
        for (i in 0 until count) {
            if (p + 18 > len) return Info(name, group, null)
            val n = String(b, p, 15, Charsets.US_ASCII).trim()
            val suffix = b[p + 15].toInt() and 0xff
            val isGroup = (b[p + 16].toInt() and 0x80) != 0
            if (suffix == 0x00 && !isGroup && name == null) name = n
            if (suffix == 0x00 && isGroup && group == null) group = n
            p += 18
        }
        val mac = if (p + 6 <= len) (0 until 6).joinToString(":") { "%02X".format(b[p + it].toInt() and 0xff) }
            .takeIf { it != "00:00:00:00:00:00" } else null
        return Info(name, group, mac)
    }
}

/** ONVIF WS-Discovery: IP-камеры отвечают на мультикаст-запрос (UDP 3702). */
object WsDiscovery {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 3702

    fun probe(messageId: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
            "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
            "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<e:Header><w:MessageID>uuid:$messageId</w:MessageID>" +
            "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
            "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"

    data class Camera(val addresses: List<String>, val name: String?, val hardware: String?, val scopes: List<String>)

    fun parse(xml: String): Camera? {
        val xaddrs = Regex("<(?:\\w+:)?XAddrs>([^<]*)<", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            ?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        val scopes = Regex("<(?:\\w+:)?Scopes[^>]*>([^<]*)<", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            ?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        fun scope(key: String) = scopes.firstOrNull { "/$key/" in it }?.substringAfter("/$key/")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return Camera(xaddrs, scope("name"), scope("hardware"), scopes)
    }
}

/** Wake-on-LAN: «волшебный пакет» включает компьютер по сети. */
object WakeOnLan {
    fun parseMac(s: String): ByteArray? {
        val hex = s.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || !hex.all { it in "0123456789abcdefABCDEF" }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun formatMac(b: ByteArray): String = b.joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    /** 6 байт 0xFF + MAC, повторённый 16 раз = 102 байта. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(96) { mac[it % 6] }
    }

    /** Широковещательный адрес подсети: 192.168.1.42/24 → 192.168.1.255 */
    fun broadcast(ip: String, prefix: Int): String {
        val a = Subnet.parse(ip)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return Subnet.format(a or mask.inv())
    }
}

/** Заголовок веб-страницы: так видно «TP-Link», «Hikvision», «Synology» и т.д. */
object HtmlTitle {
    fun extract(html: String): String? = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.get(1)
        ?.let { Upnp.unescape(it).replace(Regex("\\s+"), " ").trim() }
        ?.takeIf { it.isNotEmpty() }
        ?.take(80)
}

/** Угадывание типа устройства по всему, что о нём известно. */
object DeviceKind {
    enum class Kind(val icon: String, val title: String) {
        ROUTER("⌂", "роутер"), CAMERA("◉", "камера"), PRINTER("⎙", "принтер"), TV("▣", "ТВ / медиа"),
        PC("▤", "компьютер"), PHONE("▯", "телефон / планшет"), NAS("▥", "хранилище"), SMART("◇", "умный дом"),
        CONSOLE("◈", "приставка"), UNKNOWN("·", "устройство"),
    }

    private val CAMERA = listOf("camera", "ipcam", "ip cam", "hikvision", "dahua", "nvr", "dvr", "webcam", "onvif", "ezviz", "imou", "reolink", "xiongmai", "netsurveillance")
    private val PRINTER = listOf("printer", "laserjet", "deskjet", "officejet", "epson", "canon", "brother", "kyocera", "ricoh", "xerox", "pantum")
    private val TV = listOf("mediarenderer", "tv", "bravia", "webos", "tizen", "chromecast", "android tv", "roku", "kodi", "dlna", "smart tv", "googlecast", "airplay", "receiver")
    private val NAS = listOf("synology", "qnap", "nas", "diskstation", "wd my cloud", "truenas", "openmediavault", "mediaserver")
    private val ROUTER = listOf("router", "gateway", "internetgatewaydevice", "keenetic", "tp-link", "tplink", "asus", "zyxel", "mikrotik", "routeros", "netgear", "d-link", "huawei hg", "openwrt", "luci", "tenda", "mercusys", "xiaomi router", "redmi router")
    private val SMART = listOf("hue", "yeelight", "tuya", "smartlife", "sonoff", "shelly", "espressif", "esp32", "esp8266", "tasmota", "homekit", "mi home", "aqara", "broadlink", "яндекс станция", "yandex station")
    private val CONSOLE = listOf("xbox", "playstation", "ps4", "ps5", "nintendo")
    private val PC = listOf("windows", "microsoft-ds", "workstation", "macbook", "imac", "ubuntu", "linux pc")
    private val PHONE = listOf("iphone", "ipad", "android", "galaxy", "redmi", "pixel")

    fun guess(gateway: Boolean, ports: Collection<Int>, vararg hints: String?): Kind {
        val text = hints.filterNotNull().joinToString(" ").lowercase()
        fun has(list: List<String>) = list.any { it in text }
        return when {
            gateway -> Kind.ROUTER
            554 in ports || 8554 in ports || 37777 in ports || 34567 in ports || has(CAMERA) -> Kind.CAMERA
            631 in ports || 9100 in ports || 515 in ports || has(PRINTER) -> Kind.PRINTER
            has(CONSOLE) -> Kind.CONSOLE
            8008 in ports || 8009 in ports || has(TV) -> Kind.TV
            has(NAS) || 5001 in ports -> Kind.NAS
            445 in ports || 139 in ports || 3389 in ports || has(PC) -> Kind.PC
            has(ROUTER) -> Kind.ROUTER // повторители и mesh-узлы
            1883 in ports || has(SMART) -> Kind.SMART
            62078 in ports || has(PHONE) -> Kind.PHONE
            else -> Kind.UNKNOWN
        }
    }

    /** Порты, характерные для камер и видеорегистраторов. */
    val CAMERA_PORTS = listOf(554, 8554, 80, 8000, 8080, 37777, 34567, 9000, 8899)
}
