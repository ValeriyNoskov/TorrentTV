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
 * Готовность данных проверяется НА УРОВНЕ КУСКОВ (havePiece) — как в открытых
 * стримерах (stremio/torrent-stream): fileProgress() возвращает сумму скачанных
 * кусков без гарантии непрерывности, после перемотки в середину он даёт ложную
 * готовность, и плеер получал «конец файла» в середине фильма.
 * Перемотка в середину работает через boostPiecesAround (приоритеты кусков).
 */
class TorrentDataSource(private val st: TorrentEngine.StreamSession) : DataSource {

    private var raf: RandomAccessFile? = null
    // Позиция в ТОРРЕНТЕ (для готовности/приоритетов кусков): fileOffset + position
    private var pieceAbs = 0L
    // Позиция в ФАЙЛЕ серии на диске: multi-file торрент раскладывает пачку
    // в ОТДЕЛЬНЫЕ файлы, поэтому читаем файл с его собственной позиции 0.
    // Раньше сюда шёл fileOffset (смещение файла внутри пачки, ~2 ГБ для
    // 7-й серии) — чтение уходило за конец файла, ExoPlayer получал пустой
    // поток и PARSING_CONTAINER_UNSUPPORTED при полностью скачанной серии
    private var filePos = 0L

    override fun open(dataSpec: DataSpec): Long {
        pieceAbs = st.fileOffset + dataSpec.position
        filePos = dataSpec.position
        // Seek в середину: поднимаем приоритет кусков вокруг новой позиции,
        // иначе sequential-загрузка будет ползти с начала и read заблокируется
        if (dataSpec.position > 0) st.boostPiecesAround(pieceAbs)
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

        // Ждём, пока будут скачаны ВСЕ куски запрошенного диапазона.
        // Таймаут 90 с: без него заблокированный поток ExoPlayer зависает
        // молча (чёрный экран)
        val end = (pieceAbs + length).coerceAtMost(st.fileOffset + st.fileSize)
        val deadline = System.currentTimeMillis() + 90_000
        while (!st.isRangeReady(pieceAbs, end - pieceAbs)) {
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
        if (n <= 0) {
            // Конец файла — только если действительно конец. Иначе это ошибка
            // чтения (кусок есть в памяти, но не сброшен): END_OF_INPUT здесь
            // означал бы «фильм кончился» в середине — ложный STATE_ENDED
            if (filePos < st.fileSize) throw IOException("Кусок скачан, но не читается с диска")
            return C.RESULT_END_OF_INPUT
        }
        // pieceAbs/filePos уже сдвинуты в readOnce
        return n
    }

    private fun readOnce(f: RandomAccessFile, target: ByteArray, offset: Int, length: Int): Int {
        return try {
            // Читаем в КООРДИНАТАХ ФАЙЛА серии (filePos), не торрента
            f.seek(filePos)
            val limit = minOf(length.toLong(), st.fileSize - filePos).toInt().coerceAtLeast(0)
            if (limit == 0) -1 else f.read(target, offset, limit).also { n ->
                if (n > 0) {
                    pieceAbs += n
                    filePos += n
                }
            }
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
