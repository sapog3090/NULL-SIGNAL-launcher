package com.nullsignal.core.hw

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Анализ звука: спектр (БПФ), отдельные тоны и поиск ультразвуковых маяков. */
object Spectrum {
    /** Быстрое преобразование Фурье на месте. Длина массивов — степень двойки. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0 && im.size == n) { "длина должна быть степенью двойки" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Спектр окна звука в децибелах: 0 дБ — синус на полную громкость. */
    class Analyzer(val n: Int = 4096, val rate: Int = 44100) {
        val bins = n / 2
        private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        private val re = DoubleArray(n)
        private val im = DoubleArray(n)

        fun hz(bin: Int): Double = bin.toDouble() * rate / n
        fun bin(hz: Double): Int = (hz * n / rate).toInt().coerceIn(0, bins - 1)

        fun analyze(pcm: ShortArray, offset: Int = 0, out: FloatArray = FloatArray(bins)): FloatArray {
            for (i in 0 until n) {
                re[i] = pcm[offset + i] / 32768.0 * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            val norm = n / 4.0 // окно Ханна пропускает половину амплитуды, и ещё половина уходит в зеркальную часть спектра
            for (k in 0 until bins) out[k] = (20 * log10(max(sqrt(re[k] * re[k] + im[k] * im[k]) / norm, 1e-7))).toFloat()
            return out
        }
    }

    class Peak(val hz: Double, val db: Float)

    /** Самые сильные отдельные тоны: вершины спектра, заметные над соседями. */
    fun peaks(db: FloatArray, rate: Int, minHz: Double = 60.0, floorDb: Float = -70f, count: Int = 5): List<Peak> {
        val n = db.size * 2
        val from = max(1, (minHz * n / rate).toInt())
        val found = ArrayList<Pair<Int, Peak>>()
        for (k in from until db.size - 1) {
            if (db[k] < floorDb || db[k] <= db[k - 1] || db[k] < db[k + 1]) continue
            // вершина параболы по трём точкам даёт частоту точнее шага сетки
            val d = db[k - 1] - 2 * db[k] + db[k + 1]
            val shift = if (d == 0f) 0f else 0.5f * (db[k - 1] - db[k + 1]) / d
            found += k to Peak((k + shift).toDouble() * rate / n, db[k])
        }
        val out = ArrayList<Pair<Int, Peak>>()
        for (p in found.sortedByDescending { it.second.db }) {
            if (out.none { kotlin.math.abs(it.first - p.first) <= 3 }) out += p
            if (out.size >= count) break
        }
        return out.map { it.second }
    }

    /** Типичный уровень шума в полосе (медиана). */
    fun noiseFloor(db: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return -120f
        val part = db.copyOfRange(from, to)
        part.sort()
        return part[part.size / 2]
    }

    /** Нота по частоте: «Ля 4», «До# 5». */
    fun note(hz: Double): String? {
        if (hz < 20 || hz > 8000) return null
        val names = listOf("До", "До#", "Ре", "Ре#", "Ми", "Фа", "Фа#", "Соль", "Соль#", "Ля", "Ля#", "Си")
        val midi = Math.round(69 + 12 * Math.log(hz / 440.0) / Math.log(2.0)).toInt()
        return names[((midi % 12) + 12) % 12] + " " + (midi / 12 - 1)
    }

    /**
     * Ультразвуковые маяки: узкий тон выше 17.5 кГц, который держится секундами.
     * Человек его почти не слышит, а микрофон — слышит: так реклама и приложения могут «узнавать» устройства рядом.
     */
    class BeaconWatch(private val rate: Int = 44100, private val minHz: Double = 17_500.0, private val minSeconds: Double = 1.5) {
        class Beacon(val hz: Double, val db: Float, val seconds: Double)

        private class Track(var first: Double, var last: Double, var hz: Double, var db: Float)

        private val tracks = HashMap<Int, Track>()

        /** [time] — время кадра в секундах. Возвращает тоны, которые держатся дольше порога. */
        fun feed(db: FloatArray, time: Double): List<Beacon> {
            val n = db.size * 2
            val from = (minHz * n / rate).toInt().coerceIn(1, db.size - 2)
            val to = ((rate / 2.0 - 600) * n / rate).toInt().coerceIn(from + 1, db.size - 1)
            val floor = noiseFloor(db, from, to)
            for (k in from until to) {
                if (db[k] < -85f || db[k] < floor + 18f || db[k] <= db[k - 1] || db[k] < db[k + 1]) continue
                val hz = k.toDouble() * rate / n
                val bucket = (hz / 150).toInt()
                val t = tracks[bucket] ?: tracks[bucket - 1] ?: tracks[bucket + 1]
                if (t != null && time - t.last < 0.6) {
                    t.last = time
                    t.hz = hz
                    t.db = max(t.db * 0.7f + db[k] * 0.3f, db[k] - 6f)
                } else {
                    tracks[bucket] = Track(time, time, hz, db[k])
                }
            }
            tracks.values.removeAll { time - it.last > 0.6 }
            return tracks.values.filter { it.last - it.first >= minSeconds }.sortedByDescending { it.db }.map { Beacon(it.hz, it.db, it.last - it.first) }
        }

        fun reset() = tracks.clear()
    }
}
