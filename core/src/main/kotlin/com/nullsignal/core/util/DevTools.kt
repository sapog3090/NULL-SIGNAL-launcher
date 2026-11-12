package com.nullsignal.core.util

import com.nullsignal.core.net.Subnet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow

/** Калькулятор выражений: + − × ÷ % ^, скобки, функции и константы. */
object Calc {
    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            skip()
            require(i == s.length) { "лишний символ '${s[i]}'" }
            return v
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun eat(c: Char): Boolean { skip(); return if (i < s.length && s[i] == c) { i++; true } else false }

        private fun expr(): Double {
            var v = term()
            while (true) v = when {
                eat('+') -> v + term()
                eat('-') -> v - term()
                else -> return v
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) v = when {
                eat('*') || eat('×') -> v * unary()
                eat('/') || eat('÷') -> v / unary()
                eat('%') -> v % unary()
                else -> return v
            }
        }

        // -2^2 = -(2^2) = -4; 2^-1 = 0.5; 2^3^2 = 2^9
        private fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> power()
        }

        private fun power(): Double {
            val base = primary()
            return if (eat('^')) base.pow(unary()) else base
        }

        private fun primary(): Double {
            skip()
            if (eat('(')) { val v = expr(); require(eat(')')) { "не закрыта скобка" }; return v }
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) return number()
            if (i < s.length && s[i].isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit())) i++
                val name = s.substring(start, i).lowercase()
                if (eat('(')) {
                    val a = expr()
                    require(eat(')')) { "не закрыта скобка после $name" }
                    return when (name) {
                        "sqrt" -> kotlin.math.sqrt(a)
                        "sin" -> kotlin.math.sin(a)
                        "cos" -> kotlin.math.cos(a)
                        "tan" -> kotlin.math.tan(a)
                        "asin" -> kotlin.math.asin(a)
                        "acos" -> kotlin.math.acos(a)
                        "atan" -> kotlin.math.atan(a)
                        "ln" -> kotlin.math.ln(a)
                        "log", "lg" -> kotlin.math.log10(a)
                        "log2" -> kotlin.math.log2(a)
                        "exp" -> kotlin.math.exp(a)
                        "abs" -> abs(a)
                        "floor" -> kotlin.math.floor(a)
                        "ceil" -> kotlin.math.ceil(a)
                        "round" -> kotlin.math.round(a)
                        "deg" -> Math.toDegrees(a)
                        "rad" -> Math.toRadians(a)
                        else -> throw IllegalArgumentException("неизвестная функция $name")
                    }
                }
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> throw IllegalArgumentException("неизвестное имя $name")
                }
            }
            throw IllegalArgumentException(if (i < s.length) "неожиданный символ '${s[i]}'" else "выражение оборвалось")
        }

        private fun number(): Double {
            if (s.startsWith("0x", i, ignoreCase = true)) {
                i += 2
                val start = i
                while (i < s.length && s[i].isLetterOrDigit()) i++
                return s.substring(start, i).toLong(16).toDouble()
            }
            if (s.startsWith("0b", i, ignoreCase = true)) {
                i += 2
                val start = i
                while (i < s.length && (s[i] == '0' || s[i] == '1')) i++
                return s.substring(start, i).toLong(2).toDouble()
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == '_')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                i += 2
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).replace("_", "").toDouble()
        }
    }

    fun eval(expr: String): Double = Parser(expr.replace(',', '.')).parse()

    fun format(v: Double): String = when {
        v.isNaN() -> "не число"
        v.isInfinite() -> if (v > 0) "∞" else "-∞"
        v == kotlin.math.floor(v) && abs(v) < 1e15 -> v.toLong().toString()
        else -> {
            val s = "%.10g".format(Locale.US, v)
            val mantissa = s.substringBefore('e').let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
            if ('e' in s) mantissa + "e" + s.substringAfter('e') else mantissa
        }
    }
}

object DevTools {
    /** Перевод между системами счисления (2..36), любой длины. */
    fun convertBase(number: String, from: Int, to: Int): String {
        require(from in 2..36 && to in 2..36) { "основание от 2 до 36" }
        val clean = number.trim().removePrefix("0x").removePrefix("0b").replace("_", "")
        return BigInteger(clean, from).toString(to).uppercase()
    }

    fun allBases(number: String): List<String> {
        val n = number.trim()
        val v = when {
            n.startsWith("0x", true) -> BigInteger(n.substring(2), 16)
            n.startsWith("0b", true) -> BigInteger(n.substring(2), 2)
            n.startsWith("0o", true) -> BigInteger(n.substring(2), 8)
            else -> BigInteger(n)
        }
        return listOf("dec  $v", "hex  0x${v.toString(16).uppercase()}", "oct  0o${v.toString(8)}", "bin  0b${v.toString(2)}")
    }

    fun unixToDate(sec: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss zzz", Locale("ru")).apply { timeZone = zone }.format(Date(sec * 1000))

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun urlDecode(s: String): String = URLDecoder.decode(s, "UTF-8")

    private val pretty = Json { prettyPrint = true }

    fun jsonPretty(s: String): String = pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(s))

    /** Калькулятор подсети: 192.168.1.10/24 */
    fun subnet(cidr: String): List<String> {
        val ip = cidr.substringBefore('/').trim()
        val prefix = cidr.substringAfter('/', "32").trim().toInt()
        require(prefix in 0..32) { "префикс 0..32" }
        val a = Subnet.parse(ip)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = a and mask
        val broadcast = network or mask.inv()
        val hosts = when (prefix) {
            32 -> 1L
            31 -> 2L
            else -> (1L shl (32 - prefix)) - 2
        }
        return listOf(
            "сеть        ${Subnet.format(network)}/$prefix",
            "маска       ${Subnet.format(mask)}",
            "wildcard    ${Subnet.format(mask.inv())}",
            "broadcast   ${Subnet.format(broadcast)}",
            "хосты       ${if (prefix >= 31) Subnet.format(network) else Subnet.format(network + 1)} — ${if (prefix >= 31) Subnet.format(broadcast) else Subnet.format(broadcast - 1)}",
            "адресов     $hosts",
            "частная     ${if (Subnet.isPrivate(ip)) "да" else "нет"}",
        )
    }
}

/** Математика спидтеста. */
object Speed {
    fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / ms / 1000.0

    fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Джиттер — средняя разница между соседними замерами задержки. */
    fun jitter(rtts: List<Double>): Double =
        if (rtts.size < 2) 0.0 else rtts.zipWithNext { a, b -> abs(a - b) }.average()

    fun verdict(downMbps: Double, pingMs: Double): String = when {
        downMbps >= 100 && pingMs < 30 -> "отлично: 4K, игры, большие загрузки"
        downMbps >= 25 && pingMs < 60 -> "хорошо: HD-видео, звонки, игры"
        downMbps >= 8 -> "нормально: видео 1080p, соцсети"
        downMbps >= 2 -> "слабо: видео в низком качестве"
        else -> "очень медленно"
    }
}
