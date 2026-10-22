package com.torrenttv.app.torrent

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File
import java.net.URL

/**
 * Обёртка над jlibtorrent.
 *
 * MVP-стратегия: полный (последовательный) скачивание торрента, затем выбор
 * самого большого видеофайла. Потоковое воспроизведение по кускам (как TorrServe)
 * — следующий этап: локальный HTTP-сервер с Range-запросами поверх piece-буфера.
 */
object TorrentEngine {

    val session = SessionManager()

    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "webm", "wmv", "flv")

    fun start() {
        if (!session.isRunning) session.start()
    }

    /**
     * Скачать торрент и вернуть самый большой видеофайл.
     *
     * @param source magnet-ссылка, URL .torrent или локальный путь к .torrent
     * Блокирующий вызов — запускать из фонового потока.
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
