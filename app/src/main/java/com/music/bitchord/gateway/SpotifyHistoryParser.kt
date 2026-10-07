package com.music.bitchord.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/** One stream from a Spotify history export. */
data class SpotifyStream(
    /** When it stopped playing, epoch ms (UTC — both export formats are). */
    val endMs: Long,
    val playedMs: Long,
    val track: String,
    val artist: String,
    val album: String = "",
)

/**
 * Reads Spotify's "Download your data" exports, in either of their two shapes:
 *
 *  - **Extended streaming history** (`Streaming_History_Audio_*.json`): every
 *    stream since the account began, `ts` to the second, with podcasts and
 *    audiobooks mixed in under null track names;
 *  - **Account data** (`StreamingHistory_music_*.json`, older exports just
 *    `StreamingHistory*.json`): the last year, `endTime` to the minute.
 *
 * From a zip as Spotify sends it or the JSON files on their own. Only music
 * streams of [MIN_PLAYED_MS] or more come out — the gateway refuses anything
 * shorter anyway, and an export is mostly skips.
 */
object SpotifyHistoryParser {

    /** A stream, by Spotify's own definition; matches the gateway's minimum. */
    const val MIN_PLAYED_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val basicTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * Everything in [input]: a zip of the export (as Spotify sends it), or one of its
     * JSON files. [name] is the file's name, only used to tell which.
     */
    fun read(name: String, input: InputStream): List<SpotifyStream> {
        val buffered = input.buffered()
        buffered.mark(4)
        val head = ByteArray(2).also { buffered.read(it) }
        buffered.reset()
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (!isZip) return parse(buffered.readBytes().decodeToString())
        val out = mutableListOf<SpotifyStream>()
        ZipInputStream(buffered).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && isMusicHistory(entry.name)) {
                    out += parse(zip.readBytes().decodeToString())
                }
            }
        }
        return out
    }

    /** The files in an export that hold music streams — not video, podcasts or anything else. */
    internal fun isMusicHistory(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        if (!name.endsWith(".json")) return false
        return name.startsWith("streaming_history_audio") ||
            (name.startsWith("streaminghistory") && !name.contains("podcast") && !name.contains("video"))
    }

    /** One JSON file of either format. Entries that aren't a music stream are left out. */
    fun parse(text: String): List<SpotifyStream> {
        val entries = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return entries.mapNotNull { (it as? JsonObject)?.let(::stream) }
            .filter { it.playedMs >= MIN_PLAYED_MS }
    }

    private fun stream(obj: JsonObject): SpotifyStream? {
        fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun number(key: String) = obj[key]?.jsonPrimitive?.longOrNull
        // Extended: a null track name is a podcast episode or an audiobook chapter.
        text("ts")?.let { ts ->
            val track = text("master_metadata_track_name") ?: return null
            val artist = text("master_metadata_album_artist_name") ?: return null
            val end = runCatching { Instant.parse(ts).toEpochMilli() }.getOrNull() ?: return null
            return SpotifyStream(end, number("ms_played") ?: 0L, track, artist,
                text("master_metadata_album_album_name").orEmpty())
        }
        // Account data.
        val endTime = text("endTime") ?: return null
        val end = runCatching {
            LocalDateTime.parse(endTime, basicTime).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull() ?: return null
        return SpotifyStream(end, number("msPlayed") ?: 0L,
            text("trackName") ?: return null, text("artistName") ?: return null)
    }
}
