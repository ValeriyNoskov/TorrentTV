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

    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "webm", "wmv", "flv")

    fun start() {
        if (!session.isRunning) session.start()
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

        /** Все видеофайлы торрента: (индекс, имя) — для выбора серии. */
        fun videoFiles(): List<Pair<Int, String>> {
            val fs = ti.files()
            val out = mutableListOf<Pair<Int, String>>()
            for (i in 0 until fs.numFiles()) {
                if (fs.fileName(i).substringAfterLast('.', "").lowercase() in VIDEO_EXT) {
                    out += i to fs.fileName(i)
                }
            }
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
        if (!source.startsWith("magnet:") || source.contains("&tr=")) return source
        return buildString {
            append(source.trimEnd('&'))
            for (t in com.torrenttv.app.data.TorrentSearch.TRACKERS) {
                append("&tr=")
                append(java.net.URLEncoder.encode(t, "UTF-8"))
            }
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

        onStatus("Получение метаданных…")
        val bytes: ByteArray? = try {
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
        try {
            val priorities = Array(numFiles) { if (it == best) Priority.NORMAL else Priority.IGNORE }
            h.prioritizeFiles(priorities)
        } catch (_: Exception) {
        }

        return StreamSession(h, ti, best, fileOffset, bestSize, saveDir)
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
    fun downloadLargestVideo(
        source: String,
        saveDir: File,
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): File? {
        start()
        saveDir.mkdirs()

        onStatus("Получение метаданных…")
        val bytes: ByteArray? = try {
            when {
                source.startsWith("magnet:") -> session.fetchMagnet(enrichMagnet(source), 90, saveDir)
                source.startsWith("http") -> URL(source).readBytes()
                else -> File(source).readBytes()
            }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            onStatus("Метаданные не получены — проверьте ссылку и наличие сидов")
            return null
        }

        val ti = TorrentInfo.bdecode(bytes)
        val totalBytes = ti.totalSize()
        onStatus("Загрузка… (${totalBytes / 1024 / 1024} МБ)")

        // Наблюдатель прогресса: по фактическому размеру файлов в папке
        val watcher = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val done = saveDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val pct = if (totalBytes > 0) ((done * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                onProgress(pct)
                if (pct >= 100) return@Thread
            }
        }
        watcher.isDaemon = true
        watcher.start()

        try {
            session.download(ti, saveDir)
        } catch (e: Exception) {
            onStatus("Ошибка загрузки: ${e.message}")
            return null
        }
        watcher.interrupt()
        onProgress(100)

        return saveDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }
            .maxByOrNull { it.length() }
    }
}
