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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

data class WifiNet(val ssid: String, val bssid: String, val level: Int, val freq: Int, val security: String)

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
                WifiNet(ssid.ifBlank { "<скрытая сеть>" }, it.BSSID ?: "?", it.level, it.frequency, Tools.wifiSecurity(it.capabilities ?: ""))
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
        Text(status, style = Ns.text.copy(color = Ns.Amber))
        val open = nets.count { it.security == "OPEN" || it.security == "WEP" }
        if (open > 0) Text("⚠ небезопасных сетей: $open — не вводи там пароли", style = Ns.small.copy(color = Ns.Red))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(nets, key = { it.bssid + it.freq }) { n ->
                val weak = n.security == "OPEN" || n.security == "WEP"
                Column(Modifier.fillMaxWidth().hud(if (weak) Ns.Red else Ns.Faint).padding(8.dp)) {
                    Row {
                        Text(signalBars(n.level) + "  ", style = Ns.text.copy(color = Ns.Cyan))
                        Text(n.ssid, style = Ns.text, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(n.security, style = Ns.text.copy(color = if (weak) Ns.Red else Ns.Green))
                    }
                    Text(
                        "${n.bssid}  ${n.level} dBm  ch${Tools.wifiChannel(n.freq)}  ${if (n.freq > 5900) "6 ГГц" else if (n.freq > 4900) "5 ГГц" else "2.4 ГГц"}",
                        style = Ns.small,
                    )
                }
            }
        }
    }
}

// ---------------- BLE-RADAR ----------------

data class BleDev(val address: String, val name: String?, val rssi: Int, val txPower: Int?, val seen: Long)

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
                    devices[r.device.address] = BleDev(r.device.address, name, r.rssi, tx, System.currentTimeMillis())
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
        Radar(list, Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.address }) { d ->
                val dist = Tools.distanceMeters(d.rssi, d.txPower?.let { it - 41 } ?: -59)
                Row {
                    Text(signalBars(d.rssi) + " ", style = Ns.small.copy(color = Ns.Cyan))
                    Text(d.name ?: d.address, style = Ns.small.copy(color = if (d.name != null) Ns.Green else Ns.Dim), modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${d.rssi}dBm ~%.1fм".format(dist), style = Ns.small)
                }
            }
        }
    }
}

/** Вращающийся радар: точки — устройства, чем ближе к центру, тем сильнее сигнал. */
@Composable
private fun Radar(devs: List<BleDev>, modifier: Modifier) {
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        for (k in 1..4) drawCircle(Ns.Faint, r * k / 4, c, style = Stroke(1.5f))
        drawLine(Ns.Faint, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(Ns.Faint, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        drawArc(
            Brush.sweepGradient(listOf(Ns.Green.copy(alpha = 0f), Ns.Green.copy(alpha = 0.35f)), c),
            startAngle = sweep - 60f, sweepAngle = 60f, useCenter = true,
            topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
        )
        val rad = Math.toRadians(sweep.toDouble())
        drawLine(Ns.Green, c, Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()), strokeWidth = 2f)
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
