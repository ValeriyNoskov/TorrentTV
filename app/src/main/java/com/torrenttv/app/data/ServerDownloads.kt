package com.torrenttv.app.data

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking

/**
 * Скачивание на сервер TorrServe («сервер качает, ТВ стримит»):
 * раздачи докачиваются на диск сервера ЦЕЛИКОМ через preload с порогом =
 * размер файла. После закачки просмотр мгновенный и не зависит от живости пиров.
 *
 * Источники задач:
 * — кнопка «⬇ Скачать на сервер» на карточке;
 * — автокачалка ИЗБРАННОГО: syncFavorites() при старте приложения и при
 *   добавлении в избранное — новые серии свежих раздач докачиваются сами.
 * Качает, пока запущено приложение (preload-соединение держит закачку).
 */
object ServerDownloads {

    /** Спецификация задачи: тайтс + магниты-кандидаты. */
    data class Spec(val item: MediaItem, val magnets: List<String>)

    /** Снимок состояния для UI (читается из главного потока). */
    data class Job(
        val title: String,
        val magnet: String,
        val filesDone: Int,
        val filesTotal: Int,
        val fileName: String,
        val fileMb: Long,
        val fileDoneMb: Long,
        val state: String, // «качаю», «готово», «ошибка», «остановлено», «в очереди»
    )

    private val queue = ConcurrentLinkedQueue<Spec>()

    @Volatile private var job: Job? = null

    /** Хэши раздач, полностью скачанных на сервер (признак «офлайн» у серий). */
    private val doneHashes = mutableSetOf<String>()
    private val doneLock = Any()

    /** Раздача полностью на сервере? (бейдж «офлайн» в списке серий) */
    fun isTorrentOnServer(hash: String): Boolean = synchronized(doneLock) { hash.lowercase() in doneHashes }

    /**
     * Сезон целиком на сервере? Все серии с сохранённой раздачей belong к
     * скачанным раздачам (и минимум одна есть) → бейдж «⬇» на чипе сезона.
     */
    fun isSeasonOnServer(
        store: LocalStore,
        item: MediaItem,
        episodes: List<Episode>,
    ): Boolean {
        var any = false
        for (ep in episodes) {
            val magnet = store.getMagnet(item, ep.sourceSuffix) ?: return false
            val hash = TorrServeService.hashOf(magnet) ?: return false
            if (!isTorrentOnServer(hash)) return false
            any = true
        }
        return any
    }

    private fun markDone(hash: String) = synchronized(doneLock) { doneHashes.add(hash.lowercase()) }

    @Volatile private var workerRunning = false

    @Volatile private var stopRequested = false

    /** Плеер на экране → качалка ждёт (сервер отдаёт стрим, канал один). */
    @Volatile private var playerActive = false

    fun setPlayerActive(active: Boolean) {
        playerActive = active
        AppLog.log("serverdl", if (active) "плеер активен — качалка приостановлена" else "плеер закрыт — качалка продолжается")
    }

    private fun waitPlayerIdle() {
        while (playerActive && !stopRequested) Thread.sleep(5_000)
    }

    /** Текущая задача (null — ничего не качается). */
    fun current(): Job? = job

    fun isRunning(): Boolean {
        val j = job ?: return false
        return j.state == "качаю" || j.state == "в очереди"
    }

    /** Остановить текущую качалку (поток завершится на следующей проверке). */
    fun stop() {
        stopRequested = true
    }

    /** Поставить раздачу(и) в очередь на сервер. */
    fun enqueue(store: LocalStore, item: MediaItem, magnets: List<String>) {
        if (magnets.isEmpty()) return
        queue.add(Spec(item, magnets))
        kickWorker(store)
    }

    /**
     * Автокачалка избранного: для каждого тайтса из избранного — сохранённые
     * раздачи (или лучшая из поиска), в очередь. Новые серии из свежих раздач
     * докачиваются: файлы, уже скачанные на сервере, preload проходит мгновенно.
     * @param only синхронизировать только этот тайтс (при добавлении в избранное)
     */
    fun syncFavorites(store: LocalStore, only: MediaItem? = null) {
        val items = if (only != null) listOf(only) else store.favorites()
        if (items.isEmpty()) return
        Thread {
            runBlocking {
                runCatching {
                    var added = 0
                    for (item in items) {
                        // НОВЫЕ СЕРИИ: свежий поиск при каждом прогоне — новые
                        // серии выходят в свежих раздачах, которых нет среди
                        // сохранённых. Кандидаты: топ-3 по сидам; сохранённые
                        // раздачи — фолбэк, если поиск не дал ничего.
                        val found = runCatching {
                            TorrentSearch.searchDetailed(
                                query = item.title,
                                isTvShow = item.type != "movie",
                                imdbId = item.imdbId,
                                jackett = store.jackettConfig(),
                                privateTrackers = store.privateTrackers(),
                                excludedKeywords = store.excludedKeywords().toSet(),
                            ).first.sortedByDescending { it.seeds }
                        }.getOrElse {
                            AppLog.log("serverdl", "«${item.title}»: поиск FAIL — ${it.message}")
                            emptyList()
                        }
                        val magnets = (found.take(3).map { TorrentSearch.toMagnet(it) } +
                            store.savedMagnets(item)).distinct()
                        if (magnets.isEmpty()) {
                            AppLog.log("serverdl", "«${item.title}»: раздач не нашлось — пропускаю")
                            continue
                        }
                        if (found.isNotEmpty()) {
                            AppLog.log(
                                "serverdl",
                                "«${item.title}»: поиск нашёл ${found.size} раздач, " +
                                    "топ по сидам: «${found.first().name.take(60)}» (${found.first().seeds} сидов)",
                            )
                        }
                        queue.add(Spec(item, magnets))
                        added++
                    }
                    AppLog.log("serverdl", "автокачалка избранного: в очередь добавлено $added тайтсов")
                    kickWorker(store)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Запустить фонового воркера, если он ещё не крутится. */
    private fun kickWorker(store: LocalStore) {
        if (workerRunning) return
        workerRunning = true
        Thread {
            try {
                while (true) {
                    val spec = queue.poll() ?: break
                    if (stopRequested) break
                    downloadSpec(store, spec)
                }
            } finally {
                workerRunning = false
            }
        }.apply { isDaemon = true }.start()
    }

    /** Качать одну раздачу целиком: каждый видео-файл до его полного размера. */
    private fun downloadSpec(store: LocalStore, spec: Spec) {
        val host = store.torrServeHost()
        if (host.isBlank()) {
            AppLog.log("serverdl", "${spec.item.title}: TorrServe не настроен — качать некуда")
            return
        }
        stopRequested = false
        var done = 0
        var total = 0
        var ok = false
        try {
            // Разведка: с какой раздачи начать — где больше скачано на сервере
            val probed = spec.magnets.map { m ->
                val t = runCatching { TorrServeService.addMagnet(host, m, spec.item.title) { } }.getOrNull()
                m to (t?.preloadedBytes ?: -1L)
            }.sortedByDescending { it.second }
            AppLog.log(
                "serverdl",
                "${spec.item.title}: качаю на сервер, раздач-кандидатов ${probed.size}, " +
                    "старт с самой скачанной (${probed.first().second / 1024 / 1024} МБ уже на диске)",
            )
            job = Job(spec.item.title, probed.first().first, 0, 0, "", 0, 0, "качаю")
            outer@ for ((mi, pair) in probed.withIndex()) {
                val (m, _) = pair
                if (stopRequested) break
                val t = runCatching { TorrServeService.addMagnet(host, m, spec.item.title) { } }.getOrNull()
                val videosAll = t?.files?.filter {
                    it.path.substringAfterLast('.', "").lowercase() in
                        com.torrenttv.app.torrent.TorrentEngine.VIDEO_EXT
                }?.sortedWith { x, y ->
                    com.torrenttv.app.torrent.TorrentEngine.naturalCompare(x.path, y.path)
                } ?: emptyList()
                if (videosAll.isEmpty() || t == null) {
                    AppLog.log("serverdl", "${spec.item.title}: раздача ${mi + 1} без видео-файлов — следующая")
                    continue
                }
                // Просмотренные серии НЕ качаем; кроме того качаем только от
                // «точки продолжения» — последней просмотренной серии (S05E06
                // → качаем S05E07+, а паки S01 по 40 ГБ не заливаются на сервер:
                // непросмотрённый ≠ нужный, продолжаем смотреть с того места,
                // где остановились)
                var allWatched = true
                var continuePoint: Pair<Int, Int>? = null
                if (spec.item.type != "movie") {
                    val watchedSet = store.watchedKeys()
                    val points = watchedSet.mapNotNull { k ->
                        Regex(":s(\\d+)e(\\d+)$").find(k)?.let { m ->
                            val key = k.substringBefore(":s")
                            if (key.startsWith("norm:") || key.startsWith(spec.item.storeKey + ":online"))
                                m.groupValues[1].toInt() * 1000 + m.groupValues[2].toInt()
                            else null
                        }
                    }.filter { it > 0 }
                    if (points.isNotEmpty()) {
                        val max = points.max()
                        continuePoint = max / 1000 to max % 1000
                        AppLog.log(
                            "serverdl",
                            "${spec.item.title}: точка продолжения S%02dE%02d — старые серии не качаю".format(
                                continuePoint.first, continuePoint.second,
                            ),
                        )
                    }
                }
                val videos = videosAll.filter { f ->
                    val nums = Episode.fileNumbers(f.path)
                    if (nums != null && continuePoint != null) {
                        val p = nums.first * 1000 + nums.second
                        if (p < continuePoint.first * 1000 + continuePoint.second) {
                            AppLog.log(
                                "serverdl",
                                "${spec.item.title}: «${f.path.substringAfterLast('/').take(50)}» — до точки продолжения, не качаю",
                            )
                            return@filter false
                        }
                    }
                    if (nums == null) return@filter true // не эпизод (пак без разметки) — качаем
                    val suffix = ":" + "S%02dE%02d".format(nums.first, nums.second).lowercase()
                    val watched = store.isWatchedAny(listOf(
                        spec.item.storeKey + ":online" + suffix,
                        store.normWatchKey(spec.item.title, suffix),
                    ))
                    if (watched) {
                        AppLog.log(
                            "serverdl",
                            "${spec.item.title}: «${f.path.substringAfterLast('/').take(50)}» — просмотрена, не качаю",
                        )
                    } else allWatched = false
                    !watched
                }
                if (videos.isEmpty()) {
                    if (allWatched) {
                        // Нечего качать не ошибка: тайтс просмотрен целиком
                        AppLog.log("serverdl", "${spec.item.title}: всё просмотрено — пропускаю")
                        job = job?.copy(state = "готово", filesDone = 0, filesTotal = 0)
                        return
                    }
                    AppLog.log("serverdl", "${spec.item.title}: после фильтра качать нечего — следующая раздача")
                    continue
                }
                total = videos.size
                done = 0
                var consecutiveFails = 0
                for ((i, f) in videos.withIndex()) {
                    if (stopRequested) break@outer
                    waitPlayerIdle()
                    if (stopRequested) break@outer
                    job = Job(
                        spec.item.title, m, done, total, f.path.substringAfterLast('/'),
                        f.length / 1024 / 1024, 0, "качаю",
                    )
                    AppLog.log(
                        "serverdl",
                        "${spec.item.title}: файл ${i + 1}/$total «${f.path.substringAfterLast('/').take(50)}» " +
                            "(${f.length / 1024 / 1024} МБ) — проверяю на сервере, докачиваю если нужно " +
                            "(уже скачанный проходит мгновенно)",
                    )
                    val fileOk = TorrServeService.preloadFile(
                        host, t.hash, f,
                        timeoutMs = 6 * 60 * 60_000L, // большой файл качается часами
                        thresholdBytes = f.length,
                    ) { s ->
                        // «Буферизация TorrServe… N МБ из ~M» — N для UI
                        val n = Regex("(\\d+) МБ").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        job = job?.copy(fileDoneMb = n)
                    }
                    if (fileOk) {
                        done++
                        consecutiveFails = 0
                    } else {
                        // Два файла подряд не докачались — почти наверняка
                        // место на сервере кончилось (или раздача умерла):
                        // дальше только простаиваем, задачу снимаем
                        consecutiveFails++
                        AppLog.log(
                            "serverdl",
                            "${spec.item.title}: файл «${f.path.substringAfterLast('/').take(50)}» не докачался " +
                                "(${consecutiveFails} подряд)" +
                                if (consecutiveFails >= 2) " — снимаю задачу: проверьте место на сервере" else " — следующий",
                        )
                        if (consecutiveFails >= 2) break@outer
                    }
                }
                if (total > 0) {
                    ok = done >= total
                    if (ok) {
                        // Раздача целиком на сервере — сериям ставим признак «офлайн»
                        markDone(t.hash)
                        break
                    }
                    // Раздача недокачана частично — пробуем следующую (докачает
                    // недостающие файлы: куски уже скачанных не повторяются)
                }
            }
        } catch (e: Exception) {
            AppLog.log("serverdl", "${spec.item.title}: ошибка качалки — ${e.message}")
        }
        job = job?.copy(
            state = when {
                stopRequested -> "остановлено"
                ok -> "готово"
                else -> "ошибка"
            },
            filesDone = done,
            filesTotal = total,
        )
        AppLog.log(
            "serverdl",
            "${spec.item.title}: ${when {
                ok -> "скачано целиком ($done файлов)"
                stopRequested -> "остановлено"
                else -> "не удалось"
            }} — файлов $done/$total",
        )
    }
}
