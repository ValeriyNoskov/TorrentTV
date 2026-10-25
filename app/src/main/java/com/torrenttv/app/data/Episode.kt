package com.torrenttv.app.data

/** Эпизод сериала (для экрана «Сезоны и эпизоды»). */
data class Episode(
    val season: Int,
    val episode: Int,
    val title: String = "",
    /** Дата выхода (yyyy-MM-dd), пусто — неизвестна/не выходила. Источник — Trakt. */
    val airDate: String = "",
) {
    /** Метка вида S01E05 для интерфейса и запросов поиска. */
    val code: String get() = "S%02dE%02d".format(season, episode)

    /** Суффикс ключа хранилища: у каждого эпизода свой magnet и свой прогресс. */
    val sourceSuffix: String get() = ":" + code.lowercase()

    companion object {
        /**
         * Номера (сезон, эпизод) из имени файла: S01E05 / 1x05 / E05 (сезон 1) /
         * «03. Название.S05…» (номер в начале + сезон в середине — паки
         * типа Files-x без E-разметки).
         */
        fun fileNumbers(name: String): Pair<Int, Int>? {
            val n = name.substringAfterLast('/').substringBeforeLast('.')
            Regex("""s(\d{1,2})[._ -]?e(\d{1,3})""", RegexOption.IGNORE_CASE).find(n)?.let {
                return it.groupValues[1].toInt() to it.groupValues[2].toInt()
            }
            Regex("""(\d{1,2})x(\d{1,3})(\D|$)""", RegexOption.IGNORE_CASE).find(n)?.let {
                return it.groupValues[1].toInt() to it.groupValues[2].toInt()
            }
            // Сезон в имени (S05) без номера эпизода + номер в начале
            // («03. Титаны.S05.2026…») — иначе E00 ломает память серий
            Regex("""s(\d{1,2})(\D|$)""", RegexOption.IGNORE_CASE).find(n)?.let { m ->
                Regex("""^0?(\d{1,3})(\.|\D|$)""").find(n)?.let { e ->
                    val ep = e.groupValues[1].toInt()
                    if (ep in 1..99) return m.groupValues[1].toInt() to ep
                }
            }
            // Сезон только в имени ПАПКИ — как в Lampa (EpisodeParser,
            // folder_regexps): «Титаны/Season 5/01.mkv», «/5 сезон/06.mkv».
            // Без этого такие паки считались первым сезоном.
            folderSeason(name)?.let { season ->
                Regex("""^0?(\d{1,3})(\D|$)""").find(n)?.let { e ->
                    val ep = e.groupValues[1].toInt()
                    if (ep in 1..99) return season to ep
                }
            }
            Regex("""[._ -]e(\d{1,3})(\D|$)""", RegexOption.IGNORE_CASE).find(n)?.let {
                return 1 to it.groupValues[1].toInt()
            }
            // «07. Название серии» — номер с ведущим нулём в начале имени
            // (ведущий ноль отличает серию от года «2026 …»)
            Regex("""^0(\d)(\D|$)""").find(n)?.let {
                return 1 to it.groupValues[1].toInt()
            }
            return null
        }

        /**
         * Сезон из имени папки («Season 5», «5 сезон», «S05»). null — нет метки.
         * Проверенное решение Lampa: в паках сезон часто указан только на
         * папке, а файлы называются «01. Серия.mkv».
         */
        fun folderSeason(name: String): Int? {
            val folder = name.substringBeforeLast('/', "").substringAfterLast('/')
            if (folder.isBlank()) return null
            Regex("""(?:season|сезон)\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
                .find(folder)?.let { return it.groupValues[1].toInt() }
            Regex("""(\d{1,2})\s*(?:season|сезон)""", RegexOption.IGNORE_CASE)
                .find(folder)?.let { return it.groupValues[1].toInt() }
            Regex("""\bs(\d{1,2})\b""", RegexOption.IGNORE_CASE)
                .find(folder)?.let { return it.groupValues[1].toInt() }
            return null
        }

        /**
         * Сезон, если он явно указан в имени файла: «S05», «5 сезон», «s1e5».
         * null — сезонной метки нет (тогда номер серии в начале имени можно
         * трактовать как «сезон неизвестен»).
         */
        fun fileSeasonInName(name: String): Int? {
            val n = name.substringAfterLast('/').substringBeforeLast('.')
            Regex("""s(\d{1,2})""", RegexOption.IGNORE_CASE).find(n)?.let {
                return it.groupValues[1].toInt()
            }
            Regex("""(\d{1,2})\s*[сС]езон""").find(n)?.let {
                return it.groupValues[1].toInt()
            }
            Regex("""(\d{1,2})x(\d{1,3})""", RegexOption.IGNORE_CASE).find(n)?.let {
                return it.groupValues[1].toInt()
            }
            // Папка: «Season 5/», «5 сезон/», «S05/»
            return folderSeason(name)
        }

        /**
         * Номер серии в начале имени — для раздач БЕЗ сезонной метки
         * («03. Название.mkv»). null, если сезон в имени есть.
         */
        fun fileEpisodeNoSeason(name: String): Int? {
            if (fileSeasonInName(name) != null) return null
            val n = name.substringAfterLast('/').substringBeforeLast('.')
            Regex("""^(\d{1,3})(\D|$)""").find(n)?.let {
                val ep = it.groupValues[1].toInt()
                if (ep in 1..99) return ep
            }
            Regex("""[._ -]e(\d{1,3})(\D|$)""", RegexOption.IGNORE_CASE).find(n)?.let {
                return it.groupValues[1].toInt()
            }
            return null
        }

        /**
         * Совпадает ли имя файла с эпизодом: S01E05, s1e5, 1x05,
         * «03. Название.S05…». Для пачек сезона — чтобы плеер включил
         * нужную серию независимо от раздачи.
         */
        fun fileMatches(name: String, season: Int, episode: Int): Boolean {
            val n = name.lowercase()
            Regex("""s(\d{1,2})[._ -]?e(\d{1,3})""").find(n)?.let { m ->
                if (m.groupValues[1].toInt() == season && m.groupValues[2].toInt() == episode) return true
            }
            Regex("""(\d{1,2})x(\d{1,3})(\D|$)""").find(n)?.let { m ->
                if (m.groupValues[1].toInt() == season && m.groupValues[2].toInt() == episode) return true
            }
            // Универсальный fallback: парсер номеров (в т.ч. «03. …S05…»)
            fileNumbers(name)?.let { (s, e) -> if (s == season && e == episode) return true }
            return false
        }
    }
}

/** Сезон с порядком эпизодов. */
data class Season(
    val number: Int,
    val episodes: List<Episode>,
)
