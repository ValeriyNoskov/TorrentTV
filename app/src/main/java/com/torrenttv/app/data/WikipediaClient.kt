package com.torrenttv.app.data

import org.json.JSONObject

/**
 * Бесплатный источник постеров без ключа: API Википедии (ru.wikipedia.org,
 * из РФ доступен, лимитов нет). Постеры фильмов/сериалов там почти всегда есть.
 * Важно: pilicense=any — иначе постеры фильмов (несвободные) не отдаются.
 * Ответы кешируются общим кешем — повторные запросы без сети.
 */
object WikipediaClient {

    private const val API = "https://ru.wikipedia.org/w/api.php"

    /** Найти постер по названию тайтла; null — если ничего не нашлось. */
    fun poster(query: String): String? {
        if (query.isBlank()) return null
        val url = buildString {
            append(API)
            append("?action=query&format=json&pithumbsize=500")
            append("&prop=pageimages&pilicense=any")
            append("&generator=search&gsrlimit=1&gsrsearch=")
            append(android.net.Uri.encode(query))
        }
        val body = KinopoiskClient.sharedFresh(url) ?: fetch(url) ?: return null
        return runCatching {
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages")
            pages?.keys()?.asSequence()
                ?.map { pages.getJSONObject(it) }
                ?.firstOrNull()
                ?.optJSONObject("thumbnail")
                ?.optString("source")
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    /** Сетевой запрос с кешем: свежий ответ сохраняем, при сбое — устаревший. */
    private fun fetch(url: String): String? = try {
        TorrentHttp.client.newCall(
            okhttp3.Request.Builder().url(url)
                .header("User-Agent", "VNosKino/1.0 (TV app)")
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) null else {
                val b = r.body!!.string()
                KinopoiskClient.sharedPut(url, b)
                b
            }
        }
    } catch (e: Exception) {
        KinopoiskClient.sharedStale(url)   // источник сбойнул — устаревший лучше пустоты
    }
}
