package com.nullsignal.core.games

/** «Найди дыру в коде»: учебные примеры типичных уязвимостей (и как их чинить). */
object VulnHunt {
    data class Snippet(val title: String, val lines: List<String>, val vulnerable: Int, val explain: String, val fix: String)

    val snippets: List<Snippet> = listOf(
        Snippet(
            "вход в админку",
            listOf(
                "def login(user, password):",
                "    ADMIN_PASSWORD = \"admin123\"",
                "    if user == \"admin\":",
                "        return password == ADMIN_PASSWORD",
                "    return check_db(user, password)",
            ),
            1, "Пароль зашит прямо в код. Кто увидит исходники — тот и админ.",
            "Хранить хеш пароля в базе или секрет в переменных окружения.",
        ),
        Snippet(
            "поиск пользователя",
            listOf(
                "def find_user(name):",
                "    query = \"SELECT * FROM users WHERE name = '\" + name + \"'\"",
                "    return db.execute(query)",
            ),
            1, "SQL-инъекция: ввод пользователя вклеивается прямо в запрос и может изменить его смысл.",
            "Параметризованный запрос: db.execute(\"... WHERE name = ?\", (name,))",
        ),
        Snippet(
            "регистрация",
            listOf(
                "def register(user, password):",
                "    if len(password) < 8:",
                "        raise ValueError(\"короткий пароль\")",
                "    db.save(user, password)",
            ),
            3, "Пароль хранится открытым текстом. Утечёт база — утекут все пароли.",
            "Хранить только медленный хеш с солью: bcrypt / argon2.",
        ),
        Snippet(
            "хеширование пароля",
            listOf(
                "import hashlib",
                "def store(user, password):",
                "    h = hashlib.md5(password.encode()).hexdigest()",
                "    db.save(user, h)",
            ),
            2, "MD5 слишком быстрый и без соли: популярные пароли находятся по готовым таблицам.",
            "bcrypt или argon2 — медленные хеши с солью.",
        ),
        Snippet(
            "скачивание файла",
            listOf(
                "def download(filename):",
                "    path = \"files/\" + filename",
                "    return open(path, \"rb\").read()",
            ),
            1, "Обход каталогов: имя вида ../../secret.txt выводит за пределы папки files.",
            "Проверять, что итоговый путь лежит внутри разрешённой папки.",
        ),
        Snippet(
            "калькулятор",
            listOf(
                "expr = input(\"пример: \")",
                "result = eval(expr)",
                "print(result)",
            ),
            1, "eval выполняет любой код Python, который ввёл пользователь.",
            "Разбирать выражение самому или использовать ast.literal_eval для данных.",
        ),
        Snippet(
            "код сброса пароля",
            listOf(
                "import random",
                "def reset_code():",
                "    return random.randint(100000, 999999)",
            ),
            2, "random предсказуем — он не для безопасности. Код сброса можно угадать.",
            "Модуль secrets: secrets.randbelow(900000) + 100000",
        ),
        Snippet(
            "веб-сервер",
            listOf(
                "app = Flask(__name__)",
                "@app.route(\"/\")",
                "def index(): return \"hi\"",
                "app.run(host=\"0.0.0.0\", debug=True)",
            ),
            3, "Режим отладки открыт в сеть: отладчик Flask позволяет выполнять код на сервере.",
            "debug=False в продакшене, отладку — только локально.",
        ),
        Snippet(
            "вход в банк",
            listOf(
                "import requests",
                "def login(user, password):",
                "    r = requests.post(\"http://bank.example/login\",",
                "                      data={\"u\": user, \"p\": password})",
                "    return r.ok",
            ),
            2, "Пароль уходит по http без шифрования — его видно в любой общей сети.",
            "Только https.",
        ),
    )
}
