package com.nullsignal.core.security

/** Проверка сети на перехват: вердикты по фактам, которые собрал телефон. */
object Mitm {
    /** Удостоверяющие центры, которыми подписаны крупные сайты. Если цепочку подписал кто-то другой — это перехват HTTPS. */
    private val TRUSTED = listOf(
        "Google Trust Services", "DigiCert", "Let's Encrypt", "ISRG", "GlobalSign", "Sectigo", "COMODO", "USERTrust",
        "Amazon", "Cloudflare", "Microsoft", "GoDaddy", "Starfield", "Entrust", "Baltimore", "Certum", "Buypass",
        "IdenTrust", "Thawte", "GeoTrust", "RapidSSL", "QuoVadis", "SSL.com", "Actalis", "HARICA", "SwissSign", "Telia",
        "Certainly", "ZeroSSL", "Apple", "Microsec",
    )

    fun issuerOrg(dn: String): String? = Regex("(?:^|,\\s*)O=(\"[^\"]*\"|[^,]+)").find(dn)?.groupValues?.get(1)?.trim('"')
    fun commonName(dn: String): String? = Regex("(?:^|,\\s*)CN=(\"[^\"]*\"|[^,]+)").find(dn)?.groupValues?.get(1)?.trim('"')

    fun trustedIssuer(dn: String): Boolean = TRUSTED.any { dn.contains(it, ignoreCase = true) }

    enum class HttpVerdict { CLEAN, INJECTED, REPLACED, FAILED }

    /** http://example.com: в оригинале нет скриптов и есть «Example Domain». */
    fun checkExample(code: Int, body: String?): HttpVerdict = when {
        body == null || code <= 0 -> HttpVerdict.FAILED
        !body.contains("Example Domain") -> HttpVerdict.REPLACED
        body.contains("<script", ignoreCase = true) || body.contains("<iframe", ignoreCase = true) -> HttpVerdict.INJECTED
        else -> HttpVerdict.CLEAN
    }

    /** Случайный домен, которого точно нет, — для проверки подмены ответов «домен не найден». */
    fun randomDomain(random: java.util.Random = java.util.Random()): String {
        val abc = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "ns-" + (1..16).map { abc[random.nextInt(abc.length)] }.joinToString("") + ".com"
    }

    data class Finding(val level: Int, val title: String, val detail: String) // 0 — ок, 1 — внимание, 2 — опасно

    fun overall(f: List<Finding>): Int = f.maxOfOrNull { it.level } ?: 0
}
