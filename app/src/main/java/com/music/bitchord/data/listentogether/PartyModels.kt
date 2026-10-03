package com.music.bitchord.data.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The party server's wire format, one-for-one.
 *
 * The server speaks camelCase for exactly this reason — it has no other client
 * — so these carry no `@SerialName` and will not need any. If a field ever has
 * to be renamed on one side, rename it on both rather than papering over the
 * difference here; the protocol is documented in `backend/README.md` and that
 * document is the contract.
 */

/**
 * A song as the party knows it.
 *
 * Deliberately *not* [Song][com.music.bitchord.data.model.Song]. What a party
 * shares is which track is playing and where the playhead is; how each device
 * gets the audio — which source answered, at what quality, from the network or
 * from a download — stays that device's own business. Two people in a party can
 * be on entirely different sources and still be in the same place in the same
 * song, and keeping this type small is what guarantees that.
 */
@Serializable
data class PartyTrack(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    /** Preserves the queue's shared manual/AutoPlay section boundary. */
    val fromAutoplay: Boolean = false,
    /**
     * From the album or playlist being played rather than queued by hand.
     * Without it every device rebuilt a playlist as hand-queued songs, and
     * the next pick kept them as if they had been asked for.
     */
    val fromContext: Boolean = false,
)

/** One signed-in device in the party, as every other device sees it. */
@Serializable
data class PartyMember(
    val memberId: String,
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
    /** Whether they are currently holding a socket — not whether they are still in. */
    val connected: Boolean = false,
    val joinedAtMs: Long = 0,
    val lastSeenMs: Long = 0,
    /**
     * [ROLE_SPEAKER] plays the party out loud; [ROLE_REMOTE] only drives it.
     * Defaulted so a server that predates remotes reads everyone as a speaker,
     * which is what everyone was.
     */
    val role: String = ROLE_SPEAKER,
    /**
     * Connect only: which phone ([deviceKey], shared by every build on it), which
     * build ([app], `prod` or `dev`) and what the phone is called.
     */
    val deviceKey: String = "",
    val app: String = "",
    val deviceName: String = "",
) {
    val isRemote: Boolean get() = role == ROLE_REMOTE

    companion object {
        const val ROLE_SPEAKER = "speaker"
        const val ROLE_REMOTE = "remote"
    }
}

/**
 * Where the party is, as of a server timestamp.
 *
 * [positionMs] is not a current position. It is the position at [anchorMs], and
 * it only becomes a current position once the reader adds the time since — see
 * [ListenTogether.partyPositionMs]. That indirection is the entire sync
 * mechanism: a frame delayed by 300 ms carries an anchor 300 ms older and still
 * lands this device in exactly the right place.
 */
@Serializable
data class PartyPlayback(
    /**
     * Bumped by the server on every change. A state whose [seq] is not greater
     * than the one already applied is dropped unread — which is what makes two
     * people hitting pause at the same moment settle rather than oscillate.
     */
    val seq: Long = 0,
    val track: PartyTrack? = null,
    /**
     * Bumped only when the queue's *contents* change, and deliberately the only
     * thing about the queue that rides along with the state.
     *
     * The queue itself travels as [PartyQueue], separately and rarely. This
     * frame is re-sent to every device every few seconds forever, and a queue
     * inside it would be large, near-constant, and paid for continuously on
     * somebody's mobile data. So all that arrives here is a number to compare
     * against the copy already held — see [ListenTogether] for the refetch.
     */
    val queueSeq: Long = 0,
    val queueLength: Int = 0,
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorMs: Long = 0,
    /** The server's own reading of [positionMs] at the instant it sent the frame. */
    val effectivePositionMs: Long = 0,
    val updatedBy: String? = null,
    /** Member who selected this track; stable across pause, play, and seek controls. */
    val startedBy: String? = null,
    /** Snapshot of their name so attribution survives that member leaving. */
    val startedByName: String? = null,
    /** Party-wide setting so a connected listener can refill AutoPlay on host loss. */
    val autoplayEnabled: Boolean = false,
    val updatedAtMs: Long = 0,
    /**
     * The member whose real playhead the party follows: the host, while it is a
     * speaker. That device reports what it is actually playing and the server
     * re-anchors the party onto it, so it must not seek itself towards the
     * party the way every other speaker does. Null on a server that predates it.
     */
    val clockMemberId: String? = null,
    /**
     * Connect only: the output's volume, 0 to 1, or null while it is unknown.
     * [volumeControl] says whether the output lets other devices change it,
     * and [volumeSteps] how many steps it has. Not part of [seq]: it changes as
     * a slider moves and is never a control anybody aligns to.
     */
    val volume: Double? = null,
    val volumeControl: Boolean = false,
    val volumeSteps: Int = 15,
)

/**
 * The party's running order, which travels on its own schedule.
 *
 * Sent whole when a device joins — there is no other way for it to learn the
 * list — and after that only when it actually changes. [seq] is how a device
 * knows its copy is stale: every state frame carries the server's current one,
 * so a missed update is noticed on the very next heartbeat rather than lived
 * with until somebody presses something.
 */
@Serializable
data class PartyQueue(
    val seq: Long = 0,
    val index: Int = -1,
    val items: List<PartyTrack> = emptyList(),
)

@Serializable
data class PartySnapshot(
    val code: String = "",
    /** [KIND_JAM], joined with a code, or [KIND_CONNECT], this account's own devices. */
    val kind: String = KIND_JAM,
    /** Connect only: every device this account has, asleep ones included. */
    val devices: List<ConnectDevice> = emptyList(),
    /** How many tracks may follow the current one: 25 in a jam, a playlist's worth in Connect. */
    val maxUpcoming: Int = DEFAULT_MAX_UPCOMING,
    val createdAtMs: Long = 0,
    val maxMembers: Int = 5,
    /**
     * Whether only the host may drive the music here.
     *
     * Defaulted false so a party on a server that predates the setting reads as
     * the shared free-for-all this feature shipped as, rather than as locked.
     */
    val hostOnlyControl: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val playback: PartyPlayback = PartyPlayback(),
    val queue: PartyQueue = PartyQueue(),
    val serverMs: Long = 0,
)

/**
 * Who is in a party, to somebody who has not joined it.
 *
 * Deliberately smaller than [PartySnapshot]: enough to show a face and a name
 * before committing a device slot, and nothing that would let the holder of a
 * code act on a party they are not in.
 */
@Serializable
data class PartyPreview(
    val code: String = "",
    val hostName: String = "",
    val memberCount: Int = 0,
    val maxMembers: Int = 5,
    val isFull: Boolean = false,
    val members: List<PartyPreviewMember> = emptyList(),
)

@Serializable
data class PartyPreviewMember(
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
    val role: String = PartyMember.ROLE_SPEAKER,
)

/** The answer to a create or a join: the code, and this device's key to it. */
@Serializable
data class PartyMembership(
    val code: String,
    val token: String,
    val you: PartyMember,
    val party: PartySnapshot,
    val serverMs: Long = 0,
)

/** A compact local-only record of a live party action. */
@Serializable
data class PartyActivity(
    val action: String,
    val by: String,
    val atMs: Long,
    val detail: String = "",
)

@Serializable
internal data class JoinRequest(
    val userId: String,
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val maxMembers: Int? = null,
    val autoplayEnabled: Boolean? = null,
    /** Null joins as a speaker, and keeps the request identical to before remotes. */
    val role: String? = null,
)

const val KIND_JAM = "jam"

/** A jam's upcoming-queue limit, and what a server that does not say is taken to have. */
const val DEFAULT_MAX_UPCOMING = 25
const val KIND_CONNECT = "connect"

/** Signing one of this account's devices into its Connect party. */
@Serializable
internal data class ConnectRequest(
    val gatewayUser: String,
    val gatewayToken: String,
    val gatewaySalt: String,
    val deviceKey: String,
    val app: String,
    val deviceName: String,
    val displayName: String,
    val avatarUrl: String? = null,
    /** This device's UnifiedPush endpoint, so a sleeping device can be woken. */
    val pushEndpoint: String? = null,
    /** Music is coming out of this device right now; see `ListenTogether.localPlaybackActive`. */
    val playing: Boolean = false,
)

/**
 * One of this account's devices as the server remembers it, connected or not.
 * The ones that are not are what the devices sheet offers to wake.
 */
@Serializable
data class ConnectDevice(
    val deviceId: String = "",
    val deviceKey: String = "",
    val app: String = "",
    val deviceName: String = "",
    /** Gave a push endpoint, so it can be woken from another device. */
    val wakeable: Boolean = false,
    val connected: Boolean = false,
    val lastSeenMs: Long = 0,
    /** "jam" while it has left Connect for a jam; empty otherwise. */
    val status: String = "",
)

@Serializable
internal data class ApiError(
    @SerialName("error") val code: String = "",
    val message: String = "",
)
