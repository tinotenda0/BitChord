package com.music.bitchord.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The party, as a player, for a device that only drives it.
 *
 * A remote plays nothing, but every surface that shows and controls music in
 * this app talks to a Media3 player through the session: the player screen, the
 * queue, the notification, a headset button. So rather than teach each of them
 * about remotes, the playback service puts this in the session in place of the
 * real ExoPlayer while the device is a remote (the same trick Media3's own Cast
 * support uses). Its state is the party's: track, running order, playing or
 * not, and a position computed from the party's anchor. Everything done to it
 * is turned into a party control and sent, and nothing changes here until the
 * server's answer arrives — the server is the truth for a remote exactly as it
 * is for every other member.
 *
 * Commands wait for that answer before they complete. Media3 shows a command's
 * expected result as a placeholder until its future completes, so completing at
 * once would flick the play button back to the old state for the round trip,
 * then over again when the echo landed.
 */
@UnstableApi
class PartyRemotePlayer(
    private val scope: CoroutineScope,
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private val handler = Handler(looper)
    private var watcher: Job? = null

    /** Commands sent and not yet answered, by the party seq they were sent at. */
    private val pending = mutableListOf<Pair<Long, SettableFuture<Any?>>>()

    /** Built once per running order: making a [MediaItem] stats the disk for downloads. */
    private var playlistKey: Any? = null
    private var playlist: List<MediaItemData> = emptyList()
    private var tracks: List<PartyTrack> = emptyList()

    private var lastSeq = -1L
    private var lastAnchor = Pair(0L, 0L)

    init {
        watcher = scope.launch(Dispatchers.Main) {
            ListenTogether.state
                .map { Triple(it.playback, it.queue, it.connection to it.controlsLocked) }
                .distinctUntilChanged()
                .collect {
                    settlePending()
                    invalidateState()
                }
        }
    }

    override fun getState(): State {
        val party = ListenTogether.state.value
        val playback = party.playback
        val current = playback.track
        val items = playlistFor(party)
        val index = when {
            current == null || items.isEmpty() -> C.INDEX_UNSET
            else -> tracks.indices.firstOrNull { it == playback.queueIndex && tracks[it].videoId == current.videoId }
                ?: tracks.indexOfFirst { it.videoId == current.videoId }.takeIf { it >= 0 }
                ?: 0
        }

        val builder = State.Builder()
            .setAvailableCommands(commands(party))
            .setPlaylist(items)
            .setPlayWhenReady(playback.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(
                when {
                    current == null -> Player.STATE_IDLE
                    party.connection != ListenTogether.Connection.LIVE -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
        if (index != C.INDEX_UNSET) {
            builder
                .setCurrentMediaItemIndex(index)
                .setContentPositionMs { ListenTogether.partyPositionMs() ?: playback.positionMs }
        }

        // Somebody moved the playhead — a seek, a new track, a re-anchor onto
        // the host. Said as a discontinuity, so a progress bar that is
        // extrapolating on its own jumps rather than gliding to the new place.
        val anchor = playback.anchorMs to playback.positionMs
        if (lastSeq != -1L && playback.seq != lastSeq && anchor != lastAnchor && index != C.INDEX_UNSET) {
            builder.setPositionDiscontinuity(
                Player.DISCONTINUITY_REASON_SEEK,
                ListenTogether.partyPositionMs() ?: playback.positionMs,
            )
        }
        lastSeq = playback.seq
        lastAnchor = anchor
        return builder.build()
    }

    private fun playlistFor(party: ListenTogether.State): List<MediaItemData> {
        val source = remotePlaylist(party)
        val key = source
        if (key != playlistKey) {
            playlistKey = key
            tracks = source
            playlist = source.mapIndexed { i, track ->
                MediaItemData.Builder("$i:${track.videoId}")
                    .setMediaItem(track.toSong().toMediaItem())
                    .setDurationUs(track.durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            }
        }
        return playlist
    }

    private fun commands(party: ListenTogether.State): Player.Commands {
        val read = Player.Commands.Builder().addAll(
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
            COMMAND_RELEASE,
        )
        // A remote in a party its host has locked can watch and nothing more.
        // The server would refuse anything else; this keeps the buttons from
        // appearing to work and then being silently overruled.
        if (party.controlsLocked || party.connection != ListenTogether.Connection.LIVE) return read.build()
        return read.addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_PREPARE,
            COMMAND_STOP,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SET_MEDIA_ITEM,
            COMMAND_CHANGE_MEDIA_ITEMS,
        ).build()
    }

    // -------------------------------------------------------------- commands --

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) ListenTogether.play() else ListenTogether.pause()
        return awaitParty()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val party = ListenTogether.state.value
        val current = currentMediaItemIndex
        val position = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        when {
            mediaItemIndex == current || mediaItemIndex == C.INDEX_UNSET -> ListenTogether.seek(position)
            (seekCommand == COMMAND_SEEK_TO_NEXT || seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) &&
                mediaItemIndex == current + 1 -> ListenTogether.next()
            (seekCommand == COMMAND_SEEK_TO_PREVIOUS || seekCommand == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) &&
                mediaItemIndex == current - 1 -> ListenTogether.previous()
            else -> tracks.getOrNull(mediaItemIndex)?.let {
                ListenTogether.setTrack(it, position, isPlaying = party.playback.isPlaying)
            } ?: return Futures.immediateVoidFuture()
        }
        return awaitParty()
    }

    /**
     * Choosing something to play, from anywhere in the app: a song, an album,
     * a playlist. Becomes the party's running order and its track, as it would
     * from a speaker. The order is cut to what a party holds, starting at the
     * chosen song, so the song is never the one cut off.
     */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val chosen = mediaItems.toPartyTracks()
        if (chosen.isEmpty()) return Futures.immediateVoidFuture()
        val start = startIndex.takeIf { it != C.INDEX_UNSET }?.coerceIn(chosen.indices) ?: 0
        val queue = chosen.subList(start, minOf(chosen.size, start + 1 + ListenTogether.state.value.maxUpcoming))
        val position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        ListenTogether.setQueue(queue, 0)
        // Starting is the caller's next command (setMediaItems, prepare, play),
        // so this keeps the party as it is rather than guessing.
        ListenTogether.setTrack(queue[0], position, isPlaying = ListenTogether.state.value.playback.isPlaying)
        return awaitParty()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val added = mediaItems.toPartyTracks()
        if (added.isEmpty()) return Futures.immediateVoidFuture()
        ListenTogether.queueAdd(added, playNext = index == currentMediaItemIndex + 1)
        return awaitParty()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        (fromIndex until toIndex).mapNotNull { tracks.getOrNull(it)?.videoId }
            .forEach(ListenTogether::queueRemove)
        return awaitParty()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        // One row is what a drag moves, and all the party's move describes.
        val moved = tracks.getOrNull(fromIndex)
        if (toIndex - fromIndex != 1 || moved == null) return Futures.immediateVoidFuture()
        ListenTogether.queueMove(fromIndex, newIndex, moved.videoId)
        return awaitParty()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    // Stopping the remote is this phone putting the buttons down, not a request
    // to stop the music in somebody else's room.
    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        watcher?.cancel()
        watcher = null
        settlePending(all = true)
        return Futures.immediateVoidFuture()
    }

    // ------------------------------------------------------------- answers --

    /**
     * A future that completes when the party moves past where it is now, or
     * after [ANSWER_TIMEOUT_MS] if it never does — a refused or lost control
     * should put the buttons back, not hold them in the expected state.
     */
    private fun awaitParty(): ListenableFuture<*> {
        val future = SettableFuture.create<Any?>()
        val party = ListenTogether.state.value
        pending += (party.playback.seq + party.queue.seq) to future
        handler.postDelayed({ if (future.set(null)) invalidateState() }, ANSWER_TIMEOUT_MS)
        return future
    }

    private fun settlePending(all: Boolean = false) {
        val party = ListenTogether.state.value
        val now = party.playback.seq + party.queue.seq
        val done = pending.filter { all || now > it.first }
        done.forEach { it.second.set(null) }
        pending.removeAll(done)
    }

    private fun List<MediaItem>.toPartyTracks(): List<PartyTrack> = map { it.toSong() }
        // A file on this phone means nothing on the host's.
        .filterNot { it.videoId.startsWith("content://") || it.videoId.startsWith("file://") }
        .map { it.toPartyTrack(0L) }

    private companion object {
        const val ANSWER_TIMEOUT_MS = 2_500L
    }
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
internal fun remotePlaylist(party: ListenTogether.State): List<PartyTrack> {
    val current = party.playback.track ?: return emptyList()
    val queue = party.queue.items
    if (queue.none { it.videoId == current.videoId }) return listOf(current)
    return queue.map { entry ->
        if (entry.videoId == current.videoId && entry.durationMs == null && current.durationMs != null) {
            entry.copy(durationMs = current.durationMs)
        } else {
            entry
        }
    }
}
