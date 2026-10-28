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
    @Volatile private var restoring = false
    @Volatile private var cachedGistId: String? = null

    /**
     * Секреты НЕ выгружаем в Gist: github-токен (даёт доступ ко всем гистам,
     * включая сами бэкапы и логи) и куки приватных трекеров. Ключи API
     * (КП/TMDB) оставляем — они нужны для полного восстановления и не
     * дают доступа к аккаунту GitHub.
     */
    private val SECRET_KEY_PREFIXES = listOf("github_token", "tracker_cookie_")

    /** Снимок настроек без секретов (для выгрузки в Gist). */
    private fun sanitizedJson(store: LocalStore): String {
        val o = store.snapshot()
        for (k in o.keys().asSequence().toList()) {
            if (SECRET_KEY_PREFIXES.any { k.startsWith(it) }) o.remove(k)
        }
        return o.toString()
    }

    /** Восстановить настройки, если локальные пусты (сброс/первый запуск). */
    fun restoreIfNeeded(context: Context, onRestored: (() -> Unit)? = null) {
        Thread {
            try {
                val store = LocalStore(context)
                if (!store.isEmpty()) return@Thread
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
                restoring = true
                store.restore(json)
                restoring = false
                lastSavedHash = sanitizedJson(store).hashCode()
                AppLog.log("backup", "настройки восстановлены из облачного бэкапа (${json.length()} ключей)")
                onRestored?.invoke()
            } catch (e: Exception) {
                restoring = false
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
            // Идёт восстановление из бэкапа — не снимаем «частичный» снимок,
            // иначе обрезанная версия перезапишет хороший облачный бэкап
            if (restoring && !force) {
                AppLog.log("backup", "сохранение пропущено: идёт восстановление")
                return false
            }
            val store = LocalStore(context)
            if (store.isEmpty()) return false
            val token = com.torrenttv.app.update.UpdateManager.ghToken(context)
            val json = sanitizedJson(store)
            if (!force && json.hashCode() == lastSavedHash) return true   // ничего не менялось
            if (token.isBlank()) {
                AppLog.log("backup", "сохранение FAIL: нет GitHub-токена")
                return false
            }
            val gistId = findGistId(token)
            if (gistId != null) cachedGistId = gistId
            val ok = if (gistId != null) patch(gistId, json, token) else create(json, token)
            if (ok) {
                lastSavedHash = json.hashCode()
                AppLog.log("backup", "настройки сохранены в приватный Gist")
            } else {
                AppLog.log("backup", "сохранение FAIL: Gist недоступен")
            }
            return ok
        } catch (e: Exception) {
            AppLog.log("backup", "сохранение FAIL: ${e.message}")
            return false
        }
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
        // Найденный id кешируем: сканирование до 300 гистов на каждую выгрузку не нужно
        cachedGistId?.let { return it }
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
