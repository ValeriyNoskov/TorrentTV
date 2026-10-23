package com.torrenttv.app.data

import android.content.Context
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
        runCatching { file().appendText(line + "\n") }
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
                crashFile().appendText(
                    "\n=== ${fmt.format(Date())} thread=${t.name} ===\n" +
                        android.util.Log.getStackTraceString(e),
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * Выложить лог на pastebin и вернуть URL. Приоритет:
     * dpaste.org → paste.rs → 0x0.st → tmpfiles.org.
     */
    fun sendToPastebin(): String {
        val content = dump().toByteArray(Charsets.UTF_8)
        val client = TorrentHttp.client

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
