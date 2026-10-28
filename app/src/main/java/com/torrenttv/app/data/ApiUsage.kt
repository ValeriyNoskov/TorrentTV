package com.torrenttv.app.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Счётчики запросов к внешним API за сегодня — для раздела «Запросы к API»
 * в настройках. Считаются только сетевые вызовы, расходующие лимиты;
 * ответы из кеша учитываются отдельной строкой (лимит не тратят).
 * Сами ключи НЕ хранятся и НЕ показываются — только порядковые номера
 * («КП №1», «КП №2»). Сброс — при смене суток (лимиты КП суточные).
 * Файл счётчиков — api_usage.json в кеш-каталоге приложения.
 */
object ApiUsage {

    private var dir: java.io.File? = null

    @Volatile private var day: String = today()

    // Индекс ключа в списке → счётчик (столько, сколько ключей задано)
    private val kp = ConcurrentHashMap<Int, AtomicInteger>()
    private val trakt = AtomicInteger()
    private val tmdb = AtomicInteger()
    private val cacheHits = AtomicInteger()

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Вызывается при старте (KinopoiskClient.attachCache): поднять счётчики с диска. */
    fun attach(dir: java.io.File) {
        if (this.dir != null) return
        this.dir = dir
        runCatching {
            val f = java.io.File(dir, "api_usage.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                if (o.optString("day") == today()) {
                    val kpObj = o.optJSONObject("kp") ?: JSONObject()
                    for (k in kpObj.keys()) {
                        kp.getOrPut(k.toIntOrNull() ?: continue) { AtomicInteger() }
                            .addAndGet(kpObj.optInt(k, 0))
                    }
                    trakt.addAndGet(o.optInt("trakt", 0))
                    tmdb.addAndGet(o.optInt("tmdb", 0))
                }
                // день другой — начинаем с нуля, файл перезапишется при первом запросе
            }
        }
    }

    /** Сетевой запрос Кинопоиска ключом №keyIndex (нумерация с 0). */
    fun kpRequest(keyIndex: Int) {
        if (day != today()) reset()
        kp.getOrPut(keyIndex) { AtomicInteger() }.incrementAndGet()
        persist()
    }

    /** Сетевой запрос Trakt (лимит 1000/час, но считаем за день — нагляднее). */
    fun traktRequest() {
        if (day != today()) reset()
        trakt.incrementAndGet()
        persist()
    }

    /** Сетевой запрос TMDB. */
    fun tmdbRequest() {
        if (day != today()) reset()
        tmdb.incrementAndGet()
        persist()
    }

    /** Ответ отдан из кеша — сетевого вызова не было, лимит не потрачен. */
    fun cacheHit() {
        // Пишем только в память: попаданий много, дёргать диск на каждый — расточительно
        cacheHits.incrementAndGet()
    }

    /** Снимок для настроек: сколько запросов ушло за сегодня. */
    data class Snapshot(
        val kp: List<Int>,      // по порядковым номерам ключей
        val trakt: Int,
        val tmdb: Int,
        val cacheHits: Int,
        val day: String,
    )

    fun snapshot(): Snapshot {
        val maxIdx = (kp.keys.maxOrNull() ?: -1) + 1
        return Snapshot(
            kp = (0 until maxIdx).map { kp[it]?.get() ?: 0 },
            trakt = trakt.get(),
            tmdb = tmdb.get(),
            cacheHits = cacheHits.get(),
            day = day,
        )
    }

    private fun reset() {
        day = today()
        kp.clear()
        trakt.set(0)
        tmdb.set(0)
        // cacheHits не сбрасываем — это не лимиты, а статистика сессии
    }

    private fun persist() {
        val d = dir ?: return
        runCatching {
            val kpObj = JSONObject()
            for ((i, c) in kp) kpObj.put(i.toString(), c.get())
            writeAtomic(
                java.io.File(d, "api_usage.json"),
                JSONObject()
                    .put("day", day)
                    .put("kp", kpObj)
                    .put("trakt", trakt.get())
                    .put("tmdb", tmdb.get())
                    .toString(),
            )
        }
    }
}
