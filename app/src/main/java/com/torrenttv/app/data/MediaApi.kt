package com.torrenttv.app.data

/**
 * Единая точка доступа к метаданным фильмов/сериалов.
 *
 * Приоритет провайдера: kinopoisk.dev (доступен из РФ) → TMDB (если сеть позволяет).
 * Ключи задаются в gradle.properties: kpApiKey=..., tmdbApiKey=...
 */
object MediaApi {

    val isConfigured: Boolean
        get() = KinopoiskClient.isConfigured || TmdbClient.isConfigured

    private val kp: Boolean
        get() = KinopoiskClient.isConfigured

    fun search(query: String): List<MediaItem> =
        if (kp) KinopoiskClient.search(query) else TmdbClient.search(query)

    fun trending(page: Int = 1): List<MediaItem> =
        if (kp) KinopoiskClient.trending(page) else TmdbClient.trending(page)

    fun popularMovies(page: Int = 1): List<MediaItem> =
        if (kp) KinopoiskClient.popularMovies(page) else TmdbClient.popularMovies(page)

    fun popularTv(page: Int = 1): List<MediaItem> =
        if (kp) KinopoiskClient.popularTv(page) else TmdbClient.popularTv(page)

    fun topRatedMovies(page: Int = 1): List<MediaItem> =
        if (kp) KinopoiskClient.topRatedMovies(page) else TmdbClient.topRatedMovies(page)

    fun topRatedTv(page: Int = 1): List<MediaItem> =
        if (kp) KinopoiskClient.topRatedTv(page) else TmdbClient.topRatedTv(page)

    fun moviesByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        if (kp) KinopoiskClient.moviesByGenre(genreId, page) else TmdbClient.moviesByGenre(genreId, page)

    fun tvByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        if (kp) KinopoiskClient.tvByGenre(genreId, page) else TmdbClient.tvByGenre(genreId, page)

    fun similar(item: MediaItem): List<MediaItem> =
        if (kp) KinopoiskClient.similar(item) else TmdbClient.similar(item)

    fun movieGenres(): List<Genre> =
        if (kp) KinopoiskClient.movieGenres() else TmdbClient.movieGenres()

    fun tvGenres(): List<Genre> =
        if (kp) KinopoiskClient.tvGenres() else TmdbClient.tvGenres()

    /** Список стран для фильтра (для TMDB не поддерживается — пустой список). */
    fun countries(): List<Genre> =
        if (kp) KinopoiskClient.countries() else emptyList()
}
