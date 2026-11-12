package com.nullsignal.core.story

/** Сохраняемый прогресс игрока. */
data class Progress(
    /** Индекс текущей (ещё не выполненной) главы. */
    val chapter: Int = 0,
    val followers: Int = 0,
    /** Сколько сообщений игрок уже видел — для значка «непрочитано». */
    val readMessages: Int = 0,
)

/** Чистая логика сюжета: без Android, легко тестировать. */
class QuestEngine(private val chapters: List<Chapter> = Story.chapters) {

    init {
        require(chapters.isNotEmpty())
    }

    fun current(p: Progress): Chapter = chapters[p.chapter.coerceIn(0, chapters.lastIndex)]

    /** Все сообщения, доступные игроку: из пройденных глав и из текущей. */
    fun inbox(p: Progress): List<Message> =
        chapters.take(p.chapter.coerceIn(0, chapters.lastIndex) + 1).flatMap { it.messages }

    /** Открытые главы: пройденные и текущая. */
    fun unlocked(p: Progress): List<Chapter> = chapters.take(p.chapter.coerceIn(0, chapters.lastIndex) + 1)

    fun unread(p: Progress): Int = (inbox(p).size - p.readMessages).coerceAtLeast(0)

    fun markRead(p: Progress): Progress = p.copy(readMessages = inbox(p).size)

    /** Конец сюжета: последняя глава открыта. */
    fun finished(p: Progress): Boolean = p.chapter >= chapters.lastIndex

    /**
     * Проверить ответ на загадку текущей главы: null — сейчас загадки нет,
     * true — ответ верный (дальше нужен Trigger.ANSWER), false — мимо.
     */
    fun check(p: Progress, answer: String): Boolean? {
        val ch = current(p)
        if (ch.answers.isEmpty() || ch.trigger != Trigger.ANSWER) return null
        val a = norm(answer)
        return a.isNotEmpty() && ch.answers.any { norm(it) == a }
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    /** Применить событие. Если оно закрывает текущую главу, открываем следующую. */
    fun on(p: Progress, trigger: Trigger): Progress {
        val ch = current(p)
        if (ch.trigger != trigger || p.chapter >= chapters.lastIndex) return p
        return p.copy(chapter = p.chapter + 1, followers = p.followers + ch.reward)
    }
}
