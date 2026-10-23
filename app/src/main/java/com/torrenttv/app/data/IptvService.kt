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
            // Региональные каналы других регионов (Астрахань 24, Дон 24, …) не нужны
            .filter { c -> !isRegional(c.name) }
            .flatMap { c ->
                // «Animation;Kids» → канал попадает в обе группы
                if (';' in c.group) {
                    c.group.split(';').filter { it.isNotBlank() }.map { g ->
                        c.copy(group = g.trim())
                    }
                } else listOf(c)
            }
            // Сортировка «как положено»: федеральные, универсальные, кино, сериалы…
            .sortedWith(
                compareBy(
                    { groupOrder(it.group) },
                    { it.group.lowercase() },
                    { it.name },
                )
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

    // ---------- Региональный фильтр ----------

    /**
     * Гео-маркеры региональных каналов (города и регионы РФ, кроме Москвы/МО).
     * iptv-org отдаёт названия транслитом («Astrahan 24»), Free-TV — кириллицей,
     * поэтому проверяем оба написания.
     */
    private val REGION_MARKERS = listOf(
        // латиница (как в iptv-org)
        "astrahan", "bashkortostan", "bashkiria", "belgorod", "bryansk", "vladivostok",
        "primorye", "volgograd", "voronezh", "dagestan", "grozny", "chechen",
        "ivanovo", "irkutsk", "kazan", "tatarstan", "kalmykia", "kaluga", "kemerevo",
        "kuzbass", "kostroma", "krasnodar", "kuban", "krasnoyarsk", "kurgan", "kursk",
        "lipetsk", "magadan", "murmansk", "nnovgorod", "novosibirsk", "omsk", "orel",
        "oryol", "orenburg", "penza", "perm", "pskov", "ryazan", "samara", "saratov",
        "sakhalin", "yakutia", "saha", "sakha", "smolensk", "stavropol", "syktyvkar",
        "tver", "tula", "tolyatti", "tomsk", "tula24", "tyumen", "ufa", "khakassia",
        "khanty", "yamal", "chelyabinsk", "chita", "chuvashia", "yamalo", "yaroslavl",
        "enisey", "angarsk", "bratsk", "afontovo", "arkhyz", "arktika", "kamchatka",
        "karelia", "komi", "mariel", "mordovia", "udmurtia", "buratia", "buryatia",
        "altai", "adigea", "ossetia", "kabardino", "karachay", "ingushetia", "tuva",
        "amur24", "amursk", "astrakhan", "don24", "don 24", "vladimir24", "region29",
        // кириллица (как в Free-TV и переведённых названиях)
        "астрахань", "башкортостан", "белгород", "брянск", "владивосток", "приморье",
        "волгоград", "воронеж", "дагестан", "грозный", "иваново", "иркутск", "казань",
        "татарстан", "калмыкия", "калуга", "кемерово", "кузбасс", "кострома",
        "краснодар", "кубань", "красноярск", "курган", "курск", "липецк", "магадан",
        "мурманск", "нижний новгород", "новосибирск", "омск", "орёл", "оренбург",
        "пенза", "пермь", "псков", "рязань", "самара", "саратов", "сахалин", "якутия",
        "смоленск", "ставрополь", "сыктывкар", "тверь", "тула", "тольятти", "томск",
        "тюмень", "уфа", "хакасия", "ямал", "челябинск", "чита", "чувашия",
        "ярославль", "енисей", "ангарск", "братск", "афонтово", "архыз", "арктика",
        "камчатка", "карелия", "коми", "мордва", "удмуртия", "бурятия", "алтай",
        "адыгея", "осетия", "кабардино", "карачай", "ингушетия", "тыва", "амур",
        "ульяновск", "хабаровск", "чукотка", "эвенкия", "норильск", "сургут",
        "таганрог", "балаково", "биробиджан", "магнитогорск", "нижний тагил",
        "дон 24", "донтр", "дон тр",
        // остальные субъекты и крупные города РФ (Москва и Подмосковье НЕ трогаем)
        "воронеж", "оренбург", "ульяновск", "чебоксары", "йошкар-ола",
        "саранск", "новокузнецк", "барнаул", "абакан", "кызыл", "улан-удэ",
        "благовещенск", "якутск", "петропавловск", "южно-сахалинск", "находка",
        "уссурийск", "комсомольск", "воркута", "усинск", "новороссийск",
        "котлас", "череповец", "мичуринск", "елец", "новочеркасск",
        "минеральные воды", "пятигорск", "кисловодск", "невинномысск",
        "кизляр", "хасавюрт", "махачкала", "назрань", "нальчик",
        "анжеро-судженск", "горно-алтайск", "златоуст", "миасс", "копейск",
        "озерск", "троицк", "снежинск", "тобольск", "ишим", "шадринск",
        "нижневартовск", "нефтеюганск", "новый уренгой", "нойбрьск", "когалым",
        "искитим", "карасук", "северск", "стрежевой", "кузнецк", "заречный",
        "энгельс", "балашов", "вольск", "ртищево", "сызрань", "новокуйбышевск",
        "чапаевск", "нижнекамск", "альметьевск", "зеленодольск", "набережные челны",
        "стерлитамак", "салават", "нефтекамск", "белорецк", "ишимбай",
        "березники", "соликамск", "чайковский", "лысьва", "кунгур", "чусовой",
        "кирочепецк", "слободской", "вятские поляны", "котельнич",
        "апатиты", "североморск", "мончегорск", "кандалакша", "новодвинск",
        "петрозаводск", "кондопога", "сегежа", "костомукша", "сортавала",
        "великий новгород", "старая русса", "боровичи", "великие луки",
        "вязьма", "сафоново", "клинцы", "новозыбков", "дятьково", "обнинск",
        "людиново", "малоярославец", "рыбинск", "углич", "нелидово", "торжок",
        "кимры", "новомосковск", "алексин", "щекино", "узловая", "скопин",
        "шуя", "кинешма", "вичуга", "фурманов", "тейково", "муром", "ковров",
        "гусь-хрустальный", "александров", "моршанск", "кирсанов", "грязи",
        "железногорск", "курчатов", "льгов", "старый оскол", "губкин",
        "алексеевка", "лиски", "павловск", "семилуки", "росошь",
        "ростов-на-дону", "батайск", "миллерово", "зерноград",
        "каменск-шахтинский", "армавир", "туапсе", "анапа", "геленджик",
        "славянск-на-кубани", "тимашевск", "белореченск", "ессентуки",
        "будённовск", "волжский", "камышин", "михайловка", "урюпинск",
        "фролово", "знаменск", "элиста", "буйнакск", "грозный", "шали",
        "гудермес", "черкесск", "владикавказ", "беслан", "анадырь",
        // латиница-дополнения (iptv-org)
        "ulyanovsk", "khabarovsk", "novokuznetsk", "abakan", "kyzyl",
        "ulanude", "ulan-ude", "blagoveshchensk", "yakutsk", "petropavlovsk",
        "yuzhno", "nakhodka", "ussuriysk", "komsomolsk", "anadyr", "chukotka",
    )

    /** Региональный канал другого региона? (Москва и МО остаются.) */
    internal fun isRegional(name: String): Boolean {
        val n = name.lowercase().replace('ё', 'е')
        if (n.contains("москв")) return false // Москва 24, Москва. Доверие, Говорит Москва
        return REGION_MARKERS.any { n.contains(it) }
    }

    /** Порядок групп в списке: значимые — первыми, прочие — по алфавиту в конце. */
    internal fun groupOrder(group: String): Int = when (group.lowercase()) {
        FEDERAL_GROUP -> 0
        "general" -> 1
        "movies" -> 2
        "series" -> 3
        "entertainment" -> 4
        "kids", "family" -> 5
        "documentary", "science", "education" -> 6
        "news" -> 7
        "sports" -> 8
        "music" -> 9
        "culture", "classic" -> 10
        "comedy" -> 11
        "lifestyle", "travel", "outdoor", "cooking", "auto" -> 12
        "religious" -> 13
        "business", "legislative" -> 14
        "shop" -> 18
        "undefined", "" -> 19
        else -> 16
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
