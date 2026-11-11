package com.nullsignal.core.story

/** Что игрок сделал в лаунчере. Сюжет двигается только по этим событиям. */
enum class Trigger {
    WHOAMI,          // ввёл whoami в терминале
    SCAN_DONE,       // закончил сканирование своей сети
    BREACH_WON,      // прошёл тренировочный взлом
    SIGNAL_CMD,      // ввёл signal в терминале
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
            id = "tbc",
            messages = listOf(
                Message(Member.KILOBYTE, "сигнал принят"),
                Message(Member.RAVEN, "Helix запускает что-то под названием «Прозрачный город». Скоро расскажу больше. Держи телефон рядом."),
            ),
            objective = "Ждать сигнала… (продолжение следует)",
            trigger = null,
            reward = 0,
        ),
    )
}
