package com.nullsignal.core.security

import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

/** Оценка надёжности пароля (как в менеджерах паролей). Пароль никуда не отправляется. */
object PasswordStrength {

    data class Report(
        val entropyBits: Double,
        val crackSeconds: Double,
        val verdict: String,
        val level: Int, // 0..4
        val issues: List<String>,
    )

    private val COMMON = setOf(
        "123456", "password", "qwerty", "123456789", "12345678", "111111", "1234567", "qwerty123",
        "1q2w3e4r", "000000", "iloveyou", "admin", "йцукен", "123123", "654321", "abc123", "password1",
        "qwertyuiop", "1234", "12345", "1q2w3e", "666666", "777777", "121212", "zxcvbnm", "dragon",
        "monkey", "football", "minecraft", "pass", "parol", "пароль", "1111", "0000", "qwe123",
    )

    private val SEQUENCES = listOf(
        "abcdefghijklmnopqrstuvwxyz", "0123456789", "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю", "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
    )

    /** Скорость перебора: мощная видеокарта против быстрого хеша. */
    const val GUESSES_PER_SECOND = 1e10

    fun check(p: String): Report {
        var pool = 0
        if (p.any { it in 'a'..'z' }) pool += 26
        if (p.any { it in 'A'..'Z' }) pool += 26
        if (p.any { it.isDigit() }) pool += 10
        if (p.any { !it.isLetterOrDigit() && it.code < 128 }) pool += 33
        if (p.any { it.code >= 128 }) pool += 66
        var entropy = if (pool == 0) 0.0 else p.length * log2(pool.toDouble())
        val issues = mutableListOf<String>()
        if (p.lowercase() in COMMON) {
            entropy = min(entropy, 4.0)
            issues += "есть в списках самых популярных паролей — подбирается первым"
        }
        if (p.length < 8) issues += "короче 8 символов"
        if (p.length >= 8 && p.length < 12) issues += "лучше 12+ символов"
        if (p.isNotEmpty() && p.all { it.isDigit() }) issues += "только цифры"
        if (p.isNotEmpty() && p.all { it.isLetter() }) issues += "только буквы — добавь цифры и символы"
        if (Regex("(.)\\1{2,}").containsMatchIn(p)) { issues += "повторы одного символа"; entropy *= 0.8 }
        if (hasSequence(p)) { issues += "последовательности вроде abc, 123, qwe"; entropy *= 0.75 }
        if (Regex("(19|20)\\d\\d").containsMatchIn(p)) issues += "похоже на год — его легко угадать"
        val seconds = 2.0.pow(entropy) / 2 / GUESSES_PER_SECOND
        val level = when {
            entropy < 28 -> 0
            entropy < 40 -> 1
            entropy < 60 -> 2
            entropy < 80 -> 3
            else -> 4
        }
        val verdict = listOf("ОЧЕНЬ СЛАБЫЙ", "СЛАБЫЙ", "СРЕДНИЙ", "НАДЁЖНЫЙ", "ОЧЕНЬ НАДЁЖНЫЙ")[level]
        return Report(entropy, seconds, verdict, level, issues)
    }

    fun hasSequence(p: String): Boolean {
        val s = p.lowercase()
        if (s.length < 3) return false
        for (i in 0..s.length - 3) {
            val w = s.substring(i, i + 3)
            if (SEQUENCES.any { w in it || w in it.reversed() }) return true
        }
        return false
    }

    fun humanTime(s: Double): String = when {
        s < 1 -> "мгновенно"
        s < 60 -> "${s.toInt()} сек"
        s < 3600 -> "${(s / 60).toInt()} мин"
        s < 86400 -> "${(s / 3600).toInt()} ч"
        s < 31_536_000 -> "${(s / 86400).toInt()} дн"
        s < 3.15e9 -> "${(s / 31_536_000).toLong()} лет"
        else -> "больше 100 лет"
    }
}
