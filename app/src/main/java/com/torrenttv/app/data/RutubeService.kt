package com.torrenttv.app.data

import org.json.JSONObject

/**
 * Трейлеры на Rutube: публичное API поиска + play/options с HLS-балансером.
 * Трейлер играет встроенный плеер (OnlinePlayerScreen), внешние приложения
 * и браузер не нужны — на ТВ-приставке их часто нет.
 */
object RutubeService {

    data class Trailer(val id: String, val title: String, val hls: String)

    /** Видео из поиска Rutube: полноценный фильм/серия, не трейлер и не нарезка. */
    data class RutubeVideo(val id: String, val title: String, val thumb: String, val durationSec: Int)

    // Названия-обманки: обзорщики, реакции, нарезки и т.п. — выкидываем
    private val JUNK_WORDS = listOf(
        "обзор", "реакция", "реакци", "трейлер", "trailer", "тизер", "teaser",
        "анонс", "клип", "нарезк", "фрагмент", "подкаст", "podcast", "shorts",
        "шортс", "стрим", "концовка", "объяснение", "спойлер", "разбор",
        "момент", "топ ", "мем", "прикол", "угар", "смешные", "лучш",
        "все серии за", "рецензия", "афиш", "премьера фильма",
    )

    /** Минимальная длина «полноценного» видео: 20 минут. */
    private const val MIN_DURATION_SEC = 1200

    /**
     * Найти на Rutube ПОЛНЫЕ фильмы/сериалы по запросу.
     * Откидываем обманные ссылки: обзоры, реакции, трейлеры, нарезки,
     * подкасты — по ключевым словам в названии и по длительности
     * (короткие ролики не могут быть фильмом).
     */
    fun searchContent(query: String): List<RutubeVideo> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val videos = buildList {
            // Обычный поиск + поиск по категории «Фильмы» — два запроса
            for (suffix in listOf("", "&category=movie")) {
                val json = runCatching {
                    TorrentHttp.get("https://rutube.ru/api/search/video/?query=$q&page=1$suffix")
                }.getOrNull() ?: continue
                val results = runCatching {
                    JSONObject(json).optJSONArray("results")
                }.getOrNull() ?: continue
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val id = r.safeString("id")
                    val title = r.safeString("title").trim()
                    val dur = r.optInt("duration", 0)
                    val thumb = r.safeString("thumbnail_url")
                    if (id.isBlank() || !title.isRealText()) continue
                    add(RutubeVideo(id, title, thumb, dur))
                }
            }
        }
        return videos
            .filter { v ->
                // Длительность: фильм/серия не может быть короче 20 минут
                v.durationSec >= MIN_DURATION_SEC &&
                    // Название без мусорных слов
                    JUNK_WORDS.none { v.title.lowercase().contains(it) }
            }
            // Дубликаты: одно видео отдаётся в обоих запросах
            .distinctBy { it.id }
            // Приоритет: запрос в начале названия
            .sortedBy { v ->
                val t = v.title.lowercase()
                val ql = query.trim().lowercase()
                (if (t.startsWith(ql)) 0 else if (ql in t) 1 else 2) * 10_000 + t.length
            }
            .take(24)
    }

    /** HLS-ссылка для видео по id (play/options с Referer). */
    fun videoHls(videoId: String): String? {
        val opts = runCatching {
            TorrentHttp.get(
                "https://rutube.ru/api/play/options/$videoId/?format=json",
                mapOf("Referer" to "https://rutube.ru/"),
            )
        }.getOrNull() ?: return null
        return runCatching {
            JSONObject(opts)
                .optJSONObject("video_balancer")
                ?.optString("m3u8", "")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

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
