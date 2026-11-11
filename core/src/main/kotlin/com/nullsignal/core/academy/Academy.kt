package com.nullsignal.core.academy

/** Задание урока. */
sealed interface Task {
    val question: String
    val code: String?
    val explain: String

    /** Выбор варианта. */
    data class Choice(
        override val question: String,
        override val code: String?,
        val options: List<String>,
        val correct: Int,
        override val explain: String,
    ) : Task

    /** Ответ вводится текстом. */
    data class Input(
        override val question: String,
        override val code: String?,
        val answers: List<String>,
        override val explain: String,
    ) : Task
}

data class Lesson(
    val id: String,
    val title: String,
    val theory: String,
    val example: String,
    val tasks: List<Task>,
    val reward: Int = 25,
)

object Academy {

    fun check(task: Task, answer: String): Boolean = when (task) {
        is Task.Choice -> answer.toIntOrNull() == task.correct
        is Task.Input -> normalize(answer).let { a -> task.answers.any { normalize(it) == a } }
    }

    /** Пробелы по краям, лишние пробелы внутри, кавычки вокруг и регистр не важны. */
    fun normalize(s: String): String =
        s.trim().trim('"', '\'', '«', '»').trim().replace(Regex("\\s+"), " ").lowercase()

    /** Урок открыт, если пройден предыдущий. */
    fun unlocked(index: Int, completed: Set<String>): Boolean =
        index == 0 || lessons.getOrNull(index - 1)?.id in completed

    val lessons: List<Lesson> = listOf(
        Lesson(
            id = "py1",
            title = "print и переменные",
            theory = "Переменная — коробка с именем, в которой лежит значение. print() выводит значение на экран. " +
                "Текст (строка) пишется в кавычках, числа — без. Python различает большие и маленькие буквы: port и Port — разные переменные.",
            example = "target = \"192.168.1.1\"\nport = 80\nprint(target)\nprint(port)",
            tasks = listOf(
                Task.Choice(
                    "Что выведет код?", "name = \"ghost\"\nprint(name)",
                    listOf("name", "ghost", "\"ghost\"", "ошибка"), 1,
                    "print выводит значение переменной, и без кавычек.",
                ),
                Task.Choice(
                    "Почему этот код упадёт?", "Port = 22\nprint(port)",
                    listOf("всё работает", "Port и port — разные переменные", "надо писать print[port]", "22 надо взять в кавычки"), 1,
                    "Переменная создана как Port, а печатается port — такой нет. Ошибка NameError.",
                ),
                Task.Input(
                    "Впиши функцию, которая выведет host на экран:", "host = \"router\"\n____(host)",
                    listOf("print"), "print(host) — выводит router.",
                ),
            ),
        ),
        Lesson(
            id = "py2",
            title = "Строки и f-строки",
            theory = "Строки можно склеивать через +. len() считает длину. f-строка подставляет переменные в фигурные скобки: f\"порт {port}\". " +
                "Склеить строку с числом через + нельзя — число сначала превращают в строку: str(80).",
            example = "ip = \"10.0.0.5\"\nport = 443\nprint(f\"цель: {ip}:{port}\")",
            tasks = listOf(
                Task.Input(
                    "Что выведет код?", "ip = \"10.0.0.5\"\nport = 443\nprint(f\"{ip}:{port}\")",
                    listOf("10.0.0.5:443"), "f-строка подставила обе переменные.",
                ),
                Task.Input("Что выведет код?", "print(len(\"signal\"))", listOf("6"), "В слове signal 6 букв."),
                Task.Choice(
                    "Что выведет код?", "print(\"null\" + \"signal\")",
                    listOf("null signal", "nullsignal", "null+signal", "ошибка"), 1,
                    "+ склеивает строки без пробела.",
                ),
                Task.Choice(
                    "Что будет?", "print(\"port \" + 80)",
                    listOf("port 80", "port80", "ошибка: нельзя сложить строку и число", "80"), 2,
                    "TypeError. Правильно: \"port \" + str(80) или f\"port {80}\".",
                ),
            ),
        ),
        Lesson(
            id = "py3",
            title = "Условия if / elif / else",
            theory = "if проверяет условие. == сравнивает, а = присваивает — их часто путают. После условия ставится двоеточие, " +
                "а код внутри сдвигается на 4 пробела. elif — «иначе если», else — «во всех остальных случаях». if, elif, else пишутся с маленькой буквы.",
            example = "x = \"popa\"\n\nif x == 10:\n    print(\"x равен десяти\")\nelse:\n    print(x)",
            tasks = listOf(
                Task.Choice(
                    "Какая строка написана правильно?", null,
                    listOf("if x = 10:", "if x == 10", "if x == 10:", "If x == 10:"), 2,
                    "Нужны и ==, и двоеточие, и маленькая буква в if.",
                ),
                Task.Input(
                    "Что выведет код?",
                    "level = 3\nif level > 5:\n    print(\"root\")\nelif level > 1:\n    print(\"user\")\nelse:\n    print(\"guest\")",
                    listOf("user"), "3 не больше 5, но больше 1 — сработал elif.",
                ),
                Task.Choice(
                    "Проверка пароля. Что выведет?",
                    "password = \"12345678\"\nif len(password) >= 8 and not password.isdigit():\n    print(\"ok\")\nelse:\n    print(\"weak\")",
                    listOf("ok", "weak", "ошибка", "ничего"), 1,
                    "Длина 8, но пароль из одних цифр — isdigit() вернул True, условие не выполнено.",
                ),
            ),
        ),
        Lesson(
            id = "py4",
            title = "Списки и циклы",
            theory = "Список хранит много значений: ports = [22, 80, 443]. Нумерация с нуля: ports[0] — это 22. " +
                "for перебирает элементы по одному. range(1, 4) даёт числа 1, 2, 3 — правая граница не входит.",
            example = "ports = [22, 80, 443]\nfor p in ports:\n    print(f\"проверяю порт {p}\")",
            tasks = listOf(
                Task.Input("Что выведет код?", "ports = [22, 80, 443]\nprint(len(ports))", listOf("3"), "В списке 3 элемента."),
                Task.Input("Что выведет код?", "ports = [22, 80, 443]\nprint(ports[0])", listOf("22"), "Индексы начинаются с 0."),
                Task.Choice(
                    "Сколько строк напечатает цикл?", "for i in range(1, 4):\n    print(i)",
                    listOf("3", "4", "1", "бесконечно"), 0, "Напечатает 1, 2, 3 — четвёрка не входит.",
                ),
                Task.Input(
                    "Что выведет код?",
                    "count = 0\nfor p in [21, 22, 23, 80]:\n    if p < 25:\n        count = count + 1\nprint(count)",
                    listOf("3"), "Меньше 25 только 21, 22 и 23.",
                ),
            ),
        ),
        Lesson(
            id = "py5",
            title = "Функции",
            theory = "Функция — кусок кода с именем, который можно вызывать много раз. Объявляется через def, " +
                "результат отдаётся через return. Так из маленьких функций собирают большие утилиты.",
            example = "def is_web(port):\n    return port == 80 or port == 443\n\nprint(is_web(443))",
            tasks = listOf(
                Task.Input("Что выведет код?", "def double(x):\n    return x * 2\n\nprint(double(21))", listOf("42"), "21 * 2 = 42."),
                Task.Choice(
                    "Что выведет код?", "def greet(name):\n    print(\"привет, \" + name)\n\ngreet(\"оператор\")",
                    listOf("привет, оператор", "привет, name", "ничего", "ошибка"), 0,
                    "В name попала строка \"оператор\".",
                ),
                Task.Input("Ключевое слово, с которого начинается объявление функции:", null, listOf("def"), "def — от define, «определить»."),
            ),
        ),
        Lesson(
            id = "py6",
            title = "Словари",
            theory = "Словарь хранит пары ключ → значение: {22: \"ssh\", 80: \"http\"}. Значение берут по ключу: d[80]. " +
                "Если ключа нет, d[key] падает с ошибкой, а d.get(key, \"запасное\") вернёт запасное значение.",
            example = "services = {22: \"ssh\", 80: \"http\", 443: \"https\"}\nfor port in [22, 443, 8080]:\n    print(port, services.get(port, \"?\"))",
            tasks = listOf(
                Task.Input("Что выведет код?", "services = {22: \"ssh\", 80: \"http\"}\nprint(services[80])", listOf("http"), "По ключу 80 лежит \"http\"."),
                Task.Input(
                    "Что выведет код?", "services = {22: \"ssh\"}\nprint(services.get(443, \"unknown\"))",
                    listOf("unknown"), "Ключа 443 нет — get вернул запасное значение.",
                ),
                Task.Choice(
                    "Что будет?", "services = {22: \"ssh\"}\nprint(services[443])",
                    listOf("None", "unknown", "ошибка KeyError", "443"), 2, "Квадратные скобки с несуществующим ключом дают KeyError.",
                ),
            ),
        ),
        Lesson(
            id = "py7",
            title = "Ошибки: try / except",
            theory = "Сеть ненадёжна: хост не ответил, порт закрыт, пользователь ввёл буквы вместо числа. " +
                "try/except ловит ошибку, и программа не падает, а делает запасной вариант.",
            example = "try:\n    port = int(input(\"порт: \"))\nexcept ValueError:\n    print(\"это не число\")",
            tasks = listOf(
                Task.Choice(
                    "Что выведет код?", "try:\n    x = int(\"abc\")\nexcept ValueError:\n    x = 0\nprint(x)",
                    listOf("abc", "0", "ошибка", "None"), 1, "\"abc\" не число → ValueError → x = 0.",
                ),
                Task.Input(
                    "Что выведет код?", "try:\n    print(10 / 0)\nexcept ZeroDivisionError:\n    print(\"нельзя\")",
                    listOf("нельзя"), "Деление на ноль поймано.",
                ),
            ),
        ),
        Lesson(
            id = "py8",
            title = "Хеши: hashlib",
            theory = "Сайты хранят не сами пароли, а их хеши — «отпечатки». Хеш нельзя превратить обратно в текст, " +
                "но одинаковый текст всегда даёт одинаковый хеш. Поэтому короткие и популярные пароли находят по словарям, " +
                "а длинные уникальные — нет. Вывод безопасника: пароли длинные и разные.",
            example = "import hashlib\n\nh = hashlib.sha256(\"hello\".encode()).hexdigest()\nprint(h)",
            tasks = listOf(
                Task.Choice(
                    "Можно ли превратить хеш sha256 обратно в текст?", null,
                    listOf("да, функцией decode()", "нет, хеш односторонний", "только с root-правами", "да, если знать ключ"), 1,
                    "Хеш — не шифр. Ключа нет, обратной операции нет.",
                ),
                Task.Choice(
                    "Сколько символов в hex-записи sha256?", "import hashlib\nprint(len(hashlib.sha256(b\"a\").hexdigest()))",
                    listOf("32", "64", "256", "1"), 1, "256 бит = 32 байта = 64 hex-символа.",
                ),
                Task.Choice(
                    "Что выведет код?",
                    "import hashlib\na = hashlib.md5(b\"qwerty\").hexdigest()\nb = hashlib.md5(b\"qwerty\").hexdigest()\nprint(a == b)",
                    listOf("True", "False", "ошибка"), 0, "Один и тот же текст — один и тот же хеш.",
                ),
            ),
        ),
        Lesson(
            id = "py9",
            title = "Финал: свой порт-сканер",
            theory = "Модуль socket открывает сетевые соединения. connect_ex пытается подключиться и возвращает 0, если порт открыт. " +
                "Это первая настоящая утилита — как NETSCAN в этом лаунчере. Запусти её на ПК или в приложении Pydroid 3 / Termux. " +
                "Сканируй только свой роутер и свои устройства.",
            example = "import socket\n\ndef check(host, port):\n    s = socket.socket()\n    s.settimeout(0.5)\n    result = s.connect_ex((host, port))\n    s.close()\n    return result == 0\n\nrouter = \"192.168.1.1\"\nfor port in [22, 53, 80, 443]:\n    if check(router, port):\n        print(f\"{port} открыт\")",
            tasks = listOf(
                Task.Choice(
                    "Что возвращает connect_ex, если порт открыт?", null,
                    listOf("True", "0", "1", "\"open\""), 1, "0 значит «ошибок нет, соединение установлено».",
                ),
                Task.Choice(
                    "Зачем s.settimeout(0.5)?", null,
                    listOf("чтобы не ждать вечно закрытые порты", "чтобы ускорить интернет", "для шифрования", "ни зачем"), 0,
                    "Без таймаута сканер может висеть на каждом молчащем порту десятки секунд.",
                ),
                Task.Input(
                    "Допиши вызов, чтобы проверить порт 8080 на роутере:", "check(router, ____)",
                    listOf("8080"), "check(router, 8080).",
                ),
                Task.Choice(
                    "Где можно запускать сканер?", null,
                    listOf("в своей сети или с письменного разрешения владельца", "в школьной сети, если интересно", "на любом сайте", "у соседей"), 0,
                    "Всё остальное — уже статья. Белый хакер всегда работает с разрешением.",
                ),
            ),
            reward = 100,
        ),
    )
}
