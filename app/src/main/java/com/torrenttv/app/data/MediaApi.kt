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

    fun popularMovies(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.popularMovies(page) }, { TmdbClient.popularMovies(page) }, { RutrackerrCatalog.popularMovies(page) })

    fun popularTv(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.popularTv(page) }, { TmdbClient.popularTv(page) }, { RutrackerrCatalog.popularTv(page) })

    fun topRatedMovies(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.topRatedMovies(page) }, { TmdbClient.topRatedMovies(page) }, { RutrackerrCatalog.popularMovies(page) })

    fun topRatedTv(page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.topRatedTv(page) }, { TmdbClient.topRatedTv(page) }, { RutrackerrCatalog.popularTv(page) })

    fun moviesByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.moviesByGenre(genreId, page) }, { TmdbClient.moviesByGenre(genreId, page) }, { RutrackerrCatalog.popularMovies(page) })

    fun tvByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        src({ KinopoiskClient.tvByGenre(genreId, page) }, { TmdbClient.tvByGenre(genreId, page) }, { RutrackerrCatalog.popularTv(page) })

    fun similar(item: MediaItem): List<MediaItem> =
        src({ KinopoiskClient.similar(item) }, { TmdbClient.similar(item) })

    /** Сезоны и эпизоды сериала. */
    fun seasons(item: MediaItem): List<Season> =
        src({ KinopoiskClient.seasons(item) }, { TmdbClient.seasons(item) })

    fun movieGenres(): List<Genre> =
        src({ KinopoiskClient.movieGenres() }, { TmdbClient.movieGenres() })

    fun tvGenres(): List<Genre> =
        src({ KinopoiskClient.tvGenres() }, { TmdbClient.tvGenres() })

    /** Список стран для фильтра (для TMDB не поддерживается — пустой список). */
    fun countries(): List<Genre> =
        src({ KinopoiskClient.countries() }, { emptyList() })
}
