package com.torrenttv.app.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Клиент TorrServe/TorrServer (https://github.com/YouROK) — внешний движок
 * стриминга торрентов. Если приложение установлено и сервер отвечает,
 * используем его вместо внутреннего: добавляем magnet через /torrents,
 * играем файл через /stream/<name>?link=<hash>&index=<N>&play.
 */
object TorrServeService {

    const val PACKAGE = "ru.yourok.torrserve"

    /** После неудачи не дёргаем TorrServe 10 минут (чтобы не заставлять ждать дважды). */
    @Volatile private var disabledUntil = 0L

    fun isDisabled(): Boolean = System.currentTimeMillis() < disabledUntil

    private fun disable() {
        disabledUntil = System.currentTimeMillis() + 10 * 60_000L
    }

    data class TSFile(val id: Int, val path: String, val length: Long)

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Один раз за сессию пробуем сами открыть TorrServe (его Activity стартует сервер). */
    @Volatile private var launchAttempted = false
    @Volatile private var lastLaunchAt = 0L

    /**
     * Готов ли TorrServe к работе: отвечает на /echo. Если установлен, но спит —
     * один раз за сессию запускаем его Activity (она стартует сервер) и ждём.
     * false — ждём слишком долго или не установлен: работаем внутренним движком.
     */
    fun ensureRunning(ctx: Context, host: String, waitMs: Long = 10_000L): Boolean {
        if (echo(host)) return true
        if (!isInstalled(ctx)) return false
        // Повторные попытки запуска: раньше — один раз за процесс, после
        // первого неуспеха TorrServe не поднимался до перезапуска приложения
        val now = System.currentTimeMillis()
        if (launchAttempted && now - lastLaunchAt < 60_000L) return false
        launchAttempted = true
        lastLaunchAt = now
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { intent ->
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            if (echo(host)) {
                // TorrServe поднялся — сразу возвращаем пользователя в ВНосКино:
                // старт чужой активити выносит её вперёд, оставлять там нельзя
                runCatching {
                    val back = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    back?.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    ctx.startActivity(back)
                }
                return true
            }
        }
        return false
    }

    private fun client(): OkHttpClient = TorrentHttp.client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // add: не ждём дольше — быстрый fallback на встроенный движок
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Сервер жив? GET /echo. */
    fun echo(host: String): Boolean {
        if (isDisabled()) return false
        return try {
            TorrentHttp.client.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
                .newCall(Request.Builder().url("$host/echo").build())
                .execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun postJson(host: String, body: String): String? {
        val req = Request.Builder()
            .url("$host/torrents")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    /**
     * Добавить magnet и дождаться метаданных:
     * 1) POST /torrents action=add — сервер возвращает hash, метаданные ещё не готовы;
     * 2) GET /stream/<title>?link=<hash>&index=0&preload — родной способ TorrServer
     *    запустить загрузку метаданных (соединение держится до готовности);
     * 3) POST /torrents action=get — поллим file_stats каждые 3 сек.
     * Возвращает торрент с непустым списком файлов или null по таймауту/ошибке.
     */
    fun addMagnet(
        host: String,
        magnet: String,
        title: String,
        timeoutMs: Long = 60_000L,
        onStatus: (String) -> Unit = {},
    ): TSTorrent? {
        val req = JSONObject()
            .put("action", "add")
            .put("link", magnet)
            .put("title", title)
            .put("poster", "")
            .put("category", "")
            .put("save_to_db", false)
            .toString()
        val resp = postJson(host, req) ?: run {
            onStatus("TorrServe не ответил")
            disable()
            return null
        }
        val ts = runCatching { parseTorrent(JSONObject(resp)) }.getOrNull() ?: run {
            disable()
            return null
        }
        if (ts.hash.isBlank()) {
            disable()
            return null
        }

        // preload в отдельном потоке: соединение держится, сервер качает метаданные
        val preloadTitle = ts.title.ifBlank { title }
        Thread {
            runCatching {
                client().newCall(
                    Request.Builder()
                        .url("$host/stream/${URLEncoder.encode(preloadTitle, "UTF-8")}?link=${ts.hash}&index=0&preload")
                        .build()
                ).execute().use { it.body?.bytes() }
            }
        }.apply { isDaemon = true }.start()

        onStatus("Получаю метаданные через TorrServe…")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000)
            val t = get(host, ts.hash)
            if (t != null) {
                if (t.stat < 0) { // -1 = сломанный торрент
                    onStatus("TorrServe: раздача недоступна")
                    disable()
                    return null
                }
                if (t.files.isNotEmpty()) {
                    disabledUntil = 0L
                    return t
                }
                val p = t.peers
                if (p > 0) onStatus("Ищу источники через TorrServe… пиры: $p")
            }
        }
        onStatus("TorrServe: метаданные не получены")
        // Сервер жив, но не качает — карантин, чтобы не ждать каждый раз
        disable()
        return null
    }

    fun get(host: String, hash: String): TSTorrent? {
        val resp = postJson(host, JSONObject().put("action", "get").put("hash", hash).toString())
            ?: return null
        return runCatching { parseTorrent(JSONObject(resp)) }.getOrNull()
    }

    private fun parseTorrent(o: JSONObject): TSTorrent {
        val files = mutableListOf<TSFile>()
        (o.optJSONArray("file_stats") ?: JSONArray()).let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                files += TSFile(f.optInt("id", i), f.optString("path", ""), f.optLong("length", 0))
            }
        }
        return TSTorrent(
            hash = o.optString("hash", ""),
            title = o.optString("title", o.optString("name", "")),
            stat = o.optInt("stat", 0),
            peers = o.optInt("total_peers", 0),
            preloadedBytes = o.optLong("preloaded_bytes", 0),
            preloadSize = o.optLong("preload_size", 0),
            files = files,
        )
    }

    data class TSTorrent(
        val hash: String,
        val title: String,
        val stat: Int = 0,
        val peers: Int = 0,
        val preloadedBytes: Long = 0,
        val preloadSize: Long = 0,
        val files: List<TSFile>,
    )

    /**
     * Префетч начала файла перед стримом (как родной клиент TorrServe):
     * держим preload-соединение на нужном index и ждём, пока сервер докачает
     * первые мегабайты — иначе плеер открывается на пустом файле (00:00).
     */
    fun preloadFile(
        host: String,
        hash: String,
        file: TSFile,
        timeoutMs: Long = 90_000L,
        onStatus: (String) -> Unit = {},
    ): Boolean {
        val name = file.path.substringAfterLast('/')
        Thread {
            runCatching {
                client().newCall(
                    Request.Builder()
                        .url("$host/stream/${URLEncoder.encode(name, "UTF-8")}?link=$hash&index=${file.id}&preload")
                        .build()
                ).execute().use { it.body?.bytes() }
            }
        }.apply { isDaemon = true }.start()

        // Порог прогрева: для 4K-файлов (десятки ГБ, битрейт 40–80 Мбит/с) 4 МБ
        // мало — плеер стартует и сразу упирается в буфер. Даём до 32 МБ:
        // старт дольше на десятки секунд, зато без заиканий в начале.
        val threshold = minOf(32L * 1024 * 1024, maxOf(4L * 1024 * 1024, file.length / 20))
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastProgress = -1L
        var stalled = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500)
            val t = get(host, hash) ?: continue
            val pb = t.preloadedBytes
            onStatus("Буферизация TorrServe… ${pb / 1024 / 1024} МБ из ~${threshold / 1024 / 1024}")
            if (pb >= threshold) return true
            // Нет прогресса 4 замера подряд — скорость нулевая, выходим раньше
            stalled = if (pb > lastProgress) 0 else stalled + 1
            lastProgress = pb
            if (stalled >= 4 && pb > 0) return pb >= threshold
        }
        return false
    }

    /** Лучший видеофайл: самый крупный с видео-расширением; иначе просто самый крупный. */
    fun bestVideo(t: TSTorrent): TSFile? {
        val video = t.files.filter { f ->
            val n = f.path.lowercase()
            listOf(".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".webm", ".m4v", ".vob", ".mpg", ".flv", ".wmv").any { n.endsWith(it) }
        }
        return (video.ifEmpty { t.files }).maxByOrNull { it.length }
    }

    fun streamUrl(host: String, hash: String, file: TSFile): String {
        val name = file.path.substringAfterLast('/')
        return "$host/stream/${URLEncoder.encode(name, "UTF-8")}?link=$hash&index=${file.id}&play"
    }
}
