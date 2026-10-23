package com.torrenttv.app.data

import android.net.Uri
import com.torrenttv.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Клиент Kinopoisk API Unofficial (kinopoiskapiunofficial.tech) — неофициальный API
 * с данными Кинопоиска, доступен из РФ (в отличие от заблокированного TMDB).
 * Бесплатный ключ: регистрация на kinopoiskapiunofficial.tech (500 запросов/сутки).
 * Задаётся в gradle.properties: kpApiKey=...
 *
 * Каталоги — /api/v2.2/films/collections (TOP_250, популярное) и /api/v2.2/films
 * (жанры, тип), поиск — /api/v2.1/films/search-by-keyword, похожее — /similars,
 * жанры с числовыми id — /api/v2.2/films/filters.
 * Все методы блокирующие — вызывать из фонового потока.
 */
object KinopoiskClient {

    private const val BASE = "https://kinopoiskapiunofficial.tech"

    val isConfigured: Boolean get() = BuildConfig.KP_API_KEY.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Кэш жанров: имя → числовой id (эндпоинт filters). Жанры в выдачах идут
    // только именами, а фильтры и стоп-слова в приложении работают по числовым id.
    @Volatile private var genreIdByName: Map<String, Int>? = null

    // Кэш стран: имя → числовой id (тот же эндпоинт filters).
    @Volatile private var countryIdByName: Map<String, Int>? = null

    /** true, если последний запрос к API не удался (сеть/лимит/ошибка HTTP). */
    @Volatile var lastFailed: Boolean = false
        private set

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        if (!isConfigured) return null
        val url = buildString {
            append(BASE).append(path)
            if (params.isNotEmpty()) {
                append('?')
                append(params.entries.joinToString("&") { (k, v) ->
                    Uri.encode(k) + "=" + Uri.encode(v)
                })
            }
        }
        return try {
            http.newCall(
                Request.Builder()
                    .url(url)
                    .header("X-API-KEY", BuildConfig.KP_API_KEY)
                    .build()
            ).execute().use { resp ->
                lastFailed = !resp.isSuccessful
                if (!resp.isSuccessful) null else JSONObject(resp.body!!.string())
            }
        } catch (e: Exception) {
            lastFailed = true
            null
        }
    }

    private fun mapType(kpType: String): String =
        if (kpType == "FILM" || kpType == "VIDEO") "movie" else "tv"

    /** Элемент любой выдачи (collections/films/search) → MediaItem. */
    private fun parseItem(o: JSONObject): MediaItem? {
        val title = sequenceOf("nameRu", "nameOriginal", "nameEn")
            .map { o.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: return null

        val id = o.optInt("kinopoiskId", 0).takeIf { it > 0 }
            ?: o.optInt("filmId", 0)
        if (id <= 0) return null

        val yearRaw = o.optString("year", "").filter { it.isDigit() }

        val rating = o.optDouble("ratingKinopoisk", 0.0).takeIf { it > 0 }
            ?: o.optDouble("ratingImdb", 0.0).takeIf { it > 0 }
            ?: 0.0

        val genreNames = mutableListOf<String>()
        o.optJSONArray("genres")?.let { garr ->
            for (g in 0 until garr.length()) {
                genreNames += garr.getJSONObject(g).optString("genre", "")
            }
        }
        val ids = genreIdByName
        val genreIds = if (ids != null) genreNames.mapNotNull { ids[it] } else emptyList()

        val countryNames = mutableListOf<String>()
        o.optJSONArray("countries")?.let { carr ->
            for (c in 0 until carr.length()) {
                countryNames += carr.getJSONObject(c).optString("country", "")
            }
        }
        val cids = countryIdByName
        val countryIds = if (cids != null) countryNames.mapNotNull { cids[it] } else emptyList()

        return MediaItem(
            id = id,
            type = mapType(o.optString("type", "FILM")),
            title = title,
            year = yearRaw,
            posterUrl = o.optString("posterUrl", "")
                .ifBlank { o.optString("posterUrlPreview", "") },
            backdropUrl = o.optString("coverUrl", ""),
            rating = rating,
            genreIds = genreIds,
            countryIds = countryIds,
            overview = o.optString("description", ""),
            imdbId = o.optString("imdbId", ""),
            // оригинальное название — ключ к автопоиску раздач (apibay не знает русских имён)
            originalTitle = sequenceOf("nameOriginal", "nameEn")
                .map { o.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: "",
        )
    }

    private fun parseItems(json: JSONObject?, key: String): List<MediaItem> {
        if (json == null) return emptyList()
        val arr = json.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parseItem(arr.getJSONObject(it)) }
    }

    private fun collection(type: String, page: Int): List<MediaItem> =
        parseItems(
            get(
                "/api/v2.2/films/collections",
                mapOf("type" to type, "page" to page.toString()),
            ),
            "items",
        )

    // ---------- Поиск ----------

    fun search(query: String): List<MediaItem> = parseItems(
        get(
            "/api/v2.1/films/search-by-keyword",
            mapOf("keyword" to query, "page" to "1"),
        ),
        "films",
    )

    // ---------- Каталоги ----------

    /** Популярное сейчас: фильмы и сериалы вперемешку. */
    fun trending(page: Int = 1): List<MediaItem> = collection("TOP_POPULAR_ALL", page)

    fun popularMovies(page: Int = 1): List<MediaItem> = collection("TOP_POPULAR_MOVIES", page)

    fun popularTv(page: Int = 1): List<MediaItem> = byType(page, type = "TV_SERIES", order = "NUM_VOTE")

    fun topRatedMovies(page: Int = 1): List<MediaItem> = collection("TOP_250_MOVIES", page)

    fun topRatedTv(page: Int = 1): List<MediaItem> = collection("TOP_250_TV_SHOWS", page)

    fun moviesByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        byType(page, type = "FILM", order = "RATING", genreId = genreId)

    fun tvByGenre(genreId: Int, page: Int = 1): List<MediaItem> =
        byType(page, type = "TV_SERIES", order = "RATING", genreId = genreId)

    private fun byType(page: Int, type: String, order: String, genreId: Int? = null): List<MediaItem> {
        val params = mutableMapOf(
            "type" to type,
            "order" to order,
            "page" to page.toString(),
        )
        genreId?.let { params["genres"] = it.toString() }
        return parseItems(get("/api/v2.2/films", params), "items")
    }

    /** Похожее для карточки-углубления. */
    fun similar(item: MediaItem): List<MediaItem> =
        parseItems(get("/api/v2.2/films/${item.id}/similars"), "items")

    /** Сезоны сериала: /api/v2.2/serials/{id}/seasons. */
    fun seasons(item: MediaItem): List<Season> {
        val json = get("/api/v2.2/serials/${item.id}/seasons") ?: return emptyList()
        val arr = json.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val num = s.optInt("number", 0)
            if (num <= 0) return@mapNotNull null
            val eps = mutableListOf<Episode>()
            s.optJSONArray("episodes")?.let { earr ->
                for (j in 0 until earr.length()) {
                    val e = earr.optJSONObject(j) ?: continue
                    val en = e.optInt("episodeNumber", 0)
                    if (en <= 0) continue
                    eps += Episode(
                        season = num,
                        episode = en,
                        title = sequenceOf("nameRu", "nameEn")
                            .map { e.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: "",
                    )
                }
            }
            Season(number = num, episodes = eps.sortedBy { it.episode })
        }.sortedBy { it.number }
    }

    // ---------- Жанры и страны ----------

    fun movieGenres(): List<Genre> = filters().genres

    fun tvGenres(): List<Genre> = filters().genres

    fun countries(): List<Genre> = filters().countries

    private data class Filters(val genres: List<Genre>, val countries: List<Genre>)

    @Volatile private var cachedFilters: Filters? = null

    /** Жанры и страны одним запросом /api/v2.2/films/filters (+ кэши имя→id). */
    private fun filters(): Filters {
        cachedFilters?.let { return it }
        val json = get("/api/v2.2/films/filters")
            ?: return Filters(emptyList(), emptyList())

        fun readList(key: String, nameField: String): List<Genre> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optInt("id", -1)
                val name = o.optString(nameField, "")
                if (id >= 0 && name.isNotBlank()) Genre(id = id, name = name) else null
            }
        }

        val genres = readList("genres", "genre")
        val countries = readList("countries", "country")
        if (genres.isNotEmpty()) genreIdByName = genres.associate { it.name to it.id }
        if (countries.isNotEmpty()) countryIdByName = countries.associate { it.name to it.id }
        val f = Filters(genres, countries)
        cachedFilters = f
        return f
    }
}
