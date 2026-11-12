@file:Suppress("UNCHECKED_CAST")

package com.nullsignal.core.py

import java.math.BigInteger
import java.util.Base64
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

private typealias Fn = (List<Any>, Map<String, Any>) -> Any

private fun mod(name: String, vararg attrs: Pair<String, Any>) = PyModule(name, linkedMapOf(*attrs))
private fun f(name: String, fn: Fn): Pair<String, Any> = name to PyBuiltin(name, fn)

private fun MiniPy.num(v: Any, fn: String): Double = when (v) {
    is Long -> v.toDouble()
    is Double -> v
    is Boolean -> if (v) 1.0 else 0.0
    is BigInteger -> v.toDouble()
    else -> throw err("TypeError", "$fn() работает только с числами, а здесь ${typeName(v)}", if (v is String) "преврати строку в число: float(...)" else null)
}

private fun MiniPy.whole(d: Double, fn: String): Any {
    if (d.isNaN() || d.isInfinite()) throw err("ValueError", "$fn(): не число")
    return if (Math.abs(d) < 9e18) d.toLong() else normInt(java.math.BigDecimal(d).toBigInteger())
}

private fun MiniPy.bounded(n: Long): Long = when {
    n <= 0 -> throw err("ValueError", "пустой диапазон для случайного числа", "в randint(a, b) должно быть a <= b")
    n <= Int.MAX_VALUE -> random.nextInt(n.toInt()).toLong()
    else -> (random.nextLong() and Long.MAX_VALUE) % n
}

private fun MiniPy.pick(v: Any, fn: String): List<Any> {
    val l: List<Any> = when (v) {
        is ArrayList<*> -> v as List<Any>
        is PyTuple -> v.items
        else -> toList(v)
    }
    if (l.isEmpty()) throw err("IndexError", "$fn() из пустого списка", "выбирать не из чего — проверь список")
    return l
}

// ---------- json ----------

private fun MiniPy.jsonDump(v: Any, sb: StringBuilder, indent: Int, level: Int, ascii: Boolean) {
    fun nl(l: Int) { if (indent >= 0) sb.append('\n').append(" ".repeat(indent * l)) }
    when (v) {
        PyNone -> sb.append("null")
        is Boolean -> sb.append(if (v) "true" else "false")
        is Long, is BigInteger -> sb.append(v.toString())
        is Double -> sb.append(if (v.isNaN()) "NaN" else if (v.isInfinite()) (if (v > 0) "Infinity" else "-Infinity") else fmtFloat(v))
        is String -> {
            sb.append('"')
            for (c in v) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 32 || (ascii && c.code > 126) -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
            sb.append('"')
        }
        is ArrayList<*>, is PyTuple -> {
            val items = if (v is PyTuple) v.items else v as List<Any>
            if (items.isEmpty()) { sb.append("[]"); return }
            sb.append('[')
            for ((i, x) in items.withIndex()) {
                if (i > 0) sb.append(if (indent >= 0) "," else ", ")
                nl(level + 1)
                jsonDump(x, sb, indent, level + 1, ascii)
            }
            nl(level)
            sb.append(']')
        }
        is LinkedHashMap<*, *> -> {
            if (v.isEmpty()) { sb.append("{}"); return }
            sb.append('{')
            var i = 0
            for ((k, x) in v) {
                if (i++ > 0) sb.append(if (indent >= 0) "," else ", ")
                nl(level + 1)
                jsonDump(if (k is String) k else pyStr(k!!), sb, indent, level + 1, ascii)
                sb.append(": ")
                jsonDump(x!!, sb, indent, level + 1, ascii)
            }
            nl(level)
            sb.append('}')
        }
        else -> throw err("TypeError", "${typeName(v)} нельзя записать в JSON", "в JSON бывают только словари, списки, строки, числа, True/False и None")
    }
}

private class JsonIn(val s: String, val py: MiniPy) {
    var i = 0
    fun bad(msg: String): Nothing = throw py.err("ValueError", "это не JSON: $msg (позиция $i)", "в JSON строки в двойных кавычках, без запятой после последнего элемента")
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any {
        ws()
        if (i >= s.length) bad("текст закончился")
        return when (val c = s[i]) {
            '{' -> {
                i++
                val m = LinkedHashMap<Any, Any>()
                ws()
                if (i < s.length && s[i] == '}') { i++; return m }
                while (true) {
                    ws()
                    if (i >= s.length || s[i] != '"') bad("ключ должен быть строкой в двойных кавычках")
                    val k = string()
                    ws()
                    if (i >= s.length || s[i] != ':') bad("после ключа нужно двоеточие")
                    i++
                    m[k] = value()
                    ws()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == '}') { i++; return m }
                    bad("ожидалась запятая или }")
                }
                @Suppress("UNREACHABLE_CODE") m
            }
            '[' -> {
                i++
                val l = ArrayList<Any>()
                ws()
                if (i < s.length && s[i] == ']') { i++; return l }
                while (true) {
                    l.add(value())
                    ws()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == ']') { i++; return l }
                    bad("ожидалась запятая или ]")
                }
                @Suppress("UNREACHABLE_CODE") l
            }
            '"' -> string()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", PyNone)
            else -> {
                if (c != '-' && !c.isDigit()) bad("неожиданный символ '$c'")
                val st = i
                i++
                while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                val t = s.substring(st, i)
                if ('.' in t || 'e' in t || 'E' in t) t.toDoubleOrNull() ?: bad("неверное число $t") else normInt(t.toBigIntegerOrNull() ?: bad("неверное число $t"))
            }
        }
    }
    fun word(w: String, v: Any): Any { if (!s.startsWith(w, i)) bad("непонятное слово"); i += w.length; return v }
    fun string(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) bad("строка не закрыта")
            val c = s[i++]
            if (c == '"') return sb.toString()
            if (c != '\\') { sb.append(c); continue }
            if (i >= s.length) bad("строка не закрыта")
            when (val e = s[i++]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val h = if (i + 4 <= s.length) s.substring(i, i + 4).toIntOrNull(16) else null
                    sb.append((h ?: bad("неверный код \\u")).toChar())
                    i += 4
                }
                else -> sb.append(e)
            }
        }
    }
}

// ---------- re ----------

private fun MiniPy.pattern(p: Any, flags: Any?): Pattern {
    val src = asStr(p, "шаблон").replace("(?P<", "(?<").replace(Regex("\\(\\?P=(\\w+)\\)"), "\\\\k<$1>")
    val fl = if (flags == null || flags === PyNone) 0 else asInt(flags, "flags")
    var f = 0
    if (fl and 2 != 0) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    if (fl and 8 != 0) f = f or Pattern.MULTILINE
    if (fl and 16 != 0) f = f or Pattern.DOTALL
    return try {
        Pattern.compile(src, f)
    } catch (e: PatternSyntaxException) {
        throw err("ValueError", "ошибка в регулярном выражении: ${e.description}", "проверь скобки и экранирование; шаблоны удобно писать как r\"...\"")
    }
}

private fun MiniPy.matchObj(m: Matcher): PyObj {
    val r = m.toMatchResult()
    fun grp(i: Any): Any {
        val n = asInt(i, "group()")
        if (n < 0 || n > r.groupCount()) throw err("IndexError", "в совпадении нет группы $n", "группы — это скобки в шаблоне; их здесь ${r.groupCount()}")
        return r.group(n) ?: PyNone
    }
    return PyObj(
        "Match", "<re.Match ${pyRepr(r.group())}>",
        mapOf(
            "group" to { a -> if (a.isEmpty()) r.group() else if (a.size == 1) grp(a[0]) else PyTuple(a.map { grp(it) }) },
            "groups" to { _ -> PyTuple((1..r.groupCount()).map { r.group(it) ?: PyNone }) },
            "start" to { _ -> r.start().toLong() },
            "end" to { _ -> r.end().toLong() },
            "span" to { _ -> PyTuple(listOf(r.start().toLong(), r.end().toLong())) },
        ),
    )
}

// ---------- itertools ----------

private fun MiniPy.product(pools: List<List<Any>>): Iterator<Any> = object : Iterator<Any> {
    val idx = IntArray(pools.size)
    var done = pools.any { it.isEmpty() }
    override fun hasNext() = !done
    override fun next(): Any {
        if (done) throw NoSuchElementException()
        tick()
        val t = PyTuple(List(pools.size) { pools[it][idx[it]] })
        var k = pools.size - 1
        while (k >= 0) {
            if (++idx[k] < pools[k].size) break
            idx[k] = 0
            k--
        }
        if (k < 0) done = true
        return t
    }
}

private fun MiniPy.permutations(pool: List<Any>, r: Int): Iterator<Any> = iterator {
    val n = pool.size
    if (r in 0..n) {
        val indices = IntArray(n) { it }
        val cycles = IntArray(r) { n - it }
        yield(PyTuple(List(r) { pool[indices[it]] }))
        var go = n > 0
        while (go) {
            go = false
            var i = r - 1
            while (i >= 0) {
                cycles[i]--
                if (cycles[i] == 0) {
                    val first = indices[i]
                    System.arraycopy(indices, i + 1, indices, i, n - i - 1)
                    indices[n - 1] = first
                    cycles[i] = n - i
                    i--
                } else {
                    val j = n - cycles[i]
                    val t = indices[i]
                    indices[i] = indices[j]
                    indices[j] = t
                    tick()
                    yield(PyTuple(List(r) { pool[indices[it]] }))
                    go = true
                    break
                }
            }
        }
    }
}

private fun MiniPy.combinations(pool: List<Any>, r: Int): Iterator<Any> = iterator {
    val n = pool.size
    if (r in 0..n) {
        val indices = IntArray(r) { it }
        yield(PyTuple(List(r) { pool[indices[it]] }))
        while (true) {
            var i = r - 1
            while (i >= 0 && indices[i] == i + n - r) i--
            if (i < 0) break
            indices[i]++
            for (j in i + 1 until r) indices[j] = indices[j - 1] + 1
            tick()
            yield(PyTuple(List(r) { pool[indices[it]] }))
        }
    }
}

// ======================= модули =======================

internal fun MiniPy.installModules() {
    fun add(m: PyModule) { modules[m.name] = m }

    add(mod(
        "math",
        "pi" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI, "inf" to Double.POSITIVE_INFINITY, "nan" to Double.NaN,
        f("sqrt") { a, _ -> num(arg(a, 0, "sqrt"), "sqrt").let { if (it < 0) throw err("ValueError", "корень из отрицательного числа") else Math.sqrt(it) } },
        f("floor") { a, _ -> arg(a, 0, "floor").let { if (it is Long || it is BigInteger) it else whole(Math.floor(num(it, "floor")), "floor") } },
        f("ceil") { a, _ -> arg(a, 0, "ceil").let { if (it is Long || it is BigInteger) it else whole(Math.ceil(num(it, "ceil")), "ceil") } },
        f("trunc") { a, _ -> arg(a, 0, "trunc").let { if (it is Long || it is BigInteger) it else whole(num(it, "trunc").let { d -> if (d < 0) Math.ceil(d) else Math.floor(d) }, "trunc") } },
        f("fabs") { a, _ -> Math.abs(num(arg(a, 0, "fabs"), "fabs")) },
        f("exp") { a, _ -> Math.exp(num(arg(a, 0, "exp"), "exp")) },
        f("log") { a, _ ->
            val x = num(arg(a, 0, "log"), "log")
            if (x <= 0) throw err("ValueError", "логарифм берут только от положительного числа")
            if (a.size > 1) Math.log(x) / Math.log(num(a[1], "log")) else Math.log(x)
        },
        f("log2") { a, _ -> num(arg(a, 0, "log2"), "log2").let { if (it <= 0) throw err("ValueError", "логарифм берут только от положительного числа") else Math.log(it) / Math.log(2.0) } },
        f("log10") { a, _ -> num(arg(a, 0, "log10"), "log10").let { if (it <= 0) throw err("ValueError", "логарифм берут только от положительного числа") else Math.log10(it) } },
        f("sin") { a, _ -> Math.sin(num(arg(a, 0, "sin"), "sin")) },
        f("cos") { a, _ -> Math.cos(num(arg(a, 0, "cos"), "cos")) },
        f("tan") { a, _ -> Math.tan(num(arg(a, 0, "tan"), "tan")) },
        f("asin") { a, _ -> Math.asin(num(arg(a, 0, "asin"), "asin")) },
        f("acos") { a, _ -> Math.acos(num(arg(a, 0, "acos"), "acos")) },
        f("atan") { a, _ -> Math.atan(num(arg(a, 0, "atan"), "atan")) },
        f("atan2") { a, _ -> Math.atan2(num(arg(a, 0, "atan2"), "atan2"), num(arg(a, 1, "atan2"), "atan2")) },
        f("degrees") { a, _ -> Math.toDegrees(num(arg(a, 0, "degrees"), "degrees")) },
        f("radians") { a, _ -> Math.toRadians(num(arg(a, 0, "radians"), "radians")) },
        f("pow") { a, _ -> Math.pow(num(arg(a, 0, "pow"), "pow"), num(arg(a, 1, "pow"), "pow")) },
        f("hypot") { a, _ -> Math.sqrt(a.sumOf { num(it, "hypot").let { d -> d * d } }) },
        f("isnan") { a, _ -> num(arg(a, 0, "isnan"), "isnan").isNaN() },
        f("isinf") { a, _ -> num(arg(a, 0, "isinf"), "isinf").isInfinite() },
        f("gcd") { a, _ -> var g = BigInteger.ZERO; for (x in a) g = g.gcd(toBig(if (x is Boolean) (if (x) 1L else 0L) else x as? Long ?: x as? BigInteger ?: throw err("TypeError", "gcd() работает только с целыми числами"))); normInt(g) },
        f("factorial") { a, _ ->
            val n = asLong(arg(a, 0, "factorial"), "factorial()")
            if (n < 0) throw err("ValueError", "факториал отрицательного числа не существует")
            if (n > 3000) throw err("OverflowError", "факториал слишком большой")
            var r = BigInteger.ONE
            for (i in 2..n) r = r.multiply(BigInteger.valueOf(i))
            normInt(r)
        },
        f("isqrt") { a, _ ->
            val n = asLong(arg(a, 0, "isqrt"), "isqrt()")
            if (n < 0) throw err("ValueError", "корень из отрицательного числа")
            var r = Math.sqrt(n.toDouble()).toLong()
            while (r * r > n) r--
            while ((r + 1) * (r + 1) <= n) r++
            r
        },
        f("comb") { a, _ ->
            val n = asLong(arg(a, 0, "comb"), "comb()")
            val k = asLong(arg(a, 1, "comb"), "comb()")
            if (n < 0 || k < 0) throw err("ValueError", "comb(): числа должны быть неотрицательными")
            if (k > n) 0L else {
                if (n > 5000) throw err("OverflowError", "comb(): слишком большие числа")
                var r = BigInteger.ONE
                for (i in 1..minOf(k, n - k)) r = r.multiply(BigInteger.valueOf(n - i + 1)).divide(BigInteger.valueOf(i))
                normInt(r)
            }
        },
    ))

    add(mod(
        "random",
        f("random") { _, _ -> random.nextDouble() },
        f("randint") { a, _ -> need("randint", a, 2); val lo = asLong(a[0], "randint()"); lo + bounded(asLong(a[1], "randint()") - lo + 1) },
        f("randrange") { a, _ ->
            need("randrange", a, 1, 3)
            val r = if (a.size == 1) PyRange(0, asLong(a[0], "randrange()"), 1)
            else PyRange(asLong(a[0], "randrange()"), asLong(a[1], "randrange()"), a.getOrNull(2)?.let { asLong(it, "randrange()") } ?: 1L)
            if (r.step == 0L) throw err("ValueError", "шаг не может быть 0")
            r.start + bounded(r.size) * r.step
        },
        f("choice") { a, _ -> pick(arg(a, 0, "choice"), "choice").let { it[random.nextInt(it.size)] } },
        f("choices") { a, kw ->
            val l = pick(arg(a, 0, "choices"), "choices")
            val k = (a.getOrNull(1) ?: kw["k"])?.let { asInt(it, "choices()") } ?: 1
            if (k > MAX_ITEMS) throw memErr()
            ArrayList<Any>(k).also { out -> repeat(k) { out.add(l[random.nextInt(l.size)]) } }
        },
        f("shuffle") { a, _ ->
            val l = arg(a, 0, "shuffle") as? ArrayList<Any> ?: throw err("TypeError", "shuffle() перемешивает только список", "строку сначала преврати в список: list(строка)")
            java.util.Collections.shuffle(l, random)
            PyNone
        },
        f("sample") { a, kw ->
            val l = ArrayList(pick(arg(a, 0, "sample"), "sample"))
            val k = (a.getOrNull(1) ?: kw["k"])?.let { asInt(it, "sample()") } ?: throw err("TypeError", "sample() ждёт количество: sample(список, k)")
            if (k < 0 || k > l.size) throw err("ValueError", "sample(): нельзя выбрать $k разных элементов из ${l.size}")
            java.util.Collections.shuffle(l, random)
            ArrayList<Any>(l.subList(0, k))
        },
        f("uniform") { a, _ -> need("uniform", a, 2); val lo = num(a[0], "uniform"); lo + (num(a[1], "uniform") - lo) * random.nextDouble() },
        f("seed") { a, _ -> random.setSeed(a.getOrNull(0)?.let { if (it is Long) it else pyRepr(it).hashCode().toLong() } ?: System.nanoTime()); PyNone },
        f("getrandbits") { a, _ -> val k = asInt(arg(a, 0, "getrandbits"), "getrandbits()"); if (k < 0 || k > 4096) throw err("ValueError", "getrandbits(): от 0 до 4096 бит"); normInt(BigInteger(k, random)) },
        f("randbytes") { a, _ -> val n = asInt(arg(a, 0, "randbytes"), "randbytes()"); if (n < 0 || n > MAX_TEXT) throw memErr(); PyBytes(ByteArray(n).also { random.nextBytes(it) }) },
    ))

    add(mod(
        "itertools",
        f("product") { a, kw ->
            val rep = kw["repeat"]?.let { asInt(it, "repeat") } ?: 1
            if (rep < 0 || rep > 64) throw err("ValueError", "product(): repeat должен быть от 0 до 64")
            val pools = a.map { toList(it) as List<Any> }
            PyIter(product(List(pools.size * rep) { pools[it % pools.size] }))
        },
        f("permutations") { a, kw ->
            val pool = toList(arg(a, 0, "permutations"))
            PyIter(permutations(pool, (a.getOrNull(1) ?: kw["r"])?.takeIf { it !== PyNone }?.let { asInt(it, "permutations()") } ?: pool.size))
        },
        f("combinations") { a, kw ->
            PyIter(combinations(toList(arg(a, 0, "combinations")), asInt(a.getOrNull(1) ?: kw["r"] ?: throw err("TypeError", "combinations() ждёт размер: combinations(список, r)"), "combinations()")))
        },
        f("count") { a, kw ->
            var x: Any = a.getOrNull(0) ?: kw["start"] ?: 0L
            val step = a.getOrNull(1) ?: kw["step"] ?: 1L
            PyIter(iterator { while (true) { tick(); yield(x); x = bin("+", x, step) } })
        },
        f("cycle") { a, _ ->
            val l = toList(arg(a, 0, "cycle"))
            PyIter(iterator { while (l.isNotEmpty()) for (x in l) { tick(); yield(x) } })
        },
        f("repeat") { a, kw ->
            val x = arg(a, 0, "repeat")
            val n = (a.getOrNull(1) ?: kw["times"])?.let { asLong(it, "repeat()") } ?: -1L
            PyIter(iterator { var i = 0L; while (n < 0 || i < n) { tick(); yield(x); i++ } })
        },
        f("chain") { a, _ -> PyIter(iterator { for (src in a) for (x in iter(src)) yield(x) }) },
        f("islice") { a, _ ->
            need("islice", a, 2, 4)
            val src = iter(a[0])
            fun n(i: Int, d: Long) = a.getOrNull(i)?.takeIf { it !== PyNone }?.let { asLong(it, "islice()") } ?: d
            val start = if (a.size == 2) 0L else n(1, 0L)
            val stop = if (a.size == 2) n(1, Long.MAX_VALUE) else n(2, Long.MAX_VALUE)
            val step = n(3, 1L)
            if (start < 0 || stop < 0 || step < 1) throw err("ValueError", "islice(): границы не могут быть отрицательными, шаг — не меньше 1")
            PyIter(iterator {
                var i = 0L
                var next = start
                while (i < stop && src.hasNext()) {
                    val x = src.next()
                    if (i == next) { yield(x); next += step }
                    i++
                }
            })
        },
        f("accumulate") { a, _ ->
            val src = iter(arg(a, 0, "accumulate"))
            val fn = a.getOrNull(1)
            PyIter(iterator {
                if (src.hasNext()) {
                    var acc = src.next()
                    yield(acc)
                    while (src.hasNext()) {
                        val x = src.next()
                        acc = if (fn == null || fn === PyNone) bin("+", acc, x) else callValue(fn, listOf(acc, x), null)
                        yield(acc)
                    }
                }
            })
        },
    ))

    add(mod(
        "string",
        "ascii_lowercase" to "abcdefghijklmnopqrstuvwxyz", "ascii_uppercase" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "ascii_letters" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", "digits" to "0123456789",
        "hexdigits" to "0123456789abcdefABCDEF", "octdigits" to "01234567",
        "punctuation" to "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~", "whitespace" to " \t\n\r\u000B\u000C",
    ))

    fun hashObj(algo: String, jname: String, init: Any?): PyObj {
        val chunks = ArrayList<ByteArray>()
        init?.let { chunks.add(bytesOf(it, "$algo()")) }
        fun digest(): ByteArray {
            val md = digests.getOrPut(jname) { java.security.MessageDigest.getInstance(jname) }
            md.reset()
            for (c in chunks) md.update(c)
            return md.digest()
        }
        return PyObj(
            "hash", "<$algo hash>",
            mapOf(
                "update" to { a -> chunks.add(bytesOf(arg(a, 0, "update"), "update()")); PyNone },
                "hexdigest" to { _ -> hex(digest()) },
                "digest" to { _ -> PyBytes(digest()) },
            ),
        )
    }
    val algos = listOf("md5" to "MD5", "sha1" to "SHA-1", "sha224" to "SHA-224", "sha256" to "SHA-256", "sha384" to "SHA-384", "sha512" to "SHA-512")
    add(PyModule(
        "hashlib",
        LinkedHashMap<String, Any>().also { m ->
            for ((py, jname) in algos) m[py] = PyBuiltin(py) { a, _ -> hashObj(py, jname, a.getOrNull(0)) }
            m["new"] = PyBuiltin("new") { a, _ ->
                val n = asStr(arg(a, 0, "new"), "new()").lowercase().replace("-", "")
                val jname = algos.firstOrNull { it.first == n }?.second ?: throw err("ValueError", "неизвестный алгоритм '$n'", "есть: " + algos.joinToString { it.first })
                hashObj(n, jname, a.getOrNull(1))
            }
        },
    ))

    fun b64in(v: Any, fn: String): ByteArray = if (v is String) v.toByteArray(Charsets.ISO_8859_1) else bytesOf(v, fn)
    fun unb64(dec: Base64.Decoder, v: Any, fn: String): Any = try {
        PyBytes(dec.decode(String(b64in(v, fn), Charsets.ISO_8859_1).filter { !it.isWhitespace() }))
    } catch (e: IllegalArgumentException) {
        throw err("ValueError", "$fn: это не Base64", "в Base64 только латинские буквы, цифры, + и /, в конце бывает =")
    }
    add(mod(
        "base64",
        f("b64encode") { a, _ -> PyBytes(Base64.getEncoder().encode(bytesOf(arg(a, 0, "b64encode"), "b64encode()"))) },
        f("b64decode") { a, _ -> unb64(Base64.getDecoder(), arg(a, 0, "b64decode"), "b64decode()") },
        f("urlsafe_b64encode") { a, _ -> PyBytes(Base64.getUrlEncoder().encode(bytesOf(arg(a, 0, "urlsafe_b64encode"), "urlsafe_b64encode()"))) },
        f("urlsafe_b64decode") { a, _ -> unb64(Base64.getUrlDecoder(), arg(a, 0, "urlsafe_b64decode"), "urlsafe_b64decode()") },
        f("b16encode") { a, _ -> PyBytes(hex(bytesOf(arg(a, 0, "b16encode"), "b16encode()")).uppercase().toByteArray()) },
        f("b16decode") { a, _ -> callMethod(typeOf("bytes"), "fromhex", listOf(String(b64in(arg(a, 0, "b16decode"), "b16decode()"), Charsets.ISO_8859_1)), emptyMap()) },
    ))

    add(mod(
        "binascii",
        f("hexlify") { a, _ -> PyBytes(hex(bytesOf(arg(a, 0, "hexlify"), "hexlify()")).toByteArray()) },
        f("unhexlify") { a, _ -> callMethod(typeOf("bytes"), "fromhex", listOf(String(b64in(arg(a, 0, "unhexlify"), "unhexlify()"), Charsets.ISO_8859_1)), emptyMap()) },
        f("crc32") { a, _ -> java.util.zip.CRC32().also { it.update(bytesOf(arg(a, 0, "crc32"), "crc32()")) }.value },
    ))

    add(mod(
        "zlib",
        f("crc32") { a, _ -> java.util.zip.CRC32().also { it.update(bytesOf(arg(a, 0, "crc32"), "crc32()")) }.value },
        f("compress") { a, _ ->
            val d = java.util.zip.Deflater()
            d.setInput(bytesOf(arg(a, 0, "compress"), "compress()"))
            d.finish()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            PyBytes(out.toByteArray())
        },
        f("decompress") { a, _ ->
            val inf = java.util.zip.Inflater()
            inf.setInput(bytesOf(arg(a, 0, "decompress"), "decompress()"))
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            try {
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw java.util.zip.DataFormatException()
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TEXT) throw memErr()
                }
            } catch (e: java.util.zip.DataFormatException) {
                throw err("ValueError", "decompress(): это не сжатые zlib данные")
            } finally {
                inf.end()
            }
            PyBytes(out.toByteArray())
        },
    ))

    add(mod(
        "time",
        f("time") { _, _ -> System.currentTimeMillis() / 1000.0 + clockOffset / 1e9 },
        f("perf_counter") { _, _ -> (System.nanoTime() - startNanos + clockOffset) / 1e9 },
        f("monotonic") { _, _ -> (System.nanoTime() - startNanos + clockOffset) / 1e9 },
        f("time_ns") { _, _ -> System.currentTimeMillis() * 1_000_000 + clockOffset },
        f("sleep") { a, _ ->
            val sec = num(arg(a, 0, "sleep"), "sleep")
            if (sec < 0) throw err("ValueError", "sleep(): время не может быть отрицательным")
            val ms = (sec * 1000).toLong()
            if (!realSleep) {
                clockOffset += ms * 1_000_000
            } else {
                slept += ms
                if (slept > 120_000) throw PyHalt("слишком долгий sleep (больше двух минут суммарно)")
                deadline += ms * 1_000_000
                var left = ms
                while (left > 0) {
                    if (cancelled) throw PyHalt("остановлено")
                    val part = minOf(left, 50L)
                    try { Thread.sleep(part) } catch (e: InterruptedException) { throw PyHalt("остановлено") }
                    left -= part
                }
            }
            PyNone
        },
    ))

    add(mod(
        "json",
        f("dumps") { a, kw ->
            val sb = StringBuilder()
            val indent = kw["indent"]?.takeIf { it !== PyNone }?.let { asInt(it, "indent").coerceIn(0, 16) } ?: -1
            jsonDump(arg(a, 0, "dumps"), sb, indent, 0, kw["ensure_ascii"]?.let { truthy(it) } ?: true)
            sb.toString()
        },
        f("loads") { a, _ ->
            val src = arg(a, 0, "loads").let { if (it is PyBytes) String(it.data, Charsets.UTF_8) else asStr(it, "loads()") }
            val p = JsonIn(src, this)
            val v = p.value()
            p.ws()
            if (p.i < src.length) p.bad("после значения идёт лишний текст")
            v
        },
    ))

    add(mod(
        "re",
        "I" to 2L, "IGNORECASE" to 2L, "M" to 8L, "MULTILINE" to 8L, "S" to 16L, "DOTALL" to 16L,
        f("findall") { a, kw ->
            val m = pattern(arg(a, 0, "findall"), a.getOrNull(2) ?: kw["flags"]).matcher(asStr(arg(a, 1, "findall"), "findall()"))
            val out = ArrayList<Any>()
            while (m.find()) {
                tick()
                out.add(
                    when (m.groupCount()) {
                        0 -> m.group()
                        1 -> m.group(1) ?: ""
                        else -> PyTuple((1..m.groupCount()).map { m.group(it) ?: "" })
                    },
                )
                if (out.size > MAX_ITEMS) throw memErr()
            }
            out
        },
        f("search") { a, kw -> pattern(arg(a, 0, "search"), a.getOrNull(2) ?: kw["flags"]).matcher(asStr(arg(a, 1, "search"), "search()")).let { if (it.find()) matchObj(it) else PyNone } },
        f("match") { a, kw -> pattern(arg(a, 0, "match"), a.getOrNull(2) ?: kw["flags"]).matcher(asStr(arg(a, 1, "match"), "match()")).let { if (it.lookingAt()) matchObj(it) else PyNone } },
        f("fullmatch") { a, kw -> pattern(arg(a, 0, "fullmatch"), a.getOrNull(2) ?: kw["flags"]).matcher(asStr(arg(a, 1, "fullmatch"), "fullmatch()")).let { if (it.matches()) matchObj(it) else PyNone } },
        f("sub") { a, kw ->
            val repl = arg(a, 1, "sub")
            val s = asStr(arg(a, 2, "sub"), "sub()")
            val limit = (a.getOrNull(3) ?: kw["count"])?.let { asInt(it, "count") } ?: 0
            val m = pattern(arg(a, 0, "sub"), a.getOrNull(4) ?: kw["flags"]).matcher(s)
            val sb = StringBuilder()
            var last = 0
            var n = 0
            while ((limit == 0 || n < limit) && m.find()) {
                tick()
                sb.append(s, last, m.start())
                if (repl is String) {
                    var i = 0
                    while (i < repl.length) {
                        val c = repl[i]
                        if (c == '\\' && i + 1 < repl.length && repl[i + 1].isDigit()) {
                            val g = repl[i + 1] - '0'
                            if (g > m.groupCount()) throw err("IndexError", "в шаблоне нет группы $g")
                            sb.append(m.group(g) ?: "")
                            i += 2
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                } else {
                    sb.append(pyStr(callValue(repl, listOf(matchObj(m)), null)))
                }
                last = m.end()
                n++
                if (sb.length > MAX_TEXT) throw memErr()
            }
            sb.append(s, last, s.length).toString()
        },
        f("split") { a, kw ->
            val s = asStr(arg(a, 1, "split"), "split()")
            val limit = (a.getOrNull(2) ?: kw["maxsplit"])?.let { asInt(it, "maxsplit") } ?: 0
            val m = pattern(arg(a, 0, "split"), kw["flags"]).matcher(s)
            val out = ArrayList<Any>()
            var last = 0
            while ((limit == 0 || out.size < limit) && m.find()) {
                if (m.end() == m.start()) continue
                out.add(s.substring(last, m.start()))
                for (g in 1..m.groupCount()) out.add(m.group(g) ?: PyNone)
                last = m.end()
            }
            out.add(s.substring(last))
            out
        },
        f("escape") { a, _ -> asStr(arg(a, 0, "escape"), "escape()").let { s -> buildString { for (c in s) { if (!c.isLetterOrDigit() && c != '_' && c.code < 128) append('\\'); append(c) } } } },
    ))

    add(mod(
        "collections",
        f("Counter") { a, _ ->
            val m = LinkedHashMap<Any, Any>()
            if (a.isNotEmpty()) {
                val src = a[0]
                if (src is LinkedHashMap<*, *>) m.putAll(src as Map<Any, Any>)
                else for (x in iter(src)) { val k = hashable(x); m[k] = bin("+", m[k] ?: 0L, 1L) }
            }
            m
        },
        f("OrderedDict") { a, kw -> construct(typeOf("dict"), a, kw) },
        f("defaultdict") { _, _ -> throw err("NotImplementedError", "defaultdict в песочнице нет", "обычный словарь справится: d[k] = d.get(k, 0) + 1") },
    ))

    add(mod(
        "functools",
        f("reduce") { a, _ ->
            need("reduce", a, 2, 3)
            val it = iter(a[1])
            var acc = a.getOrNull(2) ?: if (it.hasNext()) it.next() else throw err("TypeError", "reduce() от пустого списка без начального значения")
            while (it.hasNext()) acc = callValue(a[0], listOf(acc, it.next()), null)
            acc
        },
    ))

    add(mod(
        "statistics",
        f("mean") { a, _ -> toList(arg(a, 0, "mean")).let { l -> if (l.isEmpty()) throw err("ValueError", "mean() от пустого списка") else l.sumOf { num(it, "mean") } / l.size } },
        f("median") { a, _ ->
            val l = toList(arg(a, 0, "median")).map { num(it, "median") }.sorted()
            if (l.isEmpty()) throw err("ValueError", "median() от пустого списка")
            if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2
        },
    ))

    val secure = java.security.SecureRandom()
    add(mod(
        "secrets",
        f("token_hex") { a, _ -> val n = a.getOrNull(0)?.let { asInt(it, "token_hex()") } ?: 32; if (n < 0 || n > 4096) throw err("ValueError", "token_hex(): от 0 до 4096 байт"); hex(ByteArray(n).also { secure.nextBytes(it) }) },
        f("token_bytes") { a, _ -> val n = a.getOrNull(0)?.let { asInt(it, "token_bytes()") } ?: 32; if (n < 0 || n > 4096) throw err("ValueError", "token_bytes(): от 0 до 4096 байт"); PyBytes(ByteArray(n).also { secure.nextBytes(it) }) },
        f("randbelow") { a, _ -> val n = asLong(arg(a, 0, "randbelow"), "randbelow()"); if (n <= 0) throw err("ValueError", "randbelow(): число должно быть больше нуля"); (secure.nextLong() and Long.MAX_VALUE) % n },
        f("choice") { a, _ -> pick(arg(a, 0, "choice"), "choice").let { it[secure.nextInt(it.size)] } },
    ))

    add(mod(
        "sys",
        "version" to "3.12 (MiniPy, NULL//SIGNAL)", "maxsize" to Long.MAX_VALUE, "argv" to arrayListOf<Any>(""),
        f("exit") { _, _ -> throw PyHalt("exit") },
    ))
}
