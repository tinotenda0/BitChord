package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.KIND_CONNECT
import com.music.bitchord.data.listentogether.KIND_JAM
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
 * What being in Connect means on this side of the wire.
 *
 * A signed-in device sits in its account's Connect party nearly all the time,
 * so every rule written for a jam that keys off "in a party" would otherwise
 * apply to ordinary listening too. These pin down the split.
 */
class ConnectStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val phone = PartyMember(memberId = "phone", isHost = true, connected = true, deviceKey = "k1", app = "prod")
    private val laptop = PartyMember(
        memberId = "laptop", connected = true, deviceKey = "k2", app = "prod", role = PartyMember.ROLE_REMOTE,
    )

    private fun state(you: PartyMember, kind: String = KIND_CONNECT) = ListenTogether.State(
        code = "~abc",
        kind = kind,
        you = you,
        members = listOf(phone, laptop),
        playback = PartyPlayback(clockMemberId = "phone"),
    )

    @Test
    fun `connect is not a jam`() {
        val s = state(phone)
        assertTrue(s.isConnect)
        assertFalse(s.inJam)
        assertTrue(state(phone, kind = KIND_JAM).inJam)
        assertFalse(ListenTogether.State().inJam)
    }

    @Test
    fun `the output owns its queue and a remote does not`() {
        assertTrue(state(phone).ownsQueue)
        assertFalse(state(laptop).ownsQueue)
        assertFalse(state(phone, kind = KIND_JAM).ownsQueue)
        assertTrue(ListenTogether.State().ownsQueue)
    }

    @Test
    fun `the output is the playing device`() {
        assertEquals("phone", state(laptop).output?.memberId)
        assertNull(state(phone, kind = KIND_JAM).output)
    }

    @Test
    fun `only the output tops up the queue in connect`() {
        assertEquals("phone", autoplaySupplierId(state(laptop)))
        val gone = state(laptop).copy(members = listOf(phone.copy(connected = false), laptop))
        assertNull(autoplaySupplierId(gone))
    }

    @Test
    fun `a jam snapshot without a kind reads as a jam`() {
        val snapshot = json.decodeFromString(PartySnapshot.serializer(), """{"code":"ABC123"}""")
        assertEquals(KIND_JAM, snapshot.kind)
    }

    @Test
    fun `connect identity fields come through`() {
        val snapshot = json.decodeFromString(
            PartySnapshot.serializer(),
            """{"code":"~abc","kind":"connect","members":[
               {"memberId":"a","deviceKey":"k1","app":"prod","deviceName":"Pixel 9"},
               {"memberId":"b","deviceKey":"k1","app":"dev","deviceName":"Pixel 9"}]}""",
        )
        assertEquals(KIND_CONNECT, snapshot.kind)
        val (prod, dev) = snapshot.members
        assertEquals(prod.deviceKey, dev.deviceKey)
        assertEquals("dev", dev.app)
    }
}
