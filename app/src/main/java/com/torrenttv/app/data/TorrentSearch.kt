package com.torrenttv.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/** Результат автопоиска торрентов. */
data class TorrentResult(
    val name: String,
    val infoHash: String,
    val seeds: Int,
    val sizeBytes: Long,
) {
    val sizeMb: Int
        get() = (sizeBytes / 1024 / 1024).toInt()
}

/**
 * Автопоиск торрентов по названию через открытые API без ключей.
 *
 * Провайдер: apibay.org — публичное JSON-зеркало ThePirateBay
 * (категории: 201 — фильмы, 205 — сериалы). Из info_hash собирается
 * magnet-ссылка с набором публичных трекеров.
 */
object TorrentSearch {

    private val http = OkHttpClient()

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
    )

    /**
     * Найти раздачи. Возвращает живые раздачи (seeds > 0),
     * отсортированные по количеству сидов.
     */
    fun search(query: String, isTvShow: Boolean, excludedKeywords: Set<String> = emptySet()): List<TorrentResult> {
        val cat = if (isTvShow) "205" else "201"
        val url = "https://apibay.org/q.php?q=${URLEncoder.encode(query, "UTF-8")}&cat=$cat"
        val body: String = try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                resp.body?.string() ?: return emptyList()
            }
        } catch (_: Exception) {
            return emptyList()
        }

        val arr = try {
            JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }

        val banned = excludedKeywords.map { it.lowercase() }
        val out = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.optString("info_hash")
            if (hash.length != 40) continue
            val name = o.optString("name")
            if (banned.any { name.lowercase().contains(it) }) continue
            out += TorrentResult(
                name = name,
                infoHash = hash.lowercase(),
                seeds = o.optString("seeders").toIntOrNull() ?: 0,
                sizeBytes = o.optString("size").toLongOrNull() ?: 0L,
            )
        }
        return out.filter { it.seeds > 0 }.sortedByDescending { it.seeds }.take(25)
    }

    /** Собрать magnet-ссылку из info_hash с публичными трекерами. */
    fun toMagnet(r: TorrentResult): String {
        val dn = URLEncoder.encode(r.name, "UTF-8")
        val tr = TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:${r.infoHash}&dn=$dn$tr"
    }
}
