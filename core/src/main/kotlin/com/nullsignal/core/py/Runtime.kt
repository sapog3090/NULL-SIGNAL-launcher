@file:Suppress("UNCHECKED_CAST")

package com.nullsignal.core.py

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

// ======================= значения =======================

object PyNone { override fun toString() = "None" }

class PyTuple(val items: List<Any>) {
    override fun equals(other: Any?) = other is PyTuple && items == other.items
    override fun hashCode() = items.hashCode()
}

class PyBytes(val data: ByteArray) {
    override fun equals(other: Any?) = other is PyBytes && data.contentEquals(other.data)
    override fun hashCode() = data.contentHashCode()
}

class PyRange(val start: Long, val stop: Long, val step: Long) {
    val size: Long
        get() = if (step > 0) (if (stop > start) (stop - start + step - 1) / step else 0)
        else (if (start > stop) (start - stop - step - 1) / (-step) else 0)
}

/** Одноразовый итератор: zip, map, enumerate, itertools… */
class PyIter(val it: Iterator<Any>)

class PyBuiltin(val name: String, val fn: (List<Any>, Map<String, Any>) -> Any)
class PyMethod(val self: Any, val name: String)
class PyModule(val name: String, val attrs: Map<String, Any>)
class PyType(val name: String, val isExc: Boolean = false)

/** Значение-исключение: то, что попадает в `except ... as e`. */
class PyExc(val type: String, val msg: String)

/** Объект с методами со стороны приложения (хеш, совпадение регулярки…). */
class PyObj(val type: String, val show: String, val methods: Map<String, (List<Any>) -> Any>)

internal class PyFunc(
    val name: String,
    val params: Params,
    val body: List<Stmt>?,
    val expr: Expr?,
    val closure: Scope,
    val defaults: List<Any?>,
    val locals: Set<String>,
)

internal class Scope(val parent: Scope?, val locals: Set<String>?) {
    val vars = HashMap<String, Any>()
    var globalDecl: HashSet<String>? = null
    var nonlocalDecl: HashSet<String>? = null
    var ret: Any = PyNone
}

/** Ошибка во время работы программы (её можно поймать через try/except). */
class PyThrow(val exc: PyExc, val line: Int, val hint: String? = null) : RuntimeException(exc.msg, null, false, false) {
    var calledFrom = 0
}

/** Остановка программы: лимит шагов, отмена, exit(). Через except не ловится. */
internal class PyHalt(val reason: String) : RuntimeException(reason, null, false, false)

internal const val MAX_ITEMS = 3_000_000
internal const val MAX_TEXT = 4_000_000
private const val MAX_BITS = 400_000

internal val EXC_PARENT = mapOf(
    "Exception" to "BaseException", "ArithmeticError" to "Exception", "ZeroDivisionError" to "ArithmeticError",
    "OverflowError" to "ArithmeticError", "LookupError" to "Exception", "IndexError" to "LookupError",
    "KeyError" to "LookupError", "ValueError" to "Exception", "UnicodeDecodeError" to "ValueError",
    "TypeError" to "Exception", "NameError" to "Exception", "UnboundLocalError" to "NameError",
    "AttributeError" to "Exception", "RuntimeError" to "Exception", "RecursionError" to "RuntimeError",
    "NotImplementedError" to "RuntimeError", "StopIteration" to "Exception", "AssertionError" to "Exception",
    "ImportError" to "Exception", "ModuleNotFoundError" to "ImportError", "MemoryError" to "Exception",
    "OSError" to "Exception", "FileNotFoundError" to "OSError", "PermissionError" to "OSError",
    "ConnectionError" to "OSError", "TimeoutError" to "OSError", "SyntaxError" to "Exception",
    "EOFError" to "Exception", "KeyboardInterrupt" to "BaseException", "SystemExit" to "BaseException",
)

internal fun isSubExc(type: String, base: String): Boolean {
    var t: String? = type
    while (t != null) {
        if (t == base) return true
        t = EXC_PARENT[t] ?: if (t != "BaseException" && t != "Exception") "Exception" else null
    }
    return false
}

fun typeName(v: Any): String = when (v) {
    PyNone -> "NoneType"
    is Boolean -> "bool"
    is Long, is BigInteger -> "int"
    is Double -> "float"
    is String -> "str"
    is ArrayList<*> -> "list"
    is PyTuple -> "tuple"
    is LinkedHashMap<*, *> -> "dict"
    is LinkedHashSet<*> -> "set"
    is PyBytes -> "bytes"
    is PyRange -> "range"
    is PyFunc, is PyBuiltin, is PyMethod -> "function"
    is PyModule -> "module"
    is PyType -> "type"
    is PyExc -> v.type
    is PyIter -> "iterator"
    is PyObj -> v.type
    else -> v.javaClass.simpleName
}

// ======================= текстовое представление =======================

fun pyStr(v: Any): String = when (v) {
    is String -> v
    is PyExc -> v.msg
    else -> pyRepr(v)
}

fun pyRepr(v: Any): String = when (v) {
    PyNone -> "None"
    is Boolean -> if (v) "True" else "False"
    is Long -> v.toString()
    is BigInteger -> v.toString()
    is Double -> fmtFloat(v)
    is String -> reprStr(v)
    is ArrayList<*> -> v.joinToString(", ", "[", "]") { pyRepr(it!!) }
    is PyTuple -> if (v.items.size == 1) "(" + pyRepr(v.items[0]) + ",)" else v.items.joinToString(", ", "(", ")") { pyRepr(it) }
    is LinkedHashMap<*, *> -> v.entries.joinToString(", ", "{", "}") { pyRepr(it.key!!) + ": " + pyRepr(it.value!!) }
    is LinkedHashSet<*> -> if (v.isEmpty()) "set()" else v.joinToString(", ", "{", "}") { pyRepr(it!!) }
    is PyBytes -> reprBytes(v.data)
    is PyRange -> if (v.step == 1L) "range(${v.start}, ${v.stop})" else "range(${v.start}, ${v.stop}, ${v.step})"
    is PyFunc -> "<function ${v.name}>"
    is PyBuiltin -> "<built-in function ${v.name}>"
    is PyMethod -> "<method ${v.name} of ${typeName(v.self)}>"
    is PyModule -> "<module '${v.name}'>"
    is PyType -> "<class '${v.name}'>"
    is PyExc -> v.type + "(" + (if (v.msg.isEmpty()) "" else reprStr(v.msg)) + ")"
    is PyIter -> "<iterator>"
    is PyObj -> v.show
    else -> v.toString()
}

private fun reprStr(s: String): String {
    val q = if ('\'' in s && '"' !in s) '"' else '\''
    val sb = StringBuilder(s.length + 2).append(q)
    for (c in s) when {
        c == q || c == '\\' -> sb.append('\\').append(c)
        c == '\n' -> sb.append("\\n")
        c == '\r' -> sb.append("\\r")
        c == '\t' -> sb.append("\\t")
        c.code < 32 || c.code == 127 -> sb.append("\\x").append(c.code.toString(16).padStart(2, '0'))
        else -> sb.append(c)
    }
    return sb.append(q).toString()
}

private fun reprBytes(b: ByteArray): String {
    val sb = StringBuilder("b'")
    for (x in b) {
        val c = x.toInt() and 0xFF
        when {
            c == '\''.code || c == '\\'.code -> sb.append('\\').append(c.toChar())
            c == 10 -> sb.append("\\n")
            c == 13 -> sb.append("\\r")
            c == 9 -> sb.append("\\t")
            c in 32..126 -> sb.append(c.toChar())
            else -> sb.append("\\x").append(c.toString(16).padStart(2, '0'))
        }
    }
    return sb.append('\'').toString()
}

internal fun fmtFloat(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
    val s = d.toString()
    val e = s.indexOf('E')
    if (e < 0) return s
    val exp = s.substring(e + 1).toInt()
    if (exp in -4..15) {
        val plain = BigDecimal(s).toPlainString()
        return if ('.' in plain) plain else "$plain.0"
    }
    val mant = s.substring(0, e).removeSuffix(".0")
    return mant + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
}

private val HEX = "0123456789abcdef".toCharArray()

internal fun hex(b: ByteArray): String {
    val out = CharArray(b.size * 2)
    for (i in b.indices) {
        val v = b[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 15]
    }
    return String(out)
}

// ======================= истинность, равенство, сравнение =======================

internal fun truthy(v: Any): Boolean = when (v) {
    is Boolean -> v
    PyNone -> false
    is Long -> v != 0L
    is String -> v.isNotEmpty()
    is Double -> v != 0.0
    is Collection<*> -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    is PyTuple -> v.items.isNotEmpty()
    is PyBytes -> v.data.isNotEmpty()
    is PyRange -> v.size > 0
    is BigInteger -> v.signum() != 0
    else -> true
}

internal fun pyEq(a: Any, b: Any): Boolean {
    if (a === b) return true
    return when (a) {
        is Long -> when (b) {
            is Long -> a == b
            is Double -> a.toDouble() == b
            is Boolean -> a == (if (b) 1L else 0L)
            else -> false
        }
        is String -> b is String && a == b
        is Double -> when (b) {
            is Double -> a == b
            is Long -> a == b.toDouble()
            is Boolean -> a == (if (b) 1.0 else 0.0)
            is BigInteger -> a == b.toDouble()
            else -> false
        }
        is Boolean -> when (b) {
            is Boolean -> a == b
            is Long -> (if (a) 1L else 0L) == b
            is Double -> (if (a) 1.0 else 0.0) == b
            else -> false
        }
        is BigInteger -> when (b) {
            is BigInteger -> a == b
            is Double -> a.toDouble() == b
            else -> false
        }
        is ArrayList<*> -> b is ArrayList<*> && seqEq(a as List<Any>, b as List<Any>)
        is PyTuple -> b is PyTuple && seqEq(a.items, b.items)
        is LinkedHashMap<*, *> -> {
            if (b !is LinkedHashMap<*, *> || a.size != b.size) return false
            for ((k, v) in a) {
                val o = b[k] ?: return false
                if (!pyEq(v!!, o)) return false
            }
            true
        }
        else -> a == b
    }
}

private fun seqEq(a: List<Any>, b: List<Any>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) if (!pyEq(a[i], b[i])) return false
    return true
}

internal fun isSame(a: Any, b: Any): Boolean = a === b ||
    (a is Long && b is Long && a == b) || (a is Boolean && b is Boolean && a == b) || (a is String && b is String && a == b)

private fun isNum(v: Any) = v is Long || v is Double || v is BigInteger
private fun unbool(v: Any): Any = if (v is Boolean) (if (v) 1L else 0L) else v
internal fun toBig(v: Any): BigInteger = if (v is BigInteger) v else BigInteger.valueOf(v as Long)
internal fun toDouble(v: Any): Double = when (v) {
    is Double -> v
    is Long -> v.toDouble()
    is BigInteger -> v.toDouble()
    else -> 0.0
}

internal fun MiniPy.compare(a: Any, b: Any): Int {
    if (a is Long && b is Long) return a.compareTo(b)
    if (a is String && b is String) return a.compareTo(b)
    val x = unbool(a)
    val y = unbool(b)
    if (isNum(x) && isNum(y)) {
        return if (x is Double || y is Double) toDouble(x).compareTo(toDouble(y)) else toBig(x).compareTo(toBig(y))
    }
    if (x is ArrayList<*> && y is ArrayList<*>) return seqCompare(x as List<Any>, y as List<Any>)
    if (x is PyTuple && y is PyTuple) return seqCompare(x.items, y.items)
    if (x is PyBytes && y is PyBytes) {
        for (i in 0 until minOf(x.data.size, y.data.size)) {
            val d = (x.data[i].toInt() and 0xFF) - (y.data[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.data.size - y.data.size
    }
    val mixed = (a is String && isNum(y)) || (b is String && isNum(x))
    throw err(
        "TypeError", "нельзя сравнить ${typeName(a)} и ${typeName(b)} через < или >",
        if (mixed) "строка и число не сравниваются: преврати строку в число через int(...)" else null,
    )
}

private fun MiniPy.seqCompare(a: List<Any>, b: List<Any>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        if (pyEq(a[i], b[i])) continue
        return compare(a[i], b[i])
    }
    return a.size - b.size
}

internal fun MiniPy.contains(box: Any, item: Any): Boolean = when (box) {
    is String -> {
        if (item !is String) throw err("TypeError", "слева от 'in' для строки должна быть строка, а не ${typeName(item)}", "преврати в строку: str(x) in текст")
        box.contains(item)
    }
    is ArrayList<*> -> (box as List<Any>).any { pyEq(it, item) }
    is PyTuple -> box.items.any { pyEq(it, item) }
    is LinkedHashMap<*, *> -> box.containsKey(item)
    is LinkedHashSet<*> -> box.contains(item)
    is PyRange -> {
        val v = unbool(item)
        v is Long && (if (box.step > 0) v >= box.start && v < box.stop else v <= box.start && v > box.stop) && (v - box.start) % box.step == 0L
    }
    is PyBytes -> when (item) {
        is Long -> box.data.any { (it.toInt() and 0xFF).toLong() == item }
        is PyBytes -> String(box.data, Charsets.ISO_8859_1).contains(String(item.data, Charsets.ISO_8859_1))
        else -> false
    }
    is PyIter -> { var found = false; for (x in box.it) if (pyEq(x, item)) { found = true; break }; found }
    else -> throw err("TypeError", "'in' не работает с ${typeName(box)}", "справа от in должен быть список, строка, словарь или множество")
}

// ======================= арифметика =======================

private fun MiniPy.zeroDiv() = err("ZeroDivisionError", "деление на ноль", "проверь делитель перед делением: if b != 0: ...")
private fun MiniPy.tooBig() = err("OverflowError", "число получается слишком большим")
internal fun MiniPy.memErr() = err("MemoryError", "получается слишком большой список или строка")

private fun MiniPy.longOp(op: String, a: Long, b: Long): Any = when (op) {
    "+" -> { val r = a + b; if (((a xor r) and (b xor r)) < 0) normInt(toBig(a).add(toBig(b))) else r }
    "-" -> { val r = a - b; if (((a xor b) and (a xor r)) < 0) normInt(toBig(a).subtract(toBig(b))) else r }
    "*" -> try { Math.multiplyExact(a, b) } catch (e: ArithmeticException) { normInt(toBig(a).multiply(toBig(b))) }
    "/" -> { if (b == 0L) throw zeroDiv(); a.toDouble() / b.toDouble() }
    "//" -> { if (b == 0L) throw zeroDiv(); if (a == Long.MIN_VALUE && b == -1L) normInt(toBig(a).negate()) else Math.floorDiv(a, b) }
    "%" -> { if (b == 0L) throw zeroDiv(); Math.floorMod(a, b) }
    "**" -> ipow(a, b)
    "&" -> a and b
    "|" -> a or b
    "^" -> a xor b
    "<<" -> { if (b < 0) throw err("ValueError", "сдвиг на отрицательное число"); if (b > MAX_BITS) throw tooBig(); normInt(toBig(a).shiftLeft(b.toInt())) }
    ">>" -> { if (b < 0) throw err("ValueError", "сдвиг на отрицательное число"); if (b >= 64) (if (a < 0) -1L else 0L) else a shr b.toInt() }
    else -> throw err("TypeError", "неизвестная операция '$op'")
}

private fun MiniPy.ipow(a: Long, b: Long): Any {
    if (b < 0) { if (a == 0L) throw zeroDiv(); return Math.pow(a.toDouble(), b.toDouble()) }
    if (b == 0L) return 1L
    if (a == 0L || a == 1L) return a
    if (a == -1L) return if (b % 2 == 0L) 1L else -1L
    val bits = toBig(a).bitLength().toLong() * b
    if (bits > MAX_BITS) throw tooBig()
    if (bits < 62) {
        var r = 1L
        var base = a
        var e = b
        while (e > 0) {
            if (e and 1L == 1L) r *= base
            e = e shr 1
            if (e > 0) base *= base
        }
        return r
    }
    return normInt(toBig(a).pow(b.toInt()))
}

private fun MiniPy.doubleOp(op: String, a: Double, b: Double): Any = when (op) {
    "+" -> a + b
    "-" -> a - b
    "*" -> a * b
    "/" -> { if (b == 0.0) throw zeroDiv(); a / b }
    "//" -> { if (b == 0.0) throw zeroDiv(); Math.floor(a / b) }
    "%" -> { if (b == 0.0) throw zeroDiv(); val r = a % b; if (r != 0.0 && (r < 0) != (b < 0)) r + b else r }
    "**" -> { if (a == 0.0 && b < 0) throw zeroDiv(); Math.pow(a, b) }
    else -> throw err("TypeError", "операция '$op' не работает с дробными числами (float)", "битовые операции — только для целых: преврати через int(...)")
}

private fun floorDivMod(a: BigInteger, b: BigInteger): Array<BigInteger> {
    val qr = a.divideAndRemainder(b)
    if (qr[1].signum() != 0 && qr[1].signum() != b.signum()) {
        qr[0] = qr[0].subtract(BigInteger.ONE)
        qr[1] = qr[1].add(b)
    }
    return qr
}

private fun MiniPy.bigOp(op: String, a: BigInteger, b: BigInteger): Any = when (op) {
    "+" -> normInt(a.add(b))
    "-" -> normInt(a.subtract(b))
    "*" -> { if (a.bitLength() + b.bitLength() > MAX_BITS) throw tooBig(); normInt(a.multiply(b)) }
    "/" -> { if (b.signum() == 0) throw zeroDiv(); BigDecimal(a).divide(BigDecimal(b), MathContext.DECIMAL64).toDouble() }
    "//" -> { if (b.signum() == 0) throw zeroDiv(); normInt(floorDivMod(a, b)[0]) }
    "%" -> { if (b.signum() == 0) throw zeroDiv(); normInt(floorDivMod(a, b)[1]) }
    "**" -> {
        if (b.signum() < 0) Math.pow(a.toDouble(), b.toDouble())
        else { if (b.bitLength() > 31 || a.bitLength().toLong() * b.toLong() > MAX_BITS) throw tooBig(); normInt(a.pow(b.toInt())) }
    }
    "&" -> normInt(a.and(b))
    "|" -> normInt(a.or(b))
    "^" -> normInt(a.xor(b))
    "<<" -> { if (b.signum() < 0 || b.bitLength() > 31 || b.toLong() > MAX_BITS) throw tooBig(); normInt(a.shiftLeft(b.toInt())) }
    ">>" -> { if (b.signum() < 0) throw err("ValueError", "сдвиг на отрицательное число"); normInt(a.shiftRight(if (b.bitLength() > 31) Int.MAX_VALUE else b.toInt())) }
    else -> throw err("TypeError", "неизвестная операция '$op'")
}

internal fun MiniPy.repeat(seq: Any, n: Long): Any? {
    val k = maxOf(0L, n)
    return when (seq) {
        is String -> { if (seq.length * k > MAX_TEXT) throw memErr(); seq.repeat(k.toInt()) }
        is ArrayList<*> -> {
            if (seq.size * k > MAX_ITEMS) throw memErr()
            val out = ArrayList<Any>((seq.size * k).toInt())
            for (i in 0 until k) out.addAll(seq as List<Any>)
            out
        }
        is PyTuple -> {
            if (seq.items.size * k > MAX_ITEMS) throw memErr()
            val out = ArrayList<Any>((seq.items.size * k).toInt())
            for (i in 0 until k) out.addAll(seq.items)
            PyTuple(out)
        }
        is PyBytes -> {
            if (seq.data.size * k > MAX_TEXT) throw memErr()
            val out = ByteArray((seq.data.size * k).toInt())
            for (i in 0 until k.toInt()) System.arraycopy(seq.data, 0, out, i * seq.data.size, seq.data.size)
            PyBytes(out)
        }
        else -> null
    }
}

internal fun MiniPy.bin(op: String, a0: Any, b0: Any): Any {
    if (a0 is Long && b0 is Long) return longOp(op, a0, b0)
    if (op == "+" && a0 is String && b0 is String) {
        if (a0.length + b0.length > MAX_TEXT) throw memErr()
        return a0 + b0
    }
    val a = unbool(a0)
    val b = unbool(b0)
    if (a is Long && b is Long) return longOp(op, a, b)
    if (isNum(a) && isNum(b)) {
        return if (a is Double || b is Double) doubleOp(op, toDouble(a), toDouble(b)) else bigOp(op, toBig(a), toBig(b))
    }
    when (op) {
        "+" -> when {
            a is ArrayList<*> && b is ArrayList<*> -> {
                if (a.size + b.size > MAX_ITEMS) throw memErr()
                return ArrayList<Any>(a.size + b.size).apply { addAll(a as List<Any>); addAll(b as List<Any>) }
            }
            a is PyTuple && b is PyTuple -> return PyTuple(a.items + b.items)
            a is PyBytes && b is PyBytes -> return PyBytes(a.data + b.data)
        }
        "*" -> {
            if (b is Long) repeat(a, b)?.let { return it }
            if (a is Long) repeat(b, a)?.let { return it }
        }
        "%" -> if (a0 is String) return percentFormat(a0, b0)
        "|" -> {
            if (a is LinkedHashSet<*> && b is LinkedHashSet<*>) return LinkedHashSet<Any>(a as Set<Any>).apply { addAll(b as Set<Any>) }
            if (a is LinkedHashMap<*, *> && b is LinkedHashMap<*, *>) return LinkedHashMap<Any, Any>(a as Map<Any, Any>).apply { putAll(b as Map<Any, Any>) }
        }
        "&" -> if (a is LinkedHashSet<*> && b is LinkedHashSet<*>) return LinkedHashSet<Any>(a as Set<Any>).apply { retainAll(b as Set<Any>) }
        "-" -> if (a is LinkedHashSet<*> && b is LinkedHashSet<*>) return LinkedHashSet<Any>(a as Set<Any>).apply { removeAll(b as Set<Any>) }
        "^" -> if (a is LinkedHashSet<*> && b is LinkedHashSet<*>) {
            val out = LinkedHashSet<Any>()
            for (x in a) if (x !in b) out.add(x!!)
            for (x in b) if (x !in a) out.add(x!!)
            return out
        }
    }
    val hint = when {
        a0 === PyNone || b0 === PyNone -> "одно из значений — None: возможно, функция ничего не вернула (в ней нет return)"
        op == "+" && ((a0 is String && isNum(b)) || (b0 is String && isNum(a))) ->
            "строку и число сложить нельзя: преврати число в строку через str(...) или используй f-строку: f\"текст {число}\""
        (a0 is String && isNum(b)) || (b0 is String && isNum(a)) -> "одно из значений — строка: преврати её в число через int(...) или float(...)"
        else -> null
    }
    throw err("TypeError", "операция '$op' не работает для ${typeName(a0)} и ${typeName(b0)}", hint)
}

internal fun MiniPy.unary(op: String, v0: Any): Any {
    if (op == "not") return !truthy(v0)
    val v = unbool(v0)
    when (op) {
        "-" -> when (v) {
            is Long -> return if (v == Long.MIN_VALUE) toBig(v).negate() else -v
            is Double -> return -v
            is BigInteger -> return normInt(v.negate())
        }
        "+" -> if (isNum(v)) return v
        "~" -> when (v) {
            is Long -> return v.inv()
            is BigInteger -> return normInt(v.not())
        }
    }
    throw err("TypeError", "операция '$op' не работает для ${typeName(v0)}")
}

// ======================= форматирование =======================

private fun groupDigits(s: String, sep: Char, n: Int): String {
    if (s.length <= n) return s
    val sb = StringBuilder()
    val first = s.length % n
    if (first > 0) sb.append(s, 0, first)
    var i = first
    while (i < s.length) {
        if (sb.isNotEmpty()) sb.append(sep)
        sb.append(s, i, i + n)
        i += n
    }
    return sb.toString()
}

private fun fixed(d: Double, prec: Int): String = BigDecimal(d).setScale(prec, RoundingMode.HALF_EVEN).toPlainString()

private fun general(d: Double, p: Int): String {
    if (d == 0.0) return "0"
    val bd = BigDecimal(d).round(MathContext(p, RoundingMode.HALF_EVEN))
    val exp = bd.precision() - bd.scale() - 1
    return if (exp < -4 || exp >= p) {
        bd.movePointLeft(exp).stripTrailingZeros().toPlainString() + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
    } else {
        bd.stripTrailingZeros().toPlainString()
    }
}

/** Спецификация как в f-строках и format(): [[fill]align][sign][#][0][width][,][.precision][type]. */
internal fun MiniPy.formatValue(v: Any, spec: String): String {
    if (spec.isEmpty()) return pyStr(v)
    val n = spec.length
    var i = 0
    var fill = ' '
    var align = '\u0000'
    if (n >= 2 && spec[1] in "<>^=") { fill = spec[0]; align = spec[1]; i = 2 } else if (spec[0] in "<>^=") { align = spec[0]; i = 1 }
    var sign = '-'
    if (i < n && spec[i] in "+- ") { sign = spec[i]; i++ }
    var alt = false
    if (i < n && spec[i] == '#') { alt = true; i++ }
    if (i < n && spec[i] == '0') { if (align == '\u0000') { fill = '0'; align = '=' }; i++ }
    var width = 0
    while (i < n && spec[i].isDigit() && width < 100_000) { width = width * 10 + (spec[i] - '0'); i++ }
    var group = '\u0000'
    if (i < n && (spec[i] == ',' || spec[i] == '_')) { group = spec[i]; i++ }
    var prec = -1
    if (i < n && spec[i] == '.') {
        i++
        prec = 0
        while (i < n && spec[i].isDigit() && prec < 10_000) { prec = prec * 10 + (spec[i] - '0'); i++ }
    }
    val type = if (i < n) spec[i++] else '\u0000'
    if (i < n) throw err("ValueError", "непонятный формат '$spec'", "примеры: {x:.2f} — два знака после запятой, {x:>8} — ширина 8, {x:04d} — с нулями, {x:x} — hex")
    if (width > 10_000 || prec > 1000) throw err("ValueError", "слишком большая ширина в формате '$spec'")
    val num: Any = if (v is Boolean && type != '\u0000' && type != 's') (if (v) 1L else 0L) else v
    val body: String
    var prefix = ""
    var numeric = true
    when {
        type == 's' || (type == '\u0000' && !isNum(num)) -> {
            numeric = false
            val s = pyStr(v)
            body = if (prec in 0 until s.length) s.substring(0, prec) else s
        }
        type == 'd' || type == 'x' || type == 'X' || type == 'o' || type == 'b' || type == 'c' || (type == '\u0000' && num !is Double) -> {
            if (num is Double) throw err("ValueError", "формат '$type' — для целых чисел, а здесь float", "для дробных используй .2f")
            if (!isNum(num)) throw err("ValueError", "формат '$type' — для чисел, а здесь ${typeName(v)}")
            val b = toBig(num)
            if (type == 'c') {
                numeric = false
                body = String(Character.toChars(b.toInt()))
            } else {
                val abs = b.abs()
                var digits = when (type) {
                    'x' -> abs.toString(16)
                    'X' -> abs.toString(16).uppercase()
                    'o' -> abs.toString(8)
                    'b' -> abs.toString(2)
                    else -> abs.toString()
                }
                if (group != '\u0000') digits = groupDigits(digits, group, if (type == 'd' || type == '\u0000') 3 else 4)
                body = digits
                prefix = (if (b.signum() < 0) "-" else if (sign == '+') "+" else if (sign == ' ') " " else "") +
                    (if (!alt) "" else when (type) { 'x' -> "0x"; 'X' -> "0X"; 'o' -> "0o"; 'b' -> "0b"; else -> "" })
            }
        }
        type == 'f' || type == 'F' || type == 'e' || type == 'E' || type == 'g' || type == 'G' || type == '%' || type == '\u0000' -> {
            if (!isNum(num)) throw err("ValueError", "формат '$type' — для чисел, а здесь ${typeName(v)}", "преврати в число: float(...)")
            val d = toDouble(num)
            val ad = Math.abs(d)
            var s = when {
                d.isNaN() -> "nan"
                d.isInfinite() -> "inf"
                type == 'f' || type == 'F' -> fixed(ad, if (prec < 0) 6 else prec)
                type == '%' -> fixed(ad * 100, if (prec < 0) 6 else prec) + "%"
                type == 'e' || type == 'E' -> String.format(Locale.US, "%." + (if (prec < 0) 6 else prec) + "e", ad).let { if (type == 'E') it.uppercase() else it }
                type == '\u0000' && prec < 0 -> fmtFloat(ad)
                else -> general(ad, if (prec < 0) 6 else maxOf(prec, 1))
            }
            if (group != '\u0000') {
                val dot = s.indexOfFirst { !it.isDigit() }.let { if (it < 0) s.length else it }
                s = groupDigits(s.substring(0, dot), group, 3) + s.substring(dot)
            }
            body = s
            prefix = if (d < 0 || (d == 0.0 && 1.0 / d < 0)) "-" else if (sign == '+') "+" else if (sign == ' ') " " else ""
        }
        else -> throw err("ValueError", "неизвестный тип формата '$type'", "бывают: d — целое, f — дробное, x — hex, b — двоичное, s — строка, % — проценты")
    }
    val len = prefix.length + body.length
    if (len >= width) return prefix + body
    val pad = fill.toString().repeat(width - len)
    return when (if (align != '\u0000') align else if (numeric) '>' else '<') {
        '<' -> prefix + body + pad
        '>' -> pad + prefix + body
        '^' -> { val l = (width - len) / 2; pad.substring(0, l) + prefix + body + pad.substring(l) }
        else -> prefix + pad + body
    }
}

/** Старый стиль: "%s: %d" % (a, b). */
internal fun MiniPy.percentFormat(fmt: String, arg: Any): String {
    val args = if (arg is PyTuple) arg.items else listOf(arg)
    var ai = 0
    val sb = StringBuilder()
    var i = 0
    while (i < fmt.length) {
        val c = fmt[i]
        if (c != '%') { sb.append(c); i++; continue }
        i++
        if (i >= fmt.length) throw err("ValueError", "знак % в конце строки формата", "чтобы вывести сам знак процента, пиши %%")
        if (fmt[i] == '%') { sb.append('%'); i++; continue }
        var flags = ""
        while (i < fmt.length && fmt[i] in "-+ 0#") { flags += fmt[i]; i++ }
        var width = ""
        while (i < fmt.length && fmt[i].isDigit()) { width += fmt[i]; i++ }
        var prec = ""
        if (i < fmt.length && fmt[i] == '.') {
            i++
            prec = "."
            while (i < fmt.length && fmt[i].isDigit()) { prec += fmt[i]; i++ }
        }
        if (i >= fmt.length) throw err("ValueError", "незаконченный формат после %")
        val t = fmt[i]
        i++
        if (ai >= args.size) throw err("TypeError", "в строке формата больше %-полей, чем значений")
        val v = args[ai++]
        val numSpec = (if ('-' in flags) "<" else "") + (if ('+' in flags) "+" else if (' ' in flags) " " else "") +
            (if ('#' in flags) "#" else "") + (if ('0' in flags && '-' !in flags) "0" else "") + width
        sb.append(
            when (t) {
                's' -> formatValue(pyStr(v), (if ('-' in flags) "<" else ">") + width + prec + "s")
                'r' -> formatValue(pyRepr(v), (if ('-' in flags) "<" else ">") + width + prec + "s")
                'd', 'i', 'u' -> formatValue(if (v is Double) v.toLong() else v, numSpec + "d")
                'x', 'X', 'o', 'c' -> formatValue(v, numSpec + t)
                'f', 'F', 'e', 'E', 'g', 'G' -> formatValue(v, numSpec + prec + t)
                else -> throw err("ValueError", "неизвестный формат %$t", "бывают: %s — строка, %d — целое, %f — дробное, %x — hex")
            },
        )
    }
    if (ai < args.size) throw err("TypeError", "значений больше, чем %-полей в строке формата")
    return sb.toString()
}

/** "{} и {name:>5}".format(...). */
internal fun MiniPy.strFormat(s: String, a: List<Any>, kw: Map<String, Any>): String {
    val sb = StringBuilder()
    var i = 0
    var auto = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '{') {
            if (i + 1 < s.length && s[i + 1] == '{') { sb.append('{'); i += 2; continue }
            val j = s.indexOf('}', i)
            if (j < 0) throw err("ValueError", "в строке формата не закрыта скобка {")
            var field = s.substring(i + 1, j)
            var spec = ""
            var conv = '\u0000'
            val ci = field.indexOf(':')
            if (ci >= 0) { spec = field.substring(ci + 1); field = field.substring(0, ci) }
            val bi = field.indexOf('!')
            if (bi >= 0) { conv = field.getOrElse(bi + 1) { 's' }; field = field.substring(0, bi) }
            var v: Any = when {
                field.isEmpty() -> a.getOrNull(auto++) ?: throw err("IndexError", "в format() передано меньше значений, чем {} в строке")
                field.all { it.isDigit() } -> a.getOrNull(field.toInt()) ?: throw err("IndexError", "в format() нет значения с номером $field")
                else -> kw[field] ?: throw err("KeyError", pyRepr(field), "в format() не передано значение с именем '$field'")
            }
            if (conv == 'r') v = pyRepr(v) else if (conv == 's') v = pyStr(v)
            sb.append(formatValue(v, spec))
            i = j + 1
            continue
        }
        if (c == '}') {
            if (i + 1 < s.length && s[i + 1] == '}') { sb.append('}'); i += 2; continue }
            throw err("ValueError", "одиночная } в строке формата", "чтобы вывести саму скобку, удвой её: }}")
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}
