package com.nullsignal.core.dns

/**
 * Список блокировки доменов. Понимает форматы:
 * hosts («0.0.0.0 ads.example.com»), AdBlock («||ads.example.com^»), простой список доменов, «*.example.com».
 * Блокируется сам домен и все его поддомены.
 */
class Blocklist(domains: Collection<String> = emptyList()) {
    private val set: HashSet<String> = HashSet(domains.size * 2)

    init { domains.forEach { add(it) } }

    val size: Int get() = set.size

    fun add(raw: String): Boolean {
        val d = normalize(raw) ?: return false
        return set.add(d)
    }

    fun remove(raw: String): Boolean = normalize(raw)?.let { set.remove(it) } ?: false

    fun contains(domain: String): Boolean = match(domain) != null

    /** Какое правило сработало (сам домен или родительский), либо null. */
    fun match(domain: String): String? {
        var d = domain.lowercase().trimEnd('.')
        while (true) {
            if (d in set) return d
            val dot = d.indexOf('.')
            if (dot < 0) return null
            d = d.substring(dot + 1)
        }
    }

    fun all(): List<String> = set.sorted()

    companion object {
        private val DOMAIN = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")

        fun normalize(raw: String): String? {
            var s = raw.trim().lowercase()
            if (s.isEmpty() || s.startsWith('#') || s.startsWith('!') || s.startsWith('[')) return null
            s = s.substringBefore('#').trim()
            // hosts: «0.0.0.0 domain» / «127.0.0.1 domain»
            val parts = s.split(Regex("\\s+"))
            if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1")) s = parts[1]
            else if (parts.size > 1) return null
            // AdBlock: «||domain^» (с модификаторами после $ не связываемся)
            if (s.startsWith("||")) {
                if ('$' in s) return null
                s = s.removePrefix("||").removeSuffix("^").removeSuffix("|")
            }
            s = s.removePrefix("*.").removePrefix(".").removePrefix("http://").removePrefix("https://").substringBefore('/')
            if (s in SKIP || !DOMAIN.matches(s)) return null
            return s
        }

        /** Разбор целого файла списка. */
        fun parse(text: String): List<String> = text.lineSequence().mapNotNull { normalize(it) }.distinct().toList()
    }
}

/** Публичные списки блокировки, которые можно скачать одной кнопкой. */
object BlocklistSources {
    data class Source(val id: String, val title: String, val url: String, val about: String)

    val ALL = listOf(
        Source("stevenblack", "StevenBlack (реклама + вредоносы)", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", "~150 000 доменов, самый популярный"),
        Source("adaway", "AdAway", "https://adaway.org/hosts.txt", "реклама в приложениях, ~7 000"),
        Source("oisd-small", "OISD small", "https://small.oisd.nl/domainswild", "аккуратный список без ложных блокировок"),
        Source("hagezi-light", "HaGeZi Light", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt", "трекеры и реклама, лёгкий"),
        Source("ru-adlist", "RU AdList (домены)", "https://raw.githubusercontent.com/easylist/ruadlist/master/advblock/adservers.txt", "российская реклама и счётчики"),
    )
}

/** DNS-серверы, через которые можно отправлять запросы. */
object Upstreams {
    data class Upstream(val id: String, val title: String, val ip: String, val doh: String?)

    val ALL = listOf(
        Upstream("cloudflare", "Cloudflare 1.1.1.1", "1.1.1.1", "https://cloudflare-dns.com/dns-query"),
        Upstream("google", "Google 8.8.8.8", "8.8.8.8", "https://dns.google/dns-query"),
        Upstream("quad9", "Quad9 (блокирует вредоносы)", "9.9.9.9", "https://dns.quad9.net/dns-query"),
        Upstream("adguard", "AdGuard (блокирует рекламу)", "94.140.14.14", "https://dns.adguard-dns.com/dns-query"),
        Upstream("yandex", "Яндекс DNS", "77.88.8.8", null),
    )

    fun byId(id: String?): Upstream = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
