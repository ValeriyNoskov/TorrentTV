package com.torrenttv.app.data

import java.net.URLEncoder

/**
 * Провайдер поиска раздач: apibay.org — публичное JSON-зеркало ThePirateBay
 * (категории: 201 — фильмы, 205 — сериалы). Из info_hash собирается
 * magnet-ссылка с набором публичных трекеров.
 */
object ApibayProvider {

    const val NAME = "apibay"

    private val http = TorrentHttp.client

    /** Найти раздачи, отсортированные по количеству сидов. */
    fun search(query: String, isTvShow: Boolean): List<TorrentResult> {
        val cat = if (isTvShow) "205" else "201"
        val url = "https://apibay.org/q.php?q=${URLEncoder.encode(query, "UTF-8")}&cat=$cat"
        val body = TorrentHttp.get(url) ?: return emptyList()

        val arr = try {
            org.json.JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }

        val out = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.safeString("info_hash")
            if (hash.length != 40) continue
            val name = o.safeString("name")
            if (name.isBlank()) continue
            out += TorrentResult(
                name = name,
                infoHash = hash.lowercase(),
                seeds = o.optString("seeders").toIntOrNull() ?: 0,
                sizeBytes = o.optString("size").toLongOrNull() ?: 0L,
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
            )
        }
        return out
    }

    /** Собрать ссылку-источник из раздачи: прямая .torrent (Рутрекер) —
     *  метаданные мгновенно; иначе magnet из info_hash. */
    fun toMagnet(r: TorrentResult): String {
        if (r.dlUrl.isNotBlank()) return r.dlUrl
        val dn = URLEncoder.encode(r.name, "UTF-8")
        val tr = TorrentSearch.TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:${r.infoHash}&dn=$dn$tr"
    }

    /** Служебное: извлечь info_hash из magnet-ссылки. */
    fun hashFromMagnet(magnet: String): String? =
        Regex("urn:btih:([0-9a-fA-F]{40})").find(magnet)?.groupValues?.get(1)?.lowercase()
}
