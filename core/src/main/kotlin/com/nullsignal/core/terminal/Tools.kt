package com.nullsignal.core.terminal

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Небольшие, но настоящие крипто-утилиты. */
object Tools {
    private val ALGS = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")

    fun hash(alg: String, text: String): String? {
        val name = ALGS[alg.lowercase()] ?: return null
        return MessageDigest.getInstance(name).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun b64encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    fun b64decode(text: String): String? =
        runCatching { String(Base64.getDecoder().decode(text.trim())) }.getOrNull()

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*-_=+?"

    /** Пароль из SecureRandom: минимум по одному символу каждого класса. */
    fun password(length: Int = 20, random: SecureRandom = SecureRandom()): String {
        require(length >= 4)
        val all = LOWER + UPPER + DIGITS + SYMBOLS
        val chars = mutableListOf(
            LOWER[random.nextInt(LOWER.length)], UPPER[random.nextInt(UPPER.length)],
            DIGITS[random.nextInt(DIGITS.length)], SYMBOLS[random.nextInt(SYMBOLS.length)],
        )
        repeat(length - 4) { chars += all[random.nextInt(all.length)] }
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return chars.joinToString("")
    }

    /** Примерная дистанция по силе BLE-сигнала (модель затухания в свободном пространстве). */
    fun distanceMeters(rssi: Int, txPower: Int = -59, n: Double = 2.5): Double =
        Math.pow(10.0, (txPower - rssi) / (10 * n))

    /** Тип защиты Wi-Fi по строке capabilities из ScanResult. */
    fun wifiSecurity(capabilities: String): String = when {
        "SAE" in capabilities -> "WPA3"
        "WPA2" in capabilities || "RSN" in capabilities -> "WPA2"
        "WPA" in capabilities -> "WPA"
        "WEP" in capabilities -> "WEP"
        "OWE" in capabilities -> "OWE"
        else -> "OPEN"
    }

    /** Канал Wi-Fi по частоте в МГц. */
    fun wifiChannel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }
}
