package com.torrenttv.app.data

import org.json.JSONObject

/** Канал IPTV-плейлиста. */
data class IptvChannel(
    val name: String,
    val url: String,
    val logoUrl: String = "",
    val group: String = "",
) {
    /** Ключ для избранного — адрес потока (уникален в плейлисте). */
    val key: String get() = url

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("url", url)
        .put("logo", logoUrl)
        .put("group", group)

    companion object {
        fun fromJson(o: JSONObject): IptvChannel = IptvChannel(
            name = o.optString("name", ""),
            url = o.optString("url", ""),
            logoUrl = o.optString("logo", ""),
            group = o.optString("group", ""),
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

    fun parse(text: String): List<IptvChannel> {
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
                        if (BANNED_GROUPS.none { group.lowercase().contains(it) }) {
                            out += IptvChannel(
                                name = p.name,
                                url = line,
                                logoUrl = p.logo,
                                group = group,
                            )
                        }
                    }
                    pending = null
                }
            }
        }
        return out
    }

    internal data class Pending(val name: String, val logo: String, val group: String)

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
        )
    }

    /** Убрать технические хвосты: « (1080p)», « [Not 24/7]», « [Geo-blocked]». */
    internal fun cleanName(name: String): String {
        var s = name.trim()
        while (true) {
            val next = s.replace(Regex("\\s*[\\[(][^\\])]*[\\])]\\s*$"), "").trim()
            if (next == s) break
            s = next
        }
        return s
    }
}
