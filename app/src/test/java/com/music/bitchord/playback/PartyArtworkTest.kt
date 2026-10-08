package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A cover only the playing device can open must never reach the rest of the party. */
class PartyArtworkTest {

    private fun song(cover: String?) =
        Song(videoId = "dQw4w9WgXcQ", title = "Song", artist = "Artist", thumbnailUrl = cover)

    @Test
    fun aDownloadsSavedCoverIsNotSent() {
        assertNull(song("file:///data/user/0/com.music.bitchord/files/art/abc.jpg").toPartyTrack(0L).thumbnailUrl)
        assertNull(song("content://media/external/images/42").toPartyTrack(0L).thumbnailUrl)
    }

    @Test
    fun aWebCoverIsSentAsIs() {
        val url = "https://lh3.googleusercontent.com/abc=w544-h544"
        assertEquals(url, song(url).toPartyTrack(0L).thumbnailUrl)
    }

    @Test
    fun aFileCoverFromAnotherDeviceIsDroppedOnArrival() {
        val arrived = PartyTrack(videoId = "dQw4w9WgXcQ", thumbnailUrl = "file:///data/user/0/x/files/art/abc.jpg")
        assertNull(arrived.toSong().thumbnailUrl)
    }

    @Test
    fun aWebCoverArrivesAsIs() {
        val url = "https://lh3.googleusercontent.com/abc=w544-h544"
        assertEquals(url, PartyTrack(videoId = "dQw4w9WgXcQ", thumbnailUrl = url).toSong().thumbnailUrl)
    }
}
