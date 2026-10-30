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
        // Подробный лог: сразу видно, в каких координатах открывается файл
        com.torrenttv.app.data.AppLog.log(
            "player",
            "источник открыт: файл=${st.videoFile.name.take(40)} размер=${st.fileSize / 1024 / 1024}МБ " +
                "fileOffset=${st.fileOffset / 1024 / 1024}МБ pos=${dataSpec.position / 1024 / 1024}МБ " +
                "кусок#${pieceAbs / 1024 / 1024}МБ",
        )
        // Seek в середину: поднимаем приоритет кусков вокруг новой позиции.
        // SEQUENTIAL больше не включается — порядок задаётся приоритетами
        if (dataSpec.position > 0) st.boostPiecesAround(pieceAbs)
        raf = try {
            RandomAccessFile(st.videoFile, "r")
        } catch (e: Exception) {
            com.torrenttv.app.data.AppLog.log("player", "источник FAIL: файл не открыт: ${e.message}")
            throw e
        }
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
        // ВАЖНО: isRangeReady ждёт позицию ВНУТРИ файла и сам добавляет
        // fileOffset (абсолютный кусок). Передача абсолютной pieceAbs давала
        // двойное fileOffset (~4-5 ГБ) — готовность всегда false, ExoPlayer
        // висел в read() без READY и без ошибки (лог v0.9.42: ни «плеер
        // готов», ни playback error — пользователь выходил с чёрного экрана)
        val waitLen = minOf(length.toLong(), st.fileSize - filePos).coerceAtLeast(0)
        val deadline = System.currentTimeMillis() + 90_000
        var lastWaitLog = 0L
        var lastProgressLog = filePos
        var lastDiagLog = 0L
        while (waitLen > 0 && !st.isRangeReady(filePos, waitLen)) {
            // Ожидание кусков дольше 1 с — в лог: видно, движется ли готовность
            // (порог 5 с не срабатывал: пользователь выходил раньше первого лога)
            val now = System.currentTimeMillis()
            if (now - lastWaitLog > 1_000) {
                lastWaitLog = now
                com.torrenttv.app.data.AppLog.log(
                    "player",
                    "чтение ждёт куски: файлПоз=${filePos / 1024 / 1024}МБ " +
                        "готовность=${(st.readyFraction(filePos, waitLen) * 100).toInt()}% " +
                        "peers=${st.peers()} dht=${TorrentEngine.session.dhtNodes()} " +
                        "head=${st.headBytes() / 1024 / 1024}МБ",
                )
                // Раз в 5 с — диагностика кусков вокруг читаемой позиции:
                // have/приоритет каждого, чтобы видеть, доходит ли SEVEN
                // до движка и качается ли проблемный кусок
                if (now - lastDiagLog > 5_000) {
                    lastDiagLog = now
                    com.torrenttv.app.data.AppLog.log("player", st.piecesDiagAt(filePos, 4))
                }
            }
            if (!TorrentEngine.session.isRunning) throw IOException("Торрент-сессия остановлена")
            // Зависшие куски: если готовность окна не меняется 15 с при живых
            // пирах — форсим недостающие куски (setPieceDeadline): запросы
            // переотправляются, кусок качается вне очереди
            if (System.currentTimeMillis() - lastWaitLog > 15_000) {
                val forced = st.forcePieces(filePos, waitLen)
                if (forced > 0) {
                    com.torrenttv.app.data.AppLog.log(
                        "player",
                        "форс кусков: $forced шт. вокруг файлПоз=${filePos / 1024 / 1024}МБ (готовность стояла)",
                    )
                }
            }
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
            com.torrenttv.app.data.AppLog.log(
                "player",
                "чтение вернуло $n: файлПоз=$filePos размер=${st.fileSize} → " +
                    if (filePos < st.fileSize) "ОШИБКА (не конец файла)" else "конец файла",
            )
            if (filePos < st.fileSize) throw IOException("Кусок скачан, но не читается с диска")
            return C.RESULT_END_OF_INPUT
        }
        // pieceAbs/filePos уже сдвинуты в readOnce
        // Прогресс чтения каждые ~64 МБ — видно, доходит ли плеер до данных
        if (filePos - lastProgressLog > 64L * 1024 * 1024) {
            lastProgressLog = filePos
            com.torrenttv.app.data.AppLog.log(
                "player",
                "чтение идёт: файлПоз=${filePos / 1024 / 1024}МБ из ${st.fileSize / 1024 / 1024}МБ",
            )
        }
        return n
    }

    private fun readOnce(f: RandomAccessFile, target: ByteArray, offset: Int, length: Int): Int {
        return try {
            // Читаем в КООРДИНАТАХ ФАЙЛА серии (filePos), не торрента
            f.seek(filePos)
            val limit = minOf(length.toLong(), st.fileSize - filePos).toInt().coerceAtLeast(0)
            if (limit == 0) -1 else f.read(target, offset, limit).also { n ->
                if (n > 0) {
                    // Первое успешное чтение — в лог с hex первых байтов:
                    // валидный MKV начинается 1A 45 DF A3
                    if (filePos == 0L || (filePos - n) == 0L) {
                        val hex = target.drop(offset).take(8.coerceAtMost(n))
                            .joinToString(" ") { "%02X".format(it) }
                        com.torrenttv.app.data.AppLog.log(
                            "player",
                            "первое чтение ок: $n байт, начало: $hex",
                        )
                    }
                    pieceAbs += n
                    filePos += n
                } else if (n < 0) {
                    com.torrenttv.app.data.AppLog.log(
                        "player",
                        "read EOF: файлПоз=$filePos лимит=$limit размер=${st.fileSize}",
                    )
                }
            }
        } catch (e: Exception) {
            // Раньше исключение глоталось молча — теперь видно причину
            com.torrenttv.app.data.AppLog.log("player", "readOnce FAIL: ${e.javaClass.simpleName}: ${e.message}")
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
