package com.torrenttv.app

import com.torrenttv.app.data.RezkaService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RezkaServiceTest {

    // ---------- parseSearch ----------

    @Test
    fun `parses search html into matches`() {
        val html = """
            <ul class="b-search__section_list">
            <li><a href="https://hdrezka.ag/films/fiction/981-matrica-1999-latest.html"><span class="enty">Матрица</span> (The Matrix, 1999)</a></li>
            <li><a href="https://hdrezka.ag/series/drama/456-sverhestestvennoe-latest.html"><span class="enty">Сверхъестественное</span> (Supernatural, 2005)</a></li>
            <li><a href="https://hdrezka.ag/films/comedy/789-brat-1997-latest.html"><span class="enty">Брат</span> (1997)</a></li>
            </ul>
        """.trimIndent()
        val ms = RezkaService.parseSearch(html)
        assertEquals(3, ms.size)
        assertEquals("981", ms[0].id)
        assertEquals("Матрица", ms[0].title)
        assertEquals("The Matrix", ms[0].originalTitle)
        assertEquals("1999", ms[0].year)
        assertTrue(!ms[0].isSerial)
        assertTrue(ms[1].isSerial)
        assertEquals("1997", ms[2].year)
        assertEquals("", ms[2].originalTitle)
    }

    // ---------- parseLinks ----------

    @Test
    fun `parses comma separated links and qualities`() {
        val json = JSONObject(
            """{"success":true,"links":"https://cdn/a/1.m3u8,https://cdn/b/2.m3u8","quality":"1080p,720p"}""",
        )
        val links = RezkaService.parseLinks(json, 0, 0, "https://hdrezka.ag")
        assertEquals(2, links.size)
        assertEquals("1080p", links[0].quality)
        assertEquals("720p", links[1].quality)
        assertEquals("https://cdn/a/1.m3u8", links[0].url)
        assertEquals("https://hdrezka.ag", links[0].referer)
        assertTrue(links[0].label.contains("Rezka"))
    }

    @Test
    fun `parses or-separated alternatives as one quality`() {
        val json = JSONObject(
            """{"success":true,"links":"https://cdn/a/1.m3u8 or https://cdn2/a/1.m3u8","quality":"1080p"}""",
        )
        val links = RezkaService.parseLinks(json, 2, 5, "https://hdrezka.ag")
        assertEquals(1, links.size)
        assertEquals("1080p", links[0].quality)
        assertEquals(2, links[0].season)
        assertEquals(5, links[0].episode)
        assertTrue(links[0].label.contains("S02E05"))
    }
}
