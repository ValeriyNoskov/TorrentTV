package com.torrenttv.app

import com.torrenttv.app.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses tvg-chno and tvg-country`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="NTV.ru" tvg-chno="4" tvg-country="RU" group-title="Russia",НТВ
            http://stream/ntv
        """.trimIndent()
        val ch = M3uParser.parse(m3u).single()
        assertEquals("НТВ", ch.name)
        assertEquals("NTV.ru", ch.tvgId)
        assertEquals(4, ch.tvgNo)
    }

    @Test
    fun `onlyCountry filters by tvg-country`() {
        val m3u = """
            #EXTINF:-1 tvg-country="RU" group-title="Russia",НТВ
            http://stream/ntv
            #EXTINF:-1 tvg-country="BY" group-title="Belarus",Беларусь 1
            http://stream/by
        """.trimIndent()
        val ru = M3uParser.parse(m3u, onlyCountry = "ru")
        assertEquals(listOf("НТВ"), ru.map { it.name })
    }

    @Test
    fun `translateName english federal channels`() {
        assertEquals("Первый канал", M3uParser.translateName("Channel One (Russia)"))
        assertEquals("НТВ", M3uParser.translateName("NTV (Russia)"))
        assertEquals("НТВ (+1)", M3uParser.translateName("NTV (Russia) +1"))
        assertEquals("ТНТ", M3uParser.translateName("TNT (Russia)"))
        assertEquals("Москва 24", M3uParser.translateName("Moskva 24"))
        assertEquals("Домашний", M3uParser.translateName("Domashniy"))
    }

    @Test
    fun `translateName keeps cyrillic as is`() {
        assertEquals("Первый канал", M3uParser.translateName("Первый канал"))
    }

    @Test
    fun `translateName unknown latin stays cleaned`() {
        assertEquals("Some Channel", M3uParser.translateName("Some Channel (Russia)"))
    }

    @Test
    fun `cleanName strips marks and quality`() {
        assertEquals("Матч ТВ", M3uParser.cleanName("Матч ТВ Ⓢ (1080p)"))
        assertEquals("Канал", M3uParser.cleanName("Канал [Geo-blocked]"))
    }
}
