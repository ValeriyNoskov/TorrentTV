package com.torrenttv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Автопоиск торрентов: агрегатор провайдеров.
 *
 * Провайдеры:
 *  - apibay.org (ThePirateBay) — всегда, по названию;
 *  - Torrentio — по IMDb-id карточки (только фильмы);
 *  - Jackett — опционально, если настроен URL и API-ключ в настройках.
 *
 * Результаты сливаются, дедуплицируются по info_hash (остаётся раздача
 * с максимумом сидов), сортируются по сидам.
 */
object TorrentSearch {

    val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
    )

    /**
     * Найти раздачи у всех доступных провайдеров параллельно.
     * @param query    основной запрос (оригинальное название + год)
     * @param altQuery альтернативный запрос (русское название) — тоже отдаётся apibay
     * @param imdbId   IMDb id карточки (для Torrentio), может быть пустым
     * @param jackett  конфигурация Jackett из настроек, null если выключена
     * @param excludedKeywords стоп-слова из настроек (блокируют раздачу по названию)
     */
    suspend fun searchAll(
        query: String,
        altQuery: String? = null,
        isTvShow: Boolean,
        imdbId: String = "",
        jackett: JackettProvider.Config? = null,
        excludedKeywords: Set<String> = emptySet(),
        limit: Int = 50,
    ): List<TorrentResult> = withContext(Dispatchers.IO) {
        val partial = coroutineScope {
            val jobs = buildList {
                add(async { ApibayProvider.search(query, isTvShow) })
                if (!altQuery.isNullOrBlank() && !altQuery.equals(query, ignoreCase = true)) {
                    add(async { ApibayProvider.search(altQuery, isTvShow) })
                }
                if (!isTvShow && imdbId.isNotBlank()) {
                    add(async { TorrentioProvider.search(imdbId) })
                }
                if (jackett?.isConfigured == true) {
                    add(async { JackettProvider.search(jackett, query) })
                }
            }
            jobs.awaitAll()
        }
        merge(partial, excludedKeywords).take(limit)
    }

    /** Слить выдачи провайдеров: фильтр по сидам/стоп-словам, дедуп по info_hash. */
    fun merge(
        results: List<List<TorrentResult>>,
        excludedKeywords: Set<String> = emptySet(),
    ): List<TorrentResult> {
        val banned = excludedKeywords.map { it.lowercase() }
        return results.asSequence()
            .flatten()
            .filter { it.seeds > 0 }
            .filter { banned.none { kw -> it.name.lowercase().contains(kw) } }
            .groupBy { it.infoHash }
            .map { (_, group) -> group.maxBy { it.seeds } }
            .sortedByDescending { it.seeds }
            .toList()
    }

    /** Собрать magnet-ссылку из info_hash с публичными трекерами. */
    fun toMagnet(r: TorrentResult): String = ApibayProvider.toMagnet(r)
}
