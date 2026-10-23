package com.torrenttv.app.data

/**
 * Каталог фильмов/сериалов из открытого «Рутрекера» (rutrackerr.ru) —
 * работает без ключей и лимитов. Используется как последний фолбэк,
 * когда недоступны и Кинопоиск, и TMDB: список названий с постерами
 * берём из категорий и поиска сайта.
 */
object RutrackerrCatalog {

    private const val BASE = "https://rutrackerr.ru"

    private val UA = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120")

    /** «Сейчас в тренде»: популярное на сайте. */
    fun trending(page: Int = 1): List<MediaItem> = category("$BASE/popular/", page)

    /** Новинки (дата добавления). */
    fun newItems(page: Int = 1): List<MediaItem> = category("$BASE/index.php?do=lastnews", page)

    /** Популярные фильмы: главная-подобные списки фильмов. */
    fun popularMovies(page: Int = 1): List<MediaItem> =
        category("$BASE/filmy/", page).ifEmpty { newItems(page) }

    /** Сериалы. */
    fun popularTv(page: Int = 1): List<MediaItem> = category("$BASE/serialy/", page)

    /** Поиск: AJAX сайта возвращает список раздач — группируем по названиям. */
    fun search(query: String): List<MediaItem> {
        val html = TorrentHttp.postForm(
            url = "https://rutrackerr.ru/engine/modules/search-torrents/search.php",
            fields = mapOf("fraza" to query, "id" to "", "search_ok" to "go_search"),
            headers = UA + mapOf("Referer" to "$BASE/"),
        ) ?: return emptyList()

        val out = mutableListOf<MediaItem>()
        val seen = mutableSetOf<String>()
        // Название раздачи: «<td><b>Название (год) качество...</b></td>» —
        // берём до скобки с годом, группируем дубли
        val rows = html.split("<tr>")
        data class Found(val item: MediaItem, val url: String)
        val found = mutableListOf<Found>()
        for (r in rows) {
            val name = Regex("""<td><b>([^<]{4,})</b></td>""").find(r)?.groupValues?.get(1)?.trim() ?: continue
            val pageUrl = Regex("""href="((?:https://rutrackerr\.ru)?/\d+[^"]*tor\.html)"""")
                .find(r)?.groupValues?.get(1) ?: continue
            val clean = name.replace(Regex("""\s*\[.*$"""), "").trim()          // «Спецы [01x01-08…]» → «Спецы»
            val m = Regex("""^(.*?)\s*\((\d{4})\)""").find(clean)
            val title = (m?.groupValues?.get(1) ?: clean).replace("|", "").trim()
            val year = m?.groupValues?.get(2) ?: ""
            val key = title.lowercase().replace('ё', 'е')
            // Фильтр мусора: название должно содержать запрос (иначе поиск
            // «спецы» возвращает «Специи», книги, спорт)
            val q = query.lowercase().replace('ё', 'е').trim()
            if (title.length < 2 || !seen.add(key)) continue
            if (!key.contains(q)) continue
            found += Found(
                MediaItem(
                    id = kotlin.math.abs(("rt-" + key).hashCode()),
                    type = "movie",
                    title = title,
                    year = year,
                    posterUrl = "",
                    // Служебный адрес страницы раздачи — для «Похожего»
                    originalTitle = "@rt:" + (if (pageUrl.startsWith("http")) pageUrl else BASE + pageUrl),
                ),
                if (pageUrl.startsWith("http")) pageUrl else BASE + pageUrl,
            )
            if (found.size >= 12) break
        }
        if (found.isEmpty()) return emptyList()

        // Постер и тип подтягиваем параллельно со страниц раздач
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val items = try {
            found.map { f ->
                pool.submit<Found> {
                    val r = runCatching {
                        val page = TorrentHttp.get(f.url, UA)
                        if (page == null) {
                            f
                        } else {
                            val poster = Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"""")
                                .find(page)?.groupValues?.get(1)
                                ?.let { if (it.startsWith("http")) it else BASE + it } ?: ""
                            val isTv = page.contains("/serialy/")
                            f.copy(item = f.item.copy(posterUrl = poster, type = if (isTv) "tv" else "movie"))
                        }
                    }.getOrDefault(f)
                    r
                }
            }.map { it.get() }.map { it.item }
        } finally {
            pool.shutdown()
        }
        return items
    }

    /** «Похожее» для элемента из Рутрекера: блок похожих на его странице. */
    fun similar(item: MediaItem): List<MediaItem> {
        val pageUrl = item.originalTitle.takeIf { it.startsWith("@rt:") }?.removePrefix("@rt:")
            ?: return emptyList()
        val page = TorrentHttp.get(pageUrl, UA) ?: return emptyList()
        return parseCards(page).filter { it.title != item.title }.take(12)
    }

    /** Разбор страницы категории/подобного списка: карточки rutef_movie. */
    private fun category(url: String, page: Int): List<MediaItem> {
        val full = if (page > 1) url.trimEnd('/') + "/page/$page/" else url
        val html = TorrentHttp.get(full, UA) ?: return emptyList()
        return parseCards(html)
    }

    private fun parseCards(html: String): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val blocks = html.split("<div class=\"rutef_movie-0c1\">")
        for (b in blocks.drop(1)) {
            // Страница раздачи: /NNNN-name-year-tor.html (иногда с доменом)
            val url = Regex("""href="((?:https://rutrackerr\.ru)?/\d+[^"]*tor\.html)"""")
                .find(b)?.groupValues?.get(1) ?: continue
            val titleAll = Regex("""<h1>([^<]{2,120})</h1>""").find(b)?.groupValues?.get(1)?.trim() ?: continue
            val m = Regex("""^(.*?)\s*\((\d{4})\)""").find(titleAll)
            val title = (m?.groupValues?.get(1) ?: titleAll).trim()
            val year = m?.groupValues?.get(2) ?: ""
            val isTv = b.contains("/serialy/")
            val poster = Regex("""<img src="([^"]+)"""").find(b)?.groupValues?.get(1)
                ?.takeIf { !it.contains("none_poster") }
                ?.let { if (it.startsWith("http")) it else BASE + it } ?: ""
            out += MediaItem(
                id = kotlin.math.abs(url.hashCode()),
                type = if (isTv) "tv" else "movie",
                title = title,
                year = year,
                posterUrl = poster,
                // Служебный адрес страницы раздачи — для «Похожего»
                originalTitle = "@rt:" + (if (url.startsWith("http")) url else BASE + url),
            )
            if (out.size >= 40) break
        }
        return out
    }
}
