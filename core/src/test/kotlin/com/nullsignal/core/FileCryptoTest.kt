package com.nullsignal.core

import com.nullsignal.core.spy.FileCrypto
import com.nullsignal.core.spy.FileHash
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class FileCryptoTest {
    private fun enc(data: ByteArray, pw: String, name: String, chunk: Int): ByteArray =
        ByteArrayOutputStream().also { FileCrypto.encrypt(ByteArrayInputStream(data), it, pw, name, chunk) }.toByteArray()

    private fun dec(blob: ByteArray, pw: String): Pair<String, ByteArray> {
        val out = ByteArrayOutputStream()
        val name = FileCrypto.decrypt(BufferedInputStream(ByteArrayInputStream(blob)), out, pw)
        return name to out.toByteArray()
    }

    @Test fun `roundtrip single and multi chunk`() {
        for (size in listOf(0, 5, 1000, 4096, 10_000)) {
            val data = Random(size).nextBytes(size)
            val blob = enc(data, "пароль", "фото_${size}.jpg", chunk = 1024)
            assertTrue(FileCrypto.isEncrypted(blob))
            val (name, back) = dec(blob, "пароль")
            assertEquals("фото_${size}.jpg", name)
            assertArrayEquals(data, back)
        }
    }

    @Test(expected = FileCrypto.WrongPassword::class)
    fun `wrong password`() {
        val blob = enc(ByteArray(100) { 1 }, "a", "x", 64)
        dec(blob, "b")
    }

    @Test(expected = FileCrypto.WrongPassword::class)
    fun `truncation is detected`() {
        val blob = enc(Random(1).nextBytes(5000), "a", "x", 1024)
        // отрезаем последний кусок целиком: предпоследний не помечен как последний → ошибка
        val cut = blob.copyOf(blob.size - (1024 + 16 + 4) / 2)
        dec(cut, "a")
    }

    @Test fun hashes() {
        val r = FileHash.of(ByteArrayInputStream("hello".toByteArray()))
        assertEquals(5, r.size)
        assertEquals("5d41402abc4b2a76b9719d911017c592", r.md5)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", r.sha256)
    }
}
