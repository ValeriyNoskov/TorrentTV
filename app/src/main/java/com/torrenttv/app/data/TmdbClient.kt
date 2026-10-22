package com.torrenttv.app.data

import android.net.Uri
import com.torrenttv.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Клиент TMDB (The Movie Database) — источник каталогов фильмов и сериалов
 * и метаданных для поиска. API-ключ задаётся в gradle.properties: tmdbApiKey=...
 */
object TmdbClient {

    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGE = "https://image.tmdb.org/t/p/w500"

    private val apiKey: String get() = BuildConfig.TMDB_API_KEY

    /** Есть ли настроенный ключ — чтобы показать подсказку в UI. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        if (!isConfigured) return null
        val url = buildString {
            append(BASE).append(path)
            append(if (path.contains('?')) '&' else '?')
            append("api_key=").append(apiKey)
            append("&language=ru-RU")
            params.forEach { (k, v) -> append('&').append(k).append('=').append(Uri.encode(v)) }
        }
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) null else JSONObject(resp.body!!.string())
            }
        } catch (e: Exception) {
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
            val title = o.optString("title", "").ifBlank { o.optString("name") }
            if (title.isBlank()) return@mapNotNull null
            val date = o.optString("release_date", "").ifBlank { o.optString("first_air_date", "") }
            MediaItem(
                id = o.getInt("id"),
                type = type,
                title = title,
                year = date.take(4),
                posterUrl = o.optString("poster_path", "").removePrefix("/")
                    .let { if (it.isBlank()) "" else "$IMAGE/$it" },
                overview = o.optString("overview", ""),
            )
        }
    }

    /** Поиск по названию (фильмы + сериалы). */
    fun search(query: String): List<MediaItem> =
        parseList(get("/search/multi", mapOf("query" to query)))

    /** Каталог популярных фильмов. */
    fun popularMovies(): List<MediaItem> = parseList(get("/movie/popular"))

    /** Каталог популярных сериалов. */
    fun popularTv(): List<MediaItem> = parseList(get("/tv/popular"))
}
