package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: a cover for the playing track when it arrived without one.
 *
 * An album page bills its artwork once, in its header; its track rows carry
 * none. The album page stamps that cover onto each row as it loads, but a track
 * queued by any other route — an album shuffled from its card, a station off an
 * album track, a song a remote added — reaches the player with nothing, and the
 * player, the mini player and every remote in a party draw the empty placeholder
 * for as long as it plays.
 *
 * So the playing track's own watch-queue entry is asked for one — the same
 * lookup the player already makes for a track's album and artist links — once
 * per track, and the answer is kept for the rest of the session. Queue rows reuse
 * what has already been found but never ask on their own: a queue can be a
 * thousand songs, and only the one playing is worth a request.
 */
object MissingArtwork {

    private val found = ConcurrentHashMap<String, String>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever a cover is found, so whatever drew a placeholder can redraw. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** [song] with a cover if one is known; asks for one if it is the kind that can be found. */
    fun fill(song: Song): Song {
        if (!song.thumbnailUrl.isNullOrBlank()) return song
        found[song.videoId]?.let { return song.copy(thumbnailUrl = it) }
        if (song.localUri == null && song.videoId.length == YOUTUBE_ID_LENGTH && asked.add(song.videoId)) {
            scope.launch {
                YtMusicRepository.trackLinks(song.videoId).getOrNull()?.thumbnailUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        found[song.videoId] = it
                        _revision.value++
                    }
            }
        }
        return song
    }

    /** [song] with a cover if one is already known, without asking for one. */
    fun cached(song: Song): Song {
        if (!song.thumbnailUrl.isNullOrBlank()) return song
        return found[song.videoId]?.let { song.copy(thumbnailUrl = it) } ?: song
    }

    private const val YOUTUBE_ID_LENGTH = 11
}
