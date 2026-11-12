package com.nullsignal.core.hw

/**
 * Сотовые вышки: что за сота, на какой частоте, как далеко, и эвристики
 * фальшивых базовых станций (IMSI-перехватчиков). Это признаки, а не доказательства.
 */
object Cells {
    enum class Rat(val title: String) { GSM("2G"), WCDMA("3G"), LTE("4G"), NR("5G"), CDMA("CDMA"), TDSCDMA("TD-SCDMA") }

    data class Cell(
        val rat: Rat,
        val registered: Boolean,
        val mcc: String?,
        val mnc: String?,
        /** LAC (2G/3G) или TAC (4G/5G). */
        val area: Int?,
        val cid: Long?,
        val pci: Int?,
        val arfcn: Int?,
        val dbm: Int?,
        /** Timing advance — задержка сигнала, по ней считается расстояние до вышки. */
        val ta: Int?,
    ) {
        val plmn: String? get() = if (mcc != null && mnc != null) "$mcc-$mnc" else null
    }

    private val OPERATORS = mapOf(
        "250-01" to "МТС", "250-02" to "МегаФон", "250-20" to "T2 (Tele2)", "250-99" to "Билайн", "250-11" to "Yota",
        "250-35" to "Мотив", "250-32" to "Win Mobile", "250-50" to "СберМобайл", "257-01" to "A1 (Беларусь)",
        "257-02" to "МТС (Беларусь)", "257-04" to "life:) (Беларусь)", "401-01" to "Beeline (Казахстан)", "401-02" to "Kcell",
        "401-77" to "Tele2 (Казахстан)", "255-01" to "Vodafone (Украина)", "255-03" to "Kyivstar", "255-06" to "lifecell",
    )

    fun operatorName(plmn: String?): String? = plmn?.let { OPERATORS[it] }

    /** Диапазон LTE по номеру канала (EARFCN, нисходящий). */
    fun lteBand(earfcn: Int): Int? = when (earfcn) {
        in 0..599 -> 1
        in 1200..1949 -> 3
        in 2400..2649 -> 5
        in 2750..3449 -> 7
        in 3450..3799 -> 8
        in 6150..6449 -> 20
        in 9210..9659 -> 28
        in 37750..38249 -> 38
        in 38250..38649 -> 39
        in 38650..39649 -> 40
        in 39650..41589 -> 41
        in 41590..43589 -> 42
        else -> null
    }

    fun lteBandMhz(band: Int): Int? = mapOf(1 to 2100, 3 to 1800, 5 to 850, 7 to 2600, 8 to 900, 20 to 800, 28 to 700, 38 to 2600, 39 to 1900, 40 to 2300, 41 to 2500, 42 to 3500)[band]

    /** Расстояние до вышки по timing advance: LTE ≈ 78 м на единицу, GSM ≈ 550 м. */
    fun distanceMeters(rat: Rat, ta: Int?): Int? = when {
        ta == null || ta < 0 || ta == Int.MAX_VALUE -> null
        rat == Rat.LTE || rat == Rat.NR -> ta * 78
        rat == Rat.GSM -> ta * 550
        else -> null
    }

    /** В LTE идентификатор соты = номер базовой станции (eNB) × 256 + номер сектора. */
    fun enb(cid: Long): Long = cid shr 8
    fun sector(cid: Long): Long = cid and 0xFF

    data class Alert(val level: Int, val text: String) // 0 — норма, 1 — внимание, 2 — подозрительно

    fun assess(serving: Cell?, neighbors: List<Cell>, simPlmn: String?, history: List<Cell>): List<Alert> {
        val out = mutableListOf<Alert>()
        if (serving == null) return listOf(Alert(1, "нет данных о соте: включи геолокацию и проверь, что есть сигнал"))
        if (serving.rat == Rat.GSM) {
            val wasFaster = history.any { it.rat == Rat.LTE || it.rat == Rat.NR }
            out += Alert(
                2,
                if (wasFaster) "телефон резко перешёл с 4G/5G на 2G — так действуют перехватчики IMSI (в 2G слабое шифрование)"
                else "подключение по 2G: шифрование слабое, перехватчики IMSI работают именно в 2G",
            )
        }
        if (neighbors.isEmpty() && (serving.dbm ?: -200) > -70) {
            out += Alert(1, "очень сильная сота без соседних — так может выглядеть фальшивая базовая станция")
        }
        val plmn = serving.plmn
        if (simPlmn != null && plmn != null && simPlmn != plmn) {
            out += Alert(1, "сеть ${operatorName(plmn) ?: plmn} не совпадает с оператором SIM (${operatorName(simPlmn) ?: simPlmn}) — роуминг или чужая вышка")
        }
        val sameCidOtherArea = history.any { it.cid != null && it.cid == serving.cid && it.rat == serving.rat && it.area != null && it.area != serving.area }
        if (sameCidOtherArea) out += Alert(2, "у той же соты сменился код зоны (LAC/TAC) — у настоящих вышек так не бывает")
        if (out.isEmpty()) out += Alert(0, "признаков фальшивой вышки не видно")
        return out
    }
}
