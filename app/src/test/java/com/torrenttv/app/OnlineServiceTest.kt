package com.torrenttv.app

import com.torrenttv.app.data.OnlineService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineServiceTest {

    /** Реальный формат ответа search (массив карточек). */
    @Test
    fun `parseSearch reads array of cards`() {
        val body = """
            [{"id":1113,"section":0,"alt_name":"matricla-1999","title":"Матрица",
              "original_title":"The Matrix","year":1999,"serial_stats":null},
             {"id":2222,"alt_name":"sherlock-2010","title":"Шерлок",
              "original_title":"Sherlock","year":2010,"serial_stats":{"1":{}}}]
        """.trimIndent()
        val list = OnlineService.parseSearch(body)
        assertEquals(2, list.size)
        assertEquals("1113", list[0].filmixId)
        assertEquals("Матрица", list[0].title)
        assertEquals("The Matrix", list[0].originalTitle)
        assertEquals("1999", list[0].year)
        assertFalse(list[0].isSerial)
        assertTrue(list[1].isSerial)
    }

    @Test
    fun `parseSearch garbage returns empty`() {
        assertTrue(OnlineService.parseSearch("no json").isEmpty())
        assertTrue(OnlineService.parseSearch("{}").isEmpty())
    }

    /** player_links: abuse-ссылки («Заблокировано правообладателем») отфильтровываются. */
    @Test
    fun `parsePlayerData drops abuse links`() {
        val body = """
            {"player_links":{"movie":[
              {"link":"https://cdn/abuse_[,,,,480,].mp4","translation":"Заблокировано правообладателем!"},
              {"link":"https://cdn/ok_1080.mp4","translation":"Дубляж","quality":"1080"}
            ]}}
        """.trimIndent()
        val links = OnlineService.parsePlayerData(body)
        assertEquals(1, links.size)
        assertEquals("1080", links[0].quality)
        assertEquals("Дубляж", links[0].translation)
    }
}
