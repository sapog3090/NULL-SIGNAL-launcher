package com.nullsignal.launcher.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.academy.Academy
import com.nullsignal.core.story.Ranks
import com.nullsignal.core.terminal.Module
import com.nullsignal.launcher.AppEntry
import com.nullsignal.launcher.LauncherViewModel
import com.nullsignal.launcher.sys.NetInfo
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BOOT = listOf(
    "загрузка ядра nullsignal",
    "подключение к каналу группы",
    "радары: wifi ▸ ble ▸ lan",
    "оператор опознан",
)

private val WHISPERS = listOf(
    "ОНИ ВИДЯТ ВСЁ",
    "КАЖДОЕ ПРИЛОЖЕНИЕ ЗНАЕТ, ГДЕ ТЫ",
    "ТВОЙ ТЕЛЕФОН ГОВОРИТ О ТЕБЕ ЗА ТВОЕЙ СПИНОЙ",
    "КТО-ТО СЕЙЧАС СМОТРИТ",
    "МЫ ТОЖЕ СМОТРИМ",
    "НИЧТО НЕ УДАЛЯЕТСЯ НАСОВСЕМ",
)

private data class Tile(val module: Module, val icon: String, val title: String, val sub: String, val color: Color)

@Composable
fun HomeScreen(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var battery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf(NetInfo.local(ctx)) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            now = Date()
            if (n++ % 10 == 0) {
                val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                battery = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                net = NetInfo.local(ctx)
            }
            delay(1000)
        }
    }
    var bootLine by remember { mutableIntStateOf(if (vm.bootDone) BOOT.size else 0) }
    LaunchedEffect(Unit) {
        while (bootLine < BOOT.size) { delay(220); bootLine++ }
        delay(600)
        vm.bootDone = true
    }
    var query by remember { mutableStateOf("") }
    val apps = vm.apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    val unread = vm.engine.unread(vm.progress)
    val rank = Ranks.of(vm.progress.followers)

    val dns = com.nullsignal.launcher.sys.DnsMonitor
    val sections = listOf(
        "слежка и защита" to listOf(
            Tile(Module.INTERCEPT, "◉", "ПЕРЕХВАТ", if (dns.running) "АКТИВЕН · ${dns.total} · блок ${dns.blocked}" else "кто куда стучится", Ns.Red),
            Tile(Module.STALKER, "⊘", "АНТИСЛЕЖКА", "метки · шпионы", Ns.Red),
            Tile(Module.XRAY, "☢", "РЕНТГЕН", "трекеры внутри приложений", Ns.Red),
            Tile(Module.TRACES, "∷", "СЛЕДЫ", "кто трогал телефон", Ns.Green),
            Tile(Module.NOTIFLOG, "⚑", "УВЕДОМЛЕНИЯ", "в т.ч. удалённые", Ns.Green),
            Tile(Module.CAMS, "◍", "КАМЕРЫ", "скрытые камеры в сети", Ns.Green),
            Tile(Module.AUDIT, "⛨", "АУДИТ", "защита телефона", Ns.Green),
        ),
        "сеть" to listOf(
            Tile(Module.NETSCAN, "⌖", "СКАН СЕТИ", "кто в моём Wi-Fi", Ns.Green),
            Tile(Module.MONITOR, "∿", "МОНИТОР", "трафик и пинг", Ns.Green),
            Tile(Module.SPEED, "»", "СПИДТЕСТ", "скорость интернета", Ns.Green),
            Tile(Module.TERMINAL, ">_", "ТЕРМИНАЛ", "80+ команд", Ns.Green),
            Tile(Module.SSH, "$", "SSH", "удалённый доступ", Ns.Green),
        ),
        "разведка" to listOf(
            Tile(Module.OSINT, "⌕", "OSINT", "ник · почта · IP · домен", Ns.Red),
            Tile(Module.EXIF, "⌗", "EXIF", "где сделано фото", Ns.Green),
        ),
        "эфир и железо" to listOf(
            Tile(Module.WIFI, "≋", "WIFI-РАДАР", "сети и каналы", Ns.Green),
            Tile(Module.BLE, "◎", "BLE-РАДАР", "bluetooth рядом", Ns.Green),
            Tile(Module.NFC, "⊡", "NFC", "метки и карты", Ns.Green),
            Tile(Module.IR, "⇶", "ИК-ПУЛЬТ", "управление ТВ", Ns.Green),
            Tile(Module.SENSORS, "✱", "ДАТЧИКИ", "магнитометр и всё железо", Ns.Green),
        ),
        "крипто и шпионаж" to listOf(
            Tile(Module.SPY, "⧉", "ШПИОНАЖ", "стего · чернила · морзе", Ns.Green),
            Tile(Module.AUTH, "◷", "2FA", "одноразовые коды", Ns.Green),
            Tile(Module.TOOLS, "⚿", "ИНСТРУМЕНТЫ", "пароли · шифр · QR", Ns.Green),
        ),
        "группа" to listOf(
            Tile(Module.MESSAGES, "✉", "СООБЩЕНИЯ", if (unread > 0) "новых: $unread" else "канал группы", if (unread > 0) Ns.Red else Ns.Green),
            Tile(Module.GAMES, "#", "ТРЕНАЖЁРЫ", "взлом · шифры · код", Ns.Dim),
            Tile(Module.ACADEMY, "λ", "ОБУЧЕНИЕ", "python ${vm.lessonsDone.size}/${Academy.lessons.size}", Ns.Dim),
        ),
    )
    Box(Modifier.fillMaxSize()) {
        if (vm.matrix) MatrixRain(Modifier.fillMaxSize(), alpha = 0.10f)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------- часы ----------
            item {
                Spacer(Modifier.height(10.dp))
                Text(
                    WHISPERS[((now.time / 7000) % WHISPERS.size).toInt()],
                    style = Ns.label.copy(color = Ns.Red.copy(alpha = glowAlpha()), textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(size = 7.dp)
                            Spacer(Modifier.width(6.dp))
                            GlitchText("NULL//SIGNAL", style = Ns.label.copy(color = Ns.Green, fontSize = 11.sp))
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                SimpleDateFormat("HH:mm", Locale.US).format(now),
                                style = Ns.title.copy(fontSize = 58.sp, lineHeight = 62.sp, color = Ns.Green, letterSpacing = 0.sp),
                            )
                            Text(
                                SimpleDateFormat(":ss", Locale.US).format(now),
                                style = Ns.title.copy(fontSize = 22.sp, color = Ns.Red),
                                modifier = Modifier.padding(bottom = 10.dp, start = 2.dp),
                            )
                        }
                        Text(SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now).uppercase(), style = Ns.label.copy(color = Ns.Text2))
                    }
                }
                Row(
                    Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip(
                        "${if (charging) "⚡" else "BAT"} ${if (battery >= 0) "$battery%" else "?"}",
                        when {
                            battery in 0..15 -> Ns.Red
                            battery in 16..35 -> Ns.Amber
                            else -> Ns.Green
                        },
                    )
                    Chip(net?.let { "${if (it.onWifi) "WIFI" else "MOB"} ${it.ip}" } ?: "OFFLINE", if (net != null) Ns.Cyan else Ns.Red)
                    Chip(vm.operator.uppercase(), Ns.Text2)
                    Chip(
                        if (vm.matrix) "МАТРИЦА ВКЛ" else "МАТРИЦА ВЫКЛ",
                        if (vm.matrix) Ns.Green else Ns.Dim,
                        Modifier.clickable { vm.toggleMatrix() },
                    )
                }
            }
            // ---------- загрузка ----------
            if (!vm.bootDone) {
                item {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        BOOT.take(bootLine).forEach { Text("[ OK ] $it", style = Ns.small.copy(color = Ns.Dim)) }
                    }
                }
            }
            // ---------- оператор ----------
            item {
                Panel(color = Ns.Dim) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("РАНГ", style = Ns.label.copy(color = Ns.Text2))
                            Text(rank.rank.title, style = Ns.title.copy(color = Ns.Text2))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("УЗЛОВ СЕТИ", style = Ns.label.copy(color = Ns.Text2))
                            Text("${vm.progress.followers}", style = Ns.title.copy(color = Ns.Text2))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HudBar(rank.progress, Ns.Red)
                    Text(
                        rank.next?.let { "до «${it.title}»: ${it.from - vm.progress.followers}" } ?: "максимальный ранг",
                        style = Ns.small,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // ---------- задача ----------
            item {
                Column(
                    Modifier.fillMaxWidth().hud(Ns.Red, fill = Ns.Red.copy(alpha = 0.07f))
                        .clickable { vm.screen = Module.MESSAGES }.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(Ns.Red, 7.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("ТЕКУЩАЯ ЗАДАЧА", style = Ns.label.copy(color = Ns.Red))
                    }
                    Text(vm.engine.current(vm.progress).objective, style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(top = 4.dp))
                }
            }
            // ---------- модули по разделам ----------
            sections.forEach { (title, tiles) ->
                item(key = "section-$title") { SectionTitle(title) }
                items(tiles.chunked(2), key = { row -> "tiles-" + row.first().module.id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t -> ModuleTile(t, Modifier.weight(1f)) { vm.screen = t.module } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            // ---------- приложения ----------
            item {
                SectionTitle("приложения · ${vm.apps.size}", Ns.Green)
                Row(
                    Modifier.fillMaxWidth().hud(Ns.Dim, shape = Ns.HudSmall).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⌕ ", style = Ns.text.copy(color = Ns.Cyan))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Ns.text,
                        cursorBrush = SolidColor(Ns.Green),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("найти приложение…", style = Ns.text.copy(color = Ns.Faint))
                            inner()
                        },
                    )
                    if (query.isNotEmpty()) Text("✕", style = Ns.text.copy(color = Ns.Dim), modifier = Modifier.clickable { query = "" }.padding(start = 8.dp))
                }
            }
            items(apps.chunked(4), key = { row -> row.first().pkg + "/" + row.first().cls }) { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { app -> AppIcon(app, vm, Modifier.weight(1f)) { vm.launch(app); query = "" } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item {
                Text(
                    "сделать NULL//SIGNAL рабочим столом →",
                    style = Ns.small.copy(color = Ns.Cyan),
                    modifier = Modifier
                        .padding(vertical = 18.dp)
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

@Composable
private fun ModuleTile(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(86.dp).hud(t.color.copy(alpha = 0.6f), fill = Ns.Panel).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(t.icon, style = Ns.title.copy(color = t.color, fontSize = 18.sp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(6.dp).background(if (t.color == Ns.Red) Ns.Red else Ns.Faint))
        }
        Column {
            Text(t.title, style = Ns.title.copy(color = t.color, fontSize = 15.sp, lineHeight = 17.sp), maxLines = 1)
            Text(t.sub, style = Ns.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry, vm: LauncherViewModel, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(Ns.HudSmall).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = vm.icons[app.pkg + "/" + app.cls]
        Box(
            Modifier.size(50.dp).clip(Ns.HudSmall).background(Ns.Panel),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Image(icon, contentDescription = app.label, modifier = Modifier.size(42.dp))
            else Text(app.label.take(1).uppercase(), style = Ns.title)
        }
        Text(
            app.label,
            style = Ns.small.copy(fontSize = 10.sp, color = Ns.Text2, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
