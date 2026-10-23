package com.torrenttv.app.data

/**
 * Каталог фильмов/сериалов из открытого «Рутрекера» (rutrackerr.ru) —
 * работает без ключей и лимитов. Используется как последний фолбэк,
 * когда недоступны и Кинопоиск, и TMDB: список названий с постерами
 * берём из категорий и поиска сайта.
 */
object RutrackerrCatalog {

    private const val BASE = "https://rutrackerr.ru"

    private val UA = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120")

    /** «Сейчас в тренде»: популярное на сайте. */
    fun trending(page: Int = 1): List<MediaItem> = category("$BASE/popular/", page)

    /** Новинки (дата добавления). */
    fun newItems(page: Int = 1): List<MediaItem> = category("$BASE/index.php?do=lastnews", page)

    /** Популярные фильмы: главная-подобные списки фильмов. */
    fun popularMovies(page: Int = 1): List<MediaItem> =
        category("$BASE/filmy/", page).ifEmpty { newItems(page) }

    /** Сериалы. */
    fun popularTv(page: Int = 1): List<MediaItem> = category("$BASE/serialy/", page)

    /** Шоу (реалити, ток-шоу): отдельного раздела на сайте нет —
     *  отбираем по названию из сериалов; может быть узким или пустым. */
    fun popularShows(page: Int = 1): List<MediaItem> =
        category("$BASE/serialy/", page).filter {
            Regex("(?i)шоу|реалити|ток-|выживани|голос|битва|маска|фабрик|звёзд|звезд|проект").containsMatchIn(it.title)
        }

    /** Поиск: AJAX сайта возвращает список раздач — группируем по названиям. */
    fun search(query: String): List<MediaItem> {
        val html = TorrentHttp.postForm(
            url = "https://rutrackerr.ru/engine/modules/search-torrents/search.php",
            fields = mapOf("fraza" to query, "id" to "", "search_ok" to "go_search"),
            headers = UA + mapOf("Referer" to "$BASE/"),
        ) ?: return emptyList()

        // Выдача поиска (2026): строки раздач rutor/NNM без страниц тор.html —
        // только название, magnet и сиды/пиры. Группируем по названию фильма.
        val rows = html.split("<tr>")
        val seen = mutableSetOf<String>()
        val isTvByKey = mutableMapOf<String, Boolean>()
        val items = mutableListOf<MediaItem>()
        for (r in rows) {
            val name = Regex("""<td><b>([^<]{4,})</b></td>""").find(r)?.groupValues?.get(1)?.trim() ?: continue
            val clean = name.replace(Regex("""\s*\[.*$"""), "").trim()          // «Спецы [01x01-08…]» → «Спецы»
            val m = Regex("""^(.*?)\s*\((\d{4})\)""").find(clean)
            val title = (m?.groupValues?.get(1) ?: clean).replace("|", "").trim()
            val year = m?.groupValues?.get(2) ?: ""
            val key = title.lowercase().replace('ё', 'е')
            // Фильтр мусора: название должно содержать запрос (иначе поиск
            // «спецы» возвращает «Специи», книги, спорт)
            val q = query.lowercase().replace('ё', 'е').trim()
            if (title.length < 2 || !seen.add(key)) continue
            if (!key.contains(q)) continue
            // Сериал? S01E05, 1x05, [01x01-08], «Сезон 1», «Серии» в названии
            val tv = isTvByKey.getOrPut(key) {
                Regex("""(?i)[sс]\d{1,2}[eеxх]\d{1,3}|\[\d{2}x\d{2}|сезон|сери[ияя]""").containsMatchIn(name)
            }
            items += MediaItem(
                id = kotlin.math.abs(("rt-" + key).hashCode()),
                type = if (tv) "tv" else "movie",
                title = title,
                year = year,
                posterUrl = "",
            )
            if (items.size >= 12) break
        }
        return items
    }

    /** «Похожее» для элемента из Рутрекера: блок похожих на его странице. */
    fun similar(item: MediaItem): List<MediaItem> {
        val pageUrl = item.originalTitle.takeIf { it.startsWith("@rt:") }?.removePrefix("@rt:")
            ?: return emptyList()
        val page = TorrentHttp.get(pageUrl, UA) ?: return emptyList()
        return parseCards(page).filter { it.title != item.title }.take(12)
    }

    // ---------- Жанры = категории сайта (работают без лимитов) ----------

    private val GENRES = listOf(
        "boeviki" to "Боевики",
        "komedii" to "Комедии",
        "dramy" to "Драмы",
        "trillery" to "Триллеры",
        "uzhasy" to "Ужасы",
        "fantastika" to "Фантастика",
        "fjentezi" to "Фэнтези",
        "kriminal" to "Криминал",
        "melodramy" to "Мелодрамы",
        "prikljuchenija" to "Приключения",
        "semejnye" to "Семейные",
        "detskie" to "Детские",
        "multfilmy" to "Мультфильмы",
        "anime" to "Аниме",
        "dokumentalnye" to "Документальные",
        "voennye" to "Военные",
        "sport" to "Спорт",
        "serialy" to "Сериалы",
    )

    /** Жанры-категории Рутрекера (id = hashCode слага — по нему находим slug). */
    fun genres(): List<Genre> = GENRES.map { (slug, name) -> Genre(genreId(slug), name) }

    private fun genreId(slug: String): Int = kotlin.math.abs(("rtg-" + slug).hashCode())

    fun byGenre(id: Int, page: Int): List<MediaItem> {
        val slug = GENRES.firstOrNull { genreId(it.first) == id }?.first ?: return emptyList()
        return category("$BASE/$slug/", page)
    }

    /** Разбор страницы категории/подобного списка: карточки rutef_movie. */
    private fun category(url: String, page: Int): List<MediaItem> {
        val full = if (page > 1) url.trimEnd('/') + "/page/$page/" else url
        val html = TorrentHttp.get(full, UA) ?: return emptyList()
        return parseCards(html)
    }

    private fun parseCards(html: String): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val blocks = html.split("<div class=\"rutef_movie-0c1\">")
        for (b in blocks.drop(1)) {
            // Страница раздачи: /NNNN-name-year-tor.html (иногда с доменом)
            val url = Regex("""href="((?:https://rutrackerr\.ru)?/\d+[^"]*tor\.html)"""")
                .find(b)?.groupValues?.get(1) ?: continue
            val titleAll = Regex("""<h1>([^<]{2,120})</h1>""").find(b)?.groupValues?.get(1)?.trim() ?: continue
            val m = Regex("""^(.*?)\s*\((\d{4})\)""").find(titleAll)
            val title = (m?.groupValues?.get(1) ?: titleAll).trim()
            val year = m?.groupValues?.get(2) ?: ""
            val isTv = b.contains("/serialy/")
            val poster = Regex("""<img src="([^"]+)"""").find(b)?.groupValues?.get(1)
                ?.takeIf { !it.contains("none_poster") }
                ?.let { if (it.startsWith("http")) it else BASE + it } ?: ""
            out += MediaItem(
                id = kotlin.math.abs(url.hashCode()),
                type = if (isTv) "tv" else "movie",
                title = title,
                year = year,
                posterUrl = poster,
                // Служебный адрес страницы раздачи — для «Похожего»
                originalTitle = "@rt:" + (if (url.startsWith("http")) url else BASE + url),
            )
            if (out.size >= 40) break
        }
        return out
    }
}
