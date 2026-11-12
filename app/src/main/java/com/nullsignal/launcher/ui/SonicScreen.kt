package com.nullsignal.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.spy.SoundModem
import com.nullsignal.core.spy.SoundNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

private class Heard(val time: Long, val data: ByteArray, val quiet: Boolean) {
    var opened by mutableStateOf<SoundNote.Opened>(SoundNote.open(data))
}

/** Принятое живёт, пока жив процесс. */
private object SonicLog { val items = mutableStateListOf<Heard>() }

private fun band(quiet: Boolean) = if (quiet) SoundModem.QUIET else SoundModem.LOUD

private suspend fun play(pcm: ShortArray, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val min = AudioTrack.getMinBufferSize(SoundModem.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SoundModem.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(min, 16_384))
        .build()
    try {
        track.play()
        var i = 0
        while (i < pcm.size && isActive) {
            val k = minOf(4096, pcm.size - i)
            val w = track.write(pcm, i, k)
            if (w <= 0) break
            i += w
            onProgress(i.toFloat() / pcm.size)
        }
        track.stop() // в потоковом режиме дозвучит то, что уже в буфере
        var waited = 0
        while (isActive && track.playbackHeadPosition < pcm.size && waited < 3000) { delay(40); waited += 40 }
    } finally {
        track.release()
    }
}

/** Состояние приёмника: живёт на уровне экрана, чтобы приём не прерывался при переходе на «ПЕРЕДАТЬ». */
private class RxState {
    var listening by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var spectrum by mutableStateOf(FloatArray(16))
    var receiving by mutableStateOf(false)
    var progress by mutableStateOf<Pair<Int, Int?>>(0 to null)
    var status by mutableStateOf<String?>(null)
}

@SuppressLint("MissingPermission")
private suspend fun listen(ctx: android.content.Context, rx: RxState, quiet: Boolean) = withContext(Dispatchers.IO) {
    val am = ctx.getSystemService(AudioManager::class.java)
    val raw = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    val source = if (raw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
    val min = AudioRecord.getMinBufferSize(SoundModem.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching {
        AudioRecord(source, SoundModem.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SoundModem.RATE))
    }.getOrNull()
    if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
        rx.status = "микрофон недоступен — возможно, он занят другим приложением"
        rec?.release()
        rx.listening = false
        return@withContext
    }
    val receiver = SoundModem.Receiver(band(quiet))
    val chunk = ShortArray(2048)
    var frame = 0
    rx.status = if (raw) "микрофон без обработки (лучший вариант)" else "обычный микрофон"
    try {
        rec.startRecording()
        while (isActive) {
            val k = rec.read(chunk, 0, chunk.size)
            if (k <= 0) { delay(10); continue }
            val events = receiver.feed(chunk, k)
            if (++frame % 3 == 0) {
                rx.level = receiver.level()
                rx.spectrum = receiver.spectrum()
                rx.receiving = receiver.receiving
                rx.progress = receiver.progress
            }
            for (e in events) {
                withContext(Dispatchers.Main) {
                    if (e is SoundModem.Receiver.Event.Frame) {
                        SonicLog.items.add(0, Heard(System.currentTimeMillis(), e.data, quiet))
                        rx.status = "принято ${e.data.size} байт"
                    } else {
                        rx.status = "кадр повреждён: помехи или передачу прервали"
                    }
                    Unit
                }
            }
        }
    } finally {
        runCatching { rec.stop() }
        rec.release()
        rx.level = 0f
        rx.receiving = false
    }
}

@Composable
fun SonicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var quiet by remember { mutableStateOf(false) }
    val rx = remember { RxState() }
    LaunchedEffect(rx.listening, quiet) { if (rx.listening) listen(ctx, rx, quiet) }
    ModuleScreen("ЗВУКОВОЙ КАНАЛ", onBack) {
        NsTabs(listOf("ПЕРЕДАТЬ", "ПРИНЯТЬ · ${SonicLog.items.size}"), tab, Ns.Red) { tab = it }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("ГРОМКО 1.6–4.3 кГц", if (!quiet) Ns.Red else Ns.Dim, Modifier.clickable { quiet = false })
            Chip("ТИХО 16.8–18.6 кГц", if (quiet) Ns.Red else Ns.Dim, Modifier.clickable { quiet = true })
            if (rx.listening && tab == 0) PulseDot(if (rx.receiving) Ns.Red else Ns.Green, 7.dp)
        }
        if (tab == 0) SendTab(quiet, rx.listening) else ReceiveTab(rx)
    }
}

@Composable
private fun ColumnScope.SendTab(quiet: Boolean, listening: Boolean) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    val sealed = password.isNotEmpty()
    val bytes = text.toByteArray().size
    val cap = SoundNote.capacity(sealed)
    val seconds = SoundModem.seconds(bytes + 1 + if (sealed) 36 else 0, band(quiet))
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "Передаёт текст звуком на другой телефон с NULL//SIGNAL — без сети, Wi-Fi и Bluetooth. Работает и через стену-перегородку, и в самолёте. " +
                "«Тихий» режим почти не слышен взрослым, но не все динамики его тянут.",
            style = Ns.small,
        )
        SectionTitle("сообщение")
        NsField(text, { text = it }, "что передать", singleLine = false)
        Text(
            "$bytes / $cap байт · ≈ ${"%.1f".format(Locale.US, seconds)} с в эфире",
            style = Ns.small.copy(color = if (bytes > cap) Ns.Red else Ns.Dim),
            modifier = Modifier.padding(top = 4.dp),
        )
        SectionTitle("пароль (необязательно)")
        NsField(password, { password = it }, "без пароля — открытым текстом", secret = true)
        Text(
            if (sealed) "AES-256-GCM: кто услышит без пароля — получит шум" else "без пароля сообщение прочтёт любой, у кого есть приёмник",
            style = Ns.small.copy(color = if (sealed) Ns.Green else Ns.Amber),
            modifier = Modifier.padding(top = 4.dp),
        )
        error?.let { Text(it, style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(top = 6.dp)) }
        if (progress >= 0f) {
            SectionTitle("в эфире")
            HudBar(progress, Ns.Red)
            Text(asciiBar(progress, 28), style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            if (listening) "Приём включён: телефон услышит и собственную передачу — результат появится во вкладке «ПРИНЯТЬ»."
            else "Проверка на одном телефоне: во вкладке «ПРИНЯТЬ» включи приём, вернись сюда и отправь — телефон услышит сам себя.",
            style = Ns.small.copy(color = if (listening) Ns.Green else Ns.Dim),
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
    TermButton(
        if (progress >= 0f) "ПЕРЕДАЮ…" else "ПЕРЕДАТЬ",
        Modifier.fillMaxWidth(),
        color = Ns.Red,
        enabled = progress < 0f && text.isNotBlank() && bytes <= cap,
    ) {
        scope.launch {
            error = null
            progress = 0f
            runCatching {
                val payload = withContext(Dispatchers.Default) { if (sealed) SoundNote.seal(text, password) else SoundNote.plain(text) }
                val pcm = withContext(Dispatchers.Default) { SoundModem.encode(payload, band(quiet), if (quiet) 0.95 else 0.8) }
                play(pcm) { p -> progress = p }
            }.onFailure { error = "не получилось: ${it.message ?: it.javaClass.simpleName}" }
            progress = -1f
        }
    }
}

/** Живой спектр 16 тонов полосы: в шкале децибел, -60…0 дБ. */
@Composable
private fun ToneSpectrum(values: FloatArray, active: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width / 16
        values.forEachIndexed { k, v ->
            val db = if (v <= 0f) -90f else 20 * log10(v)
            val h = size.height * ((db + 60f) / 60f).coerceIn(0.03f, 1f)
            val c = if (active) Ns.Red else Ns.Green.copy(alpha = 0.6f)
            drawRect(c, Offset(k * w + w * 0.18f, size.height - h), Size(w * 0.64f, h))
        }
        drawLine(Ns.Faint, Offset(0f, size.height), Offset(size.width, size.height), 1f)
    }
}

@Composable
private fun ColumnScope.ReceiveTab(rx: RxState) {
    val (granted, request) = rememberPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
    if (!granted) {
        PermissionGate("Чтобы слушать эфир, нужен микрофон. Звук разбирается на телефоне и никуда не записывается.", request)
        return
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        val color = when { rx.receiving -> Ns.Red; rx.listening -> Ns.Green; else -> Ns.Dim }
        Column(Modifier.fillMaxWidth().hud(color).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rx.listening) PulseDot(color)
                Text(
                    when { rx.receiving -> "  ПРИНИМАЮ"; rx.listening -> "  СЛУШАЮ ЭФИР"; else -> "ПРИЁМ ВЫКЛЮЧЕН" },
                    style = Ns.title.copy(color = color, fontSize = 18.sp),
                )
            }
            ToneSpectrum(rx.spectrum, rx.receiving, Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp))
            HudBar(rx.level * 4f, if (rx.receiving) Ns.Red else Ns.Green, Modifier.padding(top = 6.dp), height = 3.dp)
            if (rx.receiving) {
                val (got, total) = rx.progress
                Text(
                    if (total == null) "поймал преамбулу, читаю длину…" else "символ $got из $total",
                    style = Ns.small.copy(color = Ns.Red),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            rx.status?.let { Text(it, style = Ns.small, modifier = Modifier.padding(top = 4.dp)) }
        }
        SonicLog.items.forEach { h -> key(h) { HeardCard(h) } }
        if (SonicLog.items.isEmpty()) {
            Text(
                "Держи телефоны в полуметре друг от друга, динамик к микрофону. Режим (громко/тихо) должен совпадать на обоих.",
                style = Ns.small.copy(color = Ns.Dim),
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
    TermButton(if (rx.listening) "ОСТАНОВИТЬ ПРИЁМ" else "СЛУШАТЬ", Modifier.fillMaxWidth(), color = if (rx.listening) Ns.Dim else Ns.Red) {
        rx.listening = !rx.listening
        if (rx.listening) rx.status = null
    }
}

@Composable
private fun HeardCard(h: Heard) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    val o = h.opened
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).hud(if (o is SoundNote.Opened.Text) Ns.Faint else Ns.Amber).padding(10.dp)) {
        Text(
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(h.time)) + " · " + (if (h.quiet) "тихий" else "громкий") + " канал · ${h.data.size} байт" +
                if (o is SoundNote.Opened.Text && o.sealed) " · расшифровано" else "",
            style = Ns.label,
        )
        when (o) {
            is SoundNote.Opened.Text -> SelectionContainer { Text(o.text, style = Ns.text, modifier = Modifier.padding(top = 4.dp)) }
            SoundNote.Opened.NeedPassword, SoundNote.Opened.WrongPassword -> {
                Text(
                    if (o == SoundNote.Opened.WrongPassword) "пароль не подошёл" else "сообщение под паролем",
                    style = Ns.text.copy(color = Ns.Amber),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NsField(password, { password = it }, "пароль", Modifier.weight(1f), secret = true)
                    Text("  ОТКРЫТЬ", style = Ns.text.copy(color = Ns.Red), modifier = Modifier.clickable {
                        val pass = password
                        scope.launch { h.opened = withContext(Dispatchers.Default) { SoundNote.open(h.data, pass) } }
                    })
                }
            }
            SoundNote.Opened.Unknown -> Text(h.data.joinToString(" ") { "%02X".format(it) }, style = Ns.small, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
