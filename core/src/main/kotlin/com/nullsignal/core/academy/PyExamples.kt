package com.nullsignal.core.academy

/** Готовые программы для песочницы: их можно запускать, менять и разбирать. */
object PyExamples {
    class Example(val title: String, val about: String, val code: String)

    private const val D = "$" // в «сырой» строке Kotlin знак доллара иначе не написать

    val list: List<Example> = listOf(
        Example(
            "Первые шаги", "переменные, условия, циклы",
            """
            # Переменные, условия и циклы — всё в одном примере.
            # Меняй значения и смотри, что получится.
            name = "оператор"
            level = 3

            print(f"Привет, {name}! Уровень: {level}")

            if level >= 3:
                print("доступ открыт")
            else:
                print("нужно подкачаться")

            for i in range(1, 4):
                print("шаг", i)

            total = 0
            while total < 10:
                total += 3
            print("итог:", total)
            """,
        ),
        Example(
            "Генератор паролей", "случайные надёжные пароли",
            """
            # secrets — генератор случайностей для паролей и ключей
            import secrets
            import string

            alphabet = string.ascii_letters + string.digits + "!@#$D%&*"

            def password(length=16):
                return "".join(secrets.choice(alphabet) for _ in range(length))

            for i in range(5):
                print(password())

            words = ["волк", "сигнал", "ноль", "эхо", "тень", "ключ", "шум", "порт"]
            print("из слов:", "-".join(secrets.choice(words) for _ in range(4)))
            """,
        ),
        Example(
            "Хеши", "отпечатки данных: md5, sha1, sha256",
            """
            # Хеш — отпечаток данных. Изменишь одну букву — хеш станет совсем другим.
            import hashlib

            for text in ["password", "Password", "password1"]:
                h = hashlib.sha256(text.encode()).hexdigest()
                print(f"{text:<10} {h[:32]}...")

            print("md5:   ", hashlib.md5(b"NULL//SIGNAL").hexdigest())
            print("sha1:  ", hashlib.sha1(b"NULL//SIGNAL").hexdigest())
            """,
        ),
        Example(
            "Кодировки", "байты, hex, Base64, биты",
            """
            # Один и тот же текст в разных видах
            import base64

            text = "Сигнал"
            data = text.encode()          # строка -> байты (UTF-8)

            print("байты: ", list(data))
            print("hex:   ", data.hex(" "))
            print("base64:", base64.b64encode(data).decode())
            print("биты:  ", " ".join(format(b, "08b") for b in data[:4]), "...")

            # и обратно
            print("назад: ", bytes.fromhex(data.hex()).decode())
            """,
        ),
        Example(
            "Подсеть", "что значит 192.168.1.37/24",
            """
            # Адрес сети, маска и сколько устройств помещается
            ip = "192.168.1.37"
            prefix = 24

            parts = [int(p) for p in ip.split(".")]
            number = (parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]

            mask = (0xFFFFFFFF << (32 - prefix)) & 0xFFFFFFFF
            network = number & mask
            broadcast = network | (~mask & 0xFFFFFFFF)

            def dotted(n):
                return ".".join(str((n >> shift) & 255) for shift in (24, 16, 8, 0))

            print("адрес:     ", ip)
            print("маска:     ", dotted(mask))
            print("сеть:      ", dotted(network))
            print("последний: ", dotted(broadcast))
            print("устройств: ", broadcast - network - 1)
            """,
        ),
        Example(
            "Частоты букв", "словарь, сортировка, диаграмма",
            """
            # Считаем буквы в тексте и рисуем диаграмму
            text = "кто владеет информацией тот владеет миром"

            counts = {}
            for ch in text:
                if ch != " ":
                    counts[ch] = counts.get(ch, 0) + 1

            top = sorted(counts.items(), key=lambda kv: kv[1], reverse=True)[:8]
            for letter, n in top:
                print(letter, "█" * n, n)
            """,
        ),
        Example(
            "Таблица символов", "ord, chr и коды букв",
            """
            # У каждого символа есть номер.
            # ord() — номер по символу, chr() — символ по номеру.
            for code in range(65, 91, 5):
                row = ""
                for c in range(code, min(code + 5, 91)):
                    row += f"{chr(c)}={c:3d} 0x{c:02X}  "
                print(row)

            print([ord(c) for c in "Привет"])
            print("".join(chr(n) for n in [78, 85, 76, 76]))
            """,
        ),
        Example(
            "JSON", "формат, на котором говорят сайты",
            """
            # JSON — формат, в котором сайты и программы обмениваются данными
            import json

            raw = '{"host": "router", "ip": "192.168.1.1", "ports": [53, 80, 443], "up": true}'
            device = json.loads(raw)

            print(device["host"], "->", device["ip"])
            for port in device["ports"]:
                print("  открыт порт", port)

            device["checked"] = True
            print(json.dumps(device, indent=2, ensure_ascii=False))
            """,
        ),
        Example(
            "Простые числа и Фибоначчи", "функции и списки",
            """
            def is_prime(n):
                if n < 2:
                    return False
                for d in range(2, int(n ** 0.5) + 1):
                    if n % d == 0:
                        return False
                return True

            print("простые до 60:", [n for n in range(60) if is_prime(n)])

            def fib(count):
                a, b = 0, 1
                result = []
                for _ in range(count):
                    result.append(a)
                    a, b = b, a + b
                return result

            print("Фибоначчи:", fib(15))
            print("2 в степени 200 =", 2 ** 200)
            """,
        ),
        Example(
            "Угадай число", "input() и цикл с попытками",
            """
            # input() ждёт, пока ты что-нибудь введёшь. Ввод — всегда строка,
            # поэтому число из него делает int().
            import random

            secret = random.randint(1, 100)
            print("Я загадал число от 1 до 100. У тебя 7 попыток.")

            for attempt in range(1, 8):
                answer = input(f"попытка {attempt}: ")
                if not answer.isdigit():
                    print("нужно число")
                    continue
                number = int(answer)
                if number == secret:
                    print("Угадал!")
                    break
                elif number < secret:
                    print("моё число больше")
                else:
                    print("моё число меньше")
            else:
                print("Не вышло. Я загадал", secret)
            """,
        ),
        Example(
            "Обратный отсчёт", "паузы и кнопка СТОП",
            """
            # time.sleep(секунды) — пауза. Кнопка СТОП прерывает программу.
            import time

            for n in range(5, 0, -1):
                print(n, "...")
                time.sleep(1)
            print("сигнал отправлен")
            """,
        ),
        Example(
            "Цифровой дождь", "случайные числа",
            """
            import random

            for line in range(14):
                row = ""
                for _ in range(30):
                    row += random.choice("01") if random.random() > 0.4 else " "
                print(row)
            """,
        ),
    ).map { Example(it.title, it.about, it.code.trimIndent().trim() + "\n") }
}
