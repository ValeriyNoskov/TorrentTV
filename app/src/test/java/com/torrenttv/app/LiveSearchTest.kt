package com.torrenttv.app

import com.torrenttv.app.data.JackettProvider
import com.torrenttv.app.data.RutrackerrCatalog
import org.junit.Test

/**
 * Живая проверка источников поиска (ручной прогон, не для CI):
 * ./gradlew testDebugUnitTest --tests "*LiveSearchTest*"
 */
class LiveSearchTest {

    @Test
    fun trackerSearchReturnsCards() {
        val items = RutrackerrCatalog.search("матрица")
        println("rutrackerr: ${items.size} карточек")
        items.take(8).forEach { println("  ${it.title} | ${it.year} | ${it.type} | ${it.rtGenres}") }
        check(items.isNotEmpty()) { "поиск по трекеру вернул пусто" }
    }

    @Test
    fun trackerCatalogReturnsCards() {
        val items = RutrackerrCatalog.categoryByUrl("https://rutrackerr.ru/popular/")
        println("каталог popular: ${items.size} карточек")
        items.take(5).forEach { println("  ${it.title} (${it.year}) ${it.country}") }
        // Сайт отдаёт по 10 карточек на страницу — подгрузка идёт страницами
        check(items.size >= 10) { "каталог вернул только ${items.size} карточек" }
        val page2 = RutrackerrCatalog.categoryByUrl("https://rutrackerr.ru/popular/page/2/")
        println("каталог popular стр.2: ${page2.size} карточек")
        check(page2.isNotEmpty()) { "вторая страница каталога пуста" }
    }

    @Test
    fun jackettSearchReturnsCards() {
        val cfg = JackettProvider.Config("http://192.168.3.78:9117", "2vl2iuva749yr8sqq3rknkddi8iaokvo")
        val items = JackettProvider.search(cfg, "matrix")
        println("jackett: ${items.size} раздач")
        items.take(5).forEach { println("  ${it.name} | сиды ${it.seeds} | ${it.sizeBytes}") }
        check(items.isNotEmpty()) { "Jackett вернул пусто" }
    }
}
