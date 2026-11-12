package com.nullsignal.launcher.ui

import android.content.Context
import android.hardware.ConsumerIrManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.hw.Ir
import com.nullsignal.core.hw.Ir.Button
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IrScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("ir", 0) }
    val ir = remember { ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager }
    val has = remember { runCatching { ir?.hasIrEmitter() == true }.getOrDefault(false) }
    // 0..TV.size-1 — телевизоры, TV.size — RGB-лента
    var device by remember { mutableIntStateOf(prefs.getInt("device", 0).coerceIn(0, Ir.TV.size)) }
    var status by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf(false) }

    fun send(name: String, s: Ir.Signal) {
        val m = ir ?: return
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { m.transmit(s.carrierHz, s.pattern); "→ $name" }.getOrElse { "ошибка: ${it.message}" }
            }
        }
    }

    fun choose(i: Int) { device = i; prefs.edit().putInt("device", i).apply() }

    ModuleScreen("ИК-ПУЛЬТ", onBack) {
        if (!has) {
            Text(
                "В этом телефоне нет ИК-порта. Он есть у многих Xiaomi / Redmi / POCO и некоторых Huawei / Honor — маленькое тёмное окошко на верхнем торце.",
                style = Ns.text.copy(color = Ns.Amber),
            )
            return@ModuleScreen
        }
        if (search) {
            PowerSearch(onSend = { name, s -> send(name, s) }, onFound = { i -> choose(i); search = false }, onCancel = { search = false })
            return@ModuleScreen
        }
        // выбор устройства — горизонтальная лента
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ir.TV.forEachIndexed { i, b ->
                Chip(b.name, if (i == device) Ns.Red else Ns.Dim, Modifier.clickable { choose(i) })
            }
            Chip("RGB-ЛЕНТА", if (device == Ir.TV.size) Ns.Red else Ns.Dim, Modifier.clickable { choose(Ir.TV.size) })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (device == Ir.TV.size) {
                LedRemote { k -> send("лента: ${k.title.ifEmpty { "цвет" }}", Ir.led(k)) }
            } else {
                val b = Ir.TV[device]
                Text("${b.name} · протокол ${b.protocol} · направь верх телефона на ТВ", style = Ns.small)
                Spacer(Modifier.height(8.dp))
                TvRemote(b) { btn, s -> send("${b.name} ${btn.title}", s) }
            }
            status?.let { Text(it, style = Ns.small.copy(color = Ns.Green), modifier = Modifier.padding(vertical = 8.dp)) }
            SectionTitle("не знаешь, какой пульт подходит?")
            Text("Мастер по очереди пошлёт коды питания разных марок — отметь, когда твой телевизор отреагирует.", style = Ns.small)
            TermButton("ПОДОБРАТЬ ПУЛЬТ К СВОЕМУ ТВ", Modifier.fillMaxWidth().padding(vertical = 8.dp)) { search = true }
        }
    }
}

/** Мастер подбора: шлём код питания одной марки и спрашиваем, сработало ли. */
@Composable
private fun ColumnScope.PowerSearch(onSend: (String, Ir.Signal) -> Unit, onFound: (Int) -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var sent by remember { mutableStateOf(false) }
    val list = Ir.POWER_SEARCH
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (step >= list.size) {
            Panel(color = Ns.Amber) {
                Text("НИ ОДИН КОД НЕ ПОДОШЁЛ", style = Ns.title.copy(fontSize = 16.sp, color = Ns.Amber))
                Text("Проверь, что ИК-окошко смотрит прямо на ТВ с 1–3 метров. Возможно, твоей марки пока нет в базе.", style = Ns.small)
            }
            TermButton("ЕЩЁ РАЗ", Modifier.fillMaxWidth().padding(top = 8.dp)) { step = 0; sent = false }
            TermButton("НАЗАД К ПУЛЬТУ", Modifier.fillMaxWidth().padding(top = 6.dp), color = Ns.Dim, onClick = onCancel)
            return@Column
        }
        val (name, signal) = list[step]
        Text("ПОДБОР ПУЛЬТА · ${step + 1} ИЗ ${list.size}", style = Ns.label.copy(color = Ns.Red))
        Text(name, style = Ns.title.copy(fontSize = 28.sp, lineHeight = 32.sp))
        Text("Включи телевизор, направь на него верх телефона и нажми «ПОСЛАТЬ».", style = Ns.small)
        Spacer(Modifier.height(10.dp))
        TermButton("ПОСЛАТЬ КОД ПИТАНИЯ", Modifier.fillMaxWidth(), color = Ns.Red) { onSend("питание $name", signal); sent = true }
        if (sent) {
            Text("Телевизор выключился или отреагировал?", style = Ns.text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermButton("ДА — ЭТО МОЙ", Modifier.weight(1f), color = Ns.Red) {
                    onFound(Ir.TV.indexOfFirst { it.name == name }.coerceAtLeast(0))
                }
                TermButton("НЕТ, ДАЛЬШЕ", Modifier.weight(1f)) { step++; sent = false }
            }
        } else {
            TermButton("ПРОПУСТИТЬ", Modifier.fillMaxWidth().padding(top = 6.dp), color = Ns.Dim) { step++ }
        }
        TermButton("ОТМЕНА", Modifier.fillMaxWidth().padding(top = 16.dp), color = Ns.Dim, onClick = onCancel)
    }
}

@Composable
private fun RowScope.Key(label: String, code: Ir.Signal?, color: Color = Ns.Green, weight: Float = 1f, onPress: (Ir.Signal) -> Unit) {
    if (code == null) {
        Spacer(Modifier.weight(weight))
        return
    }
    TermButton(label, Modifier.weight(weight), color = color) { onPress(code) }
}

/** Раскладка пульта: питание, крестовина, громкость/каналы, цифры. Показываются только известные кнопки. */
@Composable
private fun TvRemote(b: Ir.Brand, send: (Button, Ir.Signal) -> Unit) {
    val c = b.codes
    fun k(btn: Button) = c[btn]
    val gap = Arrangement.spacedBy(6.dp)
    Column(verticalArrangement = gap) {
        Row(horizontalArrangement = gap) {
            Key("⏻ ВКЛ/ВЫКЛ", k(Button.POWER), Ns.Red, 2f) { send(Button.POWER, it) }
            Key(Button.INPUT.title, k(Button.INPUT)) { send(Button.INPUT, it) }
        }
        Row(horizontalArrangement = gap) {
            Key(Button.MUTE.title, k(Button.MUTE)) { send(Button.MUTE, it) }
            Key(Button.MENU.title, k(Button.MENU)) { send(Button.MENU, it) }
            Key(Button.HOME.title, k(Button.HOME)) { send(Button.HOME, it) }
        }
        if (k(Button.OK) != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = gap) {
                Spacer(Modifier.weight(1f)); Key("▲", k(Button.UP)) { send(Button.UP, it) }; Spacer(Modifier.weight(1f))
            }
            Row(horizontalArrangement = gap) {
                Key("◀", k(Button.LEFT)) { send(Button.LEFT, it) }
                Key("OK", k(Button.OK), Ns.Red) { send(Button.OK, it) }
                Key("▶", k(Button.RIGHT)) { send(Button.RIGHT, it) }
            }
            Row(horizontalArrangement = gap) {
                Key(Button.BACK.title, k(Button.BACK), Ns.Dim) { send(Button.BACK, it) }
                Key("▼", k(Button.DOWN)) { send(Button.DOWN, it) }
                Key(Button.EXIT.title, k(Button.EXIT), Ns.Dim) { send(Button.EXIT, it) }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = gap) {
            Column(Modifier.weight(1f), verticalArrangement = gap) {
                Row { Key("ГРОМ +", k(Button.VOL_UP)) { send(Button.VOL_UP, it) } }
                Row { Key("ГРОМ −", k(Button.VOL_DOWN)) { send(Button.VOL_DOWN, it) } }
            }
            Column(Modifier.weight(1f), verticalArrangement = gap) {
                Row { Key("КАН +", k(Button.CH_UP)) { send(Button.CH_UP, it) } }
                Row { Key("КАН −", k(Button.CH_DOWN)) { send(Button.CH_DOWN, it) } }
            }
        }
        if (Ir.DIGITS.any { k(it) != null }) {
            Spacer(Modifier.height(4.dp))
            Ir.DIGITS.dropLast(1).chunked(3).forEach { row ->
                Row(horizontalArrangement = gap) { row.forEach { d -> Key(d.title, k(d)) { send(d, it) } } }
            }
            Row(horizontalArrangement = gap) {
                Spacer(Modifier.weight(1f)); Key("0", k(Button.D0)) { send(Button.D0, it) }; Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Пульт RGB-ленты на 24 кнопки: 4 ряда управления и цветов. */
@Composable
private fun LedRemote(send: (Ir.LedKey) -> Unit) {
    Text("Подходит к большинству китайских RGB-лент с пультом на 24 кнопки.", style = Ns.small)
    Spacer(Modifier.height(8.dp))
    Ir.LedKey.entries.chunked(4).forEach { row ->
        Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { key ->
                val rgb = key.rgb
                if (rgb != null) {
                    Box(
                        Modifier.weight(1f).aspectRatio(2.2f).background(Color(rgb)).border(1.dp, Ns.Faint).clickable { send(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.title.isNotEmpty()) Text(key.title, style = Ns.text.copy(color = Ns.Bg, textAlign = TextAlign.Center))
                    }
                } else {
                    TermButton(key.title, Modifier.weight(1f), color = if (key == Ir.LedKey.OFF || key == Ir.LedKey.ON) Ns.Red else Ns.Green) { send(key) }
                }
            }
        }
    }
}
