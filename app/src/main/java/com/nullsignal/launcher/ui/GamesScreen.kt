package com.nullsignal.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nullsignal.core.games.CipherGame
import com.nullsignal.core.games.Nodes
import com.nullsignal.core.games.VulnHunt
import com.nullsignal.core.story.Trigger
import com.nullsignal.core.terminal.Module
import com.nullsignal.launcher.LauncherViewModel

@Composable
fun GamesScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    var game by remember { mutableStateOf<String?>(null) }
    val g = game
    if (g != null) {
        BackHandler { game = null }
        val exit = { game = null }
        when (g) {
            "cipher" -> CipherView(vm, exit)
            "vuln" -> VulnView(vm, exit)
            "nodes" -> NodesView(vm, exit)
        }
        return
    }
    ModuleScreen("GAMES", onBack) {
        Text("тренажёры. всё вымышлено, навыки — настоящие", style = Ns.small)
        Spacer(Modifier.height(10.dp))
        val items = listOf(
            Triple("breach", "# BREACH", "подбор hex-ключа по подсказкам"),
            Triple("cipher", "⌗ ШИФРЫ  ${vm.cipherLevel}/${CipherGame.levels.size}", "расшифруй перехваченные сообщения"),
            Triple("vuln", "⚠ КОД  ${vm.vulnLevel}/${VulnHunt.snippets.size}", "найди уязвимость в исходниках"),
            Triple("nodes", "◇ УЗЛЫ  побед: ${vm.nodesWins}", "поверни узлы и собери сеть"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (id, title, desc) ->
                Column(
                    Modifier.fillMaxWidth().border(1.dp, Ns.Dim).clickable {
                        if (id == "breach") vm.screen = Module.BREACH else game = id
                    }.padding(12.dp),
                ) {
                    Text(title, style = Ns.title)
                    Text(desc, style = Ns.small)
                }
            }
        }
    }
}

// ---------------- ШИФРЫ ----------------

@Composable
private fun CipherView(vm: LauncherViewModel, onExit: () -> Unit) {
    val clip = LocalClipboardManager.current
    var answer by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    val idx = vm.cipherLevel
    ModuleScreen("ШИФРЫ", onExit) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (idx >= CipherGame.levels.size) {
                GlitchText("ВСЕ ПЕРЕХВАТЫ РАСШИФРОВАНЫ")
                Text("<СОЙКА> Отлично. Это ровно то, что нам было нужно.", style = Ns.text.copy(color = Ns.Cyan), modifier = Modifier.padding(vertical = 8.dp))
                TermButton("ПРОЙТИ ЗАНОВО", Modifier.fillMaxWidth()) { vm.saveCipherLevel(0) }
                return@Column
            }
            val level = CipherGame.levels[idx]
            Text("перехват ${idx + 1}/${CipherGame.levels.size}: ${level.title}", style = Ns.text.copy(color = Ns.Amber))
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(level.encoded, style = Ns.title.copy(color = Ns.Cyan), modifier = Modifier.fillMaxWidth().border(1.dp, Ns.Faint).padding(10.dp))
            }
            Text("[скопировать — пригодится в терминале]", style = Ns.small.copy(color = Ns.Cyan),
                modifier = Modifier.clickable { clip.setText(AnnotatedString(level.encoded)) }.padding(4.dp))
            Text(if (showHint) "подсказка: ${level.hint}" else "[подсказка]", style = Ns.small.copy(color = Ns.Amber),
                modifier = Modifier.clickable { showHint = true }.padding(4.dp))
            Spacer(Modifier.height(8.dp))
            fun submit() {
                if (CipherGame.check(level, answer)) {
                    answer = ""; wrong = false; showHint = false
                    vm.saveCipherLevel(idx + 1)
                    if (idx + 1 == CipherGame.levels.size) vm.fire(Trigger.CIPHER_WON)
                } else wrong = true
            }
            BasicTextField(
                value = answer,
                onValueChange = { answer = it; wrong = false },
                singleLine = true,
                textStyle = Ns.text,
                cursorBrush = SolidColor(Ns.Green),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().border(1.dp, if (wrong) Ns.Red else Ns.Dim).padding(10.dp),
                decorationBox = { inner ->
                    if (answer.isEmpty()) Text("расшифровка…", style = Ns.text.copy(color = Ns.Faint))
                    inner()
                },
            )
            if (wrong) Text("✗ не то. попробуй ещё", style = Ns.small.copy(color = Ns.Red))
            Spacer(Modifier.height(8.dp))
            TermButton("ПРОВЕРИТЬ", Modifier.fillMaxWidth(), enabled = answer.isNotBlank()) { submit() }
        }
    }
}

// ---------------- КОД ----------------

@Composable
private fun VulnView(vm: LauncherViewModel, onExit: () -> Unit) {
    var picked by remember { mutableIntStateOf(-1) }
    var misses by remember { mutableIntStateOf(0) }
    val idx = vm.vulnLevel
    ModuleScreen("КОД", onExit) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (idx >= VulnHunt.snippets.size) {
                GlitchText("ОТЧЁТ ГОТОВ")
                Text("<ВОРОН> Все дыры найдены. Это пойдёт в отчёт.", style = Ns.text.copy(color = Ns.Cyan), modifier = Modifier.padding(vertical = 8.dp))
                TermButton("ПРОЙТИ ЗАНОВО", Modifier.fillMaxWidth()) { vm.saveVulnLevel(0) }
                return@Column
            }
            val s = VulnHunt.snippets[idx]
            val found = picked == s.vulnerable
            Text("файл ${idx + 1}/${VulnHunt.snippets.size}: ${s.title}", style = Ns.text.copy(color = Ns.Amber))
            Text("нажми на строку с уязвимостью", style = Ns.small)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(Ns.Panel).border(1.dp, Ns.Faint)) {
                s.lines.forEachIndexed { i, line ->
                    val c = when {
                        found && i == s.vulnerable -> Ns.Red
                        !found && i == picked -> Ns.Amber
                        else -> Ns.Green
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (found && i == s.vulnerable) Ns.Red.copy(alpha = 0.15f) else Ns.Panel)
                            .clickable(enabled = !found) {
                                picked = i
                                if (i != s.vulnerable) misses++
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("${i + 1}".padStart(2) + "  ", style = Ns.text.copy(color = Ns.Faint))
                        Text(line, style = Ns.text.copy(color = c))
                    }
                }
            }
            if (picked >= 0 && !found) Text("✗ здесь всё нормально. ищи дальше", style = Ns.small.copy(color = Ns.Amber), modifier = Modifier.padding(top = 6.dp))
            if (found) {
                Text("✓ НАЙДЕНО: ${s.explain}", style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(top = 10.dp))
                Text("как чинить: ${s.fix}", style = Ns.text.copy(color = Ns.Cyan), modifier = Modifier.padding(vertical = 6.dp))
                TermButton(if (idx == VulnHunt.snippets.lastIndex) "ЗАВЕРШИТЬ" else "СЛЕДУЮЩИЙ ФАЙЛ", Modifier.fillMaxWidth()) {
                    picked = -1
                    vm.saveVulnLevel(idx + 1)
                    if (idx + 1 == VulnHunt.snippets.size) vm.fire(Trigger.VULN_WON)
                }
            }
            Text("промахов: $misses", style = Ns.small, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ---------------- УЗЛЫ ----------------

@Composable
private fun NodesView(vm: LauncherViewModel, onExit: () -> Unit) {
    var grid by remember { mutableIntStateOf(4) }
    var round by remember { mutableIntStateOf(0) }
    val game = remember(grid, round) { Nodes(grid) }
    var tick by remember(grid, round) { mutableIntStateOf(0) }
    var moves by remember(grid, round) { mutableIntStateOf(0) }
    val connected = remember(tick, game) { game.connected() }
    val solved = remember(tick, game) { game.solved() }
    LaunchedEffect(solved) {
        if (solved) {
            vm.addNodesWin()
            vm.fire(Trigger.NODES_WON)
        }
    }
    ModuleScreen("УЗЛЫ", onExit) {
        Text("поворачивай узлы (тап), чтобы вся сеть сошлась к серверу ◉ без обрывов", style = Ns.small)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(4, 5, 6, 7).forEach { n ->
                TermButton("${n}×$n", Modifier.weight(1f), color = if (n == grid) Ns.Cyan else Ns.Dim) { grid = n; round++ }
            }
        }
        Text(
            if (solved) "СЕТЬ СОБРАНА ✓  ходов: $moves" else "в сети: ${connected.size}/${grid * grid}  ходов: $moves",
            style = Ns.text.copy(color = if (solved) Ns.Green else Ns.Amber),
        )
        Column(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 8.dp).border(1.dp, Ns.Faint)) {
            for (r in 0 until grid) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (c in 0 until grid) {
                        val i = r * grid + c
                        val mask = game.tiles[i].also { tick } // читаем tick, чтобы перерисовать после поворота
                        val on = i in connected
                        val color = when {
                            solved -> Ns.Green
                            on -> Ns.Cyan
                            else -> Ns.Dim
                        }
                        Box(
                            Modifier.weight(1f).fillMaxSize().border(0.5.dp, Ns.Faint.copy(alpha = 0.4f)).clickable(enabled = !solved) {
                                game.rotate(i); moves++; tick++
                            },
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val center = Offset(size.width / 2, size.height / 2)
                                val w = size.minDimension * 0.14f
                                if (mask and Nodes.UP != 0) drawLine(color, center, Offset(center.x, 0f), w, StrokeCap.Square)
                                if (mask and Nodes.RIGHT != 0) drawLine(color, center, Offset(this.size.width, center.y), w, StrokeCap.Square)
                                if (mask and Nodes.DOWN != 0) drawLine(color, center, Offset(center.x, this.size.height), w, StrokeCap.Square)
                                if (mask and Nodes.LEFT != 0) drawLine(color, center, Offset(0f, center.y), w, StrokeCap.Square)
                                if (i == game.source) {
                                    drawCircle(Ns.Cyan, this.size.minDimension * 0.3f, center)
                                    drawCircle(Ns.Bg, this.size.minDimension * 0.16f, center)
                                } else if (Integer.bitCount(mask) == 1) {
                                    drawCircle(color, this.size.minDimension * 0.18f, center)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (solved) TermButton("НОВАЯ СЕТЬ", Modifier.fillMaxWidth()) { round++ }
    }
}
