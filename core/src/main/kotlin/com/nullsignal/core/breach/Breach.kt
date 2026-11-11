package com.nullsignal.core.breach

import kotlin.random.Random

/**
 * Мини-игра «подбор ключа»: нужно угадать hex-код из [length] символов.
 * После каждой попытки известно, сколько символов стоят на своём месте (exact)
 * и сколько есть в ключе, но в другой позиции (misplaced).
 */
class Breach(
    val length: Int = 4,
    val maxAttempts: Int = 8,
    random: Random = Random.Default,
    secret: String? = null,
) {
    data class Attempt(val guess: String, val exact: Int, val misplaced: Int)

    enum class State { RUNNING, WON, LOST }

    val secret: String = secret?.uppercase() ?: buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

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
