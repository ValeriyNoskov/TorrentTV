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
    val genreIds: List<Int> = emptyList(),
    val countryIds: List<Int> = emptyList(),
    val overview: String = "",
    val imdbId: String = "",
    val originalTitle: String = "",
    /** Страна производства текстом («США, Великобритания») — Рутрекер-каталог. */
    val country: String = "",
    /** Рейтинг IMDb отдельно (двойной бейдж КП+IMDb на карточке). */
    val ratingImdb: Double = 0.0,
    /** Slug'и жанров Рутрекера (/boeviki/, /serialy/…) для фильтров каталога. */
    val rtGenres: List<String> = emptyList(),
    /** Оригинальный язык (tmdb original_language): «ru» — русское производство. */
    val originalLanguage: String = "",
) {
    /** Ключ для локального хранения (прогресс/избранное). */
    val storeKey: String get() = "$type-$id"

    val typeLabel: String get() = if (type == "movie") "Фильм" else "Сериал"
/**
     * Нормализация описания: источники (Rutube/трекеры) отдают текст
     * построчно — «Год\n2026\nСтрана\nРоссия», и в карточке он выглядит
     * столбиком без знаков препинания. Здесь пары «метка / значение»
     * склеиваются в строку «Год: 2026 · Страна: Россия · …», а остальной
     * текст собирается в сплошной абзац.
     */
    fun normalizedOverview(): String {
        val lines = overview.replace("\r", "").split('\n')
            .map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        val rest = lines.toMutableList()
        // Повтор названия капсом в начале описания убираем
        if (rest.size > 1 &&
            rest.first().replace(" ", "").equals(title.replace(" ", ""), ignoreCase = true)
        ) rest.removeAt(0)
        val labels = setOf(
            "год", "год выпуска", "год производства", "страна", "страны",
            "премьера", "премьера в мире", "жанр", "жанры", "режиссёр", "режиссер",
            "в ролях", "продюсер", "сценарий", "бюджет", "время", "продолжительность",
            "возраст", "рейтинг",
        )
        val pairs = mutableListOf<String>()
        val prose = mutableListOf<String>()
        var i = 0
        while (i < rest.size) {
            val l = rest[i]
            val noColon = l.trimEnd(':', ' ')
            when {
                noColon.lowercase() in labels && l.contains(':').not() -> {
                    // Метка без значения в этой строке — значение на следующей
                    if (i + 1 < rest.size) { pairs += "$noColon: ${rest[i + 1]}"; i += 2 }
                    else { prose += l; i++ }
                }
                l.contains(": ") -> { pairs += l; i++ }
                else -> { prose += l; i++ }
            }
        }
        val head = pairs.joinToString("  ·  ")
        val body = prose.joinToString(" ")
        return listOf(head, body).filter { it.isNotBlank() }.joinToString("\n\n")
    }


    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("title", title)
        .put("year", year)
        .put("poster", posterUrl)
        .put("backdrop", backdropUrl)
        .put("rating", rating)
        .put("genreIds", JSONArray(genreIds))
        .put("countryIds", JSONArray(countryIds))
        .put("overview", overview)
        .put("imdbId", imdbId)
        .put("originalTitle", originalTitle)
        .put("country", country)
        .put("rtGenres", JSONArray(rtGenres))
        .put("originalLanguage", originalLanguage)
        .put("ratingImdb", ratingImdb)

    companion object {
        fun fromJson(o: JSONObject): MediaItem = MediaItem(
            // id — ключ хранилища: жёсткий getInt() ронял весь список из-за
            // одного битого элемента (ревью данных)
            id = runCatching { o.getInt("id") }.getOrElse { o.optInt("id", 0) },
            type = o.safeString("type", "movie"),
            title = o.safeString("title", ""),
            year = o.safeString("year", ""),
            posterUrl = o.safeString("poster", ""),
            backdropUrl = o.safeString("backdrop", ""),
            // optDouble при JSON-null возвращает NaN — чистим (ломал фильтры/сортировки)
            rating = o.optDouble("rating", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            genreIds = o.optJSONArray("genreIds")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            } ?: emptyList(),
            countryIds = o.optJSONArray("countryIds")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            } ?: emptyList(),
            overview = o.safeString("overview"),
            imdbId = o.safeString("imdbId"),
            originalTitle = o.safeString("originalTitle"),
            country = o.safeString("country"),
            rtGenres = o.optJSONArray("rtGenres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            } ?: emptyList(),
            originalLanguage = o.safeString("originalLanguage"),
            ratingImdb = o.optDouble("ratingImdb", 0.0).takeIf { !it.isNaN() } ?: 0.0,
        )
    }
}
