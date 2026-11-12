package com.nullsignal.core.spy

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Записка для звукового канала: открытая или под паролем.
 * Под паролем — AES-256-GCM, ключ из пароля через PBKDF2 (соль 8 байт, nonce 12, тег 16).
 */
object SoundNote {
    private const val PLAIN: Byte = 0x00
    private const val SEALED: Byte = 0x5A
    private const val SALT = 8
    private const val IV = 12
    private const val TAG = 16
    private const val ITERATIONS = 40_000

    /** Сколько байт текста влезет в один кадр. */
    fun capacity(sealed: Boolean): Int = SoundModem.MAX_PAYLOAD - 1 - if (sealed) SALT + IV + TAG else 0

    fun plain(text: String): ByteArray = byteArrayOf(PLAIN) + text.toByteArray(Charsets.UTF_8)

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    fun seal(text: String, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT).also { random.nextBytes(it) }
        val iv = ByteArray(IV).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG * 8, iv))
        c.updateAAD(byteArrayOf(SEALED))
        return byteArrayOf(SEALED) + salt + iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    fun isSealed(data: ByteArray): Boolean = data.isNotEmpty() && data[0] == SEALED

    sealed interface Opened {
        data class Text(val text: String, val sealed: Boolean) : Opened
        data object NeedPassword : Opened
        data object WrongPassword : Opened
        data object Unknown : Opened
    }

    fun open(data: ByteArray, password: String? = null): Opened {
        if (data.isEmpty()) return Opened.Unknown
        return when (data[0]) {
            PLAIN -> Opened.Text(String(data, 1, data.size - 1, Charsets.UTF_8), sealed = false)
            SEALED -> {
                if (data.size < 1 + SALT + IV + TAG) return Opened.Unknown
                if (password.isNullOrEmpty()) return Opened.NeedPassword
                runCatching {
                    val salt = data.copyOfRange(1, 1 + SALT)
                    val iv = data.copyOfRange(1 + SALT, 1 + SALT + IV)
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG * 8, iv))
                    c.updateAAD(byteArrayOf(SEALED))
                    String(c.doFinal(data, 1 + SALT + IV, data.size - 1 - SALT - IV), Charsets.UTF_8)
                }.fold({ Opened.Text(it, sealed = true) }, { Opened.WrongPassword })
            }
            else -> Opened.Unknown
        }
    }
}
