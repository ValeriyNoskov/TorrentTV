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

    /** Публичные зеркала API (порядок = приоритет). Живое — filmixapp.cyou (http). */
    val MIRRORS = listOf(
        "http://filmixapp.cyou",
        "https://filmixapp.cyou",
        "https://filmix.app",
        "https://filmix.ac",
        "https://filmix.my",
        "https://filmix.co",
    )

    /**
     * Параметры устройства — как в официальном приложении Filmix (Lampa).
     * user_dev_token — токен (может быть пустым до активации).
     */
    private fun devParams(token: String): String =
        "user_dev_apk=2.0.1&user_dev_id=&user_dev_name=Xiaomi&user_dev_os=11" +
            "&user_dev_token=" + Uri.encode(token) + "&user_dev_vendor=Xiaomi"

    /** Код устройства для привязки аккаунта: ввести userCode на filmix.ac/consoles. */
    data class DeviceCode(val token: String, val userCode: String, val expiresAt: Long)

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

    /** Ответ похож на API (JSON), а не на заглушку/HTML-ошибку. */
    private fun looksLikeApi(body: String): Boolean {
        val t = body.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    /** GET к API с перебором зеркал: рабочее запоминаем. Вернёт (зеркало, тело) или null. */
    private fun apiGet(path: String): Pair<String, String>? {
        activeMirror?.let { m ->
            val body = TorrentHttp.get(m + path, headers())
            if (body != null && looksLikeApi(body)) return m to body
            activeMirror = null // кэш протух — перебираем заново
        }
        for (m in MIRRORS) {
            val body = TorrentHttp.get(m + path, headers()) ?: continue
            if (looksLikeApi(body)) {
                activeMirror = m
                return m to body
            }
        }
        return null
    }

    /** Запросить код устройства для привязки аккаунта Filmix. */
    fun requestDeviceCode(): DeviceCode? {
        val (_, body) = apiGet("/api/v2/token_request?" + devParams("")) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optString("status") != "ok") return null
        val token = json.optString("code", "")
        val userCode = json.optString("user_code", "")
        if (token.isBlank() || userCode.isBlank()) return null
        return DeviceCode(token, userCode, json.optLong("expire", 0L))
    }

    /** Профиль по токену: null — токен ещё не активирован/не работает. */
    fun profile(token: String): JSONObject? {
        if (token.isBlank()) return null
        val (_, body) = apiGet("/api/v2/user_profile?" + devParams(token)) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optJSONObject("user_data")
    }

    /** Поиск карточки по названию. Блокирующий — из фонового потока. */
    fun search(token: String, query: String): List<OnlineMatch> {
        val (_, body) = apiGet("/api/v2/search?story=" + Uri.encode(query) + "&" + devParams(token))
            ?: return emptyList()
        return parseSearch(body)
    }

    /** Карточка Filmix по id (в Lampa — «post»); содержит player_links. Блокирующий. */
    fun playerData(token: String, filmixId: String): List<OnlineLink> =
        playerInfo(token, filmixId).links

    /** Результат поста с диагнозом: есть ли player_links и не заблокировано ли всё. */
    data class PlayerInfo(
        val links: List<OnlineLink>,
        val hasPlayerLinks: Boolean, // поле player_links присутствовало
        val allBlocked: Boolean,     // ссылки были, но все «Заблокировано правообладателем»
    )

    /** Загрузить пост с диагностикой: почему пусто — нет поля или всё под блокировкой. */
    fun playerInfo(token: String, filmixId: String): PlayerInfo {
        val (_, body) = apiGet("/api/v2/post/$filmixId?" + devParams(token))
            ?: return PlayerInfo(emptyList(), hasPlayerLinks = false, allBlocked = false)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return PlayerInfo(emptyList(), hasPlayerLinks = false, allBlocked = false)
        val pl = json.optJSONObject("player_links")
            ?: return PlayerInfo(emptyList(), hasPlayerLinks = false, allBlocked = false)
        val out = mutableListOf<OnlineLink>()
        walk(pl, path = emptyList(), out = out)
        // walk уже отбросил abuse-ссылки: пусто при непустом player_links = всё заблокировано
        return PlayerInfo(out, hasPlayerLinks = true, allBlocked = out.isEmpty())
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
        // API возвращает массив карточек (как в Lampa), а не объект с полем item
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            val title = o.optString("title", "")
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            val serialStats = o.optJSONObject("serial_stats")
            OnlineMatch(
                filmixId = id,
                title = title,
                originalTitle = o.optString("original_title", ""),
                year = o.optString("year", "").take(4),
                isSerial = serialStats != null,
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
                    // «abuse_» в ссылке = «Заблокировано правообладателем» — мусор
                    if (!node.optString("link").contains("abuse_")) out += leaf(node, path)
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
        // качества доступны в HLS-потоке: [1080, 720, 480] — для подписи и фильтра
        val qArr = o.optJSONArray("qualities")
        val maxQ = qArr?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optInt(it, 0) }.maxOrNull() ?: 0
        } ?: 0
        val quality = o.optString("quality", "").ifBlank { if (maxQ > 0) "${maxQ}p" else "" }
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
