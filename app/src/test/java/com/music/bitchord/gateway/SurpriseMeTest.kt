package com.music.bitchord.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurpriseMeTest {

    private fun entry(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun aGatewaySongBecomesAPlayableSurpriseMeSong() {
        val song = SurpriseMe.songOf(entry("""
            {"id": "yt-dQw4w9WgXcQ", "title": "Pompeii", "artist": "Bastille",
             "album": "Bad Blood", "coverUrl": "https://lh3.googleusercontent.com/x=w544-h544",
             "duration": 214, "artistId": "yt-artist-UC123"}
        """))!!
        assertEquals("dQw4w9WgXcQ", song.videoId)
        assertEquals("Pompeii", song.title)
        assertEquals("Bastille", song.artist)
        assertEquals("Bad Blood", song.albumName)
        assertEquals("https://lh3.googleusercontent.com/x=w544-h544", song.thumbnailUrl)
        assertEquals("3:34", song.durationText)
        assertEquals("UC123", song.artistId)
        assertTrue(SurpriseMe.isSurpriseMe(song))
    }

    @Test
    fun gatewayPlaceholdersAreDropped() {
        val song = SurpriseMe.songOf(entry("""
            {"id": "yt-dQw4w9WgXcQ", "title": "Song", "artist": "Someone",
             "album": "YouTube", "duration": 0, "artistId": "yt-artistn-Someone"}
        """))!!
        assertNull(song.albumName)
        assertNull(song.artistId)
        assertNull(song.durationText)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", song.thumbnailUrl)
    }

    @Test
    fun anythingThatIsNotASongIsSkipped() {
        assertNull(SurpriseMe.songOf(entry("""{"id": "yt-artist-UC123", "title": "x"}""")))
        assertNull(SurpriseMe.songOf(entry("""{"id": "12345", "title": "x"}""")))
    }
}
