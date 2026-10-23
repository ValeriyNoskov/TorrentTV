package com.torrenttv.app.data

import org.json.JSONObject

/** Канал IPTV-плейлиста. */
data class IptvChannel(
    val name: String,
    val url: String,
    val logoUrl: String = "",
    val group: String = "",
    /** Идентификатор канала из плейлиста (tvg-id) — для сопоставления с EPG. */
    val tvgId: String = "",
    /** Эфирный номер канала (tvg-chno), 0 — не задан. Для переключения цифрами. */
    val tvgNo: Int = 0,
) {
    /** Ключ для избранного — адрес потока (уникален в плейлисте). */
    val key: String get() = url

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("url", url)
        .put("logo", logoUrl)
        .put("group", group)
        .put("tvgId", tvgId)
        .put("tvgNo", tvgNo)

    companion object {
        fun fromJson(o: JSONObject): IptvChannel = IptvChannel(
            name = o.optString("name", ""),
            url = o.optString("url", ""),
            logoUrl = o.optString("logo", ""),
            group = o.optString("group", ""),
            tvgId = o.optString("tvgId", ""),
            tvgNo = o.optInt("tvgNo", 0),
        )
    }
}

/**
 * Разбор M3U-плейлиста (формат iptv-org):
 *
 * ```
 * #EXTINF:-1 tvg-id="..." tvg-logo="https://…" group-title="News",Название (1080p)
 * https://…/stream.m3u8
 * ```
 *
 * Чистый парсер для unit-тестов: без Android-зависимостей.
 */
object M3uParser {

    /** Группы, которые не показываем. */
    private val BANNED_GROUPS = listOf("xxx", "adult", "nsfw", "erotic")

    /**
     * Разбор плейлиста.
     * @param onlyCountry оставить каналы только этой страны (tvg-country, регистронезависимо)
     */
    fun parse(text: String, onlyCountry: String? = null): List<IptvChannel> {
        val out = mutableListOf<IptvChannel>()
        val lines = text.lines()
        var pending: Pending? = null

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> pending = parseExtInf(line)
                line.isEmpty() || line.startsWith("#") -> Unit // прочие директивы игнорируем
                else -> {
                    val p = pending
                    if (p != null && line.startsWith("http")) {
                        val group = p.group
                        val countryOk = onlyCountry == null ||
                            p.tvgCountry.equals(onlyCountry, ignoreCase = true)
                        if (countryOk && BANNED_GROUPS.none { group.lowercase().contains(it) }) {
                            out += IptvChannel(
                                name = translateName(p.name),
                                url = line,
                                logoUrl = p.logo,
                                group = group,
                                tvgId = p.tvgId,
                                tvgNo = p.tvgNo,
                            )
                        }
                    }
                    pending = null
                }
            }
        }
        return out
    }

    internal data class Pending(
        val name: String,
        val logo: String,
        val group: String,
        val tvgId: String,
        val tvgNo: Int,
        val tvgCountry: String,
    )

    /** Разбор строки #EXTINF: атрибуты до закрывающей кавычки, название после первой запятой за ними. */
    internal fun parseExtInf(line: String): Pending? {
        val lastQuote = line.lastIndexOf('"')
        val comma = if (lastQuote >= 0) line.indexOf(',', lastQuote) else line.indexOf(',')
        if (comma < 0 || comma == line.length - 1) return null
        val title = cleanName(line.substring(comma + 1).trim())
        if (title.isBlank()) return null

        val attrs = line.substring(0, comma)
        fun attr(name: String): String =
            Regex("$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: ""

        return Pending(
            name = title,
            logo = attr("tvg-logo"),
            group = attr("group-title"),
            tvgId = attr("tvg-id"),
            tvgNo = attr("tvg-chno").trim().toIntOrNull() ?: 0,
            tvgCountry = attr("tvg-country"),
        )
    }

    /** Убрать технические хвосты: « (1080p)», « [Not 24/7]», « [Geo-blocked]», пометки «Ⓢ». */
    internal fun cleanName(name: String): String {
        var s = name.replace("Ⓢ", "").trim()
        while (true) {
            val next = s.replace(Regex("\\s*[\\[(][^\\])]*[\\])]\\s*$"), "").trim()
            if (next == s) break
            s = next
        }
        return s
    }

    // ---------- Перевод названий каналов ----------

    /** Частые названия iptv-org на латинице → русский. Ключ — имя после cleanName, без «(Russia)». */
    private val NAME_MAP = mapOf(
        "channel one" to "Первый канал",
        "ntv" to "НТВ",
        "ntv +1" to "НТВ (+1)",
        "ntv +2" to "НТВ (+2)",
        "ntv +4" to "НТВ (+4)",
        "ntv +7" to "НТВ (+7)",
        "ntv hd" to "НТВ HD",
        "ntv law" to "НТВ Право",
        "ntv series" to "НТВ Сериал",
        "ntv style" to "НТВ Стиль",
        "ntv vitrina" to "НТВ Витрина",
        "ntv-hit" to "НТВ-Хит",
        "tnt" to "ТНТ",
        "tnt4" to "ТНТ4",
        "tnt music" to "ТНТ Music",
        "sts" to "СТС",
        "sts love" to "СТС Love",
        "sts kids" to "СТС Kids",
        "zvezda" to "Звезда",
        "zvezda plus" to "Звезда Плюс",
        "ren tv" to "РЕН ТВ",
        "domashniy" to "Домашний",
        "mir" to "Мир",
        "moy mir" to "Мой Мир",
        "otr" to "ОТР",
        "moskva 24" to "Москва 24",
        "moskva. doveriye" to "Москва. Доверие",
        "govorit moskva" to "Говорит Москва",
        "match! arena" to "Матч! Арена",
        "match! fighter" to "Матч! Боец",
        "match! planet" to "Матч! Планета",
        "match! football" to "Матч! Футбол 1",
        "match! football 2" to "Матч! Футбол 2",
        "match! football 3" to "Матч! Футбол 3",
        "muzyka pervogo" to "Музыка Первого",
        "pobeda" to "Победа",
        "vremya" to "Время",
        "telecafe" to "Телекафе",
        "karusel" to "Карусель",
        "karousel" to "Карусель",
        "current time tv" to "Настоящее Время",
        "rossiya 1 vainah" to "Россия 1 (Вайнах)",
        "perviy kanal" to "Первый канал",
        "russia 1" to "Россия 1",
        "russia 24" to "Россия 24",
        "russia k" to "Россия К",
        "5 kanal" to "Пятый канал",
        "pervyy kanal" to "Первый канал",
    )

    /**
     * Перевести название канала на русский, если оно на латинице и известно нам.
     * Кириллические названия не трогаем.
     */
    internal fun translateName(name: String): String {
        val hasCyrillic = name.any { it.code in 0x0400..0x04FF }
        if (hasCyrillic) return name
        val cleaned = name.replace(Regex("\\s*\\((?:Russia|RU|RUS)\\)", RegexOption.IGNORE_CASE), "").trim()
        return NAME_MAP[cleaned.lowercase()] ?: cleaned
    }
}
