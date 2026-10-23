package com.torrenttv.app.data

/**
 * Сервис бесплатного IPTV. Два источника:
 *  1) Free-TV/IPTV — федеральные RU-каналы с эфирными номерами и русскими названиями
 *     (Первый канал, Россия 1, Матч ТВ, НТВ, Пятый, Карусель, ОТР, СТС, Домашний,
 *      ТВ-3, Пятница, Звезда, ТНТ, Муз-ТВ, Москва 24 и др.);
 *  2) iptv-org по России (https://github.com/iptv-org/iptv) — несколько сотен каналов
 *     всех жанров; названия на латинице переводятся словарём M3uParser.
 *
 * Дубликаты (по нормализованному названию) берутся из Free-TV.
 * Каналы кэшируются в памяти на час. Метод блокирующий — вызывать из IO.
 */
object IptvService {

    const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/ru.m3u"
    const val FEDERAL_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    /** Имя группы федеральных каналов из Free-TV. */
    const val FEDERAL_GROUP = "federal"

    private const val TTL_MS = 60 * 60 * 1000L

    @Volatile private var cache: List<IptvChannel>? = null
    @Volatile private var cacheTime = 0L

    /** Загрузить (или взять из кэша) список каналов. */
    fun channels(forceRefresh: Boolean = false): List<IptvChannel> {
        val cached = cache
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cacheTime < TTL_MS) {
            return cached
        }
        // Федеральные: только RU из большого плейлиста Free-TV, группа своя
        val federal = TorrentHttp.get(FEDERAL_URL)
            ?.let { M3uParser.parse(it, onlyCountry = "ru") }
            ?.map { it.copy(group = FEDERAL_GROUP) }
            ?: emptyList()

        // Основной каталог iptv-org
        val main = TorrentHttp.get(PLAYLIST_URL)?.let { M3uParser.parse(it) } ?: emptyList()

        // Дедупликация: федеральные приоритетнее, дальше — по одному на нормализованное название и адрес
        val seen = HashSet<String>()
        val parsed = (federal + main)
            .filter { c -> seen.add(c.url) && seen.add("n:" + dedupName(c.name)) }
            .flatMap { c ->
                // «Animation;Kids» → канал попадает в обе группы
                if (';' in c.group) {
                    c.group.split(';').filter { it.isNotBlank() }.map { g ->
                        c.copy(group = g.trim())
                    }
                } else listOf(c)
            }
            .sortedWith(
                compareBy({ it.group != FEDERAL_GROUP }, { it.group }, { it.name })
            )
        if (parsed.isNotEmpty()) {
            cache = parsed
            cacheTime = System.currentTimeMillis()
        }
        return parsed
    }

    /** Нормализация названия для дедупликации каналов. */
    internal fun dedupName(name: String): String = name
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-я0-9]"), "")

    /** Группы в порядке первого появления + человеческие подписи. */
    fun groups(channels: List<IptvChannel>): List<Pair<String, String>> {
        val seen = LinkedHashMap<String, Int>()
        channels.forEach { c -> seen.merge(c.group, 1, Int::plus) }
        return seen.map { (g, n) -> g to groupLabel(g, n) }
    }

    /** Перевод типовых групп iptv-org на русский (остальные — как есть). */
    fun groupLabel(group: String, count: Int): String {
        val label = when (group.lowercase()) {
            "federal" -> "Федеральные"
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
