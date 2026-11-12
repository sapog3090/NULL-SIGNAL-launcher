package com.nullsignal.core.spy

/**
 * Стеганография: прячет данные в младших битах цвета пикселей (LSB).
 * На глаз картинка не меняется: каждый канал R/G/B сдвигается максимум на 1 из 255.
 * Сохранять результат нужно только в PNG — JPEG сжатием уничтожит младшие биты.
 */
object Stego {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'S'.code.toByte(), 'G'.code.toByte())
    private const val HEADER = 3 + 4
    private val CHANNELS = intArrayOf(16, 8, 0) // R, G, B

    /** Сколько байт поместится в картинку из [pixelCount] пикселей. */
    fun capacity(pixelCount: Int): Int = (pixelCount.toLong() * 3 / 8 - HEADER).coerceAtLeast(0).toInt()

    /** Возвращает новые пиксели ARGB со спрятанными данными. Альфа всегда становится 255. */
    fun embed(pixels: IntArray, data: ByteArray): IntArray {
        require(data.size <= capacity(pixels.size)) { "слишком длинное сообщение: влезает ${capacity(pixels.size)} байт" }
        val len = data.size
        val payload = MAGIC + byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()) + data
        val totalBits = payload.size * 8
        val out = IntArray(pixels.size) { pixels[it] or (0xFF shl 24) }
        var bit = 0
        loop@ for (i in out.indices) {
            var p = out[i]
            for (ch in CHANNELS) {
                if (bit >= totalBits) { out[i] = p; break@loop }
                val v = (payload[bit / 8].toInt() shr (7 - bit % 8)) and 1
                p = (p and (1 shl ch).inv()) or (v shl ch)
                bit++
            }
            out[i] = p
        }
        return out
    }

    /** Достаёт спрятанные данные или null, если в картинке ничего нет. */
    fun extract(pixels: IntArray): ByteArray? {
        fun readBytes(count: Int, startBit: Int): ByteArray? {
            val res = ByteArray(count)
            for (k in 0 until count * 8) {
                val b = startBit + k
                val px = b / 3
                if (px >= pixels.size) return null
                val v = (pixels[px] shr CHANNELS[b % 3]) and 1
                res[k / 8] = (res[k / 8].toInt() or (v shl (7 - k % 8))).toByte()
            }
            return res
        }
        val header = readBytes(HEADER, 0) ?: return null
        if (!header.copyOfRange(0, 3).contentEquals(MAGIC)) return null
        val len = ((header[3].toInt() and 0xff) shl 24) or ((header[4].toInt() and 0xff) shl 16) or
            ((header[5].toInt() and 0xff) shl 8) or (header[6].toInt() and 0xff)
        if (len < 0 || len > capacity(pixels.size)) return null
        return readBytes(len, HEADER * 8)
    }
}

/**
 * «Невидимые чернила»: прячет сообщение в обычном тексте символами нулевой ширины.
 * Текст выглядит как обычный, но внутри — секрет. Работает в большинстве мессенджеров.
 */
object InvisibleInk {
    const val ZERO = '​' // zero width space
    const val ONE = '‌'  // zero width non-joiner
    const val MARK = '⁠' // word joiner — границы секрета
    private val INVISIBLE = setOf('​', '‌', '‍', '⁠', '﻿', '᠎')

    fun hide(cover: String, secret: String): String {
        val bits = StringBuilder()
        for (b in secret.toByteArray(Charsets.UTF_8)) {
            for (i in 7 downTo 0) bits.append(if ((b.toInt() shr i) and 1 == 1) ONE else ZERO)
        }
        val hidden = "$MARK$bits$MARK"
        return if (cover.isEmpty()) hidden else cover.substring(0, 1) + hidden + cover.substring(1)
    }

    fun reveal(text: String): String? {
        val start = text.indexOf(MARK)
        if (start < 0) return null
        val end = text.indexOf(MARK, start + 1)
        if (end < 0) return null
        val bits = text.substring(start + 1, end).filter { it == ZERO || it == ONE }
        if (bits.isEmpty() || bits.length % 8 != 0) return null
        val bytes = ByteArray(bits.length / 8) { i ->
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + k] == ONE) 1 else 0)
            v.toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun detect(text: String): Int = text.count { it in INVISIBLE }

    fun strip(text: String): String = text.filterNot { it in INVISIBLE }
}
