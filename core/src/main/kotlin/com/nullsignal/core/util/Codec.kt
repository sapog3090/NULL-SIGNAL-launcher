package com.nullsignal.core.util

import com.nullsignal.core.games.Ciphers
import com.nullsignal.core.spy.Morse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

/**
 * Преобразования данных: кодировки (Base64, hex, URL, двоичный код…), сжатие, хеши
 * и автоопределение — «во что это закодировано».
 */
object Codec {
    class Bad(message: String) : Exception(message)

    class Op(val id: String, val title: String, val group: String, val apply: (ByteArray) -> ByteArray)

    private const val MAX_OUT = 2_000_000
    private val HEX = "0123456789abcdef".toCharArray()

    private fun text(b: ByteArray) = String(b, Charsets.UTF_8)
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    /** Строгая проверка: это правильный UTF-8? */
    fun utf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (e: Exception) {
        null
    }

    fun toHex(b: ByteArray, sep: String = ""): String {
        val sb = StringBuilder(b.size * (2 + sep.length))
        for ((i, x) in b.withIndex()) {
            if (i > 0) sb.append(sep)
            val v = x.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    fun fromHex(s: String): ByteArray {
        val h = s.replace("0x", "").replace("0X", "").replace("\\x", "").filter { !it.isWhitespace() && it != ':' && it != ',' && it != '-' }
        if (h.isEmpty() || h.length % 2 != 0 || h.any { it.digitToIntOrNull(16) == null }) throw Bad("это не hex: нужны пары цифр 0–9 и букв a–f")
        return ByteArray(h.length / 2) { ((h[it * 2].digitToInt(16) shl 4) or h[it * 2 + 1].digitToInt(16)).toByte() }
    }

    fun fromBase64(s: String): ByteArray {
        var t = s.filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        if (t.isEmpty() || t.any { !(it.isLetterOrDigit() && it.code < 128) && it != '+' && it != '/' } || t.length % 4 == 1) {
            throw Bad("это не Base64: в нём только латинские буквы, цифры, + и /")
        }
        while (t.length % 4 != 0) t += "="
        return try { Base64.getDecoder().decode(t) } catch (e: IllegalArgumentException) { throw Bad("это не Base64") }
    }

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun toBase32(b: ByteArray): String {
        val sb = StringBuilder()
        var buf = 0
        var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) { sb.append(B32[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    fun fromBase32(s: String): ByteArray {
        val t = s.filter { !it.isWhitespace() }.trimEnd('=').uppercase()
        if (t.isEmpty() || t.any { it !in B32 }) throw Bad("это не Base32: в нём только буквы A–Z и цифры 2–7")
        val out = ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in t) {
            buf = (buf shl 5) or B32.indexOf(c)
            bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }

    private fun fromBin(s: String): ByteArray {
        val t = s.filter { !it.isWhitespace() }
        if (t.isEmpty() || t.length % 8 != 0 || t.any { it != '0' && it != '1' }) throw Bad("это не двоичный код: нужны группы по 8 нулей и единиц")
        return ByteArray(t.length / 8) { t.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }

    private fun fromDec(s: String): ByteArray {
        val nums = s.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: throw Bad("это не список чисел") }
        if (nums.isEmpty() || nums.any { it < 0 || it > 0x10FFFF }) throw Bad("коды символов должны быть от 0 до 1114111")
        return bytes(buildString { nums.forEach { appendCodePoint(it) } })
    }

    private fun urlDecode(s: String): String {
        if (!Regex("%[0-9a-fA-F]{2}").containsMatchIn(s) && '+' !in s) throw Bad("здесь нет URL-кодирования (%XX)")
        return try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { throw Bad("битое URL-кодирование") }
    }

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    private fun htmlDecode(s: String): String {
        var hit = false
        val r = Regex("&(#x[0-9a-fA-F]+|#\\d+|\\w+);").replace(s) { m ->
            val e = m.groupValues[1]
            val v = when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it.coerceIn(0, 0x10FFFF))) }
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it.coerceIn(0, 0x10FFFF))) }
                else -> ENTITIES[e]
            }
            if (v != null) hit = true
            v ?: m.value
        }
        if (!hit) throw Bad("здесь нет HTML-сущностей (&amp; &#65;)")
        return r
    }

    private fun unicodeDecode(s: String): String {
        var hit = false
        val r = Regex("\\\\u([0-9a-fA-F]{4})|\\\\x([0-9a-fA-F]{2})").replace(s) { m ->
            hit = true
            (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt(16).toChar().toString()
        }
        if (!hit) throw Bad("здесь нет \\uXXXX или \\xNN")
        return r
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_OUT) throw Bad("после распаковки слишком много данных")
        }
        return out.toByteArray()
    }

    private fun hash(algo: String): (ByteArray) -> ByteArray = { b -> bytes(toHex(MessageDigest.getInstance(algo).digest(b))) }

    private fun rot47(s: String) = buildString { for (c in s) append(if (c.code in 33..126) (33 + (c.code - 33 + 47) % 94).toChar() else c) }

    /** Разбор JWT: заголовок и содержимое (подпись не проверяется — для этого нужен ключ сервера). */
    fun jwt(token: String): String {
        val parts = token.trim().split('.')
        if (parts.size != 3) throw Bad("JWT — это три части через точку")
        val header = utf8(runCatching { fromBase64(parts[0]) }.getOrNull() ?: throw Bad("первая часть — не Base64")) ?: throw Bad("заголовок — не текст")
        val payload = utf8(runCatching { fromBase64(parts[1]) }.getOrNull() ?: throw Bad("вторая часть — не Base64")) ?: throw Bad("содержимое — не текст")
        if (!header.trimStart().startsWith("{") || !payload.trimStart().startsWith("{")) throw Bad("внутри не JSON")
        val pretty = { s: String -> runCatching { DevTools.jsonPretty(s) }.getOrDefault(s) }
        val notes = ArrayList<String>()
        if (Regex("\"alg\"\\s*:\\s*\"none\"", RegexOption.IGNORE_CASE).containsMatchIn(header)) notes += "! алгоритм none: токен без подписи — сервер не должен такие принимать"
        Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toLongOrNull()?.let { exp ->
            notes += (if (exp * 1000 < System.currentTimeMillis()) "срок действия истёк: " else "действует до: ") + DevTools.unixToDate(exp)
        } ?: run { notes += "! нет срока действия (exp): токен вечный" }
        notes += "содержимое JWT не зашифровано — его может прочитать любой, у кого есть токен"
        return "ЗАГОЛОВОК\n" + pretty(header) + "\n\nСОДЕРЖИМОЕ\n" + pretty(payload) + "\n\n" + notes.joinToString("\n")
    }

    val ops: List<Op> = listOf(
        Op("b64", "Base64", "закодировать") { bytes(Base64.getEncoder().encodeToString(it)) },
        Op("hex", "hex", "закодировать") { bytes(toHex(it, " ")) },
        Op("url", "URL", "закодировать") { bytes(java.net.URLEncoder.encode(text(it), "UTF-8")) },
        Op("bin", "биты", "закодировать") { b -> bytes(b.joinToString(" ") { (it.toInt() and 0xFF).toString(2).padStart(8, '0') }) },
        Op("dec", "коды символов", "закодировать") { b -> bytes(text(b).codePoints().toArray().joinToString(" ")) },
        Op("b32", "Base32", "закодировать") { bytes(toBase32(it)) },
        Op("morse", "Морзе", "закодировать") { bytes(Morse.encode(text(it))) },
        Op("uni", "\\uXXXX", "закодировать") { b -> bytes(text(b).map { if (it.code < 128) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")) },
        Op("gzip", "gzip → Base64", "закодировать") { b ->
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(b) }
            bytes(Base64.getEncoder().encodeToString(out.toByteArray()))
        },

        Op("unb64", "из Base64", "раскодировать") { fromBase64(text(it)) },
        Op("unhex", "из hex", "раскодировать") { fromHex(text(it)) },
        Op("unurl", "из URL", "раскодировать") { bytes(urlDecode(text(it))) },
        Op("unbin", "из битов", "раскодировать") { fromBin(text(it)) },
        Op("undec", "из кодов символов", "раскодировать") { fromDec(text(it)) },
        Op("unb32", "из Base32", "раскодировать") { fromBase32(text(it)) },
        Op("unmorse", "из Морзе", "раскодировать") { b ->
            val s = text(b).trim()
            if (s.isEmpty() || s.any { it != '.' && it != '-' && it != '/' && it != ' ' && it != '·' && it != '−' }) throw Bad("в коде Морзе только точки, тире, пробелы и /")
            bytes(Morse.decode(s.replace('·', '.').replace('−', '-'), cyrillic = false))
        },
        Op("unhtml", "из HTML-сущностей", "раскодировать") { bytes(htmlDecode(text(it))) },
        Op("ununi", "из \\uXXXX", "раскодировать") { bytes(unicodeDecode(text(it))) },
        Op("ungzip", "распаковать gzip", "раскодировать") { b ->
            if (b.size < 3 || b[0] != 0x1f.toByte() || b[1] != 0x8b.toByte()) throw Bad("это не gzip")
            try { readAll(GZIPInputStream(ByteArrayInputStream(b))) } catch (e: java.io.IOException) { throw Bad("gzip повреждён") }
        },
        Op("unzlib", "распаковать zlib", "раскодировать") { b ->
            if (b.size < 3 || b[0] != 0x78.toByte()) throw Bad("это не zlib")
            try { readAll(InflaterInputStream(ByteArrayInputStream(b))) } catch (e: java.io.IOException) { throw Bad("zlib повреждён") }
        },
        Op("jwt", "разобрать JWT", "раскодировать") { bytes(jwt(text(it))) },
        Op("json", "JSON красиво", "раскодировать") { b -> bytes(runCatching { DevTools.jsonPretty(text(b)) }.getOrElse { throw Bad("это не JSON") }) },

        Op("rot13", "ROT13", "шифры-перевёртыши") { bytes(Ciphers.rot13(text(it))) },
        Op("rot47", "ROT47", "шифры-перевёртыши") { bytes(rot47(text(it))) },
        Op("atbash", "Атбаш", "шифры-перевёртыши") { bytes(Ciphers.atbash(text(it))) },
        Op("reverse", "задом наперёд", "шифры-перевёртыши") { bytes(text(it).reversed()) },
        Op("upper", "ЗАГЛАВНЫМИ", "шифры-перевёртыши") { bytes(text(it).uppercase()) },
        Op("lower", "строчными", "шифры-перевёртыши") { bytes(text(it).lowercase()) },

        Op("md5", "MD5", "хеши") { hash("MD5")(it) },
        Op("sha1", "SHA-1", "хеши") { hash("SHA-1")(it) },
        Op("sha256", "SHA-256", "хеши") { hash("SHA-256")(it) },
        Op("sha512", "SHA-512", "хеши") { hash("SHA-512")(it) },
        Op("crc32", "CRC32", "хеши") { b -> bytes(CRC32().also { it.update(b) }.value.toString(16).padStart(8, '0')) },
    )

    fun op(id: String): Op = ops.first { it.id == id }

    /** Результат шага: текст, если это текст, иначе hex-дамп. */
    fun show(b: ByteArray): String = utf8(b)?.takeIf { s -> s.none { it.code < 9 || (it.code in 14..31) } } ?: ("[не текст, ${b.size} байт] " + toHex(b.copyOf(minOf(b.size, 4096)), " "))

    // ---------- автоопределение ----------

    private val WORDS = setOf(
        "the", "and", "you", "that", "this", "with", "for", "are", "not", "have", "from", "password", "secret", "key", "flag", "hello",
        "http", "https", "www", "com", "user", "admin", "login", "token", "data", "name", "file", "error", "true", "false", "null",
        "это", "что", "как", "для", "привет", "пароль", "ключ", "код", "они", "если", "или", "его", "все", "был", "она", "так", "уже",
        "нет", "есть", "меня", "когда", "только", "встреча", "сигнал", "адрес", "завтра", "сегодня", "здесь", "после", "сообщение",
    )

    /** Насколько строка похожа на осмысленный текст: 0 — мусор, больше — лучше. */
    fun readability(s: String): Double {
        if (s.isEmpty()) return 0.0
        var printable = 0
        var letters = 0
        var spaces = 0
        for (c in s) {
            if (c == '\n' || c == '\t' || c == '\r' || (c.code in 32..126) || c.isLetter()) printable++
            if (c.isLetter()) letters++
            if (c == ' ') spaces++
        }
        if (printable < s.length) return 0.0
        return letters.toDouble() / s.length + minOf(0.5, spaces * 4.0 / s.length) + wordHits(s) * 0.8
    }

    /** Сколько в строке знакомых слов — по ним отличаем настоящий текст от случайного набора букв. */
    fun wordHits(s: String): Int = s.lowercase().split(Regex("[^\\p{L}]+")).count { it in WORDS }

    class Finding(val steps: List<String>, val text: String, val score: Double)

    private val DETECTORS = listOf("unb64", "unhex", "unbin", "undec", "unurl", "unmorse", "unhtml", "ununi", "unb32", "ungzip", "unzlib")
    private val GUESSES = listOf("rot13", "atbash", "reverse", "rot47")

    /** Пробует раскодировать разными способами, в том числе несколько раз подряд, и возвращает осмысленные варианты. */
    fun detect(input: String, maxDepth: Int = 4): List<Finding> {
        val start = input.trim()
        if (start.isEmpty()) return emptyList()
        val found = ArrayList<Finding>()
        val seen = HashSet<String>()
        fun walk(data: ByteArray, steps: List<String>, depth: Int) {
            if (depth >= maxDepth || found.size > 40) return
            val asText = utf8(data)
            for (id in DETECTORS) {
                val o = op(id)
                if (asText == null && id != "ungzip" && id != "unzlib") continue // текстовые кодировки — только для текста
                // одно-два числа — это просто числа, а не коды символов
                if (id == "undec" && asText!!.trim().split(Regex("[\\s,;]+")).size < 3) continue
                val out = try { o.apply(data) } catch (e: Exception) { continue }
                if (out.isEmpty() || out.contentEquals(data)) continue
                val t = utf8(out)
                val chain = steps + o.title
                val key = id + ":" + toHex(out.copyOf(minOf(out.size, 64)))
                if (!seen.add(key)) continue
                if (t != null) {
                    val score = readability(t)
                    // «1234» — формально hex, но текста внутри нет: такие варианты не показываем
                    if (score > 0.55 && t.any { it.isLetter() }) found += Finding(chain, t, score + chain.size * 0.3)
                }
                walk(out, chain, depth + 1)
            }
            if (asText != null) {
                for (id in GUESSES) {
                    val t = text(op(id).apply(data))
                    // перевёртыши «подходят» к любому тексту, поэтому верим им, только если появились знакомые слова
                    val hits = wordHits(t)
                    if (hits > 0 && hits > wordHits(asText) && seen.add(id + ":" + t.take(64))) found += Finding(steps + op(id).title, t, readability(t))
                }
            }
        }
        walk(bytes(start), emptyList(), 0)
        if (start.count { it == '.' } == 2) runCatching { found += Finding(listOf("JWT"), jwt(start), 5.0) }
        return found.sortedByDescending { it.score }.distinctBy { it.text }.take(6)
    }

    /** На что похожа строка, если это хеш. Хеш нельзя «раскодировать» — он односторонний. */
    fun hashKind(s: String): String? {
        val t = s.trim()
        val hex = t.isNotEmpty() && t.all { it.digitToIntOrNull(16) != null }
        return when {
            t.startsWith("$2a$") || t.startsWith("$2b$") || t.startsWith("$2y$") -> "bcrypt — хеш пароля с солью"
            t.startsWith("\$argon2") -> "Argon2 — современный хеш пароля"
            t.startsWith("$6$") -> "sha512crypt — хеш пароля в Linux"
            t.startsWith("$5$") -> "sha256crypt — хеш пароля в Linux"
            t.startsWith("$1$") -> "md5crypt — устаревший хеш пароля"
            hex && t.length == 32 -> "MD5 (32 hex-символа) — устаревший хеш"
            hex && t.length == 40 -> "SHA-1 (40 hex-символов)"
            hex && t.length == 56 -> "SHA-224"
            hex && t.length == 64 -> "SHA-256 (64 hex-символа)"
            hex && t.length == 96 -> "SHA-384"
            hex && t.length == 128 -> "SHA-512 (128 hex-символов)"
            hex && t.length == 8 -> "CRC32 — контрольная сумма, не защита"
            else -> null
        }
    }
}
