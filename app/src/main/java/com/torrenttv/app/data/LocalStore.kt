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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("item", item.toJson())
        .put("pos", positionSec)
        .put("dur", durationSec)
        .put("ts", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): WatchEntry = WatchEntry(
            item = MediaItem.fromJson(o.getJSONObject("item")),
            positionSec = o.optInt("pos"),
            durationSec = o.optInt("dur"),
            updatedAt = o.optLong("ts"),
        )
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

    // ---------- Прогресс просмотра ----------

    fun continueWatching(): List<WatchEntry> {
        val arr = runCatching { JSONArray(prefs.getString("progress", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length())
            .map { WatchEntry.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.updatedAt }
    }

    /** Сохранить/обновить позицию просмотра. */
    fun saveProgress(item: MediaItem, positionSec: Int, durationSec: Int) {
        val entries = continueWatching()
            .filter { it.item.storeKey != item.storeKey }
            .toMutableList()
        entries.add(0, WatchEntry(item, positionSec, durationSec, System.currentTimeMillis()))
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

    fun saveMagnet(item: MediaItem, magnet: String) {
        prefs.edit().putString("magnet:" + item.storeKey, magnet).apply()
    }

    fun getMagnet(item: MediaItem): String? =
        prefs.getString("magnet:" + item.storeKey, null)

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

    /** Пропускать ли карточку с учётом настроек фильтрации. */
    fun isAllowed(item: MediaItem): Boolean {
        val excluded = excludedGenreIds()
        if (item.genreIds.any { it in excluded }) return false
        val kws = excludedKeywords()
        if (kws.isNotEmpty()) {
            val text = (item.title + " " + item.overview).lowercase()
            if (kws.any { text.contains(it.lowercase()) }) return false
        }
        return true
    }

    // ---------- Внутреннее ----------

    private fun readList(key: String): List<MediaItem> {
        val arr = runCatching { JSONArray(prefs.getString(key, "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length()).map { MediaItem.fromJson(arr.getJSONObject(it)) }
    }
}
