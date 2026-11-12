package com.nullsignal.core.spy

/** Азбука Морзе: латиница, кириллица, цифры, знаки. Плюс тайминги для фонарика и звука. */
object Morse {
    private val COMMON = mapOf(
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '/' to "-..-.",
        '(' to "-.--.", ')' to "-.--.-", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '"' to ".-..-.", '@' to ".--.-.", '\'' to ".----.",
    )
    private val LATIN = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.",
        'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.",
        'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-",
        'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
    )
    private val CYRILLIC = mapOf(
        'А' to ".-", 'Б' to "-...", 'В' to ".--", 'Г' to "--.", 'Д' to "-..", 'Е' to ".", 'Ж' to "...-",
        'З' to "--..", 'И' to "..", 'Й' to ".---", 'К' to "-.-", 'Л' to ".-..", 'М' to "--", 'Н' to "-.",
        'О' to "---", 'П' to ".--.", 'Р' to ".-.", 'С' to "...", 'Т' to "-", 'У' to "..-", 'Ф' to "..-.",
        'Х' to "....", 'Ц' to "-.-.", 'Ч' to "---.", 'Ш' to "----", 'Щ' to "--.-", 'Ъ' to "--.--",
        'Ы' to "-.--", 'Ь' to "-..-", 'Э' to "..-..", 'Ю' to "..--", 'Я' to ".-.-",
    )

    fun encode(text: String): String = text.uppercase().replace('Ё', 'Е').trim().split(Regex("\\s+")).joinToString(" / ") { word ->
        word.mapNotNull { c -> LATIN[c] ?: CYRILLIC[c] ?: COMMON[c] }.joinToString(" ")
    }

    /** Код → текст. [cyrillic] выбирает алфавит: коды букв в двух алфавитах совпадают. */
    fun decode(code: String, cyrillic: Boolean = false): String {
        val table = (if (cyrillic) CYRILLIC else LATIN) + COMMON
        val reverse = table.entries.associate { (k, v) -> v to k }
        return code.trim().split(Regex("\\s*/\\s*|\\s{3,}")).joinToString(" ") { word ->
            word.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { reverse[it.replace('·', '.').replace('—', '-')] ?: '?' }.joinToString("")
        }
    }

    /**
     * Тайминги: список (включено?, длительность мс). Точка = 1 единица, тире = 3,
     * пауза внутри буквы = 1, между буквами = 3, между словами = 7.
     */
    fun timings(code: String, unitMs: Int = 120): List<Pair<Boolean, Int>> {
        val out = mutableListOf<Pair<Boolean, Int>>()
        val words = code.trim().split(Regex("\\s*/\\s*")).filter { it.isNotBlank() }
        words.forEachIndexed { wi, word ->
            val letters = word.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            letters.forEachIndexed { li, letter ->
                letter.forEachIndexed { si, s ->
                    out += true to (if (s == '-') 3 else 1) * unitMs
                    if (si < letter.lastIndex) out += false to unitMs
                }
                if (li < letters.lastIndex) out += false to 3 * unitMs
            }
            if (wi < words.lastIndex) out += false to 7 * unitMs
        }
        return out
    }
}
