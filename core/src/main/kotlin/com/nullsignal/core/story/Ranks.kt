package com.nullsignal.core.story

/** Ранги оператора по числу узлов сети. */
object Ranks {
    data class Rank(val title: String, val from: Int)
    data class Status(val rank: Rank, val next: Rank?, val progress: Float)

    val all = listOf(
        Rank("НОВИЧОК", 0),
        Rank("СКРИПТ-КИДДИ", 50),
        Rank("ОПЕРАТОР", 200),
        Rank("ПРИЗРАК", 500),
        Rank("ТЕНЬ", 1000),
        Rank("ЛЕГЕНДА", 2000),
    )

    fun of(followers: Int): Status {
        val i = all.indexOfLast { followers >= it.from }.coerceAtLeast(0)
        val rank = all[i]
        val next = all.getOrNull(i + 1)
        val progress = if (next == null) 1f else (followers - rank.from).toFloat() / (next.from - rank.from)
        return Status(rank, next, progress.coerceIn(0f, 1f))
    }
}
