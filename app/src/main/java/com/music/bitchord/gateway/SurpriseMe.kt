package com.music.bitchord.gateway

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.QueueSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Surprise Me: the gateway's DJ, as an endless queue.
 *
 * Each `getSurpriseMe` call continues the account's DJ session — themed
 * segments, never repeating a track within it, steering away from what was just
 * skipped (it learns that from [GatewayListening]'s reports). So the queue is
 * started with one batch and topped up with the next whenever it runs low,
 * through the same path AutoPlay uses (see `loadAutoplayTracks`), and does so
 * whether or not AutoPlay is switched on, as it did in PixelPlayer.
 *
 * A Surprise Me queue is recognised by its playback source, which every item
 * carries through the player's metadata, so the caption reads "Playing from
 * Surprise Me" and a top-up knows where to ask.
 */
object SurpriseMe {

    const val TITLE = "Surprise Me"
    private const val SOURCE_ID = "gateway:surprise-me"

    val source = QueueSource(TITLE, PlaybackSourceType.HOME, SOURCE_ID)

    fun isSurpriseMe(song: Song?): Boolean = song?.playbackSourceId == SOURCE_ID

    /** The DJ's next batch, ready to queue. */
    suspend fun batch(): Result<List<Song>> =
        Gateway.call("getSurpriseMe").map { root ->
            val entries = root["playlist"]?.jsonObject?.get("entry") as? JsonArray
            entries.orEmpty().mapNotNull { songOf(it.jsonObject) }
        }

    /** A top-up for a Surprise Me queue: new tracks only, at most [limit], as AutoPlay entries. */
    suspend fun continuation(existing: List<Song>, limit: Int): Result<List<Song>> =
        batch().map { candidates ->
            QueueBuilder.extend(existing, candidates, limit).map { it.asQueueEntry(QueueTier.AUTOPLAY) }
        }

    internal fun songOf(entry: JsonObject): Song? {
        fun text(key: String) = entry[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        val videoId = Gateway.videoId(text("id")) ?: return null
        val seconds = entry["duration"]?.jsonPrimitive?.longOrNull ?: 0L
        return Song(
            videoId = videoId,
            title = text("title"),
            artist = text("artist"),
            thumbnailUrl = text("coverUrl").takeIf { it.startsWith("http") }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationText = if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else null,
            // Only a real channel id is a page BitChord can open; the gateway's
            // name-derived ids ("yt-artistn-…") are not.
            artistId = text("artistId").takeIf { it.startsWith(ARTIST_PREFIX) }?.removePrefix(ARTIST_PREFIX),
            albumName = text("album").takeIf { it.isNotBlank() && it != PLACEHOLDER_ALBUM },
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        )
    }

    private const val ARTIST_PREFIX = "yt-artist-"

    /** What the gateway files a track under when YouTube gave it no album. */
    private const val PLACEHOLDER_ALBUM = "YouTube"
}
