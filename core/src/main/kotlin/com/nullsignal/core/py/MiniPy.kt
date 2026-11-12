@file:Suppress("UNCHECKED_CAST")

package com.nullsignal.core.py

import java.math.BigInteger

/** Что пошло не так: тип ошибки как в Python, объяснение по-русски и подсказка новичку. */
class PyProblem(val type: String, val message: String, val line: Int, val hint: String?, val calledFrom: Int = 0) {
    fun text(): String = buildString {
        if (line > 0) append("Строка ").append(line).append(" — ")
        append(type).append(": ").append(message)
        if (calledFrom > 0 && calledFrom != line) append("\n  функцию вызвали из строки ").append(calledFrom)
        if (hint != null) append("\n  ↳ ").append(hint)
    }
}

class PyResult(val problem: PyProblem?, val halted: String?, val steps: Long, val millis: Long) {
    val ok: Boolean get() = problem == null && halted == null
}

private val NO_KW: Map<String, Any> = emptyMap()
private const val MAX_DEPTH = 500

/**
 * Учебный интерпретатор Python: переменные, строки и f-строки, списки, словари, множества, кортежи, срезы,
 * if / for / while, функции и lambda, генераторы списков, try / except, модули math, random, itertools,
 * hashlib, base64, json, re, time. Без файлов и сети — это песочница.
 */
class MiniPy(private val out: (String) -> Unit = {}) {
    var maxSteps = 30_000_000L
    var maxMillis = 20_000L
    var maxOutput = 400_000

    /** false — time.sleep() не ждёт по-настоящему, а только двигает часы программы. */
    var realSleep = true

    @Volatile var cancelled = false

    /** Ответ на input(): null — ввода нет. */
    var input: ((String) -> String?)? = null
    val random = java.util.Random()

    internal val global = Scope(null, null)
    internal val builtins = HashMap<String, Any>()
    internal val modules = HashMap<String, PyModule>()
    internal val types = HashMap<String, PyType>()
    internal val digests = HashMap<String, java.security.MessageDigest>()
    internal var line = 0
    internal var steps = 0L
    internal var depth = 0
    internal var deadline = 0L
    internal var printed = 0
    internal var clockOffset = 0L
    internal var startNanos = 0L
    internal var slept = 0L
    private var currentExc: PyThrow? = null

    init {
        installBuiltins()
        installModules()
    }

    // ---------- для приложения ----------

    fun set(name: String, value: Any?) { global.vars[name] = wrap(value) }
    fun get(name: String): Any? = global.vars[name]

    /** Функция приложения, доступная в программе как встроенная. */
    fun def(name: String, fn: (List<Any>) -> Any?) { builtins[name] = PyBuiltin(name) { a, _ -> wrap(fn(a)) } }
    fun module(name: String, attrs: Map<String, Any?>) { modules[name] = PyModule(name, attrs.mapValues { wrap(it.value) }) }
    fun fn(name: String, f: (List<Any>) -> Any?): PyBuiltin = PyBuiltin(name) { a, _ -> wrap(f(a)) }
    fun fail(type: String, msg: String, hint: String? = null): Nothing = throw err(type, msg, hint)

    /** Сдвигает часы программы (time.time, time.perf_counter) — для учебных «медленных» систем. */
    fun advanceClock(nanos: Long) { clockOffset += nanos }
    fun cancel() { cancelled = true }

    /** Только проверка текста программы, без запуска. */
    fun check(src: String): PyProblem? = syntax(src)

    /** Запускает программу в отдельном потоке с большим стеком (для рекурсии) и ждёт результат. */
    fun run(src: String): PyResult {
        var result: PyResult? = null
        val t = Thread(null, { result = runHere(src) }, "minipy", 16L shl 20)
        t.start()
        while (true) {
            try { t.join(); break } catch (e: InterruptedException) { cancelled = true }
        }
        return result ?: PyResult(PyProblem("RuntimeError", "интерпретатор не запустился", 0, null), null, 0, 0)
    }

    private fun runHere(src: String): PyResult {
        val t0 = System.nanoTime()
        startNanos = t0
        steps = 0
        depth = 0
        line = 0
        printed = 0
        slept = 0
        deadline = t0 + maxMillis * 1_000_000
        var problem: PyProblem? = null
        var halted: String? = null
        try {
            execBlock(Parser(Lexer(src).lex()).file(), global)
        } catch (e: PySyntaxError) {
            problem = PyProblem("SyntaxError", e.message ?: "", e.line, e.hint)
        } catch (e: PyThrow) {
            problem = PyProblem(e.exc.type, e.exc.msg, e.line, e.hint, e.calledFrom)
        } catch (e: PyHalt) {
            if (e.reason != "exit") halted = e.reason
        } catch (e: StackOverflowError) {
            problem = PyProblem("RecursionError", "слишком глубокая рекурсия", line, "функция вызывает сама себя без остановки — проверь условие выхода")
        } catch (e: OutOfMemoryError) {
            problem = PyProblem("MemoryError", "не хватило памяти", line, "программа создаёт слишком большой список или строку")
        } catch (e: Exception) {
            problem = PyProblem("RuntimeError", "сбой интерпретатора: ${e.javaClass.simpleName}: ${e.message}", line, null)
        }
        return PyResult(problem, halted, steps, (System.nanoTime() - t0) / 1_000_000)
    }

    // ---------- служебное ----------

    internal fun err(type: String, msg: String, hint: String? = null) = PyThrow(PyExc(type, msg), line, hint)

    internal fun tick() {
        if (++steps and 0xFFF == 0L) slowCheck()
    }

    internal fun slowCheck() {
        if (cancelled) throw PyHalt("остановлено")
        if (steps > maxSteps) throw PyHalt("лимит шагов — похоже на бесконечный цикл")
        if (System.nanoTime() > deadline) throw PyHalt("время вышло (${maxMillis / 1000} с)")
    }

    internal fun emit(s: String) {
        printed += s.length
        if (printed > maxOutput) throw PyHalt("слишком много вывода")
        out(s)
    }

    internal fun typeOf(name: String, exc: Boolean = false): PyType = types.getOrPut(name) { PyType(name, exc) }

    internal fun asLong(v: Any, what: String): Long = when (v) {
        is Long -> v
        is Boolean -> if (v) 1L else 0L
        is BigInteger -> throw err("OverflowError", "$what: число слишком большое")
        is Double -> throw err("TypeError", "$what: нужно целое число, а здесь дробное ${fmtFloat(v)}", "преврати через int(...)")
        else -> throw err("TypeError", "$what: нужно целое число, а здесь ${typeName(v)}", if (v is String) "преврати строку в число: int(...)" else null)
    }

    internal fun asInt(v: Any, what: String): Int {
        val l = asLong(v, what)
        if (l > Int.MAX_VALUE || l < Int.MIN_VALUE) throw err("OverflowError", "$what: число слишком большое")
        return l.toInt()
    }

    internal fun asStr(v: Any, what: String): String =
        v as? String ?: throw err("TypeError", "$what: нужна строка, а здесь ${typeName(v)}", "преврати в строку: str(...)")

    internal fun hashable(v: Any): Any {
        if (v is ArrayList<*> || v is LinkedHashMap<*, *> || v is LinkedHashSet<*>) {
            throw err("TypeError", "${typeName(v)} нельзя использовать как ключ словаря или элемент множества", "возьми строку, число или кортеж: tuple(...)")
        }
        return v
    }

    internal fun iter(v: Any): Iterator<Any> = when (v) {
        is ArrayList<*> -> object : Iterator<Any> {
            var i = 0
            override fun hasNext() = i < v.size
            override fun next(): Any = v[i++]!!
        }
        is String -> object : Iterator<Any> {
            var i = 0
            override fun hasNext() = i < v.length
            override fun next(): Any = v[i++].toString()
        }
        is PyTuple -> v.items.iterator()
        is PyRange -> object : Iterator<Any> {
            var x = v.start
            override fun hasNext() = if (v.step > 0) x < v.stop else x > v.stop
            override fun next(): Any { tick(); val r = x; x += v.step; return r }
        }
        is LinkedHashMap<*, *> -> ArrayList<Any>(v.keys as Collection<Any>).iterator()
        is LinkedHashSet<*> -> ArrayList<Any>(v as Collection<Any>).iterator()
        is PyBytes -> object : Iterator<Any> {
            var i = 0
            override fun hasNext() = i < v.data.size
            override fun next(): Any = (v.data[i++].toInt() and 0xFF).toLong()
        }
        is PyIter -> v.it
        else -> throw err(
            "TypeError", "${typeName(v)} нельзя перебрать по элементам",
            when (v) {
                is Long -> "чтобы повторить $v раз, пиши: for i in range($v)"
                PyNone -> "значение равно None — возможно, функция ничего не вернула"
                else -> "перебирать можно список, строку, словарь, range(...)"
            },
        )
    }

    internal fun toList(v: Any): ArrayList<Any> {
        if (v is ArrayList<*>) return ArrayList(v as List<Any>)
        if (v is PyTuple) return ArrayList(v.items)
        if (v is PyRange && v.size > MAX_ITEMS) throw memErr()
        val out = ArrayList<Any>()
        for (x in iter(v)) {
            out.add(x)
            if (out.size and 0x3FF == 0) { tick(); if (out.size > MAX_ITEMS) throw memErr() }
        }
        return out
    }

    internal fun index(k: Any, size: Int, what: String): Int {
        if (k is String) {
            throw err("TypeError", "индекс $what должен быть числом, а здесь строка ${pyRepr(k)}", "элементы нумеруются с нуля: x[0], x[1]…; по строке ищут только в словаре")
        }
        val i = asLong(k, "индекс")
        val j = if (i < 0) i + size else i
        if (j < 0 || j >= size) {
            throw err("IndexError", "индекс $i за пределами $what (длина $size)", if (size == 0) "там пусто — брать нечего" else "допустимые индексы: от 0 до ${size - 1}")
        }
        return j.toInt()
    }

    private fun keyError(k: Any) = err("KeyError", pyRepr(k), "в словаре нет такого ключа: проверь через 'in' или возьми d.get(ключ)")

    internal fun getItem(o: Any, k: Any): Any = when (o) {
        is ArrayList<*> -> o[index(k, o.size, "списка")]!!
        is String -> o[index(k, o.length, "строки")].toString()
        is LinkedHashMap<*, *> -> o[k] ?: throw keyError(k)
        is PyTuple -> o.items[index(k, o.items.size, "кортежа")]
        is PyBytes -> (o.data[index(k, o.data.size, "байтов")].toInt() and 0xFF).toLong()
        is PyRange -> o.start + index(k, o.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "range") * o.step
        PyNone -> throw err("TypeError", "None нельзя индексировать", "значение равно None — возможно, функция ничего не вернула")
        else -> throw err("TypeError", "${typeName(o)} нельзя индексировать", "квадратные скобки работают со списком, строкой, словарём, кортежем")
    }

    internal fun setItem(o: Any, k: Any, v: Any) {
        when (o) {
            is ArrayList<*> -> (o as ArrayList<Any>)[index(k, o.size, "списка")] = v
            is LinkedHashMap<*, *> -> {
                if (o.size >= MAX_ITEMS) throw memErr()
                (o as LinkedHashMap<Any, Any>)[hashable(k)] = v
            }
            is String -> throw err("TypeError", "строку нельзя менять по частям", "собери новую: s = s[:i] + \"x\" + s[i+1:]")
            is PyTuple -> throw err("TypeError", "кортеж (tuple) менять нельзя", "нужен изменяемый — используй список: list(...)")
            else -> throw err("TypeError", "${typeName(o)} не поддерживает присваивание по индексу")
        }
    }

    private fun sliceIdx(lo: Any?, hi: Any?, st: Any?, len: Int): Triple<Int, Int, Int> {
        val step = if (st == null || st === PyNone) 1 else asLong(st, "шаг среза").coerceIn(-1_000_000_000L, 1_000_000_000L).toInt()
        if (step == 0) throw err("ValueError", "шаг среза не может быть 0")
        fun clamp(v: Any?, def: Int): Int {
            if (v == null || v === PyNone) return def
            var x = asLong(v, "граница среза")
            if (x < 0) x += len
            return if (step > 0) x.coerceIn(0L, len.toLong()).toInt() else x.coerceIn(-1L, len - 1L).toInt()
        }
        return if (step > 0) Triple(clamp(lo, 0), clamp(hi, len), step) else Triple(clamp(lo, len - 1), clamp(hi, -1), step)
    }

    internal fun slice(o: Any, lo: Any?, hi: Any?, st: Any?): Any {
        val len = when (o) {
            is String -> o.length
            is ArrayList<*> -> o.size
            is PyTuple -> o.items.size
            is PyBytes -> o.data.size
            is PyRange -> o.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else -> throw err("TypeError", "срез [a:b] не работает с ${typeName(o)}", "срезы берут у строки, списка, кортежа")
        }
        val (start, stop, step) = sliceIdx(lo, hi, st, len)
        if (step == 1) {
            val e = maxOf(start, stop)
            return when (o) {
                is String -> o.substring(start, e)
                is ArrayList<*> -> ArrayList<Any>((o as List<Any>).subList(start, e))
                is PyTuple -> PyTuple(ArrayList(o.items.subList(start, e)))
                is PyBytes -> PyBytes(o.data.copyOfRange(start, e))
                else -> { o as PyRange; PyRange(o.start + start * o.step, o.start + e * o.step, o.step) }
            }
        }
        val idx = ArrayList<Int>()
        var i = start
        while (if (step > 0) i < stop else i > stop) { idx.add(i); i += step }
        return when (o) {
            is String -> String(CharArray(idx.size) { o[idx[it]] })
            is ArrayList<*> -> ArrayList<Any>(idx.size).also { l -> idx.forEach { l.add(o[it]!!) } }
            is PyTuple -> PyTuple(idx.map { o.items[it] })
            is PyBytes -> PyBytes(ByteArray(idx.size) { o.data[idx[it]] })
            else -> { o as PyRange; ArrayList<Any>(idx.map { o.start + it * o.step }) }
        }
    }

    // ---------- имена ----------

    internal fun closest(name: String, candidates: Collection<String>): String? {
        candidates.firstOrNull { it != name && it.equals(name, ignoreCase = true) }?.let { return it }
        val max = if (name.length <= 4) 1 else 2
        var best: String? = null
        var bestD = max + 1
        for (c in candidates) {
            if (Math.abs(c.length - name.length) > max || c.startsWith("_")) continue
            val d = levenshtein(name, c)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun nameError(name: String, sc: Scope): PyThrow {
        val low = name.lowercase()
        val hint: String = when {
            low == "true" || low == "false" || low == "none" -> "в Python пишут с большой буквы: True, False, None"
            low == "null" || low == "nil" || low == "undefined" -> "в Python «ничего» — это None"
            low in setOf("println", "echo", "printf", "puts", "console", "cout", "write") -> "в Python на экран выводит print(...)"
            else -> {
                val names = HashSet<String>()
                var s: Scope? = sc
                while (s != null) { names += s.vars.keys; s = s.parent }
                names += builtins.keys
                closest(name, names)?.let { "может, ты имел в виду '$it'?" } ?: "если это текст, а не переменная — возьми его в кавычки: \"$name\""
            }
        }
        return err("NameError", "имя '$name' не определено", hint)
    }

    internal fun lookup(name: String, sc: Scope): Any {
        var s: Scope? = sc
        while (s != null) {
            val v = s.vars[name]
            if (v != null) return v
            if (s.globalDecl?.contains(name) == true) break
            if (s.locals != null && name in s.locals) {
                throw err(
                    "UnboundLocalError", "переменная '$name' используется раньше, чем ей присвоили значение",
                    if (global.vars.containsKey(name)) "внутри функции '$name' считается локальной, потому что ты ей присваиваешь; чтобы менять глобальную — добавь в начало функции: global $name"
                    else "сначала присвой значение, потом используй",
                )
            }
            s = s.parent
        }
        return global.vars[name] ?: builtins[name] ?: throw nameError(name, sc)
    }

    private fun store(name: String, v: Any, sc: Scope) {
        if (sc.globalDecl?.contains(name) == true) { global.vars[name] = v; return }
        if (sc.nonlocalDecl?.contains(name) == true) {
            var s = sc.parent
            while (s != null && s !== global) {
                if (s.vars.containsKey(name)) { s.vars[name] = v; return }
                s = s.parent
            }
            throw err("NameError", "nonlocal: во внешней функции нет переменной '$name'")
        }
        sc.vars[name] = v
    }

    private fun assign(t: Expr, v: Any, sc: Scope) {
        when (t) {
            is Name -> store(t.id, v, sc)
            is Index -> setItem(eval(t.obj, sc), eval(t.idx, sc), v)
            is TupleE -> unpack(t.items, v, sc)
            is ListE -> unpack(t.items, v, sc)
            is SliceE -> {
                val o = eval(t.obj, sc) as? ArrayList<Any> ?: throw err("TypeError", "присваивать срезу можно только у списка")
                val (a, b, st) = sliceIdx(t.lo?.let { eval(it, sc) }, t.hi?.let { eval(it, sc) }, t.step?.let { eval(it, sc) }, o.size)
                if (st != 1) throw err("ValueError", "присваивание срезу поддерживает только шаг 1")
                val items = toList(v)
                val sub = o.subList(a, maxOf(a, b))
                sub.clear()
                sub.addAll(items)
            }
            is Attr -> throw err("AttributeError", "нельзя менять '${t.name}' у ${typeName(eval(t.obj, sc))}")
            else -> throw err("SyntaxError", "слева от '=' должна быть переменная")
        }
    }

    private fun unpack(targets: List<Expr>, v: Any, sc: Scope) {
        val items = toList(v)
        val star = targets.indexOfFirst { it is Starred }
        if (star < 0) {
            if (items.size != targets.size) {
                throw err(
                    "ValueError", "распаковка: слева ${targets.size} переменных, а значений ${items.size}",
                    "слева и справа от '=' должно быть поровну: a, b = 1, 2",
                )
            }
            for (i in targets.indices) assign(targets[i], items[i], sc)
            return
        }
        val after = targets.size - star - 1
        if (items.size < targets.size - 1) throw err("ValueError", "распаковка: значений меньше, чем переменных")
        for (i in 0 until star) assign(targets[i], items[i], sc)
        assign((targets[star] as Starred).e, ArrayList(items.subList(star, items.size - after)), sc)
        for (i in 0 until after) assign(targets[star + 1 + i], items[items.size - after + i], sc)
    }

    // ---------- выражения ----------

    private fun cmp(op: String, a: Any, b: Any): Boolean = when (op) {
        "==" -> pyEq(a, b)
        "!=" -> !pyEq(a, b)
        "<" -> compare(a, b) < 0
        ">" -> compare(a, b) > 0
        "<=" -> compare(a, b) <= 0
        ">=" -> compare(a, b) >= 0
        "in" -> contains(b, a)
        "not in" -> !contains(b, a)
        "is" -> isSame(a, b)
        else -> !isSame(a, b)
    }

    internal fun eval(e: Expr, sc: Scope): Any = when (e) {
        is Const -> e.v
        is Name -> lookup(e.id, sc)
        is Bin -> bin(e.op, eval(e.l, sc), eval(e.r, sc))
        is Compare -> {
            var left = eval(e.first, sc)
            var ok = true
            for (i in e.ops.indices) {
                val right = eval(e.rest[i], sc)
                if (!cmp(e.ops[i], left, right)) { ok = false; break }
                left = right
            }
            ok
        }
        is BoolOp -> {
            val l = eval(e.l, sc)
            if (e.and) (if (truthy(l)) eval(e.r, sc) else l) else (if (truthy(l)) l else eval(e.r, sc))
        }
        is Unary -> unary(e.op, eval(e.e, sc))
        is Call -> call(e, sc)
        is Attr -> getAttr(eval(e.obj, sc), e.name)
        is Index -> getItem(eval(e.obj, sc), eval(e.idx, sc))
        is SliceE -> slice(eval(e.obj, sc), e.lo?.let { eval(it, sc) }, e.hi?.let { eval(it, sc) }, e.step?.let { eval(it, sc) })
        is IfExp -> if (truthy(eval(e.cond, sc))) eval(e.a, sc) else eval(e.b, sc)
        is ListE -> seq(e.items, sc)
        is TupleE -> PyTuple(seq(e.items, sc))
        is SetE -> LinkedHashSet<Any>().also { s -> for (x in seq(e.items, sc)) s.add(hashable(x)) }
        is DictE -> LinkedHashMap<Any, Any>().also { m -> for (i in e.keys.indices) m[hashable(eval(e.keys[i], sc))] = eval(e.values[i], sc) }
        is FStr -> fstr(e, sc)
        is Lambda -> PyFunc(
            "<lambda>", e.params, null, e.body, sc, e.params.defaults.map { d -> d?.let { eval(it, sc) } },
            e.params.names.toSet() + listOfNotNull(e.params.vararg, e.params.kwarg),
        )
        is Comp -> comp(e, sc)
        is Starred -> throw err("SyntaxError", "звёздочка * здесь не к месту")
    }

    private fun seq(items: List<Expr>, sc: Scope): ArrayList<Any> {
        val l = ArrayList<Any>(items.size)
        for (x in items) if (x is Starred) l.addAll(toList(eval(x.e, sc))) else l.add(eval(x, sc))
        return l
    }

    private fun fstr(e: FStr, sc: Scope): String {
        val sb = StringBuilder()
        for (p in e.parts) {
            if (p is String) { sb.append(p); continue }
            p as FPart
            var v = eval(p.e, sc)
            if (p.conv == 'r') v = pyRepr(v) else if (p.conv == 's') v = pyStr(v)
            sb.append(if (p.spec == null) pyStr(v) else formatValue(v, fstr(p.spec, sc)))
        }
        return sb.toString()
    }

    private fun comp(e: Comp, sc: Scope): Any {
        val inner = Scope(sc, null)
        return when (e.kind) {
            'l', 'g' -> {
                val out = ArrayList<Any>()
                compLoop(e, 0, inner) {
                    out.add(eval(e.elt, inner))
                    if (out.size > MAX_ITEMS) throw memErr()
                }
                if (e.kind == 'g') PyIter(out.iterator()) else out
            }
            's' -> LinkedHashSet<Any>().also { out -> compLoop(e, 0, inner) { out.add(hashable(eval(e.elt, inner))) } }
            else -> LinkedHashMap<Any, Any>().also { out -> compLoop(e, 0, inner) { out[hashable(eval(e.elt, inner))] = eval(e.value!!, inner) } }
        }
    }

    private fun compLoop(e: Comp, i: Int, sc: Scope, emit: () -> Unit) {
        val c = e.clauses[i]
        val it = iter(eval(c.iter, sc))
        outer@ while (it.hasNext()) {
            tick()
            assign(c.target, it.next(), sc)
            for (cond in c.ifs) if (!truthy(eval(cond, sc))) continue@outer
            if (i + 1 < e.clauses.size) compLoop(e, i + 1, sc, emit) else emit()
        }
    }

    private fun notCallable(v: Any, name: String?): PyThrow {
        val hint = when {
            name != null && builtins.containsKey(name) -> "выше в программе есть '$name = ...' — это затёрло встроенную функцию $name. Назови свою переменную иначе"
            v === PyNone -> "значение равно None — возможно, функция ничего не вернула"
            v is String || v is Long || v is Double -> "скобки после значения означают вызов функции; может, пропущен знак операции или запятая?"
            else -> null
        }
        return err("TypeError", "'${name ?: typeName(v)}' — это ${typeName(v)}, а не функция: вызвать нельзя", hint)
    }

    private fun call(e: Call, sc: Scope): Any {
        val fnE = e.fn
        val target: Any = if (fnE is Attr) eval(fnE.obj, sc) else eval(fnE, sc)
        val args: List<Any> = if (e.args.isEmpty()) emptyList() else seq(e.args, sc)
        var kw: Map<String, Any>? = null
        if (e.kw.isNotEmpty() || e.kwStar != null) {
            val m = LinkedHashMap<String, Any>()
            for ((k, v) in e.kw) m[k] = eval(v, sc)
            if (e.kwStar != null) {
                val d = eval(e.kwStar, sc) as? LinkedHashMap<*, *> ?: throw err("TypeError", "после ** должен стоять словарь")
                for ((k, v) in d) m[k as? String ?: throw err("TypeError", "ключи в **словаре должны быть строками")] = v!!
            }
            kw = m
        }
        tick()
        if (fnE is Attr) return callMethod(target, fnE.name, args, kw ?: NO_KW)
        if (target !is PyFunc && target !is PyBuiltin && target !is PyType && target !is PyMethod) throw notCallable(target, (fnE as? Name)?.id)
        return callValue(target, args, kw)
    }

    internal fun callValue(f: Any, args: List<Any>, kw: Map<String, Any>?): Any = when (f) {
        is PyFunc -> callFunc(f, args, kw)
        is PyBuiltin -> f.fn(args, kw ?: NO_KW)
        is PyType -> construct(f, args, kw ?: NO_KW)
        is PyMethod -> callMethod(f.self, f.name, args, kw ?: NO_KW)
        else -> throw notCallable(f, null)
    }

    private fun callFunc(f: PyFunc, args: List<Any>, kw: Map<String, Any>?): Any {
        val sc = Scope(f.closure, f.locals)
        val p = f.params
        val n = p.names.size
        for (i in 0 until minOf(args.size, n)) sc.vars[p.names[i]] = args[i]
        if (args.size > n) {
            if (p.vararg == null) throw err("TypeError", "${f.name}() принимает аргументов: $n, а передано: ${args.size}", "сверь вызов с заголовком def ${f.name}(${p.names.joinToString()})")
            sc.vars[p.vararg] = PyTuple(ArrayList(args.subList(n, args.size)))
        } else if (p.vararg != null) {
            sc.vars[p.vararg] = PyTuple(emptyList())
        }
        val extra: LinkedHashMap<Any, Any>? = if (p.kwarg != null) LinkedHashMap() else null
        if (kw != null) {
            for ((k, v) in kw) {
                if (k in p.names) {
                    if (sc.vars.containsKey(k)) throw err("TypeError", "${f.name}() получила значение для '$k' дважды")
                    sc.vars[k] = v
                } else if (extra != null) {
                    extra[k] = v
                } else {
                    throw err("TypeError", "у функции ${f.name}() нет параметра '$k'", closest(k, p.names)?.let { "может, '$it'?" })
                }
            }
        }
        if (extra != null) sc.vars[p.kwarg!!] = extra
        for (j in 0 until n) {
            if (!sc.vars.containsKey(p.names[j])) {
                sc.vars[p.names[j]] = f.defaults[j]
                    ?: throw err("TypeError", "функции ${f.name}() не передали аргумент '${p.names[j]}'", "вызов должен совпадать с заголовком: def ${f.name}(${p.names.joinToString()})")
            }
        }
        if (++depth > MAX_DEPTH) {
            depth--
            throw err("RecursionError", "слишком глубокая рекурсия: функция вызвала сама себя больше $MAX_DEPTH раз", "проверь условие, при котором рекурсия должна остановиться")
        }
        val saved = line
        try {
            if (f.expr != null) return eval(f.expr, sc)
            execBlock(f.body!!, sc)
            return sc.ret
        } catch (t: PyThrow) {
            if (t.calledFrom == 0) t.calledFrom = saved
            throw t
        } finally {
            depth--
            line = saved
        }
    }

    // ---------- инструкции ----------

    private fun execBlock(body: List<Stmt>, sc: Scope): Int {
        for (s in body) {
            val r = exec(s, sc)
            if (r != 0) return r
        }
        return 0
    }

    /** 0 — дальше, 1 — break, 2 — continue, 3 — return. */
    private fun exec(s: Stmt, sc: Scope): Int {
        line = s.line
        tick()
        when (s) {
            is ExprS -> eval(s.e, sc)
            is Assign -> {
                val v = eval(s.value, sc)
                for (t in s.targets) assign(t, v, sc)
            }
            is AugAssign -> when (val t = s.target) {
                is Name -> {
                    val old = lookup(t.id, sc)
                    val rhs = eval(s.value, sc)
                    if (s.op == "+" && old is ArrayList<*>) (old as ArrayList<Any>).addAll(toList(rhs)) else store(t.id, bin(s.op, old, rhs), sc)
                }
                is Index -> {
                    val o = eval(t.obj, sc)
                    val k = eval(t.idx, sc)
                    val old = getItem(o, k)
                    val rhs = eval(s.value, sc)
                    if (s.op == "+" && old is ArrayList<*>) (old as ArrayList<Any>).addAll(toList(rhs)) else setItem(o, k, bin(s.op, old, rhs))
                }
                else -> throw err("SyntaxError", "слева от ${s.op}= должна быть переменная")
            }
            is If -> return if (truthy(eval(s.cond, sc))) execBlock(s.body, sc) else execBlock(s.orelse, sc)
            is While -> {
                while (truthy(eval(s.cond, sc))) {
                    tick()
                    val r = execBlock(s.body, sc)
                    if (r == 1) return 0
                    if (r == 3) return 3
                }
                return execBlock(s.orelse, sc)
            }
            is For -> {
                val it = iter(eval(s.iter, sc))
                while (it.hasNext()) {
                    tick()
                    assign(s.target, it.next(), sc)
                    val r = execBlock(s.body, sc)
                    if (r == 1) return 0
                    if (r == 3) return 3
                }
                return execBlock(s.orelse, sc)
            }
            is Break -> return 1
            is Continue -> return 2
            is Pass -> {}
            is Return -> {
                sc.ret = if (s.e == null) PyNone else eval(s.e, sc)
                return 3
            }
            is Def -> store(s.name, PyFunc(s.name, s.params, s.body, null, sc, s.params.defaults.map { d -> d?.let { eval(it, sc) } }, s.locals), sc)
            is Import -> for ((m, alias) in s.names) store(alias ?: m, module(m), sc)
            is FromImport -> {
                val mod = module(s.module)
                if (s.names == null) {
                    for ((k, v) in mod.attrs) store(k, v, sc)
                } else {
                    for ((n, alias) in s.names) {
                        store(alias ?: n, mod.attrs[n] ?: throw err("ImportError", "в модуле ${s.module} нет '$n'", closest(n, mod.attrs.keys)?.let { "может, '$it'?" }), sc)
                    }
                }
            }
            is Global -> if (sc !== global) sc.globalDecl = (sc.globalDecl ?: HashSet()).also { it.addAll(s.names) }
            is Nonlocal -> sc.nonlocalDecl = (sc.nonlocalDecl ?: HashSet()).also { it.addAll(s.names) }
            is Try -> return execTry(s, sc)
            is Raise -> {
                if (s.e == null) throw currentExc ?: err("RuntimeError", "raise без ошибки можно писать только внутри except")
                when (val v = eval(s.e, sc)) {
                    is PyExc -> throw PyThrow(v, line)
                    is PyType -> if (v.isExc) throw PyThrow(PyExc(v.name, ""), line) else throw err("TypeError", "raise ждёт ошибку, например ValueError(\"текст\")")
                    else -> throw err("TypeError", "raise ждёт ошибку, например ValueError(\"текст\")")
                }
            }
            is Assert -> if (!truthy(eval(s.test, sc))) throw err("AssertionError", s.msg?.let { pyStr(eval(it, sc)) } ?: "проверка assert не прошла")
            is Del -> for (t in s.targets) del(t, sc)
        }
        return 0
    }

    private fun del(t: Expr, sc: Scope) {
        when (t) {
            is Name -> {
                val scope = if (sc.globalDecl?.contains(t.id) == true) global else sc
                if (scope.vars.remove(t.id) == null) throw nameError(t.id, sc)
            }
            is Index -> {
                val o = eval(t.obj, sc)
                val k = eval(t.idx, sc)
                when (o) {
                    is ArrayList<*> -> o.removeAt(index(k, o.size, "списка"))
                    is LinkedHashMap<*, *> -> if ((o as LinkedHashMap<Any, Any>).remove(k) == null) throw keyError(k)
                    else -> throw err("TypeError", "del не работает с ${typeName(o)}")
                }
            }
            is SliceE -> {
                val o = eval(t.obj, sc) as? ArrayList<*> ?: throw err("TypeError", "del со срезом работает только у списка")
                val (a, b, st) = sliceIdx(t.lo?.let { eval(it, sc) }, t.hi?.let { eval(it, sc) }, t.step?.let { eval(it, sc) }, o.size)
                if (st != 1) throw err("ValueError", "del со срезом поддерживает только шаг 1")
                if (b > a) o.subList(a, b).clear()
            }
            else -> throw err("SyntaxError", "так удалять нельзя: del работает с переменной, элементом списка или ключом словаря")
        }
    }

    private fun matches(h: Handler, t: PyThrow, sc: Scope): Boolean {
        val te = h.type ?: return true
        val v = eval(te, sc)
        for (x in if (v is PyTuple) v.items else listOf(v)) {
            if (x !is PyType || !x.isExc) throw err("TypeError", "после except должно стоять имя ошибки, например ValueError")
            if (isSubExc(t.exc.type, x.name)) return true
        }
        return false
    }

    private fun execTry(s: Try, sc: Scope): Int {
        var r = 0
        try {
            var bodyDone = false
            try {
                r = execBlock(s.body, sc)
                bodyDone = true
            } catch (t: PyThrow) {
                val h = s.handlers.firstOrNull { matches(it, t, sc) } ?: throw t
                if (h.name != null) store(h.name, t.exc, sc)
                val saved = currentExc
                currentExc = t
                try { r = execBlock(h.body, sc) } finally { currentExc = saved }
            }
            if (bodyDone && r == 0) r = execBlock(s.orelse, sc)
        } finally {
            if (s.final.isNotEmpty()) {
                val fr = execBlock(s.final, sc)
                if (fr != 0) r = fr
            }
        }
        return r
    }

    private fun module(name: String): PyModule = modules[name] ?: throw err(
        "ModuleNotFoundError", "модуля '$name' в песочнице нет",
        when (name.substringBefore('.')) {
            "os", "socket", "requests", "urllib", "subprocess", "threading", "http", "ssl", "shutil", "pathlib" ->
                "файлы и сеть в песочнице отключены; сетевые инструменты — в ТЕРМИНАЛЕ"
            "numpy", "pandas", "scapy", "nmap", "paramiko", "bs4", "flask", "django" -> "сторонних библиотек здесь нет — только стандартные"
            else -> closest(name, modules.keys)?.let { "может, '$it'?" } ?: ("доступны: " + modules.keys.sorted().joinToString())
        },
    )

    companion object {
        /** Проверка текста программы без запуска: null — синтаксис в порядке. */
        fun syntax(src: String): PyProblem? = try {
            Parser(Lexer(src).lex()).file()
            null
        } catch (e: PySyntaxError) {
            PyProblem("SyntaxError", e.message ?: "", e.line, e.hint)
        } catch (e: StackOverflowError) {
            PyProblem("SyntaxError", "слишком глубокая вложенность скобок", 0, null)
        }

        /** Значение из Kotlin → значение Python. */
        fun wrap(v: Any?): Any = when (v) {
            null, Unit -> PyNone
            is Int -> v.toLong()
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Float -> v.toDouble()
            is Char -> v.toString()
            is ByteArray -> PyBytes(v)
            is Pair<*, *> -> PyTuple(listOf(wrap(v.first), wrap(v.second)))
            is Triple<*, *, *> -> PyTuple(listOf(wrap(v.first), wrap(v.second), wrap(v.third)))
            is LinkedHashSet<*> -> LinkedHashSet<Any>().also { s -> v.forEach { s.add(wrap(it)) } }
            is Map<*, *> -> LinkedHashMap<Any, Any>().also { m -> v.forEach { (k, x) -> m[wrap(k)] = wrap(x) } }
            is Set<*> -> LinkedHashSet<Any>().also { s -> v.forEach { s.add(wrap(it)) } }
            is List<*> -> ArrayList<Any>(v.size).also { l -> v.forEach { l.add(wrap(it)) } }
            else -> v
        }
    }
}
