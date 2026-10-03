package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output applies each volume request once, in order, and nothing else. The
 * bug this guards: its own reports were read back as requests, and the volume
 * bounced between an old level and a new one while a slider moved.
 */
class ConnectVolumeTest {

    @Test
    fun `a new request is applied once`() {
        val first = nextVolumeStep(applied = 3, reqSeq = 4, target = 0.6, allowed = true)
        assertEquals(0.6, first.target!!, 0.0)
        assertEquals(4L, first.applied)
        val again = nextVolumeStep(applied = first.applied, reqSeq = 4, target = 0.6, allowed = true)
        assertNull("the same request is not applied twice", again.target)
    }

    @Test
    fun `nothing is applied when no request is new, whatever the reported volume does`() {
        assertNull(nextVolumeStep(applied = 4, reqSeq = 4, target = 0.2, allowed = true).target)
    }

    @Test
    fun `requests from before this device was the output are taken as read`() {
        val arrived = nextVolumeStep(applied = -1, reqSeq = 9, target = 0.1, allowed = true)
        assertNull(arrived.target)
        assertEquals(9L, arrived.applied)
        assertTrue(arrived.fresh)
    }

    @Test
    fun `a restarted server counting again is not ignored for ever`() {
        val restarted = nextVolumeStep(applied = 12, reqSeq = 1, target = 0.5, allowed = true)
        assertEquals(1L, restarted.applied)
        assertEquals(0.5, nextVolumeStep(restarted.applied, reqSeq = 2, target = 0.5, allowed = true).target!!, 0.0)
    }

    @Test
    fun `a request is consumed but not applied when the output does not allow it`() {
        val refused = nextVolumeStep(applied = 1, reqSeq = 2, target = 0.9, allowed = false)
        assertNull(refused.target)
        assertEquals(2L, refused.applied)
    }
}
