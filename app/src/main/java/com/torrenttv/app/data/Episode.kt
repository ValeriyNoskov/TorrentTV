package com.torrenttv.app.data

/** Эпизод сериала (для экрана «Сезоны и эпизоды»). */
data class Episode(
    val season: Int,
    val episode: Int,
    val title: String = "",
) {
    /** Метка вида S01E05 для интерфейса и запросов поиска. */
    val code: String get() = "S%02dE%02d".format(season, episode)

    /** Суффикс ключа хранилища: у каждого эпизода свой magnet и свой прогресс. */
    val sourceSuffix: String get() = ":" + code.lowercase()

    companion object {
        /**
         * Совпадает ли имя файла с эпизодом: S01E05, s1e5, 1x05.
         * Для пачек сезона — чтобы плеер включил нужную серию.
         */
        fun fileMatches(name: String, season: Int, episode: Int): Boolean {
            val n = name.lowercase()
            Regex("""s(\d{1,2})[._ -]?e(\d{1,3})""").find(n)?.let { m ->
                if (m.groupValues[1].toInt() == season && m.groupValues[2].toInt() == episode) return true
            }
            Regex("""(\d{1,2})x(\d{1,3})(\D|$)""").find(n)?.let { m ->
                if (m.groupValues[1].toInt() == season && m.groupValues[2].toInt() == episode) return true
            }
            return false
        }
    }
}

/** Сезон с порядком эпизодов. */
data class Season(
    val number: Int,
    val episodes: List<Episode>,
)
