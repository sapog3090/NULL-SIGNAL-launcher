package com.nullsignal.launcher.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.security.Audit
import com.nullsignal.core.security.FileCheck
import com.nullsignal.core.util.Codec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

// ======================= ДЕКОДЕР =======================

@Composable
fun DecoderScreen(onBack: () -> Unit, preset: String? = null, onPreset: () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    var input by remember { mutableStateOf(preset?.take(200_000) ?: "") }
    // текст пришёл из терминала (decode …) или через «Поделиться» из другого приложения
    LaunchedEffect(preset) {
        if (preset != null) {
            input = preset.take(200_000)
            onPreset()
        }
    }
    val stack = remember { mutableStateListOf<Pair<String, ByteArray>>() }
    var error by remember { mutableStateOf<String?>(null) }
    var findings by remember { mutableStateOf<List<Codec.Finding>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(input) {
        stack.clear()
        error = null
        findings = emptyList()
        if (input.isBlank()) return@LaunchedEffect
        delay(350)
        searching = true
        findings = withContext(Dispatchers.Default) { runCatching { Codec.detect(input) }.getOrDefault(emptyList()) }
        searching = false
    }

    fun apply(op: Codec.Op) {
        val src = stack.lastOrNull()?.second ?: input.toByteArray()
        try {
            stack += op.title to op.apply(src)
            error = null
        } catch (e: Codec.Bad) {
            error = "${op.title}: ${e.message}"
        } catch (e: Exception) {
            error = "${op.title}: не получилось"
        }
    }

    ModuleScreen("ДЕКОДЕР", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            NsField(input, { input = it }, "вставь текст, Base64, hex, JWT, хеш…", Modifier.heightIn(min = 72.dp, max = 170.dp), singleLine = false)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("ВСТАВИТЬ", Ns.Red, Modifier.clickable { clip.getText()?.text?.let { input = it.take(200_000) } })
                Chip("СТЕРЕТЬ", Ns.Dim, Modifier.clickable { input = "" })
                Text("${input.length} симв.", style = Ns.label)
            }
            if (input.isNotBlank()) {
                SectionTitle("автоопределение", Ns.Red)
                val kind = Codec.hashKind(input)
                if (kind != null) {
                    AlertRow(0, "Похоже на хеш: $kind", "Хеш — односторонний отпечаток: раскодировать его обратно нельзя, можно только сравнить с хешем известной строки.")
                }
                if (searching) Text("разбираю…", style = Ns.small.copy(color = Ns.Amber))
                findings.forEach { f ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).hud(Ns.Red).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.steps.joinToString(" → "), style = Ns.label.copy(color = Ns.Red), modifier = Modifier.weight(1f))
                            Text("копировать", style = Ns.label, modifier = Modifier.clickable { clip.setText(AnnotatedString(f.text)) })
                        }
                        SelectionContainer { Text(f.text.take(2000), style = CODE.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
                if (!searching && findings.isEmpty() && kind == null) {
                    Text("закодированного не нашёл — похоже на обычный текст. Ниже можно преобразовать его вручную.", style = Ns.small)
                }
            }
            if (stack.isNotEmpty()) {
                SectionTitle("результат")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stack.forEach { Chip(it.first, Ns.Red) }
                    Chip("↶ ШАГ НАЗАД", Ns.Dim, Modifier.clickable { stack.removeAt(stack.size - 1) })
                }
                val out = stack.last().second
                val shown = Codec.show(out)
                Panel { SelectionContainer { Text(shown.take(8000), style = CODE.copy(fontSize = 12.sp)) } }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("СКОПИРОВАТЬ", Ns.Green, Modifier.clickable { clip.setText(AnnotatedString(shown)) })
                    Codec.utf8(out)?.let { t -> Chip("СДЕЛАТЬ ВХОДОМ", Ns.Green, Modifier.clickable { input = t }) }
                }
            }
            error?.let { Text(it, style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(top = 6.dp)) }
            Codec.ops.groupBy { it.group }.forEach { (group, ops) ->
                SectionTitle(group)
                ops.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { op ->
                            Text(
                                op.title,
                                style = Ns.small.copy(color = Ns.Green, textAlign = TextAlign.Center),
                                maxLines = 1,
                                modifier = Modifier.weight(1f).hud(Ns.Dim).clickable { apply(op) }.padding(vertical = 9.dp, horizontal = 2.dp),
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Text(
                "Шаги применяются по цепочке: каждый следующий берёт результат предыдущего. Всё считается на телефоне, в сеть ничего не уходит.",
                style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

// ======================= ПРОВЕРКА ФАЙЛА =======================

private class ApkInfo(
    val pkg: String,
    val label: String,
    val version: String,
    val targetSdk: Int,
    val sensitive: List<String>,
    val permCount: Int,
    val installedVersion: String?,
    val sameSigner: Boolean?,
)

private fun human(n: Long): String = when {
    n >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(n / 1073741824.0)
    n >= 1024 * 1024 -> "%.1f МБ".format(n / 1048576.0)
    n >= 1024 -> "%.1f КБ".format(n / 1024.0)
    else -> "$n байт"
}

@Suppress("DEPRECATION")
private fun signers(p: PackageInfo): Set<String>? {
    val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
    if (sigs.isNullOrEmpty()) return null
    return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
}

@Suppress("DEPRECATION")
private fun apkInfo(ctx: Context, file: File): ApkInfo? = runCatching {
    val pm = ctx.packageManager
    val flags = PackageManager.GET_PERMISSIONS or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    val info = (
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(file.path, flags)
        ) ?: return null
    val ai = info.applicationInfo
    ai?.sourceDir = file.path
    ai?.publicSourceDir = file.path
    val perms = info.requestedPermissions.orEmpty().toList()
    val installed = runCatching {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(info.packageName, flags)
    }.getOrNull()
    val mine = signers(info)
    val theirs = installed?.let { signers(it) }
    ApkInfo(
        pkg = info.packageName,
        label = ai?.loadLabel(pm)?.toString() ?: info.packageName,
        version = info.versionName ?: "?",
        targetSdk = ai?.targetSdkVersion ?: 0,
        sensitive = perms.mapNotNull { Audit.SENSITIVE[it] }.distinct(),
        permCount = perms.size,
        installedVersion = installed?.versionName,
        sameSigner = if (mine != null && theirs != null) mine == theirs else null,
    )
}.getOrNull()

private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

@Composable
fun FileCheckScreen(onBack: () -> Unit, shared: Uri? = null, onShared: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<FileCheck.Report?>(null) }
    var name by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf<ApkInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check(uri: Uri) {
        busy = true
        error = null
        report = null
        apk = null
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val display = displayName(ctx, uri) ?: uri.lastPathSegment ?: "файл"
                    val tmp = File(ctx.cacheDir, "filecheck.bin")
                    try {
                        var total = 0L
                        ctx.contentResolver.openInputStream(uri)!!.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > 400L * 1024 * 1024) throw IllegalStateException("файл больше 400 МБ")
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        val r = FileCheck.scan(tmp, display)
                        Triple(display, r, if (r.kind.startsWith("приложение Android")) apkInfo(ctx, tmp) else null)
                    } finally {
                        tmp.delete()
                    }
                }
            }
            res.onSuccess { name = it.first; report = it.second; apk = it.third }
                .onFailure { error = "не удалось прочитать файл: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) check(uri) }
    // файлом поделились из другого приложения — проверяем сразу
    LaunchedEffect(shared) {
        if (shared != null) {
            onShared()
            check(shared)
        }
    }

    ModuleScreen("ПРОВЕРКА ФАЙЛА", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val r = report
            if (r == null) {
                Text(
                    "Прислали файл и ты не уверен, открывать ли? Модуль смотрит, что это на самом деле: программа под видом картинки, " +
                        "документ с макросами, архив с сюрпризом, страница для кражи пароля. Файл не запускается и никуда не отправляется.",
                    style = Ns.small,
                )
                if (busy) Text("читаю и считаю отпечатки…", style = Ns.text.copy(color = Ns.Amber), modifier = Modifier.padding(vertical = 12.dp))
                error?.let { Text(it, style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(vertical = 8.dp)) }
                return@Column
            }
            val extra = ArrayList<FileCheck.Finding>()
            val a = apk
            if (a != null) {
                if (a.sameSigner == false) {
                    extra += FileCheck.Finding(2, "Подпись не совпадает с установленным «${a.label}»", "Это не обновление от того же разработчика. Так выглядят подделки настоящих приложений.")
                }
                if (a.sensitive.size >= 6) {
                    extra += FileCheck.Finding(1, "Просит много опасных доступов: ${a.sensitive.size}", "Чем больше доступов, тем больше приложение знает о тебе. Сверь список с тем, что оно должно делать.")
                }
            }
            val level = maxOf(r.level, extra.maxOfOrNull { it.level } ?: 0)
            VerdictPanel(
                when (level) { 2 -> "ОПАСНО ОТКРЫВАТЬ"; 1 -> "ЕСТЬ ВОПРОСЫ"; else -> "ПРИЗНАКОВ ОПАСНОСТИ НЕТ" },
                "$name · ${r.kind} · ${human(r.size)}",
                when (level) { 2 -> Ns.Red; 1 -> Ns.Amber; else -> Ns.Green },
            )
            (extra + r.findings).sortedByDescending { it.level }.forEach { AlertRow(it.level, it.title, it.detail) }

            if (a != null) {
                SectionTitle("приложение")
                KeyValue("название", a.label)
                KeyValue("пакет", a.pkg)
                KeyValue("версия", a.version + (a.installedVersion?.let { " (установлена $it)" } ?: " (не установлено)"))
                KeyValue("собрано под", "Android API ${a.targetSdk}", if (a.targetSdk in 1..28) Ns.Amber else Ns.Green)
                a.sameSigner?.let { KeyValue("подпись", if (it) "та же, что у установленного" else "ДРУГАЯ", if (it) Ns.Green else Ns.Red) }
                KeyValue("разрешений", "${a.permCount}, из них опасных ${a.sensitive.size}")
                a.sensitive.forEach { Text("  • $it", style = Ns.small.copy(color = Ns.Amber)) }
            }

            SectionTitle("отпечатки файла")
            SelectionContainer {
                Column {
                    KeyValue("MD5", r.md5)
                    KeyValue("SHA-1", r.sha1)
                    KeyValue("SHA-256", r.sha256)
                }
            }
            Text(
                "[найти этот отпечаток на VirusTotal →]",
                style = Ns.text.copy(color = Ns.Red),
                modifier = Modifier.clickable { ctx.openUrl("https://www.virustotal.com/gui/file/${r.sha256}") }.padding(vertical = 6.dp),
            )
            Text("в браузер уйдёт только отпечаток, сам файл остаётся на телефоне", style = Ns.small)

            SectionTitle("плотность данных")
            HudBar((r.entropy / 8).toFloat(), if (r.entropy > 7.2) Ns.Amber else Ns.Green)
            Text(
                "%.1f из 8 — ".format(r.entropy) + when {
                    r.entropy > 7.2 -> "сжато или зашифровано (для архивов, фото и видео это нормально)"
                    r.entropy > 5.5 -> "смесь кода и данных"
                    else -> "обычный текст или простая структура"
                },
                style = Ns.small, modifier = Modifier.padding(top = 4.dp),
            )

            if (r.urls.isNotEmpty() || r.ips.isNotEmpty() || r.emails.isNotEmpty()) {
                SectionTitle("адреса внутри файла · ${r.urls.size + r.ips.size + r.emails.size}")
                SelectionContainer {
                    Column {
                        (r.urls + r.ips + r.emails).forEach { Text(it, style = CODE.copy(fontSize = 11.sp, lineHeight = 16.sp, color = Ns.Text2)) }
                    }
                }
            }
            if (r.entries.isNotEmpty()) {
                SectionTitle("внутри архива · ${r.entries.size}")
                r.entries.take(40).forEach { e ->
                    Row {
                        Text((if (e.encrypted) "🔒 " else "") + e.name, style = Ns.small.copy(color = Ns.Text2), modifier = Modifier.weight(1f), maxLines = 1)
                        Text(human(e.size), style = Ns.small)
                    }
                }
                if (r.entries.size > 40) Text("… и ещё ${r.entries.size - 40}", style = Ns.small)
            }
            error?.let { Text(it, style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.padding(6.dp))
        }
        TermButton(if (busy) "ПРОВЕРЯЮ…" else if (report == null) "ВЫБРАТЬ ФАЙЛ" else "ПРОВЕРИТЬ ДРУГОЙ ФАЙЛ", Modifier.fillMaxWidth(), color = Ns.Red, enabled = !busy) {
            pick.launch(arrayOf("*/*"))
        }
    }
}
