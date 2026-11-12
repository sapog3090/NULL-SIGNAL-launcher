package com.nullsignal.launcher.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.nullsignal.core.dns.BlocklistSources
import com.nullsignal.core.dns.Trackers
import com.nullsignal.core.dns.Upstreams
import com.nullsignal.launcher.sys.AppDns
import com.nullsignal.launcher.sys.DnsMonitor
import com.nullsignal.launcher.sys.DnsRules
import com.nullsignal.launcher.sys.DnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InterceptScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var trackersOn by remember { mutableStateOf(DnsMonitor.blockTrackers(ctx)) }
    var blockedUids by remember { mutableStateOf(DnsMonitor.blockedUids(ctx)) }
    var openApp by remember { mutableStateOf<AppDns?>(null) }
    var domainMenu by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { DnsRules.rebuild(ctx) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) DnsVpnService.start(ctx)
        else DnsMonitor.error = "разрешение на VPN не выдано"
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val data = DnsMonitor.csv()
            withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) } } }
        }
    }

    val menu = domainMenu
    if (menu != null) {
        BackHandler { domainMenu = null }
        DomainActions(menu) { domainMenu = null }
        return
    }

    ModuleScreen("ПЕРЕХВАТ", onBack) {
        val on = DnsMonitor.running
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (on) "ПЕРЕХВАТ АКТИВЕН" else "ПЕРЕХВАТ ВЫКЛЮЧЕН", style = Ns.title.copy(color = if (on) Ns.Red else Ns.Dim, fontSize = 17.sp))
                Text(
                    "запросов ${DnsMonitor.total} · заблокировано ${DnsMonitor.blocked} · приложений ${DnsMonitor.apps.size}",
                    style = Ns.small,
                )
                Text(
                    "DNS: ${DnsRules.upstream(ctx).title}${if (DnsRules.useDoh(ctx) && DnsRules.upstream(ctx).doh != null) " · DoH 🔒" else ""} · в блоке ${DnsRules.totalListed + DnsRules.customRules.size} доменов",
                    style = Ns.small.copy(color = Ns.Dim, fontSize = 10.sp),
                )
            }
            if (on) PulseDot(Ns.Red, 10.dp)
        }
        Spacer(Modifier.height(8.dp))
        TermButton(if (on) "ОСТАНОВИТЬ" else "НАЧАТЬ ПЕРЕХВАТ", Modifier.fillMaxWidth(), color = if (on) Ns.Dim else Ns.Red) {
            if (on) DnsVpnService.stop(ctx) else {
                val intent = VpnService.prepare(ctx)
                if (intent != null) consent.launch(intent) else DnsVpnService.start(ctx)
            }
        }
        DnsMonitor.error?.let { Text("ошибка: $it", style = Ns.small.copy(color = Ns.Red)) }
        Spacer(Modifier.height(8.dp))
        NsTabs(listOf("ЛЕНТА", "ПРИЛОЖЕНИЯ", "ТОП", "ПРАВИЛА"), tab, Ns.Red) { tab = it; openApp = null }

        val selected = openApp
        when {
            selected != null -> AppDetails(selected, blockedUids, onBack = { openApp = null }, onDomain = { domainMenu = it }) {
                DnsMonitor.setUidBlocked(ctx, selected.uid, selected.uid !in blockedUids)
                blockedUids = DnsMonitor.blockedUids(ctx)
            }
            tab == 0 -> FeedTab(onDomain = { domainMenu = it }) { export.launch("nullsignal_dns_${System.currentTimeMillis() / 1000}.csv") }
            tab == 1 -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (DnsMonitor.apps.isEmpty()) item { HowItWorks() }
                items(DnsMonitor.apps.values.sortedByDescending { it.queries }, key = { it.uid }) { a ->
                    val cut = a.uid in blockedUids
                    Row(
                        Modifier.fillMaxWidth().hud(if (cut) Ns.Red else Ns.Faint).clickable { openApp = a }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.app, style = Ns.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${a.queries} запр. · ${a.domains.size} доменов · блок ${a.blocked}", style = Ns.small)
                        }
                        if (a.trackers > 0) {
                            Box(Modifier.background(Ns.Red).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("${a.trackers} ТРЕК.", style = Ns.small.copy(color = Ns.Bg, fontSize = 10.sp))
                            }
                        }
                        if (cut) Text("  ОТРЕЗАНО", style = Ns.small.copy(color = Ns.Red, fontSize = 10.sp))
                    }
                }
            }
            tab == 2 -> TopTab { domainMenu = it }
            else -> RulesTab(trackersOn) { trackersOn = it; DnsMonitor.setBlockTrackers(ctx, it) }
        }
    }
}

@Composable
private fun HowItWorks() {
    Panel {
        Text("КАК ЭТО РАБОТАЕТ", style = Ns.label.copy(color = Ns.Red))
        Text(
            "Каждое приложение перед подключением спрашивает у DNS адрес сервера. Перехватчик встаёт на место DNS " +
                "(через VPN-механизм Android, трафик никуда не уходит) и видит, КТО и КУДА стучится. " +
                "Трекеры и домены из блок-листов получают ответ «такого адреса нет» — реклама и слежка отваливаются во всех приложениях сразу.\n\n" +
                "Если в настройках включён «Частный DNS» в строгом режиме — переключи его на «Автоматически».",
            style = Ns.small,
        )
    }
}

@Composable
private fun ColumnScope.FeedTab(onDomain: (String) -> Unit, onExport: () -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var onlyBlocked by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${if (onlyBlocked) "[■]" else "[ ]"} только заблокированные",
            style = Ns.small.copy(color = if (onlyBlocked) Ns.Red else Ns.Dim),
            modifier = Modifier.clickable { onlyBlocked = !onlyBlocked }.padding(vertical = 4.dp).weight(1f),
        )
        Text("[CSV]", style = Ns.small.copy(color = Ns.Green), modifier = Modifier.clickable(onClick = onExport).padding(4.dp))
        Text("[очистить]", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.clickable { DnsMonitor.clear() }.padding(4.dp))
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        if (DnsMonitor.feed.isEmpty()) item { HowItWorks() }
        items(DnsMonitor.feed.filter { !onlyBlocked || it.blocked }) { e ->
            Row(Modifier.fillMaxWidth().clickable { onDomain(e.domain) }.padding(vertical = 3.dp)) {
                Text(fmt.format(Date(e.time)), style = Ns.small.copy(color = Ns.Faint, fontSize = 10.sp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.app, style = Ns.small.copy(color = Ns.Dim, fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        e.domain,
                        style = Ns.text.copy(color = if (e.blocked) Ns.Red else Ns.Green, fontSize = 12.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val note = listOfNotNull(e.tracker?.takeIf { it != "блок-лист" }, e.reason).distinct().joinToString(" · ")
                    if (note.isNotEmpty()) Text((if (e.blocked) "✕ " else "· ") + note, style = Ns.small.copy(color = Ns.Red, fontSize = 10.sp))
                }
                Text(if (e.blocked) "БЛОК" else "${e.type} ${e.ms}мс", style = Ns.small.copy(color = Ns.Faint, fontSize = 10.sp))
            }
        }
    }
}

@Composable
private fun ColumnScope.TopTab(onDomain: (String) -> Unit) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        val trackers = DnsMonitor.topTrackers.entries.sortedByDescending { it.value }.take(30)
        item { SectionTitle("трекеры · ${DnsMonitor.topTrackers.size}", Ns.Red) }
        if (trackers.isEmpty()) item { Text("пока не встречались", style = Ns.small) }
        items(trackers, key = { "t" + it.key }) { (name, n) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(name, style = Ns.text.copy(color = Ns.Red), modifier = Modifier.weight(1f))
                Text("$n", style = Ns.text)
            }
        }
        val max = (DnsMonitor.topDomains.values.maxOrNull() ?: 1).toFloat()
        val domains = DnsMonitor.topDomains.entries.sortedByDescending { it.value }.take(60)
        item { SectionTitle("самые частые домены") }
        items(domains, key = { "d" + it.key }) { (d, n) ->
            Column(Modifier.fillMaxWidth().clickable { onDomain(d) }.padding(vertical = 3.dp)) {
                Row {
                    Text(d, style = Ns.small.copy(color = if (Trackers.match(d) != null) Ns.Red else Ns.Green), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("$n", style = Ns.small)
                }
                HudBar(n / max, if (Trackers.match(d) != null) Ns.Red else Ns.Dim, height = 3.dp)
            }
        }
    }
}

@Composable
private fun ColumnScope.AppDetails(a: AppDns, blockedUids: Set<Int>, onBack: () -> Unit, onDomain: (String) -> Unit, onToggleCut: () -> Unit) {
    val cut = a.uid in blockedUids
    val live = DnsMonitor.apps[a.uid] ?: a
    Text("← ${live.app}", style = Ns.title.copy(fontSize = 16.sp), modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp))
    Text("запросов ${live.queries} · трекеров ${live.trackers} · заблокировано ${live.blocked} · доменов ${live.domains.size}", style = Ns.small)
    TermButton(if (cut) "ВЕРНУТЬ СЕТЬ" else "ОТРЕЗАТЬ ОТ СЕТИ", Modifier.fillMaxWidth().padding(vertical = 6.dp), color = if (cut) Ns.Green else Ns.Red, onClick = onToggleCut)
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(live.domains.sorted()) { d ->
            val tr = Trackers.match(d)
            val mine = DnsRules.custom.match(d) != null
            Text(
                (if (tr != null || mine) "■ " else "· ") + d + (tr?.let { "  [$it]" } ?: "") + if (mine) "  [моё правило]" else "",
                style = Ns.small.copy(color = if (tr != null || mine) Ns.Red else Ns.Text2),
                modifier = Modifier.fillMaxWidth().clickable { onDomain(d) }.padding(vertical = 3.dp),
            )
        }
    }
}

/** Быстрые действия с доменом: заблокировать, разрешить, разведать. */
@Composable
private fun DomainActions(domain: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    ModuleScreen("ДОМЕН", onClose) {
        Text(domain, style = Ns.title.copy(fontSize = 18.sp, letterSpacing = 0.sp))
        Trackers.match(domain)?.let { Text("известный трекер: $it", style = Ns.text.copy(color = Ns.Red)) }
        DnsRules.lists.match(domain)?.let { Text("есть в блок-листе (правило $it)", style = Ns.small.copy(color = Ns.Amber)) }
        Spacer(Modifier.height(10.dp))
        val parent = domain.split('.').takeLast(2).joinToString(".")
        TermButton("БЛОКИРОВАТЬ $domain", Modifier.fillMaxWidth(), color = Ns.Red) {
            scope.launch { msg = if (DnsRules.addCustom(ctx, domain)) "заблокировано" else "не получилось" }
        }
        Spacer(Modifier.height(6.dp))
        if (parent != domain) {
            TermButton("БЛОКИРОВАТЬ ВЕСЬ $parent", Modifier.fillMaxWidth(), color = Ns.Red) {
                scope.launch { msg = if (DnsRules.addCustom(ctx, parent)) "заблокирован $parent и все поддомены" else "не получилось" }
            }
            Spacer(Modifier.height(6.dp))
        }
        TermButton("НИКОГДА НЕ БЛОКИРОВАТЬ", Modifier.fillMaxWidth()) {
            scope.launch { msg = if (DnsRules.addAllow(ctx, domain)) "добавлено в исключения" else "не получилось" }
        }
        Spacer(Modifier.height(6.dp))
        TermButton("ПРОБИТЬ В OSINT", Modifier.fillMaxWidth()) { ctx.openUrl("https://crt.sh/?q=$parent") }
        msg?.let { Text(it, style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(top = 8.dp)) }
        Text("правила начинают действовать сразу, без перезапуска", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ColumnScope.RulesTab(trackersOn: Boolean, onTrackers: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var rule by remember { mutableStateOf("") }
    var allowRule by remember { mutableStateOf("") }
    var upstream by remember { mutableStateOf(DnsRules.upstreamId(ctx)) }
    var doh by remember { mutableStateOf(DnsRules.useDoh(ctx)) }
    val enabled = remember(busy, msg) { DnsRules.enabled(ctx) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "${if (trackersOn) "[■]" else "[ ]"} блокировать известные трекеры (${Trackers.size} доменов)",
            style = Ns.small.copy(color = if (trackersOn) Ns.Green else Ns.Dim),
            modifier = Modifier.clickable { onTrackers(!trackersOn) }.padding(vertical = 6.dp),
        )
        SectionTitle("блок-листы · ${DnsRules.totalListed} доменов", Ns.Red)
        BlocklistSources.ALL.forEach { s ->
            val size = DnsRules.listSizes[s.id]
            val on = s.id in enabled
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).hud(if (on) Ns.Red else Ns.Faint).padding(10.dp)) {
                Text(s.title, style = Ns.text.copy(color = if (on) Ns.Green else Ns.Text2))
                Text(s.about + (size?.let { " · скачано $it" } ?: ""), style = Ns.small)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermButton(if (busy == s.id) "КАЧАЮ…" else if (size == null) "СКАЧАТЬ" else "ОБНОВИТЬ", enabled = busy == null) {
                        busy = s.id
                        scope.launch {
                            msg = DnsRules.download(ctx, s.id).fold({ "${s.title}: $it доменов" }, { "ошибка: ${it.message}" })
                            busy = null
                        }
                    }
                    if (size != null) {
                        TermButton(if (on) "ВЫКЛ" else "ВКЛ", color = if (on) Ns.Dim else Ns.Red, enabled = busy == null) {
                            scope.launch { DnsRules.setListEnabled(ctx, s.id, !on); msg = if (on) "выключен" else "включён" }
                        }
                        TermButton("✕", color = Ns.Dim, enabled = busy == null) {
                            scope.launch { DnsRules.deleteList(ctx, s.id); msg = "удалён" }
                        }
                    }
                }
            }
        }
        msg?.let { Text(it, style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(vertical = 4.dp)) }

        SectionTitle("мои правила блокировки · ${DnsRules.customRules.size}", Ns.Red)
        Row(verticalAlignment = Alignment.CenterVertically) {
            NsField(rule, { rule = it }, "домен, например ads.site.ru", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            TermButton("+", color = Ns.Red) {
                scope.launch {
                    if (DnsRules.addCustom(ctx, rule)) { rule = "" } else { msg = "это не похоже на домен" }
                }
            }
        }
        DnsRules.customRules.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("■ $r", style = Ns.small.copy(color = Ns.Red), modifier = Modifier.weight(1f))
                Text("✕", style = Ns.text.copy(color = Ns.Dim), modifier = Modifier.clickable { scope.launch { DnsRules.removeCustom(ctx, r) } }.padding(6.dp))
            }
        }

        SectionTitle("исключения (никогда не блокировать) · ${DnsRules.allowRules.size}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            NsField(allowRule, { allowRule = it }, "домен, который сломался", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            TermButton("+") {
                scope.launch {
                    if (DnsRules.addAllow(ctx, allowRule)) { allowRule = "" } else { msg = "это не похоже на домен" }
                }
            }
        }
        DnsRules.allowRules.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓ $r", style = Ns.small.copy(color = Ns.Green), modifier = Modifier.weight(1f))
                Text("✕", style = Ns.text.copy(color = Ns.Dim), modifier = Modifier.clickable { scope.launch { DnsRules.removeAllow(ctx, r) } }.padding(6.dp))
            }
        }

        SectionTitle("DNS-сервер")
        Upstreams.ALL.forEach { u ->
            Text(
                (if (u.id == upstream) "(●) " else "( ) ") + u.title + if (u.doh != null) "  · DoH" else "",
                style = Ns.small.copy(color = if (u.id == upstream) Ns.Green else Ns.Dim),
                modifier = Modifier.fillMaxWidth().clickable { upstream = u.id; DnsRules.setUpstream(ctx, u.id) }.padding(vertical = 5.dp),
            )
        }
        Text(
            "${if (doh) "[■]" else "[ ]"} шифровать DNS (DNS-over-HTTPS): провайдер не увидит, какие сайты ты открываешь",
            style = Ns.small.copy(color = if (doh) Ns.Green else Ns.Dim),
            modifier = Modifier.clickable { doh = !doh; DnsRules.setDoh(ctx, doh) }.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}
