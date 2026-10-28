package com.torrenttv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Самопроверка приложения с пульта (Настройки → Для разработчика → «Тест-кейсы»).
 * Прогоняет проверки хранилища, парсеров, сети и поиска, результат — построчно
 * в лог [selftest] и авто-выгрузка отчёта в Gist по окончании: отчёт можно
 * сразу прислать разработчику, не воспроизводя проблему руками.
 */
object SelfTest {

    data class Result(val name: String, val ok: Boolean, val detail: String, val ms: Long)

    private fun sizeOf(dir: File?): Long =
        dir?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun mb(bytes: Long): String =
        if (bytes >= 1024 * 1024 * 1024) "${"%.1f".format(bytes / 1024f / 1024f / 1024f)} ГБ"
        else "${bytes / 1024 / 1024} МБ"

    /** HTTP-код (или −1), без тела: проверка доступности хоста. */
    private fun httpCode(url: String, token: String = ""): Int = try {
        val req = okhttp3.Request.Builder().url(url)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .header("Accept", "application/vnd.github+json")
            .build()
        TorrentHttp.client.newCall(req).execute().use { it.code }
    } catch (e: Exception) {
        -1
    }

    /** Оценка длительности каждого кейса (сек, порядок = порядок кейсов) — для «осталось ~…». */
    // ETA по кейсам; кейсов 9 (раньше было 10 — прогресс не доходил до 100%)
    private val EST_SEC = intArrayOf(1, 1, 1, 1, 2, 5, 5, 60, 5)

    /**
     * Прогон тест-кейсов. onStep — прогресс после каждого кейса:
     * (пройдено, всего, осталось ~сек, текущий кейс) — для экрана «не висит ли».
     */
    suspend fun run(
        context: Context,
        store: LocalStore,
        onStep: (done: Int, total: Int, etaSec: Int, current: String) -> Unit = { _, _, _, _ -> },
    ): List<Result> {
        val results = mutableListOf<Result>()

        suspend fun check(name: String, block: suspend () -> String) {
            val idx = results.size
            // ETA включая текущий кейс; блок — в IO: HTTP в main-потоке даёт
            // NetworkOnMainThreadException и ложные FAIL (проверено отчётом с ТВ)
            onStep(idx, EST_SEC.size, EST_SEC.drop(idx).sum(), name)
            AppLog.log("selftest", "▶ $name")
            val t0 = System.currentTimeMillis()
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            val detail = r.getOrElse { "FAIL: ${it.message}" }
            val ok = r.isSuccess && !detail.startsWith("FAIL")
            val res = Result(name, ok, detail, System.currentTimeMillis() - t0)
            results += res
            AppLog.log("selftest", "${if (ok) "OK " else "FAIL"} $name — ${res.detail} (${res.ms} мс)")
        }

        // ---------- 1. Настройки: запись/чтение/удаление ----------
        check("Хранилище настроек (prefs)") {
            val k = "selftest_probe"
            store.setFallbackInternalWriteProbe("ok")
            val v = store.readProbe(k)
            store.debugRemoveKeys(k)
            if (v == "ok") "запись/чтение/удаление работают" else "FAIL: прочитано «$v»"
        }

        // ---------- 2. Магниты: сохранение и список ----------
        check("Магниты: save/get/savedMagnets") {
            val probe = MediaItem(id = 1999000001, type = "tv", title = "SelfTest", year = "2026")
            store.saveMagnet(probe, "magnet:?xt=1&dn=general", "")
            store.saveMagnet(probe, "magnet:?xt=1&dn=ep", probe.let {
                Episode(season = 1, episode = 2).sourceSuffix
            })
            val ok = store.getMagnet(probe) == "magnet:?xt=1&dn=general" &&
                store.getMagnet(probe, Episode(season = 1, episode = 2).sourceSuffix) == "magnet:?xt=1&dn=ep" &&
                store.savedMagnets(probe).size == 2
            store.debugRemoveKeys("magnet:tv-1999000001")
            if (ok) "сохранение, чтение по серии и список — ок, тестовые ключи убраны"
            else "FAIL: ключи не совпали (${store.savedMagnets(probe).size})"
        }

        // ---------- 3. Прогресс: лента «Продолжить» читается ----------
        check("Прогресс: лента «Продолжить»") {
            val list = store.continueWatching()
            val bad = list.count { it.item.title.isBlank() }
            if (bad == 0) "${list.size} записей, JSON валиден"
            else "FAIL: $bad записей с пустым заголовком"
        }

        // ---------- 4. Парсер серий ----------
        check("Парсер серий (fileNumbers)") {
            val cases = listOf(
                "Титаны.S05E07.720p.mkv" to (5 to 7),
                "Титаны 05x06 WEB-DL.mkv" to (5 to 6),
                "Show/07. Series.mkv" to (1 to 7),
                "Movie.2026.mkv" to null,
            )
            val bad = cases.filter { (name, want) -> Episode.fileNumbers(name) != want }
            if (bad.isEmpty()) "${cases.size} кейса пройдены"
            else "FAIL: " + bad.joinToString("; ") { "${it.first} → ${Episode.fileNumbers(it.first)}" }
        }

        // ---------- 5. Кеш: размеры (информация) ----------
        check("Хранилище: размеры кеша") {
            val cache = sizeOf(context.cacheDir)
            val apk = context.cacheDir.listFiles { f -> f.name.endsWith(".apk") }?.sumOf { it.length() } ?: 0L
            val posters = sizeOf(File(context.cacheDir, "image_cache"))
            val downloads = sizeOf(File(context.filesDir, "downloads"))
            val free = context.filesDir.usableSpace
            "кеш ${mb(cache)} (из них APK обновлений ${mb(apk)}, постеры ${mb(posters)}), " +
                "загрузки ${mb(downloads)}, свободно ${mb(free)}"
        }

        // ---------- 6. GitHub Releases (обновления) ----------
        check("GitHub Releases API") {
            val code = httpCode(
                "https://api.github.com/repos/ValeriyNoskov/TorrentTV/releases/latest",
                store.githubToken().ifBlank { com.torrenttv.app.update.UpdateManager.ghToken(context) },
            )
            if (code == 200) "доступен (HTTP 200)" else "FAIL: HTTP $code"
        }

        // ---------- 7. КП API (доступность, любой код = хост жив) ----------
        check("Кинопоиск API: доступность") {
            val code = httpCode("https://api.kinopoisk.dev/v1.4/movie")
            if (code > 0) "доступен (HTTP $code — без ключа 401 это норма)" else "FAIL: нет ответа"
        }

        // ---------- 8. Поиск раздач (долгий, до минуты) ----------
        check("Поиск раздач («Титаны») — до 60 с") {
            val (list, _) = TorrentSearch.searchDetailed(
                query = "Титаны",
                isTvShow = true,
            )
            if (list.isNotEmpty()) "найдено ${list.size} раздач, топ по сидам: ${list.maxOf { it.seeds }}"
            else "FAIL: 0 раздач"
        }

        // ---------- 9. Кеш метаданных раздач (информация) ----------
        check("Хранилище: режим") {
            val meta = File(context.filesDir, "downloads").listFiles { f -> f.name.startsWith("meta_") }?.size ?: 0
            "движок встроенный (jlibtorrent), кеш метаданных раздач: $meta шт"
        }

        return results
    }

    /** Текст отчёта для выгрузки: приложение само отправит его в Gist. */
    fun report(results: List<Result>): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())
        val sb = StringBuilder("=== ВНосКино самотест $stamp ===\n")
        for (r in results) {
            sb.append("${if (r.ok) "OK  " else "FAIL"} ${r.name} (${r.ms} мс)\n")
            if (r.detail.isNotBlank()) sb.append("     ${r.detail}\n")
        }
        val fails = results.count { !it.ok }
        sb.append("\nИтог: ${results.size - fails}/${results.size} пройдено\n")
        return sb.toString()
    }
}
