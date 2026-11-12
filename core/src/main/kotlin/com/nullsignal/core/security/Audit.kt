package com.nullsignal.core.security

/** Что удалось узнать о телефоне. null — проверить не получилось. */
data class AuditInput(
    val screenLock: Boolean?,
    val encrypted: Boolean?,
    val devOptions: Boolean?,
    val adb: Boolean?,
    val patchAgeDays: Int?,
    val sdk: Int,
    val rootIndicators: List<String>,
    val installUnknownApps: Int?,
)

data class Finding(val title: String, val ok: Boolean, val weight: Int, val detail: String)

/** Аудит безопасности своего телефона: оценка 0–100 и советы. */
object Audit {

    fun run(i: AuditInput): List<Finding> = buildList {
        i.screenLock?.let {
            add(Finding("блокировка экрана", it, 25,
                if (it) "есть PIN/пароль/графический ключ" else "телефон открывается без пароля! Настройки → Безопасность → Блокировка экрана"))
        }
        i.encrypted?.let {
            add(Finding("шифрование памяти", it, 15,
                if (it) "данные зашифрованы" else "данные не зашифрованы — при краже их можно прочитать"))
        }
        i.patchAgeDays?.let {
            val ok = it <= 120
            add(Finding("обновления безопасности", ok, 15,
                if (ok) "патч свежий ($it дн.)" else "последний патч $it дн. назад — проверь обновления системы"))
        }
        add(Finding("версия Android", i.sdk >= 31, 10,
            if (i.sdk >= 31) "API ${i.sdk} — актуальная" else "API ${i.sdk} — старая версия, меньше защит"))
        i.adb?.let {
            add(Finding("отладка по USB", !it, 15,
                if (it) "ADB включён: через кабель можно управлять телефоном. Выключи, если не нужен" else "выключена"))
        }
        i.devOptions?.let {
            add(Finding("режим разработчика", !it, 5, if (it) "включён — выключи, если не пользуешься" else "выключен"))
        }
        add(Finding("признаки root", i.rootIndicators.isEmpty(), 10,
            if (i.rootIndicators.isEmpty()) "не найдено" else "найдено: ${i.rootIndicators.joinToString()}"))
        i.installUnknownApps?.let {
            add(Finding("установка из неизвестных источников", it <= 2, 5,
                if (it == 0) "ни одному приложению не разрешена"
                else "разрешено приложениям: $it. Чем меньше — тем лучше"))
        }
    }

    fun score(findings: List<Finding>): Int {
        val total = findings.sumOf { it.weight }
        if (total == 0) return 0
        return findings.filter { it.ok }.sumOf { it.weight } * 100 / total
    }

    fun grade(score: Int): String = when {
        score >= 90 -> "A — крепость"
        score >= 75 -> "B — хорошо"
        score >= 55 -> "C — есть дыры"
        score >= 35 -> "D — уязвим"
        else -> "F — открыт всем ветрам"
    }

    /** Опасные разрешения, которые стоит проверить у приложений. */
    val SENSITIVE: Map<String, String> = linkedMapOf(
        "android.permission.CAMERA" to "камера",
        "android.permission.RECORD_AUDIO" to "микрофон",
        "android.permission.ACCESS_FINE_LOCATION" to "точная геолокация",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "геолокация в фоне",
        "android.permission.READ_SMS" to "чтение SMS",
        "android.permission.READ_CONTACTS" to "контакты",
        "android.permission.READ_CALL_LOG" to "журнал звонков",
        "android.permission.BODY_SENSORS" to "датчики тела",
        "android.permission.READ_MEDIA_IMAGES" to "фото",
    )
}
