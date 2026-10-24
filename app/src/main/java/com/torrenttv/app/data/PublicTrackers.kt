package com.torrenttv.app.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Дополнительные открытые трекеры — без регистрации и кук:
 *  - EZTV (eztv.re) — сериалы, официальный JSON API;
 *  - SolidTorrents (solidtorrents.to) — JSON-поиск по большому индексу;
 *  - BTDig (btdig.com) — поиск по DHT, HTML-разбор.
 * Схема результата та же, что у apibay: info_hash → magnet с публичными трекерами.
 */
object EztvProvider {

    const val NAME = "eztv"

    fun search(query: String, isTvShow: Boolean, imdbId: String = "", season: Int = 0, episode: Int = 0): List<TorrentResult> {
        val sb = StringBuilder("https://eztv.re/api/get-torrents?limit=60")
        if (imdbId.isNotBlank()) {
            // imdb id "tt1234567" → 1234567
            val n = imdbId.removePrefix("tt").toLongOrNull()
            if (n != null) sb.append("&imdb_id=").append(n)
            if (season > 0) sb.append("&season=").append(season)
            if (episode > 0) sb.append("&episode=").append(episode)
        } else {
            sb.append("&query_term=").append(URLEncoder.encode(query, "UTF-8"))
        }
        val body = TorrentHttp.get(sb.toString()) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }
        val arr = root.optJSONArray("torrents") ?: return emptyList()

        val out = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.safeString("hash").lowercase()
            if (hash.length != 40) continue
            val name = o.safeString("title")
            if (name.isBlank()) continue
            out += TorrentResult(
                name = name,
                infoHash = hash,
                seeds = o.optInt("seeds", 0),
                sizeBytes = o.optLong("size_bytes", 0),
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
            )
        }
        return out
    }
}

object SolidTorrentsProvider {

    const val NAME = "solid"

    fun search(query: String): List<TorrentResult> {
        val url = "https://solidtorrents.to/api/v1/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val body = TorrentHttp.get(url) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }
        // Форматы ответа: {"results": {"items": [...]}} либо {"results": [...]}
        val arr = root.optJSONObject("results")?.optJSONArray("items")
            ?: root.optJSONArray("results")
            ?: return emptyList()

        val out = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = (o.optString("infoHash").ifBlank { o.optString("info_hash") }).lowercase()
            if (hash.length != 40) continue
            val name = o.safeString("title").ifBlank { o.safeString("name") }
            val size = o.optJSONObject("size")?.optLong("value")
                ?: o.optLong("size", 0)
            val seeds = o.optInt("seeders", 0)
            if (name.isBlank()) continue
            out += TorrentResult(
                name = name,
                infoHash = hash,
                seeds = seeds,
                sizeBytes = size,
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
            )
        }
        return out
    }
}

object BtdigProvider {

    const val NAME = "btdig"

    fun search(query: String): List<TorrentResult> {
        val url = "https://btdig.com/search?q=${URLEncoder.encode(query, "UTF-8")}&order=0"
        val html = TorrentHttp.get(url) ?: return emptyList()

        // Блоки раздач: magnet + рядом название; сиды — если найдём рядом цифры
        val out = mutableListOf<TorrentResult>()
        val blocks = html.split("<div class=\"one_result\">")
        for (b in blocks.drop(1)) {
            val hash = Regex("urn:btih:([0-9a-fA-F]{40})").find(b)?.groupValues?.get(1)?.lowercase() ?: continue
            val name = Regex("class=\"one_result_desc\"[^>]*>([^<]+)</a>").find(b)?.groupValues?.get(1)
                ?: Regex("<b>([^<]{10,})</b>").find(b)?.groupValues?.get(1)
                ?: continue
            val seeds = Regex("class=\"one_result_seed\">([0-9 ]+)<").find(b)?.groupValues?.get(1)
                ?.replace(" ", "")?.toIntOrNull() ?: 0
            val size = Regex("([0-9.]+)\\s*(ГБ|GB|МБ|MB|TB|ТБ)").find(b)?.let { m ->
                val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val mult = when {
                    m.groupValues[2].startsWith("Т") || m.groupValues[2] == "TB" -> 1024L * 1024 * 1024 * 1024
                    m.groupValues[2].startsWith("Г") || m.groupValues[2] == "GB" -> 1024L * 1024 * 1024
                    else -> 1024L * 1024
                }
                (v * mult).toLong()
            } ?: 0L
            if (seeds <= 0) continue // без сидов не показываем — их слишком много мёртвых
            out += TorrentResult(
                name = name.trim(),
                infoHash = hash,
                seeds = seeds,
                sizeBytes = size,
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
            )
        }
        return out
    }
}

/**
 * RuTor — открытый русский индексатор раздач: регистрация не нужна,
 * magnet отдаётся прямо в списке результатов. Лучший источник русских
 * сериалов/фильмов среди публичных (apibay/EZTV — почти только англоязычное).
 * Зеркала перебираем: основной домен часто меняется.
 */
object RutorProvider {

    const val NAME = "rutor"

    private val MIRRORS = listOf(
        "https://rutor.info",
        "https://rutor.is",
        "https://rutor.org",
        "https://rutor.black",
    )

    fun search(query: String): List<TorrentResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        for (base in MIRRORS) {
            val html = TorrentHttp.get("$base/search/0/0/100/2/$q") ?: continue
            val out = parse(html)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun parse(html: String): List<TorrentResult> {
        val out = mutableListOf<TorrentResult>()
        // Строки результатов: <tr class="gai|tum"> ... magnet ... название ... сиды
        val rows = html.split("<tr class=\"gai\">", "<tr class=\"tum\">")
        for (r in rows.drop(1)) {
            val hash = Regex("urn:btih:([0-9a-fA-F]{40})").find(r)?.groupValues?.get(1)?.lowercase() ?: continue
            // Название: ссылка на страницу раздачи (/torrent/NNN или с текстом)
            val name = Regex("""href="[^"]*/torrent/[^"]*"[^>]*>([^<]{4,})</a>""").find(r)
                ?.groupValues?.get(1)?.trim() ?: continue
            val seeds = Regex("▲\\s*([0-9 ]+)|class=\"[^\"]*green[^\"]*\"[^>]*>\\s*([0-9 ]+)")
                .find(r)?.groupValues?.let { g -> (g[1].ifBlank { g[2] }).replace(" ", "").toIntOrNull() } ?: 0
            val size = Regex("([0-9.]+)\\s*(ТБ|TB|ГБ|GB|МБ|MB)").find(r)?.let { m ->
                val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val u = m.groupValues[2]
                val mult = when {
                    u.startsWith("Т") || u == "TB" -> 1024L * 1024 * 1024 * 1024
                    u.startsWith("Г") || u == "GB" -> 1024L * 1024 * 1024
                    else -> 1024L * 1024
                }
                (v * mult).toLong()
            } ?: 0L
            out += TorrentResult(
                name = name,
                infoHash = hash,
                seeds = seeds,
                sizeBytes = size,
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
            )
        }
        return out
    }
}

/**
 * Рутрекер (rutrackerr.ru) — открытый русский каталог раздач без регистрации:
 * AJAX-поиск по названию возвращает HTML-таблицу с magnet-ссылками, сидами
 * и размерами. Проверен живым запросом: «Спецы» — 97 раздач с magnet.
 */
object RutrackerrProvider {

    const val NAME = "rutrackerr"

    private const val SEARCH_URL =
        "https://rutrackerr.ru/engine/modules/search-torrents/search.php"

    fun search(query: String): List<TorrentResult> {
        val html = TorrentHttp.postForm(
            url = SEARCH_URL,
            fields = mapOf("fraza" to query, "id" to "", "search_ok" to "go_search"),
            headers = mapOf(
                "Referer" to "https://rutrackerr.ru/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120",
            ),
        ) ?: return emptyList()
        return parse(html)
    }

    private fun parse(html: String): List<TorrentResult> {
        val out = mutableListOf<TorrentResult>()
        // Каждая раздача — строка таблицы: название в <b>, размер, сиды, magnet
        val rows = html.split("<tr>")
        for (r in rows) {
            val hash = Regex("urn:btih:([0-9a-fA-F]{40})").find(r)?.groupValues?.get(1)?.lowercase() ?: continue
            val name = Regex("""<td><b>([^<]{4,})</b></td>""").find(r)?.groupValues?.get(1)?.trim() ?: continue
            val pageUrl = Regex("""href="((?:https://rutrackerr\.ru)?/\d+[^"]*tor\.html)"""")
                .find(r)?.groupValues?.get(1)?.let { if (it.startsWith("http")) it else "https://rutrackerr.ru" + it } ?: ""
            // Прямая ссылка на .torrent: метаданные мгновенно, без магнет-поиска
            val dlUrl = Regex("""href="(/search-torrents/download/[^"]+)"""")
                .find(r)?.groupValues?.get(1)?.let { "https://rutrackerr.ru$it" } ?: ""
            // Числа в таблице: первое — сиды, второе — пиры
            val nums = Regex("""&nbsp;(\d+)""").findAll(r).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            val seeds = nums.getOrNull(0) ?: 0
            val peers = nums.getOrNull(1) ?: 0
            val size = Regex("([0-9.]+)\\s*(ТБ|TB|ГБ|GB|МБ|MB|КБ|KB)").find(r)?.let { m ->
                val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val u = m.groupValues[2]
                val mult = when {
                    u.startsWith("Т") || u == "TB" -> 1024L * 1024 * 1024 * 1024
                    u.startsWith("Г") || u == "GB" -> 1024L * 1024 * 1024
                    u.startsWith("М") || u == "MB" -> 1024L * 1024
                    else -> 1024L
                }
                (v * mult).toLong()
            } ?: 0L
            out += TorrentResult(
                name = name,
                infoHash = hash,
                seeds = seeds,
                sizeBytes = size,
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
                pageUrl = pageUrl,
                dlUrl = dlUrl,
                peers = peers,
            )
        }
        return out
    }
}

object KnabenProvider {

    const val NAME = "knaben"

    /**
     * Knaben — открытый метапоиск по десяткам трекеров, публичный JSON-API
     * (POST). Регистрация не нужна. Ответ: {"results":[{"name","infoHash",
     * "seeders","size",...}]}.
     */
    fun search(query: String): List<TorrentResult> {
        val body = """
            {"query":"${query.replace("\\", "\\\\").replace("\"", "\\\"")}",
             "accu":false,"keywords":[],"categories":[],"from":0,"size":60}
        """.trimIndent()
        val resp = TorrentHttp.postJson("https://api.knaben.eu/search/", body) ?: return emptyList()
        val root = try { JSONObject(resp) } catch (_: Exception) { return emptyList() }
        val arr = root.optJSONArray("results") ?: return emptyList()

        val out = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.optString("infoHash").lowercase()
            if (hash.length != 40) continue
            val name = o.safeString("name")
            if (name.isBlank()) continue
            out += TorrentResult(
                name = name,
                infoHash = hash,
                seeds = o.optInt("seeders", 0),
                sizeBytes = o.optLong("size", 0),
                provider = NAME,
                resolution = ReleaseQuality.parse(name),
            )
        }
        return out
    }
}
