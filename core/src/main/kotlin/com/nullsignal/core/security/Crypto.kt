package com.nullsignal.core.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование заметок: AES-256-GCM, ключ из пароля через PBKDF2 (120 000 итераций).
 * Формат: ns1:base64(соль16 | iv12 | шифртекст+тег)
 */
object Crypto {
    private const val PREFIX = "ns1:"
    private const val ITERATIONS = 120_000

    fun encrypt(text: String, password: String, random: SecureRandom = SecureRandom()): String {
        require(password.isNotEmpty()) { "пустой пароль" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(salt + iv + ct)
    }

    /** null — неверный пароль или повреждённые данные (GCM это проверяет). */
    fun decrypt(blob: String, password: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(blob.trim().removePrefix(PREFIX))
        val salt = raw.copyOfRange(0, 16)
        val iv = raw.copyOfRange(16, 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        String(c.doFinal(raw.copyOfRange(28, raw.size)), Charsets.UTF_8)
    }.getOrNull()

    fun isEncrypted(s: String): Boolean = s.trim().startsWith(PREFIX)

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
