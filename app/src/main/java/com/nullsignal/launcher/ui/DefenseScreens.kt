package com.nullsignal.launcher.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.security.Mitm
import com.nullsignal.launcher.sys.DnsMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.math.cos
import kotlin.math.sin

// ======================= общие элементы =======================

private fun levelColor(level: Int): Color = when (level) { 2 -> Ns.Red; 1 -> Ns.Amber; else -> Ns.Dim }

/** Строка вердикта: метка уровня, заголовок, пояснение. */
@Composable
internal fun AlertRow(level: Int, title: String, detail: String? = null) {
    val c = levelColor(level)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).hud(if (level == 0) Ns.Faint else c).padding(10.dp)) {
        Text(
            when (level) { 2 -> "!!"; 1 -> "!"; else -> "ok" },
            style = Ns.text.copy(color = c, fontWeight = FontWeight.Bold),
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = Ns.text.copy(color = if (level == 0) Ns.Green else c))
            if (!detail.isNullOrBlank()) Text(detail, style = Ns.small, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Крупная плашка итога. */
@Composable
internal fun VerdictPanel(title: String, sub: String, color: Color, progress: Float? = null) {
    Column(Modifier.fillMaxWidth().hud(color).padding(12.dp)) {
        Text(title, style = Ns.title.copy(color = color, fontSize = 22.sp))
        Text(sub, style = Ns.small, modifier = Modifier.padding(top = 2.dp))
        if (progress != null) HudBar(progress, color, Modifier.padding(top = 8.dp), height = 3.dp)
    }
}

private fun Throwable.chainHas(cls: Class<*>): Boolean {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < 10) { if (cls.isInstance(t)) return true; t = t.cause }
    return false
}

// ======================= ПРОСЛУШКА? =======================

private class Check(val title: String, val run: (Context) -> List<Mitm.Finding>)

private fun F(level: Int, title: String, detail: String) = Mitm.Finding(level, title, detail)

private fun checkUserCa(): List<Mitm.Finding> {
    val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val users = ks.aliases().toList().filter { it.startsWith("user:") }
        .mapNotNull { (ks.getCertificate(it) as? X509Certificate)?.subjectX500Principal?.name }
    return if (users.isEmpty()) listOf(F(0, "Чужих корневых сертификатов нет", "никто не добавлял в систему сертификатов для расшифровки HTTPS"))
    else listOf(
        F(
            1, "Добавлены свои корневые сертификаты: ${users.size}",
            users.joinToString("\n") { "• " + (Mitm.commonName(it) ?: it) } +
                "\nС таким сертификатом владелец может расшифровывать HTTPS в браузере. Если ставил не ты — удали: " +
                "Настройки → Безопасность → Шифрование и учётные данные → Пользовательские сертификаты.",
        ),
    )
}

@Suppress("DEPRECATION")
private fun checkRoute(ctx: Context): List<Mitm.Finding> {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val out = mutableListOf<Mitm.Finding>()
    val lp = cm.getLinkProperties(cm.activeNetwork)
    val proxy = lp?.httpProxy
    out += when {
        proxy?.host != null -> F(1, "Трафик идёт через прокси ${proxy.host}:${proxy.port}", "Прокси видит адреса сайтов и всё незашифрованное. Если настраивал не ты — убери в настройках этой сети Wi-Fi.")
        proxy?.pacFileUrl != null && proxy.pacFileUrl != Uri.EMPTY -> F(1, "Прокси с автонастройкой", "${proxy.pacFileUrl} — сеть сама решает, через какой прокси пускать трафик")
        else -> F(0, "Прокси не настроен", "трафик идёт напрямую")
    }
    val vpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    out += when {
        vpn && DnsMonitor.running -> F(0, "VPN: ПЕРЕХВАТ NULL//SIGNAL", "это наш DNS-фильтр, он работает только на телефоне")
        vpn -> F(1, "Весь трафик идёт через VPN-приложение", "VPN видит, куда ходит телефон. Если включал не ты — Настройки → Сеть → VPN.")
        else -> F(0, "VPN не активен", "")
    }
    val servers = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
    val privateDns = Build.VERSION.SDK_INT >= 28 && lp?.isPrivateDnsActive == true
    out += if (privateDns) {
        F(0, "DNS зашифрован", (if (Build.VERSION.SDK_INT >= 28) lp?.privateDnsServerName else null)?.let { "через $it — " }.orEmpty() + "провайдер не видит, какие сайты ты открываешь")
    } else {
        F(1, "DNS идёт открытым текстом", "Имена сайтов уходят без шифрования на ${servers.joinToString().ifEmpty { "сервер сети" }} — их видит провайдер и владелец Wi-Fi. Включи «Частный DNS»: dns.google или one.one.one.one.")
    }
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true) {
        out += F(1, "Сеть требует вход через страницу", "гостевой Wi-Fi: пока не вошёл, сеть подменяет все страницы своей")
    }
    if (Build.VERSION.SDK_INT >= 31 && caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
        when ((caps.transportInfo as? WifiInfo)?.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN -> out += F(1, "Wi-Fi без пароля", "всё, что идёт без HTTPS, видят все в радиусе сети")
            WifiInfo.SECURITY_TYPE_WEP -> out += F(2, "Wi-Fi на WEP", "WEP вскрывается за минуты — считай, что пароля нет")
            WifiInfo.SECURITY_TYPE_OWE -> out += F(0, "Wi-Fi: открытый, но шифрованный (OWE)", "")
            null, -1 -> Unit
            else -> out += F(0, "Wi-Fi зашифрован", "")
        }
    }
    return out
}

private fun checkDnsHijack(): List<Mitm.Finding> {
    val d = Mitm.randomDomain()
    val ips = runCatching { InetAddress.getAllByName(d).mapNotNull { it.hostAddress } }.getOrNull()
    return if (ips.isNullOrEmpty()) listOf(F(0, "Ответы DNS честные", "выдуманный домен не нашёлся — так и должно быть"))
    else listOf(F(2, "DNS подделывает ответы", "несуществующий $d «нашёлся» по адресу ${ips.joinToString()} — ответы подменяет провайдер, гостевой Wi-Fi или атакующий"))
}

private fun checkHttp(): List<Mitm.Finding> {
    val c = (URL("http://example.com/").openConnection() as HttpURLConnection).apply {
        connectTimeout = 7000; readTimeout = 7000; instanceFollowRedirects = false; useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
    }
    return try {
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location").orEmpty()
            if (loc.startsWith("https://example.com")) listOf(F(0, "Страницы не подменяются", "сайт сам перевёл на HTTPS"))
            else listOf(F(2, "Страницу перенаправили", "вместо example.com сеть отправила на ${loc.ifEmpty { "?" }} — страница входа гостевого Wi-Fi или подмена"))
        } else {
            val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(60_000) } }.getOrNull()
            listOf(
                when (Mitm.checkExample(code, body)) {
                    Mitm.HttpVerdict.CLEAN -> F(0, "Страницы не подменяются", "незашифрованная страница пришла без чужих вставок")
                    Mitm.HttpVerdict.INJECTED -> F(2, "В страницу вставлен чужой код", "по пути в открытую страницу добавили <script>/<iframe> — реклама провайдера или атака")
                    Mitm.HttpVerdict.REPLACED -> F(2, "Страница подменена", "вместо example.com пришло что-то другое (код $code) — страница входа, блокировка или подмена")
                    Mitm.HttpVerdict.FAILED -> F(0, "Проверка страниц", "сайт не ответил")
                },
            )
        }
    } catch (e: Exception) {
        listOf(F(0, "Проверка страниц", "нет связи: ${e.javaClass.simpleName}"))
    } finally {
        c.disconnect()
    }
}

private val TLS_HOSTS = listOf("www.google.com", "www.cloudflare.com", "ru.wikipedia.org", "github.com")

private fun checkTls(): List<Mitm.Finding> {
    val ok = mutableListOf<String>()
    val bad = mutableListOf<Mitm.Finding>()
    for (host in TLS_HOSTS) {
        try {
            val c = (URL("https://$host/").openConnection() as HttpsURLConnection).apply {
                connectTimeout = 7000; readTimeout = 7000; instanceFollowRedirects = false
            }
            try {
                c.connect()
                val issuer = (c.serverCertificates.firstOrNull() as? X509Certificate)?.issuerX500Principal?.name.orEmpty()
                val org = Mitm.issuerOrg(issuer) ?: Mitm.commonName(issuer) ?: issuer
                if (Mitm.trustedIssuer(issuer)) ok += "$host — $org"
                else bad += F(2, "$host: сертификат выдал «$org»", "Крупные сайты подписывают известные центры. Чужая подпись = HTTPS расшифровывают по пути (корпоративный прокси, «защитник» или атака).")
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            bad += if (e.chainHas(CertPathValidatorException::class.java) || e is SSLPeerUnverifiedException) {
                F(2, "$host: подсунут чужой сертификат", "телефон не поверил сертификату — кто-то между тобой и сайтом выдаёт себя за него (перехват HTTPS или страница блокировки)")
            } else {
                F(0, "$host: нет связи", e.javaClass.simpleName)
            }
        }
    }
    return buildList {
        if (ok.isNotEmpty()) add(F(0, "HTTPS не подменяется", ok.joinToString("\n")))
        addAll(bad)
    }
}

private suspend fun checkMicCam(ctx: Context): List<Mitm.Finding> {
    val out = mutableListOf<Mitm.Finding>()
    val am = ctx.getSystemService(AudioManager::class.java)
    val rec = runCatching { am?.activeRecordingConfigurations?.size ?: 0 }.getOrDefault(0)
    out += if (rec > 0) F(1, "Микрофон сейчас пишет: $rec", "какое-то приложение записывает звук прямо сейчас. Если это не звонок и не диктофон — посмотри «Панель конфиденциальности» в настройках.")
    else F(0, "Микрофон никто не пишет", "")
    val cam = ctx.getSystemService(CameraManager::class.java)
    if (cam != null) {
        val busy = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) { busy += cameraId }
            override fun onCameraAvailable(cameraId: String) { busy -= cameraId }
        }
        runCatching { cam.registerAvailabilityCallback(cb, Handler(Looper.getMainLooper())) }
        delay(700)
        runCatching { cam.unregisterAvailabilityCallback(cb) }
        out += if (busy.isNotEmpty()) F(1, "Камера занята другим приложением", "камеры ${busy.joinToString()} сейчас используются — если ты ничего не снимаешь, это странно")
        else F(0, "Камеры свободны", "")
    }
    return out
}

@Composable
fun MitmScreen(onBack: () -> Unit, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val findings = remember { mutableStateListOf<Mitm.Finding>() }
    var stage by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(0) }
    val checks = remember {
        listOf(
            Check("сертификаты") { checkUserCa() },
            Check("маршрут трафика") { checkRoute(it) },
            Check("подмена DNS") { checkDnsHijack() },
            Check("подмена страниц") { checkHttp() },
            Check("подмена HTTPS") { checkTls() },
        )
    }
    fun run() {
        if (stage != null) return
        scope.launch {
            findings.clear()
            checks.forEachIndexed { i, c ->
                stage = c.title; step = i
                findings += withContext(Dispatchers.IO) {
                    runCatching { c.run(ctx) }.getOrElse { listOf(F(0, c.title, "не удалось проверить: ${it.message ?: it.javaClass.simpleName}")) }
                }
            }
            stage = "микрофон и камера"; step = checks.size
            findings += runCatching { checkMicCam(ctx) }.getOrDefault(emptyList())
            stage = null
            onDone()
        }
    }
    LaunchedEffect(Unit) { run() }

    ModuleScreen("ПРОСЛУШКА?", onBack) {
        val level = Mitm.overall(findings)
        val busy = stage != null
        val color = if (busy) Ns.Cyan else levelColor(level).takeIf { level > 0 } ?: Ns.Green
        VerdictPanel(
            when {
                busy -> "ПРОВЕРЯЮ: ${stage!!.uppercase()}"
                level == 2 -> "ПРИЗНАКИ ПЕРЕХВАТА"
                level == 1 -> "ЕСТЬ РИСКИ"
                else -> "ЧИСТО"
            },
            if (busy) "сеть, DNS, HTTPS, микрофон и камера — всё проверяется прямо с телефона"
            else "${findings.count { it.level == 2 }} опасно · ${findings.count { it.level == 1 }} внимание · ${findings.count { it.level == 0 }} в норме",
            color,
            if (busy) (step + 1f) / (checks.size + 1) else null,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            items(if (busy) findings.toList() else findings.sortedByDescending { it.level }) { f -> AlertRow(f.level, f.title, f.detail) }
            item {
                Text(
                    "Проверяется только эта сеть и этот телефон. Зелёный итог не гарантия, а красный — повод выяснить, кто и зачем стоит между тобой и интернетом.",
                    style = Ns.small.copy(color = Ns.Dim),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        TermButton("ПРОВЕРИТЬ ЕЩЁ РАЗ", Modifier.fillMaxWidth(), color = Ns.Red, enabled = stage == null) { run() }
    }
}
