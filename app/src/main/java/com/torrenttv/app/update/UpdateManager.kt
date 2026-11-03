package com.torrenttv.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Самообновление приложения.
 *
 * Принцип: при старте запрашиваем GitHub API
 * https://api.github.com/repos/ValeriyNoskov/TorrentTV/releases/latest
 * Если тег релиза (vX.Y.Z) новее текущей версии — скачиваем APK из вложений
 * релиза и открываем системный установщик.
 */
object UpdateManager {

    private const val RELEASES_API =
        "https://api.github.com/repos/ValeriyNoskov/TorrentTV/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Общий лимит на всю загрузку APK: GitHub из РФ отдаёт ~140 КБ/с —
        // 85 МБ занимает ~10 минут, прежние 10 минут обрывали закачку
        // по таймауту как раз у финала
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Источники обновлений: GitHub (исходный), GitVerse (зеркало в РФ),
    // auto — оба параллельно, чей ответ раньше. Выбор — в Настройках
    const val SOURCE_AUTO = "auto"
    const val SOURCE_GITHUB = "github"
    const val SOURCE_GITVERSE = "gitverse"

    data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

    // ---------- Фоновая загрузка APK ----------
    // Пока пользователь смотрит кино, APK тихо качается в кеш;
    // «Обновить» тогда ставит его мгновенно, без ожидания.
    @Volatile private var downloaded: Pair<UpdateInfo, File>? = null
    @Volatile private var downloading: Boolean = false
    // Атомарный флаг закачки: guard от параллельного старта двух потоков
    private val downloadingCAS = java.util.concurrent.atomic.AtomicBoolean(false)
    // Прогресс текущей закачки в %: его читает ожидающий поток («Обновить»,
    // нажатое во время фоновой скачки) — вместо мёртвой надписи «идёт в фоне»
    @Volatile private var progressPct: Int = -1

    /** APK уже скачан и ждёт установки? */
    fun readyToInstall(info: UpdateInfo): Boolean =
        downloaded?.first?.version == info.version

    /**
     * Свой GitHub-токен из Настроек (право Gists) — опциональная выгрузка логов
     * в Gist и обход rate limit GitHub API. По умолчанию логи идут в GitVerse
     * (tv-logs, вшитый LOG_TOKEN) — этот токен не нужен.
     */
    fun ghToken(context: Context): String =
        com.torrenttv.app.data.LocalStore(context).githubToken()

    /**
     * ЕДИНЫЙ процесс закачки APK. И фоновая скачка с главного экрана, и кнопка
     * «Обновить» качают только через него: поток всегда один, параллельная
     * скачка в один файл невозможна по построению (downloading + readyToInstall).
     * Прогресс публикуется в progressPct — кто хочет, тот его показывает.
     * Ничего не устанавливает — результат ждёт в readyToInstall().
     */
    private fun startDownload(context: Context, info: UpdateInfo) {
        // CAS вместо check-then-set: два потока («Обновить» + фоновая скачка)
        // иначе могли оба пройти проверку и писать в один файл двумя потоками
        if (!downloadingCAS.compareAndSet(false, true)) return
        if (readyToInstall(info)) { downloadingCAS.set(false); return }
        downloading = true
        progressPct = 0
        Thread {
            try {
                val isGitverse = info.apkUrl.contains("gitverse.ru")
                val tmpFile = File(context.cacheDir, "update-${info.version}.apk.part")
                val apkFile = File(context.cacheDir, "update-${info.version}.apk")

                // GitVerse-attachments ПРИВАТНОГО репо отдаются ТОЛЬКО
                // через api.gitverse.ru/attachments/<uuid> с Bearer +
                // Accept: application/vnd.gitverse.object+json (проверено:
                // gitverse.ru/api/attachments + "token" → 400, Bearer →
                // 401, api.gitverse.ru + Bearer + Accept → 200).
                // GitHub-URL — с пользовательским токеном.
                fun request(rangeStart: Long?, rangeEnd: Long?): Request {
                    val b = Request.Builder().url(info.apkUrl)
                    if (isGitverse) {
                        val t = gitverseToken()
                        if (t.isNotBlank()) {
                            b.header("Authorization", "Bearer $t")
                            b.header("Accept", "application/vnd.gitverse.object+json;version=1")
                        }
                    } else {
                        val t = ghToken(context)
                        if (t.isNotBlank()) b.header("Authorization", "Bearer $t")
                    }
                    if (rangeStart != null) b.header("Range", "bytes=$rangeStart-${rangeEnd ?: ""}")
                    return b.build()
                }

                // Полный размер файла: GitVerse — 1-байтовый Range 0-0
                // (полный размер в Content-Range: bytes 0-0/TOTAL)
                var total = -1L
                if (isGitverse) {
                    try {
                        http.newCall(request(0, 0)).execute().use { r ->
                            total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                        }
                    } catch (e: Exception) { total = -1L }
                }

                com.torrenttv.app.data.AppLog.log(
                    "update",
                    "скачивание v${info.version}: ${if (total > 0) "${total / 1024 / 1024} МБ" else "размер неизвестен"} " +
                        "с ${info.apkUrl.removePrefix("https://").substringBefore("/")}",
                )

                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                var lastPctAt = 0L
                // Скорость по последнему отрезку 10%: по ней из Gist видно,
                // кто тормозит. В лог — каждые 10%: темп и где встало
                fun report() {
                    if (total <= 0) return
                    val pct = (done * 100 / total).toInt()
                    if (pct == lastPct) return
                    val now = System.currentTimeMillis()
                    val speed = if (lastPctAt > 0 && now > lastPctAt)
                        (done - lastPct * total / 100) / ((now - lastPctAt) / 1000.0) / 1024 / 1024
                    else 0.0
                    lastPct = pct
                    lastPctAt = now
                    progressPct = pct
                    if (pct % 10 == 0) {
                        com.torrenttv.app.data.AppLog.log("update", "скачивание v${info.version}: $pct% (${"%.1f".format(speed)} МБ/с)")
                    }
                }

                if (isGitverse && total > 0) {
                    // GitVerse рвёт длинные HTTP/2-потоки: полный GET и даже
                    // Range "bytes=0-" отдаёт ~2.6 МБ и падает stream reset
                    // INTERNAL_ERROR (воспроизведено curl'ом и OkHttp), зато
                    // кусок 20 МБ с ЯВНЫМ конечным Range проходит целиком.
                    // Поэтому качаем кусками по 8 МБ с ретраями — короткий
                    // поток сервер разорвать не успевает
                    val CHUNK = 8L * 1024 * 1024
                    val chunkFile = File(context.cacheDir, "update-chunk.part")
                    tmpFile.delete() // прошлая неудачная закачка не смешается
                    while (done < total) {
                        val end = minOf(done + CHUNK, total) - 1
                        var ok = false
                        var lastErr: Exception? = null
                        for (attempt in 1..3) {
                            try {
                                http.newCall(request(done, end)).execute().use { response ->
                                    if (response.code != 206) throw IllegalStateException("HTTP ${response.code} (кусок $done-$end)")
                                    val input = response.body?.byteStream()
                                        ?: throw IllegalStateException("пустой ответ сервера")
                                    chunkFile.outputStream().use { output ->
                                        var read: Int
                                        var got = 0L
                                        while (input.read(buf).also { read = it } != -1) {
                                            output.write(buf, 0, read)
                                            got += read
                                        }
                                        if (got != end - done + 1) {
                                            throw IllegalStateException("кусок недокачан: $got из ${end - done + 1}")
                                        }
                                    }
                                }
                                // Кусок целиком — дописываем к результату
                                chunkFile.inputStream().use { i ->
                                    java.io.FileOutputStream(tmpFile, true).use { i.copyTo(it, buf.size) }
                                }
                                ok = true
                            } catch (e: Exception) {
                                lastErr = e
                                Thread.sleep(2000L * attempt)
                            }
                        }
                        if (!ok) throw lastErr ?: IllegalStateException("кусок $done-$end не скачался")
                        done = end + 1
                        report()
                    }
                    chunkFile.delete()
                } else {
                    // GitHub: обычный полный GET (без нареканий)
                    http.newCall(request(null, null)).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                        val body = response.body ?: throw IllegalStateException("пустой ответ сервера")
                        if (total <= 0) total = body.contentLength()
                        body.byteStream().use { input ->
                            tmpFile.outputStream().use { output ->
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    output.write(buf, 0, read)
                                    done += read
                                    report()
                                }
                            }
                        }
                        // Целостность: размер сверяем с сервером
                        if (total > 0 && done != total) {
                            throw IllegalStateException("файл скачан не полностью: $done из $total")
                        }
                    }
                }
            if (tmpFile.length() < 4 || tmpFile.inputStream().use { s ->
                ByteArray(2).also { s.read(it) }.toString(Charsets.ISO_8859_1) != "PK"
            }) {
                tmpFile.delete()
                throw IllegalStateException("файл не похож на APK (нет ZIP-сигнатуры)")
            }
            tmpFile.renameTo(apkFile) || run {
                tmpFile.delete()
                throw IllegalStateException("не удалось переименовать временный файл")
            }
            downloaded = info to apkFile
            com.torrenttv.app.data.AppLog.log("update", "скачивание v${info.version}: готово")
        } catch (e: Exception) {
                com.torrenttv.app.data.AppLog.log("update", "скачивание v${info.version} FAIL: ${e.message}")
            } finally {
                downloading = false
                downloadingCAS.set(false)
                progressPct = -1
            }
        }.start()
    }

    /** Тихо скачать APK в кеш (если ещё не качается / не скачан). Фон с главного экрана. */
    fun startBackgroundDownload(context: Context, info: UpdateInfo) {
        startDownload(context, info)
    }

    /**
     * Проверить наличие обновления. Результат — в колбеке (главный поток).
     * @param token GitHub-токен (настройки ТВ или вшитый) — обязателен
     *              для приватного репозитория
     * @param onError сетевая ошибка/провал API (отличать от «нет обновления»)
     * @param source github | gitverse | auto (параллельно, чей ответ раньше)
     */
    fun checkForUpdate(
        currentVersion: String,
        token: String = "",
        onResult: (UpdateInfo?) -> Unit,
        onError: (String) -> Unit = {},
        source: String = "auto",
    ) {
        Thread {
            // Авто: оба источника параллельно, чей ответ пришёл первым —
            // тот и используем (GitVerse из РФ обычно отвечает быстрее)
            if (source == SOURCE_AUTO) {
                // ВАЖНО: null от fetch* — это УСПЕХ «у вас последняя», а не ошибка.
                // Раньше null трактовался как fail — и авто-режим показывал
                // «Не удалось проверить» при работавшей проверке (жалоба владельца)
                val lock = Object()
                var gotGithub: UpdateInfo? = null
                var gotGitverse: UpdateInfo? = null
                var noUpdate = false // хоть один источник ответил «у вас последняя»
                var firstErr: String? = null
                var finished = 0
                fun handle(r: kotlin.Result<UpdateInfo?>, name: String) {
                    synchronized(lock) {
                        if (r.isSuccess) {
                            noUpdate = true
                            val v = r.getOrNull()
                            if (v != null) {
                                if (name == "GitVerse") gotGitverse = v else gotGithub = v
                            }
                        } else if (firstErr == null) {
                            firstErr = "$name: ${r.exceptionOrNull()?.message ?: "не удалось"}"
                        }
                        finished++
                        (lock as Object).notifyAll()
                    }
                }
                Thread {
                    handle(runCatching { fetchGithub(currentVersion, token) }, "GitHub")
                }.start()
                Thread {
                    handle(runCatching { fetchGitverse(currentVersion) }, "GitVerse")
                }.start()
                // Ждём ОБА ответа: выбор источника не по скорости проверки
                // (GitHub-API отвечает за 0.3 с и всегда «выигрывал»), а по
                // скорости СКАЧКИ — GitVerse (зеркало в РФ) отдаёт APK в ~20
                // раз быстрее GitHub из РФ (владелец: браузер 20 с, апдейтер
                // >10 мин). Обе проверки теперь быстрые — HTML-парс
                synchronized(lock) {
                    while (finished < 2) lock.wait()
                }
                // Приоритет GitVerse: найдено там — берём оттуда
                val got = gotGitverse ?: gotGithub
                mainHandler.post {
                    if (got != null) onResult(got)
                    else if (noUpdate) onResult(null)
                    else onError(firstErr ?: "нет сети")
                }
                return@Thread
            }
            try {
                val info = when (source) {
                    SOURCE_GITVERSE -> fetchGitverse(currentVersion)
                    else -> fetchGithub(currentVersion, token)
                }
                mainHandler.post { onResult(info) }
            } catch (e: Exception) {
                com.torrenttv.app.data.AppLog.log("update", "проверка ($source) FAIL: ${e.message}")
                mainHandler.post { onError(e.message ?: "нет сети") }
            }
        }.start()
    }

    private fun fetchGithub(currentVersion: String, token: String): UpdateInfo? {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw IllegalStateException("пустой ответ сервера"))

            val tag = json.optString("tag_name", "").removePrefix("v")
            // Ищем первый .apk-ассет
            var apkUrl = ""
            val assets = json.optJSONArray("assets") ?: org.json.JSONArray()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "")
                    break
                }
            }

            val newer = isNewer(tag, currentVersion) && apkUrl.isNotEmpty()
            // Каждый факт проверки — в лог: «проверили → что в releases/latest → вердикт»
            com.torrenttv.app.data.AppLog.log(
                "update",
                "проверка [GitHub]: последний релиз v$tag, текущая v$currentVersion → " +
                    if (newer) "доступно обновление" else "у вас последняя",
            )
            return if (newer) UpdateInfo(tag, apkUrl, json.optString("body", "").take(500)) else null
        }
    }

    /**
     * GitVerse (зеркало в РФ). Работает через API С ТОКЕНОМ (вшитый LOG_TOKEN —
     * тот же, что для логов): репозиторий можно сделать приватным, обновления
     * продолжат приходить. HTML-парсинг остался как фолбэк (старые сценарии:
     * токен не декодировался и т.п.).
     *
     * API: GET /repos/ChipN/TorrentTV/releases?per_page=1 → JSON, первый
     * элемент = новейший релиз (tag_name, body, assets[].uuid).
     * APK качается по api.gitverse.ru/attachments/<uuid> с Bearer —
     * единственный путь, работающий для приватного репо.
     */
    private fun fetchGitverse(currentVersion: String): UpdateInfo? {
        // Основной путь: API с токеном (работает и для приватного репо)
        try {
            val token = gitverseToken()
            if (token.isNotBlank()) {
                val request = Request.Builder()
                    .url("https://api.gitverse.ru/repos/ChipN/TorrentTV/releases?per_page=1")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.gitverse.object+json;version=1")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val arr = org.json.JSONArray(
                        response.body?.string() ?: throw IllegalStateException("пустой ответ сервера"),
                    )
                    val rel = arr.getJSONObject(0)
                    val tag = rel.optString("tag_name", "").removePrefix("v")
                    if (tag.isBlank()) throw IllegalStateException("в ответе API нет tag_name")
                    if (!isNewer(tag, currentVersion)) {
                        com.torrenttv.app.data.AppLog.log(
                            "update",
                            "проверка [GitVerse]: последний релиз v$tag, текущая v$currentVersion → у вас последняя",
                        )
                        return null
                    }
                    // APK: attachment релиза. browser_download_url отдаёт 404.
                    // Приватный репо: вложение отдаётся ТОЛЬКО через
                    // api.gitverse.ru/attachments/<uuid> с Bearer+Accept
                    // (gitverse.ru/api/attachments с "token" в приватном — 400)
                    val assets = rel.getJSONArray("assets")
                    var apkUuid = ""
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name", "").endsWith(".apk")) {
                            apkUuid = a.optString("uuid", "")
                            break
                        }
                    }
                    if (apkUuid.isBlank()) {
                        // Релиз уже создан, но CI ещё не прикрепил APK
                        // (задержка 4-6 мин) — НЕ ошибка, молча ждём
                        com.torrenttv.app.data.AppLog.log(
                            "update",
                            "проверка [GitVerse]: релиз v$tag уже есть, APK ещё не прикреплён CI → у вас последняя",
                        )
                        return null
                    }
                    val apkUrl = "https://api.gitverse.ru/attachments/$apkUuid"
                    com.torrenttv.app.data.AppLog.log(
                        "update",
                        "проверка [GitVerse]: последний релиз v$tag, текущая v$currentVersion → доступно обновление",
                    )
                    return UpdateInfo(tag, apkUrl, rel.optString("body", "").take(500))
                }
            }
        } catch (e: Exception) {
            com.torrenttv.app.data.AppLog.log(
                "update",
                "проверка [GitVerse]: API не удался (${e.message}) → пробую HTML-страницы",
            )
        }
        // Фолбэк: анонимные HTML-страницы (работает, пока репозиторий публичный)
        val listHtml = getHtml("https://gitverse.ru/ChipN/TorrentTV/releases")
        val tag = Regex("/ChipN/TorrentTV/releases/tag/v(\\d+\\.\\d+\\.\\d+)")
            .find(listHtml)?.groupValues?.get(1)
            ?: throw IllegalStateException("на странице релизов нет тега версии")
        if (!isNewer(tag, currentVersion)) {
            com.torrenttv.app.data.AppLog.log(
                "update",
                "проверка [GitVerse]: последний релиз v$tag, текущая v$currentVersion → у вас последняя",
            )
            return null
        }
        // Новее текущей: страница тега → ссылка на APK-attachment
        val tagHtml = getHtml("https://gitverse.ru/ChipN/TorrentTV/releases/tag/v$tag")
        val apkUrl = Regex("""download="[^"]*\.apk"[^>]*href="/api/attachments/([0-9a-f-]+)"""")
            .find(tagHtml)?.let { "https://gitverse.ru/api/attachments/${it.groupValues[1]}" }
            ?: Regex("/api/attachments/([0-9a-f-]+)")
                .find(tagHtml)?.let { "https://gitverse.ru/api/attachments/${it.groupValues[1]}" }
            ?: throw IllegalStateException("у релиза v$tag нет APK на странице")
        com.torrenttv.app.data.AppLog.log(
            "update",
            "проверка [GitVerse]: последний релиз v$tag, текущая v$currentVersion → доступно обновление",
        )
        return UpdateInfo(tag, apkUrl, "")
    }

    /**
     * Вшитый GitVerse-токен (обфусцированный LOG_TOKEN — тот же, что шлёт логи
     * в tv-logs). Даёт доступ к приватному репозиторию: обновления работают
     * и после «Сделать приватным».
     */
    private fun gitverseToken(): String = runCatching {
        com.torrenttv.app.Obf.decode(
            com.torrenttv.app.BuildConfig.LOG_TOKEN_OBF, com.torrenttv.app.BuildConfig.OBF_KEY,
        )
    }.getOrDefault("")

    /** GET HTML-страницы GitVerse анонимно, с проверкой кода. */
    private fun getHtml(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "text/html").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string() ?: throw IllegalStateException("пустой ответ сервера")
        }
    }

    /**
     * Описание релиза конкретной версии (для «Что нового» после обновления).
     * Блокирующий вызов — из фонового потока. null, если описания нет.
     */
    fun releaseNotes(version: String, token: String = ""): String? {
        if (version.isBlank()) return null
        return try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/ValeriyNoskov/TorrentTV/releases/tags/v$version")
                .header("Accept", "application/vnd.github+json")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string() ?: throw IllegalStateException("пустой ответ сервера")).optString("body", "").take(1500)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Сравнение версий X.Y.Z: true, если remote новее текущей. */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** Скачать APK в кеш и запустить установку. */
    /**
     * Установить обновление с экрана «Обновить». Сам не качает: подключается
     * к единому процессу startDownload (свой не плодим) и ждёт, показывая
     * его проценты. Если процесс упал — одна повторная попытка.
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (String) -> Unit, onError: (String) -> Unit) {
        // Уже скачано — ставим сразу
        if (readyToInstall(info)) {
            onProgress("Установка v${info.version}…")
            installApk(context, downloaded!!.second)
            return
        }
        startDownload(context, info)
        waitAndInstall(context, info, onProgress, onError, retried = false)
    }

    /** Ждать окончания единой закачки, публикуя её проценты, затем установить. */
    private fun waitAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (String) -> Unit,
        onError: (String) -> Unit,
        retried: Boolean,
    ) {
        Thread {
            var lastShown = -1
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                if (readyToInstall(info)) {
                    mainHandler.post { onProgress("Установка v${info.version}…") }
                    installApk(context, downloaded!!.second)
                    return@Thread
                }
                // Процесс закончился, а файла нет — упал
                if (!downloading) break
                val pct = progressPct
                if (pct != lastShown) {
                    lastShown = pct
                    mainHandler.post {
                        onProgress(
                            if (pct >= 0) "Скачивание v${info.version}… $pct%"
                            else "Скачивание v${info.version}…",
                        )
                    }
                }
                Thread.sleep(1000)
            }
            if (!readyToInstall(info)) {
                if (!retried) {
                    // Одна повторная попытка: закачка могла упасть (сеть и т.п.)
                    mainHandler.post { onProgress("Скачивание не удалось — пробую ещё раз…") }
                    startDownload(context, info)
                    waitAndInstall(context, info, onProgress, onError, retried = true)
                } else {
                    mainHandler.post { onError("Не удалось скачать обновление — проверьте сеть и попробуйте позже") }
                }
            }
        }.start()
    }

    private fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updateprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Имя apk, скачанного и ожидающего установки (для чистки старых — см. cleanupApks). */
    fun pendingApkName(): String? = downloaded?.second?.name

    /**
     * Убрать APK прошлых обновлений из кеша: после установки файл остаётся
     * (~80 МБ на каждое обновление) и копится сутками. Вызывается при старте
     * приложения; скачанный и ожидающий установки APK не трогаем.
     */
    fun cleanupApks(context: Context) {
        val pending = pendingApkName()
        runCatching {
            context.cacheDir.listFiles { f -> f.name.startsWith("update-") && f.name.endsWith(".apk") }
                ?.forEach { if (it.name != pending) it.delete() }
        }
    }
}
