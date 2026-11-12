package com.nullsignal.launcher.ui

import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nullsignal.launcher.sys.NetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val HISTORY = 60

/** Время TCP-подключения в мс, или null, если не ответил. */
private fun tcpLatency(host: String, port: Int): Long? {
    val t = SystemClock.elapsedRealtime()
    return runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1500) } }
        .map { SystemClock.elapsedRealtime() - t }
        .getOrNull()
}

private fun human(bytesPerSec: Double): String = when {
    bytesPerSec >= 1_048_576 -> "%.1f МБ/с".format(bytesPerSec / 1_048_576)
    bytesPerSec >= 1024 -> "%.0f КБ/с".format(bytesPerSec / 1024)
    else -> "%.0f Б/с".format(bytesPerSec)
}

@Composable
private fun Graph(values: List<Float>, color: Color, title: String, value: String) {
    Column(Modifier.fillMaxWidth().border(1.dp, Ns.Faint).padding(8.dp)) {
        Text("$title  $value", style = Ns.text.copy(color = color))
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp)) {
            val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
            for (k in 1..3) drawLine(Ns.Faint, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
            if (values.size < 2) return@Canvas
            val step = size.width / (HISTORY - 1)
            val offset = HISTORY - values.size
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = (offset + i) * step
                val y = size.height - v / max * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3f))
        }
    }
}

@Composable
fun MonitorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val down = remember { mutableStateListOf<Float>() }
    val up = remember { mutableStateListOf<Float>() }
    val pingGw = remember { mutableStateListOf<Float>() }
    val pingNet = remember { mutableStateListOf<Float>() }
    var info by remember { mutableStateOf("") }
    var lost by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }

    fun push(list: MutableList<Float>, v: Float) {
        list.add(v)
        while (list.size > HISTORY) list.removeAt(0)
    }

    LaunchedEffect(Unit) {
        var rx = TrafficStats.getTotalRxBytes()
        var tx = TrafficStats.getTotalTxBytes()
        var t = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val net = NetInfo.local(ctx)
            val (gw, inet) = withContext(Dispatchers.IO) {
                val g = net?.gateway?.let { tcpLatency(it, 53) ?: tcpLatency(it, 80) }
                g to tcpLatency("1.1.1.1", 443)
            }
            val now = SystemClock.elapsedRealtime()
            val nrx = TrafficStats.getTotalRxBytes()
            val ntx = TrafficStats.getTotalTxBytes()
            val dt = ((now - t).coerceAtLeast(1)) / 1000.0
            push(down, ((nrx - rx) / dt).toFloat().coerceAtLeast(0f))
            push(up, ((ntx - tx) / dt).toFloat().coerceAtLeast(0f))
            push(pingGw, (gw ?: 0L).toFloat())
            push(pingNet, (inet ?: 0L).toFloat())
            total++
            if (inet == null) lost++
            rx = nrx; tx = ntx; t = now
            info = net?.let { "${it.ip}/${it.prefix}  шлюз ${it.gateway ?: "?"}  ${if (it.onWifi) "Wi-Fi" else "моб. сеть"}" } ?: "нет сети"
        }
    }

    ModuleScreen("MONITOR", onBack) {
        Text(info.ifEmpty { "собираю данные…" }, style = Ns.small)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            Graph(down, Ns.Green, "↓ загрузка", human(down.lastOrNull()?.toDouble() ?: 0.0))
            Spacer(Modifier.height(6.dp))
            Graph(up, Ns.Cyan, "↑ отдача", human(up.lastOrNull()?.toDouble() ?: 0.0))
            Spacer(Modifier.height(6.dp))
            Graph(pingGw, Ns.Amber, "пинг до роутера", pingGw.lastOrNull()?.let { if (it > 0) "${it.toInt()} мс" else "нет ответа" } ?: "…")
            Spacer(Modifier.height(6.dp))
            Graph(pingNet, Ns.Red, "пинг до интернета (1.1.1.1)", pingNet.lastOrNull()?.let { if (it > 0) "${it.toInt()} мс" else "нет ответа" } ?: "…")
            Spacer(Modifier.height(6.dp))
            Text(
                "потери: $lost из $total  (${if (total > 0) lost * 100 / total else 0}%)",
                style = Ns.text.copy(color = if (lost > 0) Ns.Amber else Ns.Green),
            )
            Text("трафик — весь трафик телефона, пинг — время TCP-подключения", style = Ns.small)
        }
    }
}
