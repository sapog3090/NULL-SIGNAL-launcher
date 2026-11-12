package com.nullsignal.launcher.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nullsignal.core.net.ChatCrypto
import com.nullsignal.core.net.ChatNode
import com.nullsignal.core.net.ChatNode.Via
import com.nullsignal.core.net.NtfyRelay
import com.nullsignal.core.security.PasswordStrength
import com.nullsignal.core.story.Trigger
import com.nullsignal.launcher.LauncherViewModel
import com.nullsignal.launcher.sys.ChatSession
import com.nullsignal.launcher.sys.WifiDirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun viaColor(v: Via): Color = when (v) {
    Via.LAN -> Ns.Green
    Via.P2P -> Ns.Red
    Via.RELAY -> Ns.Cyan
    Via.SELF -> Ns.Dim
}

@Composable
private fun ChannelToggle(title: String, about: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).hud(if (on) Ns.Red.copy(alpha = 0.6f) else Ns.Faint)
            .clickable { onChange(!on) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (on) "[■]" else "[ ]", style = Ns.text.copy(color = if (on) Ns.Red else Ns.Dim))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ns.text.copy(fontWeight = FontWeight.Bold, color = if (on) Ns.Green else Ns.Text2))
            Text(about, style = Ns.small)
        }
    }
}

/**
 * ШИФРОЧАТ: рядом — по Wi-Fi или точке Wi-Fi Direct без интернета, далеко — напрямую P2P через NAT
 * или через ретранслятор. Ключ — из кодового слова, сервер видит только шифр.
 */
@Composable
fun LanChatScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("lanchat", 0) }
    var room by remember { mutableStateOf(prefs.getString("room", "") ?: "") }
    var nick by remember { mutableStateOf(prefs.getString("nick", null) ?: vm.operator) }
    var useLan by remember { mutableStateOf(prefs.getBoolean("lan", true)) }
    var useFar by remember { mutableStateOf(prefs.getBoolean("far", true)) }
    var server by remember { mutableStateOf(prefs.getString("server", NtfyRelay.DEFAULT) ?: NtfyRelay.DEFAULT) }
    var showServer by remember { mutableStateOf(false) }
    val session = ChatSession
    var joining by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val direct = remember { session.direct(ctx) }
    val selfId = remember {
        prefs.getLong("self_id", 0L).takeIf { it != 0L } ?: SecureRandom().nextLong().also { prefs.edit().putLong("self_id", it).apply() }
    }
    val p2pPerms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val (p2pGranted, askP2p) = rememberPermissions(p2pPerms)
    var pendingDirect by remember { mutableStateOf<((Pair<String, String>) -> Unit)?>(null) }
    val messages = session.messages
    val peers = session.peers
    val status = session.status

    // экран открыт — уведомления не нужны; закрыли экран — комната продолжает работать
    DisposableEffect(Unit) {
        session.visible = true
        onDispose { session.visible = false }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    LaunchedEffect(session.node, status.relay, peers.size) {
        if (session.node != null && (status.relay == ChatNode.RelayState.ONLINE || peers.isNotEmpty())) vm.fire(Trigger.CHANNEL_UP)
    }
    LaunchedEffect(direct.role) {
        while (direct.role != WifiDirect.Role.NONE) { delay(5000); direct.refresh() }
    }

    fun withGroup(action: (Pair<String, String>) -> Unit) {
        if (room.trim().length < 4) { error = "сначала кодовое слово — из него получаются имя и пароль точки"; return }
        error = null
        if (!p2pGranted) { pendingDirect = action; askP2p(); return }
        scope.launch {
            val names = withContext(Dispatchers.Default) { ChatCrypto.directGroup(ChatCrypto.key(room)) }
            action(names)
        }
    }
    LaunchedEffect(p2pGranted) {
        val p = pendingDirect
        if (p2pGranted && p != null) {
            pendingDirect = null
            val names = withContext(Dispatchers.Default) { ChatCrypto.directGroup(ChatCrypto.key(room)) }
            p(names)
        }
    }

    fun join() {
        val word = room.trim()
        if (word.length < 4) { error = "кодовое слово — хотя бы 4 символа"; return }
        if (!useLan && !useFar) { error = "включи хотя бы один канал связи"; return }
        if (useFar && word.length < 8) {
            error = "для дальней связи слово — от 8 символов: ящик на сервере виден всем, короткое слово можно подобрать"
            return
        }
        val relayUrl = if (useFar) runCatching { NtfyRelay.normalize(server) }.getOrElse { error = it.message; return } else null
        prefs.edit().putString("room", word).putString("nick", nick.trim()).putBoolean("lan", useLan)
            .putBoolean("far", useFar).putString("server", server.trim()).apply()
        error = null
        joining = true
        val lan = useLan
        val who = nick.trim().ifEmpty { "аноним" }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { session.join(ctx, word, who, lan, relayUrl, selfId) } }
            joining = false
            r.onFailure { error = "не удалось открыть канал: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    ModuleScreen("ШИФРОЧАТ", onBack) {
        val n = session.node
        if (n == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Переписка, которую никто не читает: ключ AES-256 получается из кодового слова и не покидает телефон. " +
                        "Рядом — по Wi-Fi без интернета, далеко — напрямую через интернет (P2P) или через ретранслятор, который видит только шифр.",
                    style = Ns.small,
                )
                SectionTitle("позывной")
                NsField(nick, { nick = it.take(24) }, "как тебя подписывать")
                SectionTitle("кодовое слово комнаты")
                NsField(room, { room = it }, "одинаковое у всех, лучше 3–4 слова")
                if (room.isNotBlank()) {
                    val rep = remember(room) { PasswordStrength.check(room.trim()) }
                    val c = when (rep.level) { 0, 1 -> Ns.Red; 2 -> Ns.Amber; else -> Ns.Green }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        HudBar((rep.level + 1) / 5f, c, Modifier.width(80.dp), 4.dp)
                        Text("  слово: ${rep.verdict.lowercase()}", style = Ns.small.copy(color = c))
                    }
                }

                SectionTitle("каналы связи")
                ChannelToggle(
                    "WI-FI РЯДОМ", "одна сеть, точка доступа или Wi-Fi Direct — интернет не нужен; телефон с двумя сетями сам передаёт сообщения между ними",
                    useLan,
                ) { useLan = it }
                ChannelToggle(
                    "ДАЛЬНЯЯ СВЯЗЬ", "любой город: напрямую P2P, если NAT пускает, иначе через ретранслятор. Сервер хранит шифр 12 часов — дойдёт, даже если собеседник офлайн",
                    useFar,
                ) { useFar = it }
                if (useFar) {
                    Text(
                        if (showServer) "▾ ретранслятор" else "▸ ретранслятор: ${runCatching { NtfyRelay(server).name }.getOrDefault(server)}",
                        style = Ns.small.copy(color = Ns.Dim),
                        modifier = Modifier.padding(top = 6.dp).clickable { showServer = !showServer },
                    )
                    if (showServer) {
                        NsField(server, { server = it.trim() }, NtfyRelay.DEFAULT)
                        Text(
                            "Подойдёт любой сервер ntfy, в том числе свой (ставится одной командой на любой VPS или Raspberry Pi). " +
                                "Сервер видит только шифр, время и IP. Push в Google отключён.",
                            style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (useLan) {
                    SectionTitle("без роутера — wi-fi direct", Ns.Amber)
                    Text(
                        "Нет общей сети? Один телефон поднимает точку, остальные подключаются. Имя и пароль точки выводятся из кодового слова. " +
                            "Дальность — как у Wi-Fi: десятки метров в здании, до 100–200 м на открытом месте.",
                        style = Ns.small,
                    )
                    if (!direct.supported) {
                        Text("нужен Android 10 или новее", style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TermButton("ПОДНЯТЬ ТОЧКУ", Modifier.weight(1f), color = Ns.Amber, enabled = !direct.busy) { withGroup { direct.host(it.first, it.second) } }
                            TermButton("ПОДКЛЮЧИТЬСЯ", Modifier.weight(1f), color = Ns.Amber, enabled = !direct.busy) { withGroup { direct.join(it.first, it.second) } }
                        }
                        if (direct.status.isNotEmpty()) Text(direct.status, style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(top = 4.dp))
                        if (direct.role != WifiDirect.Role.NONE) {
                            Text("закрыть точку", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(top = 4.dp).clickable { direct.leave() })
                        }
                    }
                }
                error?.let { Text(it, style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(10.dp))
            }
            TermButton(if (joining) "ОТКРЫВАЮ КАНАЛ…" else "ВОЙТИ В КОМНАТУ", Modifier.fillMaxWidth(), color = Ns.Red, enabled = !joining) { join() }
            return@ModuleScreen
        }

        // ---------- комната ----------
        val lanN = peers.count { it.via == Via.LAN }
        val p2pN = peers.count { it.via == Via.P2P }
        val netN = peers.count { it.via == Via.RELAY }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("комната ${n.fingerprint}", style = Ns.label.copy(color = Ns.Red), modifier = Modifier.weight(1f))
            Chip("ВЫЙТИ", Ns.Dim, Modifier.clickable { session.close() })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (session.lan) Chip("WI-FI $lanN", if (lanN > 0) Ns.Green else Ns.Dim)
            if (session.far) {
                Chip("P2P $p2pN", if (p2pN > 0) Ns.Red else Ns.Dim)
                when (status.relay) {
                    ChatNode.RelayState.ONLINE -> Chip("СЕРВЕР $netN", Ns.Cyan)
                    ChatNode.RelayState.CONNECTING -> Chip("СЕРВЕР…", Ns.Dim)
                    ChatNode.RelayState.ERROR -> Chip("СЕРВЕР ✕", Ns.Red)
                    ChatNode.RelayState.OFF -> {}
                }
            }
            if (status.bridge) Chip("МОСТ", Ns.Amber)
            if (direct.role != WifiDirect.Role.NONE) Chip(if (direct.role == WifiDirect.Role.OWNER) "ТОЧКА" else "В ТОЧКЕ", Ns.Amber)
        }
        Text(
            if (peers.isEmpty()) "в эфире пока только ты" else peers.joinToString { "${it.nick} (${it.via.label})" },
            style = Ns.small.copy(color = if (peers.isEmpty()) Ns.Dim else Ns.Green), maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
        val note = status.error ?: status.nat
        if (note != null && session.far) Text(note, style = Ns.small.copy(color = if (status.error != null) Ns.Amber else Ns.Dim), maxLines = 2)
        if (status.bridge) Text("ты — мост: пересылаешь сообщения между Wi-Fi и интернетом", style = Ns.small.copy(color = Ns.Amber))

        val day = remember { SimpleDateFormat("dd.MM HH:mm", Locale.US) }
        val hm = remember { SimpleDateFormat("HH:mm", Locale.US) }
        fun stamp(t: Long) = if (System.currentTimeMillis() - t > 20 * 3600_000L) day.format(Date(t)) else hm.format(Date(t))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).hud(Ns.Faint).padding(8.dp),
            state = list, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) item {
                Text(
                    "Сообщений нет. Отпечаток комнаты должен совпасть у всех — тогда вы на одной волне." +
                        if (session.far) "\n\nМожно писать и тем, кого сейчас нет: сервер подержит шифр 12 часов." else "",
                    style = Ns.small,
                )
            }
            items(messages, key = { it.id }) { m ->
                val archive = m.time < session.joinedAt - 60_000
                if (m.mine) {
                    Column(Modifier.animateItem().fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        val mark = when {
                            !session.far || archive -> ""
                            session.delivered[m.id] == true -> " · ✓ сервер"
                            else -> " · …"
                        }
                        Text((if (archive) "архив · " else "") + "ты · " + stamp(m.time) + mark, style = Ns.label)
                        SelectionContainer {
                            Text(m.text, style = Ns.text.copy(color = Ns.Text2), modifier = Modifier.fillMaxWidth(0.86f).hud(Ns.Faint).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                } else {
                    val c = viaColor(m.via)
                    Column(Modifier.animateItem().fillMaxWidth(0.86f)) {
                        Text((if (archive) "архив · " else "") + "${m.from} · ${stamp(m.time)} · ${m.via.label}", style = Ns.label.copy(color = c))
                        SelectionContainer {
                            Text(
                                m.text, style = Ns.text,
                                modifier = Modifier.background(Ns.Panel)
                                    .drawBehind { drawRect(c.copy(alpha = 0.75f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            NsField(input, { input = it.take(ChatNode.MAX_TEXT) }, "сообщение", Modifier.weight(1f), onDone = { n.send(input); input = "" })
            Text("  ⏎", style = Ns.title.copy(color = Ns.Red), modifier = Modifier.clickable { n.send(input); input = "" }.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}
