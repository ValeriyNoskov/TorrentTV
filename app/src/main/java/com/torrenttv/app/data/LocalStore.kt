package com.torrenttv.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Запись о просмотре (для строки «Продолжить просмотр»). */
data class WatchEntry(
    val item: MediaItem,
    val positionSec: Int,
    val durationSec: Int,
    val updatedAt: Long,
    /** Ключ источника: storeKey или storeKey + суффикс эпизода. */
    val sourceKey: String = "",
    /** Метка эпизода («S01E05») для подсказки в интерфейсе. */
    val episodeLabel: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("item", item.toJson())
        .put("pos", positionSec)
        .put("dur", durationSec)
        .put("ts", updatedAt)
        .put("skey", sourceKey)
        .put("ep", episodeLabel)

    companion object {
        fun fromJson(o: JSONObject): WatchEntry {
            val item = MediaItem.fromJson(o.getJSONObject("item"))
            return WatchEntry(
                item = item,
                positionSec = o.optInt("pos"),
                durationSec = o.optInt("dur"),
                updatedAt = o.optLong("ts"),
                sourceKey = o.optString("skey", "").ifBlank { item.storeKey },
                episodeLabel = o.optString("ep", ""),
            )
        }
    }
}

/**
 * Локальное хранилище: избранное и прогресс просмотра.
 * SharedPreferences + JSON — достаточно для MVP (объёмы малы).
 */
class LocalStore(context: Context) {

    companion object {
        // Домашний сервер бэкапа (MacBook): адрес и токен прописаны по умолчанию,
        // чтобы на телевизоре ничего не вводить руками. Сервер слушает только
        // локальную сеть (наружу порт не проброшен). Сменить можно в
        // Настройки → Логи → «Сервер бэкапа» — тогда в prefs ляжет своё значение.
        const val DEFAULT_BACKUP_URL = "http://192.168.3.78:8091"
        const val DEFAULT_BACKUP_TOKEN = "8c39cbdfb8fd114471ed7b3ff36684b8"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("torrenttv", Context.MODE_PRIVATE)

    /** Настройки пусты (свежая установка или сброс данных) — повод восстановить бэкап. */
    fun isEmpty(): Boolean = prefs.all.isEmpty()

    /** Полный снимок настроек для облачного бэкапа (SettingsBackup). */
    fun snapshot(): JSONObject {
        val o = JSONObject()
        for ((k, v) in prefs.all) {
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Set<*> -> o.put(k, JSONArray(v))
                is Int, is Long, is Boolean, is Double, is String -> o.put(k, v)
                // Управляющие символы (0x00–0x1F) ломают JSON: Android-реализация
                // org.json пишет их как есть, и потом бэкап НЕ ЧИТАЕТСЯ при
                // восстановлении. Такой символ однажды попал в историю поиска
                // и весь снимок стал невалидным — чистим перед выгрузкой
                else -> o.put(k, v.toString().filter { it >= ' ' })
            }
            if (v is String) {
                val clean = v.filter { it >= ' ' || it == '\n' || it == '\r' || it == '\t' }
                if (clean != v) o.put(k, clean)
            }
        }
        return o
    }

    /** Восстановить настройки из облачного бэкапа (перезаписывает текущие). */
    fun restore(o: JSONObject) {
        val e = prefs.edit()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                JSONObject.NULL, null -> {}
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> if (v == Math.floor(v) && !v.isInfinite()) e.putLong(k, v.toLong()) else e.putFloat(k, v.toFloat())
                is JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until v.length()) set.add(v.optString(i))
                    e.putStringSet(k, set)
                }
                is String -> e.putString(k, v)
                else -> e.putString(k, v.toString())
            }
        }
        e.apply()
    }

    // ---------- Избранное ----------

    fun favorites(): List<MediaItem> = readList("favorites")

    fun isFavorite(item: MediaItem): Boolean =
        favorites().any { it.storeKey == item.storeKey }

    /** Переключить; вернуть новое состояние (true = добавлено). */
    fun toggleFavorite(item: MediaItem): Boolean {
        val list = favorites().toMutableList()
        val existed = list.removeAll { it.storeKey == item.storeKey }
        if (!existed) list.add(0, item)
        prefs.edit()
            .putString("favorites", JSONArray(list.map { it.toJson() }).toString())
            .apply()
        return !existed
    }

    // ---------- Избранные каналы ТВ ----------
    // Ключ — адрес потока: имена каналов в разных плейлистах отличаются,
    // а ссылка на поток уникальна.

    fun tvFavorites(): Set<String> =
        prefs.getStringSet("tv_favorites", emptySet())?.toSet() ?: emptySet()

    fun isTvFavorite(url: String): Boolean = tvFavorites().contains(url)

    /** Переключить; вернуть новое состояние (true = добавлено в избранное). */
    fun toggleTvFavorite(url: String): Boolean {
        val set = tvFavorites().toMutableSet()
        val added = set.add(url)
        if (!added) set.remove(url)
        prefs.edit().putStringSet("tv_favorites", set).apply()
        return added
    }

    // Разделы «Смотреть позже» и статусы (Смотрю / Буду смотреть / Просмотрено)
    // удалены: их заменило избранное с фильтром «Фильмы / Сериалы»
    // (решение владельца), полка «Буду смотреть» на Главной не заполнялась.

    /** Есть ли запись о просмотре (для ленты «Продолжить»). */
    fun hasProgress(item: MediaItem): Boolean =
        continueWatching().any { it.item.storeKey == item.storeKey }

    // ---------- Прогресс просмотра ----------

    fun continueWatching(): List<WatchEntry> {
        val arr = runCatching { JSONArray(prefs.getString("progress", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length())
            .map { WatchEntry.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.updatedAt }
            // Дедуп на чтение: чистит старые дубли серий одного сериала,
            // оставляя самую свежую запись
            .distinctBy { it.item.storeKey }
    }

    /** Сохранить/обновить позицию просмотра. */
    fun saveProgress(
        item: MediaItem,
        positionSec: Int,
        durationSec: Int,
        sourceKey: String = item.storeKey,
        episodeLabel: String = "",
    ) {
        // Позиция конкретной серии/ссылки — отдельно от ленты «Продолжить»:
        // у серий одного сериала разные sourceKey, и дедуп ленты не должен
        // терять их resume-позиции
        if (sourceKey != item.storeKey) {
            prefs.edit()
                .putString(
                    "epos:$sourceKey",
                    org.json.JSONObject()
                        .put("pos", positionSec)
                        .put("dur", durationSec)
                        .put("at", System.currentTimeMillis())
                        .toString(),
                )
                .apply()
        }
        // Одна карточка на сериал/фильм в «Продолжить»: серии одного сериала
        // имеют разный sourceKey — без этой фильтрации сериал плодит
        // несколько постеров подряд (баг «3 постера Спецов»)
        val entries = continueWatching()
            .filter { it.item.storeKey != item.storeKey }
            .toMutableList()
        entries.add(0, WatchEntry(item, positionSec, durationSec, System.currentTimeMillis(), sourceKey, episodeLabel))
        prefs.edit()
            .putString("progress", JSONArray(entries.take(20).map { it.toJson() }).toString())
            .apply()
    }

    /** Позиция серии/ссылки (запасное хранилище при дедупе ленты). */
    fun episodePosition(sourceKey: String): Int =
        runCatching {
            prefs.getString("epos:$sourceKey", null)
                ?.let { org.json.JSONObject(it).getInt("pos") }
        }.getOrNull() ?: 0

    fun removeProgress(item: MediaItem) {
        val entries = continueWatching().filter { it.item.storeKey != item.storeKey }
        prefs.edit()
            .putString("progress", JSONArray(entries.map { it.toJson() }).toString())
            .apply()
    }

    // ---------- Мета-кеш обогащения карточек ----------

    /**
     * Сохранённая мета карточки (постер/описание/год/IMDb/рейтинг).
     * Обогащение делается один раз — дальше мгновенно из хранилища:
     * без повторных запросов к КП и без траты дневной квоты.
     */
    fun metaCacheGet(storeKey: String): MediaItem? {
        val raw = prefs.getString("meta:$storeKey", null) ?: return null
        return runCatching { MediaItem.fromJson(org.json.JSONObject(raw)) }.getOrNull()
    }

    fun metaCachePut(storeKey: String, m: MediaItem) {
        // Пустое обогащение не запоминаем: завтра квота КП обновится — попробуем снова
        if (m.posterUrl.isBlank() && m.overview.isBlank() && m.imdbId.isBlank()) return
        runCatching {
            prefs.edit().putString("meta:$storeKey", m.toJson().toString()).apply()
        }
    }

    // ---------- Magnet-ссылки (источник видео для карточки) ----------

    /**
     * Источник видео: magnet или путь к .torrent.
     * @param suffix суффикс ключа для эпизодов (":s01e05") — у сериала
     *               может быть свой источник на каждый эпизод
     */
    fun saveMagnet(item: MediaItem, magnet: String, suffix: String = "") {
        prefs.edit().putString("magnet:" + item.storeKey + suffix, magnet).apply()
    }

    fun getMagnet(item: MediaItem, suffix: String = ""): String? =
        prefs.getString("magnet:" + item.storeKey + suffix, null)

    // ---------- Последний выбранный сезон (экран эпизодов) ----------

    /** Запомнить выбранный на экране эпизодов сезон («Титаны»: открыл S05 — откроется S05). */
    fun saveLastSeason(item: MediaItem, season: Int) {
        prefs.edit().putInt("lastseason:" + item.storeKey, season).apply()
    }

    /** Последний выбранный сезон (0 — не выбирали). */
    fun lastSeason(item: MediaItem): Int =
        prefs.getInt("lastseason:" + item.storeKey, 0)

    // ---------- Последняя серия торрента (продолжить с той же серии) ----------

    /** Запомнить последнюю проигранную серию (файл) раздачи. */
    fun saveLastFile(item: MediaItem, fileIndex: Int) {
        prefs.edit().putInt("lastfile:" + item.storeKey, fileIndex).apply()
    }

    /** Последняя серия раздачи или -1 (не смотрели). */
    fun lastFile(item: MediaItem): Int =
        prefs.getInt("lastfile:" + item.storeKey, -1)

    /** Серию этой раздачи уже выбирали/смотрели? */
    fun hasLastFile(item: MediaItem): Boolean =
        prefs.contains("lastfile:" + item.storeKey)

    // ---------- Просмотренные серии (галочка в списке, авто + кнопкой) ----------

    // ---------- Статистика просмотра (ROADMAP E) ----------

    /** Сводка для экрана «Статистика»: часы, серии, активность, топ по времени. */
    data class WatchStats(
        val totalSeconds: Long,
        val episodes: Int,
        val watched: Int,
        val inHistory: Int,
        val last7: Int,
        val last30: Int,
        /** Месяц «2026-10» → сколько серий/записей, последние 6 месяцев по порядку. */
        val byMonth: List<Pair<String, Int>>,
        /** Название → минуты (из ленты «Продолжить»). */
        val top: List<Pair<String, Int>>,
    )

    fun watchStats(): WatchStats {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        var totalSec = 0L
        var episodes = 0
        var last7 = 0
        var last30 = 0
        val months = mutableMapOf<String, Int>()
        val monthFmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)

        // Серии: у каждой своя запись epos:<ключ> с позицией и временем
        for ((k, v) in prefs.all) {
            if (!k.startsWith("epos:") || v !is String) continue
            val o = runCatching { JSONObject(v) }.getOrNull() ?: continue
            val pos = o.optInt("pos", 0)
            val dur = o.optInt("dur", 0)
            val at = o.optLong("at", 0)
            episodes++
            // Смотрим не больше длительности: иначе перемотки раздувают часы
            totalSec += (if (dur > 0) minOf(pos, dur) else pos).toLong()
            if (at > now - 7 * day) last7++
            if (at > now - 30 * day) last30++
            if (at > 0) months[monthFmt.format(java.util.Date(at))] = (months[monthFmt.format(java.util.Date(at))] ?: 0) + 1
        }

        // Лента «Продолжить»: фильмы и сериалы целиком + топ по времени
        val progress = continueWatching()
        val byTitle = mutableMapOf<String, Int>()
        for (e in progress) {
            totalSec += e.positionSec.coerceAtMost(if (e.durationSec > 0) e.durationSec else e.positionSec).toLong()
            if (e.updatedAt > now - 7 * day) last7++
            if (e.updatedAt > now - 30 * day) last30++
            val minutes = (e.positionSec / 60).coerceAtLeast(1)
            byTitle[e.item.title] = (byTitle[e.item.title] ?: 0) + minutes
        }

        // Последние 6 месяцев по порядку (включая пустые — чтобы график не «прыгал»)
        val cal = java.util.Calendar.getInstance()
        val ordered = mutableListOf<Pair<String, Int>>()
        repeat(6) {
            val key = monthFmt.format(cal.time)
            ordered.add(0, key to (months[key] ?: 0))
            cal.add(java.util.Calendar.MONTH, -1)
        }

        return WatchStats(
            totalSeconds = totalSec,
            episodes = episodes,
            watched = (prefs.getStringSet("watched", emptySet()) ?: emptySet()).size,
            inHistory = progress.size,
            last7 = last7,
            last30 = last30,
            byMonth = ordered,
            top = byTitle.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        )
    }

    /** Отметить просмотренной сразу под несколькими ключами (обычный + нормализованный). */
    fun markWatchedKeys(keys: List<String>, watched: Boolean = true) {
        val set = prefs.getStringSet("watched", emptySet()) ?: emptySet()
        val next = if (watched) set + keys else set - keys.toSet()
        prefs.edit().putStringSet("watched", next).apply()
    }

    /** Серия отмечена просмотренной хотя бы под одним из ключей? */
    fun isWatchedAny(keys: List<String>): Boolean {
        val set = prefs.getStringSet("watched", emptySet()) ?: emptySet()
        return keys.any { it in set }
    }

    /**
     * Нормализованный ключ просмотра: одинаковый для ЛЮБЫХ раздач одного
     * тайтла («Спецы» с Рутрекера и с apibay — один и тот же сериал).
     * Название без регистра и пунктуации + суффикс серии (":s01e08").
     */
    fun normWatchKey(title: String, suffix: String): String =
        "norm:" + title.lowercase().filter { it.isLetterOrDigit() } + suffix

    // ---------- Офлайн-каталог «Торрентов» (ROADMAP №12) ----------

    /** Сохранить последний удачный список карточек (JSON в prefs). */
    fun saveTorrentsCache(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString("torrents_cache", arr.toString())
            .putLong("torrents_cache_ts", System.currentTimeMillis())
            .apply()
    }

    /** Сохранённый список и когда он был получен (мс) — для «Офлайн: от 14:30». */
    fun loadTorrentsCache(): Pair<List<MediaItem>, Long> {
        val raw = prefs.getString("torrents_cache", null) ?: return emptyList<MediaItem>() to 0L
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { MediaItem.fromJson(arr.getJSONObject(i)) }.getOrNull()
            } to prefs.getLong("torrents_cache_ts", 0L)
        }.getOrDefault(emptyList<MediaItem>() to 0L)
    }

    // ---------- Кеш найденных серий (поиск раздач — не при каждом открытии) ----------

    /** Сохранить собранные из раздач сезоны: повторное открытие — без поиска. */
    fun saveEpisodeCache(item: MediaItem, seasons: List<Season>) {
        val arr = JSONArray()
        for (s in seasons.sortedBy { it.number }) {
            val eps = JSONArray()
            for (e in s.episodes.sortedBy { it.episode }) {
                eps.put(JSONObject().put("s", e.season).put("e", e.episode).put("t", e.title).put("d", e.airDate))
            }
            arr.put(JSONObject().put("n", s.number).put("e", eps))
        }
        prefs.edit()
            .putString(
                "eps:" + item.storeKey,
                JSONObject().put("at", System.currentTimeMillis()).put("s", arr).toString(),
            )
            .apply()
    }

    /** Время последнего сохранения кеша серий (0 — кеша нет). */
    fun episodeCacheTime(item: MediaItem): Long =
        runCatching {
            JSONObject(prefs.getString("eps:" + item.storeKey, "{}") ?: "{}").getLong("at")
        }.getOrDefault(0L)

    /**
     * Бейдж «N новых серий»: сколько серий досмотрено в кеше с момента
     * последнего открытия списка серий. Работает без сети — по кешу,
     * который дозагрузка наполняет при открытии сериала.
     */
    fun newEpisodesCount(item: MediaItem): Int {
        if (item.type != "tv") return 0
        val cached = loadEpisodeCache(item).sumOf { it.episodes.size }
        if (cached == 0) return 0
        val seen = prefs.getInt("epsSeen:" + item.storeKey, 0)
        return (cached - seen).coerceAtLeast(0)
    }

    /** Пользователь открыл список серий — новые увидел, бейдж погасить. */
    fun markEpisodesSeen(item: MediaItem) {
        val cached = loadEpisodeCache(item).sumOf { it.episodes.size }
        prefs.edit().putInt("epsSeen:" + item.storeKey, cached).apply()
    }

    /** Кеш сезонов из раздач (пусто — не собирали). */
    fun loadEpisodeCache(item: MediaItem): List<Season> =
        runCatching {
            val obj = JSONObject(prefs.getString("eps:" + item.storeKey, "{}") ?: "{}")
            val arr = obj.optJSONArray("s") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val sObj = arr.getJSONObject(i)
                val epsArr = sObj.optJSONArray("e") ?: return@mapNotNull null
                Season(
                    number = sObj.getInt("n"),
                    episodes = (0 until epsArr.length()).map { j ->
                        val e = epsArr.getJSONObject(j)
                        Episode(
                            season = e.getInt("s"),
                            episode = e.getInt("e"),
                            title = e.optString("t"),
                            airDate = e.optString("d"),
                        )
                    },
                )
            }.sortedBy { it.number }
        }.getOrDefault(emptyList())

    // ---------- Бейдж «+N серий» (ROADMAP A) ----------

    // ---------- Настройки фильтрации поиска ----------

    /** Исключённые жанры (id TMDB). */
    fun excludedGenreIds(): Set<Int> =
        prefs.getString("excl_genres", "")
            ?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()

    fun setExcludedGenreIds(ids: Set<Int>) {
        prefs.edit().putString("excl_genres", ids.joinToString(",")).apply()
    }

    /** Стоп-слова: блокировать контент, если слово встречается в названии/описании. */
    fun excludedKeywords(): List<String> =
        prefs.getString("excl_kw", "")
            ?.split(',')?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setExcludedKeywords(kws: List<String>) {
        prefs.edit().putString("excl_kw", kws.joinToString(",")).apply()
    }

    /** Личные ключи баз фильмов (перекрывают вшитые; бесплатно: KP — kinopoiskapiunofficial.tech, TMDB — themoviedb.org). */
    fun kpApiKey(): String = prefs.getString("kp_api_key", "").orEmpty()

    /** GitHub-токен (права только gists) — для отправки логов мне в Gist. */
    fun githubToken(): String = prefs.getString("github_token", "").orEmpty()

    fun setGithubToken(v: String) {
        prefs.edit().putString("github_token", v.trim()).apply()
    }

    // ---------- Размер карточек ----------

    fun cardWidthDp(): Int = prefs.getInt("card_width_dp", 170)

    fun setCardWidthDp(v: Int) {
        prefs.edit().putInt("card_width_dp", v).apply()
    }

    fun setKpApiKey(v: String) {
        prefs.edit().putString("kp_api_key", v).apply()
        KinopoiskClient.overrideApiKey = v
    }

    fun tmdbApiKey(): String = prefs.getString("tmdb_api_key", "").orEmpty()

    fun setTmdbApiKey(v: String) {
        prefs.edit().putString("tmdb_api_key", v).apply()
        TmdbClient.overrideApiKey = v
    }

    /** Исключённые страны (id из /filters). */
    fun excludedCountryIds(): Set<Int> =
        prefs.getString("excl_countries", "")
            ?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()

    fun setExcludedCountryIds(ids: Set<Int>) {
        prefs.edit().putString("excl_countries", ids.joinToString(",")).apply()
    }

    /** Пропускать ли карточку с учётом настроек фильтрации. */
    fun isAllowed(item: MediaItem): Boolean {
        val excluded = excludedGenreIds()
        if (item.genreIds.any { it in excluded }) return false
        val excludedCountries = excludedCountryIds()
        if (item.countryIds.any { it in excludedCountries }) return false
        val kws = excludedKeywords()
        if (kws.isNotEmpty()) {
            val text = (item.title + " " + item.overview).lowercase()
            if (kws.any { text.contains(it.lowercase()) }) return false
        }
        return true
    }

    // ---------- Jackett (опциональный провайдер поиска) ----------

    fun jackettUrl(): String = prefs.getString("jackett_url", "") ?: ""

    fun setJackettUrl(v: String) {
        prefs.edit().putString("jackett_url", v.trim()).apply()
    }

    fun jackettApiKey(): String = prefs.getString("jackett_key", "") ?: ""

    fun setJackettApiKey(v: String) {
        prefs.edit().putString("jackett_key", v.trim()).apply()
    }

    /** Конфигурация Jackett для автопоиска; null, если не настроен. */
    fun jackettConfig(): JackettProvider.Config? {
        val c = JackettProvider.Config(jackettUrl(), jackettApiKey())
        return if (c.isConfigured) c else null
    }

    // ---------- Частные трекеры (cookie-авторизация) ----------

    /** Cookie-строка трекера из настроек (вставляется из браузера). */
    fun trackerCookie(trackerId: String): String =
        prefs.getString("tracker_cookie_$trackerId", "") ?: ""

    /** Настроенные частные трекеры для автопоиска. */
    fun privateTrackers(): List<PrivateTrackers.TrackerAuth> =
        PrivateTrackers.ALL.map { (id, title, _) ->
            PrivateTrackers.TrackerAuth(id, title, trackerCookie(id))
        }.filter { it.isConfigured }

    /** Авторизация конкретного трекера по id (для скачивания .torrent). */
    fun trackerAuth(trackerId: String): PrivateTrackers.TrackerAuth {
        val title = PrivateTrackers.ALL.firstOrNull { it.first == trackerId }?.second ?: trackerId
        return PrivateTrackers.TrackerAuth(trackerId, title, trackerCookie(trackerId))
    }

    // ---------- TorrServe (внешний движок стриминга торрентов) ----------

    /** Адрес TorrServe (внешний движок стриминга торрентов). */
    // ---------- IPTV ----------

    /** Свой плейлист каналов (m3u/m3u8) — добавляется к встроенному списку. */
    fun iptvPlaylistUrl(): String = prefs.getString("iptv_playlist_url", "") ?: ""

    fun setIptvPlaylistUrl(v: String) {
        prefs.edit().putString("iptv_playlist_url", v.trim()).apply()
    }

    /** Подключать свой плейлист к встроенному (по умолчанию — да, если задан). */
    fun iptvUseOwnPlaylist(): Boolean = prefs.getBoolean("iptv_use_own", true)

    fun setIptvUseOwnPlaylist(v: Boolean) {
        prefs.edit().putBoolean("iptv_use_own", v).apply()
    }

    fun torrServeHost(): String = prefs.getString("torrserve_host", "http://127.0.0.1:8090") ?: "http://127.0.0.1:8090"

    fun setTorrServeHost(v: String) {
        prefs.edit().putString("torrserve_host", v.trim().removeSuffix("/")).apply()
    }

    // ---------- «Что нового» после обновления ----------

    /** Версия, для которой пользователь уже видел список изменений. */
    fun lastSeenVersion(): String = prefs.getString("seen_version", "") ?: ""

    fun setLastSeenVersion(v: String) {
        prefs.edit().putString("seen_version", v).apply()
    }

    // ---------- Свой сервер бэкапа (домашний Mac) ----------
    // Телевизор не должен быть единственным местом, где лежат настройки и кеш:
    // при «очистить данные» они пропадают. Сначала пробуем свой сервер,
    // Gist остаётся резервом, если сервер недоступен.

    /** Адрес сервиса бэкапа, напр. http://192.168.3.78:8091 (пусто — не использовать). */
    fun backupServerUrl(): String =
        (prefs.getString("backup_server_url", DEFAULT_BACKUP_URL) ?: DEFAULT_BACKUP_URL)
            .trim().removeSuffix("/")

    fun setBackupServerUrl(v: String) {
        prefs.edit().putString("backup_server_url", v.trim().removeSuffix("/")).apply()
    }

    /** Токен сервера бэкапа (заголовок X-Backup-Token). */
    fun backupServerToken(): String =
        (prefs.getString("backup_server_token", DEFAULT_BACKUP_TOKEN) ?: DEFAULT_BACKUP_TOKEN)
            .trim()

    fun setBackupServerToken(v: String) {
        prefs.edit().putString("backup_server_token", v.trim()).apply()
    }

    // ---------- Плеер: автоскрытие панели управления ----------

    /** Секунд до автоскрытия панели плеера (0 = не прятать). Дефолт 20. */
    fun panelHideSec(): Int = prefs.getString("panel_hide_sec", "20")?.toIntOrNull()?.coerceIn(0, 120) ?: 20

    fun setPanelHideSec(sec: Int) {
        prefs.edit().putString("panel_hide_sec", sec.toString()).apply()
    }

    // ---------- Каталоги (Фильмы/Сериалы/Шоу): последний выбранный жанр ----------

    fun saveCatalogGenre(mode: String, genreId: Int) {
        prefs.edit().putInt("cataloggenre:$mode", genreId).apply()
    }

    fun lastCatalogGenre(mode: String): Int = prefs.getInt("cataloggenre:$mode", 0)
/** Выбранная сортировка каталога («полки» / «поступившие» / «год» / «рейтинг»). */
    fun lastCatalogSort(mode: String): String = prefs.getString("catalogsort:$mode", "shelves") ?: "shelves"

    fun saveCatalogSort(mode: String, sort: String) {
        prefs.edit().putString("catalogsort:$mode", sort).apply()
    }
/** Сортировка раздела «Торренты»: «popular» (по умолчанию) / «posted». */
    fun lastTorrentsSort(): String = prefs.getString("torrentssort", "popular") ?: "popular"

    fun saveTorrentsSort(sort: String) {
        prefs.edit().putString("torrentssort", sort).apply()
    }



    // ---------- Каталог «Торренты»: сохранение фильтров ----------

    /**
     * Фильтры раздела «Торренты» — мультивыбор по каждому разделу
     * (тип, жанры, страны, годы). Пустой набор = «любой».
     */
    data class TorrentsFilters(
        val types: Set<String> = emptySet(),     // movie | tv | show
        val genres: Set<String> = emptySet(),    // slug'и категорий трекера
        val countries: Set<String> = emptySet(),
        val years: Set<String> = emptySet(),     // «2024» или «2010-е»
    ) {
        val count: Int get() = types.size + genres.size + countries.size + years.size
        val isEmpty: Boolean get() = count == 0
    }

    /** Фильтры «Торрентов» (мультивыбор) — переживают выход и перезапуск. */
    fun saveTorrentsFilters(f: TorrentsFilters) {
        prefs.edit().putString(
            "torrents_filters",
            JSONObject()
                .put("types", JSONArray(f.types.toList()))
                .put("genres", JSONArray(f.genres.toList()))
                .put("countries", JSONArray(f.countries.toList()))
                .put("years", JSONArray(f.years.toList()))
                .toString(),
        ).apply()
    }

    fun loadTorrentsFilters(): TorrentsFilters = runCatching {
        val raw = prefs.getString("torrents_filters", null)
        if (raw == null) return@runCatching legacyTorrentsFilter()
        val o = JSONObject(raw)
        fun set(name: String): Set<String> {
            val arr = o.optJSONArray(name) ?: return emptySet()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
        }
        TorrentsFilters(set("types"), set("genres"), set("countries"), set("years"))
    }.getOrDefault(TorrentsFilters())

    /** Старый формат (одиночный выбор) — читаем, чтобы фильтры не сбросились. */
    private fun legacyTorrentsFilter(): TorrentsFilters = runCatching {
        val o = JSONObject(prefs.getString("torrents_filter", "{}") ?: "{}")
        val type = o.safeString("type", "all").ifBlank { "all" }
        TorrentsFilters(
            types = if (type == "all" || type.isBlank()) emptySet() else setOf(type),
            genres = listOfNotNull(o.safeString("genre").ifBlank { null }).toSet(),
            countries = listOfNotNull(o.safeString("country").ifBlank { null }).toSet(),
            years = listOfNotNull(o.safeString("year").ifBlank { null }).toSet(),
        )
    }.getOrDefault(TorrentsFilters())

    // ---------- Плеер: запоминание настроек (как в Just/Next Player) ----------

    /** Масштаб картинки: 0 = Вписать, 1 = Растянуть, 2 = Заполнить. */
    fun playerScale(): Int = prefs.getInt("player_scale", 0)

    fun setPlayerScale(v: Int) {
        prefs.edit().putInt("player_scale", v).apply()
    }

    // ---------- Умный подбор раздач: предпочтения владельца ----------

    /**
     * Предпочтительные релизёры: токены имён раздач, которые владелец выбирал
     * сам. Строка «tok1,tok2,…» — чем раньше, тем чаще выбирали.
     */
    fun preferedGroups(): List<String> =
        (prefs.getString("prefer_groups", "") ?: "")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }

    fun noteChosenGroup(releaseName: String) {
        val tok = Regex("""[A-Za-z][A-Za-z0-9]{3,}""").findAll(releaseName)
            .map { it.value.lowercase() }
            .firstOrNull { it !in STOP_TOKENS } ?: return
        val cur = preferedGroups().toMutableList()
        cur.removeAll { it == tok }
        cur.add(0, tok)
        prefs.edit().putString("prefer_groups", cur.take(6).joinToString(",")).apply()
    }

    /** Токены, которые встречаются почти в каждой раздаче — не признак релизёра. */
    private val STOP_TOKENS = setOf(
        "webdl", "web", "dlrip", "bdrip", "bluray", "hdr", "hevc", "h264", "x264",
        "x265", "1080", "720", "2160", "season", "series", "lostfilm", "hdrezka",
        "rus", "eng", "ukr", "dual", "mega", "rip", "uhd",
    )

    /**
     * Бонус раздачи при авто-выборе: совпадение релизёра с вашими прошлыми
     * выборами (первый в списке — самый частый).
     */
    fun groupBonus(releaseName: String): Int {
        val toks = Regex("""[a-z][a-z0-9]{3,}""").findAll(releaseName.lowercase())
            .map { it.value }.toSet()
        preferedGroups().forEachIndexed { idx, g ->
            if (g in toks) return 60 - idx * 15
        }
        return 0
    }

    // ---------- Разметка пропуска (SkipDB/IntroDB) ----------

    /** Пропуск по открытым базам разметки (заставка — авто, титры — кнопкой). */
    fun skipDbEnabled(): Boolean = prefs.getBoolean("skip_db", true)

    fun setSkipDbEnabled(v: Boolean) {
        prefs.edit().putBoolean("skip_db", v).apply()
    }

    /** Кеш разметки пропуска: null — нет записи, список — что запомнили (может быть пустым). */
    fun skipCacheGet(key: String): List<SkipSeg>? {
        val raw = prefs.getString("skipseg_$key", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("t", 0L) > 14L * 24 * 3600 * 1000) return null
            val arr = o.getJSONArray("segs")
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                SkipSeg(s.getString("k"), s.getLong("s"), s.getLong("e"), s.getDouble("c"))
            }
        }.getOrNull()
    }

    fun skipCachePut(key: String, segs: List<SkipSeg>) {
        val arr = StringBuilder("[")
        segs.forEachIndexed { i, s ->
            if (i > 0) arr.append(',')
            arr.append("""{"k":"${s.kind}","s":${s.startMs},"e":${s.endMs},"c":${s.confidence}}""")
        }
        arr.append(']')
        prefs.edit().putString("skipseg_$key", """{"t":${System.currentTimeMillis()},"segs":$arr}""").apply()
    }

    /** Подпись аудиодорожки, выбранной пользователем (например, «Дубляж»). */
    fun playerAudioLabel(): String = prefs.getString("player_audio_label", "") ?: ""

    fun setPlayerAudioLabel(v: String) {
        prefs.edit().putString("player_audio_label", v.trim()).apply()
    }

    /** Запомненные субтитры: имя/язык трека; «off» — выключены; «» — не выбирали. */
    fun playerSubLabel(): String = prefs.getString("player_sub_label", "") ?: ""

    fun setPlayerSubLabel(v: String) {
        prefs.edit().putString("player_sub_label", v.trim()).apply()
    }

    /** Сдвиг субтитров, мс (−5000..5000): положительный — сабы позже. */
    fun playerSubShift(): Int = prefs.getInt("player_sub_shift", 0)

    fun setPlayerSubShift(v: Int) {
        prefs.edit().putInt("player_sub_shift", v.coerceIn(-5000, 5000)).apply()
    }

    /** Открывать видео во внешнем плеере (VLC, MX Player и т.п.) вместо встроенного. */
    fun useExternalPlayer(): Boolean = prefs.getBoolean("use_external_player", false)

    fun setUseExternalPlayer(v: Boolean) {
        prefs.edit().putBoolean("use_external_player", v).apply()
    }
    // ---------- Выбранный внешний плеер (для запуска без меню «Открыть с помощью») ----------

    /** Пакет внешнего плеера, который владелец выбрал один раз ("" — не выбран). */
    fun externalPlayerPackage(): String = prefs.getString("external_player_pkg", "") ?: ""

    fun setExternalPlayerPackage(pkg: String) {
        prefs.edit().putString("external_player_pkg", pkg).apply()
    }


    /** Ввод: внешняя (системная IME) клавиатура вместо встроенной экранной. */
    fun useExternalKeyboard(): Boolean = prefs.getBoolean("external_keyboard", false)

    fun setUseExternalKeyboard(v: Boolean) {
        prefs.edit().putBoolean("external_keyboard", v).apply()
    }

    // ---------- Поиск: последние запросы (история) ----------

    /** Последние поисковые запросы, свежие сверху (макс. 8). */
    fun recentQueries(): List<String> =
        (prefs.getString("recent_queries", "") ?: "")
            .split('\u0001')
            .filter { it.isNotBlank() }

    /**
     * Словарь для подсказок клавиатуры: названия просмотренных/открытых
     * карточек + история поисков. Ограничение — 300 свежих записей.
     */
    fun kbWords(): List<String> =
        runCatching {
            val arr = org.json.JSONArray(prefs.getString("kb_words", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

    fun kbAddWord(word: String) {
        val w = word.trim()
        if (w.length < 3 || w.length > 60) return
        val next = (listOf(w) + kbWords().filterNot { it.equals(w, ignoreCase = true) }).take(300)
        prefs.edit()
            .putString("kb_words", org.json.JSONArray(next).toString())
            .apply()
    }

    fun addRecentQuery(q: String) {
        val v = q.trim()
        if (v.length < 2) return
        // Набор по буквам («сп», «спе», «спец») не должен замусоривать историю:
        // убираем все прежние записи, которые являются ПРЕФИКСОМ полного запроса.
        // «спецлаб» (другой фильм, не префикс) остаётся в списке.
        val next = (listOf(v) + recentQueries().filterNot {
            it.equals(v, ignoreCase = true) || v.startsWith(it, ignoreCase = true)
        }).take(8)
        prefs.edit().putString("recent_queries", next.joinToString("\u0001")).apply()
    }

    fun clearRecentQueries() {
        prefs.edit().remove("recent_queries").apply()
    }

    /** Последняя открытая секция настроек — открываем её при входе. */
    fun lastSettingsSection(): String = prefs.getString("settings_section", "search") ?: "search"

    // ---------- Логи ----------

    /** Запись логов включена (Настройки → Логи). */
    fun loggingEnabled(): Boolean = prefs.getBoolean("logging_enabled", true)

    fun setLoggingEnabled(v: Boolean) {
        prefs.edit().putBoolean("logging_enabled", v).apply()
    }
    fun setLastSettingsSection(id: String) {
        prefs.edit().putString("settings_section", id).apply()
    }

    // ---------- Внутреннее ----------

    private fun readList(key: String): List<MediaItem> {
        val arr = runCatching { JSONArray(prefs.getString(key, "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length()).map { MediaItem.fromJson(arr.getJSONObject(it)) }
    }
}
