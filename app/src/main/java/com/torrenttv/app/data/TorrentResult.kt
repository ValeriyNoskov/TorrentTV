package com.torrenttv.app.data

/** Результат автопоиска торрентов (одна раздача). */
data class TorrentResult(
    val name: String,
    val infoHash: String,
    val seeds: Int,
    val sizeBytes: Long,
    val provider: String = "",
    val resolution: String = "",
    /**
     * Прямая ссылка на .torrent (частные трекеры). Если заполнена, hash
     * неизвестен заранее — .torrent скачивается с cookie и hash считается
     * локально при выборе раздачи.
     */
    val torrentUrl: String = "",
) {
    /** Частный трекер: hash станет известен только после скачивания .torrent. */
    val isPrivate: Boolean get() = torrentUrl.isNotBlank()

    val sizeMb: Int
        get() = (sizeBytes / 1024 / 1024).toInt()

    fun sizeLabel(): String =
        if (sizeMb >= 1024) "%.1f ГБ".format(sizeMb / 1024f) else "$sizeMb МБ"
}

/**
 * Разбор качества релиза из названия раздачи:
 * 2160p (4K) → 1080p → 720p → SD (480p/DVD/TS/CAM).
 */
object ReleaseQuality {

    val KNOWN = listOf("2160p", "1080p", "720p", "SD")

    private val patterns = listOf(
        Regex("(?i)\\b2160p?\\b|\\b4k\\b|\\buhd\\b|\\b2160\\b") to "2160p",
        Regex("(?i)\\b1080[pi]\\b|\\b1080\\b") to "1080p",
        Regex("(?i)\\b720p?\\b") to "720p",
        Regex("(?i)\\b480p?\\b|\\bdvd|\\bsat|\\bts\\b|\\bcam|\\bhdtv") to "SD",
    )

    /** Определить качество по названию раздачи; пусто, если ничего не распознано. */
    fun parse(name: String): String {
        for ((re, res) in patterns) {
            if (re.containsMatchIn(name)) return res
        }
        return ""
    }
}
