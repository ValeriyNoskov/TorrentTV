package com.torrenttv.app.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Онлайн-просмотр через HDRezka (как второй источник после Filmix).
 *
 * Протокол (тот же, что используют балансеры):
 *  1) поиск: GET /engine/ajax/search.php?q=… → HTML со ссылками карточек
 *     вида /films/…/123-name.html (фильмы) и /series/…/456-name.html (сериалы);
 *  2) сессия: GET карточки ставит PHPSESSID — без неё ajax отвечает
 *     «Время сессии истекло»;
 *  3) фильм: POST /ajax/get_cdn_series/ {id, translator_id=0, is_cam=0,
 *     is_director=0, action=get_stream} → {links, quality};
 *  4) сериал: тот же POST с action=get_episodes → {seasons:"1,2",
 *     episodes:{"1":"1,2,3"}}, затем с season_id/episode_id и action=get_stream.
 *
 * CDN-ссылки проверяют Referer зеркала — он передаётся в OnlineLink.referer.
 */
object RezkaService {

    const val NAME = "rezka"

    /** Зеркала в порядке приоритета (домены мигрируют). */
    val MIRRORS = listOf(
        "https://hdrezka.ag",
        "https://rezka.ag",
        "https://hdrezka-home.tv",
        "https://hdrezka.la",
        "https://hdrezka.rest",
        "https://hdrezka.cm",
        "https://rezka.city",
    )

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    @Volatile private var activeMirror: String? = null

    /** Cookie-сессия по хосту (PHPSESSID и пр.) — без неё ajax отдаёт «сессия истекла». */
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val client = TorrentHttp.client.newBuilder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
                if (cookies.isNotEmpty()) cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
                cookieStore[url.host] ?: emptyList()
        })
        .build()

    /** Найденная карточка. */
    data class RezkaMatch(
        val url: String,
        val id: String,
        val title: String,
        val originalTitle: String = "",
        val year: String = "",
        val isSerial: Boolean,
    )

    private fun looksLikeSite(body: String): Boolean =
        body.contains("enty") || body.contains("b-search") || body.trimStart().startsWith("{") ||
            body.trimStart().startsWith("[")

    /** GET с перебором зеркал: рабочее запоминаем. Вернёт (зеркало, тело) или null. */
    private fun apiGet(path: String): Pair<String, String>? {
        activeMirror?.let { m ->
            val body = get(m + path, m)
            if (body != null) return m to body
            activeMirror = null
        }
        for (m in MIRRORS) {
            val body = get(m + path, m) ?: continue
            activeMirror = m
            return m to body
        }
        return null
    }

    private fun get(url: String, referer: String): String? = try {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ru,en;q=0.9")
            .header("Referer", referer)
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    /** POST-форма на ajax-endpoint активного зеркала; null при ошибке. */
    private fun postCdn(form: Map<String, String>, referer: String): JSONObject? {
        val mirror = activeMirror ?: MIRRORS.first()
        return try {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            val req = Request.Builder()
                .url(mirror + "/ajax/get_cdn_series/")
                .header("User-Agent", UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Referer", referer)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = if (!resp.isSuccessful) null else resp.body?.string() ?: return null
                if (text == null || !text.trimStart().startsWith("{")) null
                else JSONObject(text)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Поиск ----------

    /** Поиск карточек по названию. Блокирующий — из фонового потока. */
    fun search(query: String): List<RezkaMatch> {
        val (_, body) = apiGet("/engine/ajax/search.php?q=" + URLEncoder.encode(query, "UTF-8"))
            ?: return emptyList()
        return parseSearch(body)
    }

    fun parseSearch(body: String): List<RezkaMatch> {
        // <a href="…/films/fiction/981-matrica-1999-latest.html">…<span class="enty">Матрица</span> (The Matrix, 1999)…
        val rx = Regex(
            "<a href=\"(https?://[^\"]+?/(\\w+)/[^/]*?(\\d+)-[^\"]*?\\.html)\"[^>]*>" +
                "(?s).*?<span class=\"enty\">([^<]*)</span>(?:\\s*\\((?:([^,)]+),\\s*)?(\\d{4})\\))?",
        )
        val out = mutableListOf<RezkaMatch>()
        val seen = HashSet<String>()
        for (m in rx.findAll(body)) {
            val url = m.groupValues[1]
            if (!seen.add(url)) continue
            val type = m.groupValues[2]
            out += RezkaMatch(
                url = url,
                id = m.groupValues[3],
                title = m.groupValues[4].trim(),
                originalTitle = m.groupValues[5]?.trim().orEmpty(),
                year = m.groupValues[6],
                isSerial = type.contains("serial", true) || url.contains("/series/"),
            )
        }
        return out
    }

    // ---------- Ссылки ----------

    /**
     * Ссылки фильма (season=0) или конкретного эпизода.
     * Перед POST обновляет cookie-сессию GET-ом карточки; при «сессия истекла»
     * повторяет один раз.
     */
    fun streams(match: RezkaMatch, season: Int = 0, episode: Int = 0): List<OnlineService.OnlineLink> {
        val mirror = activeMirror ?: MIRRORS.first()
        // сессия: карточка ставит PHPSESSID
        get(match.url, mirror)

        val form = linkedMapOf(
            "id" to match.id,
            "translator_id" to "0",
            "is_cam" to "0",
            "is_director" to "0",
        )
        if (season > 0) {
            form["season_id"] = season.toString()
            form["episode_id"] = episode.toString()
        }
        form["action"] = "get_stream"

        var json = postCdn(form, match.url)
        if (json != null && !json.optBoolean("success") &&
            json.optString("message").contains("сессии", true)
        ) {
            // сессия протухла: GET главной + карточки, повтор
            get(mirror + "/", mirror)
            get(match.url, mirror)
            json = postCdn(form, match.url)
        }
        if (json == null || !json.optBoolean("success")) return emptyList()
        return parseLinks(json, season, episode, mirror)
    }

    /** Сезоны и эпизоды сериала: (сезоны, сезон → эпизоды). */
    fun episodes(match: RezkaMatch): Pair<List<Int>, Map<Int, List<Int>>> {
        val mirror = activeMirror ?: MIRRORS.first()
        get(match.url, mirror)
        val form = linkedMapOf(
            "id" to match.id,
            "translator_id" to "0",
            "is_cam" to "0",
            "is_director" to "0",
            "action" to "get_episodes",
        )
        var json = postCdn(form, match.url)
        if (json != null && !json.optBoolean("success") &&
            json.optString("message").contains("сессии", true)
        ) {
            get(mirror + "/", mirror)
            get(match.url, mirror)
            json = postCdn(form, match.url)
        }
        if (json == null || !json.optBoolean("success")) return emptyList<Int>() to emptyMap()
        val seasons = json.optString("seasons").split(',')
            .mapNotNull { it.trim().toIntOrNull() }.sorted()
        val epsObj = json.optJSONObject("episodes") ?: JSONObject()
        val eps = seasons.associateWith { s ->
            epsObj.optString(s.toString()).split(',')
                .mapNotNull { it.trim().toIntOrNull() }.sorted()
        }
        return seasons to eps
    }

    /** {links:"u1,u2…"/"u1 or u2", quality:"1080p,720p…"} → OnlineLink. */
    fun parseLinks(
        json: JSONObject,
        season: Int,
        episode: Int,
        mirror: String,
    ): List<OnlineService.OnlineLink> {
        val linksRaw = json.optString("links", "")
        val qualityRaw = json.optString("quality", "")
        if (linksRaw.isBlank()) return emptyList()

        val urls = linksRaw.split(",").map { it.trim() }.filter { it.startsWith("http") }
        val qualities = qualityRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return emptyList()

        val place = if (season > 0 && episode > 0) "S%02dE%02d".format(season, episode) else ""
        return urls.mapIndexed { i, raw ->
            val q = qualities.getOrElse(i) { qualities.firstOrNull() ?: "" }
            val url = raw.split(" or ").firstOrNull { it.startsWith("http") }?.trim() ?: raw.trim()
            OnlineService.OnlineLink(
                url = url,
                label = listOfNotNull("Rezka", place.ifBlank { null }, q.ifBlank { null })
                    .joinToString(" · "),
                season = season,
                episode = episode,
                quality = q,
                translation = "",
                referer = mirror,
            )
        }
    }
}
