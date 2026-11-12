package com.nullsignal.launcher.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nullsignal.core.breach.Breach
import com.nullsignal.core.net.Subnet
import com.nullsignal.core.story.Trigger
import com.nullsignal.launcher.LauncherViewModel

/** Запрос runtime-разрешений: (выданы ли, функция «попросить»). */
@Composable
fun rememberPermissions(perms: Array<String>): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    fun check() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(check()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = check()
    }
    return granted to { launcher.launch(perms) }
}

/** Индикатор прогресса из символов: [██████······] 50% */
fun asciiBar(p: Float, width: Int = 24): String {
    val n = (p.coerceIn(0f, 1f) * width).toInt()
    return "[" + "█".repeat(n) + "·".repeat(width - n) + "] ${(p * 100).toInt()}%"
}

// ---------------- MESSAGES ----------------

private sealed interface ChatItem {
    class Msg(val m: com.nullsignal.core.story.Message, val story: Boolean) : ChatItem
    class Mine(val text: String) : ChatItem
    class Choice(val chapter: com.nullsignal.core.story.Chapter) : ChatItem
}

private fun memberColor(m: com.nullsignal.core.story.Member) = when (m) {
    com.nullsignal.core.story.Member.RAVEN -> Ns.Red
    com.nullsignal.core.story.Member.KILOBYTE -> Ns.Green
    com.nullsignal.core.story.Member.JAY -> Ns.Cyan
    com.nullsignal.core.story.Member.GHOST -> Ns.Amber
}

@Composable
fun MessagesScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val chapters = vm.engine.unlocked(vm.progress)
    val current = chapters.last()
    val items = buildList<ChatItem> {
        for (ch in chapters) {
            ch.messages.forEach { add(ChatItem.Msg(it, true)) }
            val picked = vm.replies[ch.id]
            if (picked != null) {
                ch.replies.getOrNull(picked)?.let { r -> add(ChatItem.Mine(r.text)); r.answer.forEach { add(ChatItem.Msg(it, false)) } }
            } else if (ch === current && ch.replies.isNotEmpty()) {
                add(ChatItem.Choice(ch))
            }
        }
    }
    // уже прочитанное показываем сразу, новое — по одному сообщению, как в настоящем чате
    val firstNew = remember {
        var seen = 0
        var idx = items.size
        for ((i, it) in items.withIndex()) {
            if (it is ChatItem.Msg && it.story) { if (seen == vm.progress.readMessages) { idx = i; break }; seen++ }
        }
        idx
    }
    var shown by remember { mutableIntStateOf(firstNew) }
    var typing by remember { mutableStateOf<com.nullsignal.core.story.Member?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(items.size, shown) {
        if (shown >= items.size) { typing = null; vm.markRead(); return@LaunchedEffect }
        val next = items[shown]
        if (next is ChatItem.Msg) {
            typing = next.m.from
            kotlinx.coroutines.delay((450L + next.m.text.length * 22L).coerceAtMost(2600L))
            typing = null
            kotlinx.coroutines.delay(120)
        }
        shown++
    }
    LaunchedEffect(shown, typing) { list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    ModuleScreen("MESSAGES", onBack) {
        Text("#nullsignal · ${com.nullsignal.core.story.Member.entries.size} участника · сквозное шифрование", style = Ns.small)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(minOf(shown, items.size)) { i ->
                val prev = items.getOrNull(i - 1)
                when (val it = items[i]) { // it — элемент чата
                    is ChatItem.Msg -> {
                        val c = memberColor(it.m.from)
                        val sameAuthor = prev is ChatItem.Msg && prev.m.from == it.m.from
                        Column(Modifier.fillMaxWidth(0.88f).padding(top = if (sameAuthor) 0.dp else 8.dp)) {
                            if (!sameAuthor) Text(it.m.from.handle, style = Ns.label.copy(color = c))
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    it.m.text, style = Ns.text,
                                    modifier = Modifier.background(Ns.Panel)
                                        .drawBehind { drawRect(c.copy(alpha = 0.7f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    is ChatItem.Mine -> Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.End) {
                        Text("ТЫ", style = Ns.label)
                        Text(it.text, style = Ns.text.copy(color = Ns.Text2), modifier = Modifier.hud(Ns.Faint).padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    is ChatItem.Choice -> Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.End) {
                        it.chapter.replies.forEachIndexed { idx, r ->
                            Text(
                                "› ${r.text}",
                                style = Ns.text.copy(color = Ns.Red),
                                modifier = Modifier.padding(vertical = 3.dp).hud(Ns.Red.copy(alpha = 0.5f)).clickable { vm.reply(it.chapter.id, idx) }.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            typing?.let { m ->
                item { Text("${m.handle} печатает…", style = Ns.small.copy(color = memberColor(m)), modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        Text(
            "[ЗАДАЧА] ${current.objective}",
            style = Ns.text.copy(color = Ns.Amber),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).hud(Ns.Amber).padding(8.dp),
        )
    }
}

@Composable
internal fun PermissionGate(text: String, onRequest: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = Ns.text.copy(color = Ns.Amber, textAlign = TextAlign.Center))
        Spacer(Modifier.height(12.dp))
        TermButton("ВЫДАТЬ ДОСТУП", onClick = onRequest)
    }
}
