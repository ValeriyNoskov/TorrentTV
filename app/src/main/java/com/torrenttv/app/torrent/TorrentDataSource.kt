package com.torrenttv.app.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.RandomAccessFile

/**
 * DataSource для ExoPlayer: читает видео напрямую из недокачанного файла торрента.
 *
 * Чтение блокируется, пока последовательная загрузка не доберётся до нужного
 * диапазона байт. Благодаря sequential download скачанная часть файла —
 * непрерывная «голова», поэтому условие готовности простое: fileProgress >= конец запроса.
 * Перемотка в середину работает через boostPiecesAround (приоритеты кусков).
 */
class TorrentDataSource(private val st: TorrentEngine.StreamSession) : DataSource {

    private var raf: RandomAccessFile? = null
    private var absPos = 0L

    override fun open(dataSpec: DataSpec): Long {
        absPos = st.fileOffset + dataSpec.position
        // Seek в середину: поднимаем приоритет кусков вокруг новой позиции,
        // иначе sequential-загрузка будет ползти с начала и read заблокируется
        if (dataSpec.position > 0) st.boostPiecesAround(absPos)
        raf = RandomAccessFile(st.videoFile, "r")
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            st.fileSize - dataSpec.position
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val f = raf ?: throw IOException("DataSource closed")

        // Ждём, пока загрузка доберётся до нужного диапазона. Таймаут 90 с:
        // без него заблокированный поток ExoPlayer зависает молча (чёрный экран)
        val end = absPos + length
        val deadline = System.currentTimeMillis() + 90_000
        while (st.headBytes() < end) {
            if (!TorrentEngine.session.isRunning) throw IOException("Торрент-сессия остановлена")
            if (System.currentTimeMillis() > deadline) {
                throw IOException("Куски не загружаются — пиры недоступны")
            }
            Thread.sleep(250)
        }

        // Кусок мог быть ещё не сброшен на диск — делаем пару попыток
        var n = readOnce(f, target, offset, length)
        if (n <= 0) {
            Thread.sleep(250)
            n = readOnce(f, target, offset, length)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        absPos += n
        return n
    }

    private fun readOnce(f: RandomAccessFile, target: ByteArray, offset: Int, length: Int): Int {
        return try {
            f.seek(absPos)
            val limit = minOf(length.toLong(), st.fileSize - absPos).toInt().coerceAtLeast(0)
            if (limit == 0) -1 else f.read(target, offset, limit)
        } catch (_: Exception) {
            -1
        }
    }

    override fun getUri(): Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        // Прогресс передачи плееру не нужен: буферизацию показываем сами
    }

    override fun close() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
    }
}
