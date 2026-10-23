package com.torrenttv.app

import com.torrenttv.app.data.ApibayProvider
import com.torrenttv.app.data.JackettProvider
import com.torrenttv.app.data.ReleaseQuality
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.data.TorrentioProvider
import com.torrenttv.app.data.TorrentSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSearchTest {

    private fun r(
        name: String,
        hash: String,
        seeds: Int,
        provider: String = "apibay",
        size: Long = 0,
    ) = TorrentResult(
        name = name,
        infoHash = hash,
        seeds = seeds,
        sizeBytes = size,
        provider = provider,
        resolution = ReleaseQuality.parse(name),
    )

    // ---------- merge ----------

    @Test
    fun `дедуп по info_hash остаётся максимум сидов`() {
        val merged = TorrentSearch.merge(
            listOf(
                listOf(r("A 1080p", "a".repeat(40), seeds = 10)),
                listOf(r("A 1080p (другой провайдер)", "a".repeat(40), seeds = 50)),
                listOf(r("B 720p", "b".repeat(40), seeds = 5)),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(50, merged.first { it.infoHash == "a".repeat(40) }.seeds)
    }

    @Test
    fun `сортировка по сидам и фильтр мёртвых`() {
        val merged = TorrentSearch.merge(
            listOf(
                listOf(
                    r("A", "a".repeat(40), seeds = 3),
                    r("Dead", "d".repeat(40), seeds = 0),
                    r("B", "b".repeat(40), seeds = 100),
                ),
            ),
        )
        assertEquals(listOf("b".repeat(40), "a".repeat(40)), merged.map { it.infoHash })
    }

    @Test
    fun `стоп-слова блокируют раздачу по названию`() {
        val merged = TorrentSearch.merge(
            listOf(listOf(r("Movie 2023 CAMRip", "a".repeat(40), seeds = 5))),
            excludedKeywords = setOf("camrip"),
        )
        assertTrue(merged.isEmpty())
    }

    // ---------- Torrentio ----------

    @Test
    fun `imdb id нормализуется с префиксом tt`() {
        assertEquals("tt0111161", TorrentioProvider.normalizeImdb("tt0111161"))
        assertEquals("tt0111161", TorrentioProvider.normalizeImdb("TT0111161"))
        assertEquals("tt0111161", TorrentioProvider.normalizeImdb("0111161"))
        assertEquals("", TorrentioProvider.normalizeImdb("  "))
    }

    @Test
    fun `разбор размера из title`() {
        assertEquals(14_656_575_897L, TorrentioProvider.parseSizeBytes("👤 105 💾 13.65GB"))
        assertEquals(901_775_360L, TorrentioProvider.parseSizeBytes("👤 12 💾 860MB"))
        assertEquals(0L, TorrentioProvider.parseSizeBytes("без размера"))
    }

    // ---------- Jackett torznab ----------

    @Test
    fun `разбор torznab XML`() {
        val xml = """
        <?xml version="1.0"?>
        <rss><channel>
          <item>
            <title>Some.Movie.2023 1080p BluRay</title>
            <link>http://localhost/dl?file=1.torrent</link>
            <torznab:attr name="infohash" value="ABCDEF0123456789ABCDEF0123456789ABCDEF01"/>
            <torznab:attr name="seeders" value="42"/>
            <torznab:attr name="size" value="2000000000"/>
          </item>
          <item>
            <title>Broken.Item.No.Hash</title>
          </item>
        </channel></rss>
        """.trimIndent()

        val parsed = JackettProvider.parseTorznab(xml)
        assertEquals(1, parsed.size)
        val t = parsed.first()
        assertEquals("Some.Movie.2023 1080p BluRay", t.name)
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", t.infoHash)
        assertEquals(42, t.seeds)
        assertEquals(2_000_000_000L, t.sizeBytes)
        assertEquals("jackett", t.provider)
        assertEquals("1080p", t.resolution)
    }

    @Test
    fun `hash из magnet-ссылки`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            hash,
            ApibayProvider.hashFromMagnet("magnet:?xt=urn:btih:$hash&dn=Test"),
        )
        assertNull(ApibayProvider.hashFromMagnet("magnet:?xt=urn:btih:bad"))
    }
}
