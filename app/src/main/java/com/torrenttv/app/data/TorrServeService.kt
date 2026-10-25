package com.torrenttv.app.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Клиент TorrServe/TorrServer (https://github.com/YouROK) — внешний движок
 * стриминга торрентов. Если приложение установлено и сервер отвечает,
 * используем его вместо внутреннего: добавляем magnet через /torrents,
 * играем файл через /stream/<name>?link=<hash>&index=<N>&play.
 */
object TorrServeService {

    /** Кэш карты кусков /cache: hash + время + завершённые куски. */
    private val cacheLock = Any()
    private var cacheAt = 0L
    private var cacheHash = ""
    private var cacheDone: HashSet<Int>? = null
    private var cachePlen = 0L

    /** Одновременно не больше 2 preload-соединений — иначе TorrServe захлёбывается. */
    private val preloadSlots = java.util.concurrent.Semaphore(2)

    const val PACKAGE = "ru.yourok.torrserve"

    /** После неудачи не дёргаем TorrServe 10 минут (чтобы не заставлять ждать дважды). */
    @Volatile private var disabledUntil = 0L

    fun isDisabled(): Boolean = System.currentTimeMillis() < disabledUntil

    private fun disable() {
        disabledUntil = System.currentTimeMillis() + 10 * 60_000L
    }

    data class TSFile(val id: Int, val path: String, val length: Long)

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Один раз за сессию пробуем сами открыть TorrServe (его Activity стартует сервер). */
    @Volatile private var launchAttempted = false
    @Volatile private var lastLaunchAt = 0L

    /**
     * Готов ли TorrServe к работе: отвечает на /echo. Если установлен, но спит —
     * один раз за сессию запускаем его Activity (она стартует сервер) и ждём.
     * false — ждём слишком долго или не установлен: работаем внутренним движком.
     */
    fun ensureRunning(ctx: Context, host: String, waitMs: Long = 10_000L): Boolean {
        if (echo(host)) return true
        if (!isInstalled(ctx)) return false
        // Повторные попытки запуска: раньше — один раз за процесс, после
        // первого неуспеха TorrServe не поднимался до перезапуска приложения
        val now = System.currentTimeMillis()
        if (launchAttempted && now - lastLaunchAt < 60_000L) return false
        launchAttempted = true
        lastLaunchAt = now
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { intent ->
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            if (echo(host)) {
                // TorrServe поднялся — сразу возвращаем пользователя в ВНосКино:
                // старт чужой активити выносит её вперёд, оставлять там нельзя
                runCatching {
                    val back = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    back?.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    ctx.startActivity(back)
                }
                return true
            }
        }
        return false
    }

    private fun client(): OkHttpClient = TorrentHttp.client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // add: не ждём дольше — быстрый fallback на встроенный движок
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Сервер жив? GET /echo. */
    fun echo(host: String): Boolean {
        if (isDisabled()) return false
        return try {
            TorrentHttp.client.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
                .newCall(Request.Builder().url("$host/echo").build())
                .execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun postJson(host: String, body: String, path: String = "/torrents"): String? {
        val req = Request.Builder()
            .url("$host$path")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    /**
     * Удалить торрент из TorrServe: освобождает кеш сервера (используется при
     * удалении тайтса из избранного — «в избранном = качаем на сервер,
     * удалил из избранного = снесли с сервера»).
     */
    fun removeTorrent(host: String, hash: String): Boolean {
        val req = JSONObject().put("action", "rem").put("hash", hash).toString()
        val resp = postJson(host, req) ?: run {
            AppLog.log("serverdl", "torrserve: rem $hash… — сервер не ответил")
            return false
        }
        // Формат ответа зависит от версии: {"ok":true} или пустой — считаем
        // успехом сам факт ответа без «error»
        val err = runCatching { JSONObject(resp).optString("error", "") }.getOrDefault("")
        val ok = err.isBlank()
        AppLog.log("serverdl", "torrserve: rem $hash… → ${if (ok) "удалён" else "FAIL: $err"}")
        return ok
    }

    /** Хэш из магнит-ссылки (urn:btih:…) или null. */
    fun hashOf(magnet: String): String? =
        Regex("urn:btih:([0-9a-fA-F]{40})").find(magnet)?.groupValues?.get(1)?.lowercase()

    /**
     * Добавить magnet и дождаться метаданных:
     * 1) POST /torrents action=add — сервер возвращает hash, метаданные ещё не готовы;
     * 2) GET /stream/<title>?link=<hash>&index=0&preload — родной способ TorrServer
     *    запустить загрузку метаданных (соединение держится до готовности);
     * 3) POST /torrents action=get — поллим file_stats каждые 3 сек.
     * Возвращает торрент с непустым списком файлов или null по таймауту/ошибке.
     */
    fun addMagnet(
        host: String,
        magnet: String,
        title: String,
        timeoutMs: Long = 60_000L,
        onStatus: (String) -> Unit = {},
    ): TSTorrent? {
        val req = JSONObject()
            .put("action", "add")
            .put("link", magnet)
            .put("title", title)
            .put("poster", "")
            .put("category", "")
            .put("save_to_db", false)
            .toString()
        val resp = postJson(host, req) ?: run {
            onStatus("TorrServe не ответил")
            disable()
            return null
        }
        val ts = runCatching { parseTorrent(JSONObject(resp)) }.getOrNull() ?: run {
            disable()
            return null
        }
        if (ts.hash.isBlank()) {
            disable()
            return null
        }
        // Что вернул сервер: stat/пиры видны сразу после add — если торрент
        // уже есть в сессии, состояние видно без догадок (полное логирование)
        AppLog.log(
            "player",
            "torrserve: add → hash=${ts.hash.take(12)}… stat=${ts.stat}, пиров=${ts.peers}, " +
                "preloaded=${ts.preloadedBytes / 1024 / 1024} МБ, файлов=${ts.files.size}",
        )

        // preload в отдельном потоке: соединение держится, сервер качает метаданные
        val preloadTitle = ts.title.ifBlank { title }
        Thread {
            runCatching {
                client().newCall(
                    Request.Builder()
                        .url("$host/stream/${URLEncoder.encode(preloadTitle, "UTF-8")}?link=${ts.hash}&index=0&preload")
                        .build()
                ).execute().use { it.body?.bytes() }
            }
        }.apply { isDaemon = true }.start()

        onStatus("Получаю метаданные через TorrServe…")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000)
            val t = get(host, ts.hash)
            if (t != null) {
                if (t.stat < 0) { // -1 = сломанный торрент
                    onStatus("TorrServe: раздача недоступна")
                    disable()
                    return null
                }
                if (t.files.isNotEmpty()) {
                    disabledUntil = 0L
                    return t
                }
                val p = t.peers
                if (p > 0) onStatus("Ищу источники через TorrServe… пиры: $p")
            }
        }
        onStatus("TorrServe: метаданные не получены")
        // Сервер жив, но не качает — карантин, чтобы не ждать каждый раз
        disable()
        return null
    }

    fun get(host: String, hash: String): TSTorrent? {
        val resp = postJson(host, JSONObject().put("action", "get").put("hash", hash).toString())
            ?: return null
        return runCatching { parseTorrent(JSONObject(resp)) }.getOrNull()
    }

    /**
     * Список всех раздач на сервере (action=list) — для карточки сервера
     * в настройках: статус, число раздач, занятое кешем место.
     */
    fun listTorrents(host: String): List<TSTorrent>? {
        val resp = postJson(host, JSONObject().put("action", "list").toString()) ?: return null
        return runCatching {
            val arr = JSONArray(resp)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parseTorrent(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrNull()
    }

    /** Однострочная сводка о сервере: «отвечает · раздач N · кеш X, Y ГБ» / «не отвечает». */
    fun serverSummary(host: String): String {
        if (host.isBlank()) return "адрес не задан"
        val list = listTorrents(host) ?: return "⚠ не отвечает"
        val cached = list.sumOf { it.preloadedBytes } / 1024 / 1024 / 1024.0
        return "✓ отвечает · раздач ${list.size} · кеш ${"%.1f".format(cached)} ГБ"
    }

    private fun parseTorrent(o: JSONObject): TSTorrent {
        val files = mutableListOf<TSFile>()
        (o.optJSONArray("file_stats") ?: JSONArray()).let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                files += TSFile(f.optInt("id", i), f.optString("path", ""), f.optLong("length", 0))
            }
        }
        return TSTorrent(
            hash = o.optString("hash", ""),
            title = o.optString("title", o.optString("name", "")),
            stat = o.optInt("stat", 0),
            peers = o.optInt("total_peers", 0),
            preloadedBytes = o.optLong("preloaded_bytes", 0),
            preloadSize = o.optLong("preload_size", 0),
            files = files,
        )
    }

    data class TSTorrent(
        val hash: String,
        val title: String,
        val stat: Int = 0,
        val peers: Int = 0,
        val preloadedBytes: Long = 0,
        val preloadSize: Long = 0,
        val files: List<TSFile>,
    )

    /**
     * Префетч начала файла перед стримом (как родной клиент TorrServe):
     * держим preload-соединение на нужном index и ждём, пока сервер докачает
     * первые мегабайты — иначе плеер открывается на пустом файле (00:00).
     */
    fun preloadFile(
        host: String,
        hash: String,
        file: TSFile,
        timeoutMs: Long = 90_000L,
        thresholdBytes: Long? = null,
        onStatus: (String) -> Unit = {},
    ): Boolean {
        // thresholdBytes — докачать файл ЦЕЛИКОМ (качка на сервер): null = только
        // порог для старта стрима (32 МБ)
        val name = file.path.substringAfterLast('/')
        // Лимит одновременных preload-соединений: разведка/гонка плеера +
        // автокачалка дают до 5-6 параллельных — TorrServe на таком числе
        // захлёбывается (лог v0.9.19: и стрим стоит на 0 с, и докачка на
        // 0 КБ/с при живых пирах). Два слота: гонке хватает, качалка ждёт.
        preloadSlots.acquire()
        Thread {
            try {
                runCatching {
                    client().newCall(
                        Request.Builder()
                            .url("$host/stream/${URLEncoder.encode(name, "UTF-8")}?link=$hash&index=${file.id}&preload")
                            .build()
                    ).execute().use { it.body?.bytes() }
                }
            } finally {
                preloadSlots.release()
            }
        }.apply { isDaemon = true }.start()

        // Порог прогрева: для 4K-файлов (десятки ГБ, битрейт 40–80 Мбит/с) 4 МБ
        // мало — плеер стартует и сразу упирается в буфер. Даём до 32 МБ:
        // старт дольше на десятки секунд, зато без заиканий в начале.
        // thresholdBytes задаёт качать до конца файла (режим «скачать на сервер»)
        val threshold = thresholdBytes ?: minOf(32L * 1024 * 1024, maxOf(4L * 1024 * 1024, file.length / 20))
        val deadline = System.currentTimeMillis() + timeoutMs
        var firstCheck = true
        var lastProgress = -1L
        var lastProgressAt = System.currentTimeMillis()
        var lastLogAt = 0L
        var stalled = 0
        var zeroStalls = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500)
            val t = get(host, hash) ?: continue
            // Прогресс именно этого файла (карта кусков /cache), а не всего
            // торрента: иначе в паке серий «буфер есть», а серия пустая
            val exact = runCatching { fileCachedBytes(host, hash, file, t.files) }.getOrNull()
            val pb = exact ?: t.preloadedBytes
            onStatus("Буферизация TorrServe… ${pb / 1024 / 1024} МБ из ~${threshold / 1024 / 1024}")
            // Детальный лог прогресса раз в ~7 с: качается ли файл вообще,
            // сколько пиров — по нему отличаем «медленно» от «неоткуда»
            val now = System.currentTimeMillis()
            if (now - lastLogAt >= 7000) {
                val speed = if (lastProgress >= 0 && now > lastLogAt) (pb - lastProgress) * 1000 / (now - lastLogAt) / 1024 else -1
                AppLog.log(
                    "player",
                    "preload «${file.path.substringAfterLast('/').take(50)}»: ${pb / 1024 / 1024}/" +
                        "${threshold / 1024 / 1024} МБ, скорость ~${if (speed >= 0) "$speed КБ/с" else "?"}, " +
                        "пиров=${t.peers}, stat=${t.stat}",
                )
                lastLogAt = now
            }
            if (pb >= threshold) {
                // pb — байты ИМЕННО этого файла (из /cache). Если сервер не
                // отдал карту кусков — это общий прогресс торрента, и тогда
                // порог не гарантирует, что нужная серия есть (v0.9.23: 101 МБ
                // «общего» у пустой серии). Поэтому без точных данных требуем
                // двойной порог — так «тёплый» пак не пускает на пустой файл.
                if (exact != null || pb >= threshold * 2) return true
            }
            firstCheck = false
            if (thresholdBytes == null) {
                // Ранние выходы — только для стрим-режима: полная закачка на
                // сервер терпит паузы между кусками (перестановка приоритетов)
                // Нет прогресса 4 замера подряд — скорость нулевая, выходим раньше
                stalled = if (pb > lastProgress) 0 else stalled + 1
                lastProgress = pb
                if (stalled >= 4 && pb > 0) return pb >= threshold
                // Полный ноль: пиров может не быть вовсе или они не отдают куски —
                // ждём только ~20 с (не все 90), чтобы раньше попробовать другой путь
                zeroStalls = if (pb == 0L) zeroStalls + 1 else 0
                if (zeroStalls >= 12) {
                    AppLog.log(
                        "player",
                        "preload нулевой: 20 с ни одного байта, пиров=${t.peers} — файл неоткуда скачать",
                    )
                    return false
                }
            } else {
                // Режим полной закачки: паузы между кусками терпим, но ПОЛНЫЙ
                // застой > 15 минут — признак беды: на сервере кончилось место
                // (запись падает, байты замирают) или раздача умерла. Бросаем
                // файл, иначе простаиваем до 6 ч на каждом.
                if (pb > lastProgress) lastProgressAt = now
                lastProgress = pb
                if (now - lastProgressAt > 15 * 60_000L) {
                    AppLog.log(
                        "serverdl",
                        "preload застой: 15 мин ни одного байта (${pb / 1024 / 1024} МБ из " +
                            "${threshold / 1024 / 1024}) — место на сервере кончилось или раздача умерла",
                    )
                    return false
                }
            }
        }
        AppLog.log("player", "preload FAIL: за ${timeoutMs / 1000} с не набрано ${threshold / 1024 / 1024} МБ (последнее: ${lastProgress / 1024 / 1024} МБ)")
        return false
    }

    /** Лучший видеофайл: самый крупный с видео-расширением; иначе просто самый крупный. */
    fun bestVideo(t: TSTorrent): TSFile? {
        val video = t.files.filter { f ->
            val n = f.path.lowercase()
            listOf(".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".webm", ".m4v", ".vob", ".mpg", ".flv", ".wmv").any { n.endsWith(it) }
        }
        return (video.ifEmpty { t.files }).maxByOrNull { it.length }
    }

    /**
     * Сколько байт НУЖНОГО файла уже в кеше TorrServe.
     *
     * `/torrents` отдаёт preloaded_bytes по всему торренту: в паке из 6 серий
     * «300 МБ буфера» могут быть в другой серии, и плеер стартовал на пустом
     * файле (0 байт → вечная буферизация). `/cache` отдаёт карту кусков
     * (Pieces: Id → Completed, как в клиенте TorrServe/Lampa) — считаем только
     * куски, попадающие в диапазон файла. Смещение = сумма длин предыдущих
     * файлов в порядке id. null — сервер не ответил (тогда общий счётчик).
     */
    fun fileCachedBytes(host: String, hash: String, file: TSFile, allFiles: List<TSFile>): Long? {
        // Карта кусков — тяжёлый ответ (тысячи кусков), а спрашиваем каждые
        // 1.5 с на каждом preload. Кэш на 3 с: прогресс всё равно заметен
        // только по кускам (4 МБ).
        val now0 = System.currentTimeMillis()
        var done: HashSet<Int>? = null
        var plen = 0L
        synchronized(cacheLock) {
            if (cacheHash == hash && now0 - cacheAt < 3000) {
                done = cacheDone
                plen = cachePlen
            }
        }
        if (done == null || plen <= 0) {
            val body = JSONObject().put("action", "get").put("hash", hash).toString()
            val resp = postJson(host, body, path = "/cache") ?: return null
            val o = runCatching { JSONObject(resp) }.getOrNull() ?: return null
            val pieces = o.optJSONObject("Pieces") ?: return null
            plen = o.optLong("PiecesLength", 4L * 1024 * 1024).let { if (it > 0) it else 4L * 1024 * 1024 }
            val set = HashSet<Int>()
            val keys = pieces.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val p = pieces.optJSONObject(k) ?: continue
                if (p.optBoolean("Completed", false)) {
                    set += p.optInt("Id", k.toIntOrNull() ?: -1)
                }
            }
            synchronized(cacheLock) {
                cacheHash = hash
                cacheAt = now0
                cacheDone = set
                cachePlen = plen
            }
            done = set
        }
        val ordered = allFiles.sortedBy { it.id }
        var offset = 0L
        for (f in ordered) {
            val len = if (f.length > 0) f.length else 0L
            if (f.id == file.id) {
                if (len <= 0) return 0
                val first = (offset / plen).toInt()
                val last = ((offset + len - 1) / plen).toInt()
                var bytes = 0L
                for (p in first..last) if (p in done) bytes += plen
                return minOf(bytes, len)
            }
            offset += len
        }
        return null
    }

    fun streamUrl(host: String, hash: String, file: TSFile): String {
        val name = file.path.substringAfterLast('/')
        return "$host/stream/${URLEncoder.encode(name, "UTF-8")}?link=$hash&index=${file.id}&play"
    }
}
