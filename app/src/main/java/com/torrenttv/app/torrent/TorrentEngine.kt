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

        /** Сколько байт выбранного файла скачано; при sequential это непрерывная «голова» файла. */
        fun headBytes(): Long = try {
            handle.fileProgress().getOrNull(fileIndex) ?: 0L
        } catch (_: Exception) {
            0L
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
     */
    fun startStream(source: String, saveDir: File, onStatus: (String) -> Unit): StreamSession? {
        start()
        saveDir.mkdirs()

        onStatus("Получение метаданных…")
        val bytes: ByteArray? = try {
            when {
                source.startsWith("magnet:") -> session.fetchMagnet(source, 60, saveDir)
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
        val fs = ti.files()
        val numFiles = fs.numFiles()

        // Самый большой видеофайл (fallback — просто самый большой файл)
        var best = -1
        var bestSize = -1L
        for (i in 0 until numFiles) {
            val ext = fs.fileName(i).substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXT && fs.fileSize(i) > bestSize) {
                best = i
                bestSize = fs.fileSize(i)
            }
        }
        if (best == -1) {
            for (i in 0 until numFiles) {
                if (fs.fileSize(i) > bestSize) {
                    best = i
                    bestSize = fs.fileSize(i)
                }
            }
        }
        if (best == -1) {
            onStatus("В раздаче нет файлов")
            return null
        }

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
                source.startsWith("magnet:") -> session.fetchMagnet(source, 60, saveDir)
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
