package com.torrenttv.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Общий HTTP-клиент для провайдеров поиска раздач. */
object TorrentHttp {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** GET, вернуть тело строки или null при любой ошибке. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) {
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
}
