package com.torrenttv.app.data

/**
 * Единая точка доступа к метаданным фильмов/сериалов.
 *
 * Основной провайдер — Kinopoisk API Unofficial (доступен из РФ). Если он
 * недоступен или исчерпан его дневной лимит — автоматический фолбэк на TMDB.
 * Ключи задаются в gradle.properties: kpApiKey=..., tmdbApiKey=...
 */
object MediaApi {

    val isConfigured: Boolean
        get() = KinopoiskClient.isConfigured || TmdbClient.isConfigured

    private val kp: Boolean
        get() = KinopoiskClient.isConfigured

    /** Последний запрос метаданных завершился ошибкой (для показа «Повторить»). */
    val lastSearchFailed: Boolean
        get() = if (kp) KinopoiskClient.lastFailed else TmdbClient.lastFailed

    /**
     * Выбрать источник: KP основной → TMDB при его ошибке → каталог
     * «Рутрекер» (без ключей и лимитов), если и TMDB недоступен.
     */
    private fun <T> src(kpCall: () -> T, tmdbCall: () -> T, rtCall: () -> T? = { null }): T {
        if (!kp) {
            val t = tmdbCall()
            val tmdbDead = TmdbClient.lastFailed || (t is List<*> && t.isEmpty())
            return if (tmdbDead) (rtCall() ?: t) else t
        }
        val r = kpCall()
        if (!KinopoiskClient.lastFailed) return r
        if (TmdbClient.isConfigured) {
            val t = tmdbCall()
            val tmdbDead = TmdbClient.lastFailed || (t is List<*> && t.isEmpty())
            if (!tmdbDead) return t
        }
        return rtCall() ?: r
    }

    fun search(query: String): List<MediaItem> =
        src({ KinopoiskClient.search(query) }, { TmdbClient.search(query) }, { RutrackerrCatalog.search(query) })

    fun trending(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.trending(page) }, { TmdbClient.trending(page) }, { RutrackerrCatalog.trending(page) })

    /** Новинки/премьеры: КП премьеры → TMDB upcoming → тренды. */
    fun newReleases(): List<MediaItem> =
        src({ KinopoiskClient.premieres() }, { TmdbClient.upcoming() }, { trending(1) })

    fun popularMovies(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.popularMovies(page) }, { TmdbClient.popularMovies(page) }, { RutrackerrCatalog.popularMovies(page) })

    fun popularTv(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.popularTv(page) }, { TmdbClient.popularTv(page) }, { RutrackerrCatalog.popularTv(page) })

    /** Шоу (реалити, ток-шоу, ТВ-передачи): КП TV_SHOW → TMDB реалити → Рутрекер (фильтр по названию). */
    fun popularShows(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.popularShows(page) }, { TmdbClient.popularShows(page) }, { RutrackerrCatalog.popularShows(page) })

    fun topRatedMovies(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.topRatedMovies(page) }, { TmdbClient.topRatedMovies(page) }, { RutrackerrCatalog.popularMovies(page) })

    fun topRatedTv(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.topRatedTv(page) }, { TmdbClient.topRatedTv(page) }, { RutrackerrCatalog.popularTv(page) })

    // ---------- Полки каталога: новинки / русские / зарубежные ----------

    /** Новинки-фильмы (свежие релизы — на них сразу есть раздачи). */
    fun newMovies(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.popularMovies(page) }, { TmdbClient.newMovies(page) }, { RutrackerrCatalog.popularMovies(page) })

    /** Новинки-сериалы. */
    fun newTv(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.popularTv(page) }, { TmdbClient.newTv(page) }, { RutrackerrCatalog.popularTv(page) })

    /** Русские фильмы (производство РФ — КП сам даёт страну, TMDB — по языку). */
    fun russianMovies(page: Int = 1): List<MediaItem> =
        src(
            {
                KinopoiskClient.popularMovies(page).let { all ->
                    val ru = all.filter { it.originalLanguage == "ru" }
                    // У КП язык может быть не заполнен — лучше показать все, чем пусто
                    if (ru.isEmpty()) all else ru
                }
            },
            { TmdbClient.russianMovies(page) },
        )

    /** Русские сериалы. */
    fun russianTv(page: Int = 1): List<MediaItem> =
        src(
            { KinopoiskClient.popularTv(page).filter { it.originalLanguage == "ru" } },
            { TmdbClient.russianTv(page) },
        )

    /** Зарубежные фильмы: популярные минус русские. */
    fun foreignMovies(page: Int = 1): List<MediaItem> =
        src(
            { KinopoiskClient.popularMovies(page).filter { it.originalLanguage != "ru" } },
            { TmdbClient.foreignMovies(page) },
        )

    /** Зарубежные сериалы. */
    fun foreignTv(page: Int = 1): List<MediaItem> =
        src(
            { KinopoiskClient.popularTv(page).filter { it.originalLanguage != "ru" } },
            { TmdbClient.foreignTv(page) },
        )

    fun moviesByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.moviesByGenre(genreId, page) }, { TmdbClient.moviesByGenre(genreId, page) }, { RutrackerrCatalog.byGenre(genreId, page) })

    fun tvByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.tvByGenre(genreId, page) }, { TmdbClient.tvByGenre(genreId, page) }, { RutrackerrCatalog.byGenre(genreId, page) })

    fun similar(item: MediaItem): List<MediaItem> =
        src({ KinopoiskClient.similar(item) }, { TmdbClient.similar(item) }, { RutrackerrCatalog.similar(item) })

    /** Сезоны и эпизоды сериала. */
    fun seasons(item: MediaItem): List<Season> =
        src({ KinopoiskClient.seasons(item) }, { TmdbClient.seasons(item) })

    fun movieGenres(): List<Genre> =
        src({ KinopoiskClient.movieGenres() }, { TmdbClient.movieGenres() }, { RutrackerrCatalog.genres() })

    fun tvGenres(): List<Genre> =
        src({ KinopoiskClient.tvGenres() }, { TmdbClient.tvGenres() }, { RutrackerrCatalog.genres() })

    /** Список стран для фильтра (для TMDB не поддерживается — пустой список). */
    fun countries(): List<Genre> =
        src({ KinopoiskClient.countries() }, { emptyList() }, { emptyList() })
}
