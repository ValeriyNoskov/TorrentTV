package com.torrenttv.app.data

import android.net.Uri
import com.torrenttv.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Клиент kinopoisk.dev — открытый API по данным Кинопоиска (доступен из РФ,
 * в отличие от заблокированного TMDB). Бесплатный токен: kinopoisk.dev → профиль → API.
 * Задаётся в gradle.properties: kpApiKey=...
 *
 * Каталоги — через /v1.4/movie (сортировка по рейтингу/голосам),
 * жанры — /v1/movie/possible-values-by-field, похожее — /v1.3/movie/{id}.
 * Все методы блокирующие — вызывать из фонового потока.
 */
object KinopoiskClient {

    private const val BASE = "https://api.kinopoisk.dev"

    val isConfigured: Boolean get() = BuildConfig.KP_API_KEY.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Кэш списков жанров (имя жанра → числовой id = индекс в списке)
    @Volatile private var movieGenreNames: List<String>? = null
    @Volatile private var tvGenreNames: List<String>? = null

    private fun get(path: String, params: List<Pair<String, String>> = emptyList()): JSONObject? {
        if (!isConfigured) return null
        val url = buildString {
            append(BASE).append(path)
            if (params.isNotEmpty()) {
                append('?')
                append(params.joinToString("&") { (k, v) ->
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
                if (!resp.isSuccessful) null else JSONObject(resp.body!!.string())
            }
        } catch (e: Exception) {
            null
        }
    }

    private val SELECT_FIELDS = listOf(
        "id", "name", "alternativeName", "year", "poster", "backdrop",
        "rating", "genres", "type", "description",
    )

    /** Документ v1.4 → MediaItem. Возвращает null для записей без названия/постера. */
    private fun parseMovie(o: JSONObject, isTv: Boolean? = null): MediaItem? {
        val title = o.optString("name", "").ifBlank { o.optString("alternativeName", "") }
        if (title.isBlank()) return null

        val kpType = o.optString("type")
        val type = when {
            isTv != null -> if (isTv) "tv" else "movie"
            kpType == "movie" -> "movie"
            kpType.isBlank() -> "movie"
            else -> "tv" // tv-series, cartoon-series, anime-series и т.п.
        }

        val yearVal = o.optInt("year", 0)
        val posterUrl = o.optJSONObject("poster")?.optString("url", "").orEmpty()
        val backdropUrl = o.optJSONObject("backdrop")?.optString("url", "").orEmpty()
        val rating = o.optJSONObject("rating")?.optDouble("imdb", 0.0)?.takeIf { it > 0 }
            ?: o.optJSONObject("rating")?.optDouble("kp", 0.0)?.takeIf { it > 0 }
            ?: 0.0

        val genreNames = mutableListOf<String>()
        o.optJSONArray("genres")?.let { garr ->
            for (g in 0 until garr.length()) {
                genreNames += garr.getJSONObject(g).optString("name")
            }
        }
        val names = if (type == "tv") tvGenreNames ?: emptyList() else movieGenreNames ?: emptyList()
        val genreIds = genreNames.mapNotNull { n -> names.indexOf(n).takeIf { it >= 0 } }

        return MediaItem(
            id = o.optInt("id", 0),
            type = type,
            title = title,
            year = if (yearVal > 0) yearVal.toString() else "",
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            rating = rating,
            genreIds = genreIds,
            overview = o.optString("description", ""),
        )
    }

    private fun parseDocs(json: JSONObject?, isTv: Boolean? = null): List<MediaItem> {
        if (json == null) return emptyList()
        val arr = json.optJSONArray("docs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> parseMovie(arr.getJSONObject(i), isTv) }
    }

    // ---------- Поиск ----------

    fun search(query: String): List<MediaItem> =
        parseDocs(
            get(
                "/v1.4/movie/search",
                listOf("query" to query, "page" to "1", "limit" to "30"),
            ),
        )

    // ---------- Каталоги ----------

    fun trending(page: Int = 1): List<MediaItem> {
        val y = java.time.Year.now().value
        return parseDocs(
            get(
                "/v1.4/movie",
                listOf(
                    "page" to page.toString(), "limit" to "20",
                    "notNullFields[]" to "name", "notNullFields[]" to "poster.url",
                    "year" to "${y - 1}-$y",
                    "sortField" to "rating.kp", "sortType" to "-1",
                ) + selectParams(),
            ),
        )
    }

    fun popularMovies(page: Int = 1): List<MediaItem> =
        parseDocs(catalog(page, typeNumber = 1, sort = "votes.kp"), isTv = false)

    fun popularTv(page: Int = 1): List<MediaItem> =
        parseDocs(catalog(page, typeNumber = 2, sort = "votes.kp"), isTv = true)

    fun topRatedMovies(page: Int = 1): List<MediaItem> =
        parseDocs(catalog(page, typeNumber = 1, sort = "rating.imdb"), isTv = false)

    fun topRatedTv(page: Int = 1): List<MediaItem> =
        parseDocs(catalog(page, typeNumber = 2, sort = "rating.imdb"), isTv = true)

    private fun catalog(page: Int, typeNumber: Int, sort: String): JSONObject? =
        get(
            "/v1.4/movie",
            listOf(
                "page" to page.toString(), "limit" to "20",
                "typeNumber" to typeNumber.toString(),
                "notNullFields[]" to "name", "notNullFields[]" to "poster.url",
                "sortField" to sort, "sortType" to "-1",
            ) + selectParams(),
        )

    private fun selectParams(): List<Pair<String, String>> =
        SELECT_FIELDS.map { "selectFields[]" to it }

    fun moviesByGenre(genreId: Int, page: Int = 1): List<MediaItem> {
        val name = movieGenres().getOrNull(genreId)?.name ?: return emptyList()
        return parseDocs(byGenre(page, name, typeNumber = 1), isTv = false)
    }

    fun tvByGenre(genreId: Int, page: Int = 1): List<MediaItem> {
        val name = tvGenres().getOrNull(genreId)?.name ?: return emptyList()
        return parseDocs(byGenre(page, name, typeNumber = 2), isTv = true)
    }

    private fun byGenre(page: Int, genreName: String, typeNumber: Int): JSONObject? =
        get(
            "/v1.4/movie",
            listOf(
                "page" to page.toString(), "limit" to "20",
                "typeNumber" to typeNumber.toString(),
                "genres.name" to genreName,
                "notNullFields[]" to "name", "notNullFields[]" to "poster.url",
                "sortField" to "votes.kp", "sortType" to "-1",
            ) + selectParams(),
        )

    /** Похожее для карточки-углубления (v1.3, поле similar). */
    fun similar(item: MediaItem): List<MediaItem> {
        val json = get(
            "/v1.3/movie/${item.id}",
            listOf("selectFields[]" to "similar"),
        ) ?: return emptyList()
        val arr = json.optJSONArray("similar") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val title = o.optString("name", "").ifBlank { o.optString("alternativeName", "") }
            if (title.isBlank()) return@mapNotNull null
            MediaItem(
                id = o.optInt("id", 0),
                type = if (o.optString("type") == "movie") "movie" else "tv",
                title = title,
                year = "",
                posterUrl = o.optJSONObject("poster")?.optString("url", "").orEmpty(),
                backdropUrl = "",
                rating = 0.0,
                genreIds = emptyList(),
                overview = "",
            )
        }
    }

    // ---------- Жанры ----------

    fun movieGenres(): List<Genre> {
        val loaded = loadGenreNames()
        if (loaded.isNotEmpty()) movieGenreNames = loaded
        val names = loaded.ifEmpty { movieGenreNames ?: emptyList() }
        return names.mapIndexed { i, n -> Genre(id = i, name = n) }
    }

    fun tvGenres(): List<Genre> {
        val loaded = loadGenreNames()
        if (loaded.isNotEmpty()) tvGenreNames = loaded
        val names = loaded.ifEmpty { tvGenreNames ?: emptyList() }
        return names.mapIndexed { i, n -> Genre(id = i, name = n) }
    }

    /** Список возможных значений жанров (строковые имена). */
    private fun loadGenreNames(): List<String> {
        val json = get("/v1/movie/possible-values-by-field", listOf("field" to "genres.name"))
            ?: return emptyList()
        val arr = json.optJSONArray("docs")
            ?: json.optJSONArray("values")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.get(i)
            (v as? String) ?: ((v as? JSONObject)?.optString("value", "")?.ifBlank { null })
        }
    }
}
