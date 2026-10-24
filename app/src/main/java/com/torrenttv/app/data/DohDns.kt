package com.torrenttv.app.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS для TMDB через DoH (DNS-over-HTTPS).
 *
 * Проблема: api.themoviedb.org заблокирован для российского сегмента на уровне
 * DNS — Яндекс/Google/Cloudflare отдают пустой ответ или подменённый адрес,
 * поэтому TMDB-функции (кадры серий, актёры, постеры) молча не работают.
 * На телевизоре сменить DNS нельзя, зато можно ходить в DNS по HTTPS прямо
 * из приложения — это то же решение, что советуют для NAS/серверов
 * (альтернативный DNS вместо провайдерского).
 *
 * Порядок: кеш → системный DNS (если дал вменяемый адрес) → Cloudflare DoH →
 * Google DoH. Ошибки не пробрасываем: пусть вызывающая сторона получит
 * обычное сетевое исключение, если ни один способ не сработал.
 */
object DohDns : Dns {

    /** Домены, которые резолвим только через DoH (системный DNS их «ломает»). */
    private val FORCE_DOH = listOf("themoviedb.org", "tmdb.org")

    private val CACHE_TTL_MS = 10 * 60_000L
    private val cache = mutableMapOf<String, Pair<List<InetAddress>, Long>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val providers = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve",
    )

    override fun lookup(hostname: String): List<InetAddress> {
        cached(hostname)?.let { return it }

        val forced = FORCE_DOH.any { hostname == it || hostname.endsWith(".$it") }
        if (!forced) {
            // Обычные домены — системный DNS, он быстрее
            runCatching { Dns.SYSTEM.lookup(hostname) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { put(hostname, it); return it }
        } else {
            runCatching { Dns.SYSTEM.lookup(hostname) }
                .getOrNull()
                ?.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
                ?.takeIf { it.isNotEmpty() }
                ?.let { put(hostname, it); return it }
        }

        for (provider in providers) {
            val ips = resolveViaDoh(provider, hostname)
            if (ips.isNotEmpty()) {
                AppLog.log("net", "DoH $hostname → ${ips.joinToString { it.hostAddress.orEmpty() }}")
                put(hostname, ips)
                return ips
            }
        }
        // Последняя попытка — системный DNS как есть
        return runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
    }

    private fun cached(hostname: String): List<InetAddress>? {
        synchronized(cache) {
            val e = cache[hostname] ?: return null
            if (System.currentTimeMillis() - e.second > CACHE_TTL_MS) {
                cache.remove(hostname)
                return null
            }
            return e.first
        }
    }

    private fun put(hostname: String, ips: List<InetAddress>) {
        synchronized(cache) { cache[hostname] = ips to System.currentTimeMillis() }
    }

    /** JSON-запрос к DoH: {"Answer":[{"type":1,"data":"1.2.3.4"}]}. */
    private fun resolveViaDoh(provider: String, hostname: String): List<InetAddress> = runCatching {
        val req = Request.Builder()
            .url("$provider?name=$hostname&type=A")
            .header("Accept", "application/dns-json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            (0 until answers.length()).mapNotNull { i ->
                val a = answers.optJSONObject(i) ?: return@mapNotNull null
                if (a.optInt("type", 0) != 1) return@mapNotNull null   // только A-записи
                val ip = a.optString("data", "")
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }.distinct()
        }
    }.getOrDefault(emptyList())
}
