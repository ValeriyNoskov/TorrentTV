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

    // ---------- Прогресс просмотра ----------

    fun continueWatching(): List<WatchEntry> {
        val arr = runCatching { JSONArray(prefs.getString("progress", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length())
            .map { WatchEntry.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.updatedAt }
    }

    /** Сохранить/обновить позицию просмотра. */
    fun saveProgress(
        item: MediaItem,
        positionSec: Int,
        durationSec: Int,
        sourceKey: String = item.storeKey,
        episodeLabel: String = "",
    ) {
        val entries = continueWatching()
            .filter { it.sourceKey != sourceKey }
            .toMutableList()
        entries.add(0, WatchEntry(item, positionSec, durationSec, System.currentTimeMillis(), sourceKey, episodeLabel))
        prefs.edit()
            .putString("progress", JSONArray(entries.take(20).map { it.toJson() }).toString())
            .apply()
    }

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

    // ---------- Избранные телеканалы ----------

    fun favChannels(): List<IptvChannel> {
        val arr = runCatching { org.json.JSONArray(prefs.getString("fav_channels", "[]")) }
            .getOrDefault(org.json.JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            IptvChannel.fromJson(o).takeIf { it.url.isNotBlank() && it.name.isNotBlank() }
        }
    }

    fun isFavChannel(ch: IptvChannel): Boolean =
        favChannels().any { it.key == ch.key }

    /** Переключить; вернуть новое состояние (true = добавлен). */
    fun toggleFavChannel(ch: IptvChannel): Boolean {
        val list = favChannels().toMutableList()
        val existed = list.removeAll { it.key == ch.key }
        if (!existed) list.add(0, ch)
        prefs.edit()
            .putString("fav_channels", org.json.JSONArray(list.map { it.toJson() }).toString())
            .apply()
        return !existed
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

    // ---------- Filmix (онлайн-просмотр) ----------

    fun filmixToken(): String = prefs.getString("filmix_token", "") ?: ""

    fun setFilmixToken(v: String) {
        prefs.edit().putString("filmix_token", v.trim()).apply()
    }

    // ---------- EPG (телепрограмма IPTV) ----------

    /** URL XMLTV-источника; пусто — встроенный teleguide.info. */
    fun epgUrl(): String = prefs.getString("epg_url", "") ?: ""

    fun setEpgUrl(v: String) {
        prefs.edit().putString("epg_url", v.trim()).apply()
    }

    /** Показывать в IPTV вообще все группы (по умолчанию — только тематический белый список). */
    fun iptvAllGroups(): Boolean = prefs.getBoolean("iptv_all_groups", false)

    fun setIptvAllGroups(v: Boolean) {
        prefs.edit().putBoolean("iptv_all_groups", v).apply()
    }

    /** Адрес TorrServe (внешний движок стриминга торрентов). */
    fun torrServeHost(): String = prefs.getString("torrserve_host", "http://127.0.0.1:8090") ?: "http://127.0.0.1:8090"

    fun setTorrServeHost(v: String) {
        prefs.edit().putString("torrserve_host", v.trim().removeSuffix("/")).apply()
    }

    // ---------- Внутреннее ----------

    private fun readList(key: String): List<MediaItem> {
        val arr = runCatching { JSONArray(prefs.getString(key, "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length()).map { MediaItem.fromJson(arr.getJSONObject(it)) }
    }
}
