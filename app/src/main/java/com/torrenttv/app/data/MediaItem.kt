package com.torrenttv.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Универсальная карточка контента (фильм или сериал). */
data class MediaItem(
    val id: Int,
    val type: String, // "movie" | "tv"
    val title: String,
    val year: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: Double = 0.0,
    /** Рейтинг IMDb (догружается для карточки; 0 — неизвестен). */
    val ratingImdb: Double = 0.0,
    val genreIds: List<Int> = emptyList(),
    val countryIds: List<Int> = emptyList(),
    val overview: String = "",
    val imdbId: String = "",
    val originalTitle: String = "",
    /** Оригинальный язык (tmdb original_language): «ru» — русское производство. */
    val originalLanguage: String = "",
) {
    /** Ключ для локального хранения (прогресс/избранное). */
    val storeKey: String get() = "$type-$id"

    val typeLabel: String get() = if (type == "movie") "Фильм" else "Сериал"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("title", title)
        .put("year", year)
        .put("poster", posterUrl)
        .put("backdrop", backdropUrl)
        .put("rating", rating)
        .put("ratingImdb", ratingImdb)
        .put("genreIds", JSONArray(genreIds))
        .put("countryIds", JSONArray(countryIds))
        .put("overview", overview)
        .put("imdbId", imdbId)
        .put("originalTitle", originalTitle)
        .put("originalLanguage", originalLanguage)

    companion object {
        fun fromJson(o: JSONObject): MediaItem = MediaItem(
            id = o.getInt("id"),
            type = o.safeString("type", "movie"),
            title = o.safeString("title", ""),
            year = o.safeString("year", ""),
            posterUrl = o.safeString("poster", ""),
            backdropUrl = o.safeString("backdrop", ""),
            rating = o.optDouble("rating", 0.0),
            ratingImdb = o.optDouble("ratingImdb", 0.0),
            genreIds = o.optJSONArray("genreIds")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            } ?: emptyList(),
            countryIds = o.optJSONArray("countryIds")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            } ?: emptyList(),
            overview = o.safeString("overview"),
            imdbId = o.safeString("imdbId"),
            originalTitle = o.safeString("originalTitle"),
            originalLanguage = o.safeString("originalLanguage"),
        )
    }
}
