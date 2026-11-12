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

@Composable
fun MessagesScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val inbox = vm.engine.inbox(vm.progress)
    val firstNew = remember { vm.progress.readMessages }
    val list = rememberLazyListState()
    LaunchedEffect(inbox.size) {
        if (inbox.isNotEmpty()) list.animateScrollToItem(inbox.lastIndex)
        vm.markRead()
    }
    ModuleScreen("MESSAGES", onBack) {
        Text("канал: #nullsignal  ·  шифрование: сквозное (ну, в нашей истории)", style = Ns.small)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(inbox.size) { i ->
                val m = inbox[i]
                Column(Modifier.fillMaxWidth().hud(Ns.Faint).padding(8.dp)) {
                    Text("<${m.from.handle}>", style = Ns.text.copy(color = Ns.Cyan))
                    if (i >= firstNew) TypewriterText(m.text, charDelayMs = 18) else Text(m.text, style = Ns.text)
                }
            }
        }
        Text(
            "[ЗАДАЧА] ${vm.engine.current(vm.progress).objective}",
            style = Ns.text.copy(color = Ns.Amber),
            modifier = Modifier.fillMaxWidth().hud(Ns.Amber).padding(8.dp),
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
