package com.nullsignal.core.breach

import kotlin.random.Random

/**
 * Мини-игра «подбор ключа»: нужно угадать hex-код из [length] символов.
 * После каждой попытки известно, сколько символов стоят на своём месте (exact)
 * и сколько есть в ключе, но в другой позиции (misplaced).
 */
class Breach(
    val length: Int = 4,
    val maxAttempts: Int = 10,
    random: Random = Random.Default,
    secret: String? = null,
) {
    data class Attempt(val guess: String, val exact: Int, val misplaced: Int)

    enum class State { RUNNING, WON, LOST }

    val secret: String = secret?.uppercase() ?: buildString { repeat(this@Breach.length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private val _attempts = mutableListOf<Attempt>()
    val attempts: List<Attempt> get() = _attempts

    val state: State
        get() = when {
            _attempts.lastOrNull()?.exact == length -> State.WON
            _attempts.size >= maxAttempts -> State.LOST
            else -> State.RUNNING
        }

    val attemptsLeft: Int get() = maxAttempts - _attempts.size

    init {
        require(this.secret.length == length && this.secret.all { it in ALPHABET }) { "Неверный ключ" }
    }

    /** Возвращает null, если ввод некорректный или игра уже окончена. */
    fun guess(input: String): Attempt? {
        val g = input.trim().uppercase()
        if (state != State.RUNNING || !isValid(g)) return null
        val a = score(secret, g)
        _attempts += a
        return a
    }

    fun isValid(input: String): Boolean = input.length == length && input.uppercase().all { it in ALPHABET }

    /** Что уже известно: сколько ключей ещё подходит под все ответы и какие символы возможны на каждом месте. */
    class Analysis(val remaining: Int, val byPosition: List<Set<Char>>, val impossible: Set<Char>)

    /** Перебирает все ключи и оставляет те, что не противоречат ни одной попытке. null — ключ слишком длинный. */
    fun analyze(): Analysis? {
        if (length > 5) return null
        val n = ALPHABET.length
        var total = 1
        repeat(length) { total *= n }
        val done = _attempts.toList() // снимок: считаем в фоне, пока игрок может сделать новый ход
        val guesses = done.map { a -> IntArray(length) { ALPHABET.indexOf(a.guess[it]) } }
        val seen = Array(length) { BooleanArray(n) }
        val cand = IntArray(length)
        val cs = IntArray(n)
        val cg = IntArray(n)
        var remaining = 0
        for (code in 0 until total) {
            var x = code
            for (i in length - 1 downTo 0) { cand[i] = x % n; x /= n }
            var ok = true
            for (k in guesses.indices) {
                val g = guesses[k]
                var e = 0
                cs.fill(0)
                cg.fill(0)
                for (i in 0 until length) {
                    if (cand[i] == g[i]) e++ else { cs[cand[i]]++; cg[g[i]]++ }
                }
                if (e != done[k].exact) { ok = false; break }
                var m = 0
                for (j in 0 until n) m += minOf(cs[j], cg[j])
                if (m != done[k].misplaced) { ok = false; break }
            }
            if (ok) {
                remaining++
                for (i in 0 until length) seen[i][cand[i]] = true
            }
        }
        val byPos = seen.map { row -> ALPHABET.filterIndexed { j, _ -> row[j] }.toSet() }
        return Analysis(remaining, byPos, ALPHABET.toSet() - byPos.flatten().toSet())
    }

    companion object {
        const val ALPHABET = "0123456789ABCDEF"

        fun score(secret: String, guess: String): Attempt {
            val exact = secret.indices.count { secret[it] == guess[it] }
            val s = secret.indices.filter { secret[it] != guess[it] }.groupingBy { secret[it] }.eachCount()
            val g = secret.indices.filter { secret[it] != guess[it] }.groupingBy { guess[it] }.eachCount()
            val misplaced = g.entries.sumOf { (c, n) -> minOf(n, s[c] ?: 0) }
            return Attempt(guess, exact, misplaced)
        }
    }
}
