package com.torrenttv.app.data

import org.json.JSONObject

/** Универсальная карточка контента (фильм или сериал). */
data class MediaItem(
    val id: Int,
    val type: String, // "movie" | "tv"
    val title: String,
    val year: String = "",
    val posterUrl: String = "",
    val overview: String = "",
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
        .put("overview", overview)

    companion object {
        fun fromJson(o: JSONObject): MediaItem = MediaItem(
            id = o.getInt("id"),
            type = o.optString("type", "movie"),
            title = o.optString("title", ""),
            year = o.optString("year", ""),
            posterUrl = o.optString("poster", ""),
            overview = o.optString("overview", ""),
        )
    }
}
