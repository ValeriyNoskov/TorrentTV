package com.torrenttv.app

import com.torrenttv.app.data.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodesTest {

    @Test
    fun `episode file name matching`() {
        // S01E05 в разных написаниях
        assertTrue(Episode.fileMatches("The Show S01E05 1080p.mkv", 1, 5))
        assertTrue(Episode.fileMatches("the.show.s1e5.720p.mkv", 1, 5))
        assertTrue(Episode.fileMatches("Show S01_E05.mkv", 1, 5))
        assertTrue(Episode.fileMatches("Show.1x05.WEB-DL.mkv", 1, 5))
        assertTrue(Episode.fileMatches("show s01e05.mkv", 1, 5))

        // Другие серии и произвольные числа не совпадают
        assertFalse(Episode.fileMatches("The Show S01E06 1080p.mkv", 1, 5))
        assertFalse(Episode.fileMatches("The Show S02E05 1080p.mkv", 1, 5))
        assertFalse(Episode.fileMatches("2160p.mkv", 1, 5))
        assertFalse(Episode.fileMatches("Show.mkv", 1, 5))
    }

    @Test
    fun `file numbers parse packs without E-markup`() {
        // Пак Files-x: «03. Название.S05.2026…» — номер в начале, сезон в середине
        // (регрессия v0.9.22: E00 ломал «Продолжить» — открывалась чужая серия)
        assertEquals(5 to 3, Episode.fileNumbers("/t/03. Титаны.S05.2026.WEB-DL 720p.Files-x.mkv"))
        assertEquals(5 to 6, Episode.fileNumbers("/t/06. Титаны.S05.2026.WEB-DL 720p.Files-x.mkv"))
        // Обычные форматы по-прежнему работают
        assertEquals(1 to 5, Episode.fileNumbers("The Show S01E05.mkv"))
        assertEquals(1 to 5, Episode.fileNumbers("Show.1x05.mkv"))
        assertEquals(1 to 7, Episode.fileNumbers("/t/07. Название серии.mkv"))
        // Год в начале имени — не серия
        assertEquals(null, Episode.fileNumbers("2026 Титаны WEB-DL.mkv"))
        // fileMatches через «03. …S05…»
        assertTrue(Episode.fileMatches("06. Титаны.S05.2026.WEB-DL 720p.Files-x.mkv", 5, 6))
        assertFalse(Episode.fileMatches("03. Титаны.S05.2026.WEB-DL 720p.Files-x.mkv", 5, 6))
    }

    @Test
    fun `episode code and source suffix`() {
        val ep = Episode(season = 1, episode = 5)
        assertEquals("S01E05", ep.code)
        assertEquals(":s01e05", ep.sourceSuffix)

        val ep10 = Episode(season = 12, episode = 105)
        assertEquals("S12E105", ep10.code)
    }
}
