package com.nullsignal.core.security

import java.security.MessageDigest

/** Опознание Bluetooth-меток слежения по рекламным пакетам BLE. */
object TrackerDetector {

    const val APPLE = 0x004C

    /**
     * @param manufacturer данные производителя: id компании → байты
     * @param services 16-битные UUID сервисов (как числа), объявленные устройством
     */
    fun classify(manufacturer: Map<Int, ByteArray>, services: Set<Int>): String? {
        manufacturer[APPLE]?.let { d ->
            // 0x12 — сеть «Локатор» (Find My) в режиме «потерян/вдали от владельца»
            if (d.isNotEmpty() && (d[0].toInt() and 0xff) == 0x12) {
                return if (d.size >= 25) "AirTag / Локатор Apple (вдали от владельца)" else "устройство сети Локатор Apple"
            }
        }
        if (0xFD5A in services || 0xFD59 in services) return "Samsung SmartTag"
        if (0xFEED in services || 0xFEEC in services) return "Tile"
        if (0xFE33 in services) return "Chipolo"
        return null
    }

    /** Метка подозрительна, если держится рядом дольше [minutes] минут. */
    fun following(firstSeenMs: Long, lastSeenMs: Long, minutes: Int = 10): Boolean =
        lastSeenMs - firstSeenMs >= minutes * 60_000L
}

/** Поиск признаков шпионских программ (stalkerware). */
object SpywareCheck {
    private val KEYWORDS = listOf(
        "mspy", "flexispy", "spyzie", "hoverwatch", "cocospy", "spyic", "truthspy", "kidsguard", "spyhide",
        "mobistealth", "xnspy", "ikeymonitor", "spyera", "highster", "clevguard", "eyezy", "umobix", "spymaster",
        "trackview", "mobilespy", "spybubble", "copy9", "fonemonitor", "minspy", "spyine", "teensafe", "webwatcher",
        "monitor.client", "spy.phone", "phonespy", "spyapp", "keylogger",
    )

    fun suspiciousName(pkg: String, label: String): Boolean {
        val s = (pkg + " " + label).lowercase()
        return KEYWORDS.any { it in s }
    }

    data class AppFacts(
        val pkg: String,
        val label: String,
        val system: Boolean,
        val hasLauncherIcon: Boolean,
        val dangerous: List<String>,
        val accessibility: Boolean,
        val notificationAccess: Boolean,
        val deviceAdmin: Boolean,
    )

    data class Verdict(val app: AppFacts, val level: Int, val reasons: List<String>) // 0 норм, 1 внимание, 2 опасно

    fun judge(a: AppFacts): Verdict {
        val r = mutableListOf<String>()
        var level = 0
        if (suspiciousName(a.pkg, a.label)) { r += "название похоже на известную шпионскую программу"; level = 2 }
        if (!a.system && !a.hasLauncherIcon && a.dangerous.isNotEmpty()) {
            r += "СКРЫТО из меню, но имеет доступ: ${a.dangerous.joinToString()}"; level = maxOf(level, 2)
        }
        if (!a.system && a.accessibility) { r += "читает экран и нажимает за тебя (спец. возможности)"; level = maxOf(level, 1) }
        if (!a.system && a.notificationAccess) { r += "читает все уведомления"; level = maxOf(level, 1) }
        if (!a.system && a.deviceAdmin) { r += "администратор устройства — трудно удалить"; level = maxOf(level, 1) }
        if (level == 1 && (a.accessibility && a.notificationAccess || a.deviceAdmin && a.accessibility)) level = 2
        return Verdict(a, level, r)
    }
}

/** Проверка пароля по базе утечек через k-анонимность: наружу уходят только 5 символов хеша. */
object Pwned {
    fun sha1(password: String): String =
        MessageDigest.getInstance("SHA-1").digest(password.toByteArray()).joinToString("") { "%02X".format(it) }

    /** (префикс для запроса, суффикс для поиска в ответе) */
    fun split(password: String): Pair<String, String> = sha1(password).let { it.take(5) to it.drop(5) }

    /** Сколько раз пароль встречался в утечках (0 — не найден). */
    fun count(rangeBody: String, suffix: String): Int =
        rangeBody.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(':').equals(suffix, ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
}
