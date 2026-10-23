package com.torrenttv.app

import com.torrenttv.app.data.PrivateTrackers
import com.torrenttv.app.data.TorrentSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateTrackersTest {

    // ---------- RuTracker ----------

    @Test
    fun `rutracker rows parsed`() {
        val html = """
            <table>
            <tr class="hl-tr">
              <td><a class="torTopic" href="./viewtopic.php?t=123456"><span class="hl">Интерстеллар / Interstellar (2014) BDRip 1080p</span></a></td>
              <td><b class="seedmed">1523</b></td>
              <td class="dl-stretch"><a href="./dl.php?t=123456">4.35 GB</a></td>
            </tr>
            <tr class="hl-tr">
              <td><a class="torTopic" href="./viewtopic.php?t=123457">Побег / The Shawshank (1994) BDRip 720p</a></td>
              <td><b class="seedmed">77</b></td>
              <td>780 MB</td>
            </tr>
            </table>
        """.trimIndent()
        val res = PrivateTrackers.parseRutracker(html)
        assertEquals(2, res.size)
        assertEquals("Интерстеллар / Interstellar (2014) BDRip 1080p", res[0].name)
        assertEquals("123456", Regex("\\d+").find(res[0].torrentUrl)!!.value)
        assertTrue(res[0].torrentUrl.endsWith("dl.php?t=123456"))
        assertEquals(1523, res[0].seeds)
        assertEquals((4.35 * 1024 * 1024 * 1024).toLong(), res[0].sizeBytes)
        assertEquals("1080p", res[0].resolution)
        assertEquals(PrivateTrackers.RUTRACKER, res[0].provider)
        assertTrue(res[0].isPrivate)
        assertEquals("720p", res[1].resolution)
    }

    // ---------- Kinozal ----------

    @Test
    fun `kinozal rows parsed`() {
        val html = """
            <table>
            <tr>
              <td class="nam"><a href="/details.php?id=1901234">Интерстеллар (2014) BDRip 1080p</a></td>
              <td class="sl">214</td>
              <td class="s">2.11 GB</td>
            </tr>
            <tr>
              <td class="nam"><a href="/details.php?id=1901235">Джокер / Joker (2019) BDRip 720p</a></td>
              <td class="sl">5</td>
              <td class="s">1.4 GB</td>
            </tr>
            </table>
        """.trimIndent()
        val res = PrivateTrackers.parseKinozal(html)
        assertEquals(2, res.size)
        assertEquals("Интерстеллар (2014) BDRip 1080p", res[0].name)
        assertEquals("https://kinozal.tv/download.php?id=1901234", res[0].torrentUrl)
        assertEquals(214, res[0].seeds)
        assertEquals((2.11 * 1024 * 1024 * 1024).toLong(), res[0].sizeBytes)
        assertEquals(PrivateTrackers.KINOZAL, res[0].provider)
    }

    // ---------- NNM-Club ----------

    @Test
    fun `nnmclub rows parsed`() {
        val html = """
            <table>
            <tr>
              <td><a href="./viewtopic.php?t=111222">Интерстеллар / Interstellar (2014) BDRip 1080p</a></td>
              <td><b class="seedmed">312</b></td>
              <td>8.6 GB</td>
            </tr>
            <tr>
              <td>без ссылки на топик — строка пропускается</td>
            </tr>
            </table>
        """.trimIndent()
        val res = PrivateTrackers.parseNnmclub(html)
        assertEquals(1, res.size)
        assertEquals("https://nnmclub.to/forum/download.php?id=111222", res[0].torrentUrl)
        assertEquals(312, res[0].seeds)
    }

    // ---------- Размеры ----------

    @Test
    fun `size parsing`() {
        assertEquals((4.35 * 1024 * 1024 * 1024).toLong(), PrivateTrackers.sizeFromText("что-то 4.35 GB тут"))
        assertEquals(780L * 1024 * 1024, PrivateTrackers.sizeFromText("780 MB"))
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, PrivateTrackers.sizeFromText("2 TB"))
        assertEquals((2.1 * 1024 * 1024 * 1024).toLong(), PrivateTrackers.sizeFromText("2,1 ГБ"))
        assertEquals(null, PrivateTrackers.sizeFromText("размера нет"))
    }

    // ---------- Дедуп и фильтр с частными раздачами ----------

    @Test
    fun `merge keeps private results without hash and without seeds`() {
        val pub = com.torrenttv.app.data.TorrentResult(
            name = "public", infoHash = "a".repeat(40), seeds = 10, sizeBytes = 1000,
        )
        val priv1 = com.torrenttv.app.data.TorrentResult(
            name = "private A", infoHash = "", seeds = 0, sizeBytes = 1000,
            provider = "rutracker", torrentUrl = "https://x/dl.php?t=1",
        )
        val priv2 = com.torrenttv.app.data.TorrentResult(
            name = "private B", infoHash = "", seeds = 3, sizeBytes = 1000,
            provider = "kinozal", torrentUrl = "https://y/download.php?id=2",
        )
        val merged = TorrentSearch.merge(listOf(listOf(pub, priv1, priv2)))
        assertEquals(3, merged.size)
        // приватные с сидами=0 идут после публичных, но не выбрасываются
        assertEquals("public", merged[0].name)
    }
}
