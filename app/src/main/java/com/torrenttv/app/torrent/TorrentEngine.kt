package com.torrenttv.app.torrent

import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File
import java.net.URL

/**
 * Обёртка над jlibtorrent.
 *
 * Стратегия стриминга: торрент добавляется с последовательной загрузкой
 * (sequential download), приоритет — только самый большой видеофайл.
 * Плеер читает файл напрямую через TorrentDataSource, который дожидается
 * нужных кусков. Файлы удаляются при выходе из плеера.
 */
object TorrentEngine {

    val session = SessionManager()

    val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "webm", "wmv", "flv")

    /**
     * Естественное сравнение: цифровые блоки сравниваются как числа —
     * S01E02 < S01E10, «Часть 2» < «Часть 10».
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                val na = a.substring(i, i2).toLongOrNull() ?: 0L
                val nb = b.substring(j, j2).toLongOrNull() ?: 0L
                if (na != nb) return na.compareTo(nb)
                i = i2
                j = j2
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun start() {
        if (session.isRunning) return
        // Настройки как в открытых стримерах (LibreTorrent/TorrServe): больше
        // соединений и активных загрузок — быстрее находят пиры и качают
        try {
            val sp = com.frostwire.jlibtorrent.SettingsPack()
                .connectionsLimit(300)
                .activeDownloads(-1)
                .activeSeeds(-1)
            // Тюнинг под стриминг на ТВ: быстрее находить пиры за NAT провайдера
            // и собирать пиров со ВСЕХ трекеров раздачи, а не одного
            runCatching {
                sp.setBoolean(
                    com.frostwire.jlibtorrent.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(),
                    true,
                )
                sp.setBoolean(
                    com.frostwire.jlibtorrent.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(),
                    true,
                )
                sp.setBoolean(
                    com.frostwire.jlibtorrent.swig.settings_pack.bool_types.enable_upnp.swigValue(),
                    true,
                )
                sp.setBoolean(
                    com.frostwire.jlibtorrent.swig.settings_pack.bool_types.enable_natpmp.swigValue(),
                    true,
                )
            }            // Широкий список DHT-роутеров: холодная сессия находит первый
            // пир быстрее, метаданные магнита не «висят» по 90 секунд
            runCatching {
                sp.setDhtBootstrapNodes(
                    "router.bittorrent.com:6881," +
                        "router.utorrent.com:6881," +
                        "router.bitcomet.com:6881," +
                        "dht.transmissionbt.com:6881," +
                        "dht.libtorrent.org:25401," +
                        "dht.aelitis.com:6881",
                )
            }
            session.applySettings(sp)
        } catch (_: Exception) {
        }
        session.start()
    }

    /**
     * info_hash v1 из байт .torrent-файла (hex, 40 символов) — для частных
     * трекеров, где hash недоступен из страницы поиска и считается локально.
     */
    fun infoHashV1Hex(bytes: ByteArray): String? = try {
        TorrentInfo.bdecode(bytes).infoHashV1().toString()
    } catch (_: Exception) {
        null
    }

    /**
     * Активная сессия стриминга одного файла торрента.
     */
    class StreamSession(
        val handle: TorrentHandle,
        val ti: TorrentInfo,
        val fileIndex: Int,
        /** Смещение выбранного файла внутри торрента (сумма размеров предыдущих файлов). */
        val fileOffset: Long,
        val fileSize: Long,
        val saveDir: File,
    ) {
        val videoFile: File by lazy { File(saveDir, ti.files().filePath(fileIndex)) }

        /** Все видеофайлы торрента: (индекс, имя) — по порядку серий. */
        fun videoFiles(): List<Pair<Int, String>> {
            val fs = ti.files()
            val out = mutableListOf<Pair<Int, String>>()
            for (i in 0 until fs.numFiles()) {
                if (fs.fileName(i).substringAfterLast('.', "").lowercase() in VIDEO_EXT) {
                    out += i to fs.fileName(i)
                }
            }
            // Естественный порядок: S01E02 раньше S01E10, «Часть 2» раньше «Часть 10»
            out.sortWith { x, y -> TorrentEngine.naturalCompare(x.second, y.second) }
            return out
        }

        /** Сколько байт выбранного файла скачано; при sequential это непрерывная «голова» файла. */
        fun headBytes(): Long = try {
            handle.fileProgress().getOrNull(fileIndex) ?: 0L
        } catch (_: Exception) {
            0L
        }

        /** Число подключённых пиров (для диагностики на экране буферизации). */
        fun peers(): Int = try {
            handle.status().numPeers()
        } catch (_: Exception) {
            0
        }

        /** Скорость загрузки, байт/с (payload). */
        fun speedBps(): Long = try {
            handle.status().downloadPayloadRate().toLong()
        } catch (_: Exception) {
            0L
        }

        /** Общий прогресс торрента, 0..1. */
        fun progress(): Float = try {
            handle.status().progress()
        } catch (_: Exception) {
            0f
        }

        /**
         * Готов ли диапазон [from, from+length) файла: все куски диапазона
         * скачаны (havePiece) — как в открытых стримерах. fileProgress() тут
         * не годится: он суммирует скачанные куски без непрерывности, после
         * перемотки даёт ложную готовность.
         */
        fun isRangeReady(filePos: Long, length: Long): Boolean = readyFraction(filePos, length) >= 1f

        /** Доля готовых кусков в диапазоне файла [filePos, filePos+length), 0..1. */
        fun readyFraction(filePos: Long, length: Long): Float {
            try {
                val pieceLen = ti.pieceLength()
                if (pieceLen <= 0) return 1f
                val abs = fileOffset + filePos
                val end = (abs + length).coerceAtMost(fileOffset + fileSize)
                val first = (abs / pieceLen).toInt().coerceIn(0, ti.numPieces() - 1)
                val last = ((end - 1).coerceAtLeast(abs) / pieceLen).toInt().coerceIn(0, ti.numPieces() - 1)
                if (last < first) return 1f
                var have = 0
                for (i in first..last) if (handle.havePiece(i)) have++
                return have.toFloat() / (last - first + 1)
            } catch (_: Exception) {
                return 0f
            }
        }

        /**
         * Приоритет кусков вокруг абсолютной позиции в торренте — чтобы перемотка
         * в середину фильма не ждала, пока последовательно скачается всё до неё.
         */
        fun boostPiecesAround(absolutePos: Long, spanBytes: Long = 48L * 1024 * 1024) {
            try {
                val pieceLen = ti.pieceLength()
                if (pieceLen <= 0) return
                val from = (absolutePos / pieceLen).toInt().coerceIn(0, ti.numPieces() - 1)
                val to = ((absolutePos + spanBytes) / pieceLen).toInt().coerceIn(0, ti.numPieces() - 1)
                for (i in from..to) handle.piecePriority(i, Priority.SEVEN)
            } catch (_: Exception) {
            }
        }

        /** Убрать торрент из сессии (файлы удаляет вызывающий код). */
        fun stop() {
            try {
                session.remove(handle)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Стриминг: добавляет торрент с последовательной загрузкой и возвращает сессию
     * самого большого видеофайла. Не ждёт полного скачивания.
     * Блокирующий вызов (метаданные до 60 с) — запускать из фонового потока.
     *
     * @param source magnet-ссылка, URL .torrent или локальный путь к .torrent
     * @param fileMatcher необязательный предикат по имени файла — для выбора
     *        нужной серии в пачке сезона (S01E05); если не сработал — берётся
     *        самый большой видеофайл
     */
    /**
     * Дополнить magnet публичными трекерами, если своих нет: DHT при холодном
     * старте сессии не успевает за таймаут, трекеры дают пиры напрямую.
     */
    private fun enrichMagnet(source: String): String {
        if (!source.startsWith("magnet:")) return source
        // Добавляем ВСЕГДА трекеры из нашего списка, которых в magnet ещё нет:
        // magnet-и из внешних каталогов содержат 1-2 своих трекера, и без
        // дополнения раздача может часами не находить метаданные
        val existing = Regex("[?&]tr=([^&]+)").findAll(source)
            .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            .toSet()
        val missing = com.torrenttv.app.data.TorrentSearch.TRACKERS.filter { it !in existing }
        if (missing.isEmpty()) return source
        return buildString {
            append(source.trimEnd('&'))
            for (t in missing) {
                append("&tr=")
                append(java.net.URLEncoder.encode(t, "UTF-8"))
            }
        }
    }

    /** Метаданные раздачи (для экрана «файлы раздачи») — без запуска стрима. */
    fun fetchMetadata(
        source: String,
        saveDir: File,
        onStatus: (String) -> Unit,
    ): com.frostwire.jlibtorrent.TorrentInfo? {
        start()
        saveDir.mkdirs()
        // Кэш метаданных: повторный вход в раздачу — мгновенно, без пиров
        val cacheKey = Regex("urn:btih:([0-9a-fA-F]{40})").find(source)?.groupValues?.get(1)?.lowercase()
            ?: Integer.toHexString(source.hashCode())
        val cacheFile = File(saveDir, "meta_$cacheKey.torrent")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val ti = com.frostwire.jlibtorrent.TorrentInfo.bdecode(cacheFile.readBytes())
                onStatus("Состав из кэша")
                return ti
            } catch (_: Exception) {
                cacheFile.delete()
            }
        }
        onStatus("Получение метаданных…")
        val bytes: ByteArray? = try {
            when {
                source.startsWith("magnet:") -> {
                    val enriched = enrichMagnet(source)
                    val t0 = System.currentTimeMillis()
                    val ticker = Thread {
                        while (System.currentTimeMillis() - t0 < 90_000) {
                            try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
                            onStatus("Ищу источники… ${(System.currentTimeMillis() - t0) / 1000} сек")
                        }
                    }
                    ticker.isDaemon = true
                    ticker.start()
                    try {
                        session.fetchMagnet(enriched, 90, saveDir)
                    } finally {
                        ticker.interrupt()
                    }
                }
                source.startsWith("http") -> URL(source).readBytes()
                else -> File(source).readBytes()
            }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            onStatus("Метаданные не получены — нет сидов или ссылка битая")
            return null
        }
        // Сохраним в кэш — в следующий раз состав откроется мгновенно
        runCatching { cacheFile.writeBytes(bytes) }
        return try {
            com.frostwire.jlibtorrent.TorrentInfo.bdecode(bytes)
        } catch (e: Exception) {
            onStatus("Не удалось разобрать раздачу")
            null
        }
    }

    fun startStream(
        source: String,
        saveDir: File,
        onStatus: (String) -> Unit,
        fileMatcher: ((String) -> Boolean)? = null,
        /** Прямой выбор файла (для «☰ Серии»): перекрывает matcher. */
        fileIndex: Int = -1,
    ): StreamSession? {
        start()
        saveDir.mkdirs()
        // Политика кэша: перед новым стримом освобождаем место —
        // досмотренное и старьё под нож, недосмотренное живёт до лимита
        runCatching { com.torrenttv.app.torrent.TorrentCache.enforceLimit(saveDir) }

        // Кэш метаданных: для знакомой раздачи плеер стартует без пиров
        val cacheKeyS = Regex("urn:btih:([0-9a-fA-F]{40})").find(source)?.groupValues?.get(1)?.lowercase()
            ?: Integer.toHexString(source.hashCode())
        val cacheFileS = File(saveDir, "meta_$cacheKeyS.torrent")

        onStatus("Получение метаданных…")
        val bytes: ByteArray? = if (cacheFileS.exists() && cacheFileS.length() > 0) {
            runCatching { cacheFileS.readBytes() }.getOrNull()
        } else try {
            when {
                source.startsWith("magnet:") -> {
                    val enriched = enrichMagnet(source)
                    com.torrenttv.app.data.AppLog.log(
                        "player",
                        "fetchMagnet: trackers added=${enriched != source}, timeout=90s",
                    )
                    // Живой статус: поиск метаданных занимает обычно 10-30 сек,
                    // без обратной связи экран выглядит зависшим
                    val t0 = System.currentTimeMillis()
                    val ticker = Thread {
                        while (System.currentTimeMillis() - t0 < 90_000) {
                            try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
                            val sec = (System.currentTimeMillis() - t0) / 1000
                            onStatus("Ищу источники фильма… $sec сек")
                        }
                    }
                    ticker.isDaemon = true
                    ticker.start()
                    try {
                        session.fetchMagnet(enriched, 90, saveDir)
                    } finally {
                        ticker.interrupt()
                    }
                }
                source.startsWith("http") -> URL(source).readBytes()
                else -> File(source).readBytes()
            }
        } catch (e: Exception) {
            com.torrenttv.app.data.AppLog.log("player", "fetchMagnet error: ${e.message}")
            null
        }
        if (bytes == null) {
            onStatus("Метаданные не получены — проверьте ссылку и наличие сидов")
            return null
        }
        // Пополнить кэш метаданных
        runCatching { cacheFileS.writeBytes(bytes) }

        val ti = TorrentInfo.bdecode(bytes)
        val fs = ti.files()
        val numFiles = fs.numFiles()
        val best = if (fileIndex in 0 until numFiles) fileIndex else bestFileIndex(fs, fileMatcher)
        if (best == -1) {
            onStatus("В раздаче нет файлов")
            return null
        }
        val bestSize = fs.fileSize(best)

        var fileOffset = 0L
        for (i in 0 until best) fileOffset += fs.fileSize(i)

        onStatus("Подключение к раздаче…")
        try {
            session.download(ti, saveDir)
        } catch (e: Exception) {
            onStatus("Ошибка добавления торрента: ${e.message}")
            return null
        }

        // TorrentHandle появляется асинхронно — ждём до 15 секунд
        var handle: TorrentHandle? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (handle == null && System.currentTimeMillis() < deadline) {
            handle = try {
                session.find(ti.infoHashV1())
            } catch (_: Exception) {
                null
            }
            if (handle == null) Thread.sleep(200)
        }
        val h = handle ?: run {
            onStatus("Торрент не добавился — нет пиров?")
            return null
        }

        try {
            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        } catch (_: Exception) {
        }
        // Предзагрузка следующей серии (как у Stremio): следующий видеофайл
        // с приоритетом LOW вместо IGNORE — докачивается после текущей серии,
        // не отнимая скорость. Переключение серий становится мгновенным
        val st = StreamSession(h, ti, best, fileOffset, bestSize, saveDir)
        try {
            val order = st.videoFiles()
            val pos = order.indexOfFirst { it.first == best }
            val next = if (pos >= 0) order.getOrNull(pos + 1) else null
            val priorities = Array(numFiles) { i ->
                when {
                    i == best -> Priority.NORMAL
                    next != null && i == next.first -> Priority.TWO
                    else -> Priority.IGNORE
                }
            }
            h.prioritizeFiles(priorities)
        } catch (_: Exception) {
            // fallback: только текущий файл
            try {
                h.prioritizeFiles(Array(numFiles) { if (it == best) Priority.NORMAL else Priority.IGNORE })
            } catch (_: Exception) {
            }
        }

        return st
    }

    /**
     * Выбор файла: 1) совпавший с fileMatcher среди видео (самый большой),
     * 2) совпавший без учёта расширения, 3) самый большой видеофайл,
     * 4) просто самый большой. -1, если файлов нет.
     */
    private fun bestFileIndex(
        fs: com.frostwire.jlibtorrent.FileStorage,
        fileMatcher: ((String) -> Boolean)?,
    ): Int {
        val numFiles = fs.numFiles()
        val largest = { predicate: (Int) -> Boolean ->
            var best = -1
            var bestSize = -1L
            for (i in 0 until numFiles) {
                if (predicate(i) && fs.fileSize(i) > bestSize) {
                    best = i
                    bestSize = fs.fileSize(i)
                }
            }
            best
        }
        val isVideo = { i: Int -> fs.fileName(i).substringAfterLast('.', "").lowercase() in VIDEO_EXT }

        if (fileMatcher != null) {
            largest { i -> isVideo(i) && fileMatcher(fs.fileName(i)) }.let { if (it != -1) return it }
            largest { i -> fileMatcher(fs.fileName(i)) }.let { if (it != -1) return it }
        }
        largest(isVideo).let { if (it != -1) return it }
        return largest { true }
    }

    /**
     * Полное скачивание (резервный путь): скачать торрент целиком и вернуть
     * самый большой видеофайл. Блокирующий вызов — запускать из фонового потока.
     */
}
