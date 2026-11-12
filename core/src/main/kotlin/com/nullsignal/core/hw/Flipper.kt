package com.nullsignal.core.hw

import java.io.ByteArrayOutputStream

/** Записи NDEF — то, что лежит на NFC-метке: ссылка или текст. */
object Ndef {
    private val PREFIXES = listOf("https://www." to 0x02, "http://www." to 0x01, "https://" to 0x04, "http://" to 0x03, "tel:" to 0x05, "mailto:" to 0x06)

    private fun record(type: Char, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val short = payload.size < 256
        // MB, ME, SR (короткая запись), TNF = 1 (стандартный тип)
        out.write(0x80 or 0x40 or (if (short) 0x10 else 0) or 0x01)
        out.write(1)
        if (short) out.write(payload.size) else for (s in intArrayOf(24, 16, 8, 0)) out.write((payload.size shr s) and 0xFF)
        out.write(type.code)
        out.write(payload)
        return out.toByteArray()
    }

    fun uri(url: String): ByteArray {
        val (prefix, code) = PREFIXES.firstOrNull { url.startsWith(it.first, ignoreCase = true) } ?: ("" to 0)
        return record('U', byteArrayOf(code.toByte()) + url.substring(prefix.length).toByteArray())
    }

    fun text(text: String, lang: String = "ru"): ByteArray =
        record('T', byteArrayOf(lang.length.toByte()) + lang.toByteArray() + text.toByteArray())
}

/**
 * Телефон притворяется NFC-меткой (тип 4, как у Flipper в режиме эмуляции): другой телефон
 * прикладывается — и открывает ссылку или показывает текст. Здесь только разбор команд считывателя (APDU).
 */
class NdefT4(message: ByteArray) {
    private val ndefFile: ByteArray = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message
    private val cc: ByteArray = byteArrayOf(
        0x00, 0x0F, // длина файла CC
        0x20, // версия 2.0
        0x00, 0x3B, // сколько можно прочитать за раз
        0x00, 0x34, // сколько можно записать за раз
        0x04, 0x06, 0xE1.toByte(), 0x04, // где лежит файл NDEF
        (ndefFile.size shr 8).toByte(), ndefFile.size.toByte(),
        0x00, 0xFF.toByte(), // читать можно, писать нельзя
    )
    private var selected: ByteArray? = null
    private var appSelected = false

    fun respond(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return SW_WRONG
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        return when {
            ins == 0xA4 && p1 == 0x04 -> {
                val len = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
                val aid = apdu.copyOfRange(5, minOf(apdu.size, 5 + len))
                appSelected = aid.contentEquals(AID)
                selected = null
                if (appSelected) SW_OK else SW_NOT_FOUND
            }
            ins == 0xA4 && p1 == 0x00 -> {
                if (!appSelected || apdu.size < 7) return SW_NOT_FOUND
                val id = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
                selected = when (id) { 0xE103 -> cc; 0xE104 -> ndefFile; else -> null }
                if (selected != null) SW_OK else SW_NOT_FOUND
            }
            ins == 0xB0 -> {
                val file = selected ?: return SW_NOT_FOUND
                val offset = (p1 shl 8) or (apdu[3].toInt() and 0xFF)
                val want = if (apdu.size > 4) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
                if (offset > file.size) return SW_WRONG_OFFSET
                file.copyOfRange(offset, minOf(file.size, offset + want)) + SW_OK
            }
            else -> SW_NO_COMMAND
        }
    }

    companion object {
        val AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
        val SW_WRONG = byteArrayOf(0x67, 0x00)
        val SW_NO_COMMAND = byteArrayOf(0x6D, 0x00)
    }
}

/** Bluetooth-пульт (HID): клавиатура, мышь и мультимедиа-кнопки — как приложение Bluetooth Remote у Flipper. */
object Hid {
    const val KEYBOARD = 1
    const val MOUSE = 2
    const val MEDIA = 3

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Описание устройства для компьютера: что за кнопки и в каком формате приходят нажатия. */
    val DESCRIPTOR: ByteArray = bytes(
        // клавиатура
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // мышь
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, MOUSE, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        // мультимедиа
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, MEDIA,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    // клавиши (коды USB HID)
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val F5 = 0x3E
    const val PAGE_UP = 0x4B
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val SHIFT = 0x02

    // мультимедиа
    const val PLAY_PAUSE = 0xCD
    const val NEXT = 0xB5
    const val PREV = 0xB6
    const val MUTE = 0xE2
    const val VOL_UP = 0xE9
    const val VOL_DOWN = 0xEA

    private const val SHIFTED_DIGITS = ")!@#$%^&*("
    private val PUNCT = mapOf(
        '-' to (0x2D to false), '_' to (0x2D to true), '=' to (0x2E to false), '+' to (0x2E to true),
        '[' to (0x2F to false), '{' to (0x2F to true), ']' to (0x30 to false), '}' to (0x30 to true),
        '\\' to (0x31 to false), '|' to (0x31 to true), ';' to (0x33 to false), ':' to (0x33 to true),
        '\'' to (0x34 to false), '"' to (0x34 to true), '`' to (0x35 to false), '~' to (0x35 to true),
        ',' to (0x36 to false), '<' to (0x36 to true), '.' to (0x37 to false), '>' to (0x37 to true),
        '/' to (0x38 to false), '?' to (0x38 to true), ' ' to (SPACE to false), '\n' to (ENTER to false), '\t' to (TAB to false),
    )

    /** Клавиша для символа (английская раскладка): (модификатор, код) или null, если символа на клавиатуре нет. */
    fun key(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> 0 to 0x04 + (c - 'a')
        in 'A'..'Z' -> SHIFT to 0x04 + (c - 'A')
        in '1'..'9' -> 0 to 0x1E + (c - '1')
        '0' -> 0 to 0x27
        in SHIFTED_DIGITS -> SHIFT to (SHIFTED_DIGITS.indexOf(c).let { if (it == 0) 0x27 else 0x1D + it })
        else -> PUNCT[c]?.let { (code, shift) -> (if (shift) SHIFT else 0) to code }
    }

    fun keyReport(mod: Int, code: Int): ByteArray = bytes(mod, 0, code, 0, 0, 0, 0, 0)
    val KEY_UP: ByteArray = ByteArray(8)
    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray =
        bytes(buttons, dx.coerceIn(-127, 127), dy.coerceIn(-127, 127), wheel.coerceIn(-127, 127))
    fun mediaReport(usage: Int): ByteArray = bytes(usage and 0xFF, usage shr 8)
}
