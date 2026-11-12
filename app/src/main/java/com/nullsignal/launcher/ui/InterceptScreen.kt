package com.nullsignal.launcher.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.dns.Trackers
import com.nullsignal.launcher.sys.AppDns
import com.nullsignal.launcher.sys.DnsMonitor
import com.nullsignal.launcher.sys.DnsVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InterceptScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var trackersOn by remember { mutableStateOf(DnsMonitor.blockTrackers(ctx)) }
    var blockedUids by remember { mutableStateOf(DnsMonitor.blockedUids(ctx)) }
    var openApp by remember { mutableStateOf<AppDns?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) DnsVpnService.start(ctx)
        else DnsMonitor.error = "разрешение на VPN не выдано"
    }

    ModuleScreen("ПЕРЕХВАТ", onBack) {
        // ---------- рубильник ----------
        val on = DnsMonitor.running
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (on) "ПЕРЕХВАТ АКТИВЕН" else "ПЕРЕХВАТ ВЫКЛЮЧЕН", style = Ns.title.copy(color = if (on) Ns.Red else Ns.Dim, fontSize = 17.sp))
                Text(
                    "запросов ${DnsMonitor.total} · заблокировано ${DnsMonitor.blocked} · приложений ${DnsMonitor.apps.size}",
                    style = Ns.small,
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
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (trackersOn) "[■]" else "[ ]"} блокировать трекеры и рекламу (${Trackers.size} доменов)",
                style = Ns.small.copy(color = if (trackersOn) Ns.Green else Ns.Dim),
                modifier = Modifier.clickable { trackersOn = !trackersOn; DnsMonitor.setBlockTrackers(ctx, trackersOn) }.padding(vertical = 6.dp),
            )
        }
        if (!on && DnsMonitor.total == 0) {
            Panel {
                Text("КАК ЭТО РАБОТАЕТ", style = Ns.label.copy(color = Ns.Red))
                Text(
                    "Каждое приложение перед подключением спрашивает у DNS адрес сервера. Перехватчик встаёт на место DNS " +
                        "(через VPN-механизм Android, трафик никуда не уходит) и видит, КТО и КУДА стучится. " +
                        "Трекеры получают ответ «такого адреса нет». Отрезанное приложение не может найти ни один сервер.\n\n" +
                        "Если в настройках включён «Частный DNS» в строгом режиме — переключи его на «Автоматически».",
                    style = Ns.small,
                )
            }
        }

        // ---------- вкладки ----------
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ЛЕНТА", "ПРИЛОЖЕНИЯ").forEachIndexed { i, t ->
                Text(
                    t,
                    style = Ns.small.copy(color = if (i == tab) Ns.Bg else Ns.Green, textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f).background(if (i == tab) Ns.Green else Ns.Panel).clickable { tab = i; openApp = null }.padding(vertical = 8.dp),
                )
            }
        }
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        val selected = openApp
        when {
            selected != null -> {
                val cut = selected.uid in blockedUids
                Text("← ${selected.app}", style = Ns.title.copy(fontSize = 16.sp), modifier = Modifier.clickable { openApp = null }.padding(vertical = 4.dp))
                Text("запросов ${selected.queries} · трекеров ${selected.trackers} · доменов ${selected.domains.size}", style = Ns.small)
                TermButton(if (cut) "ВЕРНУТЬ СЕТЬ" else "ОТРЕЗАТЬ ОТ СЕТИ", Modifier.fillMaxWidth().padding(vertical = 6.dp), color = if (cut) Ns.Green else Ns.Red) {
                    DnsMonitor.setUidBlocked(ctx, selected.uid, !cut)
                    blockedUids = DnsMonitor.blockedUids(ctx)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(selected.domains.sorted()) { d ->
                        val tr = Trackers.match(d)
                        Text(
                            (if (tr != null) "■ " else "· ") + d + (tr?.let { "  [$it]" } ?: ""),
                            style = Ns.small.copy(color = if (tr != null) Ns.Red else Ns.Text2),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            tab == 0 -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(DnsMonitor.feed) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(fmt.format(Date(e.time)), style = Ns.small.copy(color = Ns.Faint, fontSize = 10.sp))
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.app, style = Ns.small.copy(color = Ns.Dim, fontSize = 10.sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                e.domain,
                                style = Ns.text.copy(color = if (e.blocked) Ns.Red else Ns.Green, fontSize = 12.sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            e.tracker?.let { Text("${if (e.blocked) "ЗАБЛОКИРОВАНО" else "трекер"}: $it", style = Ns.small.copy(color = Ns.Red, fontSize = 10.sp)) }
                        }
                        Text(e.type, style = Ns.small.copy(color = Ns.Faint, fontSize = 10.sp))
                    }
                }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(DnsMonitor.apps.values.sortedByDescending { it.queries }, key = { it.uid }) { a ->
                    val cut = a.uid in blockedUids
                    Row(
                        Modifier.fillMaxWidth().hud(if (cut) Ns.Red else Ns.Faint).clickable { openApp = a }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.app, style = Ns.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${a.queries} запр. · ${a.domains.size} доменов", style = Ns.small)
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
        }
    }
}
