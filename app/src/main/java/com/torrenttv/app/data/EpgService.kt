package com.torrenttv.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Телепрограмма (EPG) для IPTV из XMLTV-источника.
 *
 * Источник по умолчанию — teleguide.info (только RU-каналы, ~4 МБ gz).
 * URL можно заменить в настройках (любой XMLTV / epg.xml.gz).
 *
 * Как работает:
 *  1) потоково разбираем GZIP-XML (XmlPullParser, без чтения всего файла в память);
 *  2) берём только передачи для каналов нашего плейлиста: сопоставление
 *     по tvg-id, при его отсутствии — по нормализованному названию;
 *  3) окно программы: −3 ч … +48 ч от текущего момента;
 *  4) кэш на диске (epg_cache.json) — скачивание раз в 6 часов.
 */
object EpgService {

    const val DEFAULT_URL = "http://teleguide.info/download/new3/xmltv.xml.gz"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val WINDOW_BEFORE_MS = 3 * 60 * 60 * 1000L
    private const val WINDOW_AFTER_MS = 48 * 60 * 60 * 1000L

    /** Передача в программе. */
    data class Programme(val title: String, val startMs: Long, val stopMs: Long)

    @Volatile private var memory: Map<String, List<Programme>>? = null

    private fun cacheFile(context: Context) = File(context.filesDir, "epg_cache.json")

    fun cachedAt(context: Context): Long =
        if (cacheFile(context).exists()) cacheFile(context).lastModified() else 0L

    /**
     * Загрузить программу (кэш из памяти → файл → сеть). Блокирующий — из IO.
     * @return число каналов с программой (0 — не удалось)
     */
    fun load(context: Context, channels: List<IptvChannel>): Int {
        memory?.let { return it.size }

        val file = cacheFile(context)
        if (file.exists()) {
            val fromDisk = readCache(file)
            if (fromDisk != null) {
                memory = fromDisk
                return fromDisk.size
            }
        }
        return refresh(context, channels)
    }

    /** Скачать свежую программу; вернуть число каналов с программой. */
    fun refresh(context: Context, channels: List<IptvChannel>): Int {
        val url = LocalStore(context).epgUrl().ifBlank { DEFAULT_URL }
        // сетевые/XML-ошибки не должны ронять приложение
        val progs = runCatching { downloadAndParse(url, channels) }.getOrDefault(emptyMap())
        if (progs.isNotEmpty()) {
            memory = progs
            writeCache(cacheFile(context), progs)
        }
        return progs.size
    }

    /** Передача «сейчас» для канала (null — нет данных). */
    fun now(channel: IptvChannel, nowMs: Long = System.currentTimeMillis()): Programme? =
        forChannel(channel)?.firstOrNull { nowMs in it.startMs until it.stopMs }

    /** Следующая передача после текущей. */
    fun next(channel: IptvChannel, nowMs: Long = System.currentTimeMillis()): Programme? =
        forChannel(channel)?.firstOrNull { it.startMs > nowMs }

    private fun forChannel(channel: IptvChannel): List<Programme>? =
        memory?.get(channel.key)

    // ---------- Сеть и разбор ----------

    fun downloadAndParse(url: String, channels: List<IptvChannel>): Map<String, List<Programme>> {
        val request = okhttp3.Request.Builder().url(url).build()
        val response = TorrentHttp.client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val stream = resp.body?.byteStream() ?: return emptyMap()
            GZIPInputStream(stream, 64 * 1024).use { gz ->
                return parseXmltv(gz, channels, System.currentTimeMillis())
            }
        }
    }

    /**
     * Потоковый разбор XMLTV-потока: только передачи каналов из списка.
     * Чистая функция — покрыта unit-тестами.
     */
    fun parseXmltv(
        xml: java.io.InputStream,
        channels: List<IptvChannel>,
        nowMs: Long,
    ): Map<String, List<Programme>> {
        // Сопоставление EPG-id и нормализованных названий → ключ канала
        val byId = HashMap<String, String>()
        val byName = HashMap<String, String>()
        for (ch in channels) {
            if (ch.tvgId.isNotBlank()) byId[ch.tvgId.lowercase()] = ch.key
            val n = normalizeName(ch.name)
            if (n.isNotBlank() && n !in byName) byName[n] = ch.key
        }

        val from = nowMs - WINDOW_BEFORE_MS
        val to = nowMs + WINDOW_AFTER_MS
        val out = HashMap<String, MutableList<Programme>>()

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml, null)

        var channelKey: String? = null // ключ канала текущего <programme>
        var curXmlChannelId: String? = null // id канала внутри <channel>
        var inDispName = false
        val resolvedIds = HashMap<String, String>() // id XMLTV → ключ нашего канала (по display-name)
        var inTitle = false
        var title = StringBuilder()
        var pStart = 0L
        var pStop = 0L

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        curXmlChannelId = parser.getAttributeValue(null, "id")?.lowercase()
                        inDispName = true
                    }

                    "programme" -> {
                        val rawChannel = parser.getAttributeValue(null, "channel") ?: ""
                        val key = byId[rawChannel.lowercase()]
                            ?: resolvedIds[rawChannel.lowercase()]
                            ?: byName[normalizeName(rawChannel)]
                        channelKey = key
                        pStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                        pStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                        title = StringBuilder()
                    }

                    "title" -> if (channelKey != null && pStart in from..to) inTitle = true
                }

                XmlPullParser.TEXT -> when {
                    inDispName -> {
                        // display-name канала: сопоставляем по нормализованному названию
                        val id = curXmlChannelId
                        if (id != null && id !in resolvedIds) {
                            byName[normalizeName(parser.text)]?.let { resolvedIds[id] = it }
                        }
                    }
                    inTitle -> title.append(parser.text)
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> inDispName = false
                    "channel" -> curXmlChannelId = null
                    "title" -> inTitle = false
                    "programme" -> {
                        val key = channelKey
                        val t = title.toString().trim()
                        if (key != null && t.isNotBlank() && pStart in from..to) {
                            out.getOrPut(key) { mutableListOf() } +=
                                Programme(t, pStart, if (pStop > pStart) pStop else pStart)
                        }
                        channelKey = null
                    }
                }
            }
            parser.next()
        }

        return out.mapValues { (_, list) -> list.sortedBy { it.startMs } }
    }

    /** Время XMLTV: «20261023120000 +0300» (мс epoch) или 0. */
    fun parseXmltvTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val m = Regex("""(\d{14})(?:\s*([+-])(\d{2})(\d{2}))?""").find(raw) ?: return 0L
        val utc = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(m.groupValues[1])?.time ?: return 0L
        val sign = if (m.groupValues[2] == "-") -1L else 1L
        val hh = m.groupValues[3].ifEmpty { "0" }.toLong()
        val mm = m.groupValues[4].ifEmpty { "0" }.toLong()
        val offsetMs = (hh * 3600 + mm * 60) * 1000
        return utc - sign * offsetMs
    }

    /** Нормализация названия для сопоставления: нижний регистр, без «ё», пунктуации и хвостов. */
    fun normalizeName(name: String): String = name
        .replace(Regex("\\s*[\\[(][^\\])]*[\\])]\\s*$"), "") // « (1080p)», « [Not 24/7]»
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-я0-9]"), "")

    // ---------- Кэш на диске ----------

    private fun writeCache(file: File, progs: Map<String, List<Programme>>) {
        runCatching {
            val root = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("progs", JSONObject().apply {
                    progs.forEach { (key, list) ->
                        put(key, JSONArray().apply {
                            list.take(60).forEach { p ->
                                put(JSONObject().put("t", p.title).put("s", p.startMs).put("e", p.stopMs))
                            }
                        })
                    }
                })
            file.writeText(root.toString())
        }
    }

    private fun readCache(file: File): Map<String, List<Programme>>? = runCatching {
        // устаревший кэш бесполезен
        if (System.currentTimeMillis() - file.lastModified() > TTL_MS) return@runCatching null
        val root = JSONObject(file.readText())
        val progsJson = root.getJSONObject("progs")
        val out = HashMap<String, List<Programme>>()
        val keys = progsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = progsJson.getJSONArray(key)
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Programme(o.optString("t"), o.optLong("s"), o.optLong("e"))
            }
            if (list.isNotEmpty()) out[key] = list
        }
        out.ifEmpty { null }
    }.getOrNull()
}
