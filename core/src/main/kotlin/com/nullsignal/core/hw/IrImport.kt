package com.nullsignal.core.hw

/**
 * Свой пульт из чужой базы: файлы .ir от Flipper Zero (огромная открытая база Flipper-IRDB)
 * и коды Pronto Hex (так их публикуют на форумах и в инструкциях).
 */
object IrImport {
    class Button(val name: String, val signal: Ir.Signal)
    class Result(val buttons: List<Button>, val skipped: List<String>)

    private fun hexBytes(v: String?): List<Int> =
        v.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull(16) }

    private fun le(b: List<Int>, n: Int): Int = (0 until n).sumOf { (b.getOrElse(it) { 0 } and 0xFF) shl (8 * it) }

    /** Сигнал по описанию из .ir. null — протокол пока не поддерживается. */
    fun parsed(protocol: String, address: List<Int>, command: List<Int>): Ir.Signal? {
        val a = le(address, 2)
        val c = le(command, 2)
        return when (protocol.uppercase()) {
            "NEC", "NEC42" -> Ir.nec(a and 0xFF, c and 0xFF)
            "NECEXT", "NEC42EXT" -> Ir.necBytes(listOf(a and 0xFF, a shr 8, c and 0xFF, if (c > 0xFF) c shr 8 else c.inv() and 0xFF))
            "SAMSUNG32" -> Ir.samsung(a and 0xFF, c and 0xFF)
            "SIRC" -> Ir.sony(a and 0x1F, c and 0x7F)
            "RC5", "RC5X" -> Ir.rc5(a and 0x1F, c and 0x7F)
            "RC6" -> Ir.rc6(a and 0xFF, c and 0xFF)
            else -> null
        }
    }

    /** Разбор текста файла .ir (Flipper Zero). */
    fun flipper(text: String): Result {
        val buttons = ArrayList<Button>()
        val skipped = ArrayList<String>()
        var fields = HashMap<String, String>()
        fun flush() {
            val name = fields["name"] ?: return
            val sig = when (fields["type"]?.lowercase()) {
                "parsed" -> parsed(fields["protocol"].orEmpty(), hexBytes(fields["address"]), hexBytes(fields["command"]))
                "raw" -> {
                    val freq = fields["frequency"]?.trim()?.toIntOrNull() ?: 38000
                    val data = fields["data"].orEmpty().trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.filter { it > 0 }
                    if (data.size < 2 || data.sum() > 2_000_000) null else Ir.Signal(freq, data.toIntArray())
                }
                else -> null
            }
            if (sig != null) buttons += Button(name.trim(), sig) else skipped += name.trim() + (fields["protocol"]?.let { " ($it)" } ?: "")
            fields = HashMap()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#")) { flush(); continue }
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).trim().lowercase()
            if (key == "name") flush()
            fields[key] = line.substring(i + 1).trim()
        }
        flush()
        return Result(buttons, skipped)
    }

    /** Pronto Hex: «0000 006D 0022 0002 0157 00AC …». null — это не Pronto. */
    fun pronto(code: String): Ir.Signal? {
        val w = code.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull(16) ?: return null }
        if (w.size < 6 || w[0] != 0 || w[1] == 0) return null
        val freq = (1_000_000 / (w[1] * 0.241246)).toInt()
        val once = w[2]
        val repeat = w[3]
        if (w.size < 4 + 2 * (once + repeat)) return null
        val pairs = if (once > 0) w.subList(4, 4 + 2 * once) else w.subList(4, 4 + 2 * repeat)
        val period = 1_000_000.0 / freq
        return Ir.Signal(freq, pairs.map { maxOf(1, (it * period).toInt()) }.toIntArray())
    }

    /** Что угодно из буфера обмена: файл .ir целиком или один код Pronto. */
    fun any(text: String, defaultName: String = "Кнопка"): Result {
        if (text.contains("name:", ignoreCase = true)) return flipper(text)
        val p = pronto(text) ?: return Result(emptyList(), listOf("не похоже ни на файл .ir, ни на код Pronto"))
        return Result(listOf(Button(defaultName, p)), emptyList())
    }
}
