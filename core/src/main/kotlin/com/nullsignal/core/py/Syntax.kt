package com.nullsignal.core.py

import java.math.BigInteger

/** Ошибка в тексте программы (ещё до запуска). */
class PySyntaxError(val line: Int, message: String, val hint: String? = null) : Exception(message)

// ======================= лексер =======================

internal enum class T { NAME, NUM, STR, BYTES, FSTR, OP, NEWLINE, INDENT, DEDENT, EOF }

internal class Tok(val t: T, val s: String, val line: Int, val v: Any? = null, val raw: Boolean = false) {
    fun show(): String = when (t) {
        T.NEWLINE -> "конец строки"
        T.INDENT -> "отступ"
        T.DEDENT -> "конец блока"
        T.EOF -> "конец программы"
        T.STR, T.FSTR, T.BYTES -> "строка в кавычках"
        T.NUM -> "число $s"
        else -> "'$s'"
    }
}

internal val KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
    "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
)
private val KEYWORDS_LOWER = KEYWORDS.map { it.lowercase() }.toSet()
private val AUG_OPS = setOf("+=", "-=", "*=", "/=", "//=", "%=", "**=", "&=", "|=", "^=", "<<=", ">>=")
private val OPS3 = arrayOf("**=", "//=", ">>=", "<<=", "...")
private val OPS2 = arrayOf("**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "->", ":=")
private const val OPS1 = "+-*/%<>=()[]{},:.;&|^~@"
private val STR_PREFIXES = setOf("r", "b", "f", "u", "rb", "br", "rf", "fr")

internal fun normInt(b: BigInteger): Any = if (b.bitLength() < 64) b.toLong() else b

private fun isNameStart(c: Char) = c == '_' || c.isLetter()
private fun isNamePart(c: Char) = c == '_' || c.isLetterOrDigit()

/** Разбор \n, \t, \x41 и прочих экранированных символов. */
internal fun unescape(s: String, line: Int): String {
    if (s.indexOf('\\') < 0) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
        val e = s[i + 1]
        i += 2
        when (e) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            '\\' -> sb.append('\\')
            '\'' -> sb.append('\'')
            '"' -> sb.append('"')
            '0' -> sb.append('\u0000')
            'a' -> sb.append('\u0007')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'v' -> sb.append('\u000B')
            '\n' -> {}
            'x', 'u' -> {
                val n = if (e == 'x') 2 else 4
                val h = s.substring(i, minOf(i + n, s.length))
                val v = (if (h.length == n) h.toIntOrNull(16) else null)
                    ?: throw PySyntaxError(line, "после \\$e нужны $n шестнадцатеричные цифры")
                sb.append(v.toChar())
                i += n
            }
            else -> sb.append('\\').append(e)
        }
    }
    return sb.toString()
}

internal class Lexer(src: String) {
    private val s = src.replace("\r\n", "\n").replace('\r', '\n')
    private var i = 0
    private var line = 1
    private val out = ArrayList<Tok>()
    private val indents = arrayListOf(0)
    private val opens = ArrayList<Pair<Char, Int>>()

    fun lex(): List<Tok> {
        var lineStart = true
        while (true) {
            if (lineStart && opens.isEmpty()) {
                var col = 0
                var j = i
                while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == ' ')) { col += if (s[j] == '\t') 4 - col % 4 else 1; j++ }
                if (j >= s.length) { i = j; break }
                if (s[j] == '\n') { i = j + 1; line++; continue }
                if (s[j] == '#') { while (j < s.length && s[j] != '\n') j++; i = j; continue }
                i = j
                if (col > indents.last()) {
                    indents.add(col)
                    out += Tok(T.INDENT, "", line)
                } else {
                    while (col < indents.last()) { indents.removeAt(indents.size - 1); out += Tok(T.DEDENT, "", line) }
                    if (col != indents.last()) {
                        throw PySyntaxError(line, "отступ не совпадает ни с одним из предыдущих", "строки одного блока должны начинаться ровно друг под другом (обычно 4 пробела на уровень)")
                    }
                }
                lineStart = false
            }
            if (i >= s.length) break
            val c = s[i]
            when {
                c == '\n' -> {
                    if (opens.isEmpty()) {
                        if (out.isNotEmpty() && out.last().t != T.NEWLINE) out += Tok(T.NEWLINE, "", line)
                        lineStart = true
                    }
                    i++
                    line++
                }
                c == ' ' || c == '\t' || c == ' ' || c == '\u000C' -> i++
                c == '#' -> while (i < s.length && s[i] != '\n') i++
                c == '\\' && i + 1 < s.length && s[i + 1] == '\n' -> { i += 2; line++ }
                isNameStart(c) -> {
                    val st = i
                    while (i < s.length && isNamePart(s[i])) i++
                    val word = s.substring(st, i)
                    if (i < s.length && (s[i] == '"' || s[i] == '\'') && word.lowercase() in STR_PREFIXES) string(word.lowercase())
                    else out += Tok(T.NAME, word, line)
                }
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> number()
                c == '"' || c == '\'' -> string("")
                else -> operator(c)
            }
        }
        if (opens.isNotEmpty()) throw PySyntaxError(opens.last().second, "не закрыта скобка '${opens.last().first}'", "у каждой открывающей скобки должна быть закрывающая")
        if (out.isNotEmpty() && out.last().t != T.NEWLINE) out += Tok(T.NEWLINE, "", line)
        while (indents.size > 1) { indents.removeAt(indents.size - 1); out += Tok(T.DEDENT, "", line) }
        out += Tok(T.EOF, "", line)
        return out
    }

    private fun string(prefix: String) {
        val startLine = line
        val q = s[i]
        val triple = s.startsWith("$q$q$q", i)
        i += if (triple) 3 else 1
        val raw = 'r' in prefix
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw PySyntaxError(startLine, "строка не закрыта кавычкой $q", "текст в кавычках должен заканчиваться такой же кавычкой")
            val c = s[i]
            if (triple) {
                if (s.startsWith("$q$q$q", i)) { i += 3; break }
            } else if (c == q) {
                i++
                break
            } else if (c == '\n') {
                throw PySyntaxError(startLine, "строка не закрыта кавычкой $q", "текст в кавычках должен закрываться на той же строке")
            }
            if (c == '\n') line++
            if (c == '\\' && i + 1 < s.length) {
                if (s[i + 1] == '\n') line++
                sb.append(c).append(s[i + 1])
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        val body = sb.toString()
        when {
            'f' in prefix -> out += Tok(T.FSTR, body, startLine, raw = raw)
            'b' in prefix -> {
                val text = if (raw) body else unescape(body, startLine)
                if (text.any { it.code > 255 }) throw PySyntaxError(startLine, "в b'...' можно писать только латиницу и \\xNN", "для русского текста: \"текст\".encode()")
                out += Tok(T.BYTES, body, startLine, ByteArray(text.length) { text[it].code.toByte() })
            }
            else -> out += Tok(T.STR, if (raw) body else unescape(body, startLine), startLine)
        }
    }

    private fun number() {
        val st = i
        if (s[i] == '0' && i + 1 < s.length && s[i + 1] in "xXbBoO") {
            val radix = when (s[i + 1].lowercaseChar()) { 'x' -> 16; 'b' -> 2; else -> 8 }
            i += 2
            val d = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            val v = s.substring(d, i).replace("_", "").toBigIntegerOrNull(radix)
                ?: throw PySyntaxError(line, "неверное число ${s.substring(st, i)}")
            out += Tok(T.NUM, s.substring(st, i), line, normInt(v))
            return
        }
        while (i < s.length && (s[i].isDigit() || s[i] == '_')) i++
        var isFloat = false
        if (i < s.length && s[i] == '.' && (i + 1 >= s.length || !isNameStart(s[i + 1]))) {
            isFloat = true
            i++
            while (i < s.length && (s[i].isDigit() || s[i] == '_')) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            var j = i + 1
            if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
            if (j < s.length && s[j].isDigit()) {
                isFloat = true
                i = j
                while (i < s.length && s[i].isDigit()) i++
            }
        }
        val txt = s.substring(st, i).replace("_", "")
        if (i < s.length && isNameStart(s[i])) {
            throw PySyntaxError(line, "сразу после числа $txt идёт буква", "имя переменной не может начинаться с цифры")
        }
        if (isFloat) {
            out += Tok(T.NUM, txt, line, txt.toDouble())
        } else {
            if (txt.length > 1 && txt[0] == '0' && txt.any { it != '0' }) {
                throw PySyntaxError(line, "число $txt начинается с нуля", "числа не пишут с нулём впереди; если это код или PIN — возьми в кавычки: \"$txt\"")
            }
            out += Tok(T.NUM, txt, line, normInt(BigInteger(txt)))
        }
    }

    private fun operator(c: Char) {
        for (op in OPS3) if (s.startsWith(op, i)) { out += Tok(T.OP, op, line); i += 3; return }
        for (op in OPS2) if (s.startsWith(op, i)) { out += Tok(T.OP, op, line); i += 2; return }
        if (c in OPS1) {
            when (c) {
                '(', '[', '{' -> opens.add(c to line)
                ')', ']', '}' -> {
                    val open = opens.lastOrNull() ?: throw PySyntaxError(line, "лишняя закрывающая скобка '$c'")
                    val want = when (open.first) { '(' -> ')'; '[' -> ']'; else -> '}' }
                    if (c != want) throw PySyntaxError(line, "скобка '${open.first}' со строки ${open.second} закрыта через '$c'", "здесь должна быть '$want'")
                    opens.removeAt(opens.size - 1)
                }
            }
            out += Tok(T.OP, c.toString(), line)
            i++
            return
        }
        val hint = when (c) {
            '!' -> "в Python вместо ! пишут not, а «не равно» — это !="
            '“', '”', '„', '«', '»', '‘', '’' -> "это «красивые» кавычки: в коде нужны прямые \" или '"
            '–', '—', '−' -> "это длинное тире: в коде нужен обычный минус -"
            '×' -> "умножение в Python — звёздочка *"
            '÷' -> "деление в Python — косая черта /"
            '$', '?', '`' -> "в Python такого знака нет"
            else -> null
        }
        throw PySyntaxError(line, "непонятный символ '$c'", hint)
    }
}

// ======================= дерево программы =======================

internal sealed class Expr
internal class Const(val v: Any) : Expr()
internal class Name(val id: String) : Expr()
internal class FStr(val parts: List<Any>) : Expr() // String или FPart
internal class FPart(val e: Expr, val conv: Char, val spec: FStr?)
internal class ListE(val items: List<Expr>) : Expr()
internal class TupleE(val items: List<Expr>) : Expr()
internal class SetE(val items: List<Expr>) : Expr()
internal class DictE(val keys: List<Expr>, val values: List<Expr>) : Expr()
internal class Starred(val e: Expr) : Expr()
internal class Unary(val op: String, val e: Expr) : Expr()
internal class Bin(val op: String, val l: Expr, val r: Expr) : Expr()
internal class BoolOp(val and: Boolean, val l: Expr, val r: Expr) : Expr()
internal class Compare(val first: Expr, val ops: List<String>, val rest: List<Expr>) : Expr()
internal class IfExp(val cond: Expr, val a: Expr, val b: Expr) : Expr()
internal class Call(val fn: Expr, val args: List<Expr>, val kw: List<Pair<String, Expr>>, val kwStar: Expr?) : Expr()
internal class Attr(val obj: Expr, val name: String) : Expr()
internal class Index(val obj: Expr, val idx: Expr) : Expr()
internal class SliceE(val obj: Expr, val lo: Expr?, val hi: Expr?, val step: Expr?) : Expr()
internal class Lambda(val params: Params, val body: Expr) : Expr()
internal class Comp(val kind: Char, val elt: Expr, val value: Expr?, val clauses: List<CompFor>) : Expr()
internal class CompFor(val target: Expr, val iter: Expr, val ifs: List<Expr>)
internal class Params(val names: List<String>, val defaults: List<Expr?>, val vararg: String?, val kwarg: String?)

internal sealed class Stmt { var line = 0 }
internal class ExprS(val e: Expr) : Stmt()
internal class Assign(val targets: List<Expr>, val value: Expr) : Stmt()
internal class AugAssign(val target: Expr, val op: String, val value: Expr) : Stmt()
internal class If(val cond: Expr, val body: List<Stmt>, val orelse: List<Stmt>) : Stmt()
internal class While(val cond: Expr, val body: List<Stmt>, val orelse: List<Stmt>) : Stmt()
internal class For(val target: Expr, val iter: Expr, val body: List<Stmt>, val orelse: List<Stmt>) : Stmt()
internal class Break : Stmt()
internal class Continue : Stmt()
internal class Pass : Stmt()
internal class Def(val name: String, val params: Params, val body: List<Stmt>, val locals: Set<String>) : Stmt()
internal class Return(val e: Expr?) : Stmt()
internal class Import(val names: List<Pair<String, String?>>) : Stmt()
internal class FromImport(val module: String, val names: List<Pair<String, String?>>?) : Stmt()
internal class Try(val body: List<Stmt>, val handlers: List<Handler>, val orelse: List<Stmt>, val final: List<Stmt>) : Stmt()
internal class Handler(val type: Expr?, val name: String?, val body: List<Stmt>)
internal class Raise(val e: Expr?) : Stmt()
internal class Global(val names: List<String>) : Stmt()
internal class Nonlocal(val names: List<String>) : Stmt()
internal class Del(val targets: List<Expr>) : Stmt()
internal class Assert(val test: Expr, val msg: Expr?) : Stmt()

/** Имена, которым функция что-то присваивает: они считаются её локальными переменными. */
internal fun localsOf(params: Params, body: List<Stmt>): Set<String> {
    val assigned = HashSet<String>()
    val declared = HashSet<String>()
    fun target(e: Expr) {
        when (e) {
            is Name -> assigned += e.id
            is TupleE -> e.items.forEach { target(it) }
            is ListE -> e.items.forEach { target(it) }
            is Starred -> target(e.e)
            else -> {}
        }
    }
    fun walk(ss: List<Stmt>) {
        for (s in ss) when (s) {
            is Assign -> s.targets.forEach { target(it) }
            is AugAssign -> target(s.target)
            is For -> { target(s.target); walk(s.body); walk(s.orelse) }
            is While -> { walk(s.body); walk(s.orelse) }
            is If -> { walk(s.body); walk(s.orelse) }
            is Try -> {
                walk(s.body)
                s.handlers.forEach { h -> h.name?.let { assigned += it }; walk(h.body) }
                walk(s.orelse)
                walk(s.final)
            }
            is Def -> assigned += s.name
            is Import -> s.names.forEach { (m, a) -> assigned += a ?: m.substringBefore('.') }
            is FromImport -> s.names?.forEach { (n, a) -> assigned += a ?: n }
            is Global -> declared += s.names
            is Nonlocal -> declared += s.names
            is Del -> s.targets.forEach { target(it) }
            else -> {}
        }
    }
    walk(body)
    assigned += params.names
    params.vararg?.let { assigned += it }
    params.kwarg?.let { assigned += it }
    assigned -= declared
    return assigned
}

// ======================= разбор =======================

internal class Parser(private val toks: List<Tok>, private val fixedLine: Int = 0) {
    private var p = 0
    private var noIn = false
    private var fnDepth = 0
    private var loopDepth = 0
    private val cur: Tok get() = toks[p]

    private fun ln(t: Tok = cur) = if (fixedLine > 0) fixedLine else t.line
    private fun isOp(s: String) = cur.t == T.OP && cur.s == s
    private fun isKw(s: String) = cur.t == T.NAME && cur.s == s
    private fun accept(s: String): Boolean { if (isOp(s)) { p++; return true }; return false }
    private fun expect(s: String) { if (!accept(s)) unexpected("'$s'") }
    private fun atEnd() = cur.t == T.NEWLINE || cur.t == T.EOF || isOp(";")
    private fun fail(msg: String, hint: String? = null, line: Int = ln()): Nothing = throw PySyntaxError(line, msg, hint)
    private fun <S : Stmt> S.at(l: Int): S { line = l; return this }

    private inline fun <R> free(block: () -> R): R {
        val saved = noIn
        noIn = false
        try { return block() } finally { noIn = saved }
    }

    private fun unexpected(expected: String? = null): Nothing {
        val t = cur
        val prev = if (p > 0) toks[p - 1] else null
        var hint: String? = null
        if (prev != null && prev.t == T.NAME) {
            val low = prev.s.lowercase()
            hint = when {
                prev.s !in KEYWORDS && low in KEYWORDS_LOWER && low != "true" && low != "false" && low != "none" ->
                    "Python различает большие и маленькие буквы: пиши '$low', а не '${prev.s}'"
                prev.s == "print" && t.t != T.OP -> "print — это функция, нужны скобки: print(...)"
                prev.s == "else" && t.t == T.NAME && t.s == "if" -> "вместо 'else if' в Python пишут 'elif'"
                else -> null
            }
        }
        if (hint == null && prev != null && prev.t == T.OP && (prev.s == "+" || prev.s == "-") && p >= 2 && toks[p - 2].t == T.OP && toks[p - 2].s == prev.s) {
            hint = "в Python нет ++ и --: пиши x += 1 или x -= 1"
        }
        if (hint == null && t.t == T.INDENT) hint = "лишний отступ: строка должна начинаться на том же уровне, что и предыдущая"
        if (hint == null && t.t == T.NAME && t.s in KEYWORDS) hint = "'${t.s}' — служебное слово Python, здесь оно не к месту"
        if (hint == null && t.t == T.OP && t.s == "=" ) hint = "'=' кладёт значение в переменную; сравнение — это '=='"
        fail(if (expected != null) "ожидалось $expected, а здесь ${t.show()}" else "здесь не должно быть: ${t.show()}", hint, ln(t))
    }

    fun file(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (cur.t != T.EOF) {
            if (cur.t == T.NEWLINE) { p++; continue }
            stmt(out)
        }
        return out
    }

    fun evalInput(): Expr {
        val e = exprList()
        if (cur.t != T.NEWLINE && cur.t != T.EOF) unexpected()
        return e
    }

    // ---------- инструкции ----------

    private fun stmt(out: MutableList<Stmt>) {
        val t = cur
        if (t.t == T.INDENT) unexpected()
        if (t.t == T.NAME) {
            when (t.s) {
                "if" -> { out += ifStmt(); return }
                "while" -> { out += whileStmt(); return }
                "for" -> { out += forStmt(); return }
                "def" -> { out += defStmt(); return }
                "try" -> { out += tryStmt(); return }
                "class" -> fail("классы (class) в этой песочнице не поддерживаются", "обойдись функциями и словарями")
                "with" -> fail("with в песочнице не поддерживается: файлов здесь нет")
                "async", "await", "yield" -> fail("'${t.s}' в песочнице не поддерживается")
                "elif", "else" -> fail("'${t.s}' без своего if", "проверь отступы: ${t.s} должен стоять ровно под своим if")
                "except", "finally" -> fail("'${t.s}' без своего try", "проверь отступы: ${t.s} должен стоять ровно под своим try")
            }
        }
        simpleStmts(out)
    }

    private fun simpleStmts(out: MutableList<Stmt>) {
        out += simple()
        while (accept(";")) {
            if (cur.t == T.NEWLINE || cur.t == T.EOF) break
            out += simple()
        }
        if (cur.t == T.NEWLINE) p++ else if (cur.t != T.EOF) unexpected()
    }

    private fun block(): List<Stmt> {
        val body = ArrayList<Stmt>()
        if (cur.t == T.NEWLINE) {
            p++
            if (cur.t != T.INDENT) fail("после ':' нужен блок с отступом", "строки внутри if / for / while / def начинаются с 4 пробелов")
            p++
            while (cur.t != T.DEDENT && cur.t != T.EOF) {
                if (cur.t == T.NEWLINE) { p++; continue }
                stmt(body)
            }
            if (cur.t == T.DEDENT) p++
        } else {
            simpleStmts(body)
        }
        return body
    }

    private fun caseHint(t: Tok) {
        val low = t.s.lowercase()
        if (t.s in KEYWORDS || low !in KEYWORDS_LOWER || low == "true" || low == "false" || low == "none") return
        val nx = toks[p + 1]
        if (nx.t == T.OP && nx.s in setOf("=", ".", "(", "[", ",", "+=", "-=", "*=", "/=")) return
        fail("'${t.s}' — Python такого слова не знает", "Python различает большие и маленькие буквы: пиши '$low'", ln(t))
    }

    private fun name(what: String): String {
        val t = cur
        if (t.t != T.NAME) unexpected(what)
        if (t.s in KEYWORDS) fail("'${t.s}' — служебное слово Python, так назвать нельзя", "выбери другое имя", ln(t))
        p++
        return t.s
    }

    private fun dotted(): String {
        val sb = StringBuilder(name("имя модуля"))
        while (accept(".")) sb.append('.').append(name("имя модуля"))
        return sb.toString()
    }

    private fun checkTarget(e: Expr) {
        when (e) {
            is Name, is Index, is SliceE, is Attr -> {}
            is TupleE -> e.items.forEach { checkTarget(it) }
            is ListE -> e.items.forEach { checkTarget(it) }
            is Starred -> checkTarget(e.e)
            is Call -> fail("слева от '=' стоит вызов функции", "слева от '=' должно быть имя переменной; для сравнения используй '=='")
            is Const -> fail("слева от '=' стоит значение, а не переменная", "сначала имя, потом значение: x = 5; для сравнения используй '=='")
            else -> fail("слева от '=' должно быть имя переменной", "для сравнения используй '=='")
        }
    }

    private fun simple(): Stmt {
        val t = cur
        val line = ln()
        if (t.t == T.NAME) {
            when (t.s) {
                "pass" -> { p++; return Pass().at(line) }
                "break" -> {
                    if (loopDepth == 0) fail("break вне цикла", "break работает только внутри for или while")
                    p++
                    return Break().at(line)
                }
                "continue" -> {
                    if (loopDepth == 0) fail("continue вне цикла", "continue работает только внутри for или while")
                    p++
                    return Continue().at(line)
                }
                "return" -> {
                    if (fnDepth == 0) fail("return вне функции", "return работает только внутри def")
                    p++
                    return Return(if (atEnd()) null else exprList()).at(line)
                }
                "import" -> {
                    p++
                    val names = ArrayList<Pair<String, String?>>()
                    do {
                        val m = dotted()
                        val alias = if (isKw("as")) { p++; name("имя после as") } else null
                        names += m to alias
                    } while (accept(","))
                    return Import(names).at(line)
                }
                "from" -> {
                    p++
                    val m = dotted()
                    if (!isKw("import")) unexpected("слово import")
                    p++
                    if (accept("*")) return FromImport(m, null).at(line)
                    val paren = accept("(")
                    val names = ArrayList<Pair<String, String?>>()
                    do {
                        if (paren && isOp(")")) break
                        val n = name("имя")
                        val alias = if (isKw("as")) { p++; name("имя после as") } else null
                        names += n to alias
                    } while (accept(","))
                    if (paren) expect(")")
                    return FromImport(m, names).at(line)
                }
                "raise" -> {
                    p++
                    val e = if (atEnd()) null else expr()
                    if (isKw("from")) { p++; expr() }
                    return Raise(e).at(line)
                }
                "global", "nonlocal" -> {
                    p++
                    val names = ArrayList<String>()
                    do { names += name("имя переменной") } while (accept(","))
                    return (if (t.s == "global") Global(names) else Nonlocal(names)).at(line)
                }
                "del" -> {
                    p++
                    val e = exprList()
                    return Del(if (e is TupleE) e.items else listOf(e)).at(line)
                }
                "assert" -> {
                    p++
                    val test = expr()
                    return Assert(test, if (accept(",")) expr() else null).at(line)
                }
            }
            caseHint(t)
        }
        val first = exprList()
        if (isOp(":") && first is Name) { // аннотация: x: int = 5
            p++
            expr()
            if (!accept("=")) return Pass().at(line)
            return Assign(listOf(first), exprList()).at(line)
        }
        if (isOp("=")) {
            val targets = ArrayList<Expr>()
            var value = first
            while (accept("=")) { targets += value; value = exprList() }
            targets.forEach { checkTarget(it) }
            return Assign(targets, value).at(line)
        }
        if (cur.t == T.OP && cur.s in AUG_OPS) {
            val op = cur.s.dropLast(1)
            p++
            if (first !is Name && first !is Index) fail("слева от $op= должна быть переменная")
            return AugAssign(first, op, exprList()).at(line)
        }
        return ExprS(first).at(line)
    }

    private fun condition(): Expr {
        val e = expr()
        if (isOp("=")) fail("в условии стоит '=', а нужно '=='", "'=' кладёт значение в переменную, а сравнивает '=='")
        return e
    }

    private fun colon(kw: String) {
        if (accept(":")) return
        if (kw == "else" && cur.t != T.NEWLINE && cur.t != T.EOF) {
            fail("после else сразу должно идти ':'", if (isKw("if")) "вместо 'else if' в Python пишут 'elif'" else "у else нет условия; если условие нужно — пиши elif")
        }
        if (cur.t == T.NEWLINE || cur.t == T.EOF) {
            fail("в конце строки с '$kw' не хватает двоеточия ':'", "строки с if, elif, else, for, while, def, try всегда заканчиваются на ':'")
        }
        unexpected("':'")
    }

    private fun ifStmt(): Stmt {
        val line = ln()
        val kw = cur.s
        p++
        val cond = condition()
        colon(kw)
        val body = block()
        var orelse: List<Stmt> = emptyList()
        if (isKw("elif")) {
            orelse = listOf(ifStmt())
        } else if (isKw("else")) {
            p++
            colon("else")
            orelse = block()
        }
        return If(cond, body, orelse).at(line)
    }

    private fun elseBlock(): List<Stmt> {
        if (!isKw("else")) return emptyList()
        p++
        colon("else")
        return block()
    }

    private fun whileStmt(): Stmt {
        val line = ln()
        p++
        val cond = condition()
        colon("while")
        loopDepth++
        val body = try { block() } finally { loopDepth-- }
        return While(cond, body, elseBlock()).at(line)
    }

    private fun forStmt(): Stmt {
        val line = ln()
        p++
        noIn = true
        val target = try { exprList() } finally { noIn = false }
        checkTarget(target)
        if (!isKw("in")) unexpected("слово in (форма: for x in список)")
        p++
        val iter = exprList()
        colon("for")
        loopDepth++
        val body = try { block() } finally { loopDepth-- }
        return For(target, iter, body, elseBlock()).at(line)
    }

    private fun defStmt(): Stmt {
        val line = ln()
        p++
        val n = name("имя функции")
        if (!accept("(")) unexpected("'(' после имени функции")
        val ps = free { params(")", true) }
        expect(")")
        if (accept("->")) expr()
        colon("def")
        val savedLoop = loopDepth
        loopDepth = 0
        fnDepth++
        val body = try { block() } finally { fnDepth--; loopDepth = savedLoop }
        return Def(n, ps, body, localsOf(ps, body)).at(line)
    }

    private fun tryStmt(): Stmt {
        val line = ln()
        p++
        colon("try")
        val body = block()
        val handlers = ArrayList<Handler>()
        while (isKw("except")) {
            p++
            val type = if (isOp(":")) null else expr()
            val n = if (isKw("as")) { p++; name("имя после as") } else null
            colon("except")
            handlers += Handler(type, n, block())
        }
        val orelse = if (handlers.isNotEmpty()) elseBlock() else emptyList()
        var final: List<Stmt> = emptyList()
        if (isKw("finally")) {
            p++
            colon("finally")
            final = block()
        }
        if (handlers.isEmpty() && final.isEmpty()) fail("после try нужен блок except", "try: ... except: ... — в except пишут, что делать при ошибке", line)
        return Try(body, handlers, orelse, final).at(line)
    }

    private fun params(end: String, annotations: Boolean): Params {
        val names = ArrayList<String>()
        val defaults = ArrayList<Expr?>()
        var vararg: String? = null
        var kwarg: String? = null
        while (!isOp(end)) {
            if (accept("**")) {
                kwarg = name("имя параметра")
            } else if (accept("*")) {
                if (cur.t == T.NAME) vararg = name("имя параметра")
            } else if (accept("/")) {
                // только позиционные — различие не важно
            } else {
                val n = name("имя параметра")
                if (n in names) fail("параметр '$n' указан дважды")
                if (annotations && accept(":")) expr()
                val d = if (accept("=")) expr() else null
                if (d == null && vararg == null && defaults.any { it != null }) {
                    fail("параметр '$n' без значения по умолчанию стоит после параметра со значением", "сначала обычные параметры, потом те, у которых есть '='")
                }
                names += n
                defaults += d
            }
            if (!accept(",")) break
        }
        return Params(names, defaults, vararg, kwarg)
    }

    // ---------- выражения ----------

    private fun endOfList(): Boolean = cur.t == T.NEWLINE || cur.t == T.EOF ||
        (cur.t == T.OP && (cur.s == ")" || cur.s == "]" || cur.s == "}" || cur.s == "=" || cur.s == ":" || cur.s == ";" || cur.s in AUG_OPS)) ||
        (noIn && isKw("in"))

    private fun starOrExpr(): Expr = if (accept("*")) Starred(bitor()) else expr()

    private fun exprList(): Expr {
        val first = starOrExpr()
        if (!isOp(",")) return first
        val items = arrayListOf(first)
        while (accept(",")) {
            if (endOfList()) break
            items += starOrExpr()
        }
        return TupleE(items)
    }

    private fun expr(): Expr {
        if (isKw("lambda")) {
            p++
            val ps = params(":", false)
            expect(":")
            return Lambda(ps, expr())
        }
        val a = orTest()
        if (isKw("if")) {
            p++
            val c = orTest()
            if (!isKw("else")) unexpected("слово else (форма: A if условие else B)")
            p++
            return IfExp(c, a, expr())
        }
        return a
    }

    private fun orTest(): Expr {
        var l = andTest()
        while (isKw("or")) { p++; l = BoolOp(false, l, andTest()) }
        return l
    }

    private fun andTest(): Expr {
        var l = notTest()
        while (isKw("and")) { p++; l = BoolOp(true, l, notTest()) }
        return l
    }

    private fun notTest(): Expr {
        if (isKw("not")) { p++; return Unary("not", notTest()) }
        return comparison()
    }

    private fun comparison(): Expr {
        val first = bitor()
        var ops: ArrayList<String>? = null
        var rest: ArrayList<Expr>? = null
        while (true) {
            val op: String = when {
                cur.t == T.OP && (cur.s == "<" || cur.s == ">" || cur.s == "==" || cur.s == "!=" || cur.s == "<=" || cur.s == ">=") -> { val s = cur.s; p++; s }
                isKw("in") && !noIn -> { p++; "in" }
                isKw("not") && toks[p + 1].t == T.NAME && toks[p + 1].s == "in" -> { p += 2; "not in" }
                isKw("is") -> { p++; if (isKw("not")) { p++; "is not" } else "is" }
                else -> break
            }
            if (ops == null) { ops = ArrayList(); rest = ArrayList() }
            ops.add(op)
            rest!!.add(bitor())
        }
        return if (ops == null) first else Compare(first, ops, rest!!)
    }

    private fun bitor(): Expr {
        var l = bitxor()
        while (isOp("|")) { p++; l = Bin("|", l, bitxor()) }
        return l
    }

    private fun bitxor(): Expr {
        var l = bitand()
        while (isOp("^")) { p++; l = Bin("^", l, bitand()) }
        return l
    }

    private fun bitand(): Expr {
        var l = shift()
        while (isOp("&")) { p++; l = Bin("&", l, shift()) }
        return l
    }

    private fun shift(): Expr {
        var l = arith()
        while (isOp("<<") || isOp(">>")) { val op = cur.s; p++; l = Bin(op, l, arith()) }
        return l
    }

    private fun arith(): Expr {
        var l = term()
        while (isOp("+") || isOp("-")) { val op = cur.s; p++; l = Bin(op, l, term()) }
        return l
    }

    private fun term(): Expr {
        var l = factor()
        while (isOp("*") || isOp("/") || isOp("//") || isOp("%")) { val op = cur.s; p++; l = Bin(op, l, factor()) }
        return l
    }

    private fun factor(): Expr {
        if (cur.t == T.OP && (cur.s == "-" || cur.s == "+" || cur.s == "~")) {
            val op = cur.s
            p++
            val e = factor()
            if (op == "-" && e is Const) {
                when (val v = e.v) {
                    is Long -> if (v != Long.MIN_VALUE) return Const(-v)
                    is Double -> return Const(-v)
                    else -> {}
                }
            }
            return Unary(op, e)
        }
        return power()
    }

    private fun power(): Expr {
        val base = primary()
        if (accept("**")) return Bin("**", base, factor())
        return base
    }

    private fun primary(): Expr {
        var e = atom()
        while (true) {
            if (isOp("(")) {
                p++
                e = free { callArgs(e) }
            } else if (isOp("[")) {
                p++
                e = free { subscript(e) }
            } else if (isOp(".")) {
                p++
                if (cur.t != T.NAME) unexpected("имя после точки")
                e = Attr(e, cur.s)
                p++
            } else {
                return e
            }
        }
    }

    private fun callArgs(fn: Expr): Expr {
        val args = ArrayList<Expr>()
        val kw = ArrayList<Pair<String, Expr>>()
        var kwStar: Expr? = null
        while (!isOp(")")) {
            if (accept("**")) {
                kwStar = expr()
            } else if (accept("*")) {
                args += Starred(expr())
            } else if (cur.t == T.NAME && cur.s !in KEYWORDS && toks[p + 1].t == T.OP && toks[p + 1].s == "=") {
                val n = cur.s
                p += 2
                kw += n to expr()
            } else {
                val a = expr()
                if (isKw("for")) {
                    args += Comp('g', a, null, compClauses())
                } else {
                    if (kw.isNotEmpty()) fail("обычный аргумент стоит после именованного", "сначала обычные аргументы, потом вида имя=значение")
                    args += a
                }
            }
            if (!accept(",")) break
        }
        if (!isOp(")")) unexpected("')' или ','")
        p++
        return Call(fn, args, kw, kwStar)
    }

    private fun subscript(obj: Expr): Expr {
        var lo: Expr? = null
        if (!isOp(":")) lo = exprList()
        if (accept(":")) {
            val hi = if (isOp(":") || isOp("]")) null else expr()
            val step = if (accept(":")) (if (isOp("]")) null else expr()) else null
            expect("]")
            return SliceE(obj, lo, hi, step)
        }
        expect("]")
        return Index(obj, lo!!)
    }

    private fun compClauses(): List<CompFor> {
        val cl = ArrayList<CompFor>()
        while (isKw("for")) {
            p++
            noIn = true
            val target = try { exprList() } finally { noIn = false }
            checkTarget(target)
            if (!isKw("in")) unexpected("слово in")
            p++
            val iter = orTest()
            val ifs = ArrayList<Expr>()
            while (isKw("if")) { p++; ifs += orTest() }
            cl += CompFor(target, iter, ifs)
        }
        return cl
    }

    private fun atom(): Expr {
        val t = cur
        when (t.t) {
            T.NUM -> { p++; return Const(t.v!!) }
            T.STR, T.FSTR -> return strings()
            T.BYTES -> {
                var b = t.v as ByteArray
                p++
                while (cur.t == T.BYTES) { b += cur.v as ByteArray; p++ }
                return Const(PyBytes(b))
            }
            T.NAME -> {
                when (t.s) {
                    "True" -> { p++; return Const(true) }
                    "False" -> { p++; return Const(false) }
                    "None" -> { p++; return Const(PyNone) }
                }
                if (t.s in KEYWORDS) unexpected()
                p++
                return Name(t.s)
            }
            T.OP -> when (t.s) {
                "(" -> { p++; return free { paren() } }
                "[" -> { p++; return free { bracket() } }
                "{" -> { p++; return free { brace() } }
                "..." -> { p++; return Const(PyNone) }
            }
            else -> {}
        }
        unexpected(if (t.t == T.NEWLINE || t.t == T.EOF) "значение" else null)
    }

    private fun paren(): Expr {
        if (accept(")")) return TupleE(emptyList())
        val first = starOrExpr()
        if (isKw("for")) {
            val c = Comp('g', first, null, compClauses())
            expect(")")
            return c
        }
        if (isOp(",")) {
            val items = arrayListOf(first)
            while (accept(",")) {
                if (isOp(")")) break
                items += starOrExpr()
            }
            expect(")")
            return TupleE(items)
        }
        expect(")")
        return first
    }

    private fun bracket(): Expr {
        if (accept("]")) return ListE(emptyList())
        val first = starOrExpr()
        if (isKw("for")) {
            val c = Comp('l', first, null, compClauses())
            expect("]")
            return c
        }
        val items = arrayListOf(first)
        while (accept(",")) {
            if (isOp("]")) break
            items += starOrExpr()
        }
        if (!isOp("]")) unexpected("']' или ','")
        p++
        return ListE(items)
    }

    private fun brace(): Expr {
        if (accept("}")) return DictE(emptyList(), emptyList())
        val first = starOrExpr()
        if (accept(":")) {
            val v = expr()
            if (isKw("for")) {
                val c = Comp('d', first, v, compClauses())
                expect("}")
                return c
            }
            val keys = arrayListOf(first)
            val vals = arrayListOf(v)
            while (accept(",")) {
                if (isOp("}")) break
                keys += expr()
                if (!accept(":")) unexpected("':' между ключом и значением")
                vals += expr()
            }
            if (!isOp("}")) unexpected("'}' или ','")
            p++
            return DictE(keys, vals)
        }
        if (isKw("for")) {
            val c = Comp('s', first, null, compClauses())
            expect("}")
            return c
        }
        val items = arrayListOf(first)
        while (accept(",")) {
            if (isOp("}")) break
            items += starOrExpr()
        }
        if (!isOp("}")) unexpected("'}' или ','")
        p++
        return SetE(items)
    }

    private fun addLit(parts: MutableList<Any>, s: String) {
        if (s.isEmpty()) return
        val last = parts.lastOrNull()
        if (last is String) parts[parts.size - 1] = last + s else parts += s
    }

    private fun strings(): Expr {
        val parts = ArrayList<Any>()
        var anyF = false
        while (cur.t == T.STR || cur.t == T.FSTR) {
            val t = cur
            p++
            if (t.t == T.STR) addLit(parts, t.s) else { anyF = true; fparts(t.s, t.raw, ln(t), parts) }
        }
        if (!anyF) return Const(parts.joinToString("") { it as String })
        return FStr(parts)
    }

    /** Разбирает f-строку на куски текста и выражения в {…}. */
    private fun fparts(src: String, raw: Boolean, line: Int, out: MutableList<Any>) {
        val lit = StringBuilder()
        var i = 0
        fun flush() {
            if (lit.isNotEmpty()) {
                addLit(out, if (raw) lit.toString() else unescape(lit.toString(), line))
                lit.setLength(0)
            }
        }
        while (i < src.length) {
            val c = src[i]
            if (c == '{') {
                if (i + 1 < src.length && src[i + 1] == '{') { lit.append('{'); i += 2; continue }
                flush()
                var j = i + 1
                var depth = 0
                var q = '\u0000'
                var colon = -1
                var bang = -1
                while (j < src.length) {
                    val d = src[j]
                    if (q != '\u0000') {
                        if (d == q) q = '\u0000'
                    } else if (d == '"' || d == '\'') {
                        q = d
                    } else if (d == '(' || d == '[' || d == '{') {
                        depth++
                    } else if (d == ')' || d == ']') {
                        depth--
                    } else if (d == '}') {
                        if (depth == 0) break
                        depth--
                    } else if (depth == 0 && colon < 0) {
                        if (d == ':') colon = j
                        else if (d == '!' && j + 1 < src.length && src[j + 1] != '=') bang = j
                    }
                    j++
                }
                if (j >= src.length) fail("в f-строке не закрыта фигурная скобка {", "после имени переменной поставь }", line)
                val exprEnd = if (bang >= 0) bang else if (colon >= 0) colon else j
                val exprSrc = src.substring(i + 1, exprEnd).trim()
                if (exprSrc.isEmpty()) fail("в f-строке пустые скобки {}", "внутри {} должно быть имя переменной или выражение", line)
                val conv = if (bang >= 0 && bang + 1 < src.length) src[bang + 1] else '\u0000'
                val spec = if (colon >= 0) src.substring(colon + 1, j) else null
                val e = try {
                    Parser(Lexer(exprSrc).lex(), line).evalInput()
                } catch (x: PySyntaxError) {
                    throw PySyntaxError(line, "в f-строке внутри {…}: ${x.message}", x.hint)
                }
                val specNode = when {
                    spec == null -> null
                    spec.indexOf('{') >= 0 -> FStr(ArrayList<Any>().also { fparts(spec, true, line, it) })
                    else -> FStr(listOf(spec))
                }
                out += FPart(e, conv, specNode)
                i = j + 1
                continue
            }
            if (c == '}') {
                if (i + 1 < src.length && src[i + 1] == '}') { lit.append('}'); i += 2; continue }
                fail("в f-строке лишняя скобка }", "чтобы вывести саму скобку, удвой её: }}", line)
            }
            lit.append(c)
            i++
        }
        flush()
    }
}
