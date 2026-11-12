package com.nullsignal.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.security.Crypto
import com.nullsignal.core.spy.FileCrypto
import com.nullsignal.core.spy.FileHash
import com.nullsignal.core.spy.InvisibleInk
import com.nullsignal.core.spy.Morse
import com.nullsignal.core.spy.Stego
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SpyScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ModuleScreen("ШПИОНАЖ", onBack) {
        NsTabs(listOf("СТЕГО", "ЧЕРНИЛА", "ФАЙЛЫ", "МОРЗЕ"), tab, Ns.Red) { tab = it }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> StegoTab()
                1 -> InkTab()
                2 -> FilesTab()
                else -> MorseTab()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun Context.fileName(uri: Uri): String = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: "file"

/** Картинка в пикселях ARGB, уменьшенная до ~4 Мп, чтобы не упереться в память. */
private fun Context.loadPixels(uri: Uri): Triple<IntArray, Int, Int>? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (opts.outWidth / sample * (opts.outHeight / sample) > 4_000_000) sample *= 2
    val bmp = contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    } ?: return null
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return Triple(px, bmp.width, bmp.height)
}

// ---------------- СТЕГО ----------------

@Composable
private fun ColumnScope.StegoTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var image by remember { mutableStateOf<Uri?>(null) }
    var capacity by remember { mutableIntStateOf(0) }
    var secret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var pendingCipher by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        image = uri
        status = "читаю картинку…"
        scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { ctx.loadPixels(uri) }.getOrNull() }
            capacity = p?.let { Stego.capacity(it.first.size) } ?: 0
            status = if (p == null) "не удалось открыть картинку" else "картинка ${p.second}×${p.third}: поместится до ${capacity / 1024} КБ текста"
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { out ->
        val src = image
        if (out == null || src == null) return@rememberLauncherForActivityResult
        status = "прячу…"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val (px, w, h) = ctx.loadPixels(src) ?: error("картинка не открылась")
                    val payload = if (password.isNotEmpty()) Crypto.encrypt(secret, password) else secret
                    val result = Stego.embed(px, payload.toByteArray(Charsets.UTF_8))
                    val bmp = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
                    ctx.contentResolver.openOutputStream(out)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    "✓ спрятано ${payload.length} символов. Отправляй как ФАЙЛ (не как фото — мессенджер пережмёт и сотрёт)."
                }.getOrElse { "ошибка: ${it.message}" }
            }
        }
    }
    val reveal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        revealed = null; pendingCipher = null
        status = "ищу спрятанное…"
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ctx.loadPixels(uri)?.first?.let { Stego.extract(it) }?.toString(Charsets.UTF_8) }.getOrNull()
            }
            when {
                text == null -> status = "в этой картинке ничего не спрятано (или её пережали)"
                Crypto.isEncrypted(text) -> { pendingCipher = text; status = "найдено зашифрованное сообщение — нужен пароль" }
                else -> { revealed = text; status = "✓ найдено сообщение" }
            }
        }
    }

    Text(
        "Прячет текст внутри обычной картинки: младшие биты цвета меняются незаметно для глаза. " +
            "С паролем текст ещё и шифруется AES-256 — без пароля его не прочитать, даже если найти.",
        style = Ns.small,
    )
    SectionTitle("спрятать", Ns.Red)
    TermButton(if (image == null) "1. ВЫБРАТЬ КАРТИНКУ" else "КАРТИНКА ВЫБРАНА ✓", Modifier.fillMaxWidth()) { pick.launch(arrayOf("image/*")) }
    Spacer(Modifier.height(6.dp))
    NsField(secret, { secret = it }, "2. секретный текст", Modifier.heightIn(min = 80.dp), singleLine = false)
    Spacer(Modifier.height(6.dp))
    NsField(password, { password = it }, "пароль (необязательно)", secret = true)
    val tooLong = capacity > 0 && secret.toByteArray().size * 2 > capacity
    TermButton("3. СПРЯТАТЬ И СОХРАНИТЬ PNG", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red, enabled = image != null && secret.isNotEmpty() && !tooLong) {
        save.launch("secret_${System.currentTimeMillis() / 1000}.png")
    }
    if (tooLong) Text("слишком длинно для этой картинки — выбери картинку побольше", style = Ns.small.copy(color = Ns.Amber))
    SectionTitle("достать")
    TermButton("ВЫБРАТЬ КАРТИНКУ С СЕКРЕТОМ", Modifier.fillMaxWidth()) { reveal.launch(arrayOf("image/png", "image/*")) }
    status?.let { Text(it, style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(vertical = 6.dp)) }
    val cipher = pendingCipher
    if (cipher != null) {
        var pw by remember { mutableStateOf("") }
        NsField(pw, { pw = it }, "пароль", secret = true)
        TermButton("РАСШИФРОВАТЬ", Modifier.fillMaxWidth().padding(top = 6.dp)) {
            scope.launch {
                val r = withContext(Dispatchers.Default) { Crypto.decrypt(cipher, pw) }
                if (r == null) {
                    status = "✗ неверный пароль"
                } else {
                    revealed = r; pendingCipher = null; status = "✓ расшифровано"
                }
            }
        }
    }
    revealed?.let { r ->
        Panel(Modifier.padding(top = 6.dp), color = Ns.Red) {
            SelectionContainer { Text(r, style = Ns.text) }
            Text("[скопировать]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { clip.setText(AnnotatedString(r)) }.padding(top = 6.dp))
        }
    }
}

// ---------------- НЕВИДИМЫЕ ЧЕРНИЛА ----------------

@Composable
private fun ColumnScope.InkTab() {
    val clip = LocalClipboardManager.current
    var cover by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var check by remember { mutableStateOf("") }
    Text(
        "Прячет сообщение внутри обычного текста невидимыми символами. Пишешь «привет, как дела?», а внутри — секрет. " +
            "Работает в Telegram, VK, почте. Получатель вставляет текст сюда и видит скрытое.",
        style = Ns.small,
    )
    SectionTitle("спрятать", Ns.Red)
    NsField(cover, { cover = it }, "обычный текст (что увидят все)")
    Spacer(Modifier.height(6.dp))
    NsField(secret, { secret = it }, "секрет (невидимый)")
    TermButton("СПРЯТАТЬ И СКОПИРОВАТЬ", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red, enabled = cover.isNotEmpty() && secret.isNotEmpty()) {
        result = InvisibleInk.hide(cover, secret)
        clip.setText(AnnotatedString(result))
    }
    if (result.isNotEmpty()) {
        Text("✓ скопировано: выглядит как «$cover», длина ${result.length} символов вместо ${cover.length}", style = Ns.small.copy(color = Ns.Green), modifier = Modifier.padding(vertical = 6.dp))
    }
    SectionTitle("прочитать / проверить")
    NsField(check, { check = it }, "вставь подозрительный текст", Modifier.heightIn(min = 70.dp), singleLine = false)
    if (check.isNotEmpty()) {
        val n = InvisibleInk.detect(check)
        val found = InvisibleInk.reveal(check)
        Panel(Modifier.padding(top = 6.dp), color = if (n > 0) Ns.Red else Ns.Dim) {
            Text(if (n > 0) "⚠ невидимых символов: $n" else "невидимых символов нет", style = Ns.text.copy(color = if (n > 0) Ns.Red else Ns.Green))
            found?.let { SelectionContainer { Text("секрет: $it", style = Ns.text) } }
            if (n > 0) {
                Text(
                    "[скопировать без невидимых символов]",
                    style = Ns.small.copy(color = Ns.Cyan),
                    modifier = Modifier.clickable { clip.setText(AnnotatedString(InvisibleInk.strip(check))) }.padding(top = 6.dp),
                )
                Text("Такие символы иногда используют для слежки: в «одинаковые» тексты для разных людей прячут метку, чтобы найти, кто слил.", style = Ns.small.copy(color = Ns.Dim))
            }
        }
    }
}

// ---------------- ФАЙЛЫ ----------------

@Composable
private fun ColumnScope.FilesTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var hash by remember { mutableStateOf<Pair<String, FileHash.Result>?>(null) }
    var src by remember { mutableStateOf<Uri?>(null) }
    var srcName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var decryptSrc by remember { mutableStateOf<Uri?>(null) }

    val pickHash = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "считаю хеши…"
        scope.launch {
            hash = withContext(Dispatchers.IO) {
                runCatching { ctx.fileName(uri) to ctx.contentResolver.openInputStream(uri)!!.use { FileHash.of(it) } }.getOrNull()
            }
            status = null
        }
    }
    val encOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { out ->
        val s = src
        if (out == null || s == null) return@rememberLauncherForActivityResult
        status = "шифрую… (ключ из пароля считается пару секунд)"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(s)!!.use { i ->
                        ctx.contentResolver.openOutputStream(out)!!.use { o -> FileCrypto.encrypt(i, o, password, srcName) }
                    }
                    "✓ зашифровано. Без пароля файл — просто шум."
                }.getOrElse { "ошибка: ${it.message}" }
            }
        }
    }
    val decOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { out ->
        val s = decryptSrc
        if (out == null || s == null) return@rememberLauncherForActivityResult
        status = "расшифровываю…"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val name = ctx.contentResolver.openInputStream(s)!!.use { i ->
                        ctx.contentResolver.openOutputStream(out)!!.use { o -> FileCrypto.decrypt(i, o, password) }
                    }
                    "✓ расшифровано (исходное имя: $name)"
                }.getOrElse {
                    runCatching { ctx.contentResolver.delete(out, null, null) }
                    "✗ ${it.message}"
                }
            }
        }
    }
    val pickEnc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        src = uri
        srcName = ctx.fileName(uri)
        encOut.launch("$srcName.nsenc")
    }
    val pickDec = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decryptSrc = uri
        val name = ctx.fileName(uri).removeSuffix(".nsenc")
        decOut.launch(name)
    }

    SectionTitle("шифрование любого файла", Ns.Red)
    Text("AES-256-GCM: фото, документы, архивы. Имя файла тоже прячется внутри.", style = Ns.small)
    Spacer(Modifier.height(6.dp))
    NsField(password, { password = it }, "пароль (запомни — восстановить нельзя)", secret = true)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TermButton("ЗАШИФРОВАТЬ", Modifier.weight(1f), color = Ns.Red, enabled = password.length >= 4) { pickEnc.launch(arrayOf("*/*")) }
        TermButton("РАСШИФРОВАТЬ", Modifier.weight(1f), enabled = password.isNotEmpty()) { pickDec.launch(arrayOf("*/*")) }
    }
    if (password.isNotEmpty() && password.length < 4) Text("пароль слишком короткий", style = Ns.small.copy(color = Ns.Amber))
    status?.let { Text(it, style = Ns.small.copy(color = if (it.startsWith("✓")) Ns.Green else Ns.Amber), modifier = Modifier.padding(vertical = 6.dp)) }

    SectionTitle("хеш файла (проверка подлинности)")
    Text("Сравни с хешем на сайте, откуда качал: если не совпадает — файл подменили.", style = Ns.small)
    TermButton("ВЫБРАТЬ ФАЙЛ", Modifier.fillMaxWidth().padding(top = 6.dp)) { pickHash.launch(arrayOf("*/*")) }
    hash?.let { (name, h) ->
        Panel(Modifier.padding(top = 6.dp)) {
            Text(name, style = Ns.text)
            KeyValue("размер", "%,d байт".format(h.size))
            listOf("MD5" to h.md5, "SHA-1" to h.sha1, "SHA-256" to h.sha256).forEach { (k, v) ->
                Text(k, style = Ns.label)
                SelectionContainer { Text(v, style = Ns.small.copy(color = Ns.Green)) }
                Text("[копировать]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { clip.setText(AnnotatedString(v)) })
            }
        }
    }
}

// ---------------- МОРЗЕ ----------------

private class Signaller(private val ctx: Context) {
    private val cam = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? = runCatching {
        val c = cam ?: return@runCatching null
        c.cameraIdList.firstOrNull { c.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    private val vibrator = ctx.getSystemService(Vibrator::class.java)

    val hasTorch get() = torchId != null

    fun on(mode: Int, ms: Int) {
        when (mode) {
            0 -> torchId?.let { runCatching { cam?.setTorchMode(it, true) } }
            1 -> tone?.startTone(ToneGenerator.TONE_DTMF_5, ms)
            else -> runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)) }
        }
    }

    fun off(mode: Int) {
        when (mode) {
            0 -> torchId?.let { runCatching { cam?.setTorchMode(it, false) } }
            1 -> tone?.stopTone()
            else -> Unit
        }
    }

    fun release() {
        torchId?.let { runCatching { cam?.setTorchMode(it, false) } }
        tone?.release()
    }
}

@Composable
private fun ColumnScope.MorseTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val sig = remember { Signaller(ctx) }
    DisposableEffect(Unit) { onDispose { sig.release() } }
    var text by remember { mutableStateOf("SOS") }
    var code by remember { mutableStateOf("") }
    var cyr by remember { mutableStateOf(false) }
    var unit by remember { mutableIntStateOf(120) }
    var job by remember { mutableStateOf<Job?>(null) }
    var playingIndex by remember { mutableIntStateOf(-1) }
    val encoded = Morse.encode(text)

    fun play(mode: Int) {
        job?.cancel()
        job = scope.launch {
            try {
                Morse.timings(encoded, unit).forEachIndexed { i, (on, ms) ->
                    playingIndex = i
                    if (on) { sig.on(mode, ms); delay(ms.toLong()); sig.off(mode) } else delay(ms.toLong())
                }
            } finally {
                sig.off(mode); playingIndex = -1
            }
        }
    }

    Text("Передай сообщение светом фонарика, звуком или вибрацией. Работает без сети — как у подводников.", style = Ns.small)
    SectionTitle("передать", Ns.Red)
    NsField(text, { text = it }, "текст (латиница или кириллица)")
    SelectionContainer { Text(encoded.ifEmpty { "—" }, style = Ns.title.copy(letterSpacing = 3.sp, color = if (playingIndex >= 0) Ns.Red else Ns.Green), modifier = Modifier.padding(vertical = 8.dp)) }
    Text("[скопировать код]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { clip.setText(AnnotatedString(encoded)) })
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TermButton("ФОНАРИК", Modifier.weight(1f), color = Ns.Red, enabled = sig.hasTorch && encoded.isNotEmpty()) { play(0) }
        TermButton("ЗВУК", Modifier.weight(1f), enabled = encoded.isNotEmpty()) { play(1) }
        TermButton("ВИБРО", Modifier.weight(1f), enabled = encoded.isNotEmpty()) { play(2) }
    }
    if (job?.isActive == true) TermButton("СТОП", Modifier.fillMaxWidth(), color = Ns.Dim) { job?.cancel() }
    Text("скорость: точка = $unit мс", style = Ns.small)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(60 to "БЫСТРО", 120 to "НОРМА", 220 to "МЕДЛЕННО").forEach { (u, l) ->
            TermButton(l, Modifier.weight(1f), color = if (unit == u) Ns.Red else Ns.Dim) { unit = u }
        }
    }
    SectionTitle("расшифровать")
    NsField(code, { code = it }, ".- / -... (буквы через пробел, слова через /)")
    Text(
        "${if (cyr) "[■]" else "[ ]"} русский алфавит",
        style = Ns.small.copy(color = if (cyr) Ns.Green else Ns.Dim),
        modifier = Modifier.clickable { cyr = !cyr }.padding(vertical = 6.dp),
    )
    if (code.isNotBlank()) Text(Morse.decode(code, cyr), style = Ns.title.copy(color = Ns.Green))
}
