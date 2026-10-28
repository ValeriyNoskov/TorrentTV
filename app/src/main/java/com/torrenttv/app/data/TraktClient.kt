package com.torrenttv.app.data

import android.net.Uri
import com.torrenttv.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Клиент Trakt (trakt.tv) — запасной источник статуса сериала: «идёт/завершён»,
 * следующая/последняя серия с датой выхода. Нужен потому, что основной источник
 * TMDB заблокирован в РФ и работает только с VPN, а данные Trakt доступны напрямую.
 * Ключ задаётся в gradle.properties: traktClientId=... (идёт в заголовок trakt-api-key).
 * Данные публичные — OAuth не нужен. Все методы блокирующие — вызывать из фонового потока.
 */
object TraktClient {

    private const val BASE = "https://api.trakt.tv"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val clientId: String
        get() = BuildConfig.TRAKT_CLIENT_ID

    /** Есть ли настроенный ключ — чтобы не ходить в сеть впустую. */
    val isConfigured: Boolean
        get() = clientId.isNotBlank()

    private fun raw(path: String): String? {
        if (!isConfigured) return null
        val url = BASE + path
        // Кеш метаданных общий для всех источников: свежий — без сети,
        // устаревший — при сбое (конвенция KinopoiskClient/TmdbClient)
        KinopoiskClient.sharedFresh(url)?.let { return it }
        return try {
            ApiUsage.traktRequest() // расход лимита — для счётчиков в настройках
            http.newCall(
                Request.Builder().url(url)
                    .header("trakt-api-key", clientId)
                    .header("trakt-api-version", "2")
                    .build(),
            ).execute().use { resp ->
                val body = if (resp.isSuccessful) resp.body?.string() else null
                if (body != null) {
                    KinopoiskClient.sharedPut(url, body)
                    body
                } else {
                    KinopoiskClient.sharedStale(url)
                }
            }
        } catch (e: Exception) {
            KinopoiskClient.sharedStale(url)
        }
    }

    private fun obj(path: String): JSONObject? =
        raw(path)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun arr(path: String): JSONArray? =
        raw(path)?.let { runCatching { JSONArray(it) }.getOrNull() }

    /**
     * Статус сериала в том же формате, что TmdbClient.tvStatus, —
     * чтобы экран деталей не различал источники.
     */
    fun tvStatus(title: String, year: String, country: String = ""): TmdbClient.TvStatus? {
        if (title.isBlank()) return null
        val slug = findShow(title, year, country) ?: return null
        return statusBySlug(slug)
    }

    /** Поиск сериала по названию (+год, +страна) — проверка совпадения как в TmdbClient.tvStatus. */
    private fun findShow(title: String, year: String, country: String = ""): String? {
        // Подвязка: тайтл, найденный однажды, кешируется локально — дальше
        // без поисковых запросов к Trakt (лимит 1000/ч общий на Client ID)
        val cacheKey = "${title.lowercase().trim()}|$year"
        slugCache[cacheKey]?.let {
            com.torrenttv.app.data.AppLog.log("trakt", "«$title»: подвязка из кеша → $it")
            return it
        }
        // extended=full — нужен country кандидатов прямо в выдаче поиска:
        // «Титаны» есть и как шоу ТНТ (ru, 2024), и как сериал Netflix (us, 2018)
        val json = arr("/search/show?query=" + URLEncoder.encode(title, "UTF-8") + "&extended=full")
            ?: return null
        val want = TitleMatch.normalize(title)
        val wantCountry = countryCode(country)
        var bestSlug = ""
        var bestScore = 0
        for (i in 0 until minOf(json.length(), 5)) {
            val o = json.optJSONObject(i)?.optJSONObject("show") ?: continue
            val name = TitleMatch.normalize(
                o.optString("title", "").ifBlank { o.optJSONObject("ids")?.optString("slug", "") ?: "" },
            )
            if (name.isBlank()) continue
            var score = when {
                name == want -> 100
                name.startsWith(want) || want.startsWith(name) -> 70
                else -> 0
            }
            val candYear = o.optInt("year", 0).toString()
            if (year.isNotBlank() && candYear == year) score += 40
            // Страна: русская карточка → русское шоу, американская → американский сериал
            val candCountry = o.optString("country", "")
            if (wantCountry.isNotBlank() && candCountry == wantCountry) score += 30
            if (score > bestScore) {
                bestScore = score
                bestSlug = o.optJSONObject("ids")?.optString("slug", "") ?: ""
            }
        }
        // Строгий порог: как у TMDB — не рискуем выдать чужой сериал.
        // НО: Trakt ищет и по русским алиасам («Гангстерленд» → MobLand),
        // при этом title результата — латиницей. Если точного совпадения нет,
        // доверяем первому результату с совпавшим годом (или страной, если
        // год карточки неизвестен) — иначе русский каталог вообще не матчится
        if (bestScore < 100 && json.length() > 0) {
            val first = json.optJSONObject(0)?.optJSONObject("show") ?: return null.also {
                com.torrenttv.app.data.AppLog.log("trakt", "«$title» (год $year): поиск пуст — сериала нет")
            }
            val y = first.optInt("year", 0).toString()
            val c = first.optString("country", "")
            val yearOk = year.isNotBlank() && y == year
            val countryOk = year.isBlank() && wantCountry.isNotBlank() && c == wantCountry
            if (yearOk || countryOk) {
                val slug = first.optJSONObject("ids")?.optString("slug", "").takeUnless { it.isNullOrBlank() }
                if (slug != null) {
                    com.torrenttv.app.data.AppLog.log(
                        "trakt",
                        "«$title»: точного совпадения нет — принят первый результат по алиасу: $slug " +
                            (if (yearOk) "(год $y совпал)" else "(страна $c совпала)"),
                    )
                    slugCache[cacheKey] = slug
                }
                return slug
            }
            com.torrenttv.app.data.AppLog.log(
                "trakt",
                "«$title»: алиас не принят — год/страна не совпали (ищем $year/$wantCountry, нашли $y/$c)",
            )
        }
        if (bestScore >= 100 && bestSlug.isNotBlank()) {
            slugCache[cacheKey] = bestSlug
            return bestSlug
        }
        if (bestScore < 100) {
            com.torrenttv.app.data.AppLog.log("trakt", "«$title» (год $year): не найден, лучший score $bestScore")
        }
        return null
    }

    /** Подвязка тайтл → slug в памяти процесса (живёт с приложением).
     *  ConcurrentHashMap: enrich идёт из нескольких фоновых потоков. */
    private val slugCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** «Россия»/«СССР» → ru, «США» → us и т.д. — для сверки с Trakt country (2 буквы). */
    private fun countryCode(c: String): String {
        val s = c.lowercase()
        return when {
            s.isBlank() -> ""
            "росси" in s || "ссср" in s -> "ru"
            "сша" in s || "америк" in s -> "us"
            "британ" in s || "англ" in s -> "gb"
            "франц" in s -> "fr"
            "герман" in s -> "de"
            "япон" in s -> "jp"
            "корей" in s -> "kr"
            "испан" in s -> "es"
            "итал" in s -> "it"
            "канад" in s -> "ca"
            "австрал" in s -> "au"
            else -> ""
        }
    }

    private fun statusBySlug(slug: String): TmdbClient.TvStatus? {
        val show = obj("/shows/" + Uri.encode(slug) + "?extended=full") ?: return null
        // Статусы Trakt: returning series / in production / planned / ended / canceled
        val st = show.optString("status", "").lowercase()
        val running = st == "returning series" || st == "in production" || st == "planned"

        // Сезоны с эпизодами: ищем ближайшую будущую и последнюю вышедшую серию
        val seasons = arr("/shows/" + Uri.encode(slug) + "/seasons?extended=full,episodes")
        val now = System.currentTimeMillis()
        var nextSeason = 0; var nextEpisode = 0; var nextAirMs = Long.MAX_VALUE
        var lastSeason = 0; var lastEpisode = 0; var lastAirMs = 0L
        var seasonCount = 0
        seasons?.let { sarr ->
            for (i in 0 until sarr.length()) {
                val s = sarr.optJSONObject(i) ?: continue
                val sn = s.optInt("number", -1)
                if (sn > 0) seasonCount++
                val eps = s.optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val e = eps.optJSONObject(j) ?: continue
                    val aired = e.optString("first_aired", "")
                    if (aired.isBlank()) continue
                    val ms = runCatching { Instant.parse(aired).toEpochMilli() }.getOrNull() ?: continue
                    val en = e.optInt("number", 0)
                    if (ms > now && ms < nextAirMs) {
                        nextAirMs = ms; nextSeason = sn; nextEpisode = en
                    }
                    if (ms <= now && ms >= lastAirMs) {
                        lastAirMs = ms; lastSeason = sn; lastEpisode = en
                    }
                }
            }
        }
        fun date(ms: Long): String =
            if (ms == Long.MAX_VALUE || ms == 0L) ""
            else Instant.ofEpochMilli(ms).toString().take(10) // yyyy-MM-dd, формат даты в UI общий

        // Ни будущих, ни прошедших серий не нашли — данных о сериях нет, не выдумываем
        if (nextSeason == 0 && lastSeason == 0) return null
        return TmdbClient.TvStatus(
            running = running,
            nextSeason = nextSeason,
            nextEpisode = nextEpisode,
            nextAirDate = date(nextAirMs),
            lastSeason = lastSeason,
            lastEpisode = lastEpisode,
            lastAirDate = date(lastAirMs),
            seasons = seasonCount,
        )
    }

    /**
     * Все сезоны и серии с датами выхода — для экрана «Эпизоды».
     * Основные источники (КП/TMDB) названий серий не дают или недоступны в РФ,
     * Trakt отдаёт и названия, и first_aired по каждой серии.
     * Совпадение сериала проверяем по названию и году (как в tvStatus).
     */
    fun seasons(title: String, year: String, country: String = ""): List<Season> {
        if (title.isBlank()) return emptyList()
        val slug = findShow(title, year, country) ?: return emptyList()
        // full обязателен: без него Trakt НЕ присылает first_aired у серий
        // (проверено на «Титанах»: episodes → 0 дат, full,episodes → все даты)
        val sarr = arr("/shows/" + Uri.encode(slug) + "/seasons?extended=full,episodes") ?: return emptyList()
        val result = (0 until sarr.length()).mapNotNull { i ->
            val s = sarr.optJSONObject(i) ?: return@mapNotNull null
            val num = s.optInt("number", -1)
            if (num <= 0) return@mapNotNull null // сезон 0 — спецэпизоды, не показываем
            val epsArr = s.optJSONArray("episodes") ?: return@mapNotNull null
            val eps = (0 until epsArr.length()).mapNotNull { j ->
                val e = epsArr.optJSONObject(j) ?: return@mapNotNull null
                val en = e.optInt("number", 0)
                if (en <= 0) return@mapNotNull null
                Episode(
                    season = num,
                    episode = en,
                    // Названия у Trakt на языке оригинала; русские приходят из КП/раздач
                    title = e.optString("title", "").removePrefix("Episode "),
                    airDate = e.optString("first_aired", "").take(10), // yyyy-MM-dd (UTC)
                )
            }
            if (eps.isEmpty()) null else Season(number = num, episodes = eps.sortedBy { it.episode })
        }.sortedBy { it.number }
        // Диагностика: сколько сезонов пришло и сколько серий с датами —
        // по этому логу видно, сработал ли full (без него дат 0)
        val withDates = result.sumOf { s -> s.episodes.count { it.airDate.isNotBlank() } }
        com.torrenttv.app.data.AppLog.log(
            "trakt",
            "«$title» ($slug): сезонов ${result.size} ${result.map { "S${it.number}(${it.episodes.size})" }}, серий с датами $withDates",
        )
        return result
    }

    /**
     * Постер с Trakt (media.trakt.tv — доступен из РФ, в отличие от TMDB).
     * isTv: true — сериал, false — фильм. null — постера нет.
     */
    fun posterUrl(title: String, year: String, isTv: Boolean, country: String = ""): String? {
        if (title.isBlank()) return null
        val slug = if (isTv) {
            findShow(title, year, country)
        } else {
            // Фильм: поиск → slug первого результата с совпавшим годом
            val json = arr("/search/movie?query=" + Uri.encode(title)) ?: return null
            var found: String? = null
            for (i in 0 until json.length()) {
                val m = json.optJSONObject(i)?.optJSONObject("movie") ?: continue
                if (year.isBlank() || m.optInt("year", 0).toString() == year) {
                    found = m.optJSONObject("ids")?.optString("slug")
                    break
                }
            }
            found
        } ?: run {
            com.torrenttv.app.data.AppLog.log("trakt", "«$title»: постер — объект не найден")
            return null
        }
        val images = obj("/" + (if (isTv) "shows" else "movies") + "/" + Uri.encode(slug) + "?extended=images")
            ?.optJSONObject("images")?.optJSONArray("poster") ?: return null.also {
            com.torrenttv.app.data.AppLog.log("trakt", "«$title» ($slug): постера нет")
        }
        if (images.length() == 0) return null.also {
            com.torrenttv.app.data.AppLog.log("trakt", "«$title» ($slug): постера нет")
        }
        // medium → full: полноразмерный постер для карточки
        val url = images.optJSONObject(0)?.optString("url", "")
            ?.replace("/medium/", "/full/")
            ?.takeUnless { it.isBlank() }
        com.torrenttv.app.data.AppLog.log(
            "trakt",
            if (url != null) "«$title» ($slug): постер найден" else "«$title» ($slug): постер битый",
        )
        return url
    }
}
