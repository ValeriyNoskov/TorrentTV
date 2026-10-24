package com.torrenttv.app.data

import org.json.JSONObject

/**
 * Трейлеры на Rutube: публичное API поиска + play/options с HLS-балансером.
 * Трейлер играет встроенный плеер (OnlinePlayerScreen), внешние приложения
 * и браузер не нужны — на ТВ-приставке их часто нет.
 */
object RutubeService {

    data class Trailer(val id: String, val title: String, val hls: String)

    /**
     * Найти трейлер по запросу (обычно «Название трейлер»).
     * Приоритет — видео со словом «трейлер» в названии; HLS берём у первого
     * кандидата, у которого балансер доступен (не у всех роликов он есть).
     */
    fun searchTrailer(query: String): Trailer? {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val searchJson = runCatching {
            TorrentHttp.get("https://rutube.ru/api/search/video/?query=$q&page=1")
        }.getOrNull() ?: return null
        val results = runCatching {
            JSONObject(searchJson).optJSONArray("results")
        }.getOrNull() ?: return null

        data class Cand(val id: String, val title: String, val score: Int)
        val candidates = buildList {
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val id = r.optString("id", "")
                val title = r.optString("title", "")
                if (id.isBlank()) continue
                val t = title.lowercase()
                // Релевантность: трейлер в названии, не музыка/обзор
                var score = 0
                if ("трейлер" in t || "trailer" in t) score += 2
                if ("тизер" in t || "teaser" in t) score += 1
                add(Cand(id, title, score))
            }
        }.sortedByDescending { it.score }.take(6)

        for (c in candidates) {
            val opts = runCatching {
                TorrentHttp.get(
                    "https://rutube.ru/api/play/options/${c.id}/?format=json",
                    mapOf("Referer" to "https://rutube.ru/"),
                )
            }.getOrNull() ?: continue
            val hls = runCatching {
                JSONObject(opts)
                    .optJSONObject("video_balancer")
                    ?.optString("m3u8", "")
            }.getOrNull().orEmpty()
            if (hls.isNotBlank()) return Trailer(id = c.id, title = c.title, hls = hls)
        }
        return null
    }
}
