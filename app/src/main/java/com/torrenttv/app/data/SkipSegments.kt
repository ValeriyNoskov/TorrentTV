package com.torrenttv.app.data

import org.json.JSONObject

/** Один размеченный отрезок серии/фильма (в мс). */
data class SkipSeg(
    val kind: String, // intro (заставка) | recap (пересказ) | outro (титры) | preview (анонс)
    val startMs: Long,
    val endMs: Long,
    val confidence: Double,
)

/**
 * Разметка пропускаемых отрезков из открытых баз сообщества:
 *  - IntroDB (api.introdb.app) — intro/recap/outro/post-credits, без ключа;
 *  - SkipDB (skipdb.tv) — intro/recap/outro/preview, открытые данные ODbL.
 * Запрос по IMDb ID (+ сезон/эпизод для сериалов), ответ кешируется локально.
 * Это точные таймкоды сообщества (как у Netflix), а не догадки плеера.
 */
object SkipSegments {

    private const val INTRODB = "https://api.introdb.app/segments"
    private const val SKIPDB = "https://skipdb.tv/api/segments"

    /**
     * Загрузить разметку (с кешем). Для сериала нужны сезон/эпизод,
     * для фильма episode = null (в базах ищется как is_movie=true).
     */
    fun fetch(store: LocalStore, imdbId: String, season: Int, episode: Int?): List<SkipSeg> {
        val id = imdbId.trim()
        if (id.isBlank()) return emptyList()
        val key = cacheKey(id, season, episode)
        store.skipCacheGet(key)?.let { return it }

        val found = mutableListOf<SkipSeg>()

        // --- IntroDB ---
        runCatching {
            val url = buildString {
                append(INTRODB).append("?imdb_id=").append(id)
                if (episode != null) append("&season=").append(season).append("&episode=").append(episode)
                else append("&is_movie=true")
            }
            val body = TorrentHttp.getFast(url) ?: return@runCatching
            val o = JSONObject(body)
            for (kind in listOf("intro", "recap", "outro")) {
                // no_intro=true — сообщество подтвердило: сегмента нет, не ищем дальше
                if (o.optBoolean("no_$kind", false)) continue
                val s = o.optJSONObject(kind) ?: continue
                val st = s.optLong("start_ms", 0L)
                val en = s.optLong("end_ms", 0L)
                if (st > 0 && en > st) {
                    found += SkipSeg(kind, st, en, s.optDouble("confidence", 0.5))
                }
            }
        }

        // --- SkipDB (единственный источник recap/preview) ---
        if (episode != null) runCatching {
            val url = "$SKIPDB?imdb_id=$id&season=$season&episode=$episode"
            val body = TorrentHttp.getFast(url) ?: return@runCatching
            val segs = JSONObject(body).optJSONObject("segments") ?: return@runCatching
            for (kind in listOf("intro", "recap", "outro", "preview")) {
                val s = segs.optJSONObject(kind) ?: continue
                val st = s.optLong("start_ms", 0L)
                val en = s.optLong("end_ms", 0L)
                if (st > 0 && en > st) {
                    found += SkipSeg(kind, st, en, s.optDouble("confidence", 0.5))
                }
            }
        }

        // Объединение: по каждому виду — сегмент с максимальной уверенностью
        val best = found.groupBy { it.kind }.map { (_, list) ->
            list.maxByOrNull { it.confidence } ?: list.first()
        }.filter { it.endMs - it.startMs >= 5_000L } // короче 5 сек — шум

        store.skipCachePut(key, best)
        return best
    }

    private fun cacheKey(imdbId: String, season: Int, episode: Int?): String =
        if (episode != null) "${imdbId}_s${season}e$episode" else "${imdbId}_movie"

}
