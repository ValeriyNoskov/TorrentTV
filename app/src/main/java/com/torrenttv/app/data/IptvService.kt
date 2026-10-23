package com.torrenttv.app.data

/**
 * Сервис бесплатного IPTV: плейлист iptv-org по России
 * (https://github.com/iptv-org/iptv — открытый каталог публичных трансляций).
 * Включает федеральные каналы и московские/подмосковные: Moskva 24,
 * Moskva. Doveriye, Govorit Moskva, 360° и др.
 *
 * Каналы кэшируются в памяти на час. Метод блокирующий — вызывать из IO.
 */
object IptvService {

    const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/ru.m3u"

    private const val TTL_MS = 60 * 60 * 1000L

    @Volatile private var cache: List<IptvChannel>? = null
    @Volatile private var cacheTime = 0L

    /** Загрузить (или взять из кэша) список каналов. */
    fun channels(forceRefresh: Boolean = false): List<IptvChannel> {
        val cached = cache
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cacheTime < TTL_MS) {
            return cached
        }
        val body = TorrentHttp.get(PLAYLIST_URL) ?: return cached ?: emptyList()
        val parsed = M3uParser.parse(body)
            // «Animation;Kids» → канал попадает в обе группы
            .flatMap { c ->
                if (';' in c.group) {
                    c.group.split(';').filter { it.isNotBlank() }.map { g ->
                        c.copy(group = g.trim())
                    }
                } else listOf(c)
            }
            .sortedWith(compareBy({ it.group }, { it.name }))
        if (parsed.isNotEmpty()) {
            cache = parsed
            cacheTime = System.currentTimeMillis()
        }
        return parsed
    }

    /** Группы в порядке первого появления + человеческие подписи. */
    fun groups(channels: List<IptvChannel>): List<Pair<String, String>> {
        val seen = LinkedHashMap<String, Int>()
        channels.forEach { c -> seen.merge(c.group, 1, Int::plus) }
        return seen.map { (g, n) -> g to groupLabel(g, n) }
    }

    /** Перевод типовых групп iptv-org на русский (остальные — как есть). */
    fun groupLabel(group: String, count: Int): String {
        val label = when (group.lowercase()) {
            "general" -> "Универсальные"
            "movies" -> "Кино"
            "news" -> "Новости"
            "entertainment" -> "Развлекательные"
            "kids" -> "Детские"
            "sports" -> "Спорт"
            "music" -> "Музыка"
            "documentary" -> "Документальные"
            "series" -> "Сериалы"
            "culture" -> "Культура"
            "lifestyle" -> "Стиль жизни"
            "travel" -> "Путешествия"
            "outdoor" -> "Природа и охота"
            "religious" -> "Религиозные"
            "science" -> "Наука"
            "business" -> "Деловые"
            "cooking" -> "Кулинария"
            "legislative" -> "Гос. каналы"
            "undefined", "" -> "Прочие"
            else -> group
        }
        return "$label ($count)"
    }
}
