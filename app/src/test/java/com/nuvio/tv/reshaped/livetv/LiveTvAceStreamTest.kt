package com.nuvio.tv.reshaped.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvAceStreamTest {

    private val id = "0123456789abcdef0123456789ABCDEF01234567"

    @Test
    fun readsContentIdsFromLinks() {
        val expected = id.lowercase()
        assertEquals(expected, "acestream://$id".aceStreamContentId())
        assertEquals(expected, "  ACESTREAM://$id/ ".aceStreamContentId())
        assertEquals(expected, id.aceStreamContentId())
        assertNull("acestream://not-an-id".aceStreamContentId())
        assertNull("https://stream.example/1.m3u8".aceStreamContentId())
    }

    @Test
    fun tellsAceLinksFromHttpLinks() {
        assertTrue("acestream://$id".isAceStreamLink())
        assertTrue(id.isAceStreamLink())
        assertFalse("http://127.0.0.1:6878/ace/getstream?id=$id".isAceStreamLink())
    }

    @Test
    fun playlistAceEntriesPlayThroughTheEngine() {
        val playlist = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sport",Sport One
            acestream://$id
            #EXTINF:-1,Plain
            https://stream.example/2.m3u8
            """.trimIndent().lineSequence(),
        )
        assertEquals(2, playlist.channels.size)
        assertEquals("$ACE_STREAM_DEFAULT_ENGINE/ace/manifest.m3u8?id=${id.lowercase()}", playlist.channels[0].streamUrl)
        assertEquals("Sport", playlist.channels[0].group)
        assertEquals("https://stream.example/2.m3u8", playlist.channels[1].streamUrl)
    }

    @Test
    fun normalizesEngineAddress() {
        assertEquals(ACE_STREAM_DEFAULT_ENGINE, LiveTvAceStreamSettings(engineUrl = "").normalized().engineUrl)
        assertEquals("http://192.168.1.5:6878", LiveTvAceStreamSettings(engineUrl = "192.168.1.5:6878/").normalized().engineUrl)
        assertEquals(
            "http://10.0.0.2:6878",
            LiveTvAceStreamSettings(engineUrl = "http://10.0.0.2:6878/search?query=x").normalized().engineUrl,
        )
        assertEquals("sport", LiveTvAceStreamSettings(category = " Sport ").normalized().category)
    }
}
