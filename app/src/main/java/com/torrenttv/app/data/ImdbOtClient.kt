package com.torrenttv.app.data

import org.json.JSONObject

/**
 * IMDbOT (imdb.iamidiotareyoutoo.com) — бесплатный поиск по IMDb без ключей
 * и лимитов. Формат — прокси к IMDb suggest:
 * {"ok":true,"d":[{"id":"tt0111161","l":"Название","y":1994,
 *                  "i":{"imageUrl":"https://m.media-amazon.com/…"},"s":"актёры"}]}
 *
 * Сервис на бесплатном хостинге и периодически лежит (SERVER_FAILURE) —
 * поэтому это последний фолбэк: упавший отвечает без постера, не ломая
 * остальные источники. Даёт главное — точный IMDb ID и постер.
 */
object ImdbOtClient {

    private const val BASE = "https://imdb.iamidiotareyoutoo.com/search"

    /** До этого времени не ходим в сеть: сервис считаем лежащим. */
    @Volatile private var cooldownUntil: Long = 0

    /** true, если последний запрос не удался — для диагностики в логах. */
    @Volatile var lastFailed: Boolean = false
        private set

    /** Поиск по названию. Возвращает карточки с imdbId/постером/годом. */
    fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        if (System.currentTimeMillis() < cooldownUntil) {
            lastFailed = true
            return emptyList()
        }
        val body = TorrentHttp.getFast(
            "$BASE?query=" + java.net.URLEncoder.encode(query, "UTF-8"),
        )
        if (body == null) {
            fail()
            return emptyList()
        }
        return runCatching { parse(body) }.getOrElse {
            fail()
            emptyList()
        }
    }

    /** Разбор в tolerant-формате IMDb suggest (поля d/l/y/i.id). */
    private fun parse(body: String): List<MediaItem> {
        val root = JSONObject(body)
        if (!root.optBoolean("ok", false)) {
            fail()
            return emptyList()
        }
        val arr = root.optJSONArray("d") ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val title = o.optString("l", "")
            if (!id.startsWith("tt") || title.isBlank()) continue
            val poster = o.optJSONObject("i")?.optString("imageUrl", "").orEmpty()
            out += MediaItem(
                id = kotlin.math.abs(("imdb-" + id).hashCode()),
                type = if (o.optString("qid", "").contains("tv", true) ||
                    o.optString("q", "").contains("series", true)
                ) "tv" else "movie",
                title = title,
                year = o.optInt("y", 0).takeIf { it > 0 }?.toString().orEmpty(),
                posterUrl = poster,
                imdbId = id,
            )
        }
        return out
    }

    private fun fail() {
        lastFailed = true
        cooldownUntil = System.currentTimeMillis() + 30 * 60_000L // 30 минут
    }
}
