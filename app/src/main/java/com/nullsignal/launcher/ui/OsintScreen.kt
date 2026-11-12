package com.nullsignal.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.osint.Breaches
import com.nullsignal.core.osint.DomainIntel
import com.nullsignal.core.osint.UsernameSearch
import com.nullsignal.launcher.LauncherViewModel
import com.nullsignal.launcher.sys.OsintNet
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun OsintScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val arg = vm.moduleArg?.trim()
        vm.moduleArg = null
        if (!arg.isNullOrEmpty()) {
            tab = when {
                '@' in arg -> 1
                Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(arg) || ':' in arg -> 2
                '.' in arg -> 3
                else -> 0
            }
            preset = arg
        }
    }
    ModuleScreen("OSINT", onBack) {
        Text("разведка по открытым источникам: то, что о человеке, сайте или адресе может узнать любой", style = Ns.small)
        Spacer(Modifier.height(6.dp))
        NsTabs(listOf("НИК", "ПОЧТА", "IP", "ДОМЕН"), tab, Ns.Red) { tab = it }
        val consume = { preset = null }
        when (tab) {
            0 -> NickTab(preset, consume)
            1 -> EmailTab(preset, consume)
            2 -> IpTab(preset, consume)
            else -> DomainTab(preset, consume)
        }
    }
}

@Composable
private fun ColumnScope.NickTab(preset: String?, consume: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var nick by remember { mutableStateOf(preset ?: "") }
    var busy by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<OsintNet.SiteResult>() }
    fun run() {
        val n = nick.trim().removePrefix("@")
        if (!UsernameSearch.valid(n) || busy) return
        busy = true
        results.clear()
        scope.launch {
            try { OsintNet.username(n) { results += it } } finally { busy = false }
        }
    }
    LaunchedEffect(preset) { if (preset != null) { nick = preset; run(); consume() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NsField(nick, { nick = it }, "ник без @", Modifier.weight(1f), onDone = { run() })
        Spacer(Modifier.width(6.dp))
        TermButton(if (busy) "…" else "ИСКАТЬ", color = Ns.Red, enabled = !busy) { run() }
    }
    val found = results.filter { it.verdict == UsernameSearch.Verdict.FOUND }
    val unknown = results.filter { it.verdict == UsernameSearch.Verdict.UNKNOWN }
    if (results.isNotEmpty()) {
        Text(
            "проверено ${results.size}/${UsernameSearch.SITES.size} · найден на ${found.size}" + if (unknown.isNotEmpty()) " · не ответили ${unknown.size}" else "",
            style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(vertical = 6.dp),
        )
    }
    if (busy) HudBar(results.size.toFloat() / UsernameSearch.SITES.size, Ns.Red)
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(found, key = { "f" + it.site.name }) { r ->
            val url = UsernameSearch.fill(r.site.profile, nick.trim().removePrefix("@"))
            Column(Modifier.fillMaxWidth().hud(Ns.Red).clickable { ctx.openUrl(url) }.padding(10.dp)) {
                Text("◉ ${r.site.name}", style = Ns.text.copy(color = Ns.Red))
                Text(url, style = Ns.small)
            }
        }
        if (unknown.isNotEmpty()) item {
            Text("не ответили / закрылись от проверки: ${unknown.joinToString { it.site.name }}", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 6.dp))
        }
        val missing = results.filter { it.verdict == UsernameSearch.Verdict.NOT_FOUND }
        if (missing.isNotEmpty()) item {
            Text("нет на: ${missing.joinToString { it.site.name }}", style = Ns.small.copy(color = Ns.Faint), modifier = Modifier.padding(vertical = 6.dp))
        }
        if (results.isEmpty() && !busy) item {
            Text(
                "Проверяет ${UsernameSearch.SITES.size} сайтов: GitHub, Reddit, YouTube, Telegram, Steam, Roblox, Minecraft, Chess.com, Habr и другие. " +
                    "Так можно увидеть свой цифровой след: где ник «засветился» и что по нему найдёт любой.",
                style = Ns.small.copy(color = Ns.Dim),
            )
        }
    }
}

@Composable
private fun ColumnScope.EmailTab(preset: String?, consume: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(preset ?: "") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Breaches.Result>?>(null) }
    fun run() {
        if (!Breaches.validEmail(email) || busy) return
        busy = true
        scope.launch { try { results = OsintNet.leaks(email.trim()) } finally { busy = false } }
    }
    LaunchedEffect(preset) { if (preset != null) { email = preset; run(); consume() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NsField(email, { email = it }, "почта", Modifier.weight(1f), keyboard = KeyboardType.Email, onDone = { run() })
        Spacer(Modifier.width(6.dp))
        TermButton(if (busy) "…" else "ПРОВЕРИТЬ", color = Ns.Red, enabled = !busy) { run() }
    }
    Text("адрес отправится в сервисы XposedOrNot и LeakCheck — проверяй свою почту", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 6.dp))
    val r = results
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (r != null && r.isEmpty()) Text("сервисы не ответили — попробуй позже", style = Ns.small.copy(color = Ns.Amber))
        r?.forEach { b ->
            Panel(Modifier.padding(vertical = 4.dp), color = if (b.found) Ns.Red else Ns.Dim) {
                Text(
                    if (b.found) "⚠ ${b.service}: НАЙДЕНА В УТЕЧКАХ (${b.sources.size})" else "✓ ${b.service}: в утечках не найдена",
                    style = Ns.text.copy(color = if (b.found) Ns.Red else Ns.Green),
                )
                if (b.fields.isNotEmpty()) Text("что утекло: ${b.fields.joinToString()}", style = Ns.small.copy(color = Ns.Amber))
                b.sources.take(60).forEach { Text("· $it", style = Ns.small) }
            }
        }
        if (r?.any { it.found } == true) {
            Text(
                "Что делать: смени пароль везде, где он совпадал с утёкшими сайтами, и включи двухфакторную защиту (модуль 2FA).",
                style = Ns.small.copy(color = Ns.Green), modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.IpTab(preset: String?, consume: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(preset ?: "") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<OsintNet.IpReport?>(null) }
    fun run(t: String = target) {
        if (t.isBlank() || busy) return
        busy = true
        scope.launch { try { report = OsintNet.ip(t) } finally { busy = false } }
    }
    LaunchedEffect(preset) { if (preset != null) { target = preset; run(preset); consume() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NsField(target, { target = it }, "IP или домен", Modifier.weight(1f), onDone = { run() })
        Spacer(Modifier.width(6.dp))
        TermButton(if (busy) "…" else "ПРОБИТЬ", color = Ns.Red, enabled = !busy) { run() }
    }
    Text(
        "[мой внешний IP]",
        style = Ns.small.copy(color = Ns.Cyan),
        modifier = Modifier.clickable { scope.launch { OsintNet.myIp()?.let { target = it; run(it) } } }.padding(vertical = 6.dp),
    )
    val r = report
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (r == null) {
            Text("Где находится сервер, чей он, через какого провайдера, какие порты и уязвимости видят интернет-сканеры (Shodan).", style = Ns.small.copy(color = Ns.Dim))
            return@Column
        }
        r.error?.let { Text(it, style = Ns.small.copy(color = Ns.Red)) }
        val g = r.geo
        Panel {
            Text(r.ip ?: r.target, style = Ns.title.copy(fontSize = 18.sp, letterSpacing = 0.sp))
            r.rdns?.let { KeyValue("имя", it) }
            if (g != null) {
                KeyValue("страна", listOfNotNull(g.country, g.countryCode?.let { "($it)" }).joinToString(" "))
                KeyValue("регион", listOfNotNull(g.region, g.city).joinToString(", "))
                g.isp?.let { KeyValue("провайдер", it) }
                if (g.org != null && g.org != g.isp) KeyValue("организация", g.org!!)
                g.asn?.let { KeyValue("ASN", it) }
                g.timezone?.let { KeyValue("часовой пояс", it) }
                if (g.lat != null && g.lon != null) {
                    KeyValue("координаты", "%.4f, %.4f (примерно, по городу)".format(g.lat, g.lon))
                    Text(
                        "[на карте]", style = Ns.small.copy(color = Ns.Red),
                        modifier = Modifier.clickable { ctx.openUrl("https://www.openstreetmap.org/?mlat=${g.lat}&mlon=${g.lon}#map=11/${g.lat}/${g.lon}") }.padding(top = 4.dp),
                    )
                }
            }
        }
        val e = r.exposure
        SectionTitle("что видят интернет-сканеры (Shodan InternetDB)", Ns.Red)
        if (e == null) Text("об этом адресе сканеры ничего не знают — открытых сервисов не видно ✓", style = Ns.small.copy(color = Ns.Green))
        else {
            KeyValue("открытые порты", e.ports.joinToString().ifEmpty { "нет" }, if (e.ports.isEmpty()) Ns.Green else Ns.Red)
            if (e.hostnames.isNotEmpty()) KeyValue("имена", e.hostnames.take(6).joinToString())
            if (e.tags.isNotEmpty()) KeyValue("метки", e.tags.joinToString())
            if (e.cpes.isNotEmpty()) KeyValue("ПО", e.cpes.take(6).joinToString { it.removePrefix("cpe:/") })
            if (e.vulns.isNotEmpty()) {
                Text("⚠ известные уязвимости: ${e.vulns.size}", style = Ns.text.copy(color = Ns.Red), modifier = Modifier.padding(top = 6.dp))
                e.vulns.take(30).forEach { v -> Text("· $v", style = Ns.small, modifier = Modifier.clickable { ctx.openUrl("https://nvd.nist.gov/vuln/detail/$v") }) }
            }
        }
    }
}

@Composable
private fun ColumnScope.DomainTab(preset: String?, consume: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(preset ?: "") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<OsintNet.DomainReport?>(null) }
    var tech by remember { mutableStateOf<OsintNet.TechReport?>(null) }
    var subs by remember { mutableStateOf<List<String>?>(null) }
    var subsBusy by remember { mutableStateOf(false) }
    fun run(raw: String = input) {
        val d = DomainIntel.clean(raw)
        if (!DomainIntel.validDomain(d) || busy) return
        input = d
        busy = true
        report = null; tech = null; subs = null
        scope.launch {
            try {
                val t = async { OsintNet.tech(d) }
                report = OsintNet.domain(d)
                tech = t.await()
            } finally { busy = false }
        }
    }
    LaunchedEffect(preset) { if (preset != null) { run(preset); consume() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NsField(input, { input = it }, "домен или ссылка", Modifier.weight(1f), onDone = { run() })
        Spacer(Modifier.width(6.dp))
        TermButton(if (busy) "…" else "РАЗВЕДКА", color = Ns.Red, enabled = !busy) { run() }
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
        val r = report
        if (r == null) {
            Text(
                if (busy) "собираю: DNS, WHOIS, почтовая защита, технологии, веб-архив…"
                else "DNS-записи, владелец (WHOIS), можно ли подделать письмо от домена, на чём сделан сайт, поддомены из журналов сертификатов, первая копия в веб-архиве.",
                style = Ns.small.copy(color = if (busy) Ns.Amber else Ns.Dim),
            )
            return@Column
        }
        SelectionContainer {
            Column {
                Panel {
                    Text(r.domain, style = Ns.title.copy(fontSize = 18.sp, letterSpacing = 0.sp))
                    if (r.a.isNotEmpty()) KeyValue("A", r.a.joinToString())
                    if (r.aaaa.isNotEmpty()) KeyValue("AAAA", r.aaaa.take(3).joinToString())
                    if (r.ns.isNotEmpty()) KeyValue("NS", r.ns.joinToString())
                    if (r.mx.isNotEmpty()) KeyValue("MX", r.mx.joinToString())
                    r.whois.forEach { line -> KeyValue(line.substringBefore(':').trim().lowercase(), line.substringAfter(':').trim()) }
                    r.firstSnapshot?.let { KeyValue("в архиве с", DomainIntel.formatTimestamp(it.timestamp)) }
                }
            }
        }
        r.a.firstOrNull()?.let { ip -> Text("[пробить сервер $ip]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { ctx.openUrl("https://internetdb.shodan.io/$ip") }.padding(vertical = 4.dp)) }
        r.lastSnapshot?.let { s -> Text("[открыть в веб-архиве]", style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.clickable { ctx.openUrl(s.url) }.padding(vertical = 4.dp)) }

        val spoof = com.nullsignal.core.osint.MailSecurity.spoofable(r.mail)
        SectionTitle(if (spoof) "почта: подделать письмо МОЖНО" else "почта: защищена", if (spoof) Ns.Red else Ns.Green)
        r.mail.forEach { f -> Text((if (f.ok) "✓ " else "✗ ") + f.text, style = Ns.small.copy(color = if (f.ok) Ns.Green else Ns.Red), modifier = Modifier.padding(vertical = 1.dp)) }
        if (r.txt.isNotEmpty()) {
            SectionTitle("TXT-записи")
            r.txt.take(12).forEach { Text("· ${it.trim('"').take(160)}", style = Ns.small) }
        }

        val t = tech
        SectionTitle("технологии сайта")
        when {
            t == null -> Text("смотрю сайт…", style = Ns.small.copy(color = Ns.Amber))
            t.error != null -> Text(t.error!!, style = Ns.small.copy(color = Ns.Amber))
            else -> {
                t.title?.let { Text("«$it» · HTTP ${t.code}", style = Ns.small.copy(color = Ns.Green)) }
                if (t.tech.isEmpty()) Text("ничего характерного не найдено", style = Ns.small)
                t.tech.groupBy { it.category }.forEach { (cat, list) ->
                    KeyValue(cat, list.joinToString { it.name }, if (cat == "слежка") Ns.Red else Ns.Green)
                }
            }
        }

        SectionTitle("поддомены (журналы сертификатов)", Ns.Red)
        val s = subs
        when {
            s == null -> TermButton(if (subsBusy) "ИЩУ… (crt.sh медленный)" else "НАЙТИ ПОДДОМЕНЫ", Modifier.fillMaxWidth(), enabled = !subsBusy) {
                subsBusy = true
                scope.launch { try { subs = OsintNet.subdomains(r.domain) } finally { subsBusy = false } }
            }
            s.isEmpty() -> Text("не найдено (или crt.sh не ответил)", style = Ns.small)
            else -> {
                Text("найдено ${s.size}", style = Ns.small.copy(color = Ns.Amber))
                s.take(300).forEach { sub -> Text("· $sub", style = Ns.small.copy(color = Ns.Green), modifier = Modifier.clickable { ctx.openUrl("https://$sub") }.padding(vertical = 1.dp)) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
