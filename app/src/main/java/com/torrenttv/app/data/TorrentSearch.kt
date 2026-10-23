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
 *  - Jackett — опционально, если настроен URL и API-ключ в настройках;
 *  - частные трекеры (RuTracker, Kinozal, NNM-Club) — по cookie из настроек.
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
        // http/https-анонсы: udp-трекеры в некоторых сетях (в т.ч. в РФ) режутся —
        // эти работают по обычному HTTP и повышают шанс найти пиры
        "http://tracker.gbitt.info:80/announce",
        "https://tracker.gbitt.info:443/announce",
        "http://open.acgnxtracker.com:80/announce",
        "https://tracker.nanoha.org:443/announce",
    )

    /**
     * Найти раздачи у всех доступных провайдеров параллельно.
     * @param query    основной запрос (оригинальное название + год)
     * @param altQuery альтернативный запрос (русское название) — тоже отдаётся apibay
     * @param imdbId   IMDb id карточки (для Torrentio), может быть пустым
     * @param season   сезон (для Torrentio series endpoint), 0 — фильм
     * @param episode  эпизод (для Torrentio series endpoint)
     * @param jackett  конфигурация Jackett из настроек, null если выключена
     * @param excludedKeywords стоп-слова из настроек (блокируют раздачу по названию)
     */
    suspend fun searchAll(
        query: String,
        altQuery: String? = null,
        isTvShow: Boolean,
        imdbId: String = "",
        season: Int = 0,
        episode: Int = 0,
        jackett: JackettProvider.Config? = null,
        privateTrackers: List<PrivateTrackers.TrackerAuth> = emptyList(),
        excludedKeywords: Set<String> = emptySet(),
        limit: Int = 50,
    ): List<TorrentResult> =
        searchDetailed(
            query, altQuery, isTvShow, imdbId, season, episode,
            jackett, privateTrackers, excludedKeywords, limit,
        ).first

    /** Статистика одного провайдера для диагностики («apibay:20 · torrentio:35 · jackett:—»). */
    data class ProviderStat(
        val name: String,
        val count: Int,
        val error: String? = null,
    ) {
        /** «apibay:20», «apibay:0 (недоступен)» или «apibay:—». */
        fun label(): String = when {
            error != null -> "$name:ошибка"
            count == 0 -> "$name:—"
            else -> "$name:$count"
        }
    }

    /** Поиск + по каждому провайдеру — сколько нашёл (для строки диагностики). */
    suspend fun searchDetailed(
        query: String,
        altQuery: String? = null,
        isTvShow: Boolean,
        imdbId: String = "",
        season: Int = 0,
        episode: Int = 0,
        jackett: JackettProvider.Config? = null,
        privateTrackers: List<PrivateTrackers.TrackerAuth> = emptyList(),
        excludedKeywords: Set<String> = emptySet(),
        limit: Int = 50,
    ): Pair<List<TorrentResult>, List<ProviderStat>> = withContext(Dispatchers.IO) {
        // Каждый вызов провайдера: имя, результаты, ошибка (если вылетел)
        data class Call(val name: String, val res: List<TorrentResult>, val err: String?)

        val calls: List<Call> = coroutineScope {
            val jobs = buildList {
                add(async {
                    runCatching {
                        // apibay ищет и по оригинальному, и по русскому названию
                        Call(ApibayProvider.NAME, ApibayProvider.search(query, isTvShow) +
                            (altQuery?.takeIf { it.isNotBlank() && !it.equals(query, true) }
                                ?.let { ApibayProvider.search(it, isTvShow) } ?: emptyList()), null)
                    }.getOrElse { Call(ApibayProvider.NAME, emptyList(), it.message) }
                })
                if (imdbId.isNotBlank()) {
                    // Torrentio: фильм целиком или конкретная серия (сериалы)
                    if (season > 0 && episode > 0) {
                        add(async {
                            runCatching { Call(TorrentioProvider.NAME, TorrentioProvider.search(imdbId, season, episode), null) }
                                .getOrElse { Call(TorrentioProvider.NAME, emptyList(), it.message) }
                        })
                    } else if (!isTvShow) {
                        add(async {
                            runCatching { Call(TorrentioProvider.NAME, TorrentioProvider.search(imdbId), null) }
                                .getOrElse { Call(TorrentioProvider.NAME, emptyList(), it.message) }
                        })
                    }
                }
                // Резервные публичные источники — когда основные недоступны из сети
                add(async {
                    runCatching { Call(Bt4gProvider.NAME, Bt4gProvider.search(query), null) }
                        .getOrElse { Call(Bt4gProvider.NAME, emptyList(), it.message) }
                })
                if (!isTvShow) {
                    add(async {
                        runCatching { Call(YtsProvider.NAME, YtsProvider.search(query), null) }
                            .getOrElse { Call(YtsProvider.NAME, emptyList(), it.message) }
                    })
                }
                // Открытые трекеры без регистрации: EZTV (сериалы, в т.ч. по IMDb),
                // SolidTorrents и BTDig (DHT-поиск) — расширяют покрытие
                if (imdbId.isNotBlank() || isTvShow) {
                    add(async {
                        runCatching {
                            Call(EztvProvider.NAME, EztvProvider.search(query, isTvShow, imdbId, season, episode), null)
                        }.getOrElse { Call(EztvProvider.NAME, emptyList(), it.message) }
                    })
                }
                add(async {
                    runCatching { Call(SolidTorrentsProvider.NAME, SolidTorrentsProvider.search(query), null) }
                        .getOrElse { Call(SolidTorrentsProvider.NAME, emptyList(), it.message) }
                })
                add(async {
                    runCatching {
                        Call(BtdigProvider.NAME, BtdigProvider.search(query) +
                            (altQuery?.takeIf { it.isNotBlank() && !it.equals(query, true) }
                                ?.let { BtdigProvider.search(it) } ?: emptyList()), null)
                    }.getOrElse { Call(BtdigProvider.NAME, emptyList(), it.message) }
                })
                add(async {
                    runCatching { Call(KnabenProvider.NAME, KnabenProvider.search(query), null) }
                        .getOrElse { Call(KnabenProvider.NAME, emptyList(), it.message) }
                })
                if (jackett?.isConfigured == true) {
                    add(async {
                        runCatching { Call(JackettProvider.NAME, JackettProvider.search(jackett, query), null) }
                            .getOrElse { Call(JackettProvider.NAME, emptyList(), it.message) }
                    })
                }
                // Частные трекеры: RU-трекеры лучше ищут по русскому названию —
                // поэтому запрос идёт и основным, и альтернативным
                for (auth in privateTrackers.filter { it.isConfigured }) {
                    add(async {
                        runCatching {
                            Call(auth.id, PrivateTrackers.search(auth, query) +
                                (altQuery?.takeIf { it.isNotBlank() && !it.equals(query, true) }
                                    ?.let { PrivateTrackers.search(auth, it) } ?: emptyList()), null)
                        }.getOrElse { Call(auth.id, emptyList(), it.message) }
                    })
                }
            }
            jobs.awaitAll()
        }
        val stats = calls.map { ProviderStat(it.name, it.res.size, it.err) }
        val merged = merge(calls.map { it.res }, excludedKeywords).take(limit)
        com.torrenttv.app.data.AppLog.log(
            "search",
            "q=\"$query\" alt=\"$altQuery\" tv=$isTvShow imdb=$imdbId -> " +
                stats.joinToString(" ") { s ->
                    "${s.name}:${s.count}" + (s.error?.let { e -> "($e)" } ?: "")
                } +
                " => ${merged.size}",
        )
        merged to stats
    }

    /** Слить выдачи провайдеров: фильтр по сидам/стоп-словам, дедуп по info_hash. */
    fun merge(
        results: List<List<TorrentResult>>,
        excludedKeywords: Set<String> = emptySet(),
    ): List<TorrentResult> {
        val banned = excludedKeywords.map { it.lowercase() }
        return results.asSequence()
            .flatten()
            // Частные раздачи могут прийти без известного числа сидов — не выбрасываем
            .filter { it.seeds > 0 || it.isPrivate }
            .filter { banned.none { kw -> it.name.lowercase().contains(kw) } }
            .groupBy { it.infoHash.ifBlank { "url:" + it.torrentUrl } }
            .map { (_, group) -> group.maxBy { it.seeds } }
            .sortedByDescending { it.seeds }
            .toList()
    }

    /** Собрать magnet-ссылку из info_hash с публичными трекерами. */
    fun toMagnet(r: TorrentResult): String = ApibayProvider.toMagnet(r)
}
