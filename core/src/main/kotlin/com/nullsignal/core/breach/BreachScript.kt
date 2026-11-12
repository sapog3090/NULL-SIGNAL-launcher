package com.nullsignal.core.breach

import com.nullsignal.core.py.MiniPy
import com.nullsignal.core.py.PyResult

/**
 * Решение головоломки BREACH программой на Python: скрипт сам делает попытки через guess()
 * и по ответам отсекает невозможные ключи. Попыток столько же, сколько у игрока.
 */
class BreachScript(val game: Breach, out: (String) -> Unit, private val onAttempt: (Breach.Attempt) -> Unit = {}) {
    val py = MiniPy(out)

    init {
        py.maxSteps = 120_000_000L
        py.maxMillis = 90_000L
        py.set("LENGTH", game.length)
        py.set("ALPHABET", Breach.ALPHABET)
        py.set("ATTEMPTS", game.maxAttempts)
        py.def("guess") { a ->
            val code = a.getOrNull(0) as? String
                ?: py.fail("TypeError", "guess() ждёт код в кавычках, например guess(\"A1B2\")", "если код собран из символов, склей их: \"\".join(список)")
            when (game.state) {
                Breach.State.WON -> py.fail("RuntimeError", "ключ уже найден — дальше пробовать не нужно", "после победы выйди из цикла: break")
                Breach.State.LOST -> py.fail("RuntimeError", "попытки закончились", "проверяй остаток: while attempts_left() > 0:")
                Breach.State.RUNNING -> {}
            }
            val at = game.guess(code)
                ?: py.fail("ValueError", "код ${code.take(12)} не подходит: нужно ровно ${game.length} символа из $ALPHABET_HINT", "буквы — большие: A–F")
            onAttempt(at)
            Pair(at.exact, at.misplaced)
        }
        py.def("score") { a ->
            val key = a.getOrNull(0) as? String
            val attempt = a.getOrNull(1) as? String
            if (key == null || attempt == null) py.fail("TypeError", "score() ждёт два кода в кавычках: score(ключ, попытка)")
            if (!game.isValid(key) || !game.isValid(attempt)) {
                py.fail("ValueError", "score(): оба кода должны быть из ${game.length} символов $ALPHABET_HINT")
            }
            val s = Breach.score(key.uppercase(), attempt.uppercase())
            Pair(s.exact, s.misplaced)
        }
        py.def("attempts_left") { game.attemptsLeft }
        py.def("history") { game.attempts.map { Triple(it.guess, it.exact, it.misplaced) } }
    }

    fun run(src: String): PyResult = py.run(src)
    fun cancel() = py.cancel()

    class Template(val title: String, val about: String, val code: String)

    companion object {
        private const val ALPHABET_HINT = "0–9, A–F"

        const val API = "guess(\"A1B2\") → (на месте, не на месте) — тратит попытку\n" +
            "score(ключ, попытка) → такой же ответ, но попытку не тратит\n" +
            "attempts_left() — сколько попыток осталось\n" +
            "history() — список прошлых попыток: (код, на месте, не на месте)\n" +
            "LENGTH — длина ключа, ALPHABET — все символы, ATTEMPTS — всего попыток"

        val TEMPLATES = listOf(
            Template(
                "РЕШАТЕЛЬ", "готовый скрипт: отсекает варианты после каждого ответа",
                """
                # Скрипт решает головоломку сам.
                # Идея: держим список всех возможных ключей и после каждого
                # ответа выбрасываем те, которые дали бы другой ответ.
                import itertools
                import random

                # все ключи: 16 символов на 4 местах = 65536 вариантов
                variants = ["".join(p) for p in itertools.product(ALPHABET, repeat=LENGTH)]

                # если часть попыток уже сделана вручную — учитываем их ответы
                for old_code, e, m in history():
                    variants = [v for v in variants if score(v, old_code) == (e, m)]

                # первые ходы — из разных символов, чтобы узнать побольше
                openers = [ALPHABET[i:i + LENGTH] for i in range(0, 12, LENGTH)]
                if history():
                    openers = []

                while attempts_left() > 0:
                    if openers:
                        code = openers.pop(0)
                    else:
                        code = random.choice(variants)

                    exact, misplaced = guess(code)
                    print(code, "на месте:", exact, "не на месте:", misplaced)

                    if exact == LENGTH:
                        print("КЛЮЧ НАЙДЕН:", code)
                        break

                    # оставляем только ключи, которые ответили бы так же
                    variants = [v for v in variants if score(v, code) == (exact, misplaced)]
                    print("   осталось вариантов:", len(variants))
                """.trimIndent() + "\n",
            ),
            Template(
                "С НУЛЯ", "заготовка: одна попытка и разбор ответа — допиши сам",
                """
                # Что умеет скрипт:
                #   guess("A1B2")        -> (на_месте, не_на_месте), тратит попытку
                #   score(ключ, попытка) -> такой же ответ без траты попытки
                #   attempts_left(), history(), LENGTH, ALPHABET

                exact, misplaced = guess("0123")
                print("на месте:", exact, "не на месте:", misplaced)

                # подсказка: если оба числа равны 0,
                # символов 0, 1, 2, 3 в ключе нет вообще
                if exact + misplaced == 0:
                    print("символы 0123 можно вычеркнуть")
                """.trimIndent() + "\n",
            ),
            Template(
                "ПОДРЯД", "перебор 0000, 0001, 0002… — посмотри, почему так не выиграть",
                """
                # Перебор подряд: 0000, 0001, 0002...
                # Ключей 65536, а попыток всего 10 — так не успеть.
                # Запусти и убедись, потом сравни с РЕШАТЕЛЕМ.
                number = 0
                while attempts_left() > 0:
                    code = format(number, "X").zfill(LENGTH)
                    exact, misplaced = guess(code)
                    print(code, exact, misplaced)
                    if exact == LENGTH:
                        print("повезло!")
                        break
                    number += 1
                print("перебрано", number + 1, "из", 16 ** LENGTH)
                """.trimIndent() + "\n",
            ),
        )
    }
}
