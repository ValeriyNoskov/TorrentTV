package com.torrenttv.app

import com.torrenttv.app.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U x-tvg-url="https://epg"
        #EXTINF:-1 tvg-id="Channel1.ru" tvg-logo="https://logo/1.png" group-title="News",Первый канал (1080p)
        https://stream/first.m3u8

        #EXTINF:-1 tvg-id="Moskva24.ru" tvg-logo="https://logo/m24.png" group-title="General",Moskva 24 (1080p) [Not 24/7]
        http://stream/m24.m3u8
        #EXTVLCOPT:http-user-agent=Mozilla/5.0

        #EXTINF:-1 group-title="XXX",Adult Channel
        http://stream/adult.m3u8

        #EXTINF:-1,Просто название
        http://stream/plain.m3u8

        #EXTINF:-1 tvg-logo="https://logo/x.png",Название, с запятой (720p)
        http://stream/comma.m3u8
    """.trimIndent()

    @Test
    fun `разбор атрибутов и url`() {
        val ch = M3uParser.parse(sample)
        assertEquals(4, ch.size)

        val first = ch.first()
        assertEquals("Первый канал", first.name)
        assertEquals("https://stream/first.m3u8", first.url)
        assertEquals("https://logo/1.png", first.logoUrl)
        assertEquals("News", first.group)
    }

    @Test
    fun `директивы и пустые строки не ломают порядок`() {
        val ch = M3uParser.parse(sample)
        val m24 = ch.first { it.name == "Moskva 24" }
        assertEquals("http://stream/m24.m3u8", m24.url)  // EXTVLCOPT пропущен, url взят верный
    }

    @Test
    fun `взрослые группы отфильтрованы`() {
        val ch = M3uParser.parse(sample)
        assertTrue(ch.none { it.name == "Adult Channel" })
    }

    @Test
    fun `плейлист без атрибутов тоже парсится`() {
        val ch = M3uParser.parse(sample)
        val plain = ch.first { it.url == "http://stream/plain.m3u8" }
        assertEquals("Просто название", plain.name)
        assertEquals("", plain.logoUrl)
    }

    @Test
    fun `запятая в названии не ломает разбор`() {
        val ch = M3uParser.parse(sample)
        val comma = ch.first { it.url == "http://stream/comma.m3u8" }
        assertEquals("Название, с запятой", comma.name)
    }

    @Test
    fun `хвосты качества и пометок вычищены`() {
        assertEquals("Первый канал", M3uParser.cleanName("Первый канал (1080p)"))
        assertEquals("Moskva 24", M3uParser.cleanName("Moskva 24 (1080p) [Not 24/7]"))
        assertEquals("Канал", M3uParser.cleanName("Канал [Geo-blocked]"))
    }

    @Test
    fun `битая строка EXTINF даёт null`() {
        assertNull(M3uParser.parseExtInf("#EXTINF:-1"))
        assertNull(M3uParser.parseExtInf("#EXTINF:-1 group-title=\"News\","))
    }
}
