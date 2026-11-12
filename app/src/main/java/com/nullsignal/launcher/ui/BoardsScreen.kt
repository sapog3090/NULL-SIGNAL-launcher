package com.nullsignal.launcher.ui

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.hw.UsbInfo
import com.nullsignal.launcher.sys.SerialLink
import kotlinx.coroutines.delay

private val BAUDS = listOf(9600, 57600, 115200, 230400, 921600)

@Composable
fun BoardsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val um = remember { ctx.getSystemService(UsbManager::class.java) }
    var devices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }
    var tab by remember { mutableIntStateOf(if (SerialLink.connected) 1 else 0) }
    var baud by remember { mutableIntStateOf(115200) }
    LaunchedEffect(Unit) {
        while (true) {
            devices = runCatching { um?.deviceList?.values?.toList().orEmpty() }.getOrDefault(emptyList())
            delay(1500)
        }
    }
    ModuleScreen("ПЛАТЫ", onBack) {
        NsTabs(listOf("УСТРОЙСТВА · ${devices.size}", "ТЕРМИНАЛ"), tab, Ns.Red) { tab = it }
        if (tab == 0) DevicesTab(devices) { d -> tab = 1; SerialLink.requestAndConnect(ctx, d, baud) }
        else SerialTab(devices, baud, onBaud = { baud = it })
    }
}

@Composable
private fun ColumnScope.DevicesTab(devices: List<UsbDevice>, onOpen: (UsbDevice) -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (devices.isEmpty()) {
            Panel {
                Text("ПОДКЛЮЧИ УСТРОЙСТВО ЧЕРЕЗ OTG", style = Ns.title.copy(fontSize = 16.sp))
                Text(
                    "Переходник USB-C/micro-USB → USB-A. Подойдут платы Arduino, ESP32/ESP8266, Raspberry Pi Pico и любые USB-устройства.\n\n" +
                        "Проверка «флешки»: подключи найденную или подаренную флешку/кабель — модуль покажет, не притворяется ли она клавиатурой (BadUSB).",
                    style = Ns.small,
                )
            }
        }
        devices.forEach { d ->
            val ifaces = SerialLink.ifaces(d)
            val v = UsbInfo.assess(d.vendorId, d.productId, ifaces)
            val color = when (v.level) { 2 -> Ns.Red; 1 -> Ns.Amber; else -> Ns.Dim }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).hud(color).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModuleIcon(com.nullsignal.core.terminal.Module.BOARDS, color, size = 20.dp)
                    Spacer(Modifier.padding(4.dp))
                    Text(SerialLink.label(d), style = Ns.title.copy(fontSize = 16.sp, letterSpacing = 0.sp))
                }
                KeyValue("VID:PID", "%04X:%04X".format(d.vendorId, d.productId))
                UsbInfo.vendor(d.vendorId)?.let { KeyValue("производитель", it) }
                runCatching { d.manufacturerName }.getOrNull()?.let { KeyValue("представляется", "$it ${runCatching { d.productName }.getOrNull().orEmpty()}") }
                ifaces.forEachIndexed { i, f ->
                    KeyValue("интерфейс ${i + 1}", UsbInfo.hidKind(f)?.let { "HID — $it" } ?: UsbInfo.className(f.cls), if (f.cls == 3) Ns.Amber else Ns.Green)
                }
                v.notes.forEach { n -> Text((if (v.level > 0) "⚠ " else "✓ ") + n, style = Ns.small.copy(color = if (v.level > 0) color else Ns.Green), modifier = Modifier.padding(top = 4.dp)) }
                if (SerialLink.canSerial(d)) {
                    TermButton("ОТКРЫТЬ ТЕРМИНАЛ", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red) { onOpen(d) }
                }
            }
        }
    }
}

private val QUICK = listOf(
    "^C" to byteArrayOf(3), "^D" to byteArrayOf(4), "help()" to "help()\r\n".toByteArray(),
    "ls" to "import os; print(os.listdir())\r\n".toByteArray(),
)

@Composable
private fun ColumnScope.SerialTab(devices: List<UsbDevice>, baud: Int, onBaud: (Int) -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var ending by remember { mutableStateOf("\r\n") }
    val list = rememberLazyListState()
    LaunchedEffect(SerialLink.lines.size) { if (SerialLink.lines.isNotEmpty()) list.scrollToItem(SerialLink.lines.size) }
    val serial = devices.filter { SerialLink.canSerial(it) }

    if (!SerialLink.connected) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Монитор порта, как в Arduino IDE: читай, что пишет плата, и отправляй ей команды. Для MicroPython — интерактивная консоль.", style = Ns.small)
            SectionTitle("скорость (бод)")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BAUDS.forEach { b -> Chip("$b", if (b == baud) Ns.Red else Ns.Dim, Modifier.clickable { onBaud(b) }) }
            }
            SectionTitle("плата")
            if (serial.isEmpty()) Text("нет плат с последовательным портом — подключи через OTG", style = Ns.small.copy(color = Ns.Amber))
            serial.forEach { d ->
                TermButton("ПОДКЛЮЧИТЬ: ${SerialLink.label(d)}", Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Ns.Red) {
                    SerialLink.requestAndConnect(ctx, d, baud)
                }
            }
            SerialLink.status?.let { Text(it, style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(vertical = 6.dp)) }
            if (SerialLink.lines.isNotEmpty()) {
                SectionTitle("прошлый сеанс")
                SerialLink.lines.takeLast(30).forEach { Text(it, style = Ns.small) }
            }
        }
        return
    }
    Text("${SerialLink.deviceName} · $baud бод", style = Ns.small.copy(color = Ns.Green))
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), state = list) {
        itemsIndexed(SerialLink.lines) { _, l -> SelectionContainer { Text(l, style = Ns.text.copy(fontSize = 12.sp)) } }
        item { Text(SerialLink.partial, style = Ns.text.copy(fontSize = 12.sp, color = Ns.Red)) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("DTR", if (SerialLink.dtr) Ns.Red else Ns.Dim, Modifier.clickable { SerialLink.switchDtr(!SerialLink.dtr) })
        Chip("RTS", if (SerialLink.rts) Ns.Red else Ns.Dim, Modifier.clickable { SerialLink.switchRts(!SerialLink.rts) })
        Chip("HEX", if (SerialLink.hex) Ns.Red else Ns.Dim, Modifier.clickable { SerialLink.hex = !SerialLink.hex })
        Chip(when (ending) { "\r\n" -> "CRLF"; "\n" -> "LF"; else -> "БЕЗ ⏎" }, Ns.Green, Modifier.clickable {
            ending = when (ending) { "\r\n" -> "\n"; "\n" -> ""; else -> "\r\n" }
        })
        Chip("СБРОС", Ns.Amber, Modifier.clickable { SerialLink.reset() })
        QUICK.forEach { (t, b) -> Chip(t, Ns.Dim, Modifier.clickable { SerialLink.send(b) }) }
        Chip("ОЧИСТИТЬ", Ns.Dim, Modifier.clickable { SerialLink.clear() })
        Chip("ОТКЛЮЧИТЬ", Ns.Red, Modifier.clickable { SerialLink.close() })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NsField(input, { input = it }, "команда для платы", Modifier.weight(1f), keyboard = KeyboardType.Ascii, onDone = {
            SerialLink.sendLine(input, ending); input = ""
        })
        Text(" ⏎", style = Ns.title, modifier = Modifier.clickable { SerialLink.sendLine(input, ending); input = "" })
    }
    Spacer(Modifier.height(2.dp))
}
