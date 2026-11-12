package com.nullsignal.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
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
import com.nullsignal.core.hw.Cells
import com.nullsignal.core.hw.Gnss
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
fun MitmScreen(onBack: () -> Unit) {
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

// ======================= СОТЫ =======================

private fun Int.known(): Int? = takeIf { it != Int.MAX_VALUE && it != Int.MIN_VALUE }
private fun Long.known(): Long? = takeIf { it != Long.MAX_VALUE && it >= 0 }

@Suppress("DEPRECATION")
private fun CellInfo.toCell(): Cells.Cell? = when (this) {
    is CellInfoLte -> cellIdentity.let { id ->
        Cells.Cell(
            Cells.Rat.LTE, isRegistered, if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.known()?.toString(),
            if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.known()?.let { "%02d".format(it) },
            id.tac.known(), id.ci.known()?.toLong(), id.pci.known(), id.earfcn.known(), cellSignalStrength.dbm.known(),
            cellSignalStrength.timingAdvance.known(),
        )
    }
    is CellInfoGsm -> cellIdentity.let { id ->
        Cells.Cell(
            Cells.Rat.GSM, isRegistered, if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.known()?.toString(),
            if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.known()?.let { "%02d".format(it) },
            id.lac.known(), id.cid.known()?.toLong(), id.bsic.known(), id.arfcn.known(), cellSignalStrength.dbm.known(),
            cellSignalStrength.timingAdvance.known(),
        )
    }
    is CellInfoWcdma -> cellIdentity.let { id ->
        Cells.Cell(
            Cells.Rat.WCDMA, isRegistered, if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.known()?.toString(),
            if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.known()?.let { "%02d".format(it) },
            id.lac.known(), id.cid.known()?.toLong(), id.psc.known(), id.uarfcn.known(), cellSignalStrength.dbm.known(), null,
        )
    }
    is CellInfoCdma -> Cells.Cell(
        Cells.Rat.CDMA, isRegistered, null, null, cellIdentity.networkId.known(), cellIdentity.basestationId.known()?.toLong(),
        null, null, cellSignalStrength.dbm.known(), null,
    )
    else -> when {
        Build.VERSION.SDK_INT >= 29 && this is CellInfoNr -> {
            val id = cellIdentity as CellIdentityNr
            val s = cellSignalStrength as CellSignalStrengthNr
            Cells.Cell(Cells.Rat.NR, isRegistered, id.mccString, id.mncString, id.tac.known(), id.nci.known(), id.pci.known(), id.nrarfcn.known(), s.dbm.known(), null)
        }
        Build.VERSION.SDK_INT >= 29 && this is CellInfoTdscdma -> cellIdentity.let { id ->
            Cells.Cell(Cells.Rat.TDSCDMA, isRegistered, id.mccString, id.mncString, id.lac.known(), id.cid.known()?.toLong(), id.cpid.known(), id.uarfcn.known(), cellSignalStrength.dbm.known(), null)
        }
        else -> null
    }
}

private fun Cells.Cell.key() = "${rat.name}:${plmn}:${area}:${cid}"

/** История сот живёт, пока жив процесс: так видно смену вышек и скачки 4G→2G. */
private object CellWatch {
    val history = mutableStateListOf<Cells.Cell>()
    val log = mutableStateListOf<Pair<Long, Cells.Cell>>()
    val signal = mutableStateListOf<Int>()

    fun push(serving: Cells.Cell?) {
        if (serving != null) {
            serving.dbm?.let { signal += it; if (signal.size > 90) signal.removeAt(0) }
            if (log.lastOrNull()?.second?.key() != serving.key()) {
                log += System.currentTimeMillis() to serving
                if (log.size > 60) log.removeAt(0)
            }
            if (history.none { it.key() == serving.key() }) {
                history += serving
                if (history.size > 40) history.removeAt(0)
            }
        }
    }
}

private fun cellLine(c: Cells.Cell): String = buildString {
    append(c.rat.title)
    c.arfcn?.let { a -> if (c.rat == Cells.Rat.LTE) Cells.lteBand(a)?.let { append(" · B$it") } }
    c.cid?.let { append(if (c.rat == Cells.Rat.LTE) " · eNB ${Cells.enb(it)}/${Cells.sector(it)}" else " · CID $it") }
    c.pci?.let { append(" · PCI $it") }
}

@Composable
private fun Sparkline(values: List<Int>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = -125f
        val hi = -45f
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        listOf(-60, -80, -100, -120).forEach { db ->
            val y = size.height * (1 - (db - lo) / (hi - lo))
            drawLine(Ns.Faint, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        }
        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, size.height * (1 - ((v - lo) / (hi - lo)).coerceIn(0f, 1f)))
            prev?.let { drawLine(color, it, p, 2f) }
            prev = p
        }
        prev?.let { drawCircle(color, 4f, it) }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun CellsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val (granted, request) = rememberPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    val tm = remember { ctx.getSystemService(TelephonyManager::class.java) }
    var cells by remember { mutableStateOf<List<Cells.Cell>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    if (granted) {
        LaunchedEffect(Unit) {
            while (true) {
                if (Build.VERSION.SDK_INT >= 29) {
                    runCatching {
                        tm?.requestCellInfoUpdate(ctx.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                cells = cellInfo.mapNotNull { runCatching { it.toCell() }.getOrNull() }
                                CellWatch.push(cells.firstOrNull { it.registered })
                            }
                        })
                    }
                } else {
                    runCatching { tm?.allCellInfo }.onSuccess { list ->
                        cells = list.orEmpty().mapNotNull { runCatching { it.toCell() }.getOrNull() }
                        CellWatch.push(cells.firstOrNull { it.registered })
                    }.onFailure { error = it.message }
                }
                delay(3000)
            }
        }
    }
    ModuleScreen("СОТЫ", onBack) {
        if (!granted) {
            PermissionGate("Данные о вышках Android выдаёт только с доступом к геолокации. Никуда не отправляются.", request)
            return@ModuleScreen
        }
        val serving = cells.firstOrNull { it.registered }
        val neighbors = cells.filter { !it.registered }.sortedByDescending { it.dbm ?: -999 }
        val sim = tm?.simOperator?.takeIf { it.length >= 5 }?.let { "${it.take(3)}-${it.drop(3)}" }
        val alerts = Cells.assess(serving, neighbors, sim, CellWatch.history.toList())
        val worst = alerts.maxOf { it.level }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                VerdictPanel(
                    when (worst) { 2 -> "ПОДОЗРИТЕЛЬНАЯ ВЫШКА"; 1 -> "ВНИМАНИЕ"; else -> "ВЫШКИ В НОРМЕ" },
                    "детектор фальшивых базовых станций (IMSI-перехватчиков) · обновление каждые 3 с",
                    if (worst == 0) Ns.Green else levelColor(worst),
                )
                alerts.filter { it.level > 0 }.forEach { AlertRow(it.level, it.text) }
            }
            if (serving != null) item {
                SectionTitle("обслуживающая сота")
                Column(Modifier.fillMaxWidth().hud(Ns.Red).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Cells.operatorName(serving.plmn) ?: serving.plmn ?: "оператор ?", style = Ns.title.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
                        Text(serving.rat.title, style = Ns.title.copy(color = Ns.Red, fontSize = 18.sp))
                    }
                    serving.dbm?.let { KeyValue("сигнал", "$it dBm  ${signalBars(it)}") }
                    if (serving.rat == Cells.Rat.LTE) serving.arfcn?.let { e ->
                        val b = Cells.lteBand(e)
                        KeyValue("диапазон", (b?.let { "B$it · ${Cells.lteBandMhz(it) ?: "?"} МГц" } ?: "?") + " · EARFCN $e")
                    } else serving.arfcn?.let { KeyValue("канал", "$it") }
                    serving.cid?.let {
                        if (serving.rat == Cells.Rat.LTE) KeyValue("базовая станция", "eNB ${Cells.enb(it)} · сектор ${Cells.sector(it)}")
                        else KeyValue("ID соты", "$it")
                    }
                    serving.area?.let { KeyValue(if (serving.rat == Cells.Rat.LTE || serving.rat == Cells.Rat.NR) "TAC" else "LAC", "$it") }
                    serving.pci?.let { KeyValue(if (serving.rat == Cells.Rat.GSM) "BSIC" else if (serving.rat == Cells.Rat.WCDMA) "PSC" else "PCI", "$it") }
                    Cells.distanceMeters(serving.rat, serving.ta)?.let { KeyValue("до вышки", "≈ $it м (по задержке сигнала)", Ns.Amber) }
                    sim?.let { KeyValue("SIM", Cells.operatorName(it) ?: it) }
                    if (CellWatch.signal.size > 1) {
                        Sparkline(CellWatch.signal.toList(), Ns.Red, Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp))
                        Text("сила сигнала за последние минуты", style = Ns.label)
                    }
                }
            }
            if (neighbors.isNotEmpty()) item { SectionTitle("соседние · ${neighbors.size}") }
            items(neighbors) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cellLine(c), style = Ns.small.copy(color = Ns.Green), modifier = Modifier.weight(1f))
                    Text(c.dbm?.let { "$it dBm" } ?: "—", style = Ns.small)
                }
            }
            if (CellWatch.log.size > 1) item { SectionTitle("журнал переключений") }
            items(CellWatch.log.reversed()) { (t, c) ->
                Text(
                    "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))}  ${Cells.operatorName(c.plmn) ?: c.plmn ?: "?"} · ${cellLine(c)}",
                    style = Ns.small.copy(color = if (c.rat == Cells.Rat.GSM) Ns.Amber else Ns.Text2),
                )
            }
            item {
                error?.let { Text(it, style = Ns.small.copy(color = Ns.Amber)) }
                Text(
                    "Перехватчики IMSI глушат 4G и заставляют телефон уйти на 2G, где шифрование слабое. Внезапный 2G при хорошей сети — главный признак. " +
                        "Защита: в настройках сети отключи 2G (Android 12+, пункт «Разрешить 2G»).",
                    style = Ns.small.copy(color = Ns.Dim),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }
}

// ======================= СПУТНИКИ =======================

private fun constellation(type: Int): Gnss.System = when (type) {
    GnssStatus.CONSTELLATION_GPS -> Gnss.System.GPS
    GnssStatus.CONSTELLATION_GLONASS -> Gnss.System.GLONASS
    GnssStatus.CONSTELLATION_GALILEO -> Gnss.System.GALILEO
    GnssStatus.CONSTELLATION_BEIDOU -> Gnss.System.BEIDOU
    GnssStatus.CONSTELLATION_QZSS -> Gnss.System.QZSS
    GnssStatus.CONSTELLATION_SBAS -> Gnss.System.SBAS
    7 -> Gnss.System.IRNSS
    else -> Gnss.System.UNKNOWN
}

private fun sysColor(s: Gnss.System): Color = when (s) {
    Gnss.System.GPS -> Ns.Green
    Gnss.System.GLONASS -> Ns.Red
    Gnss.System.GALILEO -> Ns.Cyan
    Gnss.System.BEIDOU -> Ns.Amber
    else -> Ns.Dim
}

/** Небо над головой: центр — зенит, край — горизонт. */
@Composable
private fun SkyPlot(sats: List<Gnss.Sat>, modifier: Modifier) {
    val t by rememberSeconds()
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 6f
        listOf(1f, 2 / 3f, 1 / 3f).forEach { k -> drawCircle(Ns.Faint, r * k, c, style = Stroke(1.2f)) }
        drawLine(Ns.Faint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(Ns.Faint, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        // медленный луч, как у радара
        val a = (t * 0.6f) % (2 * Math.PI.toFloat())
        drawLine(Ns.Red.copy(alpha = 0.35f), c, Offset(c.x + r * sin(a), c.y - r * cos(a)), 2f)
        drawLine(Ns.Red, Offset(c.x, c.y - r - 2f), Offset(c.x, c.y - r + 10f), 3f) // север
        sats.forEach { s ->
            val rr = r * (1 - s.elevation.coerceIn(0f, 90f) / 90f)
            val az = Math.toRadians(s.azimuth.toDouble())
            val p = Offset(c.x + (rr * sin(az)).toFloat(), c.y - (rr * cos(az)).toFloat())
            val col = sysColor(s.system)
            val rad = 3f + (s.cn0 / 50f).coerceIn(0f, 1f) * 7f
            if (s.used) drawCircle(col, rad, p) else drawCircle(col.copy(alpha = 0.7f), rad, p, style = Stroke(1.5f))
        }
    }
}

@Composable
private fun Cn0Bars(sats: List<Gnss.Sat>, modifier: Modifier) {
    Canvas(modifier) {
        if (sats.isEmpty()) return@Canvas
        val w = size.width / sats.size
        sats.forEachIndexed { i, s ->
            val h = size.height * (s.cn0 / 50f).coerceIn(0.02f, 1f)
            val col = sysColor(s.system)
            drawRect(if (s.used) col else col.copy(alpha = 0.35f), Offset(i * w + w * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(w * 0.7f, h))
        }
        val y = size.height * (1 - 30 / 50f)
        drawLine(Ns.Faint, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
    }
}

@SuppressLint("MissingPermission")
@Composable
fun GnssScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val (granted, request) = rememberPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    var sats by remember { mutableStateOf<List<Gnss.Sat>>(emptyList()) }
    val fixes = remember { mutableStateListOf<Gnss.Fix>() }
    var started by remember { mutableStateOf(System.currentTimeMillis()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf<String?>(null) }
    if (granted) {
        DisposableEffect(Unit) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            started = System.currentTimeMillis()
            val status = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(st: GnssStatus) {
                    sats = (0 until st.satelliteCount).map { i ->
                        Gnss.Sat(constellation(st.getConstellationType(i)), st.getSvid(i), st.getCn0DbHz(i), st.getElevationDegrees(i), st.getAzimuthDegrees(i), st.usedInFix(i))
                    }
                }
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    fixes += Gnss.Fix(l.time, System.currentTimeMillis(), l.latitude, l.longitude, l.accuracy)
                    if (fixes.size > 120) fixes.removeAt(0)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) { error = "геолокация выключена — включи её в шторке" }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) lm?.registerGnssStatusCallback(ctx.mainExecutor, status)
                else lm?.registerGnssStatusCallback(status, Handler(Looper.getMainLooper()))
                lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }.onFailure { error = it.message }
            onDispose {
                runCatching { lm?.unregisterGnssStatusCallback(status) }
                runCatching { lm?.removeUpdates(listener) }
            }
        }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    }
    ModuleScreen("СПУТНИКИ", onBack) {
        if (!granted) {
            PermissionGate("Нужна точная геолокация, чтобы слушать спутники. Координаты остаются на телефоне.", request)
            return@ModuleScreen
        }
        val last = fixes.lastOrNull()
        val noFix = (((now - (last?.systemTime ?: started)) / 1000).toInt()).coerceAtLeast(0)
        val alerts = Gnss.assess(sats, fixes.toList(), noFix)
        val worst = alerts.maxOf { it.level }
        val visible = sats.filter { it.cn0 > 0 }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                VerdictPanel(
                    when (worst) { 2 -> "ПОХОЖЕ НА ПОДМЕНУ / ГЛУШЕНИЕ"; 1 -> "ВНИМАНИЕ"; else -> if (visible.isEmpty()) "ИЩУ СПУТНИКИ" else "СИГНАЛ ЧЕСТНЫЙ" },
                    "видно ${visible.size} · в решении ${sats.count { it.used }} · ${if (last == null) "координат пока нет ($noFix с)" else "точность ±${last.accuracy.toInt()} м"}",
                    if (worst == 0) (if (visible.isEmpty()) Ns.Cyan else Ns.Green) else levelColor(worst),
                )
                alerts.filter { it.level > 0 }.forEach { AlertRow(it.level, it.text) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    SkyPlot(sats, Modifier.fillMaxWidth(0.82f).aspectRatio(1f))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Gnss.System.entries.filter { s -> sats.any { it.system == s } }.forEach { s ->
                        Text("● ${s.title.substringBefore(" (")} ${sats.count { it.system == s && it.used }}/${sats.count { it.system == s }}", style = Ns.small.copy(color = sysColor(s)))
                    }
                }
                Text("● — в решении, ○ — видим, размер = сила сигнала", style = Ns.label, modifier = Modifier.padding(top = 4.dp))
                if (visible.isNotEmpty()) {
                    SectionTitle("сила сигнала, дБ·Гц")
                    Cn0Bars(visible.sortedWith(compareBy({ it.system.ordinal }, { it.svid })), Modifier.fillMaxWidth().height(70.dp))
                }
                last?.let { f ->
                    SectionTitle("последняя точка")
                    KeyValue("координаты", "%.5f, %.5f".format(Locale.US, f.lat, f.lon))
                    KeyValue("точность", "±${f.accuracy.toInt()} м")
                    val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    KeyValue("время спутников", utc.format(Date(f.gnssTime)) + " UTC")
                    val skew = (f.gnssTime - f.systemTime) / 1000.0
                    KeyValue("расхождение с телефоном", "%+.1f с".format(Locale.US, skew), if (kotlin.math.abs(skew) > 5) Ns.Red else Ns.Green)
                }
                error?.let { Text(it, style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(top = 6.dp)) }
                Text(
                    "Подменный GPS-сигнал обычно слишком «ровный»: все спутники одинаковой силы, время уезжает, точка прыгает на десятки километров. " +
                        "Глушилка — наоборот: спутники видны, но слабые, а координат нет. В помещении сигнал тоже слабый — проверяй на улице.",
                    style = Ns.small.copy(color = Ns.Dim),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }
}
