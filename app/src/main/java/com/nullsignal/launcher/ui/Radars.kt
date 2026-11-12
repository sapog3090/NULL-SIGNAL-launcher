package com.nullsignal.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nullsignal.core.terminal.Tools
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

// ---------------- WIFI-RADAR ----------------

data class WifiNet(val ssid: String, val bssid: String, val level: Int, val freq: Int, val security: String, val wps: Boolean = false) {
    val channel: Int get() = Tools.wifiChannel(freq)
}

@SuppressLint("MissingPermission")
@Composable
fun WifiScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val (granted, request) = rememberPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    var nets by remember { mutableStateOf<List<WifiNet>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    val wifi = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager? }

    fun read() {
        val w = wifi ?: return
        nets = runCatching {
            w.scanResults.map {
                @Suppress("DEPRECATION")
                val ssid = it.SSID
                WifiNet(
                    ssid.ifBlank { "<скрытая сеть>" }, it.BSSID ?: "?", it.level, it.frequency,
                    Tools.wifiSecurity(it.capabilities ?: ""), com.nullsignal.core.hw.WifiChannels.wps(it.capabilities ?: ""),
                )
            }.sortedByDescending { it.level }
        }.getOrDefault(emptyList())
        status = if (nets.isEmpty()) "пусто. включи Wi-Fi и геолокацию (без неё Android не отдаёт список)" else "сетей в эфире: ${nets.size}"
    }

    if (granted) {
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = read()
            }
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED,
            )
            onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
        }
        LaunchedEffect(Unit) {
            read()
            while (true) {
                @Suppress("DEPRECATION")
                runCatching { wifi?.startScan() } // Android ограничивает: ~4 скана за 2 минуты
                delay(30_000)
            }
        }
    }

    ModuleScreen("WIFI-RADAR", onBack) {
        if (!granted) {
            PermissionGate("Android показывает Wi-Fi сети вокруг только приложениям с доступом к геолокации.", request)
            return@ModuleScreen
        }
        var tab by remember { mutableStateOf(0) }
        Text(status, style = Ns.text.copy(color = Ns.Amber))
        val open = nets.count { it.security == "OPEN" || it.security == "WEP" }
        if (open > 0) Text("⚠ небезопасных сетей: $open — не вводи там пароли", style = Ns.small.copy(color = Ns.Red))
        val wps = nets.count { it.wps }
        if (wps > 0) Text("⚠ сетей с WPS: $wps — WPS-PIN подбирается за часы, у себя выключи", style = Ns.small.copy(color = Ns.Amber))
        val twins = com.nullsignal.core.hw.WifiChannels.twins(nets.map { com.nullsignal.core.hw.WifiChannels.Ap(it.ssid, it.bssid, it.security, it.level) })
        val twinBssids = twins.flatMap { t -> t.open.map { it.bssid } }.toSet()
        twins.forEach { t ->
            AlertRow(
                2, "ДВОЙНИК: «${t.ssid}»",
                "Эта сеть есть с паролем (${t.secured.size} точ.) и без пароля (${t.open.joinToString { "${it.bssid}, ${it.level} dBm" }}). " +
                    "Открытая копия может быть ловушкой — не подключайся к ней и забудь её в настройках, если телефон уже подключался.",
            )
        }
        Spacer(Modifier.height(6.dp))
        NsTabs(listOf("СЕТИ", "КАНАЛЫ"), tab, Ns.Red) { tab = it }
        if (tab == 1) {
            ChannelsTab(nets)
            return@ModuleScreen
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(nets, key = { it.bssid + it.freq }) { n ->
                val weak = n.security == "OPEN" || n.security == "WEP"
                val twin = n.bssid in twinBssids
                Column(Modifier.fillMaxWidth().hud(if (weak) Ns.Red else Ns.Faint).padding(8.dp)) {
                    Row {
                        Text(signalBars(n.level) + "  ", style = Ns.text.copy(color = Ns.Cyan))
                        Text(n.ssid, style = Ns.text, modifier = Modifier.weight(1f), maxLines = 1)
                        if (twin) Text("ДВОЙНИК ", style = Ns.text.copy(color = Ns.Red))
                        if (n.wps) Text("WPS ", style = Ns.text.copy(color = Ns.Amber))
                        Text(n.security, style = Ns.text.copy(color = if (weak) Ns.Red else Ns.Green))
                    }
                    Text(
                        "${n.bssid}  ${n.level} dBm  ch${n.channel}  ${if (n.freq > 5900) "6 ГГц" else if (n.freq > 4900) "5 ГГц" else "2.4 ГГц"}",
                        style = Ns.small,
                    )
                }
            }
        }
    }
}

/** Загрузка каналов и лучший канал для своего роутера. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ChannelsTab(nets: List<WifiNet>) {
    val ch = nets.map { com.nullsignal.core.hw.WifiChannels.Net(it.channel, it.level, it.freq) }
    val load24 = com.nullsignal.core.hw.WifiChannels.load24(ch)
    val load5 = com.nullsignal.core.hw.WifiChannels.load5(ch)
    val best = com.nullsignal.core.hw.WifiChannels.best24(ch)
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Panel(color = Ns.Red) {
            Text("ЛУЧШИЙ КАНАЛ 2.4 ГГц: $best", style = Ns.title.copy(fontSize = 18.sp, color = Ns.Red))
            Text(
                "Выбери его в настройках своего роутера — меньше помех от соседей, выше скорость. " +
                    "Каналы 1, 6 и 11 не перекрываются между собой.",
                style = Ns.small,
            )
        }
        SectionTitle("загрузка 2.4 ГГц (с учётом перекрытия)")
        val max = (load24.maxOfOrNull { it.score } ?: 1.0).coerceAtLeast(0.5)
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 6.dp)) {
            val w = size.width / load24.size
            load24.forEachIndexed { i, l ->
                val h = (l.score / max).toFloat() * (size.height - 4f)
                drawRect(
                    if (l.channel == best) Ns.Red else if (l.channel in listOf(1, 6, 11)) Ns.Green else Ns.Dim,
                    topLeft = Offset(i * w + w * 0.15f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(w * 0.7f, h.coerceAtLeast(2f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            load24.forEach { l ->
                Text(
                    "${l.channel}", style = Ns.small.copy(fontSize = 9.sp, color = if (l.channel == best) Ns.Red else Ns.Dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        load24.filter { it.networks > 0 }.forEach { l -> KeyValue("канал ${l.channel}", "сетей: ${l.networks}") }
        if (load5.isNotEmpty()) {
            SectionTitle("5 ГГц")
            Text("В 5 ГГц каналов много и они не перекрываются — если роутер умеет, переходи туда.", style = Ns.small)
            load5.forEach { l -> KeyValue("канал ${l.channel}", "сетей: ${l.networks}") }
        }
    }
}

// ---------------- BLE-RADAR ----------------

data class BleDev(val address: String, val name: String?, val rssi: Int, val txPower: Int?, val seen: Long, val vendor: String? = null)

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@SuppressLint("MissingPermission")
@Composable
fun BleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val (granted, request) = rememberPermissions(blePermissions())
    val devices = remember { mutableStateMapOf<String, BleDev>() }
    var status by remember { mutableStateOf("") }

    if (granted) {
        DisposableEffect(Unit) {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            status = when {
                adapter == null -> "на устройстве нет Bluetooth"
                scanner == null -> "включи Bluetooth"
                else -> "слушаю эфир…"
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, r: ScanResult) {
                    val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull()
                    val tx = r.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
                    val man = r.scanRecord?.manufacturerSpecificData
                    val vendor = if (man != null && man.size() > 0) {
                        val id = man.keyAt(0)
                        val base = com.nullsignal.core.hw.BleVendors.name(id)
                        val apple = if (id == 0x004C) com.nullsignal.core.hw.BleVendors.appleKind(man.valueAt(0) ?: ByteArray(0)) else null
                        listOfNotNull(base, apple).joinToString(" · ").ifEmpty { null }
                    } else null
                    devices[r.device.address] = BleDev(r.device.address, name, r.rssi, tx, System.currentTimeMillis(), vendor)
                }
                override fun onScanFailed(errorCode: Int) {
                    status = "ошибка сканирования ($errorCode)"
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner?.startScan(null, settings, cb) }.onFailure { status = "ошибка: ${it.message}" }
            onDispose { runCatching { scanner?.stopScan(cb) } }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                val cutoff = System.currentTimeMillis() - 20_000
                devices.keys.filter { (devices[it]?.seen ?: 0) < cutoff }.forEach { devices.remove(it) }
            }
        }
    }

    ModuleScreen("BLE-RADAR", onBack) {
        if (!granted) {
            PermissionGate("Нужен доступ к устройствам поблизости, чтобы слушать Bluetooth-эфир.", request)
            return@ModuleScreen
        }
        val list = devices.values.sortedByDescending { it.rssi }
        Text("$status  устройств: ${list.size}", style = Ns.text.copy(color = Ns.Amber))
        val vendors = list.mapNotNull { it.vendor?.substringBefore(" · ") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (vendors.isNotEmpty()) Text(vendors.take(5).joinToString("  ") { "${it.key} ${it.value}" }, style = Ns.small)
        Radar(list, Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.address }) { d ->
                val dist = Tools.distanceMeters(d.rssi, d.txPower?.let { it - 41 } ?: -59)
                Row {
                    Text(signalBars(d.rssi) + " ", style = Ns.small.copy(color = Ns.Cyan))
                    Column(Modifier.weight(1f)) {
                        Text(d.name ?: d.vendor ?: d.address, style = Ns.small.copy(color = if (d.name != null) Ns.Green else Ns.Dim), maxLines = 1)
                        if (d.name != null && d.vendor != null) Text(d.vendor, style = Ns.small.copy(fontSize = 9.sp, color = Ns.Faint), maxLines = 1)
                    }
                    Text("${d.rssi}dBm ~%.1fм".format(dist), style = Ns.small)
                }
            }
        }
    }
}

/** Вращающийся радар: точки — устройства, чем ближе к центру, тем сильнее сигнал. */
@Composable
private fun Radar(devs: List<BleDev>, modifier: Modifier) {
    val t by rememberSeconds()
    val sweep = (t * 120f) % 360f // оборот за 3 секунды, без системных анимаций
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        for (k in 1..4) drawCircle(Ns.Faint, r * k / 4, c, style = Stroke(1.5f))
        drawLine(Ns.Faint, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(Ns.Faint, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        drawArc(
            Brush.sweepGradient(listOf(Ns.Red.copy(alpha = 0f), Ns.Red.copy(alpha = 0.4f)), c),
            startAngle = sweep - 60f, sweepAngle = 60f, useCenter = true,
            topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
        )
        val rad = Math.toRadians(sweep.toDouble())
        drawLine(Ns.Red, c, Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()), strokeWidth = 2f)
        devs.forEach { d ->
            val dist = Tools.distanceMeters(d.rssi).coerceIn(0.3, 30.0)
            val norm = (ln(dist / 0.3) / ln(100.0)).toFloat().coerceIn(0.05f, 1f) // логарифмическая шкала 0.3–30 м
            val angle = Math.toRadians((d.address.hashCode() and 0x7fffffff) % 360.0)
            val p = Offset(c.x + r * norm * cos(angle).toFloat(), c.y + r * norm * sin(angle).toFloat())
            drawCircle((if (d.name != null) Ns.Cyan else Ns.Green).copy(alpha = 0.25f), 12f, p)
            drawCircle(if (d.name != null) Ns.Cyan else Ns.Green, 5f, p)
        }
    }
}
