package com.nullsignal.core.academy

import com.nullsignal.core.py.MiniPy
import com.nullsignal.core.py.PyProblem

/** Задача, которую решают настоящим кодом: программа запускается и проверяется автоматически. */
class PracticeTask(
    val id: String,
    val title: String,
    val statement: String,
    val starter: String,
    /** Выражение → каким должен быть его результат (как его печатает repr). */
    val tests: List<Pair<String, String>>,
    val solution: String,
    /** Для задач «на вывод»: что программа должна напечатать. */
    val expectedOutput: String? = null,
    val reward: Int = 15,
)

class CheckLine(val ok: Boolean, val text: String)

class CheckResult(val passed: Boolean, val lines: List<CheckLine>, val output: String, val problem: PyProblem?)

object Practice {
    private fun norm(s: String) = s.lines().joinToString("\n") { it.trimEnd() }.trim()

    fun check(task: PracticeTask, code: String): CheckResult {
        val out = StringBuilder()
        val py = MiniPy { if (out.length < 20_000) out.append(it) }
        py.maxSteps = 5_000_000L
        py.maxMillis = 8_000L
        py.realSleep = false
        val r = py.run(code)
        if (r.problem != null) return CheckResult(false, emptyList(), out.toString(), r.problem)
        if (r.halted != null) return CheckResult(false, listOf(CheckLine(false, "программа остановлена: ${r.halted}")), out.toString(), null)
        val lines = ArrayList<CheckLine>()
        var ok = true
        if (task.expectedOutput != null) {
            val got = norm(out.toString())
            val want = norm(task.expectedOutput)
            if (got == want) {
                lines += CheckLine(true, "вывод совпал: $want")
            } else {
                ok = false
                lines += CheckLine(false, "нужно вывести:\n$want\nа получилось:\n${got.ifEmpty { "(ничего)" }}")
            }
        }
        for ((expr, want) in task.tests) {
            val t = py.run("__got = repr($expr)")
            val got = py.get("__got") as? String
            val p = t.problem
            when {
                p != null -> {
                    ok = false
                    val hint = if (p.type == "NameError") "функция должна называться точно как в условии" else p.hint
                    lines += CheckLine(false, "$expr → ${p.type}: ${p.message}" + (hint?.let { "\n  ↳ $it" } ?: ""))
                }
                t.halted != null -> { ok = false; lines += CheckLine(false, "$expr → не дождался ответа: ${t.halted}") }
                got == want -> lines += CheckLine(true, "$expr → $got")
                else -> {
                    ok = false
                    lines += CheckLine(false, "$expr → получилось $got, а нужно $want" + if (got == "None") "\n  ↳ функция ничего не вернула: нужен return" else "")
                }
            }
            if (lines.count { !it.ok } >= 3) break
        }
        return CheckResult(ok, lines, out.toString(), null)
    }

    val tasks: List<PracticeTask> = listOf(
        PracticeTask(
            "p1", "Привет, оператор",
            "В переменной name лежит ник. Выведи строку: Привет, ghost! — но не вписывай ник руками, подставь переменную.",
            "name = \"ghost\"\n# выведи: Привет, ghost!\n",
            emptyList(),
            "name = \"ghost\"\nprint(f\"Привет, {name}!\")\n",
            expectedOutput = "Привет, ghost!", reward = 10,
        ),
        PracticeTask(
            "p2", "Сумма списка",
            "Напиши функцию total(nums): она складывает все числа списка и возвращает сумму. Для пустого списка — 0.",
            "def total(nums):\n    result = 0\n    # пройди по списку и прибавь каждое число\n\n    return result\n",
            listOf("total([1, 2, 3])" to "6", "total([])" to "0", "total([10, -4])" to "6"),
            "def total(nums):\n    result = 0\n    for n in nums:\n        result += n\n    return result\n",
        ),
        PracticeTask(
            "p3", "Только чётные",
            "Функция evens(nums) возвращает новый список, в котором остались только чётные числа (те, что делятся на 2 без остатка).",
            "def evens(nums):\n    result = []\n\n    return result\n",
            listOf("evens([1, 2, 3, 4, 10])" to "[2, 4, 10]", "evens([7])" to "[]", "evens([0, -2])" to "[0, -2]"),
            "def evens(nums):\n    result = []\n    for n in nums:\n        if n % 2 == 0:\n            result.append(n)\n    return result\n",
        ),
        PracticeTask(
            "p4", "Наоборот",
            "Функция reverse(s) возвращает строку задом наперёд. Подсказка: срез с шагом -1.",
            "def reverse(s):\n    return s\n",
            listOf("reverse(\"abc\")" to "'cba'", "reverse(\"\")" to "''", "reverse(\"шифр\")" to "'рфиш'"),
            "def reverse(s):\n    return s[::-1]\n",
        ),
        PracticeTask(
            "p5", "Палиндром",
            "Функция is_palindrome(s) возвращает True, если строка читается одинаково в обе стороны. Регистр букв и пробелы не считаются.",
            "def is_palindrome(s):\n    # убери пробелы, приведи к нижнему регистру, сравни с перевёрнутой\n    return False\n",
            listOf("is_palindrome(\"Level\")" to "True", "is_palindrome(\"А роза упала на лапу Азора\")" to "True", "is_palindrome(\"signal\")" to "False"),
            "def is_palindrome(s):\n    t = s.lower().replace(\" \", \"\")\n    return t == t[::-1]\n",
        ),
        PracticeTask(
            "p6", "Счётчик слов",
            "Функция count_words(text) возвращает словарь: слово → сколько раз оно встретилось. Слова разделены пробелами.",
            "def count_words(text):\n    counts = {}\n    for word in text.split():\n        pass\n    return counts\n",
            listOf(
                "count_words(\"a b a\")" to "{'a': 2, 'b': 1}", "count_words(\"\")" to "{}",
                "count_words(\"to be or not to be\")" to "{'to': 2, 'be': 2, 'or': 1, 'not': 1}",
            ),
            "def count_words(text):\n    counts = {}\n    for word in text.split():\n        counts[word] = counts.get(word, 0) + 1\n    return counts\n",
        ),
        PracticeTask(
            "p7", "FizzBuzz",
            "Функция fizzbuzz(n) возвращает список строк для чисел от 1 до n: вместо чисел, кратных 3, — \"Fizz\", кратных 5 — \"Buzz\", кратных и 3, и 5 — \"FizzBuzz\".",
            "def fizzbuzz(n):\n    result = []\n    for i in range(1, n + 1):\n        result.append(str(i))\n    return result\n",
            listOf("fizzbuzz(5)" to "['1', '2', 'Fizz', '4', 'Buzz']", "fizzbuzz(15)[-1]" to "'FizzBuzz'", "len(fizzbuzz(30))" to "30"),
            "def fizzbuzz(n):\n    result = []\n    for i in range(1, n + 1):\n        if i % 15 == 0:\n            result.append(\"FizzBuzz\")\n        elif i % 3 == 0:\n            result.append(\"Fizz\")\n" +
                "        elif i % 5 == 0:\n            result.append(\"Buzz\")\n        else:\n            result.append(str(i))\n    return result\n",
        ),
        PracticeTask(
            "p8", "Надёжный пароль",
            "Функция strong(password) возвращает True, если пароль не короче 8 символов и в нём есть цифра, большая буква и маленькая буква.",
            "def strong(password):\n    has_digit = False\n    for ch in password:\n        if ch.isdigit():\n            has_digit = True\n    return has_digit\n",
            listOf(
                "strong(\"Qwerty12\")" to "True", "strong(\"qwerty12\")" to "False", "strong(\"Ab1\")" to "False",
                "strong(\"PASSWORD123\")" to "False", "strong(\"Tr0ub4dor&3\")" to "True",
            ),
            "def strong(password):\n    if len(password) < 8:\n        return False\n    digit = upper = lower = False\n    for ch in password:\n        if ch.isdigit():\n            digit = True\n" +
                "        elif ch.isupper():\n            upper = True\n        elif ch.islower():\n            lower = True\n    return digit and upper and lower\n",
            reward = 20,
        ),
        PracticeTask(
            "p9", "Это IP-адрес?",
            "Функция valid_ip(s) возвращает True, если строка — правильный адрес IPv4: четыре числа от 0 до 255 через точку.",
            "def valid_ip(s):\n    parts = s.split(\".\")\n    # частей должно быть 4, каждая — число от 0 до 255\n    return False\n",
            listOf(
                "valid_ip(\"192.168.1.1\")" to "True", "valid_ip(\"256.1.1.1\")" to "False", "valid_ip(\"1.2.3\")" to "False",
                "valid_ip(\"a.b.c.d\")" to "False", "valid_ip(\"10.0.0.0\")" to "True",
            ),
            "def valid_ip(s):\n    parts = s.split(\".\")\n    if len(parts) != 4:\n        return False\n    for p in parts:\n        if not p.isdigit():\n            return False\n" +
                "        if int(p) > 255:\n            return False\n    return True\n",
            reward = 20,
        ),
        PracticeTask(
            "p10", "Спрячь номер",
            "Функция mask(s) заменяет звёздочками все символы строки, кроме последних четырёх. Так банки показывают номер карты.",
            "def mask(s):\n    return s\n",
            listOf("mask(\"4276123456789012\")" to "'************9012'", "mask(\"1234\")" to "'1234'", "mask(\"79001234567\")" to "'*******4567'"),
            "def mask(s):\n    return \"*\" * (len(s) - 4) + s[-4:]\n",
        ),
        PracticeTask(
            "p11", "Шифр Цезаря",
            "Функция caesar(text, shift) сдвигает каждую маленькую латинскую букву на shift позиций по алфавиту (после z снова идёт a). Остальные символы не меняются.",
            "def caesar(text, shift):\n    result = \"\"\n    for ch in text:\n        # ord(ch) — номер символа, chr(n) — символ по номеру\n        result += ch\n    return result\n",
            listOf(
                "caesar(\"abc\", 1)" to "'bcd'", "caesar(\"xyz\", 3)" to "'abc'", "caesar(\"hello, world\", 13)" to "'uryyb, jbeyq'",
                "caesar(caesar(\"signal\", 5), -5)" to "'signal'",
            ),
            "def caesar(text, shift):\n    result = \"\"\n    for ch in text:\n        if \"a\" <= ch <= \"z\":\n            result += chr((ord(ch) - ord(\"a\") + shift) % 26 + ord(\"a\"))\n" +
                "        else:\n            result += ch\n    return result\n",
            reward = 25,
        ),
        PracticeTask(
            "p12", "В двоичную",
            "Функция to_bin(n) переводит неотрицательное целое число в двоичную запись (строку из 0 и 1). Готовую bin() не используй — сделай сам через деление на 2.",
            "def to_bin(n):\n    digits = \"\"\n    # n % 2 — последняя двоичная цифра, n // 2 — число без неё\n    return digits\n",
            listOf("to_bin(5)" to "'101'", "to_bin(0)" to "'0'", "to_bin(255)" to "'11111111'"),
            "def to_bin(n):\n    if n == 0:\n        return \"0\"\n    digits = \"\"\n    while n > 0:\n        digits = str(n % 2) + digits\n        n = n // 2\n    return digits\n",
            reward = 20,
        ),
        PracticeTask(
            "p13", "Самый частый",
            "Функция most_common(items) возвращает элемент, который встречается в списке чаще всех.",
            "def most_common(items):\n    counts = {}\n\n    return None\n",
            listOf("most_common([\"a\", \"b\", \"a\"])" to "'a'", "most_common([3, 3, 1, 1, 1])" to "1", "most_common([\"x\"])" to "'x'"),
            "def most_common(items):\n    counts = {}\n    for x in items:\n        counts[x] = counts.get(x, 0) + 1\n    return max(counts, key=counts.get)\n",
            reward = 20,
        ),
        PracticeTask(
            "p14", "Простые числа",
            "Функция primes(n) возвращает список всех простых чисел от 2 до n включительно. Простое число делится только на 1 и на себя.",
            "def primes(n):\n    result = []\n    for x in range(2, n + 1):\n        pass\n    return result\n",
            listOf("primes(10)" to "[2, 3, 5, 7]", "primes(1)" to "[]", "primes(30)[-1]" to "29", "len(primes(100))" to "25"),
            "def primes(n):\n    result = []\n    for x in range(2, n + 1):\n        ok = True\n        for d in range(2, int(x ** 0.5) + 1):\n            if x % d == 0:\n                ok = False\n" +
                "                break\n        if ok:\n            result.append(x)\n    return result\n",
            reward = 25,
        ),
        PracticeTask(
            "p15", "Журнал входов",
            "В журнале сервера строки вида \"FAIL 10.0.0.5\" и \"OK 10.0.0.7\". Функция count_fail(lines) возвращает словарь: адрес → сколько у него неудачных входов (FAIL). Так админы замечают подбор пароля.",
            "def count_fail(lines):\n    result = {}\n    for line in lines:\n        status, ip = line.split()\n\n    return result\n",
            listOf(
                "count_fail([\"FAIL 10.0.0.5\", \"OK 10.0.0.7\", \"FAIL 10.0.0.5\", \"FAIL 10.0.0.9\"])" to "{'10.0.0.5': 2, '10.0.0.9': 1}",
                "count_fail([\"OK 1.1.1.1\"])" to "{}",
            ),
            "def count_fail(lines):\n    result = {}\n    for line in lines:\n        status, ip = line.split()\n        if status == \"FAIL\":\n            result[ip] = result.get(ip, 0) + 1\n    return result\n",
            reward = 25,
        ),
        PracticeTask(
            "p16", "Hex-дамп",
            "Функция hexdump(s) возвращает коды символов строки в шестнадцатеричном виде, по две цифры, через пробел. Так выглядят данные в любом hex-редакторе.",
            "def hexdump(s):\n    parts = []\n    for ch in s:\n        # format(число, \"02x\") — две hex-цифры\n        pass\n    return \" \".join(parts)\n",
            listOf("hexdump(\"Hi\")" to "'48 69'", "hexdump(\"\")" to "''", "hexdump(\"AZ az\")" to "'41 5a 20 61 7a'"),
            "def hexdump(s):\n    parts = []\n    for ch in s:\n        parts.append(format(ord(ch), \"02x\"))\n    return \" \".join(parts)\n",
            reward = 25,
        ),
    )
}
