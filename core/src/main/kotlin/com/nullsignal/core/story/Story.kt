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
    FILE_CHECKED,    // проверил файл в ПРОВЕРКЕ ФАЙЛА
    DECODED,         // раскодировал сообщение в ДЕКОДЕРЕ
    MITM_DONE,       // проверил свою сеть в ПРОСЛУШКА?
}

/** Участник группировки NULL//SIGNAL. */
enum class Member(val handle: String) {
    RAVEN("ВОРОН"),
    KILOBYTE("КИЛОБАЙТ"),
    JAY("СОЙКА"),
    GHOST("ТИХИЙ"),
}

data class Message(val from: Member, val text: String)

/** Вариант ответа игрока и что ему на это скажут. */
data class Reply(val text: String, val answer: List<Message>)

data class Chapter(
    val id: String,
    val messages: List<Message>,
    /** Задача, которая показывается на рабочем столе. */
    val objective: String,
    /** null — последняя глава (продолжение следует). */
    val trigger: Trigger?,
    /** Сколько «узлов сети» (подписчиков) даёт выполнение. */
    val reward: Int,
    /** Что игрок может ответить в чате (необязательно). */
    val replies: List<Reply> = emptyList(),
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
            replies = listOf(
                Reply("кто вы такие?", listOf(Message(Member.RAVEN, "Те, кому не нравится, что за городом следят без спроса."), Message(Member.RAVEN, "Остальное потом. Терминал, whoami."))),
                Reply("это розыгрыш?", listOf(Message(Member.RAVEN, "Розыгрыши не попадают в лаунчер сами."), Message(Member.RAVEN, "whoami. Жду."))),
            ),
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
            replies = listOf(
                Reply("а это вообще законно?", listOf(Message(Member.JAY, "Тренажёр — да. Чужие системы — нет."), Message(Member.JAY, "Мы трогаем только своё и то, на что нам дали разрешение. Это не обсуждается."))),
                Reply("сделаю", listOf(Message(Member.JAY, "Включи анализатор. Угадывать — не наш метод."))),
            ),
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
            id = "phish",
            messages = listOf(
                Message(Member.KILOBYTE, "так"),
                Message(Member.KILOBYTE, "мне в почту пришло «предложение о сотрудничестве»"),
                Message(Member.KILOBYTE, "вложение: Отчёт_Helix_утечка.pdf"),
                Message(Member.KILOBYTE, "выглядит как пдф. весит 4 мегабайта. пдф на 4 мегабайта от незнакомца, ага"),
                Message(Member.JAY, "Не открывай."),
                Message(Member.JAY, "Оператор, у тебя есть ПРОВЕРКА ФАЙЛА. Проверь любой файл с телефона — посмотри, что она показывает. Потом Килобайт перешлёт свой."),
            ),
            objective = "Проверь любой файл: ПРОВЕРКА ФАЙЛА",
            trigger = Trigger.FILE_CHECKED,
            reward = 150,
            replies = listOf(
                Reply("а если просто открыть?", listOf(Message(Member.JAY, "Если внутри программа под видом PDF — она получит всё, что ей разрешит телефон."), Message(Member.KILOBYTE, "я как-то открыл «фото». потом месяц менял пароли)"))),
                Reply("проверю", listOf(Message(Member.KILOBYTE, "вот это правильный ответ"))),
            ),
        ),
        Chapter(
            id = "insider",
            messages = listOf(
                Message(Member.KILOBYTE, "проверил. это был не пдф, а установщик. классика"),
                Message(Member.RAVEN, "Значит, они знают наши адреса. Это плохо."),
                Message(Member.RAVEN, "И есть вторая новость. Нам написал кто-то изнутри Helix."),
                Message(Member.GHOST, "0LLRgdGC0YDQtdGH0LAg0L7RgtC80LXQvdC10L3QsC4gaGVsaXgg0LfQvdCw0LXRgiDQv9GA0L4g0YHQvtC50LrRgy4g0LfQsNCy0YLRgNCwINCyIDIxOjAwINC/0YDQvtCy0LXRgNGM0YLQtSDQv9C+0YfRgtGDINC60YPRgNGM0LXRgNCw"),
                Message(Member.KILOBYTE, "это даже не шифр. это обёртка"),
                Message(Member.RAVEN, "Оператор, разверни. Зажми сообщение, скопируй и вставь в ДЕКОДЕР — он сам поймёт, что это."),
            ),
            objective = "Раскодируй сообщение Тихого: ДЕКОДЕР",
            trigger = Trigger.DECODED,
            reward = 200,
            replies = listOf(
                Reply("а если это ловушка?", listOf(Message(Member.RAVEN, "Может быть. Поэтому мы ничего у него не запускаем и никуда не переходим. Только читаем."))),
                Reply("кто такой Тихий?", listOf(Message(Member.JAY, "Не знаем. Пишет с одноразовых адресов, ни разу не повторился."), Message(Member.JAY, "Либо очень осторожный, либо очень хорошо играет."))),
            ),
        ),
        Chapter(
            id = "wire",
            messages = listOf(
                Message(Member.JAY, "Он знает про меня. Значит, где-то утечка."),
                Message(Member.KILOBYTE, "кажется я знаю где"),
                Message(Member.KILOBYTE, "вчера браузер ругался на сертификат гугла. ГУГЛА"),
                Message(Member.KILOBYTE, "я нажал «всё равно продолжить». знаю. не надо"),
                Message(Member.JAY, "Чужой сертификат на крупном сайте — это кто-то посередине. Прокси, роутер, «защитник» — неважно."),
                Message(Member.JAY, "Оператор, проверь свою сеть в ПРОСЛУШКА?. Если у тебя чисто, значит, взяли только его."),
            ),
            objective = "Проверь свою сеть: ПРОСЛУШКА?",
            trigger = Trigger.MITM_DONE,
            reward = 200,
            replies = listOf(
                Reply("может, просто глюк?", listOf(Message(Member.JAY, "Глюки не подписывают сертификаты чужим именем."))),
                Reply("Килобайт, смени пароль от роутера", listOf(Message(Member.KILOBYTE, "уже. и WPS выключил. и прошивку обновил"), Message(Member.KILOBYTE, "и стыдно мне тоже уже"))),
            ),
        ),
        Chapter(
            id = "tbc",
            messages = listOf(
                Message(Member.RAVEN, "Итак. Сеть Килобайта чистим, адреса меняем."),
                Message(Member.GHOST, "вы быстро учитесь"),
                Message(Member.GHOST, "они переходят на новый протокол. ищите меня в эфире. не в сети — в эфире"),
                Message(Member.KILOBYTE, "в смысле в эфире. радио?"),
                Message(Member.JAY, "Или звук. Оператор, держи телефон рядом."),
            ),
            objective = "Ждать сигнала… (продолжение следует)",
            trigger = null,
            reward = 0,
        ),
    )
}
