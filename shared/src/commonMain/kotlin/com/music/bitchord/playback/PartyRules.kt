package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.PartyView

// Fork: the party decisions both apps make, in one place. See PartyView.

data class QueueMoveDelta(
    val fromIndex: Int,
    val toIndex: Int,
    val videoId: String,
)

/**
 * Detects if [newList] is the result of moving exactly one item in [oldList].
 * If so, returns the from and to indices (offset by [baseOffset]) and the item's id.
 * Returns null if the lists cannot be explained by a single move.
 */
fun detectSingleMove(
    oldList: List<String>,
    newList: List<String>,
    baseOffset: Int = 0,
): QueueMoveDelta? {
    if (oldList.size != newList.size || oldList == newList || oldList.isEmpty()) return null
    if (oldList.groupingBy { it }.eachCount() != newList.groupingBy { it }.eachCount()) return null

    for (from in oldList.indices) {
        val item = oldList[from]
        val withoutItem = oldList.toMutableList().apply { removeAt(from) }
        for (to in oldList.indices) {
            if (from == to) continue
            val simulated = withoutItem.toMutableList().apply { add(to, item) }
            if (simulated == newList) {
                return QueueMoveDelta(
                    fromIndex = baseOffset + from,
                    toIndex = baseOffset + to,
                    videoId = item,
                )
            }
        }
    }
    return null
}

/**
 * Whether the player may be moved to match the party at all: only while the
 * party is being heard from. A device out of touch keeps playing what it is
 * playing; see [shouldCatchUpOnReconnect] for the way back.
 */
fun mayFollow(party: PartyView): Boolean =
    party.live

/**
 * Whether this device fills an empty party with what its own player holds.
 *
 * A jam is created on purpose, by somebody with the song they mean to share
 * loaded, so its host seeds it playing or not. Connect is joined by every
 * signed-in device on its own, and an idle one holds whatever it was left on:
 * a tablet at home that happened to sign in first after the server restarted
 * pushed its paused, hours-old queue over the phone that was out playing. So in
 * Connect only a device that is actually playing may seed.
 */
fun shouldSeedEmptyParty(party: PartyView, playing: Boolean): Boolean {
    if (!party.live) return false
    if (party.you?.isHost != true || party.playback.track != null) return false
    return playing || !party.isConnect
}

/**
 * Whether this device, just back in touch, should tell the party what it is
 * doing rather than be told. Only the device whose playback is the party's (the
 * clock, or Connect's output) and only when it actually moved on while away.
 */
fun shouldCatchUpOnReconnect(
    party: PartyView,
    localTrackId: String?,
    localPlaying: Boolean,
): Boolean {
    if (!party.live || localTrackId == null) return false
    val ownsPlayback = party.isClock || (party.isConnect && !party.isRemote)
    if (!ownsPlayback) return false
    val playback = party.playback
    // An empty party is seeded from this device by [PartySync] as it is.
    val partyTrack = playback.track ?: return false
    return localTrackId != partyTrack.videoId || localPlaying != playback.isPlaying
}

/**
 * The running order a remote shows: the party's queue, with the current song's
 * entry taken from the party's track wherever that knows more.
 *
 * The two are separate records. The queue's copy of a song is whatever the
 * device that queued it knew, often no length at all (anything picked from a
 * row that showed none); the track is the one the device playing it reports
 * its real length into. A remote built from the queue alone had no length for
 * the song playing, which read as 0:00 / -0:00 with the knob at the end, and
 * the lyrics, which wait for a length to match against, never loaded for it.
 *
 * And the queue and the track are separate controls, so between them the party
 * can hold a running order the current song is not in. Showing that order
 * would put the wrong song under the cursor, so the track alone stands in for
 * it until the queue catches up.
 */
fun remotePlaylist(party: PartyView): List<PartyTrack> {
    val current = party.playback.track ?: return emptyList()
    val queue = party.queue.items
    if (queue.none { it.videoId == current.videoId }) return listOf(current)
    return queue.map { entry ->
        if (entry.videoId == current.videoId) {
            // Fork: the cover too, which the queue's copy can lack the same way.
            entry.copy(
                durationMs = entry.durationMs ?: current.durationMs,
                thumbnailUrl = entry.thumbnailUrl ?: current.thumbnailUrl,
            )
        } else {
            entry
        }
    }
}

/** What the output does with the party's volume request state. See [nextVolumeStep]. */
data class VolumeStep(val applied: Long, val target: Double?, val fresh: Boolean)

/**
 * Which volume request, if any, the output should act on now.
 *
 * Requests are numbered and acted on once each. The output's own reports never
 * appear here, which is the point: when reports and requests shared one value,
 * a report of an older level overwrote a newer request and was then applied as
 * though it were one, and the volume bounced between the two.
 *
 * [applied] below zero means the device has just become the output (or got back
 * in touch): requests made before then were for another device, so they are
 * taken as read rather than acted on. A count lower than [applied] is a server
 * that restarted and began counting again, which is treated the same way.
 */
fun nextVolumeStep(applied: Long, reqSeq: Long, target: Double?, allowed: Boolean): VolumeStep = when {
    applied < 0 || reqSeq < applied -> VolumeStep(reqSeq, null, fresh = true)
    reqSeq > applied -> VolumeStep(reqSeq, target?.takeIf { allowed }, fresh = false)
    else -> VolumeStep(applied, null, fresh = false)
}
