package com.torrenttv.app.data

/**
 * Сопоставление названий при обогащении меты (постер, описание, год).
 *
 * Идея и эвристика взяты из открытого проекта cinesta (MIT, nickneustroev):
 * нормализация названия + балльная оценка кандидатов вместо простого
 * «contains». Это закрывает типовые ошибки:
 *  - «Спецы» (сериал 2026) получали постер фильма 2017 — тип и год решают;
 *  - ремейки и одноимённые фильмы разных лет;
 *  - «II/III» против «2/3», артикли the/a/an, диакритика, пунктуация;
 *  - русская «ё» и лишние пробелы.
 *
 * Возвращаем только уверенное совпадение: чужой постер хуже, чем его
 * отсутствие (жалоба владельца по «Спецам»).
 */
object TitleMatch {

    /** Ниже этого балла мету не берём: точное название + год (±1) = 120…140. */
    const val MIN_SCORE = 115

    /** Нормализация названия: регистр, диакритика, пунктуация, римские цифры. */
    fun normalize(raw: String): String = raw.lowercase()
        .replace('ё', 'е')
        // Диакритика (é → e): NFD разлагает, затем убираем комбинирующие знаки
        .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .replace(Regex("[’'`]"), "'")
        .replace("&", " and ")
        .replace(Regex("[:\\-–—/,!.?()\\[\\]]"), " ")
        .replace(Regex("\\biii\\b"), "3")
        .replace(Regex("\\bii\\b"), "2")
        .replace(Regex("\\biv\\b"), "4")
        .replace(Regex("\\b(the|a|an)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokens(s: String): Set<String> =
        s.split(' ').filter { it.isNotBlank() }.toSet()

    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b }.toDouble() / maxOf(a.size, b.size)
    }

    /**
     * Балл кандидата: название, год, «цифры не совпали».
     * Тип (фильм/сериал) проверяется отдельно — несовпадение типа отбрасывает
     * кандидата полностью, как и раньше.
     */
    fun score(input: MediaItem, candidate: MediaItem): Int {
        val inputTitle = normalize(input.title)
        val candTitle = normalize(candidate.title)
        val candOriginal = normalize(candidate.originalTitle)
        var score = 0

        when {
            candTitle == inputTitle -> score += 100
            candOriginal.isNotBlank() && candOriginal == inputTitle -> score += 100
            candTitle.startsWith(inputTitle) || inputTitle.startsWith(candTitle) -> score += 70
            else -> {
                val best = maxOf(
                    overlap(tokens(inputTitle), tokens(candTitle)),
                    overlap(tokens(inputTitle), tokens(candOriginal)),
                )
                score += when {
                    best >= 0.8 -> 40
                    best <= 0.3 -> -80
                    else -> 0
                }
            }
        }

        val inputYear = input.year.toIntOrNull()
        val candYear = candidate.year.toIntOrNull()
        if (inputYear != null && candYear != null) {
            score += when (kotlin.math.abs(candYear - inputYear)) {
                0 -> 40
                1 -> 20
                2 -> 5
                else -> -40
            }
        } else if (candYear == null && score < 100) {
            score -= 60
        }

        // «Дюна 2» не должна матчиться с «Дюной» и наоборот
        val inputHasNumber = Regex("\\b\\d+\\b").containsMatchIn(inputTitle)
        val candHasNumber = Regex("\\b\\d+\\b").containsMatchIn(candTitle)
        if (inputHasNumber != candHasNumber) score -= 20

        return score
    }

    /**
     * Лучший кандидат или null.
     *
     * Если год у карточки известен — берём только при балле ≥ [MIN_SCORE]
     * (точное название + год ±1 проходит, «похожее» название + точный год — нет).
     * Если год неизвестен — дополнительно требуем однозначности: несколько
     * разных кандидатов вокруг одного названия («Спецы» 2017 фильм и 2026
     * сериал) — мету не берём вовсе.
     */
    fun best(input: MediaItem, candidates: List<MediaItem>): MediaItem? {
        if (input.title.isBlank()) return null
        val typed = candidates.filter { it.type == input.type }
        if (typed.isEmpty()) return null
        val scored = typed.map { it to score(input, it) }.filter { it.second >= MIN_SCORE }
        if (scored.isEmpty()) return null
        val top = scored.maxByOrNull { it.second } ?: return null
        if (input.year.isBlank()) {
            // Однозначность: если равные по баллу кандидаты отличаются годом —
            // уверенности нет (ремейк/одноимённый фильм)
            val tied = scored.filter { it.second == top.second }.map { it.first.year }.distinct()
            if (tied.size > 1) return null
        }
        // Балл равенства по названию: если топ делят два разных года — не берём
        val sameScoreYears = scored.filter { it.second == top.second }.map { it.first.year }.distinct()
        if (sameScoreYears.size > 1) return null
        return top.first
    }
}
