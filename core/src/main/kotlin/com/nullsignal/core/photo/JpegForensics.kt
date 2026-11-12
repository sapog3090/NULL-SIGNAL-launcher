package com.nullsignal.core.photo

/**
 * Криминалистика JPEG без декодирования картинки: разбор служебных блоков файла.
 * Качество сжатия — по таблицам квантования (как у IJG libjpeg), следы редакторов — по APP-блокам и XMP.
 */
object JpegForensics {
    /** Стандартная таблица яркости IJG (качество 50). */
    private val STD_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
    )

    private val EDITORS = listOf(
        "Photoshop" to "Adobe Photoshop", "Lightroom" to "Adobe Lightroom", "GIMP" to "GIMP", "Snapseed" to "Snapseed",
        "PicsArt" to "PicsArt", "Meitu" to "Meitu", "FaceApp" to "FaceApp", "Facetune" to "Facetune", "Pixelmator" to "Pixelmator",
        "Affinity" to "Affinity Photo", "Paint.NET" to "Paint.NET", "Canva" to "Canva", "VSCO" to "VSCO", "Polarr" to "Polarr",
        "Luminar" to "Luminar", "Capture One" to "Capture One", "Photopea" to "Photopea", "Fotor" to "Fotor",
        "Remini" to "Remini", "Lensa" to "Lensa", "gd-jpeg" to "библиотека gd (сайт пересжал)",
    )

    data class Report(
        /** Оценка качества сжатия 1..100 (null — таблиц нет). */
        val quality: Int?,
        /** Таблицы совпадают со стандартными IJG — так сохраняют libjpeg, редакторы и сайты; у камер телефонов свои. */
        val standardTables: Boolean,
        val progressive: Boolean,
        val hasExif: Boolean,
        val hasXmp: Boolean,
        /** Найденные следы программ-редакторов. */
        val editors: List<String>,
        /** Служебные блоки по порядку: APP0 JFIF, APP1 Exif, DQT… */
        val segments: List<String>,
        val width: Int?,
        val height: Int?,
    )

    fun isJpeg(b: ByteArray): Boolean = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    /** Качество IJG, чья таблица ближе всего к данной (порядок значений не важен: DQT хранит их зигзагом). */
    fun estimateQuality(luma: IntArray): Int {
        val sorted = luma.sorted()
        return (1..100).minByOrNull { q ->
            val t = ijgTable(q).sorted()
            var d = 0L
            for (k in 0 until 64) d += kotlin.math.abs(t[k] - sorted[k])
            d
        } ?: 50
    }

    /** Таблица IJG для качества [q] (в том же порядке, что [STD_LUMA]). */
    fun ijgTable(q: Int): IntArray {
        val s = if (q < 50) 5000 / q.coerceAtLeast(1) else 200 - q * 2
        return IntArray(64) { ((STD_LUMA[it] * s + 50) / 100).coerceIn(1, 255) }
    }

    private fun ascii(b: ByteArray, from: Int, len: Int): String =
        String(CharArray(len.coerceAtMost(b.size - from).coerceAtLeast(0)) { i -> (b[from + i].toInt() and 0xFF).toChar() })

    fun analyze(b: ByteArray): Report? {
        if (!isJpeg(b)) return null
        val segs = mutableListOf<String>()
        val editors = linkedSetOf<String>()
        var luma: IntArray? = null
        var progressive = false
        var exif = false
        var xmp = false
        var w: Int? = null
        var h: Int? = null
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xFF) { i++; continue }
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            if (m == 0xD9) break
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2) break
            val p = i + 4
            val end = (i + 2 + len).coerceAtMost(b.size)
            when (m) {
                0xE0 -> segs += "APP0 " + ascii(b, p, 4).trimEnd('\u0000')
                0xE1 -> {
                    val head = ascii(b, p, 29)
                    when {
                        head.startsWith("Exif") -> { exif = true; segs += "APP1 Exif" }
                        head.startsWith("http://ns.adobe.com/xap/1.0/") -> {
                            xmp = true
                            segs += "APP1 XMP"
                            val text = String(b, p, end - p, Charsets.UTF_8)
                            Regex("CreatorTool(?:=\"|>)([^\"<]+)").find(text)?.groupValues?.get(1)?.let { tool ->
                                editors += EDITORS.firstOrNull { tool.contains(it.first, ignoreCase = true) }?.second ?: "XMP: $tool"
                            }
                            if (text.contains("photoshop:", ignoreCase = true)) editors += "Adobe Photoshop"
                            if (text.contains("<stEvt:action>saved", ignoreCase = true) || text.contains("stEvt:action=\"saved\"", ignoreCase = true)) {
                                editors += "история правок в XMP (stEvt)"
                            }
                        }
                        else -> segs += "APP1"
                    }
                }
                0xED -> {
                    segs += "APP13 " + ascii(b, p, 13).trimEnd('\u0000')
                    if (ascii(b, p, 13).startsWith("Photoshop")) editors += "Adobe Photoshop"
                }
                0xEE -> {
                    segs += "APP14 " + ascii(b, p, 5)
                    if (ascii(b, p, 5) == "Adobe") editors += "Adobe (блок APP14)"
                }
                0xFE -> {
                    val c = String(b, p, end - p, Charsets.ISO_8859_1).trim()
                    segs += "COM «${c.take(40)}»"
                    EDITORS.firstOrNull { c.contains(it.first, ignoreCase = true) }?.let { editors += it.second }
                }
                0xDB -> {
                    segs += "DQT"
                    var q = p
                    while (q < end) {
                        val pq = (b[q].toInt() and 0xFF) shr 4
                        val tq = b[q].toInt() and 0x0F
                        val size = if (pq == 0) 64 else 128
                        if (q + 1 + size > end) break
                        if (tq == 0 && luma == null) {
                            luma = IntArray(64) { k ->
                                if (pq == 0) b[q + 1 + k].toInt() and 0xFF
                                else ((b[q + 1 + 2 * k].toInt() and 0xFF) shl 8) or (b[q + 2 + 2 * k].toInt() and 0xFF)
                            }
                        }
                        q += 1 + size
                    }
                }
                0xC0, 0xC1, 0xC2 -> {
                    if (m == 0xC2) progressive = true
                    segs += if (m == 0xC2) "SOF2 (прогрессивный)" else "SOF${m - 0xC0}"
                    if (p + 5 <= b.size) {
                        h = ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() and 0xFF)
                        w = ((b[p + 3].toInt() and 0xFF) shl 8) or (b[p + 4].toInt() and 0xFF)
                    }
                }
                0xDA -> { segs += "SOS"; break }
                else -> segs += "0x%02X".format(m)
            }
            i += 2 + len
        }
        val l = luma
        val q = l?.let { estimateQuality(it) }
        // DQT хранит таблицу в зигзаг-порядке, стандартную сравниваем по множеству значений
        val standard = l != null && q != null && ijgTable(q).sorted() == l.sorted()
        return Report(q, standard, progressive, exif, xmp, editors.toList(), segs, w, h)
    }
}
