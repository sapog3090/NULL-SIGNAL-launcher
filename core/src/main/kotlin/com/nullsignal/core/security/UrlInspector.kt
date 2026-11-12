package com.nullsignal.core.security

import java.net.URI

/** Проверка ссылки до перехода: фишинг, подмена букв, сокращатели. */
object UrlInspector {

    data class Report(val input: String, val host: String?, val warnings: List<String>) {
        val safeLooking get() = warnings.isEmpty()
    }

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "cutt.ly", "is.gd", "clck.ru", "vk.cc", "ow.ly", "rb.gy", "shorturl.at",
    )

    /** Ключевое слово бренда → официальные домены. */
    private val BRANDS = mapOf(
        "steam" to setOf("steampowered.com", "steamcommunity.com", "steamstatic.com"),
        "gosuslugi" to setOf("gosuslugi.ru"),
        "sberbank" to setOf("sberbank.ru", "sber.ru", "sberbank.com"),
        "tinkoff" to setOf("tinkoff.ru", "tbank.ru"),
        "vk" to setOf("vk.com", "vk.ru", "vk.me", "vk.cc", "userapi.com"),
        "telegram" to setOf("telegram.org", "t.me", "telegram.me"),
        "google" to setOf("google.com", "google.ru", "googleapis.com", "youtube.com"),
        "apple" to setOf("apple.com", "icloud.com"),
        "paypal" to setOf("paypal.com"),
        "roblox" to setOf("roblox.com"),
        "epicgames" to setOf("epicgames.com"),
        "discord" to setOf("discord.com", "discord.gg", "discordapp.com"),
        "microsoft" to setOf("microsoft.com", "live.com", "office.com"),
    )

    fun registeredDomain(host: String): String = host.split('.').takeLast(2).joinToString(".")

    fun inspect(raw: String): Report {
        val input = raw.trim()
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)) input else "http://$input"
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return Report(input, null, listOf("не похоже на ссылку"))
        val w = mutableListOf<String>()
        val scheme = uri.scheme?.lowercase()
        val authority = uri.rawAuthority ?: ""
        val host = (uri.host ?: authority.substringAfterLast('@').substringBefore(':')).lowercase()
        if (host.isEmpty()) return Report(input, null, listOf("в ссылке нет адреса сайта"))
        if (scheme == "http") w += "нет шифрования (http, а не https): данные видны по пути"
        if ('@' in authority) w += "символ @ в адресе: настоящий сайт — то, что после @ ($host)"
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) w += "вместо имени сайта — голый IP-адрес"
        if ("xn--" in host) w += "punycode (xn--): имя может маскироваться под другой сайт"
        if (host.any { it.code > 127 }) w += "в адресе не-латинские буквы: «а» может быть кириллической (подмена)"
        if (host in SHORTENERS) w += "сокращённая ссылка: куда она ведёт — скрыто"
        if (host.count { it == '.' } >= 4) w += "очень длинное имя: настоящий домен — ${registeredDomain(host)}"
        if (host.count { it == '-' } >= 3) w += "много дефисов в имени — частый признак фишинга"
        val reg = registeredDomain(host)
        val plain = host.replace("-", "").replace(".", "")
        for ((brand, official) in BRANDS) {
            if (brand in plain && reg !in official) {
                w += "похоже на $brand, но домен $reg — не официальный (${official.first()})"
                break
            }
        }
        return Report(input, host, w)
    }

    /** QR с Wi-Fi: WIFI:T:WPA;S:имя;P:пароль;; */
    fun parseWifiQr(s: String): Map<String, String>? {
        if (!s.startsWith("WIFI:", ignoreCase = true)) return null
        return s.substring(5).split(Regex("(?<!\\\\);")).mapNotNull {
            val k = it.substringBefore(':', "")
            if (k.isEmpty()) null else k to it.substringAfter(':').replace("\\;", ";")
        }.toMap()
    }
}
