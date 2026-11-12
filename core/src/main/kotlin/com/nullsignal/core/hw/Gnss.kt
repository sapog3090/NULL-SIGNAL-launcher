package com.nullsignal.core.hw

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Спутники навигации: сводка и эвристики глушения/подмены GPS. Это признаки, а не доказательства. */
object Gnss {
    enum class System(val title: String) {
        GPS("GPS (США)"), GLONASS("ГЛОНАСС"), GALILEO("Galileo (ЕС)"), BEIDOU("BeiDou (Китай)"),
        QZSS("QZSS (Япония)"), IRNSS("NavIC (Индия)"), SBAS("SBAS"), UNKNOWN("?"),
    }

    data class Sat(val system: System, val svid: Int, val cn0: Float, val elevation: Float, val azimuth: Float, val used: Boolean)

    data class Fix(val gnssTime: Long, val systemTime: Long, val lat: Double, val lon: Double, val accuracy: Float)

    data class Alert(val level: Int, val text: String)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    fun stddev(xs: List<Float>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m).pow(2) } / (xs.size - 1))
    }

    fun assess(sats: List<Sat>, fixes: List<Fix>, secondsWithoutFix: Int): List<Alert> {
        val out = mutableListOf<Alert>()
        val visible = sats.filter { it.cn0 > 0 }
        if (visible.size >= 4 && visible.map { it.cn0 }.average() < 18 && secondsWithoutFix > 60) {
            out += Alert(2, "спутники видны, но сигнал от всех очень слабый и нет координат больше минуты — похоже на глушение")
        }
        val last = fixes.lastOrNull()
        if (last != null && kotlin.math.abs(last.gnssTime - last.systemTime) > 5000) {
            out += Alert(2, "время со спутников расходится с временем сети на ${kotlin.math.abs(last.gnssTime - last.systemTime) / 1000} с — признак подмены GPS")
        }
        fixes.zipWithNext().firstOrNull { (a, b) ->
            val dt = (b.systemTime - a.systemTime) / 1000.0
            dt in 0.0..60.0 && distanceMeters(a.lat, a.lon, b.lat, b.lon) > 50_000
        }?.let { out += Alert(2, "координаты прыгнули больше чем на 50 км за минуту — признак подмены GPS") }
        val used = sats.filter { it.used }
        if (used.size >= 6 && stddev(used.map { it.cn0 }) < 1.5) {
            out += Alert(1, "у всех спутников почти одинаковая сила сигнала — так бывает при подмене одним передатчиком")
        }
        if (out.isEmpty()) out += Alert(0, if (visible.isEmpty()) "спутников пока не видно — выйди под открытое небо" else "признаков глушения или подмены не видно")
        return out
    }
}
