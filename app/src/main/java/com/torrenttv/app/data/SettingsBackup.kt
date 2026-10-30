package com.torrenttv.app.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Облачный бэкап настроек в приватный репозиторий ChipN/tv-logs на GitVerse
 * (токен вшит в APK обфусцированным — см. Obf.kt; раньше был GitHub Gist).
 * Сброс данных/кеша на ТВ убивает SharedPreferences — из tv-logs всё
 * восстанавливается при следующем запуске (LocalStore.isEmpty() → restore).
 *
 * Экономия API: сохраняем только если снимок изменился с прошлой выгрузки.
 */
object SettingsBackup {

    private const val FILE_PATH = "settings/vnoskino_settings.json"
    private const val CONTENTS_API = "https://api.gitverse.ru/repos/ChipN/tv-logs/contents"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastSavedHash: Int? = null
    @Volatile private var lastSavedKeyCount: Int = 0
    @Volatile private var restoring = false

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
                val token = logToken()
                if (token.isBlank()) return@Thread
                val content = download(token) ?: return@Thread
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
            val token = logToken()
            val json = sanitizedJson(store)
            // Защита облачного бэкапа от деградации: сброс настроек на ТВ
            // (прошивки убивают prefs) опустошал хранилище, и «пустой» снимок
            // из 2 ключей перезаписывал в облаке полный бэкап — настройки
            // терялись безвозвратно. Сильно урезанный снапшот не сохраняем:
            // пусть лучше бэкап останется полным (лог 09:19: снапшот 2 ключа
            // затёр сотни ключей)
            val keyCount = runCatching { org.json.JSONObject(json).length() }.getOrDefault(0)
            val known = lastSavedKeyCount
            if (known > 20 && keyCount < known / 2) {
                AppLog.log(
                    "backup",
                    "сохранение отклонено: снапшот подозрительно мал ($keyCount ключей против $known) — " +
                        "вероятен сброс настроек, облачный бэкап не тронут",
                )
                return false
            }
            if (!force && json.hashCode() == lastSavedHash) return true   // ничего не менялось
            if (token.isBlank()) {
                AppLog.log("backup", "сохранение FAIL: нет токена tv-logs")
                return false
            }
            val ok = put(json, token)
            if (ok) {
                lastSavedHash = json.hashCode()
                lastSavedKeyCount = keyCount
                AppLog.log("backup", "настройки сохранены в GitVerse tv-logs")
            } else {
                AppLog.log("backup", "сохранение FAIL: tv-logs недоступен")
            }
            return ok
        } catch (e: Exception) {
            AppLog.log("backup", "сохранение FAIL: ${e.message}")
            return false
        }
    }

    // ---------- GitVerse tv-logs (Contents API) ----------

    /** Вшитый токен хранилища (обфусцированный в BuildConfig). */
    private fun logToken(): String =
        com.torrenttv.app.Obf.decode(com.torrenttv.app.BuildConfig.LOG_TOKEN_OBF, com.torrenttv.app.BuildConfig.OBF_KEY)

    private fun request(url: String, token: String, method: String, body: String?): Pair<Int, String> {
        val rb = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.gitverse.object+json;version=1")
        when (method) {
            "PUT" -> rb.put(body!!.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            else -> rb.get()
        }
        http.newCall(rb.build()).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    /**
     * Выгрузка снимка: если файл уже есть — обновляем по sha (PATCH-семантика
     * Contents API), если нет — создаём. Возвращает успех.
     */
    private fun put(json: String, token: String): Boolean {
        // sha текущей версии файла (для обновления); 404 = файла нет, создадим
        val (getCode, getBody) = request("$CONTENTS_API/$FILE_PATH", token, "GET", null)
        val sha: String? = if (getCode == 200) {
            runCatching { JSONObject(getBody).optString("sha").takeIf { it.isNotBlank() } }.getOrNull()
        } else null

        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )
        val payload = JSONObject()
            .put("message", "бэкап настроек с ТВ")
            .put("content", b64)
        if (sha != null) payload.put("sha", sha)

        val (putCode, _) = request("$CONTENTS_API/$FILE_PATH", token, "PUT", payload.toString())
        return putCode in 200..299
    }

    /** Скачать текущий снимок настроек (или null — бэкапа ещё нет). */
    private fun download(token: String): String? {
        val (code, body) = request("$CONTENTS_API/$FILE_PATH", token, "GET", null)
        if (code != 200) return null
        val b64 = runCatching { JSONObject(body).optString("content") }.getOrDefault("")
        if (b64.isBlank()) return null
        // API отдаёт content с переводами строк — Base64 их игнорирует
        return String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            .takeIf { it.isNotBlank() }
    }
}
