package com.music.bitchord.gateway

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale

/**
 * Imports a Spotify listening history into the gateway, so the Replay and the stats
 * widget count years of Spotify listening alongside everything played here.
 *
 * The matching happens on this device, with the same [TrackMatcher] that decides
 * whether two catalogues hold the same recording when a song is played — it is the
 * part of this app that already knows a cover from a remix and a film credit from a
 * different song. Each distinct song is searched once on YouTube Music, and only a
 * confident match is kept; a song with none is counted as unmatched and its streams
 * are left out rather than filed under a guess.
 *
 * Matches are saved as they are found, so an import that is interrupted — the app
 * closed, the phone out of signal — picks up where it stopped when the same file is
 * chosen again, rather than searching thousands of songs from the start.
 *
 * Counting twice is the gateway's to prevent, not this class's: it derives each
 * stream's id itself (see `importSpotifyHistory`), so sending the same history again,
 * or the other export format of the same months, adds nothing. What comes back says so.
 */
object SpotifyImport {

    sealed interface State {
        data object Idle : State
        data object Reading : State
        data class Matching(val done: Int, val total: Int) : State
        data class Uploading(val done: Int, val total: Int) : State
        /** [added] new listens, [already] that were in already, [unmatched] songs left out. */
        data class Finished(val added: Int, val already: Int, val unmatched: Int) : State
        data class Failed(val message: String) : State
    }

    /** What the account holds from Spotify: plays, and the span they cover (epoch ms). */
    data class Imported(val plays: Int, val fromMs: Long, val toMs: Long)

    @Serializable
    private data class Match(
        val videoId: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val cover: String = "",
    )

    @Serializable
    private data class Upload(
        val endTime: Long,
        val msPlayed: Long,
        val trackName: String,
        val artistName: String,
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _imported = MutableStateFlow<Imported?>(null)
    val imported: StateFlow<Imported?> = _imported.asStateFlow()

    /** Its own scope, so leaving the settings screen doesn't stop an import halfway. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLock = Mutex()

    val running: Boolean
        get() = _state.value.let { it is State.Reading || it is State.Matching || it is State.Uploading }

    fun start(context: Context, uri: Uri) {
        if (running) return
        _state.value = State.Reading
        val app = context.applicationContext
        scope.launch {
            _state.value = runCatching { import(app, uri) }.getOrElse {
                Log.w(TAG, "Spotify import failed", it)
                State.Failed(it.message ?: "Couldn't import that file")
            }
            refreshImported()
        }
    }

    /** Asks the gateway what Spotify history the account holds. */
    fun refreshImported() {
        if (!Gateway.signedIn) return
        scope.launch {
            val history = Gateway.call("getSpotifyHistory").getOrNull()?.get("spotifyHistory")?.jsonObject
                ?: return@launch
            _imported.value = Imported(
                plays = history["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                fromMs = history["from"]?.jsonPrimitive?.longOrNull ?: 0L,
                toMs = history["to"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    /** Undoes every Spotify import on this account. */
    fun remove(context: Context) {
        if (running) return
        val app = context.applicationContext
        scope.launch {
            Gateway.call("removeSpotifyHistory")
                .onSuccess { afterStatsChanged(app) }
                .onFailure { _state.value = State.Failed(it.message ?: "Couldn't remove it") }
            if (_state.value is State.Finished) _state.value = State.Idle
            refreshImported()
        }
    }

    private suspend fun import(context: Context, uri: Uri): State {
        val name = displayName(context, uri)
        val streams = context.contentResolver.openInputStream(uri)?.use { SpotifyHistoryParser.read(name, it) }
            ?: error("Couldn't open that file")
        if (streams.isEmpty()) error("No Spotify listening history in that file")

        // One search per song, not per stream: a favourite is streamed hundreds of times.
        val songs = streams.groupBy { keyOf(it.track, it.artist) }
        val cacheFile = File(context.filesDir, MATCH_CACHE)
        val matches = cacheLock.withLock { readCache(cacheFile) }
        val toMatch = songs.keys.filterNot { it in matches }
        var done = songs.size - toMatch.size
        _state.value = State.Matching(done, songs.size)
        val gate = Semaphore(PARALLEL_SEARCHES)
        toMatch.chunked(SAVE_EVERY).forEach { chunk ->
            val found = chunk.map { key ->
                scope.async {
                    gate.withPermit {
                        val sample = songs.getValue(key).first()
                        key to match(sample)
                    }
                }
            }.awaitAll()
            // A search that failed (no signal) is left unrecorded, to be tried next time;
            // a search that found nothing is recorded as such, so it isn't asked again.
            found.forEach { (key, match) -> if (match != null) matches[key] = match }
            done += chunk.size
            _state.value = State.Matching(done, songs.size)
            cacheLock.withLock { writeCache(cacheFile, matches) }
        }

        val uploads = streams.mapNotNull { stream ->
            val match = matches[keyOf(stream.track, stream.artist)]?.takeIf { it.videoId.isNotEmpty() }
                ?: return@mapNotNull null
            Upload(stream.endMs, stream.playedMs, stream.track, stream.artist,
                match.videoId, match.title, match.artist, match.album, match.cover)
        }
        val unmatched = songs.keys.count { matches[it]?.videoId.isNullOrEmpty() }
        var added = 0
        var already = 0
        _state.value = State.Uploading(0, uploads.size)
        uploads.chunked(UPLOAD_BATCH).forEachIndexed { index, batch ->
            val body = Gateway.json.encodeToString(ListSerializer(Upload.serializer()), batch)
                .toRequestBody("application/json".toMediaType())
            val result = Gateway.call("importSpotifyHistory", body = body).getOrThrow()["import"]?.jsonObject
            added += result?.get("accepted")?.jsonPrimitive?.intOrNull ?: 0
            already += result?.get("duplicates")?.jsonPrimitive?.intOrNull ?: 0
            _state.value = State.Uploading(minOf((index + 1) * UPLOAD_BATCH, uploads.size), uploads.size)
        }
        if (added > 0) afterStatsChanged(context)
        return State.Finished(added, already, unmatched)
    }

    /**
     * The YouTube Music recording a Spotify song is, or a blank [Match] when there is
     * none — or null when the search itself failed, so it is tried again next time.
     */
    private suspend fun match(stream: SpotifyStream): Match? {
        val target = TrackMatcher.targetOf(
            Song(videoId = "", title = stream.track, artist = stream.artist, thumbnailUrl = null,
                albumName = stream.album.ifEmpty { null }),
        )
        var anySearchWorked = false
        for (query in TrackMatcher.queries(target)) {
            val results = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() ?: continue
            anySearchWorked = true
            val best = TrackMatcher.best(results.filterIsInstance<SearchResult.Track>().map { it.song }, target)
                ?: continue
            return Match(best.videoId, best.title, best.artist, best.albumName.orEmpty(), best.thumbnailUrl.orEmpty())
        }
        return if (anySearchWorked) Match() else null
    }

    private suspend fun afterStatsChanged(context: Context) {
        GatewayStats.forgetAll()
        ListeningStatsWidget.refresh(context)
    }

    /** A song's identity across its streams: Spotify's names, case and spacing folded. */
    internal fun keyOf(track: String, artist: String): String =
        fold(track) + "\u001f" + fold(artist)

    private fun fold(text: String) = text.trim().lowercase(Locale.ROOT).replace(SPACES, " ")

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun readCache(file: File): MutableMap<String, Match> = runCatching {
        Gateway.json.decodeFromString(MapSerializer(String.serializer(), Match.serializer()), file.readText())
            .toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeCache(file: File, matches: Map<String, Match>) {
        runCatching {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(Gateway.json.encodeToString(MapSerializer(String.serializer(), Match.serializer()), matches))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Couldn't save Spotify matches", it) }
    }

    private const val TAG = "SpotifyImport"
    private const val MATCH_CACHE = "spotify_matches.json"
    private val SPACES = Regex("\\s+")

    /** Searches in flight at once: quick enough for thousands of songs, gentle on YouTube. */
    private const val PARALLEL_SEARCHES = 4

    /** Matches are saved every this many songs, so an interruption loses little. */
    private const val SAVE_EVERY = 40

    /** The gateway's per-call cap. */
    private const val UPLOAD_BATCH = 500
}
