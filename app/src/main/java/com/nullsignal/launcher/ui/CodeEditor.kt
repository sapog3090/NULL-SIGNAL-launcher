package com.nullsignal.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.py.PyProblem
import com.nullsignal.core.py.PyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val CODE = TextStyle(fontFamily = Ns.Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Ns.Green)

private val PY_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "break", "continue", "def", "del", "elif", "else", "except", "finally",
    "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
)
private val PY_BUILTINS = setOf(
    "print", "len", "range", "int", "str", "float", "bool", "list", "dict", "set", "tuple", "bytes", "sum", "min", "max", "abs",
    "round", "sorted", "reversed", "enumerate", "zip", "map", "filter", "any", "all", "hex", "bin", "ord", "chr", "type",
    "isinstance", "input", "repr", "format", "guess", "score", "history", "attempts_left",
)

/** Подсветка Python: служебные слова — красным, строки — оранжевым, комментарии — серым, числа и встроенные функции — холодным серым. */
internal fun highlight(code: String): AnnotatedString {
    val b = AnnotatedString.Builder(code)
    val kw = SpanStyle(color = Ns.Red, fontWeight = FontWeight.Bold)
    val str = SpanStyle(color = Ns.Amber)
    val com = SpanStyle(color = Ns.Dim)
    val num = SpanStyle(color = Ns.Cyan)
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        when {
            c == '#' -> {
                val e = code.indexOf('\n', i).let { if (it < 0) n else it }
                b.addStyle(com, i, e)
                i = e
            }
            c == '"' || c == '\'' -> {
                val st = i
                val triple = code.startsWith("$c$c$c", i)
                i += if (triple) 3 else 1
                while (i < n) {
                    if (code[i] == '\\') { i += 2; continue }
                    if (triple) {
                        if (code.startsWith("$c$c$c", i)) { i += 3; break }
                    } else if (code[i] == c) {
                        i++
                        break
                    } else if (code[i] == '\n') {
                        break
                    }
                    i++
                }
                i = minOf(i, n)
                b.addStyle(str, st, i)
            }
            c.isDigit() -> {
                val st = i
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
                b.addStyle(num, st, i)
            }
            c == '_' || c.isLetter() -> {
                val st = i
                while (i < n && (code[i] == '_' || code[i].isLetterOrDigit())) i++
                val w = code.substring(st, i)
                if (w in PY_KEYWORDS) b.addStyle(kw, st, i) else if (w in PY_BUILTINS) b.addStyle(num, st, i)
            }
            else -> i++
        }
    }
    return b.toAnnotatedString()
}

private val HIGHLIGHT = VisualTransformation { TransformedText(highlight(it.text), OffsetMapping.Identity) }

/** После Enter переносит отступ с прошлой строки, а после «:» добавляет ещё 4 пробела. */
private fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val pos = new.selection.start
    if (new.text.length != old.text.length + 1 || !new.selection.collapsed || pos == 0 || new.text[pos - 1] != '\n') return new
    val prevEnd = pos - 1
    val prevStart = new.text.lastIndexOf('\n', prevEnd - 1) + 1
    val prev = new.text.substring(prevStart, prevEnd)
    var indent = prev.takeWhile { it == ' ' }
    if (prev.trimEnd().endsWith(":")) indent += "    "
    if (indent.isEmpty()) return new
    return TextFieldValue(new.text.substring(0, pos) + indent + new.text.substring(pos), TextRange(pos + indent.length))
}

/** Редактор кода: номера строк, подсветка, автоотступ, прокрутка в обе стороны. */
@Composable
fun CodeEditor(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier, errorLine: Int = 0) {
    val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val gutter = remember(lines, errorLine) {
        buildAnnotatedString {
            for (i in 1..lines) {
                val s = i.toString().padStart(3)
                if (i == errorLine) withStyle(SpanStyle(color = Ns.Red, fontWeight = FontWeight.Bold)) { append(s) } else append(s)
                if (i < lines) append('\n')
            }
        }
    }
    BoxWithConstraints(modifier.hud(Ns.Faint)) {
        val minW = maxWidth - 52.dp
        val minH = maxHeight - 16.dp
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text(gutter, style = CODE.copy(color = Ns.Dim), modifier = Modifier.padding(start = 6.dp, end = 8.dp))
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = value,
                    onValueChange = { onChange(autoIndent(value, it)) },
                    textStyle = CODE,
                    cursorBrush = SolidColor(Ns.Red),
                    visualTransformation = HIGHLIGHT,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.widthIn(min = minW).heightIn(min = minH).padding(end = 24.dp),
                )
            }
        }
    }
}

private val SYMBOLS = listOf("⇥", "⇤", ":", "(", ")", "\"", "=", "[", "]", "_", "#", ",", ".", "+", "-", "*", "/", "<", ">", "%", "{", "}", "'", "!")

private fun insert(v: TextFieldValue, s: String): TextFieldValue {
    val a = v.selection.min
    return TextFieldValue(v.text.substring(0, a) + s + v.text.substring(v.selection.max), TextRange(a + s.length))
}

private fun dedent(v: TextFieldValue): TextFieldValue {
    val pos = v.selection.min
    val ls = v.text.lastIndexOf('\n', pos - 1) + 1
    var n = 0
    while (n < 4 && ls + n < v.text.length && v.text[ls + n] == ' ') n++
    if (n == 0) return v
    return TextFieldValue(v.text.removeRange(ls, ls + n), TextRange(maxOf(ls, pos - n)))
}

/** Ряд символов над клавиатурой: на телефоне скобки и двоеточия спрятаны глубоко. ⇥ — отступ, ⇤ — убрать отступ. */
@Composable
fun SymbolBar(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SYMBOLS.forEach { k ->
            Text(
                k,
                style = Ns.text.copy(fontSize = 15.sp, textAlign = TextAlign.Center, color = if (k == "⇥" || k == "⇤") Ns.Red else Ns.Green),
                modifier = Modifier
                    .widthIn(min = 36.dp)
                    .hud(Ns.Faint)
                    .clickable { onChange(when (k) { "⇥" -> insert(value, "    "); "⇤" -> dedent(value); else -> insert(value, k) }) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

/** Состояние запуска программы: вывод копится в буфере и раз в 80 мс переносится на экран. */
class ScriptRun {
    var running by mutableStateOf(false)
        private set
    var text by mutableStateOf("")
        private set
    var problem by mutableStateOf<PyProblem?>(null)
        private set
    var halted by mutableStateOf<String?>(null)
        private set
    var stats by mutableStateOf("")
        private set
    /** Подсказка input(), на которую программа сейчас ждёт ответ; null — не ждёт. */
    var waiting by mutableStateOf<String?>(null)
        private set
    private val answers = java.util.concurrent.LinkedBlockingQueue<String>()
    private val buf = StringBuilder()
    private var cancel: (() -> Unit)? = null

    /** Вызывается из потока программы: ждёт строку от человека или остановку программы. */
    fun ask(prompt: String, cancelled: () -> Boolean): String? {
        answers.clear()
        waiting = prompt
        var v: String? = null
        while (v == null && !cancelled()) v = answers.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        waiting = null
        return v
    }

    fun answer(text: String) { answers.offer(text) }

    fun append(s: String) { synchronized(buf) { buf.append(s) } }

    private fun flush() {
        val s = synchronized(buf) { val t = buf.toString(); buf.setLength(0); t }
        if (s.isNotEmpty()) text = (text + s).takeLast(60_000)
    }

    fun start(scope: CoroutineScope, cancel: () -> Unit, block: () -> PyResult, onDone: (PyResult) -> Unit = {}) {
        if (running) return
        running = true
        text = ""
        problem = null
        halted = null
        stats = ""
        waiting = null
        answers.clear()
        synchronized(buf) { buf.setLength(0) }
        this.cancel = cancel
        val pump = scope.launch { while (true) { flush(); delay(80) } }
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            pump.cancel()
            flush()
            problem = r.problem
            halted = r.halted
            stats = "шагов: ${r.steps} · ${r.millis} мс"
            running = false
            onDone(r)
        }
    }

    fun stop() { cancel?.invoke() }

    fun clear() { if (!running) { text = ""; problem = null; halted = null; stats = "" } }
}

/** Поле для ответа на input(): видно, только пока программа ждёт ввод. */
@Composable
fun ScriptInput(run: ScriptRun) {
    if (run.waiting == null) return
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NsField(text, { text = it }, "программа ждёт ввод…", Modifier.weight(1f), onDone = { run.answer(text); text = "" })
        Text(
            "  ⏎",
            style = Ns.title.copy(color = Ns.Red),
            modifier = Modifier.clickable { run.answer(text); text = "" }.padding(horizontal = 6.dp),
        )
    }
}

/** Ошибка программы: тип, объяснение и подсказка. */
@Composable
fun ProblemBox(p: PyProblem, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().hud(Ns.Red).padding(10.dp)) {
        Text((if (p.line > 0) "СТРОКА ${p.line} · " else "") + p.type, style = Ns.label.copy(color = Ns.Red))
        Text(p.message, style = Ns.text.copy(color = Ns.Red), modifier = Modifier.padding(top = 2.dp))
        if (p.calledFrom > 0 && p.calledFrom != p.line) Text("функцию вызвали из строки ${p.calledFrom}", style = Ns.small)
        p.hint?.let { Text("↳ $it", style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** Вывод программы с автопрокруткой вниз. */
@Composable
fun ScriptOutput(run: ScriptRun, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(run.text.length, run.problem, run.halted) { scroll.scrollTo(Int.MAX_VALUE) }
    Column(modifier.hud(Ns.Faint).verticalScroll(scroll).padding(10.dp)) {
        if (run.running && run.text.isEmpty()) Text("выполняется…", style = Ns.small.copy(color = Ns.Amber))
        if (run.text.isNotEmpty()) SelectionContainer { Text(run.text.trimEnd('\n'), style = CODE) }
        run.problem?.let { ProblemBox(it, Modifier.padding(top = 6.dp)) }
        run.halted?.let { Text("■ остановлено: $it", style = Ns.text.copy(color = Ns.Amber), modifier = Modifier.padding(top = 6.dp)) }
        if (!run.running && run.problem == null && run.halted == null && run.stats.isNotEmpty() && run.text.isEmpty()) {
            Text("программа ничего не вывела — добавь print(...)", style = Ns.small)
        }
        if (run.stats.isNotEmpty()) Text(run.stats, style = Ns.label, modifier = Modifier.padding(top = 6.dp))
    }
}
