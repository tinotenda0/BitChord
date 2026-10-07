package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.KIND_CONNECT
import com.music.bitchord.data.listentogether.KIND_JAM
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.ListenTogether.Connection
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop this guards against: the playing device lost the party, played on
 * into the next song, and was dragged back to the stale party state, the same
 * song from where the connection dropped, every time that song ended.
 */
class PartyReconnectTest {

    private val me = PartyMember(memberId = "me", isHost = true, connected = true)

    private fun state(
        connection: Connection = Connection.LIVE,
        kind: String = KIND_JAM,
        you: PartyMember = me,
        clock: String? = "me",
        track: String? = "homecoming",
        playing: Boolean = true,
    ) = ListenTogether.State(
        code = "ABC123",
        kind = kind,
        you = you,
        members = listOf(you),
        connection = connection,
        playback = PartyPlayback(
            track = track?.let { PartyTrack(videoId = it) },
            isPlaying = playing,
            clockMemberId = clock,
        ),
    )

    @Test
    fun `nothing follows a party it is not hearing from`() {
        assertFalse(mayFollow(state(connection = Connection.CONNECTING)))
        assertFalse(mayFollow(state(connection = Connection.OFFLINE)))
        assertTrue(mayFollow(state()))
    }

    @Test
    fun `the device playing catches the party up when it moved on while away`() {
        assertTrue(shouldCatchUpOnReconnect(state(), localTrackId = "next-song", localPlaying = true))
        assertTrue(shouldCatchUpOnReconnect(state(), localTrackId = "homecoming", localPlaying = false))
    }

    @Test
    fun `nothing to catch up when it is where the party is`() {
        assertFalse(shouldCatchUpOnReconnect(state(), localTrackId = "homecoming", localPlaying = true))
    }

    @Test
    fun `only the device whose playback is the party's speaks for it`() {
        assertFalse(shouldCatchUpOnReconnect(state(clock = "someone-else"), "next-song", true))
        val remote = me.copy(role = PartyMember.ROLE_REMOTE, isHost = false)
        assertFalse(shouldCatchUpOnReconnect(state(kind = KIND_CONNECT, you = remote, clock = null), "next-song", true))
        assertTrue(shouldCatchUpOnReconnect(state(kind = KIND_CONNECT, clock = null), "next-song", true))
    }

    @Test
    fun `not before the party is heard from, nor for an empty one`() {
        assertFalse(shouldCatchUpOnReconnect(state(connection = Connection.CONNECTING), "next-song", true))
        assertFalse(shouldCatchUpOnReconnect(state(track = null), "next-song", true))
        assertFalse(shouldCatchUpOnReconnect(state(), localTrackId = null, localPlaying = true))
    }

    @Test
    fun `an idle device never fills an empty Connect party`() {
        val empty = state(kind = KIND_CONNECT, clock = null, track = null, playing = false)
        assertFalse(shouldSeedEmptyParty(empty, playing = false))
        assertTrue(shouldSeedEmptyParty(empty, playing = true))
    }

    @Test
    fun `a jam host seeds what it has loaded, playing or not`() {
        assertTrue(shouldSeedEmptyParty(state(track = null, playing = false), playing = false))
    }

    @Test
    fun `nobody seeds a party that has music, or before hearing from it, or as a guest`() {
        assertFalse(shouldSeedEmptyParty(state(kind = KIND_CONNECT), playing = true))
        assertFalse(shouldSeedEmptyParty(state(track = null, connection = Connection.CONNECTING), playing = true))
        assertFalse(shouldSeedEmptyParty(state(track = null, you = me.copy(isHost = false)), playing = true))
    }
}
