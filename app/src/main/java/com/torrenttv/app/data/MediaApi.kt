package com.torrenttv.app.data

/**
 * Единая точка доступа к метаданным фильмов/сериалов.
 *
 * Приоритет источников (2026-10-24, решение владельца — уход от зависимости от КП):
 * 1) каталог «Рутрекер» — без ключей и лимитов, работает из РФ всегда;
 * 2) Kinopoisk API — только обогащение (названия, описания, рейтинги), не критичный путь;
 * 3) TMDB — последний (заблокирован из РФ).
 * Постеры-фолбэк — WikipediaClient. Эпизоды — из раздач (EpisodesScreen).
 */
object MediaApi {

    /** Каталоги работают без ключей (Рутрекер первичен) — предупреждение в UI не нужно. */
    val isConfigured: Boolean
        get() = true

    private val kp: Boolean
        get() = KinopoiskClient.isConfigured

    /** Последний запрос метаданных завершился ошибкой (для показа «Повторить»). */
    val lastSearchFailed: Boolean
        get() = KinopoiskClient.lastFailed

    /**
     * Первый непустой результат по цепочке источников; пустой/упавший — пропускаем.
     */
    private fun <T> pick(vararg calls: () -> T?): T? =
        calls.firstNotNullOfOrNull { f ->
            try { f() } catch (e: Exception) { null }
                ?.takeIf { it !is List<*> || it.isNotEmpty() }
        }

    fun search(query: String): List<MediaItem> =
        pick({ RutrackerrCatalog.search(query) }, { KinopoiskClient.search(query) }, { TmdbClient.search(query) })
            ?: emptyList()

    fun trending(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.trending(page) }, { KinopoiskClient.trending(page) }, { TmdbClient.trending(page) })
            ?: emptyList()

    /** Новинки/премьеры: свежее Рутрекера → КП премьеры → тренды. */
    fun newReleases(): List<MediaItem> =
        pick({ RutrackerrCatalog.newItems(1) }, { KinopoiskClient.premieres() }, { trending(1) })
            ?: emptyList()

    fun popularMovies(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.popularMovies(page) }, { KinopoiskClient.popularMovies(page) }, { TmdbClient.popularMovies(page) })
            ?: emptyList()

    fun popularTv(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.popularTv(page) }, { KinopoiskClient.popularTv(page) }, { TmdbClient.popularTv(page) })
            ?: emptyList()

    /** Шоу (реалити, ток-шоу, ТВ-передачи): Рутрекер → КП TV_SHOW → TMDB. */
    fun popularShows(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.popularShows(page) }, { KinopoiskClient.popularShows(page) }, { TmdbClient.popularShows(page) })
            ?: emptyList()

    fun topRatedMovies(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.popularMovies(page) }, { KinopoiskClient.topRatedMovies(page) }) ?: emptyList()

    fun topRatedTv(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.popularTv(page) }, { KinopoiskClient.topRatedTv(page) }) ?: emptyList()

    // ---------- Полки каталога: новинки / русские / зарубежные ----------

    /** Новинки-фильмы (свежие релизы — на них сразу есть раздачи). */
    fun newMovies(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.newItems(page) }, { KinopoiskClient.popularMovies(page) }) ?: emptyList()

    /** Новинки-сериалы. */
    fun newTv(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.newItems(page) }, { KinopoiskClient.popularTv(page) }) ?: emptyList()

    /** Русские фильмы (производство РФ — КП сам даёт страну, TMDB — по языку). */
    fun russianMovies(page: Int = 1): List<MediaItem> =
        pick(
            { RutrackerrCatalog.popularMovies(page) },
            {
                KinopoiskClient.popularMovies(page).let { all ->
                    val ru = all.filter { it.originalLanguage == "ru" }
                    // У КП язык может быть не заполнен — лучше показать все, чем пусто
                    if (ru.isEmpty()) all else ru
                }
            },
        ) ?: emptyList()

    /** Русские сериалы. */
    fun russianTv(page: Int = 1): List<MediaItem> =
        pick(
            { RutrackerrCatalog.popularTv(page) },
            { KinopoiskClient.popularTv(page).filter { it.originalLanguage == "ru" } },
        ) ?: emptyList()

    /** Зарубежные фильмы: популярные минус русские. */
    fun foreignMovies(page: Int = 1): List<MediaItem> =
        pick(
            { RutrackerrCatalog.popularMovies(page) },
            { KinopoiskClient.popularMovies(page).filter { it.originalLanguage != "ru" } },
        ) ?: emptyList()

    /** Зарубежные сериалы. */
    fun foreignTv(page: Int = 1): List<MediaItem> =
        pick(
            { RutrackerrCatalog.popularTv(page) },
            { KinopoiskClient.popularTv(page).filter { it.originalLanguage != "ru" } },
        ) ?: emptyList()

    fun moviesByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.byGenre(genreId, page) }, { KinopoiskClient.moviesByGenre(genreId, page) }) ?: emptyList()

    fun tvByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.byGenre(genreId, page) }, { KinopoiskClient.tvByGenre(genreId, page) }) ?: emptyList()

    fun similar(item: MediaItem): List<MediaItem> =
        pick({ RutrackerrCatalog.similar(item) }, { KinopoiskClient.similar(item) }) ?: emptyList()

    /** Сезоны и эпизоды сериала (обогащение; главный источник эпизодов — раздача). */
    fun seasons(item: MediaItem): List<Season> =
        pick({ KinopoiskClient.seasons(item) }, { TmdbClient.seasons(item) })?.ifEmpty {
            // Фолбэк: карточка без кинопоиск-id (торрент/рутрекер) — КП по такому id
            // отдаёт пустоту. Ищем тайтл поиском и берём сезоны по найденному id.
            val found = KinopoiskClient.search(item.title).firstOrNull { cand ->
                cand.type == "tv" && cand.title.equals(item.title, ignoreCase = true)
            } ?: KinopoiskClient.search(item.title).firstOrNull { it.type == "tv" }
            found?.let { AppLog.log("episodes", "сезоны по найденному id: «${item.title}» → «${it.title}» id=${it.id}") }
            found?.let { KinopoiskClient.seasons(it) } ?: emptyList()
        } ?: emptyList()

    fun movieGenres(): List<Genre> =
        pick({ RutrackerrCatalog.genres() }, { KinopoiskClient.movieGenres() }) ?: emptyList()

    fun tvGenres(): List<Genre> =
        pick({ RutrackerrCatalog.genres() }, { KinopoiskClient.tvGenres() }) ?: emptyList()

    /** Список стран для фильтра (только КП). */
    fun countries(): List<Genre> =
        pick({ KinopoiskClient.countries() }) ?: emptyList()
}
