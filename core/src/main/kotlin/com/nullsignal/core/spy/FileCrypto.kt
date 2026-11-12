package com.nullsignal.core.spy

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование файлов любого размера: AES-256-GCM кусками по 1 МБ.
 * Каждый кусок со своим nonce (номер + флаг «последний»), поэтому подмена,
 * перестановка или обрезка файла обнаруживаются. Имя файла тоже зашифровано.
 *
 * Формат: "NSF2" | соль 16 | база nonce 8 | { длина 4 | шифртекст+тег }…
 */
object FileCrypto {
    private val MAGIC = "NSF2".toByteArray()
    const val CHUNK = 1 shl 20
    private const val ITERATIONS = 120_000

    class WrongPassword : Exception("неверный пароль или файл повреждён")

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded, "AES")
    }

    private fun nonce(base: ByteArray, index: Int, last: Boolean): ByteArray {
        val n = base.copyOf(12)
        val v = index or if (last) Int.MIN_VALUE else 0
        n[8] = (v ushr 24).toByte(); n[9] = (v ushr 16).toByte(); n[10] = (v ushr 8).toByte(); n[11] = v.toByte()
        return n
    }

    /** Читает до [size] байт (меньше — только в конце потока). */
    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var n = 0
        while (n < size) {
            val r = input.read(buf, n, size - n)
            if (r < 0) break
            n += r
        }
        return if (n == size) buf else buf.copyOf(n)
    }

    fun encrypt(input: InputStream, output: OutputStream, password: String, fileName: String, chunk: Int = CHUNK, random: SecureRandom = SecureRandom()) {
        require(password.isNotEmpty()) { "пустой пароль" }
        val salt = ByteArray(16).also(random::nextBytes)
        val base = ByteArray(8).also(random::nextBytes)
        val k = key(password, salt)
        val out = DataOutputStream(output)
        out.write(MAGIC); out.write(salt); out.write(base)
        val name = fileName.toByteArray(Charsets.UTF_8).let { if (it.size > 1024) it.copyOf(1024) else it }
        // первый кусок начинается с имени файла
        val head = byteArrayOf((name.size ushr 8).toByte(), name.size.toByte()) + name
        var pending = head + readFully(input, chunk)
        var index = 0
        while (true) {
            val next = readFully(input, chunk)
            val last = next.isEmpty()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(base, index, last)))
            val ct = c.doFinal(pending)
            out.writeInt(ct.size)
            out.write(ct)
            if (last) break
            pending = next
            index++
        }
        out.flush()
    }

    /** Возвращает исходное имя файла. */
    fun decrypt(input: InputStream, output: OutputStream, password: String): String {
        val inp = DataInputStream(if (input.markSupported()) input else java.io.BufferedInputStream(input))
        val magic = ByteArray(4)
        try { inp.readFully(magic) } catch (e: EOFException) { throw IllegalArgumentException("это не зашифрованный файл") }
        require(magic.contentEquals(MAGIC)) { "это не зашифрованный файл NULL//SIGNAL" }
        val salt = ByteArray(16).also { inp.readFully(it) }
        val base = ByteArray(8).also { inp.readFully(it) }
        val k = key(password, salt)
        var index = 0
        var name: String? = null
        while (true) {
            val len = try { inp.readInt() } catch (e: EOFException) { throw WrongPassword() } // оборвался до последнего куска
            require(len in 16..(CHUNK + 1024 + 2 + 16)) { "файл повреждён" }
            val ct = ByteArray(len)
            try { inp.readFully(ct) } catch (e: EOFException) { throw WrongPassword() } // файл обрезан
            // последний кусок узнаём по тому, что дальше поток пуст
            inp.mark(1)
            val last = inp.read() < 0
            if (!last) inp.reset()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(base, index, last)))
            val plain = try { c.doFinal(ct) } catch (e: Exception) { throw WrongPassword() }
            var data = plain
            if (index == 0) {
                val nl = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
                name = String(plain, 2, nl, Charsets.UTF_8)
                data = plain.copyOfRange(2 + nl, plain.size)
            }
            output.write(data)
            if (last) break
            index++
        }
        output.flush()
        return name ?: "file"
    }

    fun isEncrypted(head: ByteArray): Boolean = head.size >= 4 && head.copyOf(4).contentEquals(MAGIC)
}

/** Хеши файла одним проходом. */
object FileHash {
    data class Result(val size: Long, val md5: String, val sha1: String, val sha256: String)

    fun of(input: InputStream): Result {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md5.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n)
            size += n
        }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        return Result(size, hex(md5.digest()), hex(sha1.digest()), hex(sha256.digest()))
    }
}
