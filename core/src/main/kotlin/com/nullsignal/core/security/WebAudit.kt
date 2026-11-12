package com.nullsignal.core.security

import java.util.Base64

/** Проверка заголовков безопасности сайта (как securityheaders.com). */
object WebAudit {

    data class HeaderCheck(val header: String, val present: Boolean, val about: String)

    private val CHECKS = linkedMapOf(
        "strict-transport-security" to "HSTS: браузер всегда идёт по https",
        "content-security-policy" to "CSP: защита от внедрения чужих скриптов (XSS)",
        "x-frame-options" to "запрет встраивать сайт в чужую страницу (кликджекинг)",
        "x-content-type-options" to "браузер не угадывает тип файлов",
        "referrer-policy" to "не сливает адрес страницы другим сайтам",
        "permissions-policy" to "ограничение камеры/микрофона/геолокации для страницы",
    )

    fun check(headers: Map<String, String>): List<HeaderCheck> {
        val keys = headers.keys.map { it.lowercase() }.toSet()
        return CHECKS.map { (h, about) ->
            val present = h in keys || (h == "x-frame-options" && headers.entries.any {
                it.key.lowercase() == "content-security-policy" && "frame-ancestors" in it.value
            })
            HeaderCheck(h, present, about)
        }
    }

    fun grade(checks: List<HeaderCheck>): String = when (checks.count { it.present }) {
        6 -> "A+"
        5 -> "A"
        4 -> "B"
        3 -> "C"
        2 -> "D"
        else -> "F"
    }

    /** Заголовки, которые выдают версии ПО сервера. */
    fun leaks(headers: Map<String, String>): List<String> = headers.entries
        .filter { it.key.lowercase() in setOf("server", "x-powered-by", "x-aspnet-version") && it.value.any(Char::isDigit) }
        .map { "${it.key}: ${it.value} — версия ПО видна всем" }

    /** Декодирование JWT-токена (без проверки подписи) — для отладки своих приложений. */
    fun decodeJwt(token: String): Pair<String, String>? {
        val parts = token.trim().split('.')
        if (parts.size < 2) return null
        val d = Base64.getUrlDecoder()
        return runCatching {
            String(d.decode(parts[0].padBase64())) to String(d.decode(parts[1].padBase64()))
        }.getOrNull()
    }

    private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)
}
