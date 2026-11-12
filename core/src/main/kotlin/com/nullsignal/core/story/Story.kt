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
    SOUND_SENT,      // передал сообщение через ЗВУКОВОЙ КАНАЛ
    ANSWER,          // ввёл верный ответ в терминале: answer <слово>
    CHANNEL_UP,      // поднял ШИФРОЧАТ и кого-то услышал (или вышел на сервер)
    BEACON_SCAN,     // полминуты слушал эфир в СПЕКТРЕ
    FINAL_BREACH,    // открыл архив Helix в BREACH → АРХИВ
    REPLIED,         // выбрал ответ в чате (для глав-развилок)
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
    /** Для загадок: верные ответы для команды answer (регистр, пробелы и ё не важны). */
    val answers: List<String> = emptyList(),
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
            id = "air",
            messages = listOf(
                Message(Member.RAVEN, "Итак. Сеть Килобайта чистим, адреса меняем."),
                Message(Member.GHOST, "вы быстро учитесь"),
                Message(Member.GHOST, "они переходят на новый протокол. ищите меня в эфире. не в сети — в эфире"),
                Message(Member.KILOBYTE, "в смысле в эфире. радио?"),
                Message(Member.JAY, "Или звук. Сеть можно прослушать, воздух в комнате — сложнее."),
                Message(Member.JAY, "Оператор, открой ЗВУКОВОЙ КАНАЛ и передай в эфир что-нибудь. Пусть знает, что мы готовы слушать."),
            ),
            objective = "Передай сообщение звуком: ЗВУКОВОЙ КАНАЛ",
            trigger = Trigger.SOUND_SENT,
            reward = 200,
            replies = listOf(
                Reply("а если кто-то услышит?", listOf(Message(Member.JAY, "Услышит писк. Чтобы понять, нужен модем. А если поставить пароль — нужен ещё и пароль."))),
                Reply("передаю", listOf(Message(Member.KILOBYTE, "чувствую себя радистом из старого фильма)"))),
            ),
        ),
        Chapter(
            id = "morse",
            messages = listOf(
                Message(Member.KILOBYTE, "ору"),
                Message(Member.KILOBYTE, "у меня ноут ночью писал звук с микрофона. я забыл выключить запись после созвона"),
                Message(Member.KILOBYTE, "в 03:12 там пищит. не модем. точки и тире"),
                Message(Member.KILOBYTE, ".-.. --- -.-. -.- . .-. / .---- --..."),
                Message(Member.JAY, "Морзянка. Тихий слушал наш эфир и ответил тем же, только проще."),
                Message(Member.RAVEN, "Оператор, расшифруй. ДЕКОДЕР её понимает, терминал тоже: morse."),
                Message(Member.RAVEN, "Ответ пришли мне в терминале: answer и то, что получилось."),
            ),
            objective = "Расшифруй морзянку и введи в TERMINAL: answer <ответ>",
            trigger = Trigger.ANSWER,
            reward = 250,
            answers = listOf("locker 17", "locker17", "ячейка 17", "локер 17"),
            replies = listOf(
                Reply("откуда он знает, где живёт Килобайт?", listOf(Message(Member.RAVEN, "Не обязательно знает. Сигнал мог идти на весь двор. Поймал тот, кто слушал."), Message(Member.KILOBYTE, "то есть весь двор слушал морзянку в три ночи. отлично"))),
                Reply("сейчас разберу", listOf(Message(Member.JAY, "Подсказка: латиница и цифры. Слово и число."))),
            ),
        ),
        Chapter(
            id = "channel",
            messages = listOf(
                Message(Member.RAVEN, "Locker 17. Камера хранения на старом вокзале, ячейка семнадцать."),
                Message(Member.RAVEN, "Идти всем вместе — глупо. Пойдёт Сойка, остальные на связи."),
                Message(Member.JAY, "Почта и мессенджеры отпадают: Килобайта читали через его же роутер."),
                Message(Member.KILOBYTE, "поэтому шифрочат. ключ из кодового слова, сервер видит только мусор"),
                Message(Member.KILOBYTE, "а если интернет вырубят — телефоны рядом свяжутся по Wi-Fi напрямую. один с интернетом станет мостом для остальных"),
                Message(Member.KILOBYTE, "оператор, зайди в ШИФРОЧАТ и подними канал. дальнюю связь включи: Сойка будет на другом конце города"),
            ),
            objective = "Подними канал связи: ШИФРОЧАТ",
            trigger = Trigger.CHANNEL_UP,
            reward = 200,
            replies = listOf(
                Reply("а сервер нас не сдаст?", listOf(Message(Member.KILOBYTE, "сдать он может только шифр и время. не нравится чужой — подними свой ретранслятор, там есть поле для адреса"))),
                Reply("какое кодовое слово?", listOf(Message(Member.JAY, "Не здесь. Договоримся голосом, лично."), Message(Member.JAY, "Правило: слово никогда не пишут туда, где им пользуются."))),
            ),
        ),
        Chapter(
            id = "beacon",
            messages = listOf(
                Message(Member.JAY, "Я на месте. В ячейке флешка и записка от руки."),
                Message(Member.JAY, "«Прозрачный город» — это не только камеры. В рекламных экранах стоят динамики, которые пищат на 18–20 кГц. Ухо не слышит, микрофон — да."),
                Message(Member.GHOST, "приложения партнёров helix слушают этот писк и сообщают, мимо какого экрана ты прошёл. без геолокации. просто микрофон"),
                Message(Member.KILOBYTE, "ультразвуковые маячки. я думал, это байка с конференций"),
                Message(Member.RAVEN, "Оператор, проверь сам. Открой СПЕКТР и послушай эфир хотя бы полминуты: дома, на улице, в торговом центре."),
                Message(Member.RAVEN, "Если маяк есть — модуль его покажет. Если нет — тоже результат."),
            ),
            objective = "Послушай эфир в СПЕКТРЕ 30 секунд",
            trigger = Trigger.BEACON_SCAN,
            reward = 250,
            replies = listOf(
                Reply("это правда так работает?", listOf(Message(Member.JAY, "Да. Такие маячки в рекламе находили и раньше, это не фантастика."), Message(Member.JAY, "Защита простая: не давать микрофон тем, кому он не нужен. АУДИТ покажет, у кого он есть."))),
                Reply("а что в записке?", listOf(Message(Member.JAY, "«Ключ меняется каждый час. Восемь попыток. Не ошибитесь»."))),
            ),
        ),
        Chapter(
            id = "core",
            messages = listOf(
                Message(Member.KILOBYTE, "флешку разобрал. внутри архив helix: кто, где, когда. сорок тысяч человек"),
                Message(Member.KILOBYTE, "и замок. 4 символа hex, 8 попыток, потом архив сам себя стирает"),
                Message(Member.JAY, "На тренажёре было десять попыток. Здесь восемь — простой решатель успевает примерно в трёх случаях из четырёх."),
                Message(Member.KILOBYTE, "в BREACH → СКРИПТОМ я положил шаблон УМНЫЙ. он выбирает ход, после которого в худшем случае остаётся меньше всего вариантов"),
                Message(Member.RAVEN, "Оператор, это твоё. BREACH → АРХИВ HELIX. Сотрётся — не страшно: у нас есть копия, пробуй снова."),
            ),
            objective = "Открой архив: BREACH → АРХИВ HELIX (8 попыток)",
            trigger = Trigger.FINAL_BREACH,
            reward = 500,
            replies = listOf(
                Reply("а если не выйдет?", listOf(Message(Member.RAVEN, "Выйдет. Ты делал это уже дважды."))),
                Reply("почему не перебрать всё подряд?", listOf(Message(Member.KILOBYTE, "65536 вариантов и 8 попыток. перебор тут не работает, работает голова"))),
            ),
        ),
        Chapter(
            id = "reveal",
            messages = listOf(
                Message(Member.KILOBYTE, "ОТКРЫЛОСЬ"),
                Message(Member.KILOBYTE, "там всё. маршруты, лица, кто кому платил за данные"),
                Message(Member.GHOST, "теперь вы знаете, зачем я вас нашёл"),
                Message(Member.GHOST, "фильтр, который пропустил оператора в первый день, писал я. мне нужен был человек, которого helix ещё не знает"),
                Message(Member.RAVEN, "Ты нас использовал."),
                Message(Member.GHOST, "я вас выбрал. разница есть"),
                Message(Member.JAY, "Что делаем с архивом? Оператор, решай. Ты его открыл."),
            ),
            objective = "Реши судьбу архива: MESSAGES",
            trigger = Trigger.REPLIED,
            reward = 300,
            replies = listOf(
                Reply(
                    "журналистам. имена людей вычеркнуть",
                    listOf(
                        Message(Member.RAVEN, "Да. Люди в архиве — пострадавшие, а не улики."),
                        Message(Member.KILOBYTE, "вычеркну. отправлю по шифрочату частями, через три разных ретранслятора"),
                    ),
                ),
                Reply(
                    "выложить всё в открытый доступ",
                    listOf(
                        Message(Member.JAY, "Тогда в сеть утекут адреса сорока тысяч человек. Мы станем ещё одним Helix."),
                        Message(Member.RAVEN, "Нет. Журналистам, имена вычеркнуть. Это не обсуждается."),
                        Message(Member.KILOBYTE, "оператор, без обид. злость — плохой советчик"),
                    ),
                ),
                Reply(
                    "отдать Тихому, это его архив",
                    listOf(
                        Message(Member.GHOST, "нет. я его принёс, решать вам. я слишком долго был внутри"),
                        Message(Member.RAVEN, "Тогда журналистам. Без имён."),
                    ),
                ),
            ),
        ),
        Chapter(
            id = "epilogue",
            messages = listOf(
                Message(Member.KILOBYTE, "вы видели новости??"),
                Message(Member.KILOBYTE, "«Прозрачный город» приостановлен. проверка, депутаты, всё как надо"),
                Message(Member.JAY, "Экраны на площади молчат. Проверила СПЕКТРОМ — на 19 кГц тишина."),
                Message(Member.GHOST, "спасибо. я ухожу из сети. насовсем"),
                Message(Member.GHOST, "оператор, совет напоследок: не верь фильтрам. даже моим"),
                Message(Member.RAVEN, "Оператор. Ты пришёл к нам чужим, а уходишь своим."),
                Message(Member.RAVEN, "Канал остаётся открытым. Город большой, и шепчет он всегда."),
                Message(Member.RAVEN, "— конец первого сезона —"),
            ),
            objective = "Сезон пройден. Инструменты остаются — пользуйся ими честно",
            trigger = null,
            reward = 0,
        ),
    )
}
