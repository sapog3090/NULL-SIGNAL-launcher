@file:Suppress("UNCHECKED_CAST")

package com.nullsignal.core.py

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private val STR_METHODS = listOf(
    "upper", "lower", "capitalize", "title", "swapcase", "casefold", "strip", "lstrip", "rstrip", "split", "rsplit", "splitlines",
    "join", "replace", "find", "rfind", "index", "rindex", "count", "startswith", "endswith", "isdigit", "isnumeric", "isdecimal",
    "isalpha", "isalnum", "isspace", "isupper", "islower", "isascii", "zfill", "ljust", "rjust", "center", "format", "encode",
    "removeprefix", "removesuffix", "partition", "rpartition",
)
private val LIST_METHODS = listOf("append", "extend", "insert", "pop", "remove", "index", "count", "sort", "reverse", "copy", "clear")
private val DICT_METHODS = listOf("get", "keys", "values", "items", "pop", "popitem", "setdefault", "update", "copy", "clear", "most_common")
private val SET_METHODS = listOf(
    "add", "remove", "discard", "pop", "clear", "copy", "union", "intersection", "difference", "symmetric_difference",
    "update", "issubset", "issuperset", "isdisjoint",
)
private val TUPLE_METHODS = listOf("count", "index")
private val BYTES_METHODS = listOf("hex", "decode", "startswith", "endswith", "find", "count")
private val INT_METHODS = listOf("bit_length", "bit_count", "to_bytes")
private val FLOAT_METHODS = listOf("is_integer")

/** Привычки из других языков — и как это пишется в Python. */
private val ALIEN = mapOf(
    "push" to "в Python это append(...)", "length" to "длина — это функция: len(x)", "size" to "длина — это функция: len(x)",
    "len" to "len — функция, а не метод: len(x)", "toUpperCase" to "в Python это upper()", "toLowerCase" to "в Python это lower()",
    "trim" to "в Python это strip()", "contains" to "вхождение проверяет оператор in: x in список",
    "includes" to "вхождение проверяет оператор in: x in список", "has" to "вхождение проверяет оператор in",
    "indexOf" to "в Python это find(...) или index(...)", "substring" to "часть строки берут срезом: s[начало:конец]",
    "substr" to "часть строки берут срезом: s[начало:конец]", "slice" to "часть берут срезом: x[начало:конец]",
    "charAt" to "символ берут по индексу: s[i]", "equals" to "сравнение — это ==", "toString" to "в строку превращает str(x)",
    "has_key" to "ключ проверяет оператор in: ключ in словарь", "forEach" to "перебор — это цикл: for x in список:",
    "delete" to "удаляют так: del x[i] или x.remove(значение)", "put" to "в словарь кладут так: d[ключ] = значение",
    "first" to "первый элемент: x[0]", "last" to "последний элемент: x[-1]", "isEmpty" to "пустоту проверяют так: if not x:",
    "sorted" to "есть метод sort() и функция sorted(x)", "reversed" to "есть метод reverse() и срез x[::-1]",
)

private fun methodsOf(o: Any): List<String> = when (o) {
    is String -> STR_METHODS
    is ArrayList<*> -> LIST_METHODS
    is LinkedHashMap<*, *> -> DICT_METHODS
    is LinkedHashSet<*> -> SET_METHODS
    is PyTuple -> TUPLE_METHODS
    is PyBytes -> BYTES_METHODS
    is Long, is BigInteger -> INT_METHODS
    is Double -> FLOAT_METHODS
    is PyObj -> o.methods.keys.toList()
    else -> emptyList()
}

internal fun MiniPy.attrError(o: Any, name: String): PyThrow {
    val hint = when {
        o === PyNone -> "значение равно None: либо функция ничего не вернула (нет return), либо это результат метода вроде sort() или append() — они меняют список на месте и возвращают None"
        ALIEN.containsKey(name) -> ALIEN[name]
        o is String && name in LIST_METHODS -> "$name() — метод списка, а здесь строка; строку нельзя менять на месте, собирай новую через +"
        o is ArrayList<*> && name in STR_METHODS -> "$name() — метод строки, а здесь список: примени к элементу, например x[0].$name()"
        o is LinkedHashMap<*, *> && name in LIST_METHODS -> "$name() — метод списка, а здесь словарь: в словарь кладут так: d[ключ] = значение"
        else -> closest(name, methodsOf(o))?.let { "может, ты имел в виду '$it'?" }
    }
    return err("AttributeError", "у ${typeName(o)} нет '$name'", hint)
}

internal fun MiniPy.need(fn: String, a: List<Any>, min: Int, max: Int = min) {
    if (a.size < min || a.size > max) {
        throw err("TypeError", "$fn() ждёт аргументов: ${if (min == max) "$min" else "от $min до $max"}, а передано: ${a.size}")
    }
}

internal fun MiniPy.arg(a: List<Any>, i: Int, fn: String): Any = a.getOrNull(i) ?: throw err("TypeError", "$fn(): не хватает аргумента №${i + 1}")

internal fun MiniPy.charset(v: Any?): Charset {
    if (v == null || v === PyNone) return Charsets.UTF_8
    return when (val n = asStr(v, "кодировка").lowercase().replace("_", "-")) {
        "utf-8", "utf8" -> Charsets.UTF_8
        "ascii", "us-ascii" -> Charsets.US_ASCII
        "latin-1", "latin1", "iso-8859-1" -> Charsets.ISO_8859_1
        "utf-16" -> Charsets.UTF_16
        "cp1251", "windows-1251" -> Charset.forName("windows-1251")
        else -> try { Charset.forName(n) } catch (e: Exception) { throw err("LookupError", "неизвестная кодировка '$n'", "обычно нужна 'utf-8'") }
    }
}

internal fun MiniPy.decode(data: ByteArray, enc: Any?, errors: Any?): String {
    val cs = charset(enc)
    val mode = if (errors == null || errors === PyNone) "strict" else asStr(errors, "errors")
    if (mode != "strict") return String(data, cs).let { if (mode == "ignore") it.replace("�", "") else it }
    return try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
    } catch (e: CharacterCodingException) {
        throw err("UnicodeDecodeError", "эти байты — не текст в кодировке ${cs.name().lowercase()}", "посмотри на них через .hex() или попробуй .decode(\"latin-1\")")
    }
}

internal fun MiniPy.bytesOf(v: Any, fn: String): ByteArray = when (v) {
    is PyBytes -> v.data
    is String -> throw err("TypeError", "$fn: нужны байты, а здесь строка", "строку сначала закодируй: текст.encode()")
    else -> throw err("TypeError", "$fn: нужны байты, а здесь ${typeName(v)}")
}

private fun MiniPy.intVal(v: Any, fn: String): Any = when (v) {
    is Long, is BigInteger -> v
    is Boolean -> if (v) 1L else 0L
    is Double -> throw err("TypeError", "$fn() работает только с целыми числами, а здесь дробное", "преврати через int(...)")
    else -> throw err("TypeError", "$fn() работает только с целыми числами, а здесь ${typeName(v)}", if (v is String) "преврати строку в число: int(...)" else null)
}

private fun MiniPy.radix(v: Any, fn: String, prefix: String, r: Int): String {
    val b = toBig(intVal(v, fn))
    return (if (b.signum() < 0) "-" else "") + prefix + b.abs().toString(r)
}

// ======================= создание значений: int(), str(), list()… =======================

private val FLOAT_RE = Regex("[+-]?(\\d+\\.?\\d*([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?)")

private fun MiniPy.parseInt(s0: String, base0: Int): Any {
    var s = s0.trim().replace("_", "")
    var neg = false
    if (s.startsWith("-")) { neg = true; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
    var base = base0
    val low = s.lowercase()
    if ((base == 16 || base == 0) && low.startsWith("0x")) { s = s.substring(2); base = 16 }
    else if ((base == 2 || base == 0) && low.startsWith("0b")) { s = s.substring(2); base = 2 }
    else if ((base == 8 || base == 0) && low.startsWith("0o")) { s = s.substring(2); base = 8 }
    else if (base == 0) base = 10
    if (base !in 2..36) throw err("ValueError", "основание системы счисления должно быть от 2 до 36")
    val v = (if (s.isEmpty() || s[0] == '-' || s[0] == '+') null else s.toBigIntegerOrNull(base)) ?: throw err(
        "ValueError", "нельзя превратить ${pyRepr(s0)} в целое число" + (if (base != 10) " по основанию $base" else ""),
        when {
            s0.isBlank() -> "строка пустая"
            base == 10 && FLOAT_RE.matches(s0.trim()) -> "это дробное число: сначала float(...), потом int(...)"
            base == 10 && s.all { it.isLetterOrDigit() } && s.any { it in "abcdefABCDEF" } -> "похоже на шестнадцатеричное: int(строка, 16)"
            else -> "в строке должны быть только цифры"
        },
    )
    return normInt(if (neg) v.negate() else v)
}

internal fun MiniPy.construct(t: PyType, a: List<Any>, kw: Map<String, Any>): Any {
    if (t.isExc) return PyExc(t.name, when (a.size) { 0 -> ""; 1 -> pyStr(a[0]); else -> pyRepr(PyTuple(a)) })
    return when (t.name) {
        "int" -> {
            if (a.isEmpty()) return 0L
            val v = a[0]
            val base = (a.getOrNull(1) ?: kw["base"])?.let { asInt(it, "основание") }
            if (base != null && v !is String) throw err("TypeError", "int() с основанием работает только со строкой")
            when (v) {
                is Long, is BigInteger -> v
                is Boolean -> if (v) 1L else 0L
                is Double -> {
                    if (v.isNaN() || v.isInfinite()) throw err("ValueError", "нельзя превратить ${fmtFloat(v)} в целое число")
                    if (Math.abs(v) < 9e18) v.toLong() else normInt(BigDecimal(v).toBigInteger())
                }
                is String -> parseInt(v, base ?: 10)
                is PyBytes -> parseInt(String(v.data, Charsets.ISO_8859_1), base ?: 10)
                else -> throw err("TypeError", "int() не умеет превращать ${typeName(v)} в число", if (v === PyNone) "значение равно None" else null)
            }
        }
        "float" -> {
            if (a.isEmpty()) return 0.0
            when (val v = a[0]) {
                is Double -> v
                is Long -> v.toDouble()
                is BigInteger -> v.toDouble()
                is Boolean -> if (v) 1.0 else 0.0
                is String -> {
                    val s = v.trim().replace("_", "")
                    when (s.lowercase().removePrefix("+")) {
                        "inf", "infinity" -> Double.POSITIVE_INFINITY
                        "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
                        "nan", "-nan" -> Double.NaN
                        else -> if (FLOAT_RE.matches(s)) s.toDouble() else throw err(
                            "ValueError", "нельзя превратить ${pyRepr(v)} в дробное число",
                            if (',' in v) "в Python дробная часть отделяется точкой, а не запятой: 3.14" else "в строке должно быть число, например \"3.14\"",
                        )
                    }
                }
                else -> throw err("TypeError", "float() не умеет превращать ${typeName(v)} в число")
            }
        }
        "str" -> when {
            a.isEmpty() -> ""
            a[0] is PyBytes && (a.size > 1 || kw.containsKey("encoding")) -> decode((a[0] as PyBytes).data, a.getOrNull(1) ?: kw["encoding"], kw["errors"])
            else -> pyStr(a[0])
        }
        "bool" -> if (a.isEmpty()) false else truthy(a[0])
        "list" -> if (a.isEmpty()) ArrayList<Any>() else toList(a[0])
        "tuple" -> PyTuple(if (a.isEmpty()) emptyList() else toList(a[0]))
        "set" -> LinkedHashSet<Any>().also { s -> if (a.isNotEmpty()) for (x in iter(a[0])) s.add(hashable(x)) }
        "dict" -> LinkedHashMap<Any, Any>().also { m ->
            if (a.isNotEmpty()) {
                val src = a[0]
                if (src is LinkedHashMap<*, *>) m.putAll(src as Map<Any, Any>)
                else for (p in iter(src)) {
                    val kv = toList(p)
                    if (kv.size != 2) throw err("ValueError", "dict() ждёт пары (ключ, значение)")
                    m[hashable(kv[0])] = kv[1]
                }
            }
            for ((k, v) in kw) m[k] = v
        }
        "bytes" -> when {
            a.isEmpty() -> PyBytes(ByteArray(0))
            a[0] is PyBytes -> a[0]
            a[0] is String -> {
                if (a.size < 2 && !kw.containsKey("encoding")) throw err("TypeError", "bytes() от строки требует кодировку", "пиши bytes(текст, \"utf-8\") или проще: текст.encode()")
                PyBytes((a[0] as String).toByteArray(charset(a.getOrNull(1) ?: kw["encoding"])))
            }
            a[0] is Long -> {
                val n = a[0] as Long
                if (n < 0 || n > MAX_TEXT) throw err("ValueError", "bytes(n): n должно быть от 0 до $MAX_TEXT")
                PyBytes(ByteArray(n.toInt()))
            }
            else -> {
                val items = toList(a[0])
                PyBytes(ByteArray(items.size) { i ->
                    val x = items[i]
                    if (x !is Long || x < 0 || x > 255) throw err("ValueError", "байт — это целое число от 0 до 255, а здесь ${pyRepr(x)}", "если число больше 255, возьми остаток: x % 256")
                    x.toByte()
                })
            }
        }
        "type" -> {
            need("type", a, 1)
            val v = a[0]
            if (v is PyExc) typeOf(v.type, true) else typeOf(typeName(v))
        }
        "object" -> PyObj("object", "<object>", emptyMap())
        else -> throw err("TypeError", "нельзя создать ${t.name}")
    }
}

// ======================= сортировка =======================

internal fun MiniPy.sortList(list: MutableList<Any>, kw: Map<String, Any>) {
    val key = kw["key"]?.takeIf { it !== PyNone }
    val reverse = kw["reverse"]?.let { truthy(it) } ?: false
    for (k in kw.keys) if (k != "key" && k != "reverse") throw err("TypeError", "у сортировки нет параметра '$k'", "бывают только key= и reverse=")
    if (key == null) {
        val c = Comparator<Any> { x, y -> compare(x, y) }
        list.sortWith(if (reverse) c.reversed() else c)
    } else {
        val keyed = ArrayList<Pair<Any, Any>>(list.size)
        for (x in list) keyed.add(Pair(callValue(key, listOf(x), null), x))
        val c = Comparator<Pair<Any, Any>> { x, y -> compare(x.first, y.first) }
        keyed.sortWith(if (reverse) c.reversed() else c)
        for (i in keyed.indices) list[i] = keyed[i].second
    }
}

private fun MiniPy.minMax(fn: String, a: List<Any>, kw: Map<String, Any>, sign: Int): Any {
    if (a.isEmpty()) throw err("TypeError", "$fn() ждёт хотя бы один аргумент")
    val items = if (a.size == 1) iter(a[0]) else a.iterator()
    val key = kw["key"]?.takeIf { it !== PyNone }
    if (!items.hasNext()) return kw["default"] ?: throw err("ValueError", "$fn() от пустого списка", "список пуст — выбирать не из чего; проверь его перед вызовом")
    var best = items.next()
    var bestK = if (key != null) callValue(key, listOf(best), null) else best
    while (items.hasNext()) {
        val x = items.next()
        val k = if (key != null) callValue(key, listOf(x), null) else x
        if (compare(k, bestK) * sign > 0) { best = x; bestK = k }
    }
    return best
}

// ======================= встроенные функции =======================

internal fun MiniPy.installBuiltins() {
    fun b(name: String, fn: (List<Any>, Map<String, Any>) -> Any) { builtins[name] = PyBuiltin(name, fn) }
    for (t in listOf("int", "float", "str", "bool", "list", "tuple", "dict", "set", "bytes", "type", "object")) builtins[t] = typeOf(t)
    for (e in EXC_PARENT.keys + "BaseException") builtins[e] = typeOf(e, true)

    b("print") { a, kw ->
        val sep = kw["sep"]?.let { if (it === PyNone) " " else asStr(it, "sep") } ?: " "
        val end = kw["end"]?.let { if (it === PyNone) "\n" else asStr(it, "end") } ?: "\n"
        val sb = StringBuilder()
        for (i in a.indices) {
            if (i > 0) sb.append(sep)
            sb.append(pyStr(a[i]))
        }
        emit(sb.append(end).toString())
        PyNone
    }
    b("input") { a, _ ->
        val prompt = a.getOrNull(0)?.let { pyStr(it) } ?: ""
        val provider = input ?: throw err("RuntimeError", "input() здесь не работает", "в песочнице нет ввода с клавиатуры: запиши значение прямо в переменную")
        if (prompt.isNotEmpty()) emit(prompt)
        val asked = System.nanoTime()
        val v = provider(prompt)
        deadline += System.nanoTime() - asked // пока человек печатает, время программы не идёт
        if (cancelled) throw PyHalt("остановлено")
        if (v == null) throw err("EOFError", "ввод закончился")
        emit(v + "\n")
        v
    }
    b("len") { a, _ ->
        need("len", a, 1)
        when (val v = a[0]) {
            is String -> v.length.toLong()
            is ArrayList<*> -> v.size.toLong()
            is PyTuple -> v.items.size.toLong()
            is Map<*, *> -> v.size.toLong()
            is Collection<*> -> v.size.toLong()
            is PyBytes -> v.data.size.toLong()
            is PyRange -> v.size
            else -> throw err(
                "TypeError", "у ${typeName(v)} нет длины",
                if (v is Long || v is Double) "len() считает элементы в строке, списке, словаре; для числа: len(str(x))" else "len() работает со строками, списками, словарями, кортежами",
            )
        }
    }
    b("range") { a, _ ->
        if (a.isEmpty() || a.size > 3) throw err("TypeError", "range() ждёт от 1 до 3 чисел: range(конец), range(начало, конец), range(начало, конец, шаг)")
        val n = a.map { asLong(it, "range()") }
        when (n.size) {
            1 -> PyRange(0, n[0], 1)
            2 -> PyRange(n[0], n[1], 1)
            else -> { if (n[2] == 0L) throw err("ValueError", "шаг range() не может быть 0"); PyRange(n[0], n[1], n[2]) }
        }
    }
    b("repr") { a, _ -> need("repr", a, 1); pyRepr(a[0]) }
    b("abs") { a, _ ->
        need("abs", a, 1)
        when (val v = a[0]) {
            is Long -> if (v == Long.MIN_VALUE) toBig(v).negate() else Math.abs(v)
            is Double -> Math.abs(v)
            is BigInteger -> v.abs()
            is Boolean -> if (v) 1L else 0L
            else -> throw err("TypeError", "abs() работает только с числами, а здесь ${typeName(v)}")
        }
    }
    b("round") { a, kw ->
        need("round", a, 1, 2)
        val nd = a.getOrNull(1) ?: kw["ndigits"] ?: PyNone
        when (val v = a[0]) {
            is Long, is BigInteger -> if (nd === PyNone || asLong(nd, "round()") >= 0) v else normInt(BigDecimal(toBig(v)).setScale(asInt(nd, "round()"), RoundingMode.HALF_EVEN).toBigInteger())
            is Boolean -> if (v) 1L else 0L
            is Double -> when {
                v.isNaN() || v.isInfinite() -> if (nd === PyNone) throw err("ValueError", "нельзя округлить ${fmtFloat(v)}") else v
                nd === PyNone -> normInt(BigDecimal(v).setScale(0, RoundingMode.HALF_EVEN).toBigInteger())
                else -> BigDecimal(v).setScale(asInt(nd, "round()").coerceIn(-300, 300), RoundingMode.HALF_EVEN).toDouble()
            }
            else -> throw err("TypeError", "round() работает только с числами, а здесь ${typeName(v)}", if (v is String) "сначала преврати строку в число: float(...)" else null)
        }
    }
    b("divmod") { a, _ -> need("divmod", a, 2); PyTuple(listOf(bin("//", a[0], a[1]), bin("%", a[0], a[1]))) }
    b("pow") { a, _ ->
        need("pow", a, 2, 3)
        if (a.size == 3 && a[2] !== PyNone) {
            val m = toBig(intVal(a[2], "pow"))
            if (m.signum() <= 0) throw err("ValueError", "pow(a, b, m): модуль m должен быть больше нуля")
            val e = toBig(intVal(a[1], "pow"))
            val base = toBig(intVal(a[0], "pow"))
            normInt(if (e.signum() < 0) base.modInverse(m).modPow(e.negate(), m) else base.modPow(e, m))
        } else {
            bin("**", a[0], a[1])
        }
    }
    b("hex") { a, _ -> need("hex", a, 1); radix(a[0], "hex", "0x", 16) }
    b("bin") { a, _ -> need("bin", a, 1); radix(a[0], "bin", "0b", 2) }
    b("oct") { a, _ -> need("oct", a, 1); radix(a[0], "oct", "0o", 8) }
    b("ord") { a, _ ->
        need("ord", a, 1)
        val s = a[0] as? String ?: throw err("TypeError", "ord() ждёт один символ в кавычках, а здесь ${typeName(a[0])}")
        if (s.codePointCount(0, s.length) != 1) throw err("TypeError", "ord() ждёт ровно один символ, а в строке их ${s.length}", "возьми один символ по индексу: ord(s[0])")
        s.codePointAt(0).toLong()
    }
    b("chr") { a, _ ->
        need("chr", a, 1)
        val n = asLong(a[0], "chr()")
        if (n < 0 || n > 0x10FFFF) throw err("ValueError", "chr(): код символа должен быть от 0 до 1114111")
        String(Character.toChars(n.toInt()))
    }
    b("sum") { a, kw ->
        need("sum", a, 1, 2)
        var acc: Any = a.getOrNull(1) ?: kw["start"] ?: 0L
        for (x in iter(a[0])) {
            if (x is String) throw err("TypeError", "sum() складывает числа, а в списке есть строка ${pyRepr(x)}", "строки склеивают так: \"\".join(список); числа из строк: sum(int(x) for x in список)")
            acc = bin("+", acc, x)
        }
        acc
    }
    b("min") { a, kw -> minMax("min", a, kw, -1) }
    b("max") { a, kw -> minMax("max", a, kw, 1) }
    b("sorted") { a, kw -> need("sorted", a, 1); toList(a[0]).also { sortList(it, kw) } }
    b("reversed") { a, _ -> need("reversed", a, 1); PyIter(toList(a[0]).also { it.reverse() }.iterator()) }
    b("enumerate") { a, kw ->
        need("enumerate", a, 1, 2)
        val it = iter(a[0])
        var i = (a.getOrNull(1) ?: kw["start"])?.let { asLong(it, "enumerate()") } ?: 0L
        PyIter(object : Iterator<Any> {
            override fun hasNext() = it.hasNext()
            override fun next(): Any = PyTuple(listOf(i++, it.next()))
        })
    }
    b("zip") { a, _ ->
        val its = a.map { iter(it) }
        PyIter(object : Iterator<Any> {
            override fun hasNext() = its.isNotEmpty() && its.all { it.hasNext() }
            override fun next(): Any = PyTuple(its.map { it.next() })
        })
    }
    b("map") { a, _ ->
        if (a.size < 2) throw err("TypeError", "map() ждёт функцию и список: map(функция, список)")
        val f = a[0]
        val its = a.drop(1).map { iter(it) }
        PyIter(object : Iterator<Any> {
            override fun hasNext() = its.all { it.hasNext() }
            override fun next(): Any = callValue(f, its.map { it.next() }, null)
        })
    }
    b("filter") { a, _ ->
        need("filter", a, 2)
        val f = a[0]
        val out = ArrayList<Any>()
        for (x in iter(a[1])) if (truthy(if (f === PyNone) x else callValue(f, listOf(x), null))) out.add(x)
        PyIter(out.iterator())
    }
    b("any") { a, _ -> need("any", a, 1); var r = false; for (x in iter(a[0])) if (truthy(x)) { r = true; break }; r }
    b("all") { a, _ -> need("all", a, 1); var r = true; for (x in iter(a[0])) if (!truthy(x)) { r = false; break }; r }
    b("iter") { a, _ -> need("iter", a, 1); PyIter(iter(a[0])) }
    b("next") { a, _ ->
        need("next", a, 1, 2)
        val it = (a[0] as? PyIter)?.it ?: throw err("TypeError", "next() работает с итератором", "сначала получи итератор: it = iter(список)")
        if (it.hasNext()) it.next() else a.getOrNull(1) ?: throw err("StopIteration", "итератор закончился")
    }
    b("isinstance") { a, _ ->
        need("isinstance", a, 2)
        val v = a[0]
        (if (a[1] is PyTuple) (a[1] as PyTuple).items else listOf(a[1])).any { t ->
            t is PyType && (t.name == "object" || typeName(v) == t.name || (t.name == "int" && v is Boolean) || (v is PyExc && t.isExc && isSubExc(v.type, t.name)))
        }
    }
    b("callable") { a, _ -> need("callable", a, 1); a[0].let { it is PyFunc || it is PyBuiltin || it is PyType || it is PyMethod } }
    b("id") { a, _ -> need("id", a, 1); System.identityHashCode(a[0]).toLong() }
    b("hash") { a, _ -> need("hash", a, 1); hashable(a[0]).hashCode().toLong() }
    b("format") { a, _ -> need("format", a, 1, 2); formatValue(a[0], a.getOrNull(1)?.let { asStr(it, "format()") } ?: "") }
    b("dir") { a, _ ->
        ArrayList<Any>(if (a.isEmpty()) global.vars.keys.sorted() else (a[0] as? PyModule)?.attrs?.keys?.sorted() ?: methodsOf(a[0]).sorted())
    }
    b("exit") { _, _ -> throw PyHalt("exit") }
    b("quit") { _, _ -> throw PyHalt("exit") }
    b("open") { _, _ -> throw err("PermissionError", "файлы в песочнице недоступны", "данные держи прямо в переменных программы") }
    b("help") { _, _ ->
        emit("Учебный Python внутри NULL//SIGNAL.\nМодули: " + modules.keys.sorted().joinToString() + "\nМетоды значения: dir(x). Подсказки по ошибкам появляются сами.\n")
        PyNone
    }
}

// ======================= атрибуты и методы =======================

internal fun MiniPy.getAttr(o: Any, name: String): Any = when (o) {
    is PyModule -> o.attrs[name] ?: throw err("AttributeError", "в модуле ${o.name} нет '$name'", closest(name, o.attrs.keys)?.let { "может, '$it'?" })
    is PyExc -> if (name == "args") PyTuple(if (o.msg.isEmpty()) emptyList() else listOf(o.msg)) else throw attrError(o, name)
    is PyType -> PyMethod(o, name)
    else -> if (name in methodsOf(o)) PyMethod(o, name) else throw attrError(o, name)
}

internal fun MiniPy.callMethod(o: Any, name: String, a: List<Any>, kw: Map<String, Any>): Any = when (o) {
    is String -> strMethod(o, name, a, kw)
    is ArrayList<*> -> listMethod(o as ArrayList<Any>, name, a, kw)
    is LinkedHashMap<*, *> -> dictMethod(o as LinkedHashMap<Any, Any>, name, a, kw)
    is PyModule -> callValue(getAttr(o, name), a, kw)
    is LinkedHashSet<*> -> setMethod(o as LinkedHashSet<Any>, name, a)
    is PyTuple -> when (name) {
        "count" -> o.items.count { pyEq(it, arg(a, 0, name)) }.toLong()
        "index" -> o.items.indexOfFirst { pyEq(it, arg(a, 0, name)) }.let { if (it < 0) throw err("ValueError", "в кортеже нет значения ${pyRepr(a[0])}") else it.toLong() }
        else -> throw attrError(o, name)
    }
    is PyBytes -> bytesMethod(o, name, a, kw)
    is PyObj -> (o.methods[name] ?: throw attrError(o, name))(a)
    is PyType -> typeMethod(o, name, a, kw)
    is Long, is BigInteger -> intMethod(o, name, a, kw)
    is Double -> if (name == "is_integer") (!o.isInfinite() && !o.isNaN() && o == Math.floor(o)) else throw attrError(o, name)
    else -> throw attrError(o, name)
}

private fun MiniPy.typeMethod(t: PyType, name: String, a: List<Any>, kw: Map<String, Any>): Any = when {
    t.name == "bytes" && name == "fromhex" -> {
        val h = asStr(arg(a, 0, name), "fromhex()").filter { !it.isWhitespace() }
        if (h.length % 2 != 0 || h.any { it.digitToIntOrNull(16) == null }) {
            throw err("ValueError", "fromhex(): нужны пары шестнадцатеричных цифр", "пример: bytes.fromhex(\"48656c6c6f\")")
        }
        PyBytes(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }
    t.name == "int" && name == "from_bytes" -> {
        val data = bytesOf(arg(a, 0, name), "from_bytes()")
        val order = (a.getOrNull(1) ?: kw["byteorder"])?.let { asStr(it, "byteorder") } ?: "big"
        normInt(BigInteger(1, if (order == "little") data.reversedArray() else data))
    }
    t.name == "dict" && name == "fromkeys" -> LinkedHashMap<Any, Any>().also { m -> for (k in iter(arg(a, 0, name))) m[hashable(k)] = a.getOrNull(1) ?: PyNone }
    a.isNotEmpty() && typeName(a[0]) == t.name -> callMethod(a[0], name, a.subList(1, a.size), kw)
    else -> throw err("AttributeError", "у типа ${t.name} нет '$name'")
}

private fun MiniPy.intMethod(o: Any, name: String, a: List<Any>, kw: Map<String, Any>): Any = when (name) {
    "bit_length" -> toBig(o).abs().bitLength().toLong()
    "bit_count" -> toBig(o).abs().bitCount().toLong()
    "to_bytes" -> {
        val len = (a.getOrNull(0) ?: kw["length"])?.let { asInt(it, "to_bytes()") } ?: 1
        val order = (a.getOrNull(1) ?: kw["byteorder"])?.let { asStr(it, "byteorder") } ?: "big"
        val b = toBig(o)
        if (b.signum() < 0) throw err("OverflowError", "to_bytes(): отрицательное число в байты не превратить")
        if (len < 0 || len > 4096 || b.bitLength() > len * 8) throw err("OverflowError", "to_bytes(): число не помещается в $len байт")
        val raw = b.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val out = ByteArray(len)
        System.arraycopy(raw, 0, out, len - raw.size, raw.size)
        PyBytes(if (order == "little") out.reversedArray() else out)
    }
    else -> throw attrError(o, name)
}

private fun MiniPy.find(s: String, a: List<Any>, name: String, last: Boolean): Int {
    val sub = asStr(arg(a, 0, name), "$name()")
    var st = a.getOrNull(1)?.takeIf { it !== PyNone }?.let { asLong(it, name) } ?: 0L
    var en = a.getOrNull(2)?.takeIf { it !== PyNone }?.let { asLong(it, name) } ?: s.length.toLong()
    if (st < 0) st = maxOf(0L, st + s.length)
    if (en < 0) en = maxOf(0L, en + s.length)
    en = minOf(en, s.length.toLong())
    if (st > en) return -1
    val part = s.substring(st.toInt(), en.toInt())
    val i = if (last) part.lastIndexOf(sub) else part.indexOf(sub)
    return if (i < 0) -1 else i + st.toInt()
}

private fun MiniPy.affix(s: String, v: Any, start: Boolean, name: String): Boolean = when (v) {
    is String -> if (start) s.startsWith(v) else s.endsWith(v)
    is PyTuple -> v.items.any { affix(s, it, start, name) }
    else -> throw err("TypeError", "$name() ждёт строку или кортеж строк, а здесь ${typeName(v)}")
}

private fun MiniPy.split(s: String, sep: Any, max: Int): ArrayList<Any> {
    val out = ArrayList<Any>()
    if (sep === PyNone) {
        var i = 0
        val n = s.length
        while (true) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) break
            if (max >= 0 && out.size >= max) { out.add(s.substring(i)); break }
            val st = i
            while (i < n && !s[i].isWhitespace()) i++
            out.add(s.substring(st, i))
        }
        return out
    }
    val d = asStr(sep, "split()")
    if (d.isEmpty()) throw err("ValueError", "пустой разделитель", "чтобы разбить строку на символы, используй list(строка)")
    out.addAll(if (max < 0) s.split(d) else s.split(d, limit = max + 1))
    return out
}

private fun MiniPy.rsplit(s: String, sep: Any, max: Int): ArrayList<Any> {
    if (max < 0 || sep === PyNone) return split(s, sep, -1).let { all ->
        if (max < 0 || all.size <= max + 1) all
        else ArrayList<Any>().apply { add(all.subList(0, all.size - max).joinToString(" ")); addAll(all.subList(all.size - max, all.size)) }
    }
    val d = asStr(sep, "rsplit()")
    if (d.isEmpty()) throw err("ValueError", "пустой разделитель")
    val rev = ArrayList<Any>()
    var end = s.length
    while (rev.size < max) {
        val i = if (end - d.length >= 0) s.lastIndexOf(d, end - d.length) else -1
        if (i < 0) break
        rev.add(s.substring(i + d.length, end))
        end = i
    }
    rev.add(s.substring(0, end))
    rev.reverse()
    return rev
}

private fun MiniPy.strMethod(s: String, name: String, a: List<Any>, kw: Map<String, Any>): Any {
    fun str(i: Int): String = asStr(arg(a, i, name), "$name()")
    fun optStr(i: Int): String? = a.getOrNull(i)?.takeIf { it !== PyNone }?.let { asStr(it, "$name()") }
    return when (name) {
        "upper" -> s.uppercase()
        "lower", "casefold" -> s.lowercase()
        "capitalize" -> if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1).lowercase()
        "title" -> {
            val sb = StringBuilder()
            var start = true
            for (c in s) {
                if (c.isLetter()) { sb.append(if (start) c.uppercaseChar() else c.lowercaseChar()); start = false } else { sb.append(c); start = true }
            }
            sb.toString()
        }
        "swapcase" -> String(CharArray(s.length) { val c = s[it]; if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar() })
        "strip" -> optStr(0)?.let { ch -> s.trim { it in ch } } ?: s.trim()
        "lstrip" -> optStr(0)?.let { ch -> s.trimStart { it in ch } } ?: s.trimStart()
        "rstrip" -> optStr(0)?.let { ch -> s.trimEnd { it in ch } } ?: s.trimEnd()
        "split" -> split(s, a.getOrNull(0) ?: kw["sep"] ?: PyNone, (a.getOrNull(1) ?: kw["maxsplit"])?.let { asInt(it, "maxsplit") } ?: -1)
        "rsplit" -> rsplit(s, a.getOrNull(0) ?: kw["sep"] ?: PyNone, (a.getOrNull(1) ?: kw["maxsplit"])?.let { asInt(it, "maxsplit") } ?: -1)
        "splitlines" -> ArrayList<Any>(s.split("\r\n", "\n", "\r")).also { if (it.isNotEmpty() && it.last() == "") it.removeAt(it.size - 1) }
        "join" -> {
            val sb = StringBuilder()
            var first = true
            for (x in iter(arg(a, 0, name))) {
                if (x !is String) {
                    throw err("TypeError", "join() склеивает только строки, а в списке есть ${typeName(x)}", "преврати элементы в строки: \", \".join(str(x) for x in список)")
                }
                if (!first) sb.append(s)
                sb.append(x)
                first = false
                if (sb.length > MAX_TEXT) throw memErr()
            }
            sb.toString()
        }
        "replace" -> {
            val old = str(0)
            val new = str(1)
            val cnt = a.getOrNull(2)?.let { asInt(it, "replace()") } ?: -1
            if (cnt < 0) {
                s.replace(old, new)
            } else {
                val sb = StringBuilder()
                var i = 0
                var done = 0
                while (done < cnt && old.isNotEmpty()) {
                    val j = s.indexOf(old, i)
                    if (j < 0) break
                    sb.append(s, i, j).append(new)
                    i = j + old.length
                    done++
                }
                sb.append(s, i, s.length).toString()
            }
        }
        "find" -> find(s, a, name, false).toLong()
        "rfind" -> find(s, a, name, true).toLong()
        "index", "rindex" -> {
            val i = find(s, a, name, name == "rindex")
            if (i < 0) throw err("ValueError", "подстрока ${pyRepr(a[0])} не найдена", "find() в таком случае вернёт -1, а index() — ошибку")
            i.toLong()
        }
        "count" -> {
            val sub = str(0)
            if (sub.isEmpty()) {
                (s.length + 1).toLong()
            } else {
                var c = 0L
                var i = s.indexOf(sub)
                while (i >= 0) { c++; i = s.indexOf(sub, i + sub.length) }
                c
            }
        }
        "startswith" -> affix(s, arg(a, 0, name), true, name)
        "endswith" -> affix(s, arg(a, 0, name), false, name)
        "isdigit", "isnumeric", "isdecimal" -> s.isNotEmpty() && s.all { it.isDigit() }
        "isalpha" -> s.isNotEmpty() && s.all { it.isLetter() }
        "isalnum" -> s.isNotEmpty() && s.all { it.isLetterOrDigit() }
        "isspace" -> s.isNotEmpty() && s.all { it.isWhitespace() }
        "isupper" -> s.any { it.isLetter() } && s.none { it.isLowerCase() }
        "islower" -> s.any { it.isLetter() } && s.none { it.isUpperCase() }
        "isascii" -> s.all { it.code < 128 }
        "zfill" -> {
            val w = asInt(arg(a, 0, name), "zfill()").coerceAtMost(MAX_TEXT)
            when {
                s.length >= w -> s
                s.startsWith("-") || s.startsWith("+") -> s.substring(0, 1) + "0".repeat(w - s.length) + s.substring(1)
                else -> "0".repeat(w - s.length) + s
            }
        }
        "ljust", "rjust", "center" -> {
            val w = asInt(arg(a, 0, name), "$name()").coerceAtMost(MAX_TEXT)
            val f = a.getOrNull(1)?.let { asStr(it, "$name()") } ?: " "
            if (f.length != 1) throw err("TypeError", "$name(): заполнитель должен быть одним символом")
            val pad = maxOf(0, w - s.length)
            when (name) {
                "ljust" -> s + f.repeat(pad)
                "rjust" -> f.repeat(pad) + s
                else -> { val l = pad / 2 + (pad and w and 1); f.repeat(l) + s + f.repeat(pad - l) }
            }
        }
        "format" -> strFormat(s, a, kw)
        "encode" -> PyBytes(s.toByteArray(charset(a.getOrNull(0) ?: kw["encoding"])))
        "removeprefix" -> s.removePrefix(str(0))
        "removesuffix" -> s.removeSuffix(str(0))
        "partition" -> {
            val sep = str(0)
            val i = s.indexOf(sep)
            if (i < 0) PyTuple(listOf(s, "", "")) else PyTuple(listOf(s.substring(0, i), sep, s.substring(i + sep.length)))
        }
        "rpartition" -> {
            val sep = str(0)
            val i = s.lastIndexOf(sep)
            if (i < 0) PyTuple(listOf("", "", s)) else PyTuple(listOf(s.substring(0, i), sep, s.substring(i + sep.length)))
        }
        else -> throw attrError(s, name)
    }
}

private fun MiniPy.listMethod(l: ArrayList<Any>, name: String, a: List<Any>, kw: Map<String, Any>): Any {
    when (name) {
        "append" -> {
            need("append", a, 1)
            if (l.size >= MAX_ITEMS) throw memErr()
            l.add(a[0])
        }
        "extend" -> {
            val items = toList(arg(a, 0, name))
            if (l.size + items.size > MAX_ITEMS) throw memErr()
            l.addAll(items)
        }
        "insert" -> {
            need("insert", a, 2)
            var i = asLong(a[0], "insert()")
            if (i < 0) i += l.size
            l.add(i.coerceIn(0L, l.size.toLong()).toInt(), a[1])
        }
        "pop" -> {
            if (l.isEmpty()) throw err("IndexError", "pop() из пустого списка", "проверь перед вызовом: if список:")
            return if (a.isEmpty()) l.removeAt(l.size - 1) else l.removeAt(index(a[0], l.size, "списка"))
        }
        "remove" -> {
            val i = l.indexOfFirst { pyEq(it, arg(a, 0, name)) }
            if (i < 0) throw err("ValueError", "в списке нет значения ${pyRepr(a[0])}", "remove() удаляет по значению; проверь через 'in' перед удалением")
            l.removeAt(i)
        }
        "index" -> {
            val i = l.indexOfFirst { pyEq(it, arg(a, 0, name)) }
            if (i < 0) throw err("ValueError", "в списке нет значения ${pyRepr(a[0])}", "проверь через 'in' перед поиском")
            return i.toLong()
        }
        "count" -> return l.count { pyEq(it, arg(a, 0, name)) }.toLong()
        "sort" -> sortList(l, kw)
        "reverse" -> l.reverse()
        "copy" -> return ArrayList(l)
        "clear" -> l.clear()
        else -> throw attrError(l, name)
    }
    return PyNone
}

private fun MiniPy.dictMethod(d: LinkedHashMap<Any, Any>, name: String, a: List<Any>, kw: Map<String, Any>): Any = when (name) {
    "get" -> d[arg(a, 0, name)] ?: a.getOrNull(1) ?: PyNone
    "keys" -> ArrayList<Any>(d.keys)
    "values" -> ArrayList<Any>(d.values)
    "items" -> ArrayList<Any>(d.size).also { l -> for ((k, v) in d) l.add(PyTuple(listOf(k, v))) }
    "pop" -> d.remove(arg(a, 0, name)) ?: a.getOrNull(1)
        ?: throw err("KeyError", pyRepr(a[0]), "такого ключа нет; чтобы не было ошибки, дай значение по умолчанию: d.pop(ключ, None)")
    "popitem" -> {
        val k = d.keys.lastOrNull() ?: throw err("KeyError", "словарь пуст")
        PyTuple(listOf(k, d.remove(k)!!))
    }
    "setdefault" -> {
        val k = hashable(arg(a, 0, name))
        d[k] ?: (a.getOrNull(1) ?: PyNone).also { d[k] = it }
    }
    "update" -> {
        if (a.isNotEmpty()) {
            val src = a[0]
            if (src is LinkedHashMap<*, *>) d.putAll(src as Map<Any, Any>)
            else for (p in iter(src)) {
                val kv = toList(p)
                if (kv.size != 2) throw err("ValueError", "update() ждёт словарь или пары (ключ, значение)")
                d[hashable(kv[0])] = kv[1]
            }
        }
        for ((k, v) in kw) d[k] = v
        PyNone
    }
    "copy" -> LinkedHashMap(d)
    "clear" -> { d.clear(); PyNone }
    "most_common" -> {
        val items = d.entries.map { PyTuple(listOf(it.key, it.value)) }.sortedWith { x, y -> compare(y.items[1], x.items[1]) }
        val n = a.getOrNull(0)?.takeIf { it !== PyNone }?.let { asInt(it, "most_common()") } ?: items.size
        ArrayList<Any>(items.take(n))
    }
    else -> throw attrError(d, name)
}

private fun MiniPy.setMethod(s: LinkedHashSet<Any>, name: String, a: List<Any>): Any {
    fun other(): Collection<Any> = arg(a, 0, name).let { if (it is LinkedHashSet<*>) it as Collection<Any> else toList(it) }
    return when (name) {
        "add" -> { if (s.size >= MAX_ITEMS) throw memErr(); s.add(hashable(arg(a, 0, name))); PyNone }
        "remove" -> { if (!s.remove(arg(a, 0, name))) throw err("KeyError", pyRepr(a[0]), "во множестве нет такого элемента; discard() удаляет без ошибки"); PyNone }
        "discard" -> { s.remove(arg(a, 0, name)); PyNone }
        "pop" -> { val x = s.firstOrNull() ?: throw err("KeyError", "pop() из пустого множества"); s.remove(x); x }
        "clear" -> { s.clear(); PyNone }
        "copy" -> LinkedHashSet(s)
        "union" -> LinkedHashSet(s).also { r -> for (x in a) r.addAll(toList(x)) }
        "intersection" -> LinkedHashSet(s).also { it.retainAll(other().toSet()) }
        "difference" -> LinkedHashSet(s).also { it.removeAll(other().toSet()) }
        "symmetric_difference" -> { val o = other().toSet(); LinkedHashSet<Any>().also { r -> for (x in s) if (x !in o) r.add(x); for (x in o) if (x !in s) r.add(x) } }
        "update" -> { for (x in a) for (y in toList(x)) s.add(hashable(y)); PyNone }
        "issubset" -> other().toSet().containsAll(s)
        "issuperset" -> s.containsAll(other())
        "isdisjoint" -> other().none { it in s }
        else -> throw attrError(s, name)
    }
}

private fun MiniPy.bytesMethod(o: PyBytes, name: String, a: List<Any>, kw: Map<String, Any>): Any {
    fun latin(b: ByteArray) = String(b, Charsets.ISO_8859_1)
    return when (name) {
        "hex" -> {
            val h = hex(o.data)
            val sep = a.getOrNull(0)?.let { asStr(it, "hex()") }
            if (sep == null) h else h.chunked(2).joinToString(sep)
        }
        "decode" -> decode(o.data, a.getOrNull(0) ?: kw["encoding"], a.getOrNull(1) ?: kw["errors"])
        "startswith" -> latin(o.data).startsWith(latin(bytesOf(arg(a, 0, name), "startswith()")))
        "endswith" -> latin(o.data).endsWith(latin(bytesOf(arg(a, 0, name), "endswith()")))
        "find" -> latin(o.data).indexOf(latin(bytesOf(arg(a, 0, name), "find()"))).toLong()
        "count" -> {
            val sub = latin(bytesOf(arg(a, 0, name), "count()"))
            val s = latin(o.data)
            var c = 0L
            var i = if (sub.isEmpty()) -1 else s.indexOf(sub)
            while (i >= 0) { c++; i = s.indexOf(sub, i + sub.length) }
            c
        }
        else -> throw attrError(o, name)
    }
}
