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
}
