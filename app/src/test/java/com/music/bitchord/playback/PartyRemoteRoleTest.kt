package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartySnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remotes and the clock, as this side of the wire reads them.
 *
 * Both are decided by the server (see `backend/party.ClockMember`); what matters
 * here is the derivation the player layer acts on, and that a server which
 * predates them reads as the behaviour the app had before.
 */
class PartyRemoteRoleTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun state(
        you: PartyMember? = PartyMember(memberId = "me"),
        clock: String? = null,
        code: String? = "ABC123",
    ) = ListenTogether.State(
        code = code,
        you = you,
        members = listOfNotNull(you),
        playback = PartyPlayback(clockMemberId = clock),
    )

    @Test
    fun `a remote member makes this device a remote`() {
        val remote = PartyMember(memberId = "me", role = PartyMember.ROLE_REMOTE)
        assertTrue(state(you = remote).isRemote)
        assertFalse(state().isRemote)
    }

    @Test
    fun `this device is the clock only when named as it`() {
        assertTrue(state(clock = "me").isClock)
        assertFalse(state(clock = "someone-else").isClock)
        assertFalse(state(clock = null).isClock)
    }

    @Test
    fun `outside a party nothing is a remote or a clock`() {
        val remote = PartyMember(memberId = "me", role = PartyMember.ROLE_REMOTE)
        assertFalse(state(you = remote, clock = "me", code = null).isRemote)
        assertFalse(state(you = remote, clock = "me", code = null).isClock)
    }

    @Test
    fun `a server that predates remotes reads as speakers with no clock`() {
        val snapshot = json.decodeFromString(
            PartySnapshot.serializer(),
            """{"code":"ABC123","members":[{"memberId":"a","isHost":true}],"playback":{"seq":3}}""",
        )
        assertEquals(PartyMember.ROLE_SPEAKER, snapshot.members.single().role)
        assertFalse(snapshot.members.single().isRemote)
        assertNull(snapshot.playback.clockMemberId)
    }

    @Test
    fun `the role and clock come through from a current server`() {
        val snapshot = json.decodeFromString(
            PartySnapshot.serializer(),
            """{"code":"ABC123","members":[{"memberId":"a","isHost":true,"role":"speaker"},
               {"memberId":"b","role":"remote"}],"playback":{"seq":3,"clockMemberId":"a"}}""",
        )
        assertTrue(snapshot.members.last().isRemote)
        assertEquals("a", snapshot.playback.clockMemberId)
    }
}
