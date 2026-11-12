package com.nullsignal.launcher.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.net.DeviceKind
import com.nullsignal.core.net.Subnet
import com.nullsignal.launcher.LauncherViewModel
import com.nullsignal.launcher.sys.Discover
import com.nullsignal.launcher.sys.Host

@Composable
fun NetScanScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Host?>(null) }
    val selected = open
    if (selected != null) {
        BackHandler { open = null }
        HostDetails(vm, vm.scanHosts.firstOrNull { it.ip == selected.ip } ?: selected) { open = null }
        return
    }
    ModuleScreen("NETSCAN", onBack) {
        NsTabs(listOf("УСТРОЙСТВА", "РОУТЕР", "WAKE-ON-LAN"), tab) { tab = it }
        when (tab) {
            0 -> DevicesTab(vm) { open = it }
            1 -> RouterTab(vm)
            else -> WolTab(vm)
        }
    }
}

@Composable
private fun ColumnScope.DevicesTab(vm: LauncherViewModel, onOpen: (Host) -> Unit) {
    TermButton(if (vm.scanning) "СКАНИРУЮ…" else "СКАНИРОВАТЬ СЕТЬ", Modifier.fillMaxWidth(), enabled = !vm.scanning) { vm.startScan() }
    Spacer(Modifier.height(6.dp))
    Text(vm.scanState, style = Ns.small.copy(color = Ns.Amber))
    if (vm.scanning) HudBar(vm.scanProgress, Ns.Red, Modifier.padding(vertical = 4.dp))
    if (vm.newDevices > 0) {
        Text(
            "⚠ НОВЫХ УСТРОЙСТВ В СЕТИ: ${vm.newDevices} — если это не твои, смени пароль Wi-Fi",
            style = Ns.text.copy(color = Ns.Red),
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    if (vm.scanHosts.isNotEmpty()) {
        val byKind = vm.scanHosts.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        Text(byKind.joinToString("  ") { "${it.key.icon} ${it.value}" }, style = Ns.small, modifier = Modifier.padding(vertical = 4.dp))
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(vm.scanHosts, key = { it.ip }) { h ->
            Row(
                Modifier.fillMaxWidth().hud(if (h.isNew) Ns.Red else if (h.gateway) Ns.Green else Ns.Faint).clickable { onOpen(h) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(h.kind.icon, style = Ns.title.copy(fontSize = 20.sp, color = if (h.isNew) Ns.Red else Ns.Green), modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.label, style = Ns.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (h.isNew) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.background(Ns.Red).padding(horizontal = 4.dp)) { Text("НОВОЕ", style = Ns.small.copy(color = Ns.Bg, fontSize = 9.sp)) }
                        }
                    }
                    Text(
                        "${h.ip} · ${h.kind.title}" + if (h.ports.isNotEmpty()) " · порты ${h.ports.joinToString(",")}" else "",
                        style = Ns.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (h.ports.any { it in Subnet.ADVICE.keys && it != 80 }) Text("⚠", style = Ns.text.copy(color = Ns.Amber))
            }
        }
        if (vm.mdns.isNotEmpty()) {
            item { SectionTitle("mDNS: устройства сами представились") }
            items(vm.mdns, key = { "m" + it.type + it.name }) { s ->
                Text("◆ ${s.name}  (${s.type})", style = Ns.small.copy(color = Ns.Green), modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        if (vm.scanHosts.isNotEmpty() && !vm.scanning) {
            item {
                Text(
                    "[забыть сеть — сбросить список известных устройств]",
                    style = Ns.small.copy(color = Ns.Dim),
                    modifier = Modifier.clickable { vm.forgetNetwork() }.padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun HostDetails(vm: LauncherViewModel, h: Host, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    ModuleScreen(h.label, onClose) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Panel(color = if (h.isNew) Ns.Red else Ns.Dim) {
                Text("${h.kind.icon}  ${h.kind.title.uppercase()}", style = Ns.title.copy(fontSize = 16.sp))
                Spacer(Modifier.height(6.dp))
                KeyValue("IP", h.ip)
                h.mac?.let { KeyValue("MAC", it) }
                h.netbios?.let { KeyValue("имя Windows", it) }
                h.upnp?.let { KeyValue("UPnP", it) }
                h.upnpType?.let { KeyValue("тип UPnP", it.substringAfter("device:").substringBefore(':')) }
                h.web?.let { KeyValue("веб-страница", it) }
                h.server?.let { KeyValue("сервер", it) }
                h.name?.let { KeyValue("DNS-имя", it) }
                if (h.gateway) KeyValue("роль", "шлюз — это твой роутер", Ns.Red)
                if (h.isNew) KeyValue("статус", "НЕ БЫЛО при прошлых сканах", Ns.Red)
            }
            SectionTitle("открытые порты · ${h.ports.size}")
            if (h.ports.isEmpty()) Text("все проверенные порты закрыты — устройство просто в сети", style = Ns.small)
            h.ports.forEach { p ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text("$p/tcp  ${Subnet.COMMON_PORTS[p] ?: ""}", style = Ns.text)
                    Subnet.ADVICE[p]?.let { Text("  $it", style = Ns.small.copy(color = Ns.Amber)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            val webPort = listOf(80, 8080, 443, 8443, 8000).firstOrNull { it in h.ports }
            if (webPort != null) {
                TermButton("ОТКРЫТЬ ВЕБ-ИНТЕРФЕЙС", Modifier.fillMaxWidth()) {
                    ctx.openUrl("${if (webPort == 443 || webPort == 8443) "https" else "http"}://${h.ip}:$webPort/")
                }
                Spacer(Modifier.height(6.dp))
            }
            val mac = h.mac
            if (mac != null) {
                TermButton("ЗАПОМНИТЬ ДЛЯ WAKE-ON-LAN", Modifier.fillMaxWidth()) {
                    msg = if (vm.addWol(h.label, mac)) "сохранено: ${h.label} ($mac)" else "не удалось"
                }
            } else {
                Text(
                    "MAC-адрес Android скрывает от приложений. Его видно только у Windows-компьютеров (через NetBIOS).",
                    style = Ns.small.copy(color = Ns.Dim),
                )
            }
            msg?.let { Text(it, style = Ns.small.copy(color = Ns.Green), modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun ColumnScope.RouterTab(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    val r = vm.router
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "UPnP позволяет любому устройству в сети самому открыть порт в интернет — без спроса. " +
                "Проверим, что уже открыто, какой у тебя внешний IP и что о нём знают интернет-сканеры.",
            style = Ns.small,
        )
        Spacer(Modifier.height(8.dp))
        TermButton(if (vm.routerBusy) "ОПРАШИВАЮ РОУТЕР…" else "ПРОВЕРИТЬ РОУТЕР", Modifier.fillMaxWidth(), color = Ns.Red, enabled = !vm.routerBusy) { vm.auditRouter() }
        if (r != null) {
            Spacer(Modifier.height(10.dp))
            Panel(color = if (r.mappings.isNotEmpty()) Ns.Red else Ns.Dim) {
                Text(r.device?.title?.ifBlank { null } ?: "роутер", style = Ns.title.copy(fontSize = 16.sp))
                r.device?.modelNumber?.let { KeyValue("модель", it) }
                r.externalIp?.let { ip ->
                    KeyValue("внешний IP", ip)
                    if (Discover.isCarrierNat(ip)) {
                        Text(
                            "Серый IP: провайдер прячет тебя за своим NAT. Из интернета к устройствам дома не подключиться — для безопасности это плюс.",
                            style = Ns.small.copy(color = Ns.Green),
                        )
                    }
                }
                r.error?.let { Text(it, style = Ns.small.copy(color = Ns.Green)) }
                if (r.error == null) {
                    Text(
                        "⚠ UPnP включён: любая программа в сети может открыть порт наружу. Если не играешь в онлайн-игры с хостингом — выключи UPnP в настройках роутера.",
                        style = Ns.small.copy(color = Ns.Amber),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                vm.routerExposure?.let { Text(it, style = Ns.small.copy(color = Ns.Cyan), modifier = Modifier.padding(top = 6.dp)) }
                r.device?.presentationUrl?.let { url ->
                    Text("[открыть админку роутера]", style = Ns.small.copy(color = Ns.Red), modifier = Modifier.clickable { ctx.openUrl(url) }.padding(top = 6.dp))
                }
            }
            SectionTitle("проброшенные порты · ${r.mappings.size}")
            if (r.mappings.isEmpty() && r.error == null) Text("ни одного — снаружи к тебе не попасть через UPnP ✓", style = Ns.small.copy(color = Ns.Green))
            r.mappings.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).hud(Ns.Red).padding(8.dp)) {
                    Text("${m.external}/${m.protocol} → ${m.internalClient}:${m.internalPort}", style = Ns.text.copy(color = Ns.Red))
                    Text(
                        listOfNotNull(m.description.ifBlank { null }, if (!m.enabled) "выключено" else null,
                            if (m.leaseSeconds > 0) "истекает через ${m.leaseSeconds / 60} мин" else "навсегда").joinToString(" · "),
                        style = Ns.small,
                    )
                    Subnet.ADVICE[m.internalPort]?.let { Text("⚠ открыт в ИНТЕРНЕТ: $it", style = Ns.small.copy(color = Ns.Amber)) }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WolTab(vm: LauncherViewModel) {
    var name by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "Wake-on-LAN включает компьютер по сети «волшебным пакетом». В BIOS/настройках сетевой карты ПК должна быть включена опция Wake on LAN.",
            style = Ns.small,
        )
        Spacer(Modifier.height(8.dp))
        NsField(name, { name = it }, "название (например, «мой ПК»)")
        Spacer(Modifier.height(6.dp))
        NsField(mac, { mac = it }, "MAC-адрес: AA:BB:CC:DD:EE:FF")
        TermButton("ДОБАВИТЬ", Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            msg = if (vm.addWol(name, mac)) { name = ""; mac = ""; "добавлено" } else "неверный MAC-адрес"
        }
        msg?.let { Text(it, style = Ns.small.copy(color = Ns.Amber)) }
        SectionTitle("компьютеры · ${vm.wolTargets.size}")
        if (vm.wolTargets.isEmpty()) {
            Text("MAC-адрес ПК: Windows → cmd → getmac /v. Или найди ПК в NETSCAN и нажми «запомнить».", style = Ns.small.copy(color = Ns.Dim))
        }
        vm.wolTargets.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).hud(Ns.Faint).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = Ns.text)
                    Text(t.mac, style = Ns.small)
                }
                TermButton("РАЗБУДИТЬ", color = Ns.Red) { msg = vm.wake(t) }
                Spacer(Modifier.width(6.dp))
                Text("✕", style = Ns.text.copy(color = Ns.Dim), modifier = Modifier.clickable { vm.removeWol(t) }.padding(6.dp))
            }
        }
    }
}

// ---------------- КАМЕРЫ ----------------

@Composable
fun CamsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    ModuleScreen("КАМЕРЫ", onBack) {
        Text(
            "Ищет IP-камеры и видеорегистраторы в сети, к которой ты подключён: видеопотоки RTSP, ответы ONVIF, порты Dahua/Hikvision/XMEye, веб-интерфейсы камер. " +
                "Полезно в съёмной квартире или отеле: камеры в спальне и ванной там запрещены.",
            style = Ns.small,
        )
        Spacer(Modifier.height(8.dp))
        TermButton(if (vm.camBusy) "ИЩУ…" else "НАЙТИ КАМЕРЫ", Modifier.fillMaxWidth(), color = Ns.Red, enabled = !vm.camBusy) { vm.scanCameras() }
        Spacer(Modifier.height(6.dp))
        Text(vm.camState, style = Ns.small.copy(color = Ns.Amber))
        if (vm.camBusy) HudBar(vm.camProgress, Ns.Red, Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.cameras, key = { it.ip }) { c ->
                Column(Modifier.fillMaxWidth().hud(if (c.confidence >= 5) Ns.Red else Ns.Amber).clickable { ctx.openUrl("http://${c.ip}/") }.padding(10.dp)) {
                    Text(
                        "${if (c.confidence >= 5) "◉ ПОЧТИ ТОЧНО КАМЕРА" else "◍ ПОХОЖЕ НА КАМЕРУ"} · ${c.ip}",
                        style = Ns.label.copy(color = if (c.confidence >= 5) Ns.Red else Ns.Amber),
                    )
                    c.title?.let { Text(it, style = Ns.text) }
                    c.reasons.forEach { Text("· $it", style = Ns.small) }
                }
            }
            if (vm.cameras.isNotEmpty()) item {
                Text(
                    "Нажми на устройство, чтобы открыть его веб-страницу. Камера в чужой сети может быть охранной и законной — " +
                        "но если она смотрит туда, где ты раздеваешься или спишь, сообщи хозяину и в полицию.",
                    style = Ns.small.copy(color = Ns.Dim),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Suppress("unused")
private val kinds = DeviceKind.Kind.entries
