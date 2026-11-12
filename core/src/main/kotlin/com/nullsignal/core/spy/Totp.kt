package com.nullsignal.core.spy

import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Base32 (RFC 4648) — в нём хранятся секреты двухфакторной аутентификации. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(s: String): ByteArray {
        val clean = s.uppercase().filter { it != '=' && it != '-' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "недопустимый символ base32: $c" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun isValid(s: String): Boolean = runCatching { decode(s).isNotEmpty() }.getOrDefault(false)
}

/**
 * Одноразовые коды TOTP (RFC 6238) — то же, что Google Authenticator.
 * Код = HMAC(секрет, номер 30-секундного окна), обрезанный до 6 цифр.
 */
object Totp {

    data class Account(
        val issuer: String,
        val name: String,
        val secret: String,
        val digits: Int = 6,
        val period: Int = 30,
        val algorithm: String = "SHA1",
    ) {
        val label: String get() = if (issuer.isNotEmpty()) "$issuer · $name" else name
    }

    fun code(secret: ByteArray, timeSec: Long, period: Int = 30, digits: Int = 6, algorithm: String = "SHA1"): String {
        val counter = timeSec / period
        val msg = ByteArray(8) { i -> (counter ushr (8 * (7 - i))).toByte() }
        val alg = "Hmac" + algorithm.uppercase().replace("-", "")
        val mac = Mac.getInstance(alg)
        mac.init(SecretKeySpec(secret, alg))
        val h = mac.doFinal(msg)
        val o = h.last().toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    fun code(a: Account, timeSec: Long): String = code(Base32.decode(a.secret), timeSec, a.period, a.digits, a.algorithm)

    fun secondsLeft(timeSec: Long, period: Int = 30): Int = (period - timeSec % period).toInt()

    /** Разбор otpauth://totp/Issuer:name?secret=...&issuer=...&digits=6&period=30 (так кодируют QR сайтов). */
    fun parseUri(uri: String): Account? = runCatching {
        val u = uri.trim()
        if (!u.startsWith("otpauth://totp/", ignoreCase = true)) return null
        val rest = u.substring("otpauth://totp/".length)
        val label = URLDecoder.decode(rest.substringBefore('?'), "UTF-8")
        val params = rest.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            it.substringBefore('=').lowercase() to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val secret = params["secret"]?.replace(" ", "")?.uppercase() ?: return null
        if (!Base32.isValid(secret)) return null
        val issuer = params["issuer"] ?: label.substringBefore(':', "")
        val name = label.substringAfter(':').trim()
        Account(
            issuer = issuer.trim(),
            name = name.ifEmpty { issuer },
            secret = secret,
            digits = params["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: 6,
            period = params["period"]?.toIntOrNull()?.takeIf { it in 10..120 } ?: 30,
            algorithm = (params["algorithm"] ?: "SHA1").uppercase(),
        )
    }.getOrNull()

    /** Счета хранятся строками: issuer \t name \t secret \t digits \t period \t algorithm */
    fun serialize(a: Account): String = listOf(a.issuer, a.name, a.secret, a.digits, a.period, a.algorithm).joinToString("\t")

    fun deserialize(s: String): Account? {
        val p = s.split('\t')
        if (p.size < 3) return null
        return Account(p[0], p[1], p[2], p.getOrNull(3)?.toIntOrNull() ?: 6, p.getOrNull(4)?.toIntOrNull() ?: 30, p.getOrNull(5) ?: "SHA1")
    }
}
