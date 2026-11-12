package com.nullsignal.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.nullsignal.core.security.Crypto
import com.nullsignal.core.security.PasswordStrength
import com.nullsignal.core.security.UrlInspector
import com.nullsignal.core.terminal.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    secret: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = Ns.text,
        cursorBrush = SolidColor(Ns.Green),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        modifier = modifier.fillMaxWidth().hud(Ns.Dim).padding(10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, style = Ns.text.copy(color = Ns.Faint))
            inner()
        },
    )
}

@Composable
fun ToolsScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("ПАРОЛЬ", "ШИФР", "QR", "ССЫЛКА")
    ModuleScreen("TOOLS", onBack) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { i, t ->
                Text(
                    t,
                    style = Ns.small.copy(color = if (i == tab) Ns.Bg else Ns.Green),
                    modifier = Modifier
                        .weight(1f)
                        .background(if (i == tab) Ns.Green else Ns.Bg)
                        .hud(Ns.Dim)
                        .clickable { tab = i }
                        .padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> PasswordTab()
                1 -> CryptTab()
                2 -> QrTab()
                else -> UrlTab()
            }
        }
    }
}

@Composable
private fun PasswordTab() {
    val clip = LocalClipboardManager.current
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    Text("проверка надёжности. пароль никуда не отправляется", style = Ns.small)
    Spacer(Modifier.height(6.dp))
    Field(pw, { pw = it }, "введи пароль…", secret = !show)
    Text(if (show) "[скрыть]" else "[показать]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { show = !show }.padding(4.dp))
    if (pw.isNotEmpty()) {
        val r = PasswordStrength.check(pw)
        val c = listOf(Ns.Red, Ns.Red, Ns.Amber, Ns.Green, Ns.Cyan)[r.level]
        Text(r.verdict, style = Ns.title.copy(color = c))
        Text(asciiBar((r.level + 1) / 5f, 24).substringBefore("]") + "]", style = Ns.text.copy(color = c))
        Text("энтропия ~${r.entropyBits.toInt()} бит", style = Ns.small)
        Text("перебор на мощной видеокарте: ${PasswordStrength.humanTime(r.crackSeconds)}", style = Ns.text.copy(color = c))
        r.issues.forEach { Text("  - $it", style = Ns.small.copy(color = Ns.Amber)) }
        val scope = rememberCoroutineScope()
        var leak by remember(pw) { mutableStateOf<String?>(null) }
        TermButton("ПРОВЕРИТЬ ПО БАЗЕ УТЕЧЕК", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red) {
            leak = "проверяю… (наружу уходят только 5 символов хеша)"
            scope.launch {
                leak = runCatching { com.nullsignal.launcher.sys.NetTools.pwnedCount(pw) }
                    .map { if (it == 0) "✓ в известных утечках не найден" else "⚠ СЛИТ $it раз(а) — не используй этот пароль" }
                    .getOrElse { "ошибка сети: ${it.message}" }
            }
        }
        leak?.let { Text(it, style = Ns.text.copy(color = if (it.startsWith("✓")) Ns.Green else Ns.Red)) }
    }
    Spacer(Modifier.height(16.dp))
    Text("— генератор —", style = Ns.text.copy(color = Ns.Cyan))
    var gen by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        listOf(12, 16, 24, 32).forEach { n -> TermButton("$n", Modifier.weight(1f)) { gen = Tools.password(n) } }
    }
    if (gen.isNotEmpty()) {
        SelectionContainer { Text(gen, style = Ns.title.copy(color = Ns.Green)) }
        Text("[скопировать]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { clip.setText(AnnotatedString(gen)) }.padding(4.dp))
    }
}

@Composable
private fun CryptTab() {
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Text("AES-256-GCM, ключ из пароля (PBKDF2). без пароля текст не прочитать никому, даже нам", style = Ns.small)
    Spacer(Modifier.height(6.dp))
    Field(text, { text = it }, "текст или зашифрованное ns1:…", Modifier.heightIn(min = 90.dp), singleLine = false)
    Spacer(Modifier.height(6.dp))
    Field(pw, { pw = it }, "пароль", secret = true)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        TermButton("ЗАШИФРОВАТЬ", Modifier.weight(1f), enabled = !busy && text.isNotEmpty() && pw.isNotEmpty()) {
            busy = true
            scope.launch {
                result = withContext(Dispatchers.Default) { Crypto.encrypt(text, pw) }
                busy = false
            }
        }
        TermButton("РАСШИФРОВАТЬ", Modifier.weight(1f), enabled = !busy && text.isNotEmpty() && pw.isNotEmpty()) {
            busy = true
            scope.launch {
                result = withContext(Dispatchers.Default) { Crypto.decrypt(text, pw) } ?: "✗ неверный пароль или данные повреждены"
                busy = false
            }
        }
    }
    if (busy) Text("вычисляю ключ…", style = Ns.small.copy(color = Ns.Amber))
    if (result.isNotEmpty()) {
        SelectionContainer {
            Text(result, style = Ns.text.copy(color = Ns.Cyan), modifier = Modifier.fillMaxWidth().hud(Ns.Faint).padding(8.dp))
        }
        Text("[скопировать]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { clip.setText(AnnotatedString(result)) }.padding(4.dp))
    }
}

private fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF00FF66.toInt() else 0xFF040704.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun QrTab() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("— сканер —", style = Ns.text.copy(color = Ns.Cyan))
    Text("показывает, что внутри QR, ДО того как ты перейдёшь по ссылке", style = Ns.small)
    TermButton("СКАНИРОВАТЬ QR", Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        error = null
        GmsBarcodeScanning.getClient(ctx).startScan()
            .addOnSuccessListener { scanned = it.rawValue ?: "" }
            .addOnFailureListener { error = "сканер недоступен: ${it.message}" }
    }
    error?.let { Text(it, style = Ns.small.copy(color = Ns.Red)) }
    scanned?.let { raw ->
        SelectionContainer { Text(raw, style = Ns.text, modifier = Modifier.fillMaxWidth().hud(Ns.Faint).padding(8.dp)) }
        val wifi = UrlInspector.parseWifiQr(raw)
        when {
            wifi != null -> {
                Text("это Wi-Fi: сеть «${wifi["S"]}», защита ${wifi["T"] ?: "нет"}", style = Ns.text.copy(color = Ns.Cyan))
                if (wifi["T"].isNullOrEmpty() || wifi["T"] == "nopass") Text("⚠ открытая сеть", style = Ns.small.copy(color = Ns.Red))
            }
            raw.contains("://") || raw.startsWith("www.") -> UrlReport(raw)
            else -> Text("это не ссылка, просто текст", style = Ns.small)
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("— генератор —", style = Ns.text.copy(color = Ns.Cyan))
    Field(text, { text = it }, "текст или ссылка")
    if (text.isNotEmpty()) {
        val bmp = remember(text) { runCatching { qrBitmap(text) }.getOrNull() }
        if (bmp != null) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Image(bmp, contentDescription = "QR", filterQuality = FilterQuality.None, modifier = Modifier.size(240.dp))
            }
        }
    }
}

@Composable
private fun UrlReport(url: String) {
    val r = UrlInspector.inspect(url)
    Text("домен: ${r.host ?: "?"}", style = Ns.text)
    if (r.safeLooking) Text("✓ явных признаков фишинга нет (но это не гарантия)", style = Ns.text.copy(color = Ns.Green))
    r.warnings.forEach { Text("⚠ $it", style = Ns.text.copy(color = Ns.Amber)) }
}

@Composable
private fun UrlTab() {
    var url by remember { mutableStateOf("") }
    Text("проверь подозрительную ссылку из сообщения, не открывая её", style = Ns.small)
    Spacer(Modifier.height(6.dp))
    Field(url, { url = it.trim() }, "https://…")
    Spacer(Modifier.height(8.dp))
    if (url.isNotEmpty()) UrlReport(url)
    Spacer(Modifier.height(12.dp))
    Text("примеры для тренировки:", style = Ns.small)
    listOf("https://steamcommunlty-gift.ru/trade", "http://192.168.0.1/login", "https://bit.ly/3xYz", "https://store.steampowered.com").forEach {
        Text("  $it", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { url = it }.padding(4.dp))
    }
}
