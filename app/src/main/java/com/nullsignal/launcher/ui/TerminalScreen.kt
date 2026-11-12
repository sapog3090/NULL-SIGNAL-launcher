package com.nullsignal.launcher.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nullsignal.launcher.LauncherViewModel
import com.nullsignal.launcher.LineKind

private val QUICK = listOf("help", "whoami", "sysinfo", "ping 8.8.8.8", "nslookup github.com", "curl github.com", "sh getprop ro.product.model", "passgen", "quest")

@Composable
fun TerminalScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var historyPos by remember { mutableIntStateOf(-1) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.termLines.size) {
        if (vm.termLines.isNotEmpty()) list.scrollToItem(vm.termLines.lastIndex)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun send() {
        val cmd = input
        input = ""
        historyPos = -1
        vm.submit(cmd)
    }

    ModuleScreen("TERMINAL", onBack) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            itemsIndexed(vm.termLines) { _, line ->
                Text(
                    line.text,
                    style = when (line.kind) {
                        LineKind.INPUT -> Ns.text.copy(color = Ns.Cyan)
                        LineKind.SYSTEM -> Ns.text.copy(color = Ns.Amber)
                        LineKind.ERROR -> Ns.text.copy(color = Ns.Red)
                        LineKind.OUTPUT -> Ns.text
                    },
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (vm.termBusy) {
                Text("^C стоп", style = Ns.small.copy(color = Ns.Red), modifier = Modifier.hud(Ns.Red).clickable { vm.interrupt() }.padding(8.dp))
            }
            Text("↑", style = Ns.small, modifier = Modifier.hud(Ns.Faint).clickable {
                val h = vm.termHistory
                if (h.isNotEmpty()) {
                    historyPos = if (historyPos < 0) h.lastIndex else (historyPos - 1).coerceAtLeast(0)
                    input = h[historyPos]
                }
            }.padding(8.dp))
            QUICK.forEach { q ->
                Text(q, style = Ns.small, modifier = Modifier.hud(Ns.Faint).clickable { input = q }.padding(8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().hud(Ns.Dim).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.termBusy) "… " else "$ ", style = Ns.text.copy(color = Ns.Cyan))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = Ns.text,
                cursorBrush = SolidColor(Ns.Green),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Text(" ⏎", style = Ns.text, modifier = Modifier.clickable { send() })
        }
    }
}
