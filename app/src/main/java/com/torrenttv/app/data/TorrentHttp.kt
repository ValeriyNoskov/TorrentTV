package com.torrenttv.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Общий HTTP-клиент для провайдеров поиска раздач. */
object TorrentHttp {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Жёсткий предел на весь вызов: блокирующий OkHttp нельзя прервать
        // из withTimeoutOrNull (ревью данных) — без callTimeout зависший
        // источник занимал поток IO до 30+ секунд
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Быстрый клиент для каталога: медленный ответ = сразу ошибка на экране,
     *  а не «чёрный экран» на 30+ секунд (затыки DNS/IPv6 на ТВ). */
    val fast: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** GET с короткими таймаутами (каталог трекера) или null при ошибке. */
    fun getFast(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        fast.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    /** GET, вернуть тело строки или null при любой ошибке. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Точный код ответа в лог — иначе «не ответил» скрывает 4xx
                com.torrenttv.app.data.AppLog.log("net", "GET $url → HTTP ${resp.code}")
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        com.torrenttv.app.data.AppLog.log("net", "GET $url FAIL: ${e.message}")
        null
    }

    /** GET, вернуть тело байтами (для .torrent-файлов) или null при любой ошибке. */
    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (_: Exception) {
        null
    }

    /** POST JSON, вернуть тело строки или null при любой ошибке. */
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    /** POST формы (x-www-form-urlencoded), вернуть тело строки или null. */
    fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String? = try {
        val form = fields.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val b = Request.Builder()
            .url(url)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    /** POST multipart-формы (TorBox требует её для createtorrent с магнитом). */
    fun postMultipart(
        url: String,
        fields: Map<String, String>,
        file: Pair<String, String>? = null, // (поле, содержимое файла) — TorBox: магнит как «файл»
        fileBinary: Pair<String, ByteArray>? = null, // (поле, байты) — TorBox: настоящий .torrent
        headers: Map<String, String> = emptyMap(),
    ): String? = try {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
        for ((k, v) in fields) body.addFormDataPart(k, v)
        if (file != null) {
            body.addFormDataPart(
                file.first,
                "magnet.txt",
                file.second.toRequestBody("text/plain".toMediaType()),
            )
        }
        if (fileBinary != null) {
            body.addFormDataPart(
                fileBinary.first,
                "torrent.torrent",
                fileBinary.second.toRequestBody("application/x-bittorrent".toMediaType()),
            )
        }
        val b = Request.Builder().url(url).post(body.build())
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Точный лог: какой код вернул сервер — иначе «не ответил»
                // маскирует и сеть, и 4xx (невалидный запрос)
                com.torrenttv.app.data.AppLog.log("net", "POST $url → HTTP ${resp.code}")
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        com.torrenttv.app.data.AppLog.log("net", "POST $url FAIL: ${e.message}")
        null
    }

    /**
     * Бинарная загрузка (.torrent-файлы): строкой нельзя — text-декод
     * портит байты, TorBox/RD отклонят файл.
     */
    fun getBytesLogged(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                com.torrenttv.app.data.AppLog.log("net", "GET $url → HTTP ${resp.code}")
                null
            } else resp.body?.bytes()
        }
    } catch (e: Exception) {
        com.torrenttv.app.data.AppLog.log("net", "GET $url FAIL: ${e.message}")
        null
    }
}
