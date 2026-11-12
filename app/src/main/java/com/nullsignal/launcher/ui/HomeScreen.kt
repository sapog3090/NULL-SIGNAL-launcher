package com.nullsignal.launcher.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nullsignal.core.terminal.Module
import com.nullsignal.launcher.LauncherViewModel
import com.nullsignal.launcher.sys.NetInfo
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BOOT = listOf(
    "[ OK ] загрузка ядра nullsignal…",
    "[ OK ] маскировка MAC-адреса… (шутка, это делает Android)",
    "[ OK ] подключение к каналу группы",
    "[ OK ] радары: wifi ▸ ble ▸ lan",
    "[ OK ] оператор опознан",
)

@Composable
fun HomeScreen(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var battery by remember { mutableIntStateOf(-1) }
    var ip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            if (now.time / 1000 % 10 == 0L || battery < 0) {
                val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                battery = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                ip = NetInfo.local(ctx)?.ip
            }
            delay(1000)
        }
    }
    var bootLine by remember { mutableIntStateOf(if (vm.bootDone) BOOT.size else 0) }
    LaunchedEffect(Unit) {
        while (bootLine < BOOT.size) { delay(260); bootLine++ }
        vm.bootDone = true
    }
    var query by remember { mutableStateOf("") }
    val apps = vm.apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    val unread = vm.engine.unread(vm.progress)

    Box(Modifier.fillMaxSize()) {
    if (vm.matrix) MatrixRain(Modifier.fillMaxSize(), alpha = 0.22f)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlitchText("NULL//SIGNAL", modifier = Modifier.weight(1f))
                Text(SimpleDateFormat("HH:mm:ss", Locale.US).format(now), style = Ns.title.copy(color = Ns.Cyan))
            }
            Text(
                SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now) +
                    "  ·  бат ${if (battery >= 0) "$battery%" else "?"}  ·  ${ip ?: "offline"}",
                style = Ns.small,
            )
            Text(
                if (vm.matrix) "[матрица: вкл]" else "[матрица: выкл]",
                style = Ns.small.copy(color = Ns.Faint),
                modifier = Modifier.clickable { vm.toggleMatrix() }.padding(vertical = 2.dp),
            )
        }
        item {
            Column(Modifier.padding(vertical = 6.dp)) {
                BOOT.take(bootLine).forEach { Text(it, style = Ns.small) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().border(1.dp, Ns.Faint).padding(10.dp)) {
                Text("оператор: ${vm.operator}   узлов сети: ${vm.progress.followers}", style = Ns.text)
                Text(
                    "[ЗАДАЧА] ${vm.engine.current(vm.progress).objective}",
                    style = Ns.text.copy(color = Ns.Amber),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            val modules = listOf(
                Module.TERMINAL to ">_ терминал",
                Module.MESSAGES to (if (unread > 0) "✉ сообщения ($unread)" else "✉ сообщения"),
                Module.NETSCAN to "⌖ скан сети",
                Module.MONITOR to "∿ монитор",
                Module.WIFI to "≋ wifi-радар",
                Module.BLE to "◎ ble-радар",
                Module.AUDIT to "⛨ аудит",
                Module.TOOLS to "⚿ инструменты",
                Module.GAMES to "# игры",
                Module.ACADEMY to "λ обучение ${vm.lessonsDone.size}/${com.nullsignal.core.academy.Academy.lessons.size}",
            )
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modules.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (m, label) ->
                            TermButton(
                                label,
                                modifier = Modifier.weight(1f),
                                color = if (m == Module.MESSAGES && unread > 0) Ns.Amber else Ns.Green,
                            ) { vm.screen = m }
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().border(1.dp, Ns.Dim).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(">_ ", style = Ns.text)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = Ns.text,
                    cursorBrush = SolidColor(Ns.Green),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("поиск приложений (${vm.apps.size})", style = Ns.text.copy(color = Ns.Faint))
                        inner()
                    },
                )
            }
        }
        items(apps, key = { it.pkg + "/" + it.cls }) { app ->
            Text(
                "  ▸ ${app.label}",
                style = Ns.text,
                modifier = Modifier.fillMaxWidth().clickable { vm.launch(app); query = "" }.padding(vertical = 7.dp),
            )
        }
        item {
            Text(
                "сделать NULL//SIGNAL рабочим столом →",
                style = Ns.small.copy(color = Ns.Cyan),
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .clickable {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
            )
        }
    }
}
}
