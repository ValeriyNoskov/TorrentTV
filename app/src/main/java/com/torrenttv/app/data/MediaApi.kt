package com.torrenttv.app.data

import kotlin.random.Random

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

    fun search(query: String): List<MediaItem> {
        val rt = runCatching { RutrackerrCatalog.search(query) }.getOrDefault(emptyList())
        if (rt.isEmpty()) {
            // Рутрекер молчит — как раньше: первый непустой источник
            return pick({ KinopoiskClient.search(query) }, { TmdbClient.search(query) }).orEmpty()
        }
        // ROADMAP №2: карточки Рутрекера + параллельный запрос КП — достаём
        // постер/год/IMDb, чтобы поиск не выглядел «заглушками». Сопоставление
        // строгое, как в enrich: тип обязан совпасть, спорные годы — не берём
        val kp = runCatching { KinopoiskClient.search(query) }.getOrDefault(emptyList())
        if (kp.isEmpty()) return rt
        return rt.map { card ->
            // Балльное сопоставление (cinesta): тип обязан совпасть, год ±1
            // допустим только при точном названии — иначе чужой постер
            val best = TitleMatch.best(card, kp) ?: return@map card
            card.copy(
                posterUrl = best.posterUrl.ifBlank { card.posterUrl },
                year = best.year.ifBlank { card.year },
                imdbId = best.imdbId.ifBlank { card.imdbId },
                overview = best.overview.ifBlank { card.overview },
                rating = if (best.rating > 0) best.rating else card.rating,
            )
        }
    }

    /**
     * Обогащение «пустой» карточки (заглушки из поиска раздач, без меты):
     * КП-поиск по названию → постер, год, IMDb, описание, рейтинг.
     * id/type НЕ меняем — storeKey записи «Продолжить» должен остаться тем же,
     * иначе история развалится на две карточки.
     */
    /**
     * @param store если передан — мета-кеш: успешное обогащение делается один
     * раз, дальше мгновенно из хранилища (без сети и траты квоты КП).
     */
    fun enrich(item: MediaItem, store: LocalStore? = null): MediaItem {
        if (item.title.isBlank()) return item
        // Уже обогащали? Мгновенно из хранилища, ноль запросов
        if (store != null) {
            val cached = store.metaCacheGet(item.storeKey)
            if (cached != null) {
                return item.copy(
                    posterUrl = cached.posterUrl.ifBlank { item.posterUrl },
                    overview = cached.overview.ifBlank { item.overview },
                    year = cached.year.ifBlank { item.year },
                    imdbId = cached.imdbId.ifBlank { item.imdbId },
                    rating = if (cached.rating > 0) cached.rating else item.rating,
                )
            }
        }
        val cards = try { KinopoiskClient.search(item.title) } catch (e: Exception) { emptyList() }
        // Сопоставление по баллам (эвристика cinesta): точное название + год ±1
        // проходит, «похожее» название без года — нет. Чужой постер хуже, чем
        // его отсутствие: «Спецы»-сериал 2026 получали постер фильма 2017
        val card = TitleMatch.best(item, cards)
        var poster = card?.posterUrl.orEmpty()
        var overview = card?.overview.orEmpty()
        var tmdbImdb = ""
        // Фолбэк 1 — TMDB: работает, когда КП упёрся в дневную квоту (500/сутки).
        // Добирает постер, описание и IMDb ID — без IMDb не работает разметка
        // пропуска (SkipDB/IntroDB ищут именно по нему)
        val imdbKnown = !(card?.imdbId ?: item.imdbId).isBlank()
        if (poster.isBlank() || !imdbKnown) {
            val tm = runCatching { TmdbClient.search(item.title) }.getOrDefault(emptyList())
            val tBest = if (tm.isNotEmpty()) TitleMatch.best(item, tm) else null
            if (tBest != null) {
                if (poster.isBlank()) poster = tBest.posterUrl
                if (overview.isBlank()) overview = tBest.overview
            }
            tmdbImdb = tBest?.imdbId.orEmpty()
        }
        // Фолбэк 2 — Википедия: настоящий художественный постер фильма
        if (poster.isBlank()) {
            poster = runCatching { WikipediaClient.poster(item.title) }.getOrNull().orEmpty()
        }
        // Фолбэк 2 — IMDbOT: бесплатный IMDb-поиск без ключей и лимитов.
        // Даёт точный IMDb ID (нужен и для разметки пропуска) и постер.
        // Сервис периодически лежит — с кулдауном, лежащий не мешает остальным.
        if (poster.isBlank() || (!imdbKnown && tmdbImdb.isBlank())) {
            val imdb = runCatching { ImdbOtClient.search(item.title) }.getOrDefault(emptyList())
            val iBest = if (imdb.isNotEmpty()) TitleMatch.best(item, imdb) else null
            if (iBest != null) {
                if (poster.isBlank()) poster = iBest.posterUrl
                if (tmdbImdb.isBlank()) tmdbImdb = iBest.imdbId
            }
        }
        // Фолбэк 3 — Рутуб: кадр и описание (подсказка владельца: «в рутубе
        // есть постеры и описание некоторых»)
        if (poster.isBlank() || overview.isBlank()) {
            val (rtThumb, rtDesc) = runCatching { RutubeService.searchMeta(item.title) }
                .getOrNull() ?: ("" to "")
            if (poster.isBlank()) poster = rtThumb
            if (overview.isBlank()) overview = rtDesc
        }
        if (poster.isBlank() && overview.isBlank() && card == null) return item
        val enriched = item.copy(
            posterUrl = poster.ifBlank { item.posterUrl },
            year = card?.year?.ifBlank { item.year } ?: item.year,
            imdbId = (card?.imdbId ?: "").ifBlank { tmdbImdb.ifBlank { item.imdbId } },
            overview = overview.ifBlank { item.overview },
            rating = if ((card?.rating ?: 0.0) > 0) card!!.rating else item.rating,
        )
        store?.metaCachePut(item.storeKey, enriched)
        return enriched
    }

    fun trending(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.trending(page) }, { KinopoiskClient.trending(page) }, { TmdbClient.trending(page) })
            ?: emptyList()
            ?: emptyList()

    /**
     * Случайный фильм (как у Zona/Lampa): берём из уже кешируемых списков
     * трендов/популярного (случайная страница 1..5) — отдельный API-запрос
     * не нужен, дневная квота не тратится. Фильтр владельца обязателен:
     * кнопка не должна подсовывать запрещённое. До 3 попыток — страница
     * может целиком отсеться фильтром или экранными качествами.
     */
    fun randomItem(allowed: (MediaItem) -> Boolean = { true }): MediaItem? {
        repeat(3) {
            val list = when (Random.nextInt(0, 3)) {
                0 -> trending(Random.nextInt(1, 6))
                1 -> popularMovies(Random.nextInt(1, 6))
                else -> newMovies(Random.nextInt(1, 6))
            }.filter { allowed(it) && it.type == "movie" && it.rating > 0.0 }
            if (list.isNotEmpty()) return list.random()
        }
        return null
    }

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

    /**
     * «Поступившие»: свежие раздачи на трекере (дата появления), без ключей
     * и лимитов. type: "movie" — только фильмы, иначе сериалы/шоу.
     */
    fun postedItems(page: Int, type: String): List<MediaItem> =
        RutrackerrCatalog.newItems(page).filter { if (type == "movie") it.type == "movie" else it.type != "movie" }

    /** Сортировка по году (новинки) или рейтингу: серверная, с фолбэком на Рутрекер. */
    fun orderedMovies(page: Int, order: String, genreId: Int? = null): List<MediaItem> =
        pick(
            { KinopoiskClient.ordered(page, "FILM", order, genreId) },
            { RutrackerrCatalog.popularMovies(page) },
        ) ?: emptyList()

    /** Единая точка для полки сортировки в каталоге (год/рейтинг). */
    fun orderedMoviesTv(
        page: Int,
        order: String,
        movies: Boolean,
        shows: Boolean = false,
        genreId: Int? = null,
    ): List<MediaItem> =
        if (movies) orderedMovies(page, order, genreId) else orderedTv(page, order, shows, genreId)

    fun orderedTv(page: Int, order: String, shows: Boolean = false, genreId: Int? = null): List<MediaItem> =
        pick(
            { KinopoiskClient.ordered(page, if (shows) "TV_SHOW" else "TV_SERIES", order, genreId) },
            { if (shows) RutrackerrCatalog.popularShows(page) else RutrackerrCatalog.popularTv(page) },
        ) ?: emptyList()

    /** Новинки-сериалы. */
    fun newTv(page: Int = 1): List<MediaItem> =
        pick({ RutrackerrCatalog.newItems(page) }, { KinopoiskClient.popularTv(page) }) ?: emptyList()

    /** Страна производства по тексту трекера («Россия, США»). */
    private fun isRussianCountry(c: String): Boolean =
        c.contains("Россия", ignoreCase = true) ||
            c.contains("СССР", ignoreCase = true) ||
            c.contains("Русск", ignoreCase = true)

    /** Русские фильмы: КП с фильтром страны (34), иначе — трекер с отбором по стране. */
    fun russianMovies(page: Int = 1): List<MediaItem> =
        pick(
            { KinopoiskClient.popularRussianMovies(page) },
            {
                // Трекер отдаёт всё популярное подряд — оставляем только
                // раздачи с российской/советской страной производства
                RutrackerrCatalog.popularMovies(page).filter { isRussianCountry(it.country) }
            },
        ) ?: emptyList()

    /** Русские сериалы: тот же фильтр страны. */
    fun russianTv(page: Int = 1): List<MediaItem> =
        pick(
            { KinopoiskClient.popularRussianTv(page) },
            {
                RutrackerrCatalog.popularTv(page).filter { isRussianCountry(it.country) }
            },
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
