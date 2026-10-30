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
                val request = Request.Builder().url(info.apkUrl)
                    .apply { val t = ghToken(context); if (t.isNotBlank()) header("Authorization", "Bearer $t") }
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    // Пишем во временный файл: убитый посреди закачки процесс
                    // не оставит «битый» APK под финальным именем
                    val tmpFile = File(context.cacheDir, "update-${info.version}.apk.part")
                    val apkFile = File(context.cacheDir, "update-${info.version}.apk")
                    val body = response.body ?: throw IllegalStateException("пустой ответ сервера")
                    val total = body.contentLength()
                    com.torrenttv.app.data.AppLog.log(
                        "update",
                        "скачивание v${info.version}: ${if (total > 0) "${total / 1024 / 1024} МБ" else "размер неизвестен"} " +
                            "с ${info.apkUrl.substringBefore("/", "https://").removePrefix("https://").substringBefore("/")}",
                    )
                    body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            var lastPct = -1
                            var lastPctAt = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                done += read
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        // Скорость по последнему отрезку 10%:
                                        // по ней из Gist видно, кто тормозит
                                        val now = System.currentTimeMillis()
                                        val speed = if (lastPctAt > 0 && now > lastPctAt)
                                            (done - lastPct * total / 100) / ((now - lastPctAt) / 1000.0) / 1024 / 1024
                                        else 0.0
                                        lastPct = pct
                                        lastPctAt = now
                                        progressPct = pct
                                        // В лог — каждые 10%: по нему видно темп и где встало
                                        if (pct % 10 == 0) {
                                            com.torrenttv.app.data.AppLog.log("update", "скачивание v${info.version}: $pct% (${"%.1f".format(speed)} МБ/с)")
                                        }
                                    }
                                }
                            }
                            // Целостность: APK — это ZIP; размер сверяем с сервером
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
                }
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
     * GitVerse (зеркало в РФ). API GitVerse закрыт для анонимов (401 на
     * /releases/latest — «ошибка не удалось проверить http» на ТВ), поэтому
     * парсим анонимные HTML-страницы релизов:
     *  1) страница списка — первый тег `/releases/tag/vX` = новейший релиз;
     *  2) страница тега — APK виден как `<a download="VNosKino-X.apk"
     *     href="/api/attachments/<uuid>">`; attachment качается без токена.
     */
    private fun fetchGitverse(currentVersion: String): UpdateInfo? {
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
