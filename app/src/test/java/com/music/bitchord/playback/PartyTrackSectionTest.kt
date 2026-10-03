package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A track's queue section has to survive a trip through the party. When it
 * did not, a playlist came back on every other device as hand-queued songs, and
 * the next song picked there kept the old playlist after it as though somebody
 * had asked for every one of them.
 */
class PartyTrackSectionTest {

    private fun song(tier: QueueTier) = Song(videoId = "v", title = "T", artist = "A", thumbnailUrl = null, queueTier = tier)

    @Test
    fun `a playlist track goes out marked as one and comes back as one`() {
        val wire = song(QueueTier.CONTEXT).toPartyTrack(200_000L)
        assertTrue(wire.fromContext)
        assertFalse(wire.fromAutoplay)
        assertEquals(QueueTier.CONTEXT, wire.toSong().queueTier)
    }

    @Test
    fun `hand-queued and AutoPlay tracks keep their sections too`() {
        assertEquals(QueueTier.USER_QUEUE, song(QueueTier.USER_QUEUE).toPartyTrack(0L).toSong().queueTier)
        assertEquals(QueueTier.AUTOPLAY, PartyTrack("v", fromAutoplay = true).toSong().queueTier)
    }

    @Test
    fun `a track from an older device reads as hand-queued, as it always did`() {
        assertEquals(QueueTier.USER_QUEUE, PartyTrack("v").toSong().queueTier)
    }
}
