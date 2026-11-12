package com.nullsignal.launcher.ui

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.academy.CheckResult
import com.nullsignal.core.academy.Practice
import com.nullsignal.core.academy.PracticeTask
import com.nullsignal.core.academy.PyExamples
import com.nullsignal.core.py.MiniPy
import com.nullsignal.core.py.PyProblem
import com.nullsignal.launcher.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Песочница Python: пиши и запускай код прямо на телефоне, решай задачи с автопроверкой. */
@Composable
fun PythonScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("py", 0) }
    var tab by remember { mutableIntStateOf(if (vm.moduleArg == "tasks") 2 else 0) }
    var code by remember { mutableStateOf(TextFieldValue(prefs.getString("code", null) ?: PyExamples.list[0].code)) }
    var syntax by remember { mutableStateOf<PyProblem?>(null) }
    var task by remember { mutableStateOf<PracticeTask?>(null) }
    val run = remember { ScriptRun() }

    LaunchedEffect(Unit) {
        vm.moduleArg = null
        if (vm.tasksDone.size >= 3) vm.fire(com.nullsignal.core.story.Trigger.PY_TASKS)
    }
    DisposableEffect(Unit) { onDispose { run.stop() } }
    LaunchedEffect(code.text) {
        prefs.edit().putString("code", code.text).apply()
        delay(600)
        syntax = withContext(Dispatchers.Default) { MiniPy.syntax(code.text) }
    }

    val open = task
    if (open != null) {
        BackHandler { task = null }
        TaskView(vm, open, prefs) { task = null }
        return
    }

    fun start() {
        if (run.running) return
        val src = code.text
        tab = 1
        val py = MiniPy { run.append(it) }
        py.maxMillis = 60_000L
        py.maxSteps = 300_000_000L
        run.start(scope, cancel = { py.cancel() }, block = { py.run(src) })
    }

    ModuleScreen("PYTHON", onBack) {
        NsTabs(listOf("КОД", "ВЫВОД", "ЗАДАЧИ", "ПРИМЕРЫ"), tab, Ns.Red) { tab = it }
        when (tab) {
            0 -> {
                val shown = syntax ?: run.problem
                CodeEditor(code, { code = it }, Modifier.weight(1f).fillMaxWidth(), errorLine = shown?.line ?: 0)
                if (shown != null) {
                    Text(
                        (if (shown.line > 0) "строка ${shown.line}: " else "") + shown.message + (shown.hint?.let { " — $it" } ?: ""),
                        style = Ns.small.copy(color = Ns.Red), maxLines = 3, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                SymbolBar(code) { code = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermButton("▶ ЗАПУСТИТЬ", Modifier.weight(1f), color = Ns.Red, enabled = !run.running) { start() }
                    TermButton("СТЕРЕТЬ", color = Ns.Dim) { code = TextFieldValue(""); run.clear() }
                }
            }
            1 -> {
                ScriptOutput(run, Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermButton("← КОД", Modifier.weight(1f), color = Ns.Dim) { tab = 0 }
                    if (run.running) TermButton("■ СТОП", Modifier.weight(1f), color = Ns.Red) { run.stop() }
                    else TermButton("▶ ЕЩЁ РАЗ", Modifier.weight(1f), color = Ns.Red) { start() }
                }
            }
            2 -> {
                Text(
                    "Задачи проверяются сами: пишешь функцию — программа запускает её на примерах. Решено ${vm.tasksDone.size} из ${Practice.tasks.size}.",
                    style = Ns.small,
                )
                HudBar(vm.tasksDone.size.toFloat() / Practice.tasks.size, Ns.Red, Modifier.padding(vertical = 8.dp), height = 3.dp)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Practice.tasks, key = { it.id }) { t ->
                        val done = t.id in vm.tasksDone
                        Row(
                            Modifier.fillMaxWidth().hud(if (done) Ns.Faint else Ns.Dim).clickable { task = t }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (done) "✓" else "·", style = Ns.title.copy(color = if (done) Ns.Red else Ns.Dim), modifier = Modifier.padding(end = 10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = Ns.text.copy(color = if (done) Ns.Text2 else Ns.Green))
                                Text(t.statement, style = Ns.small, maxLines = 2)
                            }
                            if (!done) Text("+${t.reward}", style = Ns.label.copy(color = Ns.Red), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            else -> {
                Text("Нажми на пример — он откроется в редакторе. Текущий код при этом заменится.", style = Ns.small, modifier = Modifier.padding(bottom = 6.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PyExamples.list, key = { it.title }) { e ->
                        Column(
                            Modifier.fillMaxWidth().hud(Ns.Dim).clickable {
                                code = TextFieldValue(e.code)
                                run.clear()
                                tab = 0
                            }.padding(10.dp),
                        ) {
                            Text(e.title, style = Ns.text)
                            Text(e.about, style = Ns.small)
                        }
                    }
                    item {
                        Text(
                            "Есть модули: math, random, secrets, string, itertools, hashlib, base64, json, re, time, collections. " +
                                "Файлов и сети в песочнице нет — программа не может ничего испортить.",
                            style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TaskView(vm: LauncherViewModel, task: PracticeTask, prefs: SharedPreferences, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val key = "task_${task.id}"
    var code by remember(task.id) { mutableStateOf(TextFieldValue(prefs.getString(key, null) ?: task.starter)) }
    var result by remember(task.id) { mutableStateOf<CheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var fails by remember(task.id) { mutableIntStateOf(0) }
    var view by remember { mutableIntStateOf(0) } // 0 — код, 1 — результат
    var syntax by remember { mutableStateOf<PyProblem?>(null) }

    LaunchedEffect(code.text) {
        prefs.edit().putString(key, code.text).apply()
        delay(600)
        syntax = withContext(Dispatchers.Default) { MiniPy.syntax(code.text) }
    }

    ModuleScreen(task.title, onExit) {
        if (view == 0) {
            Panel(Modifier.padding(bottom = 6.dp), color = if (task.id in vm.tasksDone) Ns.Faint else Ns.Red) {
                Text(task.statement, style = Ns.text.copy(fontSize = 12.sp, lineHeight = 17.sp))
            }
            val shown = syntax ?: result?.problem
            CodeEditor(code, { code = it }, Modifier.weight(1f).fillMaxWidth(), errorLine = shown?.line ?: 0)
            if (syntax != null) {
                Text(
                    "строка ${syntax!!.line}: ${syntax!!.message}" + (syntax!!.hint?.let { " — $it" } ?: ""),
                    style = Ns.small.copy(color = Ns.Red), maxLines = 3, modifier = Modifier.padding(top = 4.dp),
                )
            }
            SymbolBar(code) { code = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermButton(if (checking) "ПРОВЕРЯЮ…" else "ПРОВЕРИТЬ", Modifier.weight(1f), color = Ns.Red, enabled = !checking) {
                    checking = true
                    val src = code.text
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Practice.check(task, src) }
                        result = r
                        checking = false
                        view = 1
                        if (r.passed) vm.completeTask(task) else fails++
                    }
                }
                TermButton("ЗАНОВО", color = Ns.Dim) { code = TextFieldValue(task.starter); result = null }
            }
        } else {
            val r = result
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (r != null) {
                    VerdictPanel(
                        if (r.passed) "РЕШЕНО ✓" else "ПОКА НЕТ",
                        if (r.passed) "все проверки пройдены · +${task.reward} узлов сети" else "посмотри, что не сошлось, и поправь код",
                        if (r.passed) Ns.Green else Ns.Red,
                    )
                    r.problem?.let { ProblemBox(it, Modifier.padding(top = 6.dp)) }
                    r.lines.forEach { l ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text(if (l.ok) "✓ " else "✗ ", style = Ns.text.copy(color = if (l.ok) Ns.Green else Ns.Red))
                            SelectionContainer { Text(l.text, style = CODE.copy(fontSize = 12.sp, color = if (l.ok) Ns.Text2 else Ns.Green)) }
                        }
                    }
                    if (r.output.isNotBlank()) {
                        SectionTitle("что напечатала программа")
                        Text(r.output.trimEnd().take(4000), style = CODE.copy(fontSize = 12.sp, color = Ns.Text2))
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermButton("← К КОДУ", Modifier.weight(1f), color = Ns.Dim) { view = 0 }
                if (r?.passed == true) {
                    TermButton("К ЗАДАЧАМ", Modifier.weight(1f), color = Ns.Red, onClick = onExit)
                } else if (fails >= 3) {
                    TermButton("ПОКАЗАТЬ РЕШЕНИЕ", Modifier.weight(1f), color = Ns.Amber) { code = TextFieldValue(task.solution); view = 0 }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
