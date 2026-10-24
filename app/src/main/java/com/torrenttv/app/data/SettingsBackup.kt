package com.torrenttv.app.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Облачный бэкап настроек в приватный Gist (токен уже есть — свой или вшитый).
 * Сброс данных/кеша на ТВ убивает SharedPreferences — из Gist всё восстанавливается
 * при следующем запуске (LocalStore.isEmpty() → restore).
 *
 * Экономия API: сохраняем только если снимок изменился с прошлой выгрузки.
 */
object SettingsBackup {

    private const val FILE = "vnoskino_settings.json"
    private const val DESC = "ВНосКино настройки (авто-бэкап)"
    private const val LIST_API = "https://api.github.com/gists"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastSavedHash: Int? = null

    /** Восстановить настройки, если локальные пусты (сброс/первый запуск). */
    fun restoreIfNeeded(context: Context, onRestored: (() -> Unit)? = null) {
        Thread {
            try {
                val store = LocalStore(context)
                if (!store.isEmpty()) return@Thread
                // 1) Свой сервер (домашний Mac) — основное хранилище.
                // Адрес/токен могли остаться в prefs, даже если сами настройки
                // стёрты не полностью; при полном сбросе берём резерв из Gist
                val fromServer = serverLoad(store)
                if (fromServer != null) {
                    store.restore(fromServer)
                    lastSavedHash = fromServer.toString().hashCode()
                    AppLog.log("backup", "настройки восстановлены с сервера (${fromServer.length()} ключей)")
                    onRestored?.invoke()
                    return@Thread
                }
                // 2) Резерв — приватный Gist
                val token = com.torrenttv.app.update.UpdateManager.ghToken(context)
                if (token.isBlank()) return@Thread
                val content = download(findGistId(token) ?: return@Thread, token) ?: return@Thread
                // Старые снимки могли сохраниться с управляющими символами
                // (Android org.json их не экранирует) — такой JSON не парсится.
                // Чистим и пробуем снова, иначе настройки «теряются» при сбросе
                val json = runCatching { JSONObject(content) }.getOrElse {
                    val fixed = content.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
                    AppLog.log("backup", "бэкап содержал управляющие символы — очищен и прочитан заново")
                    JSONObject(fixed)
                }
                store.restore(json)
                lastSavedHash = content.hashCode()
                AppLog.log("backup", "настройки восстановлены из облачного бэкапа (${json.length()} ключей)")
                onRestored?.invoke()
            } catch (e: Exception) {
                AppLog.log("backup", "восстановление FAIL: ${e.message}")
            }
        }.start()
    }

    /** Выгрузить настройки, если они менялись с прошлой выгрузки. */
    fun save(context: Context, force: Boolean = false) {
        Thread { doSave(context, force) }.start()
    }

    /** Синхронная выгрузка — для кнопки «Сохранить сейчас» (нужен результат). */
    fun saveNow(context: Context): Boolean =
        runCatching { doSave(context, force = true) }.getOrDefault(false)

    private fun doSave(context: Context, force: Boolean): Boolean {
        try {
            val store = LocalStore(context)
            if (store.isEmpty()) return false
            val token = com.torrenttv.app.update.UpdateManager.ghToken(context)
            val json = store.snapshot().toString()
            if (!force && json.hashCode() == lastSavedHash) return true   // ничего не менялось
            // Основное хранилище — свой сервер (настройки и кеш не должны
            // жить только на телевизоре). Gist остаётся резервом и хранит
            // адрес/токен сервера, чтобы после полного сброса было откуда
            // их взять.
            val onServer = serverSave(store, json)
            var onGist = false
            if (token.isNotBlank()) {
                val gistId = findGistId(token)
                onGist = if (gistId != null) patch(gistId, json, token) else create(json, token)
            }
            if (onServer || onGist) {
                lastSavedHash = json.hashCode()
                AppLog.log(
                    "backup",
                    "настройки сохранены: сервер=${if (onServer) "ок" else "—"}, Gist=${if (onGist) "ок" else "—"}",
                )
            } else {
                AppLog.log("backup", "сохранение FAIL: сервер и Gist недоступны")
            }
            return onServer || onGist
        } catch (e: Exception) {
            AppLog.log("backup", "сохранение FAIL: ${e.message}")
            return false
        }
    }

    // ---------- Свой сервер бэкапа ----------

    /** Сохранить снимок на домашний сервер (PUT /backup). */
    private fun serverSave(store: LocalStore, json: String): Boolean {
        val base = store.backupServerUrl()
        val token = store.backupServerToken()
        if (base.isBlank() || token.isBlank()) return false
        return runCatching {
            val req = Request.Builder().url("$base/backup")
                .header("X-Backup-Token", token)
                .put(json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrElse { false }
    }

    /** Прочитать снимок с домашнего сервера (GET /backup) или null. */
    private fun serverLoad(store: LocalStore): JSONObject? {
        val base = store.backupServerUrl()
        val token = store.backupServerToken()
        if (base.isBlank() || token.isBlank()) return null
        val body = runCatching {
            val req = Request.Builder().url("$base/backup")
                .header("X-Backup-Token", token)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    // ---------- GitHub Gist ----------

    private fun request(url: String, token: String, method: String, body: String?): Pair<Int, String> {
        val rb = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
        when (method) {
            "POST" -> rb.post(body!!.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            "PATCH" -> rb.patch(body!!.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            else -> rb.get()
        }
        http.newCall(rb.build()).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    /** Найти наш Gist по имени файла (секретный — в профиле не виден, но API его отдаёт). */
    private fun findGistId(token: String): String? {
        for (page in 1..3) {   // до 300 гистов — логи не должны так быстро расти
            val (code, body) = request("$LIST_API?per_page=100&page=$page", token, "GET", null)
            if (code != 200) return null
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                if (g.optJSONObject("files")?.has(FILE) == true) return g.optString("id")
            }
            if (arr.length() < 100) return null
        }
        return null
    }

    private fun download(gistId: String, token: String): String? {
        val (code, body) = request("$LIST_API/$gistId", token, "GET", null)
        if (code != 200) return null
        return JSONObject(body).optJSONObject("files")
            ?.optJSONObject(FILE)?.optString("content")?.takeIf { it.isNotBlank() }
    }

    private fun create(json: String, token: String): Boolean {
        val payload = JSONObject()
            .put("description", DESC)
            .put("public", false)
            .put("files", JSONObject().put(FILE, JSONObject().put("content", json)))
        return request(LIST_API, token, "POST", payload.toString()).first in 200..299
    }

    private fun patch(gistId: String, json: String, token: String): Boolean {
        val payload = JSONObject()
            .put("files", JSONObject().put(FILE, JSONObject().put("content", json)))
        return request("$LIST_API/$gistId", token, "PATCH", payload.toString()).first in 200..299
    }
}
