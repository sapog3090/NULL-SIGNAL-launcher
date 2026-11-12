package com.nullsignal.launcher.ui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.breach.Breach
import com.nullsignal.core.breach.BreachScript
import com.nullsignal.core.py.MiniPy
import com.nullsignal.core.py.PyProblem
import com.nullsignal.core.story.Trigger
import com.nullsignal.launcher.LauncherViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun BreachScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("breach", 0) }
    var round by remember { mutableIntStateOf(0) }
    val game = remember(round) { Breach() }
    // попытки показываем из своего списка: его меняет только главный поток, даже когда играет скрипт
    val attempts = remember(round) { mutableStateListOf<Breach.Attempt>() }
    var state by remember(round) { mutableStateOf(Breach.State.RUNNING) }
    var tab by remember { mutableIntStateOf(prefs.getInt("tab", 0)) }
    val run = remember { ScriptRun() }

    LaunchedEffect(state) { if (state == Breach.State.WON) vm.fire(Trigger.BREACH_WON) }
    DisposableEffect(Unit) { onDispose { run.stop() } }

    ModuleScreen("BREACH", onBack) {
        NsTabs(listOf("ВРУЧНУЮ", "СКРИПТОМ"), tab, Ns.Red) { tab = it; prefs.edit().putInt("tab", it).apply() }
        if (tab == 0) {
            ManualTab(game, attempts, state, prefs, run.running, onGuess = { state = game.state }, newRound = { round++ })
        } else {
            ScriptTab(
                game, attempts, state, prefs, run, scope,
                onDone = {
                    state = game.state
                    if (game.state == Breach.State.WON) vm.fire(Trigger.BREACH_SCRIPT)
                },
                newRound = { round++ },
            )
        }
    }
}

@Composable
private fun AttemptRow(i: Int, a: Breach.Attempt, length: Int) {
    Text(
        "${(i + 1).toString().padStart(2)}  ${a.guess.toList().joinToString(" ")}   " +
            "●".repeat(a.exact) + "○".repeat(a.misplaced) + "·".repeat(length - a.exact - a.misplaced),
        style = Ns.text.copy(fontSize = 16.sp),
    )
}

@Composable
private fun ResultBanner(state: Breach.State, secret: String) {
    if (state == Breach.State.RUNNING) return
    Text(
        if (state == Breach.State.WON) "ДОСТУП ПОЛУЧЕН ✓" else "БЛОКИРОВКА. ключ был $secret",
        style = Ns.title.copy(color = if (state == Breach.State.WON) Ns.Green else Ns.Red),
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** Что уже известно о ключе: сколько вариантов осталось и какие символы возможны на каждом месте. */
@Composable
private fun AnalysisPanel(a: Breach.Analysis?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).hud(Ns.Faint).padding(10.dp)) {
        if (a == null) {
            Text("считаю варианты…", style = Ns.small)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ПОДХОДЯЩИХ КЛЮЧЕЙ", style = Ns.label, modifier = Modifier.weight(1f))
            Text("${a.remaining}", style = Ns.title.copy(fontSize = 18.sp, color = if (a.remaining <= 10) Ns.Red else Ns.Green))
        }
        a.byPosition.forEachIndexed { i, set ->
            Row(Modifier.padding(top = 2.dp)) {
                Text("${i + 1}  ", style = CODE.copy(color = Ns.Dim))
                Text(
                    buildAnnotatedString {
                        for (c in Breach.ALPHABET) {
                            if (c in set) withStyle(SpanStyle(color = if (set.size == 1) Ns.Red else Ns.Green)) { append(c) }
                            else withStyle(SpanStyle(color = Ns.Faint)) { append('·') }
                            append(' ')
                        }
                    },
                    style = CODE,
                )
            }
        }
        Text(
            if (a.remaining == 1) "остался один вариант — он в таблице" else "строки — места в ключе, символы — что там ещё может стоять",
            style = Ns.small,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ColumnScope.ManualTab(
    game: Breach,
    attempts: SnapshotStateList<Breach.Attempt>,
    state: Breach.State,
    prefs: SharedPreferences,
    busy: Boolean,
    onGuess: () -> Unit,
    newRound: () -> Unit,
) {
    var input by remember(game) { mutableStateOf("") }
    var hints by remember { mutableStateOf(prefs.getBoolean("hints", true)) }
    var analysis by remember(game) { mutableStateOf<Breach.Analysis?>(null) }
    LaunchedEffect(game, attempts.size, hints, busy) {
        if (hints && !busy) analysis = withContext(Dispatchers.Default) { game.analyze() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("попыток: ${game.maxAttempts - attempts.size}", style = Ns.text.copy(color = Ns.Amber), modifier = Modifier.weight(1f))
        Chip(
            if (hints) "АНАЛИЗАТОР ВКЛ" else "АНАЛИЗАТОР ВЫКЛ", if (hints) Ns.Red else Ns.Dim,
            Modifier.clickable { hints = !hints; prefs.edit().putBoolean("hints", hints).apply() },
        )
    }
    Text("подбери ${game.length}-значный hex-ключ. ● — символ на своём месте, ○ — есть в ключе, но не там", style = Ns.small)
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        if (hints) item { AnalysisPanel(analysis) }
        items(attempts.size) { i -> AttemptRow(i, attempts[i], game.length) }
        item { ResultBanner(state, game.secret) }
    }
    if (busy) {
        Text("сейчас играет скрипт — дождись конца или останови его", style = Ns.text.copy(color = Ns.Amber), modifier = Modifier.padding(8.dp))
    } else if (state == Breach.State.RUNNING) {
        Text(
            input.padEnd(game.length, '_').toList().joinToString(" "),
            style = Ns.title.copy(fontSize = 26.sp, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
        val dead = if (hints) analysis?.impossible.orEmpty() else emptySet()
        val keys = Breach.ALPHABET.toList().map { it.toString() } + listOf("⌫", "⏎")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            keys.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { k ->
                        val color = when {
                            k == "⏎" -> Ns.Red
                            k.length == 1 && k[0] in dead -> Ns.Faint
                            else -> Ns.Green
                        }
                        Text(
                            k,
                            style = Ns.title.copy(textAlign = TextAlign.Center, color = color),
                            modifier = Modifier
                                .weight(1f)
                                .hud(if (color == Ns.Faint) Ns.Faint else Ns.Dim)
                                .clickable {
                                    when (k) {
                                        "⌫" -> input = input.dropLast(1)
                                        "⏎" -> game.guess(input)?.let { attempts += it; input = ""; onGuess() }
                                        else -> if (input.length < game.length) input += k
                                    }
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    } else {
        TermButton("НОВЫЙ КЛЮЧ", modifier = Modifier.fillMaxWidth()) { newRound() }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ColumnScope.ScriptTab(
    game: Breach,
    attempts: SnapshotStateList<Breach.Attempt>,
    state: Breach.State,
    prefs: SharedPreferences,
    run: ScriptRun,
    scope: CoroutineScope,
    onDone: () -> Unit,
    newRound: () -> Unit,
) {
    var code by remember { mutableStateOf(TextFieldValue(prefs.getString("script", null) ?: BreachScript.TEMPLATES[0].code)) }
    var view by remember { mutableIntStateOf(0) } // 0 — код, 1 — вывод
    var panel by remember { mutableIntStateOf(0) } // 0 — нет, 1 — справка, 2 — шаблоны
    var autoRun by remember { mutableStateOf(false) }
    var syntax by remember { mutableStateOf<PyProblem?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(code.text) {
        prefs.edit().putString("script", code.text).apply()
        delay(600)
        syntax = withContext(Dispatchers.Default) { MiniPy.syntax(code.text) }
    }

    fun start() {
        val src = code.text
        view = 1
        val script = BreachScript(game, { run.append(it) }, { at -> main.post { attempts += at } })
        run.start(scope, cancel = { script.cancel() }, block = { script.run(src) }) { onDone() }
    }

    fun launch() {
        if (run.running) return
        if (state == Breach.State.RUNNING) start() else { autoRun = true; newRound() }
    }
    LaunchedEffect(game) { if (autoRun) { autoRun = false; start() } }

    if (view == 0) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("ЧТО УМЕЕТ СКРИПТ", if (panel == 1) Ns.Red else Ns.Dim, Modifier.clickable { panel = if (panel == 1) 0 else 1 })
            Chip("ШАБЛОНЫ", if (panel == 2) Ns.Red else Ns.Dim, Modifier.clickable { panel = if (panel == 2) 0 else 2 })
        }
        if (panel == 1) {
            Panel(Modifier.padding(bottom = 6.dp)) {
                Text("Скрипт на Python играет вместо тебя. Попыток у него столько же, зато считает он без ошибок.", style = Ns.small)
                Text(BreachScript.API, style = CODE.copy(fontSize = 11.sp, lineHeight = 16.sp), modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (panel == 2) {
            BreachScript.TEMPLATES.forEach { t ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp).hud(Ns.Dim).clickable {
                        code = TextFieldValue(t.code)
                        panel = 0
                        run.clear()
                    }.padding(10.dp),
                ) {
                    Text(t.title, style = Ns.title.copy(fontSize = 15.sp))
                    Text(t.about, style = Ns.small)
                }
            }
        }
        val shown = syntax ?: run.problem
        CodeEditor(code, { code = it }, Modifier.weight(1f).fillMaxWidth(), errorLine = shown?.line ?: 0)
        if (shown != null) {
            Text(
                (if (shown.line > 0) "строка ${shown.line}: " else "") + shown.message + (shown.hint?.let { " — $it" } ?: ""),
                style = Ns.small.copy(color = Ns.Red), maxLines = 3, modifier = Modifier.padding(top = 4.dp),
            )
        }
        SymbolBar(code) { code = it }
        TermButton(if (state == Breach.State.RUNNING) "▶ ЗАПУСТИТЬ" else "▶ НОВЫЙ КЛЮЧ И ЗАПУСК", Modifier.fillMaxWidth(), color = Ns.Red, enabled = !run.running) { launch() }
    } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("попыток: ${attempts.size}/${game.maxAttempts}", Ns.Amber)
            attempts.forEach { a ->
                Chip(a.guess + " " + "●".repeat(a.exact) + "○".repeat(a.misplaced), if (a.exact == game.length) Ns.Red else Ns.Dim)
            }
        }
        ScriptOutput(run, Modifier.weight(1f).fillMaxWidth())
        if (!run.running) ResultBanner(state, game.secret)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TermButton("← КОД", Modifier.weight(1f), color = Ns.Dim) { view = 0 }
            if (run.running) TermButton("■ СТОП", Modifier.weight(1f), color = Ns.Red) { run.stop() }
            else TermButton("▶ ЕЩЁ РАЗ", Modifier.weight(1f), color = Ns.Red) { launch() }
        }
    }
    Spacer(Modifier.height(4.dp))
}
