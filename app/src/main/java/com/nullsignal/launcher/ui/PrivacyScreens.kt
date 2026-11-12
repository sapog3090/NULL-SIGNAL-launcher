package com.nullsignal.launcher.ui

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.nullsignal.core.apps.DexScan
import com.nullsignal.core.apps.Traces
import com.nullsignal.launcher.sys.NotifStore
import com.nullsignal.launcher.sys.Xray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Перепроверка разрешений, когда пользователь вернулся из настроек. */
@Composable
fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) block() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

private fun Context.openAppSettings(pkg: String) = runCatching {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ======================= РЕНТГЕН =======================

@Composable
fun XrayScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSystem by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Xray.AppScan?>(null) }
    var openTracker by remember { mutableStateOf<DexScan.Tracker?>(null) }

    val app = open
    if (app != null) {
        BackHandler { open = null }
        ModuleScreen(app.label, { open = null }) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Panel(color = if (app.trackers.isEmpty()) Ns.Dim else Ns.Red) {
                    Text(
                        if (app.trackers.isEmpty()) "ТРЕКЕРОВ НЕ НАЙДЕНО" else "ТРЕКЕРОВ ВНУТРИ: ${app.trackers.size}",
                        style = Ns.title.copy(color = if (app.trackers.isEmpty()) Ns.Green else Ns.Red, fontSize = 17.sp),
                    )
                    KeyValue("пакет", app.pkg)
                    KeyValue("размер", "%.1f МБ · DEX-файлов ${app.dexFiles}".format(app.sizeMb))
                    app.error?.let { KeyValue("ошибка", it, Ns.Amber) }
                }
                app.trackers.groupBy { it.kind }.forEach { (kind, list) ->
                    SectionTitle(kind.title, if (kind == DexScan.Kind.CRASH) Ns.Dim else Ns.Red)
                    Text(Xray.explain(kind), style = Ns.small.copy(color = Ns.Dim))
                    list.forEach { t -> Text("■ ${t.name}", style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(vertical = 2.dp)) }
                }
                Spacer(Modifier.height(12.dp))
                TermButton("НАСТРОЙКИ ПРИЛОЖЕНИЯ / УДАЛИТЬ", Modifier.fillMaxWidth()) { ctx.openAppSettings(app.pkg) }
                Text(
                    "Трекер внутри не значит, что он точно включён, — но код слежки в приложении есть. " +
                        "Включи ПЕРЕХВАТ: он заблокирует их сетевые запросы.",
                    style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        return
    }

    ModuleScreen("РЕНТГЕН", onBack) {
        Text(
            "Ищет код слежки, вшитый в приложения: рекламные SDK, аналитику, сборщиков геолокации, запись экрана. " +
                "Читает сами APK-файлы — приложения не запускаются.",
            style = Ns.small,
        )
        Text(
            "${if (includeSystem) "[■]" else "[ ]"} включая системные",
            style = Ns.small.copy(color = if (includeSystem) Ns.Green else Ns.Dim),
            modifier = Modifier.clickable { includeSystem = !includeSystem }.padding(vertical = 6.dp),
        )
        TermButton(if (Xray.busy) "ПРОСВЕЧИВАЮ…" else "ПРОСВЕТИТЬ ВСЕ ПРИЛОЖЕНИЯ", Modifier.fillMaxWidth(), color = Ns.Red, enabled = !Xray.busy) {
            scope.launch { Xray.scanAll(ctx, includeSystem) }
        }
        if (Xray.busy) {
            HudBar(Xray.progress, Ns.Red, Modifier.padding(top = 6.dp))
            Text("${Xray.results.size}/${Xray.total} · ${Xray.current}", style = Ns.small, maxLines = 1)
        }
        val all = Xray.results.values.toList()
        if (all.isNotEmpty()) {
            val withT = all.count { it.trackers.isNotEmpty() }
            val counts = all.flatMap { it.trackers }.groupingBy { it }.eachCount()
            Panel(Modifier.padding(top = 8.dp), color = Ns.Red) {
                Text("ПРОСВЕЧЕНО ${all.size} · С ТРЕКЕРАМИ $withT (${withT * 100 / all.size}%)", style = Ns.label.copy(color = Ns.Red))
                Text(
                    "всего найдено ${counts.size} разных трекеров · больше всех: ${all.maxByOrNull { it.trackers.size }?.let { "${it.label} (${it.trackers.size})" }}",
                    style = Ns.small,
                )
                val loc = all.filter { a -> a.trackers.any { it.kind == DexScan.Kind.LOCATION } }
                if (loc.isNotEmpty()) Text("⚠ продают геолокацию: ${loc.joinToString { it.label }}", style = Ns.small.copy(color = Ns.Amber))
            }
            Spacer(Modifier.height(6.dp))
            NsTabs(listOf("ПРИЛОЖЕНИЯ", "ТРЕКЕРЫ"), tab, Ns.Red) { tab = it; openTracker = null }
            if (tab == 0) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(all.sortedWith(compareByDescending<Xray.AppScan> { it.trackers.size }.thenBy { it.label }), key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().hud(if (a.trackers.size >= 5) Ns.Red else Ns.Faint).clickable { open = a }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = Ns.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    a.trackers.take(4).joinToString { it.name } + if (a.trackers.size > 4) " …" else "",
                                    style = Ns.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (a.trackers.isEmpty()) "✓" else "${a.trackers.size}",
                                style = Ns.title.copy(color = if (a.trackers.isEmpty()) Ns.Green else Ns.Red, fontSize = 18.sp),
                            )
                        }
                    }
                }
            } else {
                val t = openTracker
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (t != null) {
                        item {
                            Text("← ${t.name}", style = Ns.title.copy(fontSize = 16.sp), modifier = Modifier.clickable { openTracker = null }.padding(vertical = 4.dp))
                            Text("${t.kind.title}: ${Xray.explain(t.kind)}", style = Ns.small)
                        }
                        items(all.filter { t in it.trackers }.sortedBy { it.label }, key = { "a" + it.pkg }) { a ->
                            Text("· ${a.label}", style = Ns.text, modifier = Modifier.fillMaxWidth().clickable { open = a }.padding(vertical = 4.dp))
                        }
                    } else {
                        items(counts.entries.sortedByDescending { it.value }, key = { it.key.name }) { (tr, n) ->
                            Row(Modifier.fillMaxWidth().clickable { openTracker = tr }.padding(vertical = 5.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(tr.name, style = Ns.text.copy(color = if (tr.kind == DexScan.Kind.CRASH) Ns.Green else Ns.Red))
                                    Text(tr.kind.title, style = Ns.small)
                                }
                                Text("в $n прил.", style = Ns.small)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================= СЛЕДЫ =======================

private fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private data class TraceData(val sessions: List<Traces.Session>, val time: Map<String, Long>, val labels: Map<String, String>)

private fun loadTraces(ctx: Context, start: Long): TraceData {
    val usm = ctx.getSystemService(UsageStatsManager::class.java)
    val now = System.currentTimeMillis()
    val ev = usm.queryEvents(start, now)
    val e = UsageEvents.Event()
    val out = ArrayList<Traces.Event>()
    val skip = setOf(ctx.packageName, "com.android.systemui")
    while (ev.hasNextEvent()) {
        ev.getNextEvent(e)
        val t = when (e.eventType) {
            1 -> Traces.Type.APP_OPEN     // ACTIVITY_RESUMED
            2 -> Traces.Type.APP_CLOSE    // ACTIVITY_PAUSED
            15 -> Traces.Type.SCREEN_ON   // SCREEN_INTERACTIVE
            16 -> Traces.Type.SCREEN_OFF  // SCREEN_NON_INTERACTIVE
            17 -> Traces.Type.LOCK        // KEYGUARD_SHOWN
            18 -> Traces.Type.UNLOCK      // KEYGUARD_HIDDEN
            26 -> Traces.Type.SHUTDOWN    // DEVICE_SHUTDOWN
            27 -> Traces.Type.BOOT        // DEVICE_STARTUP
            else -> null
        } ?: continue
        val pkg = e.packageName
        if ((t == Traces.Type.APP_OPEN || t == Traces.Type.APP_CLOSE) && (pkg == null || pkg in skip)) continue
        out += Traces.Event(e.timeStamp, t, pkg)
    }
    val pm = ctx.packageManager
    val labels = out.mapNotNull { it.pkg }.distinct().associateWith { p ->
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    }
    return TraceData(Traces.sessions(out, now), Traces.foregroundTime(out, now), labels)
}

@Composable
fun TracesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasUsageAccess(ctx)) }
    OnResume { granted = hasUsageAccess(ctx) }
    var range by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<TraceData?>(null) }
    LaunchedEffect(granted, range) {
        if (!granted) return@LaunchedEffect
        val cal = Calendar.getInstance()
        val start = when (range) {
            0 -> cal.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            1 -> System.currentTimeMillis() - 86_400_000L
            else -> System.currentTimeMillis() - 7 * 86_400_000L
        }
        data = null
        data = withContext(Dispatchers.IO) { runCatching { loadTraces(ctx, start) }.getOrNull() }
    }
    ModuleScreen("СЛЕДЫ", onBack) {
        if (!granted) {
            Text(
                "Покажу, когда телефон разблокировали и какие приложения открывали — даже если кто-то брал его без тебя. " +
                    "Нужен доступ к истории использования: найди NULL//SIGNAL в списке и включи.",
                style = Ns.small,
            )
            Spacer(Modifier.height(10.dp))
            TermButton("ВЫДАТЬ ДОСТУП", Modifier.fillMaxWidth(), color = Ns.Red) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return@ModuleScreen
        }
        NsTabs(listOf("СЕГОДНЯ", "24 ЧАСА", "7 ДНЕЙ"), range) { range = it }
        val d = data
        if (d == null) {
            Text("читаю системный журнал…", style = Ns.small.copy(color = Ns.Amber))
            return@ModuleScreen
        }
        val hourFmt = remember { Calendar.getInstance() }
        fun hourOf(t: Long): Int { hourFmt.timeInMillis = t; return hourFmt.get(Calendar.HOUR_OF_DAY) }
        val unlocks = d.sessions.count { it.unlocked }
        val night = d.sessions.count { it.unlocked && Traces.atNight(it, ::hourOf) }
        val screenTime = d.sessions.sumOf { it.durationMs }
        val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale("ru")) }
        val tfmt = remember { SimpleDateFormat("HH:mm", Locale("ru")) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Panel(color = if (night > 0) Ns.Red else Ns.Dim) {
                    Text("РАЗБЛОКИРОВОК: $unlocks · ВКЛЮЧЕНИЙ ЭКРАНА: ${d.sessions.size}", style = Ns.label.copy(color = Ns.Green))
                    Text("экран горел: ${Traces.human(screenTime)}", style = Ns.small)
                    if (night > 0) Text("⚠ ночных разблокировок (0:00–6:00): $night", style = Ns.small.copy(color = Ns.Red))
                }
            }
            val top = d.time.entries.sortedByDescending { it.value }.take(8)
            if (top.isNotEmpty()) {
                item { SectionTitle("больше всего времени") }
                val max = top.first().value.toFloat()
                items(top, key = { "top" + it.key }) { (pkg, ms) ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Row { Text(d.labels[pkg] ?: pkg, style = Ns.small.copy(color = Ns.Green), modifier = Modifier.weight(1f)); Text(Traces.human(ms), style = Ns.small) }
                        HudBar(ms / max, Ns.Dim, height = 3.dp)
                    }
                }
            }
            item { SectionTitle("сеансы · свежие сверху") }
            items(d.sessions) { s ->
                val atNight = Traces.atNight(s, ::hourOf)
                Column(Modifier.fillMaxWidth().hud(if (atNight && s.unlocked) Ns.Red else Ns.Faint).padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt.format(Date(s.start))}–${tfmt.format(Date(s.end))}", style = Ns.text, modifier = Modifier.weight(1f))
                        Text(Traces.human(s.durationMs), style = Ns.small)
                    }
                    Text(
                        listOfNotNull(
                            if (s.boot) "ВКЛЮЧЕНИЕ ТЕЛЕФОНА" else null,
                            if (s.unlocked) "разблокирован" else "только экран",
                            if (atNight) "НОЧЬЮ" else null,
                        ).joinToString(" · "),
                        style = Ns.small.copy(color = if (atNight && s.unlocked) Ns.Red else Ns.Dim),
                    )
                    if (s.apps.isNotEmpty()) Text(s.apps.joinToString(" → ") { d.labels[it] ?: it }, style = Ns.small.copy(color = Ns.Green))
                }
            }
        }
    }
}

// ======================= УВЕДОМЛЕНИЯ =======================

@Composable
fun NotifLogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(NotifStore.enabled(ctx)) }
    var entries by remember { mutableStateOf<List<NotifStore.Entry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf<String?>(null) }
    var openEntry by remember { mutableStateOf<NotifStore.Entry?>(null) }
    fun reload() = scope.launch { entries = withContext(Dispatchers.IO) { NotifStore.load(ctx) } }
    OnResume { enabled = NotifStore.enabled(ctx); reload() }

    val e = openEntry
    if (e != null) {
        BackHandler { openEntry = null }
        ModuleScreen(e.app, { openEntry = null }) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru")).format(Date(e.time)), style = Ns.small)
                if (e.removedByApp) Text("уведомление убрано приложением: сообщение удалили или прочитали на другом устройстве", style = Ns.small.copy(color = Ns.Red))
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Column {
                        Text(e.title, style = Ns.title.copy(fontSize = 16.sp, letterSpacing = 0.sp))
                        Spacer(Modifier.height(6.dp))
                        Text(e.text, style = Ns.text)
                    }
                }
            }
        }
        return
    }

    ModuleScreen("УВЕДОМЛЕНИЯ", onBack) {
        if (!enabled) {
            Text(
                "Журнал сохраняет каждое уведомление — сообщения из мессенджеров, коды, письма. Если отправитель удалит сообщение, " +
                    "у тебя останется копия. Хранится только на телефоне.\n\nНужен доступ к уведомлениям: найди NULL//SIGNAL и включи.",
                style = Ns.small,
            )
            Spacer(Modifier.height(10.dp))
            TermButton("ВЫДАТЬ ДОСТУП", Modifier.fillMaxWidth(), color = Ns.Red) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (entries.isEmpty()) return@ModuleScreen
            Spacer(Modifier.height(10.dp))
        }
        NsField(query, { query = it }, "поиск по тексту…")
        val apps = entries.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }
        Row(Modifier.padding(vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("ВСЕ ${entries.size}", if (appFilter == null) Ns.Red else Ns.Dim, Modifier.clickable { appFilter = null })
            apps.forEach { (a, n) -> Chip("$a $n", if (appFilter == a) Ns.Red else Ns.Dim, Modifier.clickable { appFilter = a }) }
        }
        val shown = entries.filter { (appFilter == null || it.app == appFilter) && (query.isBlank() || it.text.contains(query, true) || it.title.contains(query, true)) }
        val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale("ru")) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown.isEmpty()) item { Text(if (entries.isEmpty()) "пока пусто — журнал пополняется с момента, как выдан доступ" else "ничего не найдено", style = Ns.small) }
            items(shown.take(800)) { n ->
                Column(Modifier.fillMaxWidth().hud(if (n.removedByApp) Ns.Red else Ns.Faint).clickable { openEntry = n }.padding(8.dp)) {
                    Row {
                        Text(n.app, style = Ns.label.copy(color = if (n.removedByApp) Ns.Red else Ns.Dim), modifier = Modifier.weight(1f))
                        Text(fmt.format(Date(n.time)), style = Ns.small.copy(fontSize = 10.sp))
                    }
                    if (n.title.isNotBlank()) Text(n.title, style = Ns.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(n.text, style = Ns.small.copy(color = Ns.Green), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (n.removedByApp) Text("✕ убрано приложением", style = Ns.small.copy(color = Ns.Red, fontSize = 10.sp))
                }
            }
            if (entries.isNotEmpty()) item {
                Text(
                    "[очистить журнал]",
                    style = Ns.small.copy(color = Ns.Dim),
                    modifier = Modifier.clickable { scope.launch { withContext(Dispatchers.IO) { NotifStore.clear(ctx) }; reload() } }.padding(vertical = 12.dp),
                )
            }
        }
    }
}
