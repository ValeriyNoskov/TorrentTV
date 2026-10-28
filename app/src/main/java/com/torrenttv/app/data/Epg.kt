package com.torrenttv.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Телепрограмма (EPG) из открытого источника teleguide.info (XMLTV).
 *
 * Файл большой (~42 МБ в распакованном виде, 4.4 МБ gzip), поэтому:
 *  - качаем и разбираем потоково, в память целиком не читаем;
 *  - оставляем только программы нужных нам каналов и только на ±сутки;
 *  - результат кладём в компактный JSON-кэш (сотни КБ) и обновляем раз в 12 ч.
 *
 * Если сети нет — работает прошлый кэш, каналы продолжают играть.
 */
object Epg {

    private const val SOURCE = "https://www.teleguide.info/download/new3/xmltv.xml.gz"
    private const val CACHE = "epg.json"
    private const val MAX_AGE_MS = 12 * 3600_000L

    /** Одна передача: начало/конец в миллисекундах эпохи. */
    data class Program(val start: Long, val end: Long, val title: String) {
        fun isNow(now: Long): Boolean = now in start until end
    }

    /** Ключ сопоставления: без регистра, «ё», пробелов, дефисов и доменов. */
    fun normalize(name: String): String {
        var s = name.lowercase().replace('ё', 'е')
        s = s.replace(Regex("""\([^)]*\)"""), " ")       // «(1080p)»
        s = s.replace(Regex("""\b(hd|fullhd|uhd|4k)\b"""), " ")
        s = s.replace(Regex("""\.(ru|com|tv)\b"""), " ")
        s = s.replace(Regex("""[^a-zа-я0-9]+"""), "")
        // «Спас ТВ» и «Спас» — один канал; «ТВ Центр» → «центр»
        return s.removeSuffix("тв").ifBlank { s }
    }

    /**
     * Программа по каналам: ключ — нормализованное имя канала.
     * [force] — обновить, даже если кэш свежий (кнопка «Обновить программу»).
     */
    fun load(context: Context, channelNames: Collection<String>, force: Boolean = false): Map<String, List<Program>> {
        val file = File(context.filesDir, CACHE)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (!force && fresh) {
            readCache(file)?.let { AppLog.log("tv", "программа: из кэша, каналов ${it.size}"); return it }
        }
        val wanted = channelNames.associateBy { normalize(it) }
        val parsed = runCatching { downloadAndParse(wanted.keys) }.getOrElse { e ->
            AppLog.log("tv", "программа: ошибка загрузки ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (parsed != null && parsed.isNotEmpty()) {
            writeCache(file, parsed)
            AppLog.log("tv", "программа: обновлена, каналов ${parsed.size}, передач ${parsed.values.sumOf { it.size }}")
            return parsed
        }
        return readCache(file) ?: emptyMap()
    }

    /** Программа канала с учётом вариантов написания («Москва-24» ↔ «Москва 24»). */
    fun forChannel(programs: Map<String, List<Program>>, channelName: String): List<Program> {
        val key = normalize(channelName)
        programs[key]?.let { return it }
        // Мягкое совпадение: один ключ — начало другого («360» ↔ «360ru»)
        val alt = programs.keys.firstOrNull { k ->
            k.length >= 3 && key.length >= 3 && (k.startsWith(key) || key.startsWith(k))
        }
        return alt?.let { programs[it] } ?: emptyList()
    }

    // ---------- Загрузка и разбор ----------

    private fun downloadAndParse(wanted: Set<String>): Map<String, MutableList<Program>> {
        // Ручное следование редиректам: HttpURLConnection не ходит за
        // кросс-доменными 301 (teleguide.info → 81.p.giex.ru) — без этого
        // получаем HTML-заглушку и на ТВ остаётся старая программа
        var conn: HttpURLConnection? = null
        var stream: InputStream? = null
        var url = SOURCE
        try {
        repeat(3) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
            }
            c.connect()
            val loc = if (c.responseCode in 300..399) c.getHeaderField("Location") else null
            if (loc.isNullOrBlank()) {
                conn = c
                return@repeat
            }
            url = URL(URL(url), loc).toString()
            c.disconnect()
        }
        val c = conn ?: error("HTTP: не дошли до файла за 3 перехода")
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        val raw: InputStream = c.inputStream
        stream = if (SOURCE.endsWith(".gz")) GZIPInputStream(raw, 64 * 1024) else raw

        val now = System.currentTimeMillis()
        val from = now - 12 * 3600_000L       // вчерашние вечерние передачи ещё нужны
        val to = now + 3 * 86_400_000L

        val channelNames = mutableMapOf<String, String>()   // id → нормализованное имя
        val out = mutableMapOf<String, MutableList<Program>>()

        val parser = android.util.Xml.newPullParser()
        parser.setInput(stream, null)

        var id = ""
        var display = ""
        var start = 0L
        var stop = 0L
        var title = ""
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> { id = parser.getAttributeValue(null, "id").orEmpty(); display = "" }
                    "display-name" -> if (id.isNotEmpty()) display = parser.nextText()
                    "programme" -> {
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = ""
                        id = parser.getAttributeValue(null, "channel").orEmpty()
                    }
                    "title" -> if (start > 0) title = parser.nextText()
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        val norm = normalize(display)
                        if (norm.isNotEmpty()) channelNames[id] = norm
                    }
                    "programme" -> {
                        val norm = channelNames[id]
                        val relevant = start in from..to && stop > start && title.isNotBlank()
                        if (norm != null && norm in wanted && relevant) {
                            out.getOrPut(norm) { mutableListOf() } += Program(start, stop, title)
                        }
                        start = 0
                    }
                }
            }
            event = parser.next()
        }
        out.values.forEach { list -> list.sortBy { it.start } }
        return out
        } finally {
            // Раньше при ошибке сети/парсинга stream и conn оставались открытыми
            runCatching { stream?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    /** «20261024180000 +0300» → epoch ms. */
    private fun parseTime(v: String?): Long {
        if (v.isNullOrBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(v.trim())?.time ?: 0L
        }.getOrDefault(0L)
    }

    // ---------- Кэш ----------

    private fun writeCache(file: File, data: Map<String, List<Program>>) {
        val ch = JSONObject()
        for ((k, list) in data) {
            val arr = JSONArray()
            list.forEach { p -> arr.put(JSONArray().put(p.start).put(p.end).put(p.title)) }
            ch.put(k, arr)
        }
        runCatching {
            writeAtomic(file, JSONObject().put("savedAt", System.currentTimeMillis()).put("channels", ch).toString())
        }
    }

    private fun readCache(file: File): Map<String, List<Program>>? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val ch = root.optJSONObject("channels") ?: return null
        val out = mutableMapOf<String, List<Program>>()
        for (key in ch.keys()) {
            val arr = ch.optJSONArray(key) ?: continue
            val list = ArrayList<Program>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                list += Program(p.optLong(0), p.optLong(1), p.optString(2))
            }
            if (list.isNotEmpty()) out[key] = list
        }
        out
    }.getOrNull()
}
