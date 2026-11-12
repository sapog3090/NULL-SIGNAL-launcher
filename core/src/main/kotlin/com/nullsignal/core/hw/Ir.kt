package com.nullsignal.core.hw

/**
 * Инфракрасные протоколы пультов. Результат — частота несущей и последовательность
 * длительностей в микросекундах: вспышка, пауза, вспышка, … (формат ConsumerIrManager).
 */
object Ir {

    data class Signal(val carrierHz: Int, val pattern: IntArray) {
        val durationUs: Int get() = pattern.sum()
    }

    private fun bitsLsb(value: Int, count: Int): List<Int> = (0 until count).map { (value shr it) and 1 }

    fun reverse8(b: Int): Int {
        var v = b and 0xFF
        var r = 0
        repeat(8) { r = (r shl 1) or (v and 1); v = v shr 1 }
        return r
    }

    /** Склеивает соседние отрезки одного уровня; шаблон всегда начинается со вспышки и имеет чётную длину. */
    private fun merge(segments: List<Pair<Boolean, Int>>, tailGap: Int = 30000): IntArray {
        val out = mutableListOf<Int>()
        var level: Boolean? = null
        for ((on, d) in segments) {
            if (out.isEmpty() && !on) continue // ведущая пауза не нужна
            if (on == level) out[out.lastIndex] += d else { out += d; level = on }
        }
        if (out.size % 2 == 1) out += tailGap
        return out.toIntArray()
    }

    private fun pulseDistance(header: IntArray, bytes: List<Int>, mark: Int = 560, zero: Int = 560, one: Int = 1690): IntArray {
        val p = header.toMutableList()
        bytes.forEach { b -> bitsLsb(b, 8).forEach { bit -> p += mark; p += if (bit == 1) one else zero } }
        p += mark // стоп-импульс
        return p.toIntArray()
    }

    /** NEC: LG, Toshiba, Vizio, многие китайские ТВ. 8-битный адрес — с инверсией, 16-битный — расширенный. */
    fun nec(address: Int, command: Int): Signal {
        val addrBytes = if (address > 0xFF) listOf(address and 0xFF, (address shr 8) and 0xFF)
        else listOf(address and 0xFF, address.inv() and 0xFF)
        return Signal(38000, pulseDistance(intArrayOf(9000, 4500), addrBytes + listOf(command and 0xFF, command.inv() and 0xFF)))
    }

    /** Samsung32: как NEC, но заголовок 4.5/4.5 мс и адрес повторяется без инверсии. */
    fun samsung(address: Int, command: Int): Signal =
        Signal(38000, pulseDistance(intArrayOf(4500, 4500), listOf(address and 0xFF, address and 0xFF, command and 0xFF, command.inv() and 0xFF)))

    /** Sony SIRC-12: 7 бит команды + 5 бит адреса, кадр 45 мс, повтор 3 раза. */
    fun sony(address: Int, command: Int, repeats: Int = 3): Signal {
        val frame = mutableListOf(2400, 600)
        (bitsLsb(command, 7) + bitsLsb(address, 5)).forEach { bit -> frame += if (bit == 1) 1200 else 600; frame += 600 }
        frame[frame.lastIndex] = 45000 - (frame.sum() - frame.last()) // добиваем кадр до 45 мс
        return Signal(40000, (List(repeats) { frame }.flatten()).toIntArray())
    }

    /** Philips RC5: манчестерский код, 889 мкс на полубит, 36 кГц. 1 = пауза→вспышка. */
    fun rc5(address: Int, command: Int, toggle: Boolean = false): Signal {
        val bits = mutableListOf(1, if (command < 64) 1 else 0, if (toggle) 1 else 0)
        for (i in 4 downTo 0) bits += (address shr i) and 1
        for (i in 5 downTo 0) bits += (command shr i) and 1
        val seg = bits.flatMap { if (it == 1) listOf(false to 889, true to 889) else listOf(true to 889, false to 889) }
        return Signal(36000, merge(seg))
    }

    /** Philips RC6 режим 0 (новые Philips): 444 мкс на полубит, 1 = вспышка→пауза, бит-переключатель двойной длины. */
    fun rc6(address: Int, command: Int, toggle: Boolean = false): Signal {
        val t = 444
        val seg = mutableListOf(true to 6 * t, false to 2 * t)
        fun bit(b: Int, w: Int = t) {
            if (b == 1) { seg += true to w; seg += false to w } else { seg += false to w; seg += true to w }
        }
        bit(1)                              // стартовый бит
        repeat(3) { bit(0) }                // режим 0
        bit(if (toggle) 1 else 0, 2 * t)    // бит-переключатель
        for (i in 7 downTo 0) bit((address shr i) and 1)
        for (i in 7 downTo 0) bit((command shr i) and 1)
        return Signal(36000, merge(seg))
    }

    /** Sharp: 5 бит адреса + 8 бит команды, два кадра (второй — с инвертированной командой). */
    fun sharp(address: Int, command: Int): Signal {
        fun frame(cmd: Int, expansion: Int, check: Int): List<Int> {
            val p = mutableListOf<Int>()
            (bitsLsb(address, 5) + bitsLsb(cmd, 8) + listOf(expansion, check)).forEach { b -> p += 320; p += if (b == 1) 1680 else 680 }
            p += 320
            return p
        }
        return Signal(38000, (frame(command and 0xFF, 1, 0) + 40000 + frame(command.inv() and 0xFF, 0, 1)).toIntArray())
    }

    /**
     * Panasonic (Kaseikyo, 48 бит): производитель 0x2002, команда и контрольный байт.
     * Здесь — в классической записи Arduino IRremote: адрес 0x4004, данные 0x0100 cc kk.
     */
    fun panasonic(command: Int): Signal {
        val d = reverse8(command)
        val data = 0x01000000L or (d.toLong() shl 8) or ((0x01 xor d).toLong())
        val p = mutableListOf(3502, 1750)
        fun bits(v: Long, n: Int) {
            for (i in n - 1 downTo 0) { p += 502; p += if ((v shr i) and 1L == 1L) 1244 else 400 }
        }
        bits(0x4004, 16)
        bits(data, 32)
        p += 502
        return Signal(36700, p.toIntArray())
    }

    enum class Button(val title: String) {
        POWER("ПИТАНИЕ"), INPUT("ИСТОЧНИК"), MUTE("ЗВУК ✕"),
        VOL_UP("ГРОМ +"), VOL_DOWN("ГРОМ −"), CH_UP("КАН +"), CH_DOWN("КАН −"),
        UP("▲"), DOWN("▼"), LEFT("◀"), RIGHT("▶"), OK("OK"),
        BACK("НАЗАД"), HOME("ДОМОЙ"), MENU("МЕНЮ"), EXIT("ВЫХОД"),
        D1("1"), D2("2"), D3("3"), D4("4"), D5("5"), D6("6"), D7("7"), D8("8"), D9("9"), D0("0"),
    }

    val DIGITS = listOf(Button.D1, Button.D2, Button.D3, Button.D4, Button.D5, Button.D6, Button.D7, Button.D8, Button.D9, Button.D0)

    data class Brand(val name: String, val protocol: String, val codes: Map<Button, Signal>)

    private fun digits(make: (Int) -> Signal, codes: List<Int>): Map<Button, Signal> = DIGITS.zip(codes).associate { (b, c) -> b to make(c) }

    private fun samsungTv(cmd: Int) = samsung(0x07, cmd)
    private fun lgTv(cmd: Int) = nec(0x04, cmd)
    private fun sonyTv(cmd: Int) = sony(0x01, cmd)
    private fun rc5Tv(cmd: Int) = rc5(0x00, cmd)
    private fun rc6Tv(cmd: Int) = rc6(0x00, cmd)
    private fun toshibaTv(cmd: Int) = nec(0x40, cmd)
    private fun sharpTv(cmd: Int) = sharp(0x01, cmd)

    private val SAMSUNG = mapOf(
        Button.POWER to samsungTv(0x02), Button.INPUT to samsungTv(0x01), Button.MUTE to samsungTv(0x0F),
        Button.VOL_UP to samsungTv(0x07), Button.VOL_DOWN to samsungTv(0x0B), Button.CH_UP to samsungTv(0x12), Button.CH_DOWN to samsungTv(0x10),
        Button.UP to samsungTv(0x60), Button.DOWN to samsungTv(0x61), Button.LEFT to samsungTv(0x65), Button.RIGHT to samsungTv(0x62),
        Button.OK to samsungTv(0x68), Button.BACK to samsungTv(0x58), Button.HOME to samsungTv(0x79), Button.MENU to samsungTv(0x1A),
        Button.EXIT to samsungTv(0x2D),
    ) + digits(::samsungTv, listOf(0x04, 0x05, 0x06, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x11))

    private val LG = mapOf(
        Button.POWER to lgTv(0x08), Button.INPUT to lgTv(0x0B), Button.MUTE to lgTv(0x09),
        Button.VOL_UP to lgTv(0x02), Button.VOL_DOWN to lgTv(0x03), Button.CH_UP to lgTv(0x00), Button.CH_DOWN to lgTv(0x01),
        Button.UP to lgTv(0x40), Button.DOWN to lgTv(0x41), Button.LEFT to lgTv(0x07), Button.RIGHT to lgTv(0x06),
        Button.OK to lgTv(0x44), Button.BACK to lgTv(0x28), Button.HOME to lgTv(0x7C), Button.MENU to lgTv(0x43),
        Button.EXIT to lgTv(0x5B),
    ) + digits(::lgTv, listOf(0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x10))

    private val SONY = mapOf(
        Button.POWER to sonyTv(21), Button.INPUT to sonyTv(37), Button.MUTE to sonyTv(20),
        Button.VOL_UP to sonyTv(18), Button.VOL_DOWN to sonyTv(19), Button.CH_UP to sonyTv(16), Button.CH_DOWN to sonyTv(17),
        Button.UP to sonyTv(116), Button.DOWN to sonyTv(117), Button.LEFT to sonyTv(52), Button.RIGHT to sonyTv(51),
        Button.OK to sonyTv(101), Button.HOME to sonyTv(96),
    ) + digits(::sonyTv, listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))

    private val PANASONIC = mapOf(
        Button.POWER to panasonic(0x3D), Button.INPUT to panasonic(0x05), Button.MUTE to panasonic(0x32),
        Button.VOL_UP to panasonic(0x20), Button.VOL_DOWN to panasonic(0x21), Button.CH_UP to panasonic(0x34), Button.CH_DOWN to panasonic(0x35),
    ) + digits(::panasonic, listOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19))

    private val SHARP = mapOf(
        Button.POWER to sharpTv(22), Button.MUTE to sharpTv(23),
        Button.VOL_UP to sharpTv(20), Button.VOL_DOWN to sharpTv(21), Button.CH_UP to sharpTv(17), Button.CH_DOWN to sharpTv(18),
    ) + digits(::sharpTv, listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

    private val PHILIPS_RC6 = mapOf(
        Button.POWER to rc6Tv(0x0C), Button.MUTE to rc6Tv(0x0D),
        Button.VOL_UP to rc6Tv(0x10), Button.VOL_DOWN to rc6Tv(0x11), Button.CH_UP to rc6Tv(0x4C), Button.CH_DOWN to rc6Tv(0x4D),
        Button.UP to rc6Tv(0x58), Button.DOWN to rc6Tv(0x59), Button.LEFT to rc6Tv(0x5A), Button.RIGHT to rc6Tv(0x5B),
        Button.OK to rc6Tv(0x5C), Button.HOME to rc6Tv(0x54), Button.BACK to rc6Tv(0x0A),
    ) + digits(::rc6Tv, listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))

    private val PHILIPS_RC5 = mapOf(
        Button.POWER to rc5Tv(12), Button.MUTE to rc5Tv(13),
        Button.VOL_UP to rc5Tv(16), Button.VOL_DOWN to rc5Tv(17), Button.CH_UP to rc5Tv(32), Button.CH_DOWN to rc5Tv(33),
    ) + digits(::rc5Tv, listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))

    private val TOSHIBA = mapOf(
        Button.POWER to toshibaTv(0x12), Button.MUTE to toshibaTv(0x10),
        Button.VOL_UP to toshibaTv(0x1A), Button.VOL_DOWN to toshibaTv(0x1E), Button.CH_UP to toshibaTv(0x1B), Button.CH_DOWN to toshibaTv(0x1F),
    ) + digits(::toshibaTv, listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))

    /** Коды телевизоров (из открытых баз LIRC и Arduino IRremote). */
    val TV: List<Brand> = listOf(
        Brand("SAMSUNG", "Samsung32", SAMSUNG),
        Brand("LG", "NEC", LG),
        Brand("SONY", "SIRC", SONY),
        Brand("PANASONIC", "Kaseikyo", PANASONIC),
        Brand("SHARP", "Sharp", SHARP),
        Brand("PHILIPS", "RC6", PHILIPS_RC6),
        Brand("PHILIPS (старые)", "RC5", PHILIPS_RC5),
        Brand("TOSHIBA", "NEC", TOSHIBA),
        Brand("VIZIO", "NEC (как LG)", LG.filterKeys { it in setOf(Button.POWER, Button.INPUT, Button.MUTE, Button.VOL_UP, Button.VOL_DOWN, Button.CH_UP, Button.CH_DOWN) }),
        Brand("GRUNDIG", "RC5 (как Philips)", PHILIPS_RC5),
        Brand("HISENSE", "NEC ext", mapOf(Button.POWER to nec(0xBF00, 0x0D))),
    )

    /** Коды питания по очереди — для подбора пульта к своему телевизору. */
    val POWER_SEARCH: List<Pair<String, Signal>> = TV.mapNotNull { b -> b.codes[Button.POWER]?.let { b.name to it } }

    // ---------- RGB-лента (пульт на 24 кнопки, NEC с адресом 0xEF00) ----------

    enum class LedKey(val title: String, val rgb: Long?) {
        BRIGHT_UP("ЯРЧЕ", null), BRIGHT_DOWN("ТУСКЛЕЕ", null), OFF("ВЫКЛ", null), ON("ВКЛ", null),
        RED("R", 0xFFFF2020), GREEN("G", 0xFF20FF40), BLUE("B", 0xFF2040FF), WHITE("W", 0xFFFFFFFF),
        C1("", 0xFFFF5020), C2("", 0xFF40FF80), C3("", 0xFF4080FF), FLASH("ВСПЫШКА", null),
        C4("", 0xFFFF8020), C5("", 0xFF20E0E0), C6("", 0xFF8030FF), STROBE("СТРОБ", null),
        C7("", 0xFFFFB020), C8("", 0xFF20A0B0), C9("", 0xFFB030D0), FADE("ПЕРЕЛИВ", null),
        C10("", 0xFFFFFF30), C11("", 0xFF2080A0), C12("", 0xFFFF40B0), SMOOTH("ПЛАВНО", null),
    }

    /** Команда кнопки = её порядковый номер в раскладке 24-кнопочного пульта. */
    fun led(key: LedKey): Signal = nec(0xEF00, key.ordinal)
}
