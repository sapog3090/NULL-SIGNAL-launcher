package com.nullsignal.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.hw.Ir
import com.nullsignal.core.util.Speed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

// ======================= СПИДТЕСТ =======================

private object SpeedNet {
    private const val DOWN = "https://speed.cloudflare.com/__down?bytes="
    private const val UP = "https://speed.cloudflare.com/__up"

    /** Задержка: время HTTP-запроса с нулевым телом (соединение переиспользуется). */
    fun ping(count: Int = 12): List<Double> {
        val out = ArrayList<Double>()
        repeat(count) {
            val t = System.nanoTime()
            val ok = runCatching {
                val c = URL(DOWN + "0").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
            }.isSuccess
            if (ok && it > 0) out += (System.nanoTime() - t) / 1e6 // первый замер — с рукопожатием TLS, не считаем
        }
        return out
    }

    suspend fun download(durationMs: Long, streams: Int, onSample: (Double) -> Unit): Double = coroutineScope {
        val bytes = AtomicLong()
        val start = System.currentTimeMillis()
        val deadline = start + durationMs
        val workers = (1..streams).map {
            launch(Dispatchers.IO) {
                val buf = ByteArray(64 * 1024)
                while (System.currentTimeMillis() < deadline && isActive) {
                    runCatching {
                        val c = URL(DOWN + 25_000_000).openConnection() as HttpURLConnection
                        c.connectTimeout = 6000; c.readTimeout = 6000
                        try {
                            c.inputStream.use { inp ->
                                while (System.currentTimeMillis() < deadline) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    bytes.addAndGet(n.toLong())
                                }
                            }
                        } finally { c.disconnect() }
                    }.onFailure { delay(300) }
                }
            }
        }
        sampler(bytes, start, deadline, onSample)
        workers.joinAll()
        Speed.mbps(bytes.get(), System.currentTimeMillis() - start)
    }

    suspend fun upload(durationMs: Long, streams: Int, onSample: (Double) -> Unit): Double = coroutineScope {
        val bytes = AtomicLong()
        val start = System.currentTimeMillis()
        val deadline = start + durationMs
        val chunk = ByteArray(64 * 1024).also { java.util.Random().nextBytes(it) }
        val workers = (1..streams).map {
            launch(Dispatchers.IO) {
                while (System.currentTimeMillis() < deadline && isActive) {
                    runCatching {
                        val size = 8 * 1024 * 1024
                        val c = URL(UP).openConnection() as HttpURLConnection
                        c.connectTimeout = 6000; c.readTimeout = 8000
                        c.requestMethod = "POST"
                        c.doOutput = true
                        c.setFixedLengthStreamingMode(size)
                        c.setRequestProperty("Content-Type", "application/octet-stream")
                        try {
                            var sent = 0
                            c.outputStream.use { o ->
                                while (sent < size && System.currentTimeMillis() < deadline) {
                                    val n = minOf(chunk.size, size - sent)
                                    o.write(chunk, 0, n)
                                    sent += n
                                    bytes.addAndGet(n.toLong())
                                }
                            }
                            if (sent == size) c.responseCode else 0
                        } finally { c.disconnect() }
                    }.onFailure { delay(300) }
                }
            }
        }
        sampler(bytes, start, deadline, onSample)
        workers.joinAll()
        Speed.mbps(bytes.get(), System.currentTimeMillis() - start)
    }

    private suspend fun sampler(bytes: AtomicLong, start: Long, deadline: Long, onSample: (Double) -> Unit) {
        var last = 0L
        var lastT = start
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            val b = bytes.get()
            val t = System.currentTimeMillis()
            onSample(Speed.mbps(b - last, t - lastT))
            last = b; lastT = t
        }
    }
}

@Composable
fun SpeedScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("speed", 0) }
    var phase by remember { mutableStateOf("") }
    var live by remember { mutableFloatStateOf(0f) }
    val samples = remember { mutableStateListOf<Float>() }
    var ping by remember { mutableStateOf<Double?>(null) }
    var jitter by remember { mutableStateOf<Double?>(null) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf(prefs.getString("hist", "")!!.lines().filter { it.isNotBlank() }) }

    fun start() {
        job?.cancel()
        ping = null; jitter = null; down = null; up = null; samples.clear(); live = 0f
        job = scope.launch {
            try {
                phase = "ПИНГ"
                val rtts = withContext(Dispatchers.IO) { SpeedNet.ping() }
                if (rtts.isEmpty()) { phase = "нет связи с сервером"; return@launch }
                ping = Speed.median(rtts); jitter = Speed.jitter(rtts)
                phase = "ЗАГРУЗКА"
                samples.clear()
                down = SpeedNet.download(9000, 4) { v -> live = v.toFloat(); samples += v.toFloat() }
                phase = "ОТДАЧА"
                samples.clear()
                up = SpeedNet.upload(8000, 3) { v -> live = v.toFloat(); samples += v.toFloat() }
                phase = "ГОТОВО"
                val line = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru")).format(java.util.Date()) +
                    "  ↓%.1f ↑%.1f Мбит/с  %.0f мс".format(down, up, ping)
                history = (listOf(line) + history).take(10)
                prefs.edit().putString("hist", history.joinToString("\n")).apply()
            } finally {
                if (phase != "ГОТОВО" && phase != "нет связи с сервером") phase = "ОСТАНОВЛЕНО"
            }
        }
    }

    ModuleScreen("СПИДТЕСТ", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Сервер Cloudflare рядом с тобой. Тест скачает и отправит ~100–300 МБ — на мобильном интернете осторожно.", style = Ns.small)
            Spacer(Modifier.height(10.dp))
            val running = job?.isActive == true && phase != "ГОТОВО"
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(phase.ifEmpty { "ГОТОВ" }, style = Ns.label.copy(color = Ns.Red))
                    Text(
                        if (running && phase != "ПИНГ") "%.1f".format(live) else down?.let { "%.1f".format(it) } ?: "—",
                        style = Ns.title.copy(fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = 0.sp),
                    )
                    Text("Мбит/с", style = Ns.small)
                }
            }
            if (samples.size > 1) {
                Canvas(Modifier.fillMaxWidth().height(80.dp).padding(vertical = 8.dp)) {
                    val max = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f)
                    val step = size.width / (samples.size - 1).coerceAtLeast(1)
                    val p = Path()
                    samples.forEachIndexed { i, v -> val x = i * step; val y = size.height - v / max * size.height; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                    drawPath(p, if (phase == "ОТДАЧА") Ns.Green else Ns.Red, style = Stroke(3f))
                }
            }
            TermButton(if (running) "ИДЁТ ТЕСТ…" else "ЗАПУСТИТЬ ТЕСТ", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Ns.Red, enabled = !running) { start() }
            if (running) TermButton("СТОП", Modifier.fillMaxWidth(), color = Ns.Dim) { job?.cancel() }
            if (ping != null) {
                Panel(Modifier.padding(top = 8.dp), color = Ns.Red) {
                    KeyValue("пинг", "%.0f мс".format(ping))
                    jitter?.let { KeyValue("джиттер", "%.1f мс".format(it)) }
                    down?.let { KeyValue("загрузка", "%.1f Мбит/с".format(it)) }
                    up?.let { KeyValue("отдача", "%.1f Мбит/с".format(it)) }
                    val d = down
                    val p = ping
                    if (d != null && p != null) Text(Speed.verdict(d, p), style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (history.isNotEmpty()) {
                SectionTitle("история")
                history.forEach { Text(it, style = Ns.small) }
            }
        }
    }
}

// ======================= ДАТЧИКИ =======================

@Composable
private fun rememberSensor(type: Int, rate: Int = SensorManager.SENSOR_DELAY_GAME): FloatArray? {
    val ctx = LocalContext.current
    var values by remember(type) { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val s = sm?.getDefaultSensor(type)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values = e.values.clone() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (s != null) sm?.registerListener(l, s, rate)
        onDispose { sm?.unregisterListener(l) }
    }
    return values
}

@Composable
fun SensorsScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ModuleScreen("ДАТЧИКИ", onBack) {
        NsTabs(listOf("МЕТАЛЛ", "КОМПАС", "ВСЕ"), tab, Ns.Red) { tab = it }
        when (tab) {
            0 -> MetalTab()
            1 -> CompassTab()
            else -> AllSensorsTab()
        }
    }
}

@Composable
private fun ColumnScope.MetalTab() {
    val m = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val history = remember { mutableStateListOf<Float>() }
    var baseline by remember { mutableStateOf<Float?>(null) }
    var sound by remember { mutableStateOf(true) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    // Новое значение датчика обрабатываем в эффекте, а не во время отрисовки
    androidx.compose.runtime.LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        val value = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (history.size > 150) history.removeAt(0)
        history += value
        if (baseline == null && history.size > 20) baseline = history.takeLast(20).average().toFloat()
        val b = baseline
        if (sound && b != null && abs(value - b) > 25f) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
    }
    val mag = history.lastOrNull()
    val base = baseline
    val delta = if (mag != null && base != null) abs(mag - base) else 0f
    val alarm = delta > 25f
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (m == null) {
            Text("магнитометр недоступен", style = Ns.text.copy(color = Ns.Red))
            return@Column
        }
        Text(
            "Металлоискатель на магнитометре: веди верхней частью телефона вдоль стены, мебели, предмета. " +
                "Магнитное поле Земли — 25–65 мкТл; железо, магниты, провода под током и электроника его искажают.",
            style = Ns.small,
        )
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (alarm) "ОБНАРУЖЕНО" else "ФОН", style = Ns.label.copy(color = if (alarm) Ns.Red else Ns.Dim))
                Text("%.0f".format(mag ?: 0f), style = Ns.title.copy(fontSize = 64.sp, lineHeight = 68.sp, color = if (alarm) Ns.Red else Ns.Green, letterSpacing = 0.sp))
                Text("мкТл · отклонение %.0f".format(delta), style = Ns.small)
            }
        }
        HudBar((delta / 100f).coerceIn(0f, 1f), if (alarm) Ns.Red else Ns.Dim, height = 10.dp)
        Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical = 8.dp)) {
            if (history.size < 2) return@Canvas
            val max = (history.maxOrNull() ?: 1f).coerceAtLeast(80f)
            val step = size.width / (history.size - 1)
            val p = Path()
            history.forEachIndexed { i, v -> val x = i * step; val y = size.height - v / max * size.height; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            drawPath(p, Ns.Red, style = Stroke(2.5f))
            baseline?.let { b -> val y = size.height - b / max * size.height; drawLine(Ns.Dim, Offset(0f, y), Offset(size.width, y), 1f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TermButton("КАЛИБРОВАТЬ", Modifier.weight(1f)) { baseline = mag }
            TermButton(if (sound) "ЗВУК ВКЛ" else "ЗВУК ВЫКЛ", Modifier.weight(1f), color = if (sound) Ns.Red else Ns.Dim) { sound = !sound }
        }
        Text("Калибруй вдали от металла, потом подноси к проверяемому месту.", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ColumnScope.CompassTab() {
    val acc = rememberSensor(Sensor.TYPE_ACCELEROMETER)
    val mag = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)
    var azimuth by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var roll by remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(acc, mag) {
        if (acc == null || mag == null) return@LaunchedEffect
        val r = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, null, acc, mag)) {
            val o = FloatArray(3)
            SensorManager.getOrientation(r, o)
            val target = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
            // сглаживание с учётом перехода через 0°
            var d = target - azimuth
            if (d > 180) d -= 360
            if (d < -180) d += 360
            azimuth = (azimuth + d * 0.15f + 360) % 360
            pitch = Math.toDegrees(o[1].toDouble()).toFloat()
            roll = Math.toDegrees(o[2].toDouble()).toFloat()
        }
    }
    val dirs = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        if (acc == null || mag == null) {
            Text("нужны акселерометр и магнитометр", style = Ns.text.copy(color = Ns.Red))
            return@Column
        }
        Text("%.0f° %s".format(azimuth, dirs[((azimuth + 22.5f) / 45f).toInt() % 8]), style = Ns.title.copy(fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = 0.sp))
        Canvas(Modifier.fillMaxWidth(0.8f).aspectRatio(1f).padding(10.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val rad = size.minDimension / 2
            drawCircle(Ns.Faint, rad, c, style = Stroke(2f))
            rotate(-azimuth, c) {
                for (k in 0 until 72) {
                    val a = Math.toRadians(k * 5.0 - 90)
                    val len = if (k % 18 == 0) 22f else if (k % 2 == 0) 10f else 5f
                    val o = Offset(c.x + (rad * kotlin.math.cos(a)).toFloat(), c.y + (rad * kotlin.math.sin(a)).toFloat())
                    val i = Offset(c.x + ((rad - len) * kotlin.math.cos(a)).toFloat(), c.y + ((rad - len) * kotlin.math.sin(a)).toFloat())
                    drawLine(if (k == 0) Ns.Red else Ns.Dim, i, o, if (k % 18 == 0) 4f else 1.5f)
                }
                drawLine(Ns.Red, c, Offset(c.x, c.y - rad * 0.75f), 6f)
                drawLine(Ns.Green, c, Offset(c.x, c.y + rad * 0.6f), 4f)
            }
            drawCircle(Ns.Red, 6f, c)
            drawLine(Ns.Green, Offset(c.x, c.y - rad - 4f), Offset(c.x, c.y - rad + 18f), 3f)
        }
        SectionTitle("уровень")
        Canvas(Modifier.fillMaxWidth(0.5f).aspectRatio(1f).padding(6.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val rad = size.minDimension / 2
            drawCircle(Ns.Faint, rad, c, style = Stroke(2f))
            drawCircle(Ns.Faint, rad * 0.2f, c, style = Stroke(1f))
            val bx = (roll / 45f).coerceIn(-1f, 1f) * rad * 0.8f
            val by = (-pitch / 45f).coerceIn(-1f, 1f) * rad * 0.8f
            val level = abs(pitch) < 2 && abs(roll) < 2
            drawCircle(if (level) Ns.Green else Ns.Red, rad * 0.16f, Offset(c.x + bx, c.y + by))
        }
        Text("наклон %.1f° · крен %.1f°".format(pitch, roll), style = Ns.small)
        Text("Компас врёт рядом с металлом и магнитами: калибруй восьмёркой в воздухе.", style = Ns.small.copy(color = Ns.Dim, textAlign = TextAlign.Center), modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun ColumnScope.AllSensorsTab() {
    val ctx = LocalContext.current
    val sensors = remember { ctx.getSystemService(SensorManager::class.java)?.getSensorList(Sensor.TYPE_ALL).orEmpty() }
    var open by remember { mutableStateOf<Sensor?>(null) }
    Text("датчиков в телефоне: ${sensors.size} · нажми, чтобы увидеть живые значения", style = Ns.small)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(sensors) { s ->
            val isOpen = open == s
            Column(Modifier.fillMaxWidth().hud(if (isOpen) Ns.Red else Ns.Faint).clickable { open = if (isOpen) null else s }.padding(8.dp)) {
                Text(s.name, style = Ns.text)
                Text("${s.stringType.removePrefix("android.sensor.")} · ${s.vendor} · ${"%.2f".format(s.power)} мА", style = Ns.small)
                if (isOpen) SensorValues(s)
            }
        }
    }
}

@Composable
private fun SensorValues(s: Sensor) {
    val v = rememberSensor(s.type, SensorManager.SENSOR_DELAY_UI)
    Text(
        v?.take(6)?.joinToString("   ") { "%.3f".format(it) } ?: "жду данных… (некоторые датчики шлют значения только при изменении)",
        style = Ns.small.copy(color = Ns.Green),
        modifier = Modifier.padding(top = 4.dp),
    )
    Text("диапазон ${s.maximumRange} · точность ${s.resolution}", style = Ns.small.copy(fontSize = 10.sp, color = Ns.Dim))
}

// ======================= NFC =======================

private fun hex(b: ByteArray?): String = b?.joinToString(":") { "%02X".format(it) } ?: "—"

private fun decodeRecord(r: NdefRecord): String = runCatching {
    when {
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) -> "ссылка: ${r.toUri()}"
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
            val p = r.payload
            val status = p[0].toInt()
            val langLen = status and 0x3F
            val cs = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
            "текст (${String(p, 1, langLen)}): ${String(p, 1 + langLen, p.size - 1 - langLen, cs)}"
        }
        r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> "ссылка: ${String(r.type)}"
        r.tnf == NdefRecord.TNF_MIME_MEDIA -> "данные ${String(r.type)}: ${String(r.payload).take(200)}"
        r.tnf == NdefRecord.TNF_EXTERNAL_TYPE -> "${String(r.type)}: ${String(r.payload).take(120)}"
        else -> "запись TNF=${r.tnf}, ${r.payload.size} байт"
    }
}.getOrDefault("нечитаемая запись")

private fun describeTag(tag: Tag): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    out += "UID" to hex(tag.id)
    out += "технологии" to tag.techList.joinToString { it.substringAfterLast('.') }
    NfcA.get(tag)?.let { out += "ATQA / SAK" to "${hex(it.atqa)} / %02X".format(it.sak) }
    MifareClassic.get(tag)?.let {
        val t = when (it.type) { MifareClassic.TYPE_CLASSIC -> "Classic"; MifareClassic.TYPE_PLUS -> "Plus"; MifareClassic.TYPE_PRO -> "Pro"; else -> "?" }
        out += "чип" to "MIFARE $t ${it.size / 1024}K: ${it.sectorCount} секторов"
        out += "что это" to "проездные старого типа, пропуска, ключи домофона"
    }
    MifareUltralight.get(tag)?.let {
        out += "чип" to if (it.type == MifareUltralight.TYPE_ULTRALIGHT_C) "MIFARE Ultralight C" else "MIFARE Ultralight"
        out += "что это" to "одноразовые билеты, NFC-метки"
    }
    IsoDep.get(tag)?.let {
        out += "чип" to "смарт-карта ISO 14443-4"
        out += "что это" to "банковская карта, новые проездные, пропуск, загранпаспорт: данные защищены"
        it.historicalBytes?.let { h -> out += "historical bytes" to hex(h) }
    }
    val ndef = Ndef.get(tag)
    if (ndef != null) {
        out += "NDEF" to "${ndef.type.substringAfterLast('.')} · до ${ndef.maxSize} байт · ${if (ndef.isWritable) "можно записать" else "только чтение"}"
        val msg = ndef.cachedNdefMessage
        msg?.records?.forEachIndexed { i, r -> out += "запись ${i + 1}" to decodeRecord(r) }
        if (msg == null) out += "содержимое" to "пусто"
    } else if (NdefFormatable.get(tag) != null) {
        out += "NDEF" to "не размечена — можно записать"
    }
    return out
}

private fun writeTag(tag: Tag, msg: NdefMessage): String = runCatching {
    val ndef = Ndef.get(tag)
    if (ndef != null) {
        ndef.connect()
        try {
            if (!ndef.isWritable) return "метка защищена от записи"
            if (msg.toByteArray().size > ndef.maxSize) return "не помещается: метка на ${ndef.maxSize} байт"
            ndef.writeNdefMessage(msg)
        } finally { ndef.close() }
        "✓ записано"
    } else {
        val f = NdefFormatable.get(tag) ?: return "эта метка не поддерживает NDEF"
        f.connect()
        try { f.format(msg) } finally { f.close() }
        "✓ размечено и записано"
    }
}.getOrElse { "ошибка записи: ${it.message}" }

@Composable
fun NfcScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val adapter = remember { NfcAdapter.getDefaultAdapter(ctx) }
    val info = remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val pending = remember { mutableStateOf<NdefMessage?>(null) }
    var writeResult by remember { mutableStateOf<String?>(null) }
    var payload by remember { mutableStateOf("") }
    var isUrl by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    OnResume { enabled = adapter?.isEnabled == true }
    val prefs = remember { ctx.getSharedPreferences("nfc", 0) }
    var emulating by remember { mutableStateOf(false) }
    var reads by remember { mutableStateOf(0) }
    // журнал прочитанных меток, как «Сохранённые» у Flipper
    var log by remember { mutableStateOf(prefs.getString("log", "").orEmpty().lines().filter { it.isNotBlank() }) }

    // эмуляция: телефон сам становится меткой, поэтому чтение на это время выключаем
    if (adapter != null && activity != null && enabled && emulating) {
        DisposableEffect(Unit) {
            val emu = runCatching { android.nfc.cardemulation.CardEmulation.getInstance(adapter) }.getOrNull()
            val service = android.content.ComponentName(ctx, com.nullsignal.launcher.sys.NfcTagService::class.java)
            runCatching { emu?.setPreferredService(activity, service) }
            onDispose {
                com.nullsignal.launcher.sys.NfcEmu.tag = null
                runCatching { emu?.unsetPreferredService(activity) }
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) { reads = com.nullsignal.launcher.sys.NfcEmu.reads; kotlinx.coroutines.delay(400) }
        }
    }

    if (adapter != null && activity != null && enabled && !emulating) {
        DisposableEffect(adapter, activity) {
            val main = Handler(Looper.getMainLooper())
            val cb = NfcAdapter.ReaderCallback { tag ->
                val w = pending.value
                val res = if (w != null) writeTag(tag, w) else null
                val desc = runCatching { describeTag(tag) }.getOrDefault(listOf("ошибка" to "не удалось прочитать"))
                main.post {
                    info.value = desc
                    val uid = desc.firstOrNull { it.first == "UID" }?.second ?: "?"
                    val kind = desc.firstOrNull { it.first == "технологии" }?.second.orEmpty()
                    val line = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date()) + "  " + uid + "  " + kind
                    log = (listOf(line) + log).take(30)
                    prefs.edit().putString("log", log.joinToString("\n")).apply()
                    if (res != null) { writeResult = res; if (res.startsWith("✓")) pending.value = null }
                }
            }
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            runCatching { adapter.enableReaderMode(activity, cb, flags, null) }
            onDispose { runCatching { adapter.disableReaderMode(activity) } }
        }
    }

    ModuleScreen("NFC", onBack) {
        when {
            adapter == null -> { Text("в этом телефоне нет NFC", style = Ns.text.copy(color = Ns.Red)); return@ModuleScreen }
            !enabled -> {
                Text("NFC выключен", style = Ns.text.copy(color = Ns.Amber))
                TermButton("ВКЛЮЧИТЬ В НАСТРОЙКАХ", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                return@ModuleScreen
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Panel(color = if (pending.value != null) Ns.Red else Ns.Dim) {
                Text(if (pending.value != null) "ПРИЛОЖИ МЕТКУ ДЛЯ ЗАПИСИ" else "ПРИЛОЖИ МЕТКУ ИЛИ КАРТУ", style = Ns.title.copy(fontSize = 16.sp, color = if (pending.value != null) Ns.Red else Ns.Green))
                Text("к задней крышке, обычно рядом с камерой", style = Ns.small)
            }
            info.value?.let { list ->
                SectionTitle("метка", Ns.Red)
                SelectionContainer { Column { list.forEach { (k, v) -> KeyValue(k, v) } } }
            }
            SectionTitle("записать на метку")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermButton("ССЫЛКА", Modifier.weight(1f), color = if (isUrl) Ns.Red else Ns.Dim) { isUrl = true }
                TermButton("ТЕКСТ", Modifier.weight(1f), color = if (!isUrl) Ns.Red else Ns.Dim) { isUrl = false }
            }
            Spacer(Modifier.height(6.dp))
            NsField(payload, { payload = it }, if (isUrl) "https://… (откроется при касании телефоном)" else "текст")
            TermButton(if (pending.value != null) "ОТМЕНИТЬ ЗАПИСЬ" else "ПОДГОТОВИТЬ ЗАПИСЬ", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red, enabled = payload.isNotBlank() || pending.value != null) {
                if (pending.value != null) { pending.value = null; writeResult = null } else {
                    pending.value = NdefMessage(
                        if (isUrl) NdefRecord.createUri(if (payload.contains("://")) payload.trim() else "https://${payload.trim()}")
                        else NdefRecord.createTextRecord("ru", payload),
                    )
                    writeResult = "жду метку…"
                }
            }
            writeResult?.let { Text(it, style = Ns.small.copy(color = if (it.startsWith("✓")) Ns.Green else Ns.Amber), modifier = Modifier.padding(vertical = 6.dp)) }
            Text(
                "Подойдут NTAG213/215/216 (наклейки, брелоки). Запиши ссылку на свой профиль, Wi-Fi-страницу или контакт.",
                style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(top = 8.dp),
            )
            SectionTitle("эмуляция: телефон сам становится меткой")
            Text(
                "Как у Flipper: включи — и другой телефон, приложенный к этому, откроет ссылку или покажет текст из поля выше. Без всякой наклейки. " +
                    "Экран должен быть включён.",
                style = Ns.small,
            )
            TermButton(
                if (emulating) "ОСТАНОВИТЬ ЭМУЛЯЦИЮ" else "ВКЛЮЧИТЬ ЭМУЛЯЦИЮ", Modifier.fillMaxWidth().padding(top = 8.dp),
                color = if (emulating) Ns.Dim else Ns.Red, enabled = emulating || payload.isNotBlank(),
            ) {
                if (emulating) {
                    emulating = false
                } else {
                    val msg = if (isUrl) com.nullsignal.core.hw.Ndef.uri(if (payload.contains("://")) payload.trim() else "https://${payload.trim()}")
                    else com.nullsignal.core.hw.Ndef.text(payload)
                    com.nullsignal.launcher.sys.NfcEmu.reads = 0
                    com.nullsignal.launcher.sys.NfcEmu.tag = com.nullsignal.core.hw.NdefT4(msg)
                    pending.value = null
                    emulating = true
                }
            }
            if (emulating) {
                Text("метка активна · считываний: $reads", style = Ns.text.copy(color = Ns.Red), modifier = Modifier.padding(top = 6.dp))
                Text("если вместо ссылки открывается платёжное приложение — в настройках NFC выбери NULL//SIGNAL для «других» карт", style = Ns.small)
            }
            if (log.isNotEmpty()) {
                SectionTitle("журнал меток · ${log.size}")
                log.forEach { Text(it, style = Ns.small.copy(color = Ns.Text2)) }
                Text("[очистить]", style = Ns.small.copy(color = Ns.Red), modifier = Modifier.clickable { log = emptyList(); prefs.edit().remove("log").apply() }.padding(vertical = 6.dp))
            }
        }
    }
}
