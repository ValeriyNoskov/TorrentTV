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
        // Общий лимит на всю загрузку APK: без него при плохом соединении
        // «Скачивание…» висит вечно (readTimeout — лишь пауза между байтами)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

    // ---------- Фоновая загрузка APK ----------
    // Пока пользователь смотрит кино, APK тихо качается в кеш;
    // «Обновить» тогда ставит его мгновенно, без ожидания.
    @Volatile private var downloaded: Pair<UpdateInfo, File>? = null
    @Volatile private var downloading: Boolean = false

    /** APK уже скачан и ждёт установки? */
    fun readyToInstall(info: UpdateInfo): Boolean =
        downloaded?.first?.version == info.version

    /** Токен для GitHub: настройки ТВ → вшитый в сборку. */
    fun ghToken(context: Context): String =
        com.torrenttv.app.data.LocalStore(context).githubToken()
            .ifBlank { com.torrenttv.app.BuildConfig.GH_EMBEDDED_TOKEN }

    /**
     * Тихо скачать APK в кеш (если ещё не скачан). Ничего не показывает
     * и не устанавливает — результат ждёт в readyToInstall().
     */
    fun startBackgroundDownload(context: Context, info: UpdateInfo) {
        if (downloading || readyToInstall(info)) return
        downloading = true
        Thread {
            try {
                val request = Request.Builder().url(info.apkUrl)
                    .apply { val t = ghToken(context); if (t.isNotBlank()) header("Authorization", "Bearer $t") }
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val apkFile = File(context.cacheDir, "update-${info.version}.apk")
                    response.body!!.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    downloaded = info to apkFile
                    com.torrenttv.app.data.AppLog.log("update", "background download ok: v${info.version}")
                }
            } catch (e: Exception) {
                com.torrenttv.app.data.AppLog.log("update", "background download FAIL: ${e.message}")
            } finally {
                downloading = false
            }
        }.start()
    }

    /**
     * Проверить наличие обновления. Результат — в колбеке (главный поток).
     * @param token GitHub-токен (настройки ТВ или вшитый) — обязателен
     *              для приватного репозитория
     * @param onError сетевая ошибка/провал API (отличать от «нет обновления»)
     */
    fun checkForUpdate(
        currentVersion: String,
        token: String = "",
        onResult: (UpdateInfo?) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val json = JSONObject(response.body!!.string())

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
                        "проверка: последний релиз v$tag, текущая v$currentVersion → " +
                            if (newer) "доступно обновление" else "у вас последняя",
                    )
                    val info = if (newer) UpdateInfo(tag, apkUrl, json.optString("body", "").take(500)) else null
                    mainHandler.post { onResult(info) }
                }
            } catch (e: Exception) {
                com.torrenttv.app.data.AppLog.log("update", "проверка FAIL: ${e.message}")
                mainHandler.post { onError(e.message ?: "нет сети") }
            }
        }.start()
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
                JSONObject(response.body!!.string()).optString("body", "").take(1500)
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
    fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (String) -> Unit, onError: (String) -> Unit) {
        // Уже скачано в фоне — ставим сразу, без скачивания
        if (readyToInstall(info)) {
            onProgress("Установка v${info.version}…")
            installApk(context, downloaded!!.second)
            return
        }
        Thread {
            try {
                mainHandler.post { onProgress("Скачивание v${info.version}…") }
                val request = Request.Builder().url(info.apkUrl)
                    .apply { val t = ghToken(context); if (t.isNotBlank()) header("Authorization", "Bearer $t") }
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val apkFile = File(context.cacheDir, "update-${info.version}.apk")
                    val body = response.body!!
                    val total = body.contentLength()
                    com.torrenttv.app.data.AppLog.log("update", "скачивание v${info.version}: ${if (total > 0) "${total / 1024 / 1024} МБ" else "размер неизвестен"}")
                    body.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            // Прогресс в процентах: без него «Скачивание…»
                            // неотличимо от зависания (проверено на ТВ)
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            var lastPct = -1
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                done += read
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        mainHandler.post { onProgress("Скачивание v${info.version}… $pct%") }
                                        // В лог — каждые 10%: по нему видно темп и где встало
                                        if (pct % 10 == 0) com.torrenttv.app.data.AppLog.log("update", "скачивание v${info.version}: $pct%")
                                    }
                                }
                            }
                        }
                    }
                    com.torrenttv.app.data.AppLog.log("update", "скачивание v${info.version}: готово, запуск установки")
                    mainHandler.post { onProgress("Установка v${info.version}…") }
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                mainHandler.post { onError("Ошибка обновления: ${e.message}") }
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
}
