package com.nullsignal.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.hw.Hid
import com.nullsignal.launcher.sys.HidRemote

/** Bluetooth-пульт как у Flipper: слайды, музыка, мышь и клавиатура для своего компьютера. */
@SuppressLint("MissingPermission")
@Composable
fun BtRemoteScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray()
    val (granted, request) = rememberPermissions(perms)
    var tab by remember { mutableIntStateOf(0) }
    ModuleScreen("BT-ПУЛЬТ", onBack) {
        if (Build.VERSION.SDK_INT < 28) {
            Text("Нужен Android 9 или новее.", style = Ns.text.copy(color = Ns.Amber))
            return@ModuleScreen
        }
        if (!granted) {
            PermissionGate("Чтобы стать Bluetooth-пультом, нужен доступ к Bluetooth.", request)
            return@ModuleScreen
        }
        val on = HidRemote.ready
        Column(Modifier.fillMaxWidth().hud(if (HidRemote.connected) Ns.Red else Ns.Dim).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (HidRemote.connected) PulseDot(Ns.Red)
                Text(
                    (if (HidRemote.connected) "  " else "") + (HidRemote.host?.let { "ПОДКЛЮЧЁН: $it" } ?: if (on) "ЖДУ ПОДКЛЮЧЕНИЯ" else "ПУЛЬТ ВЫКЛЮЧЕН"),
                    style = Ns.title.copy(fontSize = 16.sp, color = if (HidRemote.connected) Ns.Red else Ns.Green),
                )
            }
            Text(HidRemote.status, style = Ns.small)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!on) TermButton("ВКЛЮЧИТЬ", Modifier.weight(1f), color = Ns.Red) { HidRemote.start(ctx) }
                else TermButton("ВЫКЛЮЧИТЬ", Modifier.weight(1f), color = Ns.Dim) { HidRemote.stop(ctx) }
                TermButton("ВИДИМОСТЬ", Modifier.weight(1f), enabled = on) {
                    runCatching {
                        ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        if (!HidRemote.connected) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "1. Нажми ВКЛЮЧИТЬ, потом ВИДИМОСТЬ.\n2. На компьютере: Bluetooth → Добавить устройство → выбери этот телефон.\n" +
                        "3. Если уже сопряжены — просто нажми на компьютер в списке ниже.",
                    style = Ns.small, modifier = Modifier.padding(top = 8.dp),
                )
                if (on) {
                    SectionTitle("сопряжённые устройства")
                    HidRemote.bonded(ctx).forEach { d ->
                        Text(
                            runCatching { d.name }.getOrNull() ?: d.address, style = Ns.text,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).hud(Ns.Faint).clickable { HidRemote.connect(d) }.padding(10.dp),
                        )
                    }
                }
                Text(
                    "Работает с Windows, macOS, Linux, Android-ТВ и планшетами. На некоторых телефонах производитель отключает этот режим.",
                    style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            return@ModuleScreen
        }
        NsTabs(listOf("СЛАЙДЫ", "МЕДИА", "МЫШЬ", "КЛАВА"), tab, Ns.Red) { tab = it }
        when (tab) {
            0 -> Slides()
            1 -> Media()
            2 -> Touchpad()
            else -> Keyboard()
        }
    }
}

@Composable
private fun BigKey(label: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = Ns.Green, onClick: () -> Unit) {
    TermButton(label, modifier.height(72.dp), color = color, onClick = onClick)
}

@Composable
private fun ColumnScope.Slides() {
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigKey("◀ НАЗАД", Modifier.weight(1f)) { HidRemote.key(Hid.LEFT) }
            BigKey("ВПЕРЁД ▶", Modifier.weight(1f), Ns.Red) { HidRemote.key(Hid.RIGHT) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton("НАЧАТЬ (F5)", Modifier.weight(1f)) { HidRemote.key(Hid.F5) }
            TermButton("ВЫЙТИ (ESC)", Modifier.weight(1f), color = Ns.Dim) { HidRemote.key(Hid.ESC) }
        }
        TermButton("ЧЁРНЫЙ ЭКРАН (B)", Modifier.fillMaxWidth(), color = Ns.Dim) { HidRemote.key(0x05) }
        Text("Работает в PowerPoint, Google Slides, Keynote и любом PDF на весь экран.", style = Ns.small)
    }
}

@Composable
private fun ColumnScope.Media() {
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigKey("⏮", Modifier.weight(1f)) { HidRemote.media(Hid.PREV) }
            BigKey("⏯", Modifier.weight(1f), Ns.Red) { HidRemote.media(Hid.PLAY_PAUSE) }
            BigKey("⏭", Modifier.weight(1f)) { HidRemote.media(Hid.NEXT) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigKey("ГРОМ −", Modifier.weight(1f)) { HidRemote.media(Hid.VOL_DOWN) }
            BigKey("ТИШЕ ✕", Modifier.weight(1f), Ns.Dim) { HidRemote.media(Hid.MUTE) }
            BigKey("ГРОМ +", Modifier.weight(1f)) { HidRemote.media(Hid.VOL_UP) }
        }
    }
}

@Composable
private fun ColumnScope.Touchpad() {
    var restX by remember { mutableStateOf(0f) }
    var restY by remember { mutableStateOf(0f) }
    var restW by remember { mutableStateOf(0f) }
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().hud(Ns.Dim)
                .pointerInput(Unit) { detectTapGestures(onTap = { HidRemote.click(1) }, onLongPress = { HidRemote.click(2) }) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        // дробные остатки копим, чтобы медленное движение не терялось
                        restX += drag.x * 0.7f
                        restY += drag.y * 0.7f
                        val dx = restX.toInt()
                        val dy = restY.toInt()
                        restX -= dx
                        restY -= dy
                        if (dx != 0 || dy != 0) HidRemote.mouse(dx, dy)
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("веди пальцем · тап — клик · долгое нажатие — правый клик", style = Ns.small) }
        Box(
            Modifier.width(44.dp).fillMaxHeight().hud(Ns.Faint)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        restW -= drag.y / 25f
                        val w = restW.toInt()
                        restW -= w
                        if (w != 0) HidRemote.mouse(0, 0, 0, w)
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("⇕", style = Ns.title) }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TermButton("ЛЕВАЯ", Modifier.weight(1f)) { HidRemote.click(1) }
        TermButton("ПРАВАЯ", Modifier.weight(1f), color = Ns.Dim) { HidRemote.click(2) }
    }
}

@Composable
private fun ColumnScope.Keyboard() {
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        NsField(text, { text = it.take(500) }, "текст для компьютера", singleLine = false, onDone = null)
        TermButton("НАПЕЧАТАТЬ", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Ns.Red, enabled = text.isNotEmpty()) {
            val skipped = HidRemote.type(text)
            note = if (skipped > 0) "пропущено символов: $skipped — печатается только английская раскладка" else null
            text = ""
        }
        note?.let { Text(it, style = Ns.small.copy(color = Ns.Amber)) }
        listOf(
            listOf("ESC" to Hid.ESC, "TAB" to Hid.TAB, "⌫" to Hid.BACKSPACE, "ENTER" to Hid.ENTER),
            listOf("←" to Hid.LEFT, "↑" to Hid.UP, "↓" to Hid.DOWN, "→" to Hid.RIGHT),
            listOf("PG↑" to Hid.PAGE_UP, "PG↓" to Hid.PAGE_DOWN, "ПРОБЕЛ" to Hid.SPACE),
        ).forEach { row ->
            Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (l, k) -> TermButton(l, Modifier.weight(1f)) { HidRemote.key(k) } }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
