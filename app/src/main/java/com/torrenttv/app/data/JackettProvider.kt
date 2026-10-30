package com.torrenttv.app.data

import java.net.URLEncoder

/**
 * Провайдер Jackett (torznab-агрегатор, опциональный).
 * Пользователь указывает адрес сервера и API-ключ в настройках;
 * поиск идёт по всем настроенным индексерам сразу (endpoint «all»).
 */
object JackettProvider {

    const val NAME = "jackett"

    data class Config(val url: String, val apiKey: String) {
        val isConfigured: Boolean
            get() = url.isNotBlank() && apiKey.isNotBlank()
    }

    /** Найти раздачи по всем индексерам Jackett (категории: фильмы + сериалы). */
    fun search(config: Config, query: String): List<TorrentResult> {
        if (!config.isConfigured) return emptyList()
        val base = config.url.trimEnd('/')
        val q = URLEncoder.encode(query, "UTF-8")
        // torznab-эндпоинт: без t=search Jackett возвращает пустой ответ
        val url = "$base/api/v2.0/indexers/all/results/torznab/api" +
            "?apikey=${config.apiKey}&t=search&q=$q&cat=2000,5000&limit=100"
        val xml = TorrentHttp.get(url, callTimeoutSec = 8) ?: return emptyList()
        return parseTorznab(xml)
    }

    /** Разбор torznab-ответа (RSS с torznab:attr). Тестовый чистый парсер. */
    fun parseTorznab(xml: String): List<TorrentResult> {
        val out = mutableListOf<TorrentResult>()
        for (itemXml in xml.split("<item>").drop(1)) {
            val body = itemXml.substringBefore("</item>")

            val title = unescapeXml(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.trim() ?: "")

            var hash = attr(body, "infohash").lowercase()
            if (hash.isBlank()) {
                // fallback: magnet в <link> или <guid>
                val link = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1)?.trim() ?: ""
                hash = ApibayProvider.hashFromMagnet(unescapeXml(link)) ?: ""
            }
            if (hash.length != 40) continue

            val seeds = attr(body, "seeders").toIntOrNull() ?: 0
            val size = attr(body, "size").toLongOrNull() ?: 0L
            if (title.isBlank()) continue

            out += TorrentResult(
                name = title,
                infoHash = hash,
                seeds = seeds,
                sizeBytes = size,
                provider = NAME,
                resolution = ReleaseQuality.parse(title),
            )
        }
        return out
    }

    private fun attr(itemXml: String, name: String): String =
        Regex("name=\"$name\"\\s+value=\"([^\"]*)\"").find(itemXml)?.groupValues?.get(1) ?: ""

    /** Минимальное разворачивание XML-сущностей в тексте. */
    fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
