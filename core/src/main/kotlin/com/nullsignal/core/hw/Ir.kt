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

    private fun pulseDistance(header: IntArray, bytes: List<Int>, mark: Int = 560, zero: Int = 560, one: Int = 1690): IntArray {
        val p = header.toMutableList()
        bytes.forEach { b -> bitsLsb(b, 8).forEach { bit -> p += mark; p += if (bit == 1) one else zero } }
        p += mark // стоп-импульс
        return p.toIntArray()
    }

    /** NEC: LG, Toshiba, большинство китайских ТВ. 8-битный адрес — с инверсией, 16-битный — расширенный. */
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

    /** Philips RC5: манчестерский код, 889 мкс на полубит, 36 кГц. */
    fun rc5(address: Int, command: Int, toggle: Boolean = false): Signal {
        val bits = mutableListOf(1, if (command < 64) 1 else 0, if (toggle) 1 else 0)
        for (i in 4 downTo 0) bits += (address shr i) and 1
        for (i in 5 downTo 0) bits += (command shr i) and 1
        // 1 = пауза→вспышка, 0 = вспышка→пауза
        val halves = bits.flatMap { if (it == 1) listOf(false, true) else listOf(true, false) }
        val out = mutableListOf<Int>()
        var level = halves.first()
        var len = 0
        for (h in halves) {
            if (h == level) len += 889 else { out += len; level = h; len = 889 }
        }
        out += len
        // ConsumerIrManager требует начинать со вспышки: ведущую паузу отбрасываем
        val startsWithSpace = !halves.first()
        val pattern = if (startsWithSpace) out.drop(1) else out
        // хвост — пауза, чтобы шаблон был чётной длины
        return Signal(36000, (if (pattern.size % 2 == 1) pattern + 30000 else pattern).toIntArray())
    }

    enum class Button(val title: String) { POWER("ПИТАНИЕ"), VOL_UP("ГРОМЧЕ"), VOL_DOWN("ТИШЕ"), MUTE("БЕЗ ЗВУКА"), CH_UP("КАНАЛ +"), CH_DOWN("КАНАЛ −"), INPUT("ИСТОЧНИК") }

    data class Brand(val name: String, val codes: Map<Button, Signal>)

    private fun samsungTv(cmd: Int) = samsung(0x07, cmd)
    private fun lgTv(cmd: Int) = nec(0x04, cmd)
    private fun sonyTv(cmd: Int) = sony(0x01, cmd)
    private fun philipsTv(cmd: Int) = rc5(0x00, cmd)
    private fun toshibaTv(cmd: Int) = nec(0x40, cmd)

    /** Коды известных марок телевизоров (из открытых баз LIRC). */
    val BRANDS: List<Brand> = listOf(
        Brand("SAMSUNG", mapOf(
            Button.POWER to samsungTv(0x02), Button.VOL_UP to samsungTv(0x07), Button.VOL_DOWN to samsungTv(0x0B),
            Button.MUTE to samsungTv(0x0F), Button.CH_UP to samsungTv(0x12), Button.CH_DOWN to samsungTv(0x10),
            Button.INPUT to samsungTv(0x01),
        )),
        Brand("LG", mapOf(
            Button.POWER to lgTv(0x08), Button.VOL_UP to lgTv(0x02), Button.VOL_DOWN to lgTv(0x03),
            Button.MUTE to lgTv(0x09), Button.CH_UP to lgTv(0x00), Button.CH_DOWN to lgTv(0x01), Button.INPUT to lgTv(0x0B),
        )),
        Brand("SONY", mapOf(
            Button.POWER to sonyTv(21), Button.VOL_UP to sonyTv(18), Button.VOL_DOWN to sonyTv(19),
            Button.MUTE to sonyTv(20), Button.CH_UP to sonyTv(16), Button.CH_DOWN to sonyTv(17), Button.INPUT to sonyTv(37),
        )),
        Brand("PHILIPS", mapOf(
            Button.POWER to philipsTv(12), Button.VOL_UP to philipsTv(16), Button.VOL_DOWN to philipsTv(17),
            Button.MUTE to philipsTv(13), Button.CH_UP to philipsTv(32), Button.CH_DOWN to philipsTv(33),
        )),
        Brand("TOSHIBA", mapOf(
            Button.POWER to toshibaTv(0x12), Button.VOL_UP to toshibaTv(0x1A), Button.VOL_DOWN to toshibaTv(0x1E),
            Button.MUTE to toshibaTv(0x10), Button.CH_UP to toshibaTv(0x1B), Button.CH_DOWN to toshibaTv(0x1F),
        )),
    )

    /** Все коды питания подряд — «универсальное выключение» любого из известных ТВ. */
    val ALL_POWER: List<Pair<String, Signal>> = BRANDS.mapNotNull { b -> b.codes[Button.POWER]?.let { b.name to it } }
}
