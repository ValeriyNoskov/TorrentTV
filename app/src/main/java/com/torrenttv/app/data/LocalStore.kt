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

    private val prefs: SharedPreferences =
        context.getSharedPreferences("torrenttv", Context.MODE_PRIVATE)

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

    // ---------- Смотреть позже ----------

    fun watchLater(): List<MediaItem> = readList("watch_later")

    fun isWatchLater(item: MediaItem): Boolean =
        watchLater().any { it.storeKey == item.storeKey }

    /** Переключить; вернуть новое состояние (true = добавлено). */
    fun toggleWatchLater(item: MediaItem): Boolean {
        val list = watchLater().toMutableList()
        val existed = list.removeAll { it.storeKey == item.storeKey }
        if (!existed) list.add(0, item)
        prefs.edit()
            .putString("watch_later", JSONArray(list.map { it.toJson() }).toString())
            .apply()
        return !existed
    }

    // ---------- Статусы: Смотрю / Буду смотреть / Просмотрено ----------

    /** Статус фильма: "" — не отмечен, "watching", "want", "done". */
    fun itemStatus(item: MediaItem): String =
        prefs.getString("status:" + item.storeKey, "") ?: ""

    fun setItemStatus(item: MediaItem, status: String) {
        val key = "status:" + item.storeKey
        if (status.isBlank()) {
            prefs.edit().remove(key).remove("statusitem:" + item.storeKey).apply()
        } else {
            prefs.edit()
                .putString(key, status)
                .putString("statusitem:" + item.storeKey, item.toJson().toString())
                .apply()
        }
    }

    /** Следующий статус по циклу: нет → Смотрю → Буду смотреть → Просмотрено → нет. */
    fun cycleItemStatus(item: MediaItem): String {
        val next = when (itemStatus(item)) {
            "" -> "watching"; "watching" -> "want"; "want" -> "done"; else -> ""
        }
        setItemStatus(item, next)
        return next
    }

    /** Все фильмы с данным статусом (в порядке недавнего обновления). */
    fun itemsWithStatus(status: String): List<MediaItem> =
        prefs.all.entries
            .filter { it.key.startsWith("status:") && it.value == status }
            .mapNotNull { entry ->
                // storeKey вида "movie-706094223": ищем item в прогрессе/избранном,
                // иначе восстанавливаем по id из ключа нельзя — храним сериализованный item
                runCatching {
                    val json = prefs.getString("statusitem:" + entry.key.removePrefix("status:"), null)
                    json?.let { MediaItem.fromJson(org.json.JSONObject(it)) }
                }.getOrNull()
            }
            .reversed()

    /** Полное «убрать из просмотренных»: статус, лента «Продолжить», отметка серии. */
    fun clearWatched(item: MediaItem) {
        setItemStatus(item, "")
        prefs.edit()
            .remove("statusitem:" + item.storeKey)
            .remove("lastfile:" + item.storeKey)
            .apply()
        // Запасные позиции серий этого сериала (epos:<storeKey>:…)
        val eposKeys = prefs.all.keys.filter { it.startsWith("epos:" + item.storeKey) }
        if (eposKeys.isNotEmpty()) {
            prefs.edit().apply { eposKeys.forEach { remove(it) } }.apply()
        }
        val arr = runCatching { JSONArray(prefs.getString("progress", "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val e = WatchEntry.fromJson(o)
            if (e.item.storeKey != item.storeKey) out.put(o)
        }
        prefs.edit().putString("progress", out.toString()).apply()
    }

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

    fun setTrackerCookie(trackerId: String, cookie: String) {
        prefs.edit().putString("tracker_cookie_$trackerId", cookie.trim()).apply()
    }

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
    fun torrServeHost(): String = prefs.getString("torrserve_host", "http://127.0.0.1:8090") ?: "http://127.0.0.1:8090"

    fun setTorrServeHost(v: String) {
        prefs.edit().putString("torrserve_host", v.trim().removeSuffix("/")).apply()
    }

    // ---------- Плеер: запоминание настроек (как в Just/Next Player) ----------

    /** Масштаб картинки: 0 = Вписать, 1 = Растянуть, 2 = Заполнить. */
    fun playerScale(): Int = prefs.getInt("player_scale", 0)

    fun setPlayerScale(v: Int) {
        prefs.edit().putInt("player_scale", v).apply()
    }

    /** Подпись аудиодорожки, выбранной пользователем (например, «Дубляж»). */
    fun playerAudioLabel(): String = prefs.getString("player_audio_label", "") ?: ""

    fun setPlayerAudioLabel(v: String) {
        prefs.edit().putString("player_audio_label", v.trim()).apply()
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
