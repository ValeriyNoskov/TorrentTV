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
        }
        // Отдача ограничена: узкий аплинк ТВ, залитый безлимитной отдачей,
        // душил хендшейки и приём кусков (ревью движка)
        runCatching { sp.uploadRateLimit(2 * 1024 * 1024) }
        // Фиксированный порт вместо дефолтного 6881 (часто занят/фильтрован
        // роутерами) — UPnP пробросит его на роутере
        runCatching { sp.listenInterfaces("0.0.0.0:49013,[::]:49013") }
        // Широкий список DHT-роутеров: холодная сессия находит первый
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
        // Настройки передаются В start() через SessionParams: applySettings
        // до старта сессии мог молча теряться (native-сессии ещё нет).
        // Состояние прошлой сессии (DHT-кеш, пиры) ускоряет тёплый старт,
        // но несёт СВОИ настройки — наш тюнинг (порт, лимиты) применяем
        // поверх восстановленного, иначе порт и лимиты терялись
        val stateF = stateFile
        try {
            val params = if (stateF != null && stateF.exists() && stateF.length() > 0) {
                runCatching {
                    com.frostwire.jlibtorrent.SessionParams(stateF.readBytes())
                        .apply { setSettings(sp); setPosixDiskIO() }
                }.getOrNull()
            } else null
            // POSIX-диск вместо mmap-дефолта libtorrent 2.0: mmap сбрасывает
            // куски на диск ЛЕНИВО (havePiece=true, а на диске ещё нули) —
            // плеер читает файл напрямую и получал нули вместо заголовка MKV
            // (PARSING_CONTAINER_UNSUPPORTED при скачанном на 95% файле).
            // POSIX пишет кусок в файл сразу при завершении
            val sessionParams = (params ?: com.frostwire.jlibtorrent.SessionParams(sp))
            runCatching { sessionParams.setPosixDiskIO() }
            session.start(sessionParams)
            com.torrenttv.app.data.AppLog.log(
                "engine",
                "сессия запущена: DHT=${session.isDhtRunning()}" +
                    (if (params != null) ", состояние восстановлено" else ""),
            )
            installDiagnostics()
            // Привязка listen-порта асинхронна: сразу после старта endpoints
            // пустые (в логе v0.9.32 было «порт=[]») — логируем с задержкой
            Thread {
                runCatching {
                    Thread.sleep(3000)
                    if (session.isRunning) {
                        com.torrenttv.app.data.AppLog.log(
                            "engine",
                            "порт через 3с: endpoints=${session.listenEndpoints()}, " +
                                "DHT-узлов=${session.dhtNodes()}",
                        )
                    }
                }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            runCatching { session.start() }
            com.torrenttv.app.data.AppLog.log(
                "engine",
                "сессия запущена с дефолтными настройками (тюнинг не применился!): ${e.message}",
            )
        }
    }

    /**
     * Диагностика «пиры не найдены» по alert'ам libtorrent (v0.9.33).
     * По логу теперь видно: привязался ли порт, пробросил ли UPnP,
     * ответили ли трекеры/DHT по хешу раздачи и почему нет.
     * types()==null — все типы (так работает AlertMulticaster).
     */
    private fun installDiagnostics() {
        session.addListener(object : com.frostwire.jlibtorrent.AlertListener {
            override fun types(): IntArray? = null

            override fun alert(a: com.frostwire.jlibtorrent.alerts.Alert<*>) {
                try {
                    when (a.type()) {
                        com.frostwire.jlibtorrent.alerts.AlertType.LISTEN_SUCCEEDED -> {
                            val al = a as com.frostwire.jlibtorrent.alerts.ListenSucceededAlert
                            com.torrenttv.app.data.AppLog.log(
                                "engine",
                                "порт привязан: ${al.address()}:${al.port()} (${al.socketType()})",
                            )
                        }
                        com.frostwire.jlibtorrent.alerts.AlertType.LISTEN_FAILED -> {
                            val al = a as com.frostwire.jlibtorrent.alerts.ListenFailedAlert
                            com.torrenttv.app.data.AppLog.log(
                                "engine",
                                "порт НЕ привязан: ${al.listenInterface()} — ${al.error().message()} " +
                                    "(входящие соединения работать не будут)",
                            )
                        }
                        com.frostwire.jlibtorrent.alerts.AlertType.PORTMAP_ERROR ->
                            com.torrenttv.app.data.AppLog.log("engine", "UPnP/NAT-PMP: проброс порта не удался")
                        com.frostwire.jlibtorrent.alerts.AlertType.TRACKER_REPLY -> {
                            val al = a as com.frostwire.jlibtorrent.alerts.TrackerReplyAlert
                            // Ответы без пиров не логируем — шум; важно, где ЕСТЬ пиры
                            if (al.numPeers() > 0) {
                                com.torrenttv.app.data.AppLog.log(
                                    "engine",
                                    "трекер ${al.trackerUrl()} отдал ${al.numPeers()} пиров",
                                )
                            }
                        }
                        com.frostwire.jlibtorrent.alerts.AlertType.TRACKER_ERROR -> {
                            val al = a as com.frostwire.jlibtorrent.alerts.TrackerErrorAlert
                            com.torrenttv.app.data.AppLog.log(
                                "engine",
                                "трекер ${al.trackerUrl()} FAIL: ${al.errorMessage()} (${al.timesInRow()} раз подряд)",
                            )
                        }
                        com.frostwire.jlibtorrent.alerts.AlertType.DHT_REPLY -> {
                            val al = a as com.frostwire.jlibtorrent.alerts.DhtReplyAlert
                            com.torrenttv.app.data.AppLog.log(
                                "engine",
                                "DHT отдала ${al.numPeers()} пиров по хешу раздачи",
                            )
                        }
                        else -> {}
                    }
                } catch (_: Exception) {
                    // alert-диагностика не должна ронять движок
                }
            }
        })
    }

    /** Куда сохранять состояние сессии; выставляет MainActivity при старте. */
    @Volatile var stateFile: File? = null

    /** Сохранить состояние сессии (DHT-кеш, пиры) — тёплый старт в следующий раз. */
    fun saveState() {
        val f = stateFile ?: return
        if (!session.isRunning) return
        runCatching {
            f.writeBytes(session.saveState())
            com.torrenttv.app.data.AppLog.log("engine", "состояние сессии сохранено (${f.length()} байт)")
        }
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

        /**
         * Диагностика первых кусков: have и приоритет по каждому. Нужно понять
         * «качает, но не играет»: если куски заголовка не have при SEVEN —
         * приоритеты не дошли до движка либо у пиров нет начала файла.
         */
        fun headPiecesDiag(n: Int): String = try {
            val sb = StringBuilder("диагностика кусков [0..${n - 1}]: ")
            for (i in 0 until n) {
                val pr = try { handle.piecePriority(i).name } catch (_: Exception) { "?" }
                val have = try { handle.havePiece(i) } catch (_: Exception) { false }
                sb.append(if (have) "✓" else "✗").append("(").append(pr.first()).append(") ")
            }
            val pieceLen = ti.pieceLength()
            sb.append("pieceLen=").append(pieceLen)
            sb.toString()
        } catch (e: Exception) {
            "диагностика кусков FAIL: ${e.message}"
        }

        /**
         * Повторная установка SEVEN на заголовок (4 МБ от начала файла) и индекс
         * Cues (последние 8 МБ). Нужна потому, что prioritizeFiles() применяется
         * движком асинхронно и затирает piece-приоритеты, выставленные сразу
         * после него (диагностика: куски 0-7 = NORMAL вместо SEVEN, ready=0%
         * при 500 МБ скачанного — «качает, но не играет»).
         */
        fun ensureCriticalPieces() {
            try {
                val pieceLen = ti.pieceLength()
                if (pieceLen <= 0) return
                val n = ti.numPieces()
                val endAbs = fileOffset + fileSize
                val lastPiece = ((endAbs - 1) / pieceLen).toInt().coerceIn(0, n - 1)
                val headTo = ((fileOffset + 4L * 1024 * 1024) / pieceLen).toInt().coerceIn(0, lastPiece)
                val cueFrom = ((endAbs - 8L * 1024 * 1024).coerceAtLeast(fileOffset) / pieceLen)
                    .toInt().coerceIn(0, lastPiece)
                for (i in 0..headTo) handle.piecePriority(i, Priority.SEVEN)
                for (i in cueFrom..lastPiece) handle.piecePriority(i, Priority.SEVEN)
            } catch (_: Exception) {
            }
        }

        /**
         * Диагностика кусков, начиная с startPiece: have и приоритет по каждому.
         * «Качает, но не играет»: если куски не have при SEVEN — приоритеты не
         * дошли до движка либо у подключённых пиров нет этих кусков.
         */
        fun headPiecesDiag(startPiece: Int, n: Int): String = try {
            val sb = StringBuilder("диагностика кусков [$startPiece..${startPiece + n - 1}]: ")
            for (i in startPiece until startPiece + n) {
                if (i < 0 || i >= ti.numPieces()) continue
                val pr = try { handle.piecePriority(i).name } catch (_: Exception) { "?" }
                val have = try { handle.havePiece(i) } catch (_: Exception) { false }
                sb.append("$i=").append(if (have) "✓" else "✗").append("(").append(pr.first()).append(") ")
            }
            sb.append("pieceLen=${ti.pieceLength()} seeds=${seeds()}")
            sb.toString()
        } catch (e: Exception) {
            "диагностика кусков FAIL: ${e.message}"
        }

        /** Диагностика кусков вокруг позиции ВНУТРИ файла (для лога чтения). */
        fun piecesDiagAt(filePos: Long, n: Int): String = try {
            val pieceLen = ti.pieceLength()
            headPiecesDiag(((fileOffset + filePos) / pieceLen).toInt(), n)
        } catch (_: Exception) {
            "диагностика FAIL"
        }

        /**
         * Форс докачки зависших кусков диапазона: setPieceDeadline(i, 0) —
         * libtorrent переотправляет запросы и качает кусок вне очереди.
         * Нужен для сигнатуры «один кусок ✗(S) при соседях ✓(S) и живых
         * пирах»: запросы куска висят у пиров и без deadline не
         * переотправляются (диагностика: 591=✗(S) при 590/592/593=✓).
         */
        fun forcePieces(filePos: Long, length: Long): Int = try {
            val pieceLen = ti.pieceLength()
            if (pieceLen <= 0) 0
            else {
                val abs = fileOffset + filePos
                val end = (abs + length).coerceAtMost(fileOffset + fileSize)
                val first = (abs / pieceLen).toInt().coerceIn(0, ti.numPieces() - 1)
                val last = ((end - 1).coerceAtLeast(abs) / pieceLen).toInt().coerceIn(0, ti.numPieces() - 1)
                var forced = 0
                for (i in first..last) {
                    val have = try { handle.havePiece(i) } catch (_: Exception) { false }
                    if (!have) {
                        runCatching {
                            handle.piecePriority(i, Priority.SEVEN)
                            handle.setPieceDeadline(i, 0)
                            forced++
                        }
                    }
                }
                forced
            }
        } catch (_: Exception) {
            0
        }

        /** Снять последовательную загрузку: она качает строго по порядку и
         *  не даёт приоритетам кусков (индекс MKV в конце, перемотка) работать. */
        fun clearSequential() {
            try {
                handle.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                com.torrenttv.app.data.AppLog.log("engine", "sequential снят: приоритеты кусков активны")
            } catch (_: Exception) {
            }
        }

        /** Число сидов отдельно: «пиры есть, но 0 сидов» — другой диагноз. */
        fun seeds(): Int = try {
            handle.status().numSeeds()
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
         * скачаны (havePiece) — как в открытых стримерах (stremio/torrent-stream): fileProgress() тут
         * не годится: он суммирует скачанные куски без непрерывности, после
         * перемотки даёт ложную готовность.
         *
         * ВАЖНО (v0.9.35): одной готовности кусков мало — цикл буферизации
         * и DataSource выходили «готово» при peers=0/head=0 (лог v0.9.33:
         * PARSING_CONTAINER_UNSUPPORTED — плеер прочитал нули разрежённого
         * файла). Двойной критерий: куски есть И байты реально на диске.
         */
        fun isRangeReady(filePos: Long, length: Long): Boolean {
            val pieceOk = readyFraction(filePos, length) >= 1f
            if (!pieceOk) return false
            // Защита от ложной готовности: если скачано 0 байт файла —
            // куски «готовы» только по ошибке расчёта, читать рано
            return headBytes() > 0 || length <= 0L
        }

        /** Доля готовых кусков в диапазоне файла [filePos, filePos+length), 0..1. */
        fun readyFraction(filePos: Long, length: Long): Float {
            try {
                val pieceLen = ti.pieceLength()
                if (pieceLen <= 0) {
                    // Диагностика v0.9.33-лога: здесь цикл буферизации и DataSource
                    // мгновенно считали «готово» при 0 пиров — плеер читал нули
                    com.torrenttv.app.data.AppLog.log(
                        "engine",
                        "WARN: pieceLength=$pieceLen — готовность кусков не считается, считаю готовым",
                    )
                    return 1f
                }
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

        /**
         * Окно просмотра: качаем только [absolutePos, absolutePos+spanBytes),
         * остальной файл (кроме заголовка и хвоста с индексом Cues) — ZERO,
         * чтобы не докачивать фильм целиком. Хвост нужен MKV-плееру (Cues),
         * начало — заголовок EBML/Tracks, поэтому они всегда SEVEN.
         * Перемотка назад: куски уже на диске; далеко вперёд — дозагрузка окна.
         */
        fun boostWindow(absolutePos: Long, spanBytes: Long) {
            try {
                val pieceLen = ti.pieceLength()
                if (pieceLen <= 0) return
                val n = ti.numPieces()
                val from = (absolutePos / pieceLen).toInt().coerceIn(0, n - 1)
                val to = ((absolutePos + spanBytes) / pieceLen).toInt().coerceIn(0, n - 1)
                // Защищённые зоны: заголовок (4 МБ от начала файла) и индекс
                // Cues (последние 8 МБ файла) — без них ExoPlayer/VLC висят
                val headTo = ((fileOffset + 4L * 1024 * 1024) / pieceLen).toInt().coerceIn(0, n - 1)
                val cueFrom = ((fileOffset + fileSize - 8L * 1024 * 1024).coerceAtLeast(fileOffset) / pieceLen)
                    .toInt().coerceIn(0, n - 1)
                for (i in from..to) handle.piecePriority(i, Priority.SEVEN)
                // Понижаем всё вне окна внутри НАШЕГО файла (соседние файлы
                // пачки не трогаем — у них свои приоритеты)
                val fFirst = (fileOffset / pieceLen).toInt().coerceIn(0, n - 1)
                val fLast = ((fileOffset + fileSize - 1) / pieceLen).toInt().coerceIn(0, n - 1)
                for (i in fFirst..fLast) {
                    if (i in from..to || i <= headTo || i >= cueFrom) continue
                    handle.piecePriority(i, Priority.IGNORE)
                }
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

        /** Приостановить докачку (приложение свернуто, внешний плеер не открыт). */
        fun pauseDownload() {
            runCatching { handle.pause() }
        }

        /** Возобновить докачку после возврата приложения. */
        fun resumeDownload() {
            runCatching { handle.resume() }
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
        /** Предзагрузка следующей серии (настройка Настройки → Плеер). */
        preloadNext: Boolean = true,
        /** TTL недосмотренного, мс (0 = без TTL). См. TorrentCache.enforceLimit. */
        retentionMs: Long = 0,
    ): StreamSession? {
        start()
        saveDir.mkdirs()
        // Политика кэша: перед новым стримом освобождаем место —
        // досмотренное и старьё под нож, недосмотренное живёт до лимита
        runCatching { com.torrenttv.app.torrent.TorrentCache.enforceLimit(saveDir, retentionMs = retentionMs) }

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
            onStatus("Торрент не добавился за 15 с (проблема движка, не пиров)")
            return null
        }

        // Форс-анонс сразу: при холодном DHT (0 узлов первые 1-2 мин) пассивное
        // ожидание длилось минуты — теперь трекеры/DHT опрашиваются немедленно
        runCatching { if (session.dhtNodes() > 0) h.forceDHTAnnounce() }
        com.torrenttv.app.data.AppLog.log(
            "engine",
            "торрент добавлен: файлов=$numFiles, выбранный=$best, всего в сессии=${session.swig().get_torrents().size}",
        )

        // БЕЗ SEQUENTIAL_DOWNLOAD: он качает строго по порядку и душит
        // приоритеты — индекс MKV (Cues) в конце пачки не скачался бы
        // никогда (лог 17:19-17:45: ExoPlayer прыгал в конец и ждал вечно).
        // Порядок задаётся приоритетами: заголовок и Cues — SEVEN ниже,
        // остальное NORMAL. Скорость раздач 5-8 МБ/с при битрейте ~0.5 МБ/с
        // даёт запас, равномерная докачка всего файла не помешает просмотру
        // Предзагрузка следующей серии (как у Stremio): следующий видеофайл
        // с приоритетом TWO вместо IGNORE — докачивается после текущей серии,
        // не отнимая скорость
        val st = StreamSession(h, ti, best, fileOffset, bestSize, saveDir)
        try {
            val order = st.videoFiles()
            val pos = order.indexOfFirst { it.first == best }
            val next = if (preloadNext) {
                if (pos >= 0) order.getOrNull(pos + 1) else null
            } else null
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
        // Индекс MKV (Cues) лежит в КОНЦЕ файла: без него ExoPlayer/VLC
        // прыгают в конец и висят, пока индекс не скачается. Плюс заголовок
        // (EBML/Tracks) в начале: он нужен плееру первым делом
        runCatching {
            val pieceLen = ti.pieceLength()
            if (pieceLen > 0) {
                val endAbs = fileOffset + bestSize
                val lastPiece = ((endAbs - 1) / pieceLen).toInt().coerceIn(0, ti.numPieces() - 1)
                val cueFrom = ((endAbs - 8L * 1024 * 1024).coerceAtLeast(fileOffset) / pieceLen).toInt()
                    .coerceIn(0, lastPiece)
                for (i in cueFrom..lastPiece) h.piecePriority(i, Priority.SEVEN)
                // Заголовок: первые 4 МБ файла
                val headTo = ((fileOffset + 4L * 1024 * 1024) / pieceLen).toInt()
                    .coerceIn(0, lastPiece)
                for (i in 0..headTo) h.piecePriority(i, Priority.SEVEN)
                com.torrenttv.app.data.AppLog.log(
                    "engine",
                    "приоритеты: заголовок куски 0-$headTo, Cues куски $cueFrom-$lastPiece = SEVEN",
                )
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
