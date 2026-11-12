package com.nullsignal.core.story

/** Что игрок сделал в лаунчере. Сюжет двигается только по этим событиям. */
enum class Trigger {
    WHOAMI,          // ввёл whoami в терминале
    SCAN_DONE,       // закончил сканирование своей сети
    BREACH_WON,      // прошёл тренировочный взлом
    SIGNAL_CMD,      // ввёл signal в терминале
    AUDIT_DONE,      // проверил свой телефон в AUDIT
    CIPHER_WON,      // расшифровал все перехваты
    VULN_WON,        // нашёл все дыры в коде
    NODES_WON,       // собрал сеть в «Узлах»
    PY_TASKS,        // решил три задачи в PYTHON
    BREACH_SCRIPT,   // подобрал ключ в BREACH своим скриптом
}

/** Участник группировки NULL//SIGNAL. */
enum class Member(val handle: String) {
    RAVEN("ВОРОН"),
    KILOBYTE("КИЛОБАЙТ"),
    JAY("СОЙКА"),
}

data class Message(val from: Member, val text: String)

data class Chapter(
    val id: String,
    val messages: List<Message>,
    /** Задача, которая показывается на рабочем столе. */
    val objective: String,
    /** null — последняя глава (продолжение следует). */
    val trigger: Trigger?,
    /** Сколько «узлов сети» (подписчиков) даёт выполнение. */
    val reward: Int,
)

/**
 * Сюжет. Вымышленная корпорация Helix Data превращает «умный город»
 * в сеть слежки. Группа NULL//SIGNAL вербует нового оператора.
 */
object Story {
    val chapters: List<Chapter> = listOf(
        Chapter(
            id = "recruit",
            messages = listOf(
                Message(Member.RAVEN, "Оператор. Если ты читаешь это, значит, фильтр тебя пропустил."),
                Message(Member.RAVEN, "Мы NULL//SIGNAL. Нас мало, но мы слышим всё, что этот город шепчет в эфир."),
                Message(Member.RAVEN, "Сначала докажи, что ты живой человек, а не бот Helix. Открой TERMINAL и введи whoami."),
            ),
            objective = "Открой TERMINAL и введи whoami",
            trigger = Trigger.WHOAMI,
            reward = 12,
        ),
        Chapter(
            id = "recon",
            messages = listOf(
                Message(Member.KILOBYTE, "о, новенький) привет"),
                Message(Member.KILOBYTE, "первое правило: знай свою сеть лучше, чем её знают они"),
                Message(Member.KILOBYTE, "запусти NETSCAN и посмотри, кто живёт у тебя в Wi-Fi. только свою сеть, чужие не трогаем, мы не идиоты"),
            ),
            objective = "Просканируй свою сеть в NETSCAN",
            trigger = Trigger.SCAN_DONE,
            reward = 48,
        ),
        Chapter(
            id = "drill",
            messages = listOf(
                Message(Member.JAY, "Видела твой скан. Неплохо."),
                Message(Member.JAY, "Helix шифрует свои терминалы 4-значными hex-ключами. Звучит смешно, но людей на этом ловят каждый день."),
                Message(Member.JAY, "В BREACH есть тренажёр. Подбери ключ, пока не кончились попытки."),
                Message(Member.JAY, "Включи АНАЛИЗАТОР — он покажет, какие символы ещё возможны. А на вкладке СКРИПТОМ лежит готовый решатель на Python: запусти и посмотри, как он думает."),
            ),
            objective = "Пройди тренировочный взлом в BREACH",
            trigger = Trigger.BREACH_WON,
            reward = 120,
        ),
        Chapter(
            id = "signal",
            messages = listOf(
                Message(Member.RAVEN, "Ты справился. Добро пожаловать."),
                Message(Member.RAVEN, "Когда будешь готов к настоящему делу, введи в терминале signal. Мы услышим."),
            ),
            objective = "Введи signal в TERMINAL",
            trigger = Trigger.SIGNAL_CMD,
            reward = 300,
        ),
        Chapter(
            id = "audit",
            messages = listOf(
                Message(Member.KILOBYTE, "сигнал принят. теперь серьёзно"),
                Message(Member.KILOBYTE, "первое, что делает нормальный безопасник — чинит СВОЁ. Helix первым делом ломает телефоны тех, кто против них"),
                Message(Member.KILOBYTE, "открой AUDIT и проверь свой телефон. что красное — исправь"),
            ),
            objective = "Проверь свой телефон в AUDIT",
            trigger = Trigger.AUDIT_DONE,
            reward = 80,
        ),
        Chapter(
            id = "cipher",
            messages = listOf(
                Message(Member.JAY, "Мы перехватили переписку курьеров Helix. Они не доверяют мессенджерам и шифруют всё по старинке."),
                Message(Member.JAY, "GAMES → ШИФРЫ. Расшифруй все перехваты. Терминал поможет: b64, hex, rot13, caesar."),
            ),
            objective = "Расшифруй все перехваты: GAMES → ШИФРЫ",
            trigger = Trigger.CIPHER_WON,
            reward = 150,
        ),
        Chapter(
            id = "vuln",
            messages = listOf(
                Message(Member.RAVEN, "Из перехватов ясно: у Helix утекли исходники их портала «Прозрачный город»."),
                Message(Member.RAVEN, "Найди в них все дыры. Мы передадим отчёт журналистам — пусть весь город узнает, как они берегут чужие данные."),
                Message(Member.RAVEN, "GAMES → КОД."),
            ),
            objective = "Найди все уязвимости: GAMES → КОД",
            trigger = Trigger.VULN_WON,
            reward = 200,
        ),
        Chapter(
            id = "nodes",
            messages = listOf(
                Message(Member.KILOBYTE, "журналистам нужен канал, который Helix не заглушит"),
                Message(Member.KILOBYTE, "собери mesh-сеть из узлов, чтобы всё сходилось к нашему серверу. GAMES → УЗЛЫ"),
            ),
            objective = "Собери сеть: GAMES → УЗЛЫ",
            trigger = Trigger.NODES_WON,
            reward = 200,
        ),
        Chapter(
            id = "code",
            messages = listOf(
                Message(Member.KILOBYTE, "слушай, хватит жить на чужих инструментах"),
                Message(Member.KILOBYTE, "оператор, который не пишет код, — пассажир. у тебя в телефоне теперь есть PYTHON: пишешь программу и сразу запускаешь"),
                Message(Member.KILOBYTE, "зайди в PYTHON → ЗАДАЧИ и реши три любые. застрянешь — после трёх попыток дам подсмотреть решение, но разберись, как оно работает"),
            ),
            objective = "Реши 3 задачи: PYTHON → ЗАДАЧИ",
            trigger = Trigger.PY_TASKS,
            reward = 250,
        ),
        Chapter(
            id = "script",
            messages = listOf(
                Message(Member.JAY, "Helix сменил замки на учебном стенде: ключ теперь живёт недолго, руками считать некогда."),
                Message(Member.JAY, "BREACH → СКРИПТОМ. Пусть ключ подберёт твоя программа. Готовый решатель там уже есть — запусти его, а потом измени под себя: другие первые ходы, свой вывод."),
            ),
            objective = "Подбери ключ программой: BREACH → СКРИПТОМ",
            trigger = Trigger.BREACH_SCRIPT,
            reward = 300,
        ),
        Chapter(
            id = "tbc",
            messages = listOf(
                Message(Member.JAY, "Отчёт ушёл. Утром это будет на всех новостных сайтах."),
                Message(Member.RAVEN, "Хорошая работа, оператор. Но Helix так просто не сдастся. Держи телефон рядом."),
            ),
            objective = "Ждать сигнала… (продолжение следует)",
            trigger = null,
            reward = 0,
        ),
    )
}
