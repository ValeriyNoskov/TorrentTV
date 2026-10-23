package com.torrenttv.app.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Онлайн-просмотр через Filmix (API v2, как в Lampa).
 *
 * Требуется токен пользователя (профиль Filmix → «Устройства/API»), задаётся
 * в настройках. Домены Filmix мигрируют — пробуем список зеркал по очереди.
 *
 * Схема ответа playerdata:
 *   movie:  player_links.movie  = [ {link, translation, quality, ...} ]
 *   serial: player_links.serial = { сезон: { серия: [ {link, ...} ] } }
 * Структура проверяется рекурсивно — переживает смены формата.
 */
object OnlineService {

    /** Публичные зеркала API (порядок = приоритет). */
    val MIRRORS = listOf(
        "https://filmixapp.cyou",
        "https://filmix.app",
        "https://filmix.ac",
        "https://filmix.my",
        "https://filmix.co",
    )

    /** Прямая ссылка на поток с подписью (перевод · качество · сезон/серия). */
    data class OnlineLink(
        val url: String,
        val label: String,
        val season: Int = 0,
        val episode: Int = 0,
        val quality: String = "",
        val translation: String = "",
    )

    private var activeMirror: String? = null

    /** Базовый URL зеркала: сначала кэш, затем перебор. */
    private fun mirror(token: String): String? {
        activeMirror?.let { return it }
        for (m in MIRRORS) {
            // Проверка живости: лёгкий запрос поиска без токена
            val probe = TorrentHttp.get("$m/api/v2/search?query=test")
            if (probe != null && probe.contains("\"item\"")) {
                activeMirror = m
                return m
            }
        }
        return null
    }

    /** Поиск карточки по названию. Блокирующий — из фонового потока. */
    fun search(token: String, query: String): List<OnlineMatch> {
        val base = mirror(token) ?: return emptyList()
        val body = TorrentHttp.get(
            "$base/api/v2/search?query=" + Uri.encode(query),
            headers(),
        ) ?: return emptyList()
        return parseSearch(body)
    }

    /** Прямой запрос playerdata по id карточки Filmix. Блокирующий. */
    fun playerData(token: String, filmixId: String): List<OnlineLink> {
        if (token.isBlank()) return emptyList()
        val base = mirror(token) ?: return emptyList()
        val body = TorrentHttp.get(
            "$base/api/v2/get-playerdata?id=$filmixId&token=" + Uri.encode(token),
            headers(),
        ) ?: return emptyList()
        return runCatching { parsePlayerData(body) }.getOrDefault(emptyList())
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer" to "https://filmix.ac/",
    )

    // ---------- Разбор ----------

    data class OnlineMatch(
        val filmixId: String,
        val title: String,
        val originalTitle: String,
        val year: String,
        val isSerial: Boolean,
    )

    fun parseSearch(body: String): List<OnlineMatch> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("item") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            val title = o.optString("title", "")
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            OnlineMatch(
                filmixId = id,
                title = title,
                originalTitle = o.optString("original_title", ""),
                year = o.optString("year", "").take(4),
                isSerial = o.optString("type", "") == "serial",
            )
        }
    }

    /**
     * Рекурсивно собрать все видео-ссылки из player_links:
     * словарь → спуск по ключам (сезон/серия/перевод), массив с {link} — лист.
     */
    fun parsePlayerData(body: String): List<OnlineLink> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val links = json.optJSONObject("player_links") ?: return emptyList()
        val out = mutableListOf<OnlineLink>()
        walk(links, path = emptyList(), out = out)
        return out
    }

    private fun walk(node: Any, path: List<String>, out: MutableList<OnlineLink>) {
        when (node) {
            is JSONObject -> {
                if (node.optString("link", "").isNotBlank()) {
                    out += leaf(node, path)
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        walk(node.opt(k) ?: continue, path + k, out)
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walk(node.opt(i) ?: continue, path, out)
                }
            }
        }
    }

    private fun leaf(o: JSONObject, path: List<String>): OnlineLink {
        val translation = o.optString("translation", "")
        val quality = o.optString("quality", "")
        // Путь вида [сезон, серия] для сериалов; для фильма пустой
        val numbers = path.filter { it.toIntOrNull() != null }.map { it.toInt() }
        val season = numbers.getOrNull(0) ?: 0
        val episode = numbers.getOrNull(1) ?: 0

        val place = when {
            season > 0 && episode > 0 -> "S%02dE%02d".format(season, episode)
            else -> ""
        }
        val label = listOfNotNull(place, translation.ifBlank { null }, quality.ifBlank { null })
            .joinToString(" · ")

        var url = o.optString("link", "")
        // Относительные ссылки достраиваем до домена зеркала
        if (url.startsWith("//")) url = "https:$url"

        return OnlineLink(
            url = url,
            label = label,
            season = season,
            episode = episode,
            quality = quality,
            translation = translation,
        )
    }

    /** Лучшая ссылка для эпизода (или для фильма) — по качеству. */
    fun bestOf(links: List<OnlineLink>): OnlineLink? =
        links.maxByOrNull { qualityScore(it.quality) }

    private fun qualityScore(q: String): Int = when {
        q.contains("2160", true) || q.equals("4k", true) -> 5
        q.contains("1080", true) -> 4
        q.contains("720", true) -> 3
        q.contains("480", true) -> 2
        else -> 1
    }
}
