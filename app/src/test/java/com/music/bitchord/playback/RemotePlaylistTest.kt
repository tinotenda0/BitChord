package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyQueue
import com.music.bitchord.data.listentogether.PartyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a remote shows. Built from the queue alone, the song playing had no
 * length whenever it was queued from a row that showed none, which drew the
 * progress bar as 0:00 / -0:00 and kept the lyrics from ever loading.
 */
class RemotePlaylistTest {

    private fun state(track: PartyTrack?, vararg queue: PartyTrack) = ListenTogether.State(
        code = "~abc",
        playback = PartyPlayback(track = track),
        queue = PartyQueue(items = queue.toList()),
    )

    @Test
    fun `the playing song takes its length from the party's track`() {
        val playing = PartyTrack("b", durationMs = 213_000L)
        val shown = remotePlaylist(state(playing, PartyTrack("a"), PartyTrack("b"), PartyTrack("c")))
        assertEquals(listOf("a", "b", "c"), shown.map { it.videoId })
        assertEquals(213_000L, shown[1].durationMs)
        assertNull("other songs are left as the queue has them", shown[2].durationMs)
    }

    @Test
    fun `a length the queue already has is kept`() {
        val shown = remotePlaylist(state(PartyTrack("a", durationMs = 1L), PartyTrack("a", durationMs = 2L)))
        assertEquals(2L, shown.single().durationMs)
    }

    @Test
    fun `a song not in the queue yet stands alone, and nothing playing shows nothing`() {
        val playing = PartyTrack("z", durationMs = 5L)
        assertEquals(listOf(playing), remotePlaylist(state(playing, PartyTrack("a"))))
        assertTrue(remotePlaylist(state(null, PartyTrack("a"))).isEmpty())
    }
}
