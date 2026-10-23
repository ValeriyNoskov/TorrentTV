package com.torrenttv.app.data

import org.json.JSONObject

/**
 * Провайдер Torrentio (torrentio.strem.fun) — агрегатор раздач по IMDb-id.
 * Работает только для фильмов (сериалам нужен конкретный сезон/эпизод).
 * Не требует ключей; ответ — JSON со списком потоков-раздач.
 */
object TorrentioProvider {

    const val NAME = "torrentio"

    private val http = TorrentHttp.client

    /** Нормализованный imdb id с префиксом tt, или пустая строка. */
    fun normalizeImdb(imdbId: String): String {
        val s = imdbId.trim()
        if (s.isBlank()) return ""
        return if (s.startsWith("tt", ignoreCase = true)) s.lowercase() else "tt$s"
    }

    /** Найти раздачи фильма по imdb id (без префикса тоже сработает). */
    fun search(imdbId: String): List<TorrentResult> {
        val id = normalizeImdb(imdbId)
        if (id.isBlank()) return emptyList()
        val url = "https://torrentio.strem.fun/stream/movie/$id.json"
        val body = TorrentHttp.get(url) ?: return emptyList()

        val streams = try {
            JSONObject(body).optJSONArray("streams") ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        val out = mutableListOf<TorrentResult>()
        for (i in 0 until streams.length()) {
            val o = streams.optJSONObject(i) ?: continue
            val hash = o.optString("infoHash").lowercase()
            if (hash.length != 40) continue
            // title: «📁 4.8GB 👤 105 …», name: «Torrentio\n [Multi|1080p] …»
            val title = o.optString("title", "")
            val name = o.optString("name", "").removePrefix("Torrentio\n").trim()
                .ifBlank { title }
            out += TorrentResult(
                name = buildString {
                    append(title)
                    if (name.isNotBlank()) append(" · ").append(name)
                }.trim(),
                infoHash = hash,
                seeds = Regex("👤\\s*([0-9]+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                sizeBytes = parseSizeBytes(title),
                provider = NAME,
                resolution = ReleaseQuality.parse("$name $title"),
            )
        }
        return out
    }

    /** «14.2GB» / «860MB» → байты; иначе 0. */
    fun parseSizeBytes(title: String): Long {
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB|KB)", RegexOption.IGNORE_CASE).find(title) ?: return 0L
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val k = m.groupValues[2].uppercase()
        return when (k) {
            "GB" -> (v * 1024 * 1024 * 1024).toLong()
            "MB" -> (v * 1024 * 1024).toLong()
            "KB" -> (v * 1024).toLong()
            else -> 0L
        }
    }
}
