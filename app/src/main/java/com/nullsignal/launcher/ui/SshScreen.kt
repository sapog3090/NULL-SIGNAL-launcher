package com.nullsignal.launcher.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.launcher.LauncherViewModel
import com.nullsignal.launcher.sys.SshClient
import kotlinx.coroutines.launch

@Composable
fun SshScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    if (SshClient.connected) SshTerminal(onBack) else SshConnect(vm, onBack)
}

@Composable
private fun SshConnect(vm: LauncherViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var user by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var password by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf(SshClient.hosts(ctx)) }
    var pub by remember { mutableStateOf(SshClient.publicKey(ctx)) }
    var genBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.moduleArg?.let { arg ->
            SshClient.parseTarget(arg)?.let { (u, h, p) -> user = u; host = h; port = p.toString() }
            vm.moduleArg = null
        }
    }
    fun connect() {
        val p = port.toIntOrNull() ?: 22
        if (user.isBlank() || host.isBlank()) { SshClient.status = "укажи пользователя и адрес"; return }
        scope.launch { SshClient.connect(ctx, user.trim(), host.trim(), p, password); hosts = SshClient.hosts(ctx) }
    }
    ModuleScreen("SSH", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Удалённый терминал: свой ПК, сервер, Raspberry Pi или Termux на этом же телефоне.", style = Ns.small)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NsField(user, { user = it }, "пользователь", Modifier.weight(1f))
                Text(" @ ", style = Ns.text)
                NsField(host, { host = it }, "адрес", Modifier.weight(1.4f))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NsField(port, { port = it.filter(Char::isDigit).take(5) }, "порт", Modifier.width(90.dp), keyboard = KeyboardType.Number)
                Spacer(Modifier.width(6.dp))
                NsField(password, { password = it }, if (pub != null) "пароль (или вход по ключу)" else "пароль", Modifier.weight(1f), secret = true, onDone = { connect() })
            }
            TermButton(if (SshClient.connecting) "ПОДКЛЮЧАЮСЬ…" else "ПОДКЛЮЧИТЬСЯ", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red, enabled = !SshClient.connecting) { connect() }
            SshClient.status?.let { Text(it, style = Ns.small.copy(color = if (it.startsWith("ошибка")) Ns.Red else Ns.Amber), modifier = Modifier.padding(vertical = 6.dp)) }

            if (hosts.isNotEmpty()) {
                SectionTitle("сохранённые")
                hosts.forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).hud(Ns.Faint).clickable {
                        user = h.user; host = h.host; port = h.port.toString()
                    }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.target, style = Ns.text, modifier = Modifier.weight(1f))
                        Text("✕", style = Ns.text.copy(color = Ns.Dim), modifier = Modifier.clickable { SshClient.removeHost(ctx, h); hosts = SshClient.hosts(ctx) }.padding(4.dp))
                    }
                }
            }

            SectionTitle("вход по ключу (без пароля)", Ns.Red)
            val key = pub
            if (key == null) {
                Text("Ключ надёжнее пароля: его нельзя подобрать. Создай ключ и добавь публичную часть на сервер.", style = Ns.small)
                TermButton(if (genBusy) "СОЗДАЮ RSA-3072…" else "СОЗДАТЬ КЛЮЧ", Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !genBusy) {
                    genBusy = true
                    scope.launch { try { pub = SshClient.generateKey(ctx) } catch (e: Exception) { SshClient.status = "ошибка: ${e.message}" } finally { genBusy = false } }
                }
            } else {
                Text("Публичный ключ — добавь его на сервер в ~/.ssh/authorized_keys:", style = Ns.small)
                SelectionContainer { Text(key, style = Ns.small.copy(color = Ns.Green, fontSize = 9.sp), modifier = Modifier.padding(vertical = 6.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermButton("СКОПИРОВАТЬ", Modifier.weight(1f)) { clip.setText(AnnotatedString(key)) }
                    TermButton("СКОПИРОВАТЬ КОМАНДУ", Modifier.weight(1f)) {
                        clip.setText(AnnotatedString("mkdir -p ~/.ssh && echo '$key' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"))
                    }
                }
                Text("[удалить ключ]", style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.clickable { SshClient.deleteKey(ctx); pub = null }.padding(vertical = 6.dp))
            }

            SectionTitle("Termux на этом телефоне")
            Text(
                "Полноценный Linux в кармане: поставь Termux (из F-Droid), введи в нём:\npkg install openssh && passwd && sshd\nи подключайся сюда: любой пользователь @ 127.0.0.1, порт 8022.",
                style = Ns.small,
            )
            TermButton("ПОДСТАВИТЬ TERMUX", Modifier.fillMaxWidth().padding(vertical = 6.dp)) { user = "u0"; host = "127.0.0.1"; port = "8022" }
        }
    }
}

private val QUICK_SSH = listOf("ls -la", "uname -a", "df -h", "free -h", "uptime", "ip a", "ps aux | head -20", "whoami", "exit")

@Composable
private fun SshTerminal(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(SshClient.lines.size) { if (SshClient.lines.isNotEmpty()) list.scrollToItem(SshClient.lines.size) }
    fun send() {
        val cmd = input
        input = ""
        scope.launch { SshClient.sendLine(cmd) }
    }
    ModuleScreen("SSH · ${SshClient.title}", onBack) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            itemsIndexed(SshClient.lines) { _, l -> Text(l, style = Ns.text.copy(fontSize = 12.sp)) }
            item { Text(SshClient.partial, style = Ns.text.copy(fontSize = 12.sp, color = Ns.Red)) }
        }
        Row(Modifier.padding(vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("^C", style = Ns.small.copy(color = Ns.Red), modifier = Modifier.hud(Ns.Red).clickable { scope.launch { SshClient.send("\u0003") } }.padding(8.dp))
            Text("TAB", style = Ns.small, modifier = Modifier.hud(Ns.Faint).clickable { scope.launch { SshClient.send("\t") } }.padding(8.dp))
            Text("↑", style = Ns.small, modifier = Modifier.hud(Ns.Faint).clickable { scope.launch { SshClient.send("\u001B[A") } }.padding(8.dp))
            Text("^D", style = Ns.small, modifier = Modifier.hud(Ns.Faint).clickable { scope.launch { SshClient.send("\u0004") } }.padding(8.dp))
            QUICK_SSH.forEach { q -> Text(q, style = Ns.small, modifier = Modifier.hud(Ns.Faint).clickable { input = q }.padding(8.dp)) }
            Text("ОТКЛЮЧИТЬСЯ", style = Ns.small.copy(color = Ns.Red), modifier = Modifier.hud(Ns.Red).clickable { SshClient.disconnect() }.padding(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NsField(input, { input = it }, "команда", Modifier.weight(1f), keyboard = KeyboardType.Ascii, onDone = { send() })
            Text(" ⏎", style = Ns.title, modifier = Modifier.clickable { send() })
        }
        Text("интерактивные программы (vim, top) здесь выглядят криво — используй команды целиком", style = Ns.small.copy(fontSize = 9.sp, color = Ns.Faint))
    }
}
