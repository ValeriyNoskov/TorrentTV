package com.torrenttv.app.data

import java.security.MessageDigest

/**
 * Извлечение magnet-ссылки из содержимого .torrent-файла (bencode):
 * info-hash = SHA-1 сырого словаря «info», плюс имя и размер раздачи.
 * Позволяет смотреть раздачу, скачав .torrent в браузере (например, с
 * rutracker.ru), без входа в закрытые трекеры.
 */
object TorrentFileParser {

    data class Info(
        val hash: String,      // 40 hex-символов
        val name: String,
        val lengthBytes: Long,
    )

    fun parse(bytes: ByteArray): Info? = runCatching {
        // ISO-8859-1: отображение байт→символы 1:1, позиции строк = позиции байт
        val s = String(bytes, Charsets.ISO_8859_1)
        val idx = s.indexOf("4:info")
        if (idx < 0) return@runCatching null
        val start = idx + "4:info".length
        if (start >= s.length || s[start] != 'd') return@runCatching null
        val end = skipElement(s, start)          // индекс СЛЕДУЮЩИЙ после 'e' словаря info
        if (end <= start) return@runCatching null

        val md = MessageDigest.getInstance("SHA-1")
        md.update(bytes, start, end - start)
        val hash = md.digest().joinToString("") { "%02x".format(it) }

        val info = s.substring(start, end)
        val name = Regex("4:name(\\d+):").find(info)?.let { m ->
            val len = m.groupValues[1].toInt()
            val p = m.range.last + 1
            if (p + len <= info.length) info.substring(p, p + len) else ""
        } ?: ""
        val length = Regex("6:lengthi(\\d+)e").find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        if (hash.length != 40) null else Info(hash, name, length)
    }.getOrNull()

    /** Magnet для движка: стандартные публичные трекеры — как у apibay. */
    fun toMagnet(info: Info): String {
        val dn = java.net.URLEncoder.encode(info.name.ifBlank { "torrent" }, "UTF-8")
        val tr = TorrentSearch.TRACKERS.joinToString("") { "&tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:${info.hash}&dn=$dn$tr"
    }

    /** Конец bencode-элемента, начинающегося на позиции i (возвращает индекс после него). */
    private fun skipElement(s: String, i: Int): Int {
        var j = i
        when (s[j]) {
            'i' -> {
                while (j < s.length && s[j] != 'e') j++
                return if (j < s.length) j + 1 else -1
            }
            'l', 'd' -> {
                j++
                while (j < s.length && s[j] != 'e') {
                    j = skipElement(s, j)
                    if (j < 0) return -1
                }
                return if (j < s.length) j + 1 else -1
            }
            else -> {
                // <десятичное>:<байты>
                val colon = s.indexOf(':', j)
                if (colon < 0) return -1
                val len = s.substring(j, colon).toIntOrNull() ?: return -1
                return colon + 1 + len
            }
        }
    }
}
