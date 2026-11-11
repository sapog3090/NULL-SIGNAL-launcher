package com.nullsignal.core.net

/** IPv4-математика для сканера локальной сети. */
object Subnet {

    fun parse(ip: String): Int {
        val parts = ip.trim().split('.')
        require(parts.size == 4) { "Не IPv4: $ip" }
        return parts.fold(0) { acc, s ->
            val b = s.toInt()
            require(b in 0..255) { "Не IPv4: $ip" }
            (acc shl 8) or b
        }
    }

    fun format(ip: Int): String = "${ip ushr 24 and 255}.${ip ushr 16 and 255}.${ip ushr 8 and 255}.${ip and 255}"

    /** Только частные адреса: 10/8, 172.16/12, 192.168/16. Публичные сети сканер не трогает. */
    fun isPrivate(ip: String): Boolean {
        val a = parse(ip)
        val b1 = a ushr 24 and 255
        val b2 = a ushr 16 and 255
        return b1 == 10 || (b1 == 172 && b2 in 16..31) || (b1 == 192 && b2 == 168)
    }

    /**
     * Адреса хостов подсети, кроме своего. Подсети шире /24 урезаются до своей /24,
     * чтобы скан занимал секунды, а не часы.
     */
    fun hosts(ip: String, prefix: Int): List<String> {
        require(prefix in 0..32)
        val p = prefix.coerceAtLeast(24)
        val self = parse(ip)
        if (p >= 31) return emptyList()
        val mask = (-1L shl (32 - p)).toInt()
        val network = self and mask
        val broadcast = network or mask.inv()
        return ((network + 1) until broadcast).filter { it != self }.map(::format)
    }

    /** Известные порты: что обычно на них живёт. */
    val COMMON_PORTS: Map<Int, String> = linkedMapOf(
        21 to "ftp", 22 to "ssh", 23 to "telnet", 53 to "dns", 80 to "http",
        139 to "netbios", 443 to "https", 445 to "smb", 548 to "afp", 554 to "rtsp/камера",
        631 to "принтер", 1883 to "mqtt", 3389 to "rdp", 5000 to "upnp",
        5555 to "adb", 8008 to "chromecast", 8080 to "http-alt", 8443 to "https-alt",
        9100 to "принтер", 62078 to "iphone-sync",
    )
}
