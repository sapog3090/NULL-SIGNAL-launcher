package com.nullsignal.core.hw

/** Анализ Wi-Fi эфира: загрузка каналов и лучший канал для своего роутера. */
object WifiChannels {
    data class Net(val channel: Int, val level: Int, val freq: Int)

    data class Load(val channel: Int, val networks: Int, val score: Double)

    /** Вклад сети в загрузку канала: сильный сигнал мешает сильнее. */
    private fun weight(level: Int): Double = when {
        level >= -50 -> 1.0
        level >= -60 -> 0.8
        level >= -70 -> 0.55
        level >= -80 -> 0.3
        else -> 0.1
    }

    /**
     * Загрузка каналов 2.4 ГГц с учётом перекрытия: канал шириной 20 МГц
     * задевает соседние ±2 канала (каналы 1, 6, 11 не перекрываются).
     */
    fun load24(nets: List<Net>): List<Load> = (1..13).map { ch ->
        var score = 0.0
        var count = 0
        nets.filter { it.freq < 2500 }.forEach { n ->
            val d = kotlin.math.abs(n.channel - ch)
            if (d <= 4) {
                score += weight(n.level) * (1.0 - d / 5.0)
                if (d == 0) count++
            }
        }
        Load(ch, count, score)
    }

    fun load5(nets: List<Net>): List<Load> = nets.filter { it.freq in 4900..5900 }
        .groupBy { it.channel }
        .map { (ch, list) -> Load(ch, list.size, list.sumOf { weight(it.level) }) }
        .sortedBy { it.channel }

    /** Лучший из неперекрывающихся каналов 1/6/11. */
    fun best24(nets: List<Net>): Int = load24(nets).filter { it.channel in listOf(1, 6, 11) }.minByOrNull { it.score }?.channel ?: 1

    fun wps(capabilities: String): Boolean = "WPS" in capabilities.uppercase()
}

/** Производители Bluetooth-устройств по Company ID (Bluetooth SIG). */
object BleVendors {
    private val IDS = mapOf(
        0x004C to "Apple", 0x0075 to "Samsung", 0x0006 to "Microsoft", 0x00E0 to "Google", 0x038F to "Xiaomi",
        0x027D to "Huawei", 0x012D to "Sony", 0x00C4 to "LG", 0x0087 to "Garmin", 0x0157 to "Huami (Amazfit)",
        0x0171 to "Amazon", 0x0059 to "Nordic Semiconductor", 0x000D to "Texas Instruments",
        0x0002 to "Intel", 0x001D to "Qualcomm", 0x000F to "Broadcom", 0x02E5 to "Espressif",
        0x0499 to "Ruuvi", 0x0822 to "Adafruit", 0x05A7 to "Sonos", 0x0154 to "Pebble", 0x006B to "Polar",
        0x009E to "Bose", 0x0057 to "Harman (JBL)", 0x0078 to "Nike", 0x0046 to "MediaTek",
        0x005D to "Realtek", 0x0000 to "Ericsson", 0x0001 to "Nokia", 0x0003 to "IBM",
    )

    fun name(companyId: Int): String? = IDS[companyId]

    /** Что за Apple-устройство по типу сообщения Continuity. */
    fun appleKind(data: ByteArray): String? {
        if (data.isEmpty()) return null
        return when (data[0].toInt() and 0xff) {
            0x02 -> "iBeacon"
            0x05 -> "AirDrop"
            0x07 -> "AirPods / Beats"
            0x09 -> "AirPlay"
            0x0C -> "Handoff (iPhone/Mac)"
            0x0F -> "iPhone/iPad (Nearby Action)"
            0x10 -> "iPhone/iPad/Mac (Nearby)"
            0x12 -> "Find My / AirTag"
            else -> null
        }
    }
}
