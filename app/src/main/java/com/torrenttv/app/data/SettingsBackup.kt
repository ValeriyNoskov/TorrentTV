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
                val token = com.torrenttv.app.update.UpdateManager.ghToken(context)
                if (token.isBlank()) return@Thread
                val content = download(findGistId(token) ?: return@Thread, token) ?: return@Thread
                val json = JSONObject(content)
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
    fun save(context: Context) {
        Thread {
            try {
                val store = LocalStore(context)
                if (store.isEmpty()) return@Thread
                val token = com.torrenttv.app.update.UpdateManager.ghToken(context)
                if (token.isBlank()) return@Thread
                val json = store.snapshot().toString()
                if (json.hashCode() == lastSavedHash) return@Thread   // ничего не менялось
                val gistId = findGistId(token)
                val ok = if (gistId != null) patch(gistId, json, token) else create(json, token)
                if (ok) {
                    lastSavedHash = json.hashCode()
                    AppLog.log("backup", "настройки сохранены в облачный бэкап")
                } else {
                    AppLog.log("backup", "сохранение FAIL: API не принял")
                }
            } catch (e: Exception) {
                AppLog.log("backup", "сохранение FAIL: ${e.message}")
            }
        }.start()
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
