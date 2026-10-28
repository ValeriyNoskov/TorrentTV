package com.torrenttv.app.data

import android.content.Context

/** Канал IPTV: имя для списка, адрес потока (HLS) и группа («Москва», «Федеральные»…). */
data class IptvChannel(
    val name: String,
    val url: String,
    val group: String = "Прочее",
    val logo: String = "",
    /** Разрешение из названия («1080p») — показываем в плитке. */
    val quality: String = "",
)

/**
 * IPTV-каналы: встроенный плейлист (проверенные московские и федеральные)
 * + необязательный свой плейлист из настроек.
 *
 * Почему только они: владелец просил каналы, реально доступные в Москве,
 * плюс русские тематические. Ace Stream и NoxBit не используем — там
 * нужен отдельный клиент, а не HLS.
 */
object Iptv {

    private const val ASSET = "iptv.m3u"
    private const val CACHE = "iptv_cache.m3u"

    /** Разбор m3u: строка #EXTINF + следующая строка с адресом. */
    fun parse(text: String): List<IptvChannel> {
        val out = mutableListOf<IptvChannel>()
        var pendingName: String? = null
        var pendingGroup = "Прочее"
        var pendingLogo = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue
            if (line.startsWith("#EXTINF")) {
                // name — после последней запятой, вне кавычек
                val comma = line.lastIndexOf(',')
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                pendingGroup = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() } ?: "Прочее"
                pendingLogo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
                continue
            }
            if (line.startsWith("#")) continue
            val name = pendingName ?: continue
            // Только реальные потоки: http(s). Пропускаем acestream:// и подобное
            if (!line.startsWith("http")) {
                pendingName = null
                continue
            }
            // Качество выносим из названия: «Первый канал (450p)» → «Первый канал» + 450p
            val quality = Regex("""\((\d{3,4}p|радио)\)""").find(name)?.groupValues?.get(1).orEmpty()
            val cleanName = name.replace(Regex("""\s*\((\d{3,4}p|радио)\)"""), "").trim()
            out += IptvChannel(
                name = cleanName,
                url = line,
                group = pendingGroup,
                logo = pendingLogo,
                quality = quality,
            )
            pendingName = null
        }
        return out.distinctBy { it.name to it.url }
    }

    /** Встроенный плейлист из assets (работает без сети). */
    fun builtIn(context: Context): List<IptvChannel> = runCatching {
        parse(context.assets.open(ASSET).bufferedReader().readText())
    }.getOrDefault(emptyList())

    /**
     * Список каналов: свой плейлист (если настроен и скачался) + встроенный.
     * Свой плейлист кэшируется в файл — при отсутствии сети остаётся прошлый.
     */
    fun load(context: Context, ownUrl: String, allowOwn: Boolean): List<IptvChannel> {
        val builtin = builtIn(context)
        if (!allowOwn || ownUrl.isBlank()) return builtin

        val cacheFile = java.io.File(context.filesDir, CACHE)
        val cached = if (cacheFile.exists()) cacheFile.readText() else ""
        // Свой плейлист качаем не чаще раза в 6 часов: при каждом входе в ТВ
        // это лишний запрос и задержка, а каналы меняются редко
        val cacheFresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < 6 * 3600_000L
        val fresh = if (cacheFresh) null else runCatching { TorrentHttp.get(ownUrl) }.getOrNull()
        val own = when {
            !fresh.isNullOrBlank() -> {
                runCatching { writeAtomic(cacheFile, fresh) }
                parse(fresh)
            }
            cached.isNotBlank() -> parse(cached)
            else -> emptyList()
        }
        AppLog.log("tv", "плейлист: свой ${own.size} каналов (${if (fresh.isNullOrBlank()) "из кэша" else "скачан"}), встроенный ${builtin.size}")
        return (own + builtin).distinctBy { it.name to it.url }
    }

    /** Группы в порядке появления, с числом каналов. */
    fun groups(channels: List<IptvChannel>): List<Pair<String, Int>> =
        channels.groupingBy { it.group }.eachCount().toList()
}
