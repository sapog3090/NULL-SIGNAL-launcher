package com.nullsignal.core.games

import java.util.Base64

/** Классические шифры и кодировки. Всё работает в обе стороны. */
object Ciphers {
    private const val LAT = "abcdefghijklmnopqrstuvwxyz"
    private const val CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"

    private fun shiftIn(c: Char, alphabet: String, shift: Int): Char? {
        val i = alphabet.indexOf(c.lowercaseChar())
        if (i < 0) return null
        val r = alphabet[((i + shift) % alphabet.length + alphabet.length) % alphabet.length]
        return if (c.isUpperCase()) r.uppercaseChar() else r
    }

    /** Шифр Цезаря: сдвиг букв (латиница и кириллица). */
    fun caesar(s: String, shift: Int): String =
        s.map { shiftIn(it, LAT, shift) ?: shiftIn(it, CYR, shift) ?: it }.joinToString("")

    fun rot13(s: String): String = s.map { shiftIn(it, LAT, 13) ?: it }.joinToString("")

    /** Атбаш: a↔z, b↔y … */
    fun atbash(s: String): String = s.map { c ->
        val l = LAT.indexOf(c.lowercaseChar())
        val k = CYR.indexOf(c.lowercaseChar())
        val r = when {
            l >= 0 -> LAT[LAT.length - 1 - l]
            k >= 0 -> CYR[CYR.length - 1 - k]
            else -> return@map c
        }
        if (c.isUpperCase()) r.uppercaseChar() else r
    }.joinToString("")

    fun hexEncode(s: String): String = s.toByteArray().joinToString(" ") { "%02x".format(it) }
    fun hexDecode(s: String): String? = runCatching {
        val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
        require(clean.length % 2 == 0)
        String(clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }.getOrNull()

    fun binEncode(s: String): String = s.toByteArray().joinToString(" ") { (it.toInt() and 0xff).toString(2).padStart(8, '0') }
    fun binDecode(s: String): String? = runCatching {
        String(s.trim().split(Regex("\\s+")).map { it.toInt(2).toByte() }.toByteArray())
    }.getOrNull()

    fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    /** Перебор всех сдвигов Цезаря — так ломают этот шифр. */
    fun caesarBrute(s: String, max: Int = 32): List<String> = (1..max).map { "${it.toString().padStart(2)}: ${caesar(s, -it)}" }
}

/** Игра «Расшифруй перехват»: уровни от простого к сложному. */
object CipherGame {
    data class Level(val title: String, val hint: String, val encoded: String, val answer: String)

    val levels: List<Level> = listOf(
        Level("зеркало", "текст записан задом наперёд", "сигнал принят".reversed(), "сигнал принят"),
        Level("base64", "base64: латиница, цифры, + / и = в конце. в терминале: b64 dec …", Ciphers.b64("встреча в полночь"), "встреча в полночь"),
        Level("hex", "каждый байт — две hex-цифры. в терминале: hex dec …", Ciphers.hexEncode("helix"), "helix"),
        Level("цезарь", "русские буквы сдвинуты на 3 вперёд. в терминале: caesar -3 …", Ciphers.caesar("ворон летит", 3), "ворон летит"),
        Level("rot13", "латиница сдвинута на 13. в терминале: rot13 …", Ciphers.rot13("null signal"), "null signal"),
        Level("двоичный", "восемь бит — один символ", Ciphers.binEncode("key"), "key"),
        Level("атбаш", "алфавит перевёрнут: a↔z, b↔y", Ciphers.atbash("transparent city"), "transparent city"),
        Level("цезарь ?", "сдвиг неизвестен. перебери все: caesar brute …", Ciphers.caesar("сервер в подвале", 11), "сервер в подвале"),
        Level("двойной", "сначала base64, потом rot13", Ciphers.b64(Ciphers.rot13("zero day")), "zero day"),
    )

    fun check(level: Level, answer: String): Boolean =
        answer.trim().lowercase().replace(Regex("\\s+"), " ").replace('ё', 'е') == level.answer.replace('ё', 'е')
}
