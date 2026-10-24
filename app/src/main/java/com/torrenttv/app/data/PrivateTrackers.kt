package com.torrenttv.app.data

import com.torrenttv.app.torrent.TorrentEngine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Прямая поддержка частных трекеров (без Jackett).
 *
 * Авторизация — строка Cookie из браузера (F12 → Network → заголовок Cookie),
 * пользователь вставляет её в настройках. Такой механизм не зависит от
 * внутренностей логина каждого сайта: поиск и скачивание .torrent идут
 * с этими куками.
 *
 * Схема работы:
 *  1) поиск — HTML страницы tracker.php/browse.php, tolerant-парсер строк;
 *  2) результат не содержит info_hash — он появится только из .torrent;
 *  3) при выборе раздачи .torrent скачивается с куками, hash считается
 *     локально (bdecode через jlibtorrent), файл сохраняется как источник.
 */
object PrivateTrackers {

    const val RUTRACKER = "rutracker"
    const val KINOZAL = "kinozal"
    const val NNMCLUB = "nnmclub"

    /** Профили трекеров: id, отображаемое имя, домен (для подсказок). */
    val ALL: List<Triple<String, String, String>> = listOf(
        Triple(RUTRACKER, "RuTracker", "rutracker.org"),
        Triple(KINOZAL, "Kinozal", "kinozal.tv"),
        Triple(NNMCLUB, "NNM-Club", "nnmclub.to"),
    )

    /** Учётные данные одного трекера (cookie-строка из браузера). */
    data class TrackerAuth(
        val id: String,
        val title: String,
        val cookie: String,
    ) {
        val isConfigured: Boolean get() = cookie.isNotBlank()
    }

    private val CP1251: Charset = Charset.forName("windows-1251")

    /** Общие заголовки: без браузерного User-Agent многие трекеры отдают заглушку. */
    private fun headers(auth: TrackerAuth, referer: String): Map<String, String> = buildMap {
        put("Cookie", auth.cookie.trim().removePrefix("Cookie:").trim())
        put(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        )
        put("Referer", referer)
    }

    /** Поиск по одному трекеру (HTML-парсер соответствующего сайта). */
    fun search(auth: TrackerAuth, query: String): List<TorrentResult> = try {
        when (auth.id) {
            RUTRACKER -> rutracker(auth, query)
            KINOZAL -> kinozal(auth, query)
            NNMCLUB -> nnmclub(auth, query)
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ---------- RuTracker ----------

    private fun rutracker(auth: TrackerAuth, query: String): List<TorrentResult> {
        // encode(String, Charset) только с API 33 (minSdk 29) — строковая
        // перегрузка доступна с API 1
        val q = URLEncoder.encode(query, "windows-1251")
        val html = TorrentHttp.get("https://rutracker.org/forum/tracker.php?nm=$q", headers(auth, "https://rutracker.org/forum/")) ?: return emptyList()
        return parseRutracker(html)
    }

    /** Строки выдачи tracker.php: hl-tr → viewtopic.php?t=ID, seedmed, размер. */
    fun parseRutracker(html: String): List<TorrentResult> {
        val out = mutableListOf<TorrentResult>()
        for (row in html.split("<tr class=\"hl-tr\">").drop(1)) {
            val body = row.substringBefore("</tr>")

            val id = Regex("""viewtopic\.php\?t=(\d+)""").find(body)?.groupValues?.get(1) ?: continue
            val title = Regex("""viewtopic\.php\?t=$id"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim() ?: continue

            val seeds = Regex("""seed\w*"[^>]*>(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val size = sizeFromText(body) ?: 0L

            out += TorrentResult(
                name = title,
                infoHash = "",
                seeds = seeds,
                sizeBytes = size,
                provider = RUTRACKER,
                resolution = ReleaseQuality.parse(title),
                torrentUrl = "https://rutracker.org/forum/dl.php?t=$id",
            )
        }
        return out
    }

    // ---------- Kinozal ----------

    private fun kinozal(auth: TrackerAuth, query: String): List<TorrentResult> {
        // encode(String, Charset) только с API 33 (minSdk 29) — строковая
        // перегрузка доступна с API 1
        val q = URLEncoder.encode(query, "windows-1251")
        val html = TorrentHttp.get("https://kinozal.tv/browse.php?s=$q&g=0&c=0", headers(auth, "https://kinozal.tv/")) ?: return emptyList()
        return parseKinozal(html)
    }

    /** Строки выдачи browse.php: td.nam → details.php?id=, td.sl — сиды, td.s — размер. */
    fun parseKinozal(html: String): List<TorrentResult> {
        val out = mutableListOf<TorrentResult>()
        for (row in html.split("<td class=\"nam\">").drop(1)) {
            val body = row.substringBefore("</tr>")

            val id = Regex("""details\.php\?id=(\d+)""").find(body)?.groupValues?.get(1) ?: continue
            val title = Regex("""details\.php\?id=$id"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim() ?: continue

            val seeds = Regex("""class="sl"[^>]*>(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val size = sizeFromText(body) ?: 0L

            out += TorrentResult(
                name = title,
                infoHash = "",
                seeds = seeds,
                sizeBytes = size,
                provider = KINOZAL,
                resolution = ReleaseQuality.parse(title),
                torrentUrl = "https://kinozal.tv/download.php?id=$id",
            )
        }
        return out
    }

    // ---------- NNM-Club ----------

    private fun nnmclub(auth: TrackerAuth, query: String): List<TorrentResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = TorrentHttp.get("https://nnmclub.to/forum/tracker.php?nm=$q", headers(auth, "https://nnmclub.to/forum/")) ?: return emptyList()
        return parseNnmclub(html)
    }

    /** Строки выдачи tracker.php nnm-club: viewtopic.php?t=ID, download.php?id=ID. */
    fun parseNnmclub(html: String): List<TorrentResult> {
        val out = mutableListOf<TorrentResult>()
        for (row in html.split("<tr>").drop(1)) {
            val body = row.substringBefore("</tr>")
            if (!body.contains("viewtopic.php")) continue

            val id = Regex("""viewtopic\.php\?t=(\d+)""").find(body)?.groupValues?.get(1) ?: continue
            val title = Regex("""viewtopic\.php\?t=$id"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim() ?: continue

            val seeds = Regex("""seed\w*"[^>]*>(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val size = sizeFromText(body) ?: 0L

            out += TorrentResult(
                name = title,
                infoHash = "",
                seeds = seeds,
                sizeBytes = size,
                provider = NNMCLUB,
                resolution = ReleaseQuality.parse(title),
                torrentUrl = "https://nnmclub.to/forum/download.php?id=$id",
            )
        }
        return out
    }

    // ---------- Общее ----------

    /** Размер из текста строки: «4.35 GB», «780 MB», «2,1 ГБ» и т.п. (первое вхождение). */
    private val SIZE_RE = Regex(
        """(\d+(?:[.,]\d+)?)\s*(TB|ТБ|GB|ГБ|MB|МБ|KB|КБ)""",
        RegexOption.IGNORE_CASE,
    )

    fun sizeFromText(text: String): Long? {
        val m = SIZE_RE.find(text) ?: return null
        val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val k = when (m.groupValues[2].uppercase().substring(0, 2)) {
            "TB", "ТБ" -> 1024L * 1024 * 1024 * 1024
            "GB", "ГБ" -> 1024L * 1024 * 1024
            "MB", "МБ" -> 1024L * 1024
            else -> 1024L
        }
        return (num * k).toLong()
    }

    /**
     * Скачать .torrent частной раздачи с куками, посчитать info_hash и
     * сохранить файл. Возвращает путь к файлу-источнику или null.
     */
    fun downloadTorrent(r: TorrentResult, auth: TrackerAuth, destDir: File): String? {
        if (!r.isPrivate || !auth.isConfigured) return null
        val referer = when (r.provider) {
            RUTRACKER -> "https://rutracker.org/forum/"
            KINOZAL -> "https://kinozal.tv/"
            NNMCLUB -> "https://nnmclub.to/forum/"
            else -> ""
        }
        val bytes = TorrentHttp.getBytes(r.torrentUrl, headers(auth, referer)) ?: return null
        // bencode-словарь всегда начинается с 'd'; иначе трекер отдал HTML (логин/ошибку)
        if (bytes.size < 10 || bytes[0] != 'd'.code.toByte()) return null
        val hash = TorrentEngine.infoHashV1Hex(bytes) ?: return null
        destDir.mkdirs()
        val file = File(destDir, "$hash.torrent")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    // ---------- Вход логином и паролем (без браузера) ----------

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** POST формы: вернуть склеенную куку ответа (Set-Cookie → "k=v; k2=v2"). */
    private fun postForm(url: String, form: Map<String, String>): String? {
        val client = TorrentHttp.client.newBuilder()
            .followRedirects(false)
            .build()
        val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val req = okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", UA)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code !in 300..399) return null
                val cookies = resp.headers("Set-Cookie")
                val joined = cookies.joinToString("; ") { c -> c.substringBefore(';') }
                joined.ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Войти на трекер логином/паролем, вернуть cookie-строку (её сохраняем как
     * обычный Cookie из настроек). null — неверный логин/капча/сеть.
     */
    fun login(id: String, user: String, pass: String): String? = try {
        when (id) {
            RUTRACKER -> postForm(
                "https://rutracker.org/forum/login.php",
                mapOf(
                    "login_username" to user,
                    "login_password" to pass,
                    "login" to "Вход",
                    "redirect" to "index.php",
                ),
            )?.takeIf { it.contains("bb_session") }
            KINOZAL -> postForm(
                "https://kinozal.tv/takelogin.php",
                mapOf("username" to user, "password" to pass, "returnto" to ""),
            )?.takeIf { it.contains("uid") }
            NNMCLUB -> {
                // сначала GET-страница входа: из action формы достаём sid
                val page = TorrentHttp.get("https://nnmclub.to/forums/ucp.php?mode=login")
                if (page == null) null
                else {
                    val sid = Regex("sid=([a-f0-9]{32})").find(page)?.groupValues?.get(1) ?: ""
                    postForm(
                        "https://nnmclub.to/forums/ucp.php?mode=login" + if (sid.isNotBlank()) "?sid=$sid" else "",
                        buildMap {
                            put("username", user)
                            put("password", pass)
                            put("autologin", "1")
                            put("login", "Вход")
                            put("redirect", "index.php")
                            if (sid.isNotBlank()) put("sid", sid)
                        },
                    )?.takeIf { it.contains("sid") }
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
