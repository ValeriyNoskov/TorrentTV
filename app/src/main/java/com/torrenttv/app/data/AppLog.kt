package com.torrenttv.app.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой лог приложения: кольцевой буфер в памяти + файл app_log.txt.
 * Кнопка «Отправить лог» в Настройках → Проверка сети выкладывает dump на
 * pastebin и показывает URL — пользователь называет его разработчику.
 */
object AppLog {

    private const val MAX_LINES = 800
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Запись логов включена (галочка в Настройках → Логи). */
    @Volatile var recording: Boolean = true

    @Volatile private var appContext: Context? = null

    /** Вызвать один раз из MainActivity.onCreate. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        installCrashHandler()
        log("app", "start, log initialized")
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > MAX_LINES) buf.removeFirst()
        }
        if (recording) {
            runCatching {
                val f = file()
                // Ротация: файл больше 512 КБ — начинаем заново
                if (f.length() > 512L * 1024) f.writeText("")
                f.appendText(line + "\n")
            }
        }
        runCatching { android.util.Log.i("VNosKino", "$tag: $msg") }
    }

    /** Последние n строк лога (для превью в Настройках → Логи). */
    fun recent(n: Int = 40): List<String> = synchronized(buf) {
        buf.toList().takeLast(n)
    }

    /** Полностью очистить лог (память + файл). */
    fun clear() {
        synchronized(buf) { buf.clear() }
        runCatching { file().writeText("") }
        log("app", "логи очищены")
    }

    /** Полный dump: устройство + версия + лог + краши. */
    fun dump(): String = buildString {
        val ctx = appContext
        append("=== VNosKino log ===\n")
        if (ctx != null) {
            val pm = ctx.packageManager
            val v = runCatching {
                pm.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
            append("version: $v\n")
            append("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            append("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        } else {
            append("context: not initialized\n")
        }
        append("\n--- events ---\n")
        synchronized(buf) { buf.forEach { append(it).append('\n') } }
        // История из файла: буфер держит 800 строк, файл — больше
        runCatching {
            val f = file()
            if (f.exists() && f.length() > 0) {
                val tail = f.readText().takeLast(40_000)
                append("\n--- file log (tail) ---\n")
                append(tail)
            }
        }
        val crash = crashFile()
        if (crash.exists()) {
            append("\n--- last crash ---\n")
            append(crash.readText().takeLast(3000))
        }
    }

    fun crashFile(): File = File(appContext?.filesDir ?: File("/tmp"), "app_crash.txt")

    private fun file(): File = File(appContext?.filesDir ?: File("/tmp"), "app_log.txt")

    /** Писать краши в файл — попадут в следующий dump. */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                // Хвост событий рядом со стеком: краш всегда приходит с контекстом
                // «что пользователь делал перед вылетом» (главная жалоба — вылет
                // без следа в логе, когда стек был, а события терялись)
                val tail = synchronized(buf) { buf.takeLast(30).joinToString("\n") }
                crashFile().appendText(
                    "\n=== ${fmt.format(Date())} thread=${t.name} ===\n" +
                        "last events:\n$tail\n\n" +
                        android.util.Log.getStackTraceString(e),
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * Выложить лог и вернуть URL. Приоритет:
     * GitHub Gist (если задан токен) → dpaste.org → paste.rs → 0x0.st → tmpfiles.org.
     * Gist — секретный: открывается по ссылке без пароля, но не виден в профиле.
     * Токен хранится в Настройках ТВ (не в APK) с правами только на gists.
     */
    fun sendToPastebin(githubToken: String? = null, overrideContent: String? = null, overrideTitle: String? = null): String {
        val content = (overrideContent ?: dump()).toByteArray(Charsets.UTF_8)
        val client = TorrentHttp.client

        // 0) GitHub Gist от имени владельца токена: я (разработчик) читаю его сам
        if (!githubToken.isNullOrBlank()) {
            runCatching {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())
                val payload = org.json.JSONObject()
                    .put("description", (overrideTitle ?: "ВНосКино лог") + " $stamp")
                    .put("public", false)
                    .put("files", org.json.JSONObject()
                        .put(overrideTitle?.let { "vnoskino_selftest.txt" } ?: "vnoskino_log.txt",
                            org.json.JSONObject().put("content", String(content, Charsets.UTF_8))))
                    .toString()
                val body = okhttp3.RequestBody.create(
                    "application/json; charset=utf-8".toMediaTypeOrNull(),
                    payload,
                )
                client.newCall(
                    okhttp3.Request.Builder()
                        .url("https://api.github.com/gists")
                        .header("Authorization", "Bearer $githubToken")
                        .header("Accept", "application/vnd.github+json")
                        .post(body)
                        .build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val raw = resp.body?.string().orEmpty()
                        // html_url — адрес для человека; raw_url — прямая выгрузка
                        val html = Regex("\"html_url\"\\s*:\\s*\"([^\"]+gists?[^\"]+)\"").find(raw)?.groupValues?.get(1)
                        val direct = Regex("\"raw_url\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
                        return direct ?: html ?: return@runCatching
                    }
                }
            }
        }

        // 1) dpaste.org: form-encoded, ответ — URL
        runCatching {
            val body = okhttp3.FormBody.Builder()
                .add("content", String(content, Charsets.UTF_8))
                .add("lexer", "_text")
                .add("format", "url")
                .add("expires", "2592000")
                .build()
            client.newCall(
                okhttp3.Request.Builder()
                    .url("https://dpaste.org/api/")
                    .post(body)
                    .build()
            ).execute().use { resp ->
                if (resp.isSuccessful) {
                    val u = resp.body?.string()?.trim().orEmpty()
                    if (u.startsWith("http")) return u
                }
            }
        }

        // 2) paste.rs: тело запроса = содержимое, ответ — URL
        runCatching {
            client.newCall(
                okhttp3.Request.Builder()
                    .url("https://paste.rs/")
                    .post(okhttp3.RequestBody.create(null, content))
                    .build()
            ).execute().use { resp ->
                if (resp.isSuccessful) {
                    val u = resp.body?.string()?.trim().orEmpty()
                    if (u.startsWith("http")) return u
                }
            }
        }

        // 3) 0x0.st: multipart
        runCatching {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file", "log.txt",
                    okhttp3.RequestBody.create(null, content),
                )
                .build()
            client.newCall(
                okhttp3.Request.Builder()
                    .url("https://0x0.st/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .post(body)
                    .build()
            ).execute().use { resp ->
                if (resp.isSuccessful) {
                    val u = resp.body?.string()?.trim().orEmpty()
                    if (u.startsWith("http")) return u
                }
            }
        }

        // 4) tmpfiles.org: multipart, ответ JSON {"data":{"url":...}}
        runCatching {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file", "log.txt",
                    okhttp3.RequestBody.create(null, content),
                )
                .build()
            client.newCall(
                okhttp3.Request.Builder()
                    .url("https://tmpfiles.org/api/v1/upload")
                    .post(body)
                    .build()
            ).execute().use { resp ->
                if (resp.isSuccessful) {
                    val u = resp.body?.string().orEmpty()
                    val match = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(u)
                    val direct = match?.groupValues?.get(1)
                    if (direct != null && direct.startsWith("http")) {
                        // tmpfiles отдаёт страницу просмотра — прямой файл: dl.tmpfiles.org
                        return direct.replace("tmpfiles.org/", "tmpfiles.org/dl/")
                    }
                }
            }
        }

        return ""
    }
}
