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

    /**
     * Поступившие с пагинацией для раздела «Торренты»: DLE-параметр cstart —
     * иначе подстановка «/page/N/» в URL с query-параметром даёт битые ссылки.
     */
    fun lastnews(page: Int = 1): List<MediaItem> {
        val url = if (page > 1) "$BASE/index.php?do=lastnews&cstart=$page" else "$BASE/index.php?do=lastnews"
        return category(url, 1)
    }

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
    fun search(query: String, limit: Int = 40): List<MediaItem> {
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
        // Слова запроса: «титаны 2 сезон» не должно требовать точного вхождения
        // всей строки — иначе поиск выглядел пустым при любом уточнении
        val words = query.lowercase().replace('ё', 'е')
            .split(Regex("""[^a-zа-я0-9]+"""))
            .filter { it.length >= 2 }
        for (r in rows) {
            val name = Regex("""<td><b>([^<]{4,})</b></td>""").find(r)?.groupValues?.get(1)?.trim() ?: continue
            val clean = name.replace(Regex("""\s*\[.*$"""), "").trim()          // «Спецы [01x01-08…]» → «Спецы»
            val m = Regex("""^(.*?)\s*\((\d{4})\)""").find(clean)
            // Название чистим: «Матрица / The Matrix | 1999 | UHD BDRemux…» →
            // «Матрица» (иначе в поиске карточки с километровыми именами раздач)
            val title = (m?.groupValues?.get(1) ?: clean)
                .substringBefore(" | ")
                .substringBefore(" / ")
                .replace("|", "")
                .trim()
            val year = m?.groupValues?.get(2)
                ?: Regex("""\b(19|20)\d{2}\b""").find(name)?.value.orEmpty()
            val key = title.lowercase().replace('ё', 'е')
            if (title.length < 2 || !seen.add(key)) continue
            // Фильтр мусора: в названии должно быть хоть одно слово запроса
            // (поиск «спецы» не должен возвращать «Специи», книги, спорт)
            if (words.isNotEmpty() && words.none { key.contains(it) }) continue
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
                // Жанр из названия раздачи — чтобы фильтр жанров работал и в поиске
                rtGenres = listOfNotNull(
                    if (tv) "serialy" else null,
                    genreSlugByTitle(name),
                ),
            )
            if (items.size >= limit) break
        }
        return items
    }

    /** Жанр по ключевому слову в названии раздачи (для клиентского фильтра). */
    private fun genreSlugByTitle(name: String): String? {
        val n = name.lowercase().replace('ё', 'е')
        return when {
            Regex("""комеди""").containsMatchIn(n) -> "komedii"
            Regex("""драм""").containsMatchIn(n) -> "dramy"
            Regex("""боевик|экшен""").containsMatchIn(n) -> "boeviki"
            Regex("""ужас""").containsMatchIn(n) -> "uzhasy"
            Regex("""фантаст""").containsMatchIn(n) -> "fantastika"
            Regex("""фэнтези|фентези""").containsMatchIn(n) -> "fjentezi"
            Regex("""триллер""").containsMatchIn(n) -> "trillery"
            Regex("""детектив""").containsMatchIn(n) -> "kriminal"
            Regex("""мелодрам""").containsMatchIn(n) -> "melodramy"
            Regex("""мульт|аниме""").containsMatchIn(n) -> "multfilmy"
            else -> null
        }
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

    /** Жанры для фильтров каталога: пары (slug, название). */
    fun genreList(): List<Pair<String, String>> = GENRES

    /** Базовый адрес трекера (для сборки URL фильтров). */
    val baseUrl: String get() = BASE

    /** Страница каталога по готовому URL (категория/год/популярное). */
    fun categoryByUrl(url: String): List<MediaItem> = category(url, 1)

    private fun genreId(slug: String): Int = kotlin.math.abs(("rtg-" + slug).hashCode())

    fun byGenre(id: Int, page: Int): List<MediaItem> {
        val slug = GENRES.firstOrNull { genreId(it.first) == id }?.first ?: return emptyList()
        return category("$BASE/$slug/", page)
    }

    /** Разбор страницы категории/подобного списка: карточки rutef_movie. */
    private fun category(url: String, page: Int): List<MediaItem> {
        val full = if (page > 1) url.trimEnd('/') + "/page/$page/" else url
        val html = TorrentHttp.getFast(full, UA)
        AppLog.log("torrents", "запрос $full → ${if (html != null) "${html.length} байт" else "НЕТ ОТВЕТА"}")
        return if (html != null) parseCards(html) else emptyList()
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
            // Страна текстом: «<b>Страна:</b> США, Великобритания»
            val country = Regex("""Страна:</b>\s*([^<]+)""").find(b)
                ?.groupValues?.get(1)?.trim().orEmpty()
            // Жанры карточки — ссылки в блоке rutef_genrus: href="/boeviki/">Боевики</a>
            val genrusBlock = b.substringAfter("rutef_genrus", "").substringBefore("</div>")
            val genres = Regex("""href="/([a-z_]+)/"""").findAll(genrusBlock)
                .map { it.groupValues[1] }
                .filter { it != "xfsearch" }
                .distinct()
                .toList()
            // Сериал, если среди жанров есть «Сериалы» (/serialy/)
            val isTv = genres.contains("serialy")
            val poster = Regex("""<img src="([^"]+)"""").find(b)?.groupValues?.get(1)
                ?.takeIf { !it.contains("none_poster") }
                ?.let { if (it.startsWith("http")) it else BASE + it } ?: ""
            out += MediaItem(
                id = kotlin.math.abs(url.hashCode()),
                type = if (isTv) "tv" else "movie",
                title = title,
                year = year,
                posterUrl = poster,
                country = country,
                rtGenres = genres,
                // Служебный адрес страницы раздачи — для «Похожего»
                originalTitle = "@rt:" + (if (url.startsWith("http")) url else BASE + url),
            )
            if (out.size >= 40) break
        }
        return out
    }
}
