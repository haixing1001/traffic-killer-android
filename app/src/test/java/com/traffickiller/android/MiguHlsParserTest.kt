package com.traffickiller.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiguHlsParserTest {

    private val masterPlaylist = """
        #EXTM3U
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2084544
        01.m3u8?msisdn=abc&token=1
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1024000
        02.m3u8?msisdn=abc&token=1
    """.trimIndent()

    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXTINF:6.000,
        20251221052412-01-3128415.ts?msisdn=abc
        #EXTINF:6.000,
        20251221052412-01-3128416.ts?msisdn=abc
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun isMasterPlaylist_detectsStreamInfTag() {
        assertTrue(MiguHlsParser.isMasterPlaylist(masterPlaylist))
        assertFalse(MiguHlsParser.isMasterPlaylist(mediaPlaylist))
    }

    @Test
    fun firstVariantUri_returnsFirstNonCommentLine() {
        assertEquals("01.m3u8?msisdn=abc&token=1", MiguHlsParser.firstVariantUri(masterPlaylist))
        assertNull(MiguHlsParser.firstVariantUri("#EXTM3U\n#EXT-X-ENDLIST"))
    }

    @Test
    fun segmentUris_skipsCommentsAndBlankLines() {
        val segments = MiguHlsParser.segmentUris(mediaPlaylist)
        assertEquals(2, segments.size)
        assertEquals("20251221052412-01-3128415.ts?msisdn=abc", segments[0])
        assertEquals("20251221052412-01-3128416.ts?msisdn=abc", segments[1])
    }

    @Test
    fun resolveAgainst_handlesAbsoluteAndRelativeUris() {
        val base = "http://hlszymgsplive.miguvideo.com:8080/wd_r2/cctv8hd/1000/index.m3u8?x=1"

        assertEquals(
            "http://example.com/a.ts",
            MiguHlsParser.resolveAgainst(base, "http://example.com/a.ts")
        )
        assertEquals(
            "http://hlszymgsplive.miguvideo.com:8080/wd_r2/cctv8hd/1000/01.m3u8",
            MiguHlsParser.resolveAgainst(base, "01.m3u8")
        )
    }
}