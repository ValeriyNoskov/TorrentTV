package com.torrenttv.app.data

import android.net.Uri
import com.torrenttv.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Жанр TMDB. */
data class Genre(val id: Int, val name: String)

/**
 * Клиент TMDB (The Movie Database) — источник каталогов фильмов и сериалов
 * и метаданных для поиска. API-ключ задаётся в gradle.properties: tmdbApiKey=...
 * Все методы блокирующие — вызывать из фонового потока.
 */
object TmdbClient {

    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGE = "https://image.tmdb.org/t/p/w500"
    private const val IMAGE_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    /** Ключ, введённый пользователем в настройках; перекрывает вшитый. */
    @Volatile var overrideApiKey: String = ""
        set(value) {
            field = value.trim()
            cooldownUntil = 0
        }

    private val apiKey: String
        get() = overrideApiKey.ifBlank { BuildConfig.TMDB_API_KEY }

    /** Есть ли настроенный ключ — чтобы показать подсказку в UI. */
    val isConfigured: Boolean
        get() = overrideApiKey.isNotBlank() || BuildConfig.TMDB_API_KEY.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** true, если последний запрос к API не удался (сеть/ошибка HTTP). */
    @Volatile var lastFailed: Boolean = false
        private set

    /** До этого времени (мс) не ходим в сеть: источник считаем лежащим. */
    @Volatile private var cooldownUntil: Long = 0

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        if (!isConfigured) return null
        // Предохранитель: после сбоя пауза — не долбим источник впустую
        if (System.currentTimeMillis() < cooldownUntil) {
            lastFailed = true
            return null
        }
        val url = buildString {
            append(BASE).append(path)
            append(if (path.contains('?')) '&' else '?')
            append("api_key=").append(apiKey)
            append("&language=ru-RU")
            params.forEach { (k, v) -> append('&').append(k).append('=').append(Uri.encode(v)) }
        }
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                lastFailed = !resp.isSuccessful
                if (resp.code == 429 || resp.code == 401 || resp.code >= 500) {
                    cooldownUntil = System.currentTimeMillis() + 10 * 60_000L
                }
                if (!resp.isSuccessful) null else JSONObject(resp.body!!.string())
            }
        } catch (e: Exception) {
            lastFailed = true
            null
        }
    }

    private fun parseList(json: JSONObject?): List<MediaItem> {
        if (json == null) return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val type = when {
                o.has("title") -> "movie"
                o.has("name") -> "tv"
                else -> o.optString("media_type")
            }
            if (type != "movie" && type != "tv") return@mapNotNull null
            val title = o.safeString("title").ifBlank { o.safeString("name") }
            if (!title.isRealText()) return@mapNotNull null
            val date = o.optString("release_date", "").ifBlank { o.optString("first_air_date", "") }
            val genreIds = o.optJSONArray("genre_ids")?.let { garr ->
                (0 until garr.length()).map { garr.optInt(it) }
            } ?: emptyList()
            MediaItem(
                id = o.getInt("id"),
                type = type,
                title = title,
                year = date.take(4),
                posterUrl = o.optString("poster_path", "").removePrefix("/")
                    .let { if (it.isBlank()) "" else "$IMAGE/$it" },
                backdropUrl = o.optString("backdrop_path", "").removePrefix("/")
                    .let { if (it.isBlank()) "" else "$IMAGE_BACKDROP/$it" },
                rating = o.optDouble("vote_average", 0.0),
                genreIds = genreIds,
                overview = o.optString("overview", ""),
                imdbId = o.optString("imdb_id", ""),
                originalTitle = o.safeString("original_title")
                    .ifBlank { o.safeString("original_name") },
                originalLanguage = o.safeString("original_language"),
            )
        }
    }

    // ---------- Поиск ----------

    /** Поиск по названию (фильмы + сериалы). */
    fun search(query: String): List<MediaItem> =
        parseList(get("/search/multi", mapOf("query" to query)))

    // ---------- Каталоги (с постраничной подгрузкой) ----------

    fun trending(page: Int = 1): List<MediaItem> =
        parseList(get("/trending/all/day", mapOf("page" to page.toString())))

    /** Новинки: фильмы, которые ещё выходят в прокат. */
    fun upcoming(): List<MediaItem> =
        parseList(get("/movie/upcoming", mapOf("page" to "1")))

    fun popularMovies(page: Int = 1): List<MediaItem> =
        parseList(get("/movie/popular", mapOf("page" to page.toString())))

    fun popularTv(page: Int = 1): List<MediaItem> =
        parseList(get("/tv/popular", mapOf("page" to page.toString())))

    /** Шоу: реалити + ток-шоу (TMDB типов «шоу» отдельно нет — discover по жанрам). */
    fun popularShows(page: Int = 1): List<MediaItem> =
        parseList(
            get(
                "/discover/tv",
                mapOf("with_genres" to "10764,10767", "sort_by" to "popularity.desc", "page" to page.toString()),
            ),
        )

    fun topRatedMovies(page: Int = 1): List<MediaItem> =
        parseList(get("/movie/top_rated", mapOf("page" to page.toString())))

    fun topRatedTv(page: Int = 1): List<MediaItem> =
        parseList(get("/tv/top_rated", mapOf("page" to page.toString())))

    fun moviesByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        parseList(
            get(
                "/discover/movie",
                mapOf(
                    "with_genres" to genreId.toString(),
                    "sort_by" to "popularity.desc",
                    "page" to page.toString(),
                ),
            ),
        )

    fun tvByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        parseList(
            get(
                "/discover/tv",
                mapOf(
                    "with_genres" to genreId.toString(),
                    "sort_by" to "popularity.desc",
                    "page" to page.toString(),
                ),
            ),
        )

    // ---------- Полки каталога: новинки / русские / зарубежные ----------

    /** Срок «новинки»: вышло не раньше, чем 1 год назад, и не в будущем. */
    private val freshFrom: String
        get() = java.time.LocalDate.now().minusYears(1).toString()
    private val freshTo: String
        get() = java.time.LocalDate.now().toString()

    /** Новинки-фильмы: вышли за последний год, есть голоса (не мёртвые премьеры). */
    fun newMovies(page: Int = 1): List<MediaItem> =
        parseList(
            get(
                "/discover/movie",
                mapOf(
                    "sort_by" to "primary_release_date.desc",
                    "primary_release_date.gte" to freshFrom,
                    "primary_release_date.lte" to freshTo,
                    "vote_count.gte" to "10",
                    "page" to page.toString(),
                ),
            ),
        )

    /** Новинки-сериалы. */
    fun newTv(page: Int = 1): List<MediaItem> =
        parseList(
            get(
                "/discover/tv",
                mapOf(
                    "sort_by" to "first_air_date.desc",
                    "first_air_date.gte" to freshFrom,
                    "first_air_date.lte" to freshTo,
                    "vote_count.gte" to "10",
                    "page" to page.toString(),
                ),
            ),
        )

    /** Русские фильмы (оригинальный язык — русский). */
    fun russianMovies(page: Int = 1): List<MediaItem> =
        parseList(
            get(
                "/discover/movie",
                mapOf("with_original_language" to "ru", "sort_by" to "popularity.desc", "page" to page.toString()),
            ),
        )

    /** Русские сериалы. */
    fun russianTv(page: Int = 1): List<MediaItem> =
        parseList(
            get(
                "/discover/tv",
                mapOf("with_original_language" to "ru", "sort_by" to "popularity.desc", "page" to page.toString()),
            ),
        )

    /** Зарубежные фильмы: популярные, но не русские (фильтр по языку на клиенте). */
    fun foreignMovies(page: Int = 1): List<MediaItem> =
        popularMovies(page).filter { it.originalLanguage != "ru" }

    /** Зарубежные сериалы. */
    fun foreignTv(page: Int = 1): List<MediaItem> =
        popularTv(page).filter { it.originalLanguage != "ru" }

    /** Похожее для карточки-углубления. */
    fun similar(item: MediaItem): List<MediaItem> =
        parseList(get("/" + item.type + "/" + item.id + "/similar", mapOf("page" to "1")))

    /** Сезоны и эпизоды сериала (+ фолбэк: поиск по названию, если id чужой). */
    fun seasons(item: MediaItem): List<Season> {
        val direct = seasonsById(item.id)
        if (direct.isNotEmpty()) return direct
        // Карточка может быть не из TMDB (торрент/КП/Рутрекер) — id не совпадает.
        // Ищем сериал по названию и берём сезоны у найденного.
        if (item.type != "tv") return emptyList()
        val found = search(item.title).firstOrNull {
            it.type == "tv" && it.title.equals(item.title, ignoreCase = true)
        } ?: return emptyList()
        return seasonsById(found.id)
    }

    private fun seasonsById(tvId: Int): List<Season> {
        val json = get("/tv/$tvId") ?: return emptyList()
        val arr = json.optJSONArray("seasons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val num = s.optInt("season_number", 0)
            if (num <= 0) return@mapNotNull null
            val eps = mutableListOf<Episode>()
            s.optJSONArray("episodes")?.let { earr ->
                for (j in 0 until earr.length()) {
                    val e = earr.optJSONObject(j) ?: continue
                    val en = e.optInt("episode_number", 0)
                    if (en <= 0) continue
                    eps += Episode(
                        season = num,
                        episode = en,
                        title = e.optString("name", "").removePrefix("Эпизод "),
                    )
                }
            }
            Season(number = num, episodes = eps.sortedBy { it.episode })
        }.sortedBy { it.number }
    }

    // ---------- Жанры ----------

    fun movieGenres(): List<Genre> = genres("/genre/movie/list")

    fun tvGenres(): List<Genre> = genres("/genre/tv/list")

    private fun genres(path: String): List<Genre> {
        val json = get(path) ?: return emptyList()
        val arr = json.optJSONArray("genres") ?: return emptyList()
        return (0 until arr.length()).map {
            val g = arr.getJSONObject(it)
            Genre(id = g.getInt("id"), name = g.getString("name"))
        }
    }
}
