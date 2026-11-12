package com.nullsignal.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.hw.Spectrum
import com.nullsignal.core.net.DropServer
import com.nullsignal.launcher.sys.NetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

// ======================= СПЕКТР =======================

private const val RATE = 44100
private const val FFT = 4096
private const val WF_W = 256
private const val WF_H = 110

/** Чёрный → кровь → кость: цвет по силе сигнала. */
private fun heatColor(t: Float): Int {
    val r: Int
    val g: Int
    val b: Int
    if (t < 0.5f) {
        val k = t / 0.5f
        r = (229 * k).toInt(); g = (17 * k).toInt(); b = (46 * k).toInt()
    } else {
        val k = (t - 0.5f) / 0.5f
        r = (229 + 3 * k).toInt(); g = (17 + 211 * k).toInt(); b = (46 + 174 * k).toInt()
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Самый сильный уровень в каждом из [cols] столбцов для полосы частот [fromHz, toHz]. */
private fun columns(db: FloatArray, cols: Int, fromHz: Double, toHz: Double): FloatArray {
    val out = FloatArray(cols) { -120f }
    if (db.isEmpty()) return out
    val a = (fromHz * FFT / RATE).toInt().coerceIn(0, db.size - 1)
    val b = (toHz * FFT / RATE).toInt().coerceIn(a + 1, db.size)
    for (k in a until b) {
        val c = ((k - a).toLong() * cols / (b - a)).toInt()
        if (db[k] > out[c]) out[c] = db[k]
    }
    for (c in 1 until cols) if (out[c] == -120f) out[c] = out[c - 1]
    return out
}

@SuppressLint("MissingPermission")
@Composable
fun SpectrumScreen(onBack: () -> Unit, onListened: () -> Unit = {}) {
    val ctx = LocalContext.current
    val (granted, request) = rememberPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
    var listening by remember { mutableStateOf(true) }
    var ultra by remember { mutableStateOf(false) }
    var db by remember { mutableStateOf(FloatArray(0)) }
    var peaks by remember { mutableStateOf<List<Spectrum.Peak>>(emptyList()) }
    var beacons by remember { mutableStateOf<List<Spectrum.BeaconWatch.Beacon>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var tone by remember { mutableIntStateOf(0) }
    val fromHz = if (ultra) 15_000.0 else 0.0
    val toHz = 22_000.0

    if (granted) {
        LaunchedEffect(listening, ultra) {
            if (!listening) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                val am = ctx.getSystemService(AudioManager::class.java)
                val raw = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = runCatching {
                    AudioRecord(
                        if (raw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC,
                        RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE),
                    )
                }.getOrNull()
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                    status = "микрофон недоступен — возможно, он занят другим приложением"
                    rec?.release()
                    listening = false
                    return@withContext
                }
                status = if (raw) "микрофон без обработки: ультразвук виден лучше всего" else "обычный микрофон: телефон может приглушать ультразвук"
                val analyzer = Spectrum.Analyzer(FFT, RATE)
                val watch = Spectrum.BeaconWatch(RATE)
                val buf = ShortArray(FFT)
                val hop = FFT / 2
                val pixels = IntArray(WF_W * WF_H) { heatColor(0f) }
                var time = 0.0
                try {
                    rec.startRecording()
                    while (isActive) {
                        System.arraycopy(buf, hop, buf, 0, FFT - hop)
                        var got = 0
                        while (got < hop && isActive) {
                            val k = rec.read(buf, FFT - hop + got, hop - got)
                            if (k <= 0) break
                            got += k
                        }
                        if (got < hop) { delay(10); continue }
                        val out = analyzer.analyze(buf)
                        time += hop.toDouble() / RATE
                        beacons = watch.feed(out, time)
                        // полминуты честного прослушивания — для сюжета этого достаточно
                        if (time >= 30.0 && time - hop.toDouble() / RATE < 30.0) withContext(Dispatchers.Main) { onListened() }
                        peaks = Spectrum.peaks(out, RATE, count = 3)
                        db = out
                        // водопад: сдвигаем картинку вверх и дописываем строку снизу
                        System.arraycopy(pixels, WF_W, pixels, 0, WF_W * (WF_H - 1))
                        val row = columns(out, WF_W, fromHz, toHz)
                        for (x in 0 until WF_W) pixels[WF_W * (WF_H - 1) + x] = heatColor(((row[x] + 100f) / 75f).coerceIn(0f, 1f))
                        picture = Bitmap.createBitmap(pixels, WF_W, WF_H, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                } finally {
                    runCatching { rec.stop() }
                    rec.release()
                }
            }
        }
    }

    // генератор тона: для проверки слуха и самого детектора
    LaunchedEffect(tone) {
        if (tone == 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192))
                .build()
            val chunk = ShortArray(2048)
            var phase = 0.0
            val step = 2 * PI * tone / RATE
            try {
                track.play()
                while (isActive) {
                    for (i in chunk.indices) { chunk[i] = (sin(phase) * 0.35 * 32767).toInt().toShort(); phase += step }
                    if (phase > 1e6) phase %= 2 * PI
                    if (track.write(chunk, 0, chunk.size) <= 0) break
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    ModuleScreen("СПЕКТР", onBack) {
        if (!granted) {
            PermissionGate("Чтобы видеть звук, нужен микрофон. Звук разбирается на телефоне и никуда не записывается.", request)
            return@ModuleScreen
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (beacons.isEmpty()) {
                AlertRow(0, if (listening) "Ультразвуковых маяков не слышно" else "Прослушивание остановлено", "слежу за полосой 17,5–21,5 кГц: устойчивый тон там — повод насторожиться")
            } else {
                beacons.take(3).forEach { b ->
                    AlertRow(
                        2, "Ультразвук ${"%.1f".format(b.hz / 1000)} кГц держится ${b.seconds.toInt()} с",
                        "Тон, который человек почти не слышит, а микрофон ловит. Так работают маяки слежения в рекламе и магазинах; " +
                            "ещё его дают датчики движения, отпугиватели и некоторые блоки питания. Отойди и посмотри, пропадёт ли.",
                    )
                }
            }
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("0–22 кГц", if (!ultra) Ns.Red else Ns.Dim, Modifier.clickable { ultra = false })
                Chip("УЛЬТРАЗВУК 15–22 кГц", if (ultra) Ns.Red else Ns.Dim, Modifier.clickable { ultra = true })
                Chip(if (listening) "ПАУЗА" else "СЛУШАТЬ", Ns.Green, Modifier.clickable { listening = !listening })
            }
            val cols = columns(db, 128, fromHz, toHz)
            Canvas(Modifier.fillMaxWidth().height(150.dp).hud(Ns.Faint)) {
                val w = size.width / cols.size
                // полоса, где ищем маяки
                val x0 = (((17_500.0 - fromHz) / (toHz - fromHz)).coerceIn(0.0, 1.0) * size.width).toFloat()
                drawRect(Ns.Red.copy(alpha = 0.08f), Offset(x0, 0f), Size(size.width - x0, size.height))
                listOf(-20f, -40f, -60f, -80f).forEach { d ->
                    val y = size.height * (-d / 100f)
                    drawLine(Ns.Faint, Offset(0f, y), Offset(size.width, y), 1f)
                }
                cols.forEachIndexed { i, v ->
                    val h = size.height * ((v + 100f) / 100f).coerceIn(0f, 1f)
                    drawRect(if (i * w >= x0) Ns.Red else Ns.Green, Offset(i * w, size.height - h), Size(w * 0.8f, h))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..4) Text("%.1f".format((fromHz + (toHz - fromHz) * i / 4) / 1000), style = Ns.label)
            }
            Text("кГц · красная зона — ультразвук", style = Ns.label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            picture?.let {
                Image(it, null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxWidth().height(130.dp).padding(top = 6.dp))
                Text("водопад: время идёт снизу вверх, яркие полосы — устойчивые тоны", style = Ns.label)
            }
            SectionTitle("самые сильные тоны")
            if (peaks.isEmpty()) Text("тишина", style = Ns.small)
            peaks.forEach { p ->
                KeyValue("${p.hz.toInt()} Гц", "${p.db.toInt()} дБ" + (Spectrum.note(p.hz)?.let { " · нота $it" } ?: if (p.hz >= 17_500) " · ультразвук" else ""), if (p.hz >= 17_500) Ns.Red else Ns.Green)
            }
            status?.let { Text(it, style = Ns.small, modifier = Modifier.padding(top = 6.dp)) }

            SectionTitle("генератор тона")
            Text("Проверь слух и сам детектор: включи тон здесь, а спектр смотри на втором телефоне. Выше 15–17 кГц взрослые обычно уже не слышат.", style = Ns.small)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(440, 1000, 8000, 12000, 15000, 17000, 18500, 20000).forEach { f ->
                    Chip(if (f < 1000) "$f Гц" else "${f / 1000.0} кГц".replace(".0 ", " "), if (tone == f) Ns.Red else Ns.Dim, Modifier.clickable { tone = if (tone == f) 0 else f })
                }
            }
            if (tone != 0) Text("играет $tone Гц — нажми ещё раз, чтобы выключить", style = Ns.small.copy(color = Ns.Amber))
            SectionTitle("тоны DTMF")
            Text("Звуки кнопок телефона: каждая — сумма двух частот. Их видно в спектре двумя пиками.", style = Ns.small)
            val dtmf = remember { runCatching { android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
            DisposableEffect(Unit) { onDispose { dtmf?.release() } }
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c ->
                        val t = when (c) { '*' -> android.media.ToneGenerator.TONE_DTMF_S; '#' -> android.media.ToneGenerator.TONE_DTMF_P; else -> android.media.ToneGenerator.TONE_DTMF_0 + (c - '0') }
                        TermButton(c.toString(), Modifier.weight(1f)) { dtmf?.startTone(t, 180) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ======================= ТАЙНИК =======================

private fun fileMeta(ctx: Context, uri: Uri): Pair<String, Long> {
    val meta = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "файл") to (if (c.isNull(1)) -1L else c.getLong(1)) else null
        }
    }.getOrNull() ?: ((uri.lastPathSegment ?: "файл") to -1L)
    if (meta.second >= 0) return meta
    // поставщик не сказал размер — спросим у самого файла
    val len = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    return meta.first to len
}

private fun sizeText(n: Long): String = when {
    n < 0 -> "?"
    n >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(n / 1073741824.0)
    n >= 1024 * 1024 -> "%.1f МБ".format(n / 1048576.0)
    n >= 1024 -> "%.0f КБ".format(n / 1024.0)
    else -> "$n Б"
}

/** Куда складывать полученные файлы: «Загрузки/NULL-SIGNAL» (Android 10+) или папка приложения. */
private class Inbox(private val ctx: Context) {
    private val pending = ConcurrentHashMap<String, Any>()

    fun open(name: String): OutputStream? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NULL-SIGNAL")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            pending[name] = uri
            ctx.contentResolver.openOutputStream(uri)
        } else {
            val f = File(File(ctx.getExternalFilesDir(null), "drop").apply { mkdirs() }, name)
            pending[name] = f
            f.outputStream()
        }
    }.getOrNull()

    fun finished(name: String, ok: Boolean) {
        when (val p = pending.remove(name)) {
            is Uri -> runCatching {
                if (ok && Build.VERSION.SDK_INT >= 29) ctx.contentResolver.update(p, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                else ctx.contentResolver.delete(p, null, null)
            }
            is File -> if (!ok) p.delete()
        }
    }
}

@Composable
fun DropScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val files = remember { mutableStateListOf<Triple<Uri, String, Long>>() }
    val log = remember { mutableStateListOf<String>() }
    var server by remember { mutableStateOf<DropServer?>(null) }
    var address by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // список для сервера: его читают сетевые потоки, поэтому держим отдельной неизменяемой копией
    val shared = remember { java.util.concurrent.atomic.AtomicReference<List<DropServer.Shared>>(emptyList()) }

    LaunchedEffect(files.size) {
        shared.set(files.map { (uri, name, size) -> DropServer.Shared(name, size) { ctx.contentResolver.openInputStream(uri)!! } })
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            val (name, size) = fileMeta(ctx, u)
            if (files.none { it.first == u }) files += Triple(u, name, size)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            view.keepScreenOn = false
            server?.let { s -> Thread { s.stop() }.start() }
        }
    }

    fun open() {
        val net = NetInfo.local(ctx)
        if (net == null) { error = "нет сети: подключись к своему Wi-Fi"; return }
        error = null
        val inbox = Inbox(ctx)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        val s = DropServer(
            DropServer.newPin(), { shared.get() }, { name -> inbox.open(name) },
            { line -> main.post { log.add(0, time.format(Date()) + "  " + line) } },
            { name, ok -> inbox.finished(name, ok) },
        )
        scope.launch {
            val port = withContext(Dispatchers.IO) { runCatching { s.start(8080) }.getOrNull() }
            if (port == null) { error = "не удалось открыть порт"; return@launch }
            address = "http://${net.ip}:$port"
            qr = withContext(Dispatchers.Default) { runCatching { qrBitmap(address, 384) }.getOrNull() }
            server = s
            view.keepScreenOn = true
            if (!net.onWifi) error = "телефон не в Wi-Fi: по мобильной сети другие устройства сюда не попадут"
        }
    }

    ModuleScreen("ТАЙНИК", onBack) {
        val s = server
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (s == null) {
                Text(
                    "Передача файлов между телефоном и компьютером без проводов, облаков и мессенджеров. Телефон на время становится маленьким сайтом " +
                        "в твоей сети Wi-Fi: открываешь адрес на компьютере, вводишь PIN — и скачиваешь или загружаешь файлы.",
                    style = Ns.small,
                )
                Text("Включай только в своей сети. В чужом или общем Wi-Fi адрес увидят посторонние.", style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(top = 6.dp))
            } else {
                Column(Modifier.fillMaxWidth().hud(Ns.Red).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ТАЙНИК ОТКРЫТ", style = Ns.label.copy(color = Ns.Red))
                    SelectionContainer { Text(address, style = Ns.title.copy(fontSize = 18.sp, letterSpacing = 0.sp), modifier = Modifier.padding(top = 4.dp)) }
                    Text("PIN  " + s.pin.toList().joinToString(" "), style = Ns.title.copy(fontSize = 26.sp, color = Ns.Red), modifier = Modifier.padding(vertical = 6.dp))
                    qr?.let { Image(it, null, filterQuality = FilterQuality.None, modifier = Modifier.size(170.dp)) }
                    Text(
                        "открой адрес в браузере на компьютере или наведи камеру другого телефона на код. Устройство должно быть в этой же сети Wi-Fi",
                        style = Ns.small.copy(textAlign = TextAlign.Center), modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            error?.let { Text(it, style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(top = 6.dp)) }
            SectionTitle("файлы для скачивания · ${files.size}")
            if (files.isEmpty()) Text("пусто — с телефона пока нечего забрать (загружать на него всё равно можно)", style = Ns.small)
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.second, style = Ns.small.copy(color = Ns.Green), modifier = Modifier.weight(1f), maxLines = 1)
                    Text(sizeText(f.third), style = Ns.small)
                    Text("  ×", style = Ns.text.copy(color = Ns.Red), modifier = Modifier.clickable { files.remove(f) })
                }
            }
            Text("[добавить файлы]", style = Ns.text.copy(color = Ns.Red), modifier = Modifier.clickable { pick.launch(arrayOf("*/*")) }.padding(vertical = 8.dp))
            if (s != null) {
                SectionTitle("журнал")
                if (log.isEmpty()) Text("пока никто не заходил", style = Ns.small)
                log.take(40).forEach { Text(it, style = Ns.small.copy(color = if ("неверных" in it) Ns.Red else Ns.Text2)) }
                Text("полученные файлы лежат в папке «Загрузки / NULL-SIGNAL»", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        if (s == null) {
            TermButton("ОТКРЫТЬ ТАЙНИК", Modifier.fillMaxWidth(), color = Ns.Red) { open() }
        } else {
            TermButton("ЗАКРЫТЬ ТАЙНИК", Modifier.fillMaxWidth(), color = Ns.Dim) {
                Thread { s.stop() }.start()
                server = null
                view.keepScreenOn = false
            }
        }
    }
}
