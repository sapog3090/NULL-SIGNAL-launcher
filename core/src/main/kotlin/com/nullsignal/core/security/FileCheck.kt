package com.nullsignal.core.security

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.ln

/**
 * Проверка файла перед открытием: что это на самом деле (по содержимому, а не по расширению),
 * нет ли макросов, скриптов, двойных расширений и других приёмов, которыми маскируют вредоносные файлы.
 * Это признаки, а не приговор: чистый отчёт не гарантирует безопасность.
 */
object FileCheck {
    class Finding(val level: Int, val title: String, val detail: String) // 0 — к сведению, 1 — внимание, 2 — опасно

    class ZipItem(val name: String, val encrypted: Boolean, val size: Long, val packed: Long)

    class Report(
        val kind: String,
        val family: String,
        val size: Long,
        val md5: String,
        val sha1: String,
        val sha256: String,
        val entropy: Double,
        val findings: List<Finding>,
        val urls: List<String>,
        val ips: List<String>,
        val emails: List<String>,
        val entries: List<ZipItem>,
    ) {
        val level: Int get() = findings.maxOfOrNull { it.level } ?: 0
    }

    private val EXT_FAMILY = mapOf(
        "image" to "jpg jpeg png gif webp bmp heic heif ico tiff",
        "video" to "mp4 m4v mov avi mkv webm 3gp",
        "audio" to "mp3 wav ogg oga opus flac m4a aac",
        "doc" to "pdf doc docx docm xls xlsx xlsm ppt pptx pptm rtf odt ods txt csv epub",
        "archive" to "zip rar 7z gz tgz tar bz2 xz",
        "exe" to "apk exe dll scr com msi jar lnk bat cmd ps1 vbs vbe js jse wsf hta sh elf so dex pif cpl",
    ).flatMap { (family, exts) -> exts.split(' ').map { it to family } }.toMap()

    private val SCRIPT_EXT = setOf("bat", "cmd", "ps1", "vbs", "vbe", "js", "jse", "wsf", "hta", "sh")
    private val BIDI = setOf('\u202E', '\u202D', '\u202A', '\u202B', '\u2066', '\u2067', '\u2068', '\u2069', '\u200F', '\u200E')

    private fun starts(b: ByteArray, vararg sig: Int, at: Int = 0): Boolean {
        if (b.size < at + sig.size) return false
        for (i in sig.indices) if ((b[at + i].toInt() and 0xFF) != sig[i]) return false
        return true
    }

    private fun ascii(b: ByteArray, from: Int, len: Int): String =
        if (from < 0 || from >= b.size) "" else String(b, from, minOf(len, b.size - from), Charsets.ISO_8859_1)

    /** Тип файла по первым байтам: описание и семейство (image, video, audio, doc, archive, exe, text, data). */
    fun typeOf(b: ByteArray): Pair<String, String> = when {
        starts(b, 0x89, 0x50, 0x4E, 0x47) -> "картинка PNG" to "image"
        starts(b, 0xFF, 0xD8, 0xFF) -> "картинка JPEG" to "image"
        ascii(b, 0, 4) == "GIF8" -> "картинка GIF" to "image"
        ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" -> "картинка WebP" to "image"
        ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WAVE" -> "звук WAV" to "audio"
        ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "AVI " -> "видео AVI" to "video"
        ascii(b, 0, 5) == "%PDF-" -> "документ PDF" to "doc"
        starts(b, 0x50, 0x4B, 0x03, 0x04) || starts(b, 0x50, 0x4B, 0x05, 0x06) -> "архив ZIP" to "archive"
        ascii(b, 0, 4) == "Rar!" -> "архив RAR" to "archive"
        starts(b, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "архив 7z" to "archive"
        starts(b, 0x1F, 0x8B) -> "архив gzip" to "archive"
        ascii(b, 0, 3) == "BZh" -> "архив bzip2" to "archive"
        starts(b, 0xFD, 0x37, 0x7A, 0x58, 0x5A) -> "архив xz" to "archive"
        ascii(b, 257, 5) == "ustar" -> "архив tar" to "archive"
        starts(b, 0x7F, 0x45, 0x4C, 0x46) -> "программа Linux/Android (ELF)" to "exe"
        starts(b, 0x4D, 0x5A) && b.size > 64 -> "программа Windows (EXE/DLL)" to "exe"
        starts(b, 0xCF, 0xFA, 0xED, 0xFE) || starts(b, 0xFE, 0xED, 0xFA, 0xCF) || starts(b, 0xFE, 0xED, 0xFA, 0xCE) -> "программа macOS" to "exe"
        ascii(b, 0, 4) == "dex\n" -> "код приложения Android (DEX)" to "exe"
        starts(b, 0xD0, 0xCF, 0x11, 0xE0) -> "документ старого Office или установщик (OLE)" to "doc"
        ascii(b, 0, 5) == "{\\rtf" -> "документ RTF" to "doc"
        starts(b, 0x4C, 0x00, 0x00, 0x00, 0x01, 0x14, 0x02, 0x00) -> "ярлык Windows (LNK)" to "exe"
        ascii(b, 0, 3) == "ID3" || starts(b, 0xFF, 0xFB) || starts(b, 0xFF, 0xF3) -> "звук MP3" to "audio"
        ascii(b, 0, 4) == "OggS" -> "звук OGG" to "audio"
        ascii(b, 0, 4) == "fLaC" -> "звук FLAC" to "audio"
        ascii(b, 4, 4) == "ftyp" -> when (ascii(b, 8, 4)) {
            "heic", "heix", "mif1", "avif" -> "картинка HEIC/AVIF" to "image"
            "M4A " -> "звук M4A" to "audio"
            else -> "видео MP4/MOV" to "video"
        }
        starts(b, 0x1A, 0x45, 0xDF, 0xA3) -> "видео MKV/WebM" to "video"
        ascii(b, 0, 15) == "SQLite format 3" -> "база данных SQLite" to "data"
        ascii(b, 0, 2) == "BM" && b.size > 26 -> "картинка BMP" to "image"
        else -> textKind(b)
    }

    private fun textKind(b: ByteArray): Pair<String, String> {
        val n = minOf(b.size, 4096)
        if (n == 0) return "пустой файл" to "data"
        var printable = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 0x80) printable++
        }
        if (printable < n * 97 / 100) return "неизвестный формат" to "data"
        val t = ascii(b, 0, n).trimStart('\uFEFF', 'ï', '»', '¿', ' ', '\n', '\r', '\t').lowercase()
        return when {
            t.startsWith("#!") -> "скрипт" to "exe"
            t.startsWith("<?php") -> "скрипт PHP" to "exe"
            t.startsWith("<!doctype html") || t.startsWith("<html") || "<body" in t -> "веб-страница HTML" to "text"
            t.startsWith("<svg") || (t.startsWith("<?xml") && "<svg" in t) -> "картинка SVG (это текст с разметкой)" to "text"
            t.startsWith("<?xml") -> "файл XML" to "text"
            else -> "текст" to "text"
        }
    }

    /** Энтропия Шеннона: 0 — одни и те же байты, 8 — случайные данные (сжатие или шифрование). */
    fun entropy(b: ByteArray, n: Int = b.size): Double {
        if (n <= 0) return 0.0
        val counts = IntArray(256)
        for (i in 0 until n) counts[b[i].toInt() and 0xFF]++
        var e = 0.0
        for (c in counts) if (c > 0) { val p = c.toDouble() / n; e -= p * ln(p) / ln(2.0) }
        return e
    }

    /** Печатные строки из двоичных данных: обычные и «широкие» (UTF-16, как в программах Windows). */
    fun strings(b: ByteArray, min: Int = 6, limit: Int = 20_000): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.length >= min && out.size < limit) out.add(sb.toString()); sb.setLength(0) }
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar()) else flush()
        }
        flush()
        var i = 0
        while (i + 1 < b.size) {
            val c = b[i].toInt() and 0xFF
            if (c in 32..126 && b[i + 1].toInt() == 0) { sb.append(c.toChar()); i += 2 } else { flush(); i++ }
        }
        flush()
        return out
    }

    private val URL_RE = Regex("https?://[A-Za-z0-9._~%-]+(?::\\d+)?(?:/[A-Za-z0-9._~%!$&'()*+,;=:@/?#-]*)?")
    private val IP_RE = Regex("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])")
    private val MAIL_RE = Regex("[A-Za-z0-9._%+-]{2,}@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** Каталог ZIP читается с конца файла — сами файлы не распаковываются. */
    fun zipEntries(file: File, limit: Int = 20_000): List<ZipItem> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val tailLen = minOf(len, 66_000L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen)
            raf.readFully(tail)
            var p = tailLen - 22
            while (p >= 0 && !starts(tail, 0x50, 0x4B, 0x05, 0x06, at = p)) p--
            if (p < 0) return@use emptyList<ZipItem>()
            fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
            val count = u16(tail, p + 10)
            val cdSize = u32(tail, p + 12)
            val cdOffset = u32(tail, p + 16)
            if (cdSize <= 0 || cdSize > 64L * 1024 * 1024 || cdOffset + cdSize > len) return@use emptyList<ZipItem>()
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)
            val out = ArrayList<ZipItem>()
            var o = 0
            while (o + 46 <= cd.size && out.size < minOf(count, limit) && starts(cd, 0x50, 0x4B, 0x01, 0x02, at = o)) {
                val flags = u16(cd, o + 8)
                val packed = u32(cd, o + 20)
                val size = u32(cd, o + 24)
                val nameLen = u16(cd, o + 28)
                val extraLen = u16(cd, o + 30)
                val commentLen = u16(cd, o + 32)
                if (o + 46 + nameLen > cd.size) break
                val name = String(cd, o + 46, nameLen, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
                out.add(ZipItem(name, flags and 1 != 0, size, packed))
                o += 46 + nameLen + extraLen + commentLen
            }
            out
        }
    }.getOrDefault(emptyList())

    private fun ext(name: String): String = name.substringAfterLast('.', "").trim().lowercase()

    private fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun wide(s: String) = ByteArray(s.length * 2) { if (it % 2 == 0) s[it / 2].code.toByte() else 0 }

    /** Полная проверка файла. [name] — имя, под которым файл показали пользователю. */
    fun scan(file: File, name: String, headLimit: Int = 2 * 1024 * 1024): Report {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val headOut = java.io.ByteArrayOutputStream()
        var size = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n)
                if (headOut.size() < headLimit) headOut.write(buf, 0, minOf(n, headLimit - headOut.size()))
                size += n
            }
        }
        val head = headOut.toByteArray()
        var (kind, family) = typeOf(head)
        val f = ArrayList<Finding>()
        val e = ext(name)
        val low = String(head, Charsets.ISO_8859_1).lowercase()
        var entries: List<ZipItem> = emptyList()

        // ---------- имя ----------
        if (name.any { it in BIDI }) {
            f += Finding(2, "В имени спрятан управляющий символ", "Он переворачивает часть имени: так «cod.exe» выглядит как «exe.doc». Настоящее расширение: .$e")
        }
        val parts = name.split('.').map { it.trim().lowercase() }
        if (parts.size >= 3 && EXT_FAMILY[parts.last()] == "exe" && EXT_FAMILY[parts[parts.size - 2]].let { it != null && it != "exe" && it != "archive" }) {
            f += Finding(2, "Двойное расширение: .${parts[parts.size - 2]}.${parts.last()}", "Файл выглядит как документ или картинка, но на самом деле это программа. Классический приём рассылок.")
        }
        if (Regex("\\s{4,}\\.[A-Za-z0-9]{2,4}$").containsMatchIn(name)) {
            f += Finding(2, "Расширение отодвинуто пробелами", "Настоящее расширение .$e спрятано за длинным рядом пробелов, чтобы его не было видно в списке файлов.")
        }

        // ---------- архивы и всё, что на них построено ----------
        if (kind == "архив ZIP") {
            entries = zipEntries(file)
            val names = entries.map { it.name }
            when {
                "AndroidManifest.xml" in names && names.any { it.endsWith(".dex") } -> { kind = "приложение Android (APK)"; family = "exe" }
                "word/document.xml" in names -> { kind = "документ Word"; family = "doc" }
                "xl/workbook.xml" in names -> { kind = "таблица Excel"; family = "doc" }
                "ppt/presentation.xml" in names -> { kind = "презентация PowerPoint"; family = "doc" }
                "mimetype" in names && names.any { it.endsWith(".opf") } -> { kind = "книга EPUB"; family = "doc" }
                names.any { it.endsWith(".class") } && "META-INF/MANIFEST.MF" in names -> { kind = "программа Java (JAR)"; family = "exe" }
            }
            if (names.any { it.endsWith("vbaProject.bin", ignoreCase = true) }) {
                f += Finding(2, "В документе есть макросы", "Макрос — это программа внутри документа. Если Office попросит «Включить содержимое» — не соглашайся, пока не уверен в отправителе.")
            }
            if (family == "doc" && names.any { "oleObject" in it || "/embeddings/" in it }) {
                f += Finding(1, "В документ встроены другие файлы", "Вложенный объект может оказаться программой: не открывай его двойным щелчком.")
            }
            if (names.any { it.contains("../") || it.startsWith("/") || it.contains("..\\") }) {
                f += Finding(2, "Архив пытается выйти из своей папки", "В именах файлов есть «../»: при распаковке они могут записаться поверх чужих файлов.")
            }
            if (family == "archive") {
                val inner = names.filter { EXT_FAMILY[ext(it)] == "exe" }
                if (inner.isNotEmpty()) {
                    f += Finding(1, "Внутри архива программы: ${inner.size}", inner.take(5).joinToString("\n") + "\nЗапускай только то, что ждал получить.")
                }
                if (entries.isNotEmpty() && entries.count { it.encrypted } * 2 > entries.size) {
                    f += Finding(1, "Архив под паролем", "Антивирус и почта не видят, что внутри. Если пароль пришёл в том же письме — это типичная схема рассылки вредоносных файлов.")
                }
                val total = entries.sumOf { it.size }
                if (total > 1024L * 1024 * 1024 && size > 0 && total / size > 200) {
                    f += Finding(2, "Похоже на «zip-бомбу»", "После распаковки архив займёт ${total / (1024 * 1024)} МБ при размере ${size / 1024} КБ и может забить память.")
                }
            }
        }

        if (e == "msi" && kind.startsWith("документ старого Office")) { kind = "установщик Windows (MSI)"; family = "exe" }

        // ---------- расширение против содержимого ----------
        val expected = EXT_FAMILY[e]
        // контейнер MP4 бывает и видео, и звуком (m4a), и картинкой (heic) — это не подмена
        val container = kind == "видео MP4/MOV" && (expected == "audio" || expected == "image")
        if (expected != null && family != "data" && expected != family && !container && !(family == "text" && e in SCRIPT_EXT) && !(e == "txt" || e == "csv")) {
            if (family == "exe") {
                f += Finding(2, "Файл притворяется: .$e, а внутри $kind", "По имени это безобидный файл, по содержимому — программа. Не открывай.")
            } else if (!(expected == "doc" && family == "archive") && !(expected == "exe" && family == "archive") && !(expected == "exe" && family == "text")) {
                f += Finding(1, "Расширение .$e не совпадает с содержимым", "На самом деле это $kind. Иногда так бывает по ошибке, иногда — чтобы обойти фильтры.")
            }
        }
        if (family == "exe" || e in SCRIPT_EXT) {
            f += Finding(
                1, if (kind.startsWith("приложение Android")) "Установочный файл Android" else "Это программа или скрипт",
                "Такой файл выполняет команды на устройстве. Открывай, только если знаешь, откуда он и зачем.",
            )
        }

        // ---------- документы ----------
        if (kind == "документ PDF") {
            if ("/javascript" in low || Regex("/js[\\s(<\\[]").containsMatchIn(low)) f += Finding(2, "В PDF есть JavaScript", "Обычным документам скрипты не нужны. Открывай в браузере, а не в старом Adobe Reader.")
            if ("/launch" in low) f += Finding(2, "PDF пытается запустить программу", "Действие /Launch запускает внешний файл при открытии.")
            if ("/openaction" in low || Regex("/aa[\\s<\\[]").containsMatchIn(low)) f += Finding(1, "Действие при открытии", "PDF что-то делает сам, как только его открыли: переход по ссылке, скрипт или отправка формы.")
            if ("/embeddedfile" in low) f += Finding(1, "В PDF вложен другой файл", "Вложение может быть программой.")
            if ("/encrypt" in low) f += Finding(0, "PDF защищён паролем или ограничениями", "")
        }
        if (kind.startsWith("документ старого Office")) {
            if (contains(head, wide("_VBA_PROJECT")) || contains(head, wide("Macros")) || "vba" in low && "thisdocument" in low) {
                f += Finding(2, "В документе есть макросы", "Старые .doc/.xls с макросами — самый частый носитель вредоносного кода. Не нажимай «Включить содержимое».")
            }
        }
        if (kind == "документ RTF" && ("\\objdata" in low || "\\objupdate" in low)) {
            f += Finding(2, "В RTF встроен объект", "Встроенные OLE-объекты в RTF почти не встречаются в обычных документах и часто используются для заражения.")
        }
        if (family == "text" || e in SCRIPT_EXT) {
            if ("<script" in low && (kind.startsWith("картинка SVG") || kind.startsWith("веб-страница"))) {
                f += Finding(1, "Внутри есть скрипт", "Страница или SVG выполнит код в браузере, когда её откроют.")
            }
            if ("<form" in low && Regex("type\\s*=\\s*[\"']?password").containsMatchIn(low)) {
                f += Finding(1, "Форма для ввода пароля", "Локальный файл, который просит пароль, — типичная фишинговая страница. Настоящие сайты так не присылают.")
            }
            if (listOf("eval(", "atob(", "fromcharcode", "unescape(").count { it in low } >= 2) {
                f += Finding(1, "Код намеренно запутан", "Сочетание eval / atob / fromCharCode прячет, что скрипт делает на самом деле.")
            }
            val fetch = listOf("downloadstring", "invoke-webrequest", "invoke-expression", "iex ", "certutil", "bitsadmin", "curl ", "wget ", "-encodedcommand", " -enc ", "frombase64string", "base64 -d", "mshta", "wscript.shell")
                .filter { it in low }
            if (fetch.size >= 2 || (fetch.isNotEmpty() && e in SCRIPT_EXT)) {
                f += Finding(2, "Скрипт скачивает или запускает сторонний код", "Найдено: " + fetch.take(6).joinToString { it.trim() })
            }
        }

        // ---------- содержимое ----------
        val ent = entropy(head, minOf(head.size, 1024 * 1024))
        if (family == "exe" && !kind.startsWith("приложение Android") && !kind.startsWith("программа Java") && ent > 7.2 && head.size > 4096) {
            f += Finding(1, "Программа упакована или зашифрована", "Энтропия %.1f из 8: содержимое намеренно скрыто от анализа. Так делают и защищённые программы, и вредоносные.".format(ent))
        }
        if (family == "text" && ent > 6.0 && head.size > 4096) {
            f += Finding(0, "Внутри текста — закодированные данные", "Энтропия %.1f из 8: в файле длинные вставки Base64 или hex.".format(ent))
        }
        val strs = if (family == "image" || family == "video" || family == "audio") emptyList() else strings(head)
        val joined = strs.joinToString("\n")
        val urls = URL_RE.findAll(joined).map { it.value.trimEnd('.', ',', ')', ';', '\'') }
            .filter { u -> listOf("schemas.openxmlformats.org", "schemas.microsoft.com", "www.w3.org", "purl.org", "ns.adobe.com", "schemas.android.com", "xml.org", "apache.org/licenses").none { it in u } }
            .distinct().take(40).toList()
        val ips = IP_RE.findAll(joined).filter { m -> m.groupValues.drop(1).all { it.toInt() <= 255 } && m.groupValues[1].toInt() != 0 }
            .map { it.value }.filter { !it.startsWith("1.0.") && !it.startsWith("2.0.") && it != "127.0.0.1" }.distinct().take(20).toList()
        val emails = MAIL_RE.findAll(joined).map { it.value }.distinct().take(20).toList()
        if (family != "exe" && family != "archive" && urls.any { Regex("https?://\\d{1,3}(\\.\\d{1,3}){3}").containsMatchIn(it) }) {
            f += Finding(1, "Ссылки на голые IP-адреса", "Обычные сайты ссылаются по имени. Адрес цифрами в документе — повод насторожиться.")
        }
        if (f.none { it.level > 0 }) f.add(0, Finding(0, "Явных признаков опасности нет", "Проверены имя, тип, вложения и содержимое. Это не гарантия: новые угрозы так не ловятся."))
        return Report(kind, family, size, hex(md5.digest()), hex(sha1.digest()), hex(sha256.digest()), ent, f.sortedByDescending { it.level }, urls, ips, emails, entries)
    }
}
