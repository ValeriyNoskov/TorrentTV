package com.torrenttv.app.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Провайдер YTS (yts.mx) — качественные фильмы в 720p/1080p/2160p.
 * Резерв, когда apibay/torrentio недоступны из сети пользователя.
 * Ответ: {"data":{"movies":[{..., "torrents":[{hash, quality, size, seeds}]}]}}.
 */
object YtsProvider {

    const val NAME = "yts"

    private val http = TorrentHttp.client

    fun search(query: String): List<TorrentResult> {
        val url = "https://yts.mx/api/v2/list_movies.json?query_term=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=20"
        val body = TorrentHttp.get(url) ?: return emptyList()
        return parse(body)
    }

    fun parse(body: String): List<TorrentResult> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val movies = json.optJSONObject("data")?.optJSONArray("movies") ?: return emptyList()
        val out = mutableListOf<TorrentResult>()
        for (i in 0 until movies.length()) {
            val m = movies.optJSONObject(i) ?: continue
            val title = m.optString("title_long", m.optString("title", ""))
            val torrents = m.optJSONArray("torrents") ?: continue
            for (j in 0 until torrents.length()) {
                val t = torrents.optJSONObject(j) ?: continue
                val hash = t.optString("hash").lowercase()
                if (hash.length != 40) continue
                val quality = t.optString("quality", "")
                out += TorrentResult(
                    name = listOf(title, quality).filter { it.isNotBlank() }.joinToString(" · "),
                    infoHash = hash,
                    seeds = t.optInt("seeds", 0),
                    sizeBytes = TorrentioProvider.parseSizeBytes(t.optString("size", "")),
                    provider = NAME,
                    resolution = quality.ifBlank { ReleaseQuality.parse(title) },
                )
            }
        }
        return out
    }
}
