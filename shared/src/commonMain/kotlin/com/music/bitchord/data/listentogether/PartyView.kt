package com.music.bitchord.data.listentogether

/**
 * Fork: what a device knows of the party it is in, as both apps' party state
 * presents it.
 *
 * The phone and the desktop each keep their own client for the server, but the
 * decisions made from what that client holds — whether to follow the party,
 * who may fill an empty one, which device is playing Connect — have to be the
 * same on both, or the two disagree about who is in charge of the music. So
 * those decisions (see PartyRules) read this rather than either app's state.
 */
interface PartyView {
    val code: String?
    val kind: String
    val you: PartyMember?
    val members: List<PartyMember>
    val hostOnlyControl: Boolean
    val playback: PartyPlayback
    val queue: PartyQueue

    /**
     * Hearing from the party right now: welcomed on a live socket. Before that,
     * everything held is what was true before the gap, and nothing may be done
     * on the strength of it.
     */
    val live: Boolean

    val inParty: Boolean get() = code != null

    /**
     * Whether this device may not drive the music: a listener in a party whose
     * host has taken control of it. The server enforces the same rule.
     */
    val controlsLocked: Boolean get() = inParty && hostOnlyControl && you?.isHost != true

    /** This device drives the party without playing it. */
    val isRemote: Boolean get() = inParty && you?.isRemote == true

    /** This device's real playhead is the one the party follows. */
    val isClock: Boolean get() {
        val me = you ?: return false
        return inParty && playback.clockMemberId == me.memberId
    }

    /** In this account's Connect party: its own devices, joined without a code. */
    val isConnect: Boolean get() = inParty && kind == KIND_CONNECT

    /** In a jam: a party joined with a code, shared with other people. */
    val inJam: Boolean get() = inParty && kind != KIND_CONNECT

    /** Whether the queue on this device's own player is this user's own music. */
    val ownsQueue: Boolean get() = !inParty || (isConnect && !isRemote)

    /** The Connect device playing right now, or null. */
    val output: PartyMember? get() = if (isConnect) members.firstOrNull { it.isHost && !it.isRemote } else null
}
