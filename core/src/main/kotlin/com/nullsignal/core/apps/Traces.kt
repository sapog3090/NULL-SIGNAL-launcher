package com.nullsignal.core.apps

/**
 * «Следы»: из системного журнала использования собирает сеансы —
 * когда телефон включали/разблокировали и какие приложения открывали.
 */
object Traces {
    enum class Type { UNLOCK, LOCK, SCREEN_ON, SCREEN_OFF, APP_OPEN, APP_CLOSE, BOOT, SHUTDOWN }

    data class Event(val time: Long, val type: Type, val pkg: String? = null)

    data class Session(
        val start: Long,
        val end: Long,
        /** Начался с разблокировки (а не просто включения экрана). */
        val unlocked: Boolean,
        /** Приложения в порядке первого открытия. */
        val apps: List<String>,
        val boot: Boolean = false,
    ) {
        val durationMs: Long get() = end - start
    }

    /** Режет поток событий на сеансы «экран включён → экран выключен». */
    fun sessions(events: List<Event>, now: Long): List<Session> {
        val out = mutableListOf<Session>()
        var start: Long? = null
        var unlocked = false
        var boot = false
        val apps = LinkedHashSet<String>()
        fun close(t: Long) {
            val s = start ?: return
            out += Session(s, maxOf(t, s), unlocked, apps.toList(), boot)
            start = null; unlocked = false; boot = false; apps.clear()
        }
        for (e in events.sortedBy { it.time }) {
            when (e.type) {
                Type.SCREEN_ON -> { if (start == null) start = e.time }
                Type.BOOT -> { close(e.time); start = e.time; boot = true }
                Type.UNLOCK -> { if (start == null) start = e.time; unlocked = true }
                Type.APP_OPEN -> {
                    if (start == null) start = e.time
                    e.pkg?.let { apps += it }
                }
                Type.SCREEN_OFF, Type.SHUTDOWN -> close(e.time)
                Type.LOCK, Type.APP_CLOSE -> Unit
            }
        }
        start?.let { close(now) }
        return out.reversed()
    }

    /** Время на переднем плане по приложениям (мс), из пар открытие/закрытие. */
    fun foregroundTime(events: List<Event>, now: Long): Map<String, Long> {
        val opened = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        for (e in events.sortedBy { it.time }) {
            val p = e.pkg
            when (e.type) {
                Type.APP_OPEN -> if (p != null && p !in opened) opened[p] = e.time
                Type.APP_CLOSE -> if (p != null) opened.remove(p)?.let { total[p] = (total[p] ?: 0) + (e.time - it) }
                Type.SCREEN_OFF, Type.SHUTDOWN -> {
                    opened.forEach { (k, t) -> total[k] = (total[k] ?: 0) + (e.time - t) }
                    opened.clear()
                }
                else -> Unit
            }
        }
        opened.forEach { (k, t) -> total[k] = (total[k] ?: 0) + (now - t) }
        return total.filterValues { it > 0 }
    }

    /** Сеанс в «подозрительное» время: ночью (0:00–6:00 по часам [hourOf]). */
    fun atNight(s: Session, hourOf: (Long) -> Int): Boolean = hourOf(s.start) in 0..5

    fun human(ms: Long): String {
        val m = ms / 60000
        return when {
            ms < 60_000 -> "${ms / 1000} с"
            m < 60 -> "$m мин"
            else -> "${m / 60} ч ${m % 60} мин"
        }
    }
}
