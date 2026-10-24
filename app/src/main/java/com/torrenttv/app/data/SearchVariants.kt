package com.torrenttv.app.data

/**
 * Умные варианты поискового запроса — «ИИ без нейросети», локально и мгновенно:
 *  1) неправильная раскладка («ghjtw» → «спецы») — частая беда на ТВ-клавиатуре;
 *  2) транслит («spetsy» → «спецы») — латиницей набрали русское название;
 *  3) опечатки: близкий запрос из ВАШЕЙ истории поиска (Левенштейн ≤ 2) —
 *     «спецф» → «спецы», потому что вы уже искали «спецы».
 * Возвращает варианты ПОРЯДКЕ УВЕРЕННОСТИ, без дубликата исходного запроса.
 */
object SearchVariants {

    /** Раскладка ЙЦУКЕН: латинская буква → русская. */
    private val LAYOUT = mapOf(
        'q' to 'й', 'w' to 'ц', 'e' to 'у', 'r' to 'к', 't' to 'е', 'y' to 'н',
        'u' to 'г', 'i' to 'ш', 'o' to 'щ', 'p' to 'з', '[' to 'х', ']' to 'ъ',
        'a' to 'ф', 's' to 'ы', 'd' to 'в', 'f' to 'а', 'g' to 'п', 'h' to 'р',
        'j' to 'о', 'k' to 'л', 'l' to 'д', ';' to 'ж', '\'' to 'э',
        'z' to 'я', 'x' to 'ч', 'c' to 'с', 'v' to 'м', 'b' to 'и', 'n' to 'т',
        'm' to 'ь', ',' to 'б', '.' to 'ю',
    )

    /** Транслит латиницей: составные слоги проверять раньше одиночных букв. */
    private val TRANSLIT = listOf(
        "sch" to "щ", "sh" to "ш", "ch" to "ч", "zh" to "ж", "ts" to "ц",
        "yu" to "ю", "ya" to "я", "yo" to "ё",
        "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д", "e" to "е",
        "z" to "з", "i" to "и", "y" to "ы", "k" to "к", "l" to "л", "m" to "м",
        "n" to "н", "o" to "о", "p" to "п", "r" to "р", "s" to "с", "t" to "т",
        "u" to "у", "f" to "ф", "h" to "х", "c" to "к", "w" to "в", "j" to "й",
    )

    private val hasLatin = Regex("""[a-z]""", RegexOption.IGNORE_CASE)
    private val hasCyr = Regex("""[а-яё]""", RegexOption.IGNORE_CASE)

    /** Варианты запроса: раскладка → транслит → история (опечатки). */
    fun variants(query: String, history: List<String>): List<String> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val out = mutableListOf<String>()

        if (hasLatin.containsMatchIn(q) && !hasCyr.containsMatchIn(q)) {
            layoutSwap(q)?.let { if (!it.equals(q, true)) out += it }
            translit(q)?.let { if (!it.equals(q, true)) out += it }
        }
        // Опечатка: близкий запрос из истории (расстояние ≤ 2)
        history.firstOrNull { h ->
            h.length >= 3 && h != q &&
                levenshtein(q.lowercase(), h.lowercase()) <= 2
        }?.let { if (it !in out) out += it }
        return out.distinct().filter { !it.equals(q, true) }
    }

    /** «ghjtw» → «спецы» (перевод раскладки). Работает и в обратную сторону. */
    private fun layoutSwap(q: String): String? {
        var hits = 0
        val sb = StringBuilder()
        for (c in q.lowercase()) {
            val m = if (c.isLetter()) {
                LAYOUT[c] ?: (when {
                    c in 'a'..'z' -> null
                    else -> null
                })
            } else null
            if (m != null) { sb.append(m); hits++ } else sb.append(c)
        }
        return if (hits >= 3) sb.toString() else null
    }

    /** «spetsy» → «спецы» (транслит). */
    private fun translit(q: String): String? {
        val low = q.lowercase()
        if (!hasLatin.containsMatchIn(low)) return null
        var hits = 0
        val sb = StringBuilder()
        var i = 0
        while (i < low.length) {
            var matched = false
            for ((lat, cyr) in TRANSLIT) {
                if (low.startsWith(lat, i)) {
                    sb.append(cyr); hits++; i += lat.length; matched = true
                    break
                }
            }
            if (!matched) { sb.append(low[i]); i++ }
        }
        return if (hits >= 3) sb.toString() else null
    }

    /** Расстояние Левенштейна (итеративное, O(n·m) памяти под две строки). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
