package com.torrenttv.app.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Провайдер bt4g (bt4gprx.com) — публичный JSON-поиск по DHT-качалке.
 * Резерв, когда apibay/torrentio недоступны из сети пользователя.
 * Ответ: {"results":[{"name","info_hash","magnet","size","seeders",...}]}.
 */
object Bt4gProvider {

    const val NAME = "bt4g"

    private val http = TorrentHttp.client

    fun search(query: String): List<TorrentResult> {
        val url = "https://bt4gprx.com/api/search?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=30"
        val body = TorrentHttp.get(url) ?: return emptyList()
        return parse(body)
    }

    fun parse(body: String): List<TorrentResult> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hash = o.optString("info_hash").lowercase()
            if (hash.length != 40) continue
            val name = o.optString("name", "")
            if (name.isBlank()) continue
            out += TorrentResult(
                name = name,
                infoHash = hash,
                seeds = o.optInt("seeders", 0),
                sizeBytes = TorrentioProvider.parseSizeBytes(o.optString("size", "")),
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
            )
        }
        return out
    }
}
