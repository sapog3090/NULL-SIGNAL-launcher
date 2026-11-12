package com.nullsignal.core.spy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Звуковой модем: передаёт байты звуком между телефонами (динамик → микрофон), без сети.
 * 16 тонов = 4 бита на символ (MFSK). Кадр: преамбула, длина, данные, CRC-8.
 */
object SoundModem {
    const val RATE = 44100
    const val MAX_PAYLOAD = 200

    data class Band(val baseHz: Double, val stepHz: Double, val symbolMs: Int)

    /** Слышимый: 1.6–4.3 кГц — надёжно на любых динамиках и микрофонах. */
    val LOUD = Band(1600.0, 180.0, 80)

    /** «Тихий», почти ультразвук: 16.8–18.6 кГц — взрослые почти не слышат, но работает не на всех телефонах. */
    val QUIET = Band(16800.0, 120.0, 100)

    private val PREAMBLE = intArrayOf(0, 15, 0, 15, 3, 12)

    fun samplesPerSymbol(b: Band) = RATE * b.symbolMs / 1000
    fun freq(b: Band, k: Int) = b.baseHz + k * b.stepHz

    fun crc8(data: ByteArray): Int {
        var c = 0
        for (x in data) {
            c = c xor (x.toInt() and 0xff)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xff else (c shl 1) and 0xff }
        }
        return c
    }

    fun symbols(data: ByteArray): IntArray {
        require(data.size <= MAX_PAYLOAD) { "не больше $MAX_PAYLOAD байт" }
        val bytes = byteArrayOf(data.size.toByte()) + data + byteArrayOf(crc8(data).toByte())
        val out = ArrayList<Int>()
        PREAMBLE.forEach { out += it }
        bytes.forEach { b ->
            val v = b.toInt() and 0xff
            out += v shr 4
            out += v and 0xF
        }
        return out.toIntArray()
    }

    /** Длительность передачи в секундах. */
    fun seconds(dataSize: Int, band: Band = LOUD): Double = (PREAMBLE.size + (dataSize + 2) * 2 + 2) * band.symbolMs / 1000.0

    /** PCM 16 бит моно: тишина, символы с плавными фронтами, тишина. */
    fun encode(data: ByteArray, band: Band = LOUD, volume: Double = 0.8): ShortArray {
        val syms = symbols(data)
        val n = samplesPerSymbol(band)
        val out = ShortArray(n + syms.size * n + n)
        val fade = n / 12
        syms.forEachIndexed { si, k ->
            val f = freq(band, k)
            for (i in 0 until n) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                out[n + si * n + i] = (sin(2 * PI * f * i / RATE) * env * volume * 32767).toInt().toShort()
            }
        }
        return out
    }

    private fun goertzel(pcm: ShortArray, start: Int, len: Int, f: Double): Double {
        val c = 2 * cos(2 * PI * f / RATE)
        var s1 = 0.0
        var s2 = 0.0
        for (i in start until start + len) {
            val s = pcm[i] + c * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    private class Det(val tone: Int, val ratio: Double, val power: Double)

    /** Самый громкий из 16 тонов в центральных 60% символа и насколько он громче второго. */
    private fun detect(pcm: ShortArray, pos: Int, n: Int, band: Band): Det? {
        val start = pos + n / 5
        val len = n * 3 / 5
        if (start < 0 || start + len > pcm.size) return null
        var best = -1
        var bestP = 0.0
        var second = 0.0
        for (k in 0 until 16) {
            val p = goertzel(pcm, start, len, freq(band, k))
            if (p > bestP) { second = bestP; bestP = p; best = k } else if (p > second) second = p
        }
        if (best < 0) return null
        return Det(best, if (second <= 0) 1000.0 else bestP / second, bestP)
    }

    /** Ищет кадр в записи. Возвращает данные или null (ничего нет или не сошлась CRC). */
    fun decode(pcm: ShortArray, band: Band = LOUD): ByteArray? {
        val n = samplesPerSymbol(band)
        val hop = n / 8
        if (pcm.size < n * (PREAMBLE.size + 6)) return null
        val bestStart = findStart(pcm, band, tail = 4)
        if (bestStart < 0) return null
        for (delta in intArrayOf(0, -hop / 2, hop / 2, -hop, hop)) {
            decodeAt(pcm, bestStart + delta, n, band)?.let { return it }
        }
        return null
    }

    /**
     * Начало кадра (отсчёт, с которого идёт преамбула) или -1.
     * [tail] — сколько символов после преамбулы уже должно быть записано.
     */
    fun findStart(pcm: ShortArray, band: Band = LOUD, tail: Int = 0): Int {
        val n = samplesPerSymbol(band)
        val hop = n / 8
        var bestStart = -1
        var bestScore = 0.0
        var pos = 0
        val lastStart = pcm.size - n * (PREAMBLE.size + tail)
        while (pos <= lastStart) {
            if (bestStart >= 0 && pos > bestStart + 2 * n) break // лучший кадр найден и пройден
            val d0 = detect(pcm, pos, n, band)
            if (d0 != null && d0.tone == PREAMBLE[0] && d0.ratio > 3.0) {
                var score = min(d0.ratio, 50.0)
                var ok = true
                for (k in 1 until PREAMBLE.size) {
                    val d = detect(pcm, pos + k * n, n, band)
                    if (d == null || d.tone != PREAMBLE[k] || d.ratio < 2.0) { ok = false; break }
                    score += min(d.ratio, 50.0)
                }
                if (ok && score > bestScore) { bestScore = score; bestStart = pos }
            }
            pos += hop
        }
        return bestStart
    }

    /**
     * Где кончится кадр, начатый в [start] (по байту длины), или null, если длина ещё не записана
     * или не читается. Нужен приёмнику, чтобы понять: кадр уже целиком записан, но не сошёлся.
     */
    fun frameEnd(pcm: ShortArray, start: Int, band: Band = LOUD): Int? {
        val n = samplesPerSymbol(band)
        val hi = detect(pcm, start + PREAMBLE.size * n, n, band)?.tone ?: return null
        val lo = detect(pcm, start + (PREAMBLE.size + 1) * n, n, band)?.tone ?: return null
        val len = (hi shl 4) or lo
        if (len > MAX_PAYLOAD) return null
        return start + (PREAMBLE.size + (len + 2) * 2) * n
    }

    /** Амплитуда 16 тонов полосы в окне — для живого спектра (1.0 — синус на полную громкость). */
    fun spectrum(pcm: ShortArray, start: Int, len: Int, band: Band = LOUD): FloatArray {
        val out = FloatArray(16)
        if (start < 0 || len <= 0 || start + len > pcm.size) return out
        var max = 0.0
        val raw = DoubleArray(16) { k -> goertzel(pcm, start, len, freq(band, k)).also { if (it > max) max = it } }
        if (max <= 0) return out
        for (k in 0 until 16) out[k] = (kotlin.math.sqrt(raw[k]) / (len / 2.0) / 32767.0).toFloat().coerceIn(0f, 1f)
        return out
    }

    private fun decodeAt(pcm: ShortArray, start: Int, n: Int, band: Band): ByteArray? {
        var idx = PREAMBLE.size
        fun byte(): Int? {
            val hi = detect(pcm, start + idx * n, n, band)?.tone ?: return null
            val lo = detect(pcm, start + (idx + 1) * n, n, band)?.tone ?: return null
            idx += 2
            return (hi shl 4) or lo
        }
        val len = byte() ?: return null
        if (len > MAX_PAYLOAD) return null
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = (byte() ?: return null).toByte()
        val crc = byte() ?: return null
        return if (crc == crc8(data)) data else null
    }

    /** Громкость записи 0..1 (для индикатора). */
    fun level(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += pcm[i].toDouble() * pcm[i]
        return (kotlin.math.sqrt(sum / (to - from)) / 32767.0).toFloat().coerceIn(0f, 1f)
    }
    /**
     * Потоковый приёмник: кормишь кусками записи с микрофона, он отдаёт принятые кадры.
     * Пока кадра нет, держит в памяти ~10 символов; поймав преамбулу — пишет до конца кадра.
     */
    class Receiver(val band: Band = LOUD) {
        sealed interface Event {
            class Frame(val data: ByteArray) : Event
            data object Broken : Event
        }

        private val n = samplesPerSymbol(band)
        private var buf = ShortArray(n * 64)
        private var size = 0
        private var start = -1
        private var checked = 0
        private var weak = 0
        private var pending = 0
        private var prePower = 0.0

        /** Поймана преамбула, идёт приём кадра. */
        val receiving: Boolean get() = start >= 0

        /** Сколько символов кадра записано и сколько всего (null — длина ещё не прочитана). */
        var progress: Pair<Int, Int?> = 0 to null
            private set

        fun level(): Float = if (size == 0) 0f else SoundModem.level(buf, maxOf(0, size - n), size)

        fun spectrum(): FloatArray = if (size >= n) SoundModem.spectrum(buf, size - n, n, band) else FloatArray(16)

        fun feed(chunk: ShortArray, count: Int = chunk.size): List<Event> {
            if (count <= 0) return emptyList()
            if (size + count > buf.size) buf = buf.copyOf(maxOf(size + count, buf.size * 2))
            System.arraycopy(chunk, 0, buf, size, count)
            size += count
            pending += count
            if (pending < (if (start < 0) 2 * n else n)) return emptyList()
            pending = 0
            return step()
        }

        private fun drop(k: Int) {
            val m = k.coerceIn(0, size)
            if (m == 0) return
            System.arraycopy(buf, m, buf, 0, size - m)
            size -= m
            if (start >= 0) start -= m
        }

        private fun reset(dropTo: Int) {
            drop(dropTo)
            start = -1; checked = 0; weak = 0; progress = 0 to null
            if (buf.size > n * 256) buf = buf.copyOf(maxOf(n * 64, size))
        }

        private fun step(): List<Event> {
            if (start < 0) {
                val s = findStart(buf.copyOf(size), band)
                if (s < 0) { drop(size - n * 10); return emptyList() }
                start = s
                checked = PREAMBLE.size
                weak = 0
                prePower = (0 until PREAMBLE.size).mapNotNull { detect(buf, start + it * n, n, band)?.power }.average()
            }
            val end = if (size >= start + (PREAMBLE.size + 2) * n) frameEnd(buf, start, band) else null
            val total = end?.let { (it - start) / n }
            // в настоящем кадре каждый символ — чистый громкий тон; пропал на 3 символа подряд — передача оборвалась
            while (start + (checked + 1) * n <= size && (total == null || checked < total)) {
                val d = detect(buf, start + checked * n, n, band)
                weak = if (d == null || d.ratio < 1.3 || d.power < prePower * 0.05) weak + 1 else 0
                checked++
                if (weak >= 3) {
                    val wasFrame = checked > PREAMBLE.size + 6
                    reset(start + checked * n)
                    return if (wasFrame) listOf(Event.Broken) else emptyList()
                }
            }
            progress = checked to total
            if (end == null) {
                if (size >= start + (PREAMBLE.size + 3) * n) reset(start + PREAMBLE.size * n) // длина не читается — это была не преамбула
                return emptyList()
            }
            if (size < end + n / 2) return emptyList()
            val from = maxOf(0, start - n / 2)
            val data = decode(buf.copyOfRange(from, minOf(size, end + n)), band)
            reset(end)
            return listOf(if (data != null) Event.Frame(data) else Event.Broken)
        }
    }
}
