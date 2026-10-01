package com.music.bitchord.gateway

import android.content.Context
import android.util.Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

/** One finished listen, in the shape the gateway's `listening_events` table stores. */
@Serializable
data class ListeningEvent(
    val eventId: String,
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    /** How long was actually listened to — not the track's length. */
    val durationMs: Long,
    val startTime: Long,
    val endTime: Long,
    /** The track's own length, so the gateway can tell a skip from a finish. 0 when unknown. */
    val trackDurationMs: Long = 0L,
)

/**
 * Reports listening to the gateway exactly the way PixelPlayer did, so the
 * history each of you built up there carries straight on.
 *
 * PixelPlayer's rules, kept here unchanged because the Replay is now drawn from
 * that same log and mixing two definitions would skew it:
 *  - one event per stretch of one track, closed when the track changes or
 *    playback stops;
 *  - only stretches of at least [MIN_LISTEN_MS] count;
 *  - `durationMs` is listened time, and the stretch is stamped as ending at the
 *    last moment audio was heard, starting that long before.
 *
 * Listened time comes from the playback service's own sampler, which only ticks
 * while audio is coming out (see [com.music.bitchord.data.stats.ListeningRecorder]);
 * the gap between ticks is capped so a long pause never arrives as listening.
 *
 * Each event goes out live through `reportListeningEvent` — the call the
 * Surprise Me DJ learns skips from — and anything that cannot be sent waits in
 * an outbox file and is drained in bulk later.
 */
object GatewayListening {

    /** The stretch of one track being listened to now. */
    @Serializable
    private data class Session(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val trackDurationMs: Long,
        val listenedMs: Long = 0L,
        val lastSampleAt: Long,
        val paused: Boolean = false,
    )

    private var session: Session? = null

    private lateinit var outboxFile: File
    private lateinit var sessionFile: File
    private val outboxLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainLock = Mutex()

    /** One at a time and in order, so a save and the delete after it never swap places. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _pending = MutableStateFlow<List<ListeningEvent>>(emptyList())

    /** Events not yet confirmed by the gateway, so the Replay can count them already. */
    val pending: StateFlow<List<ListeningEvent>> = _pending.asStateFlow()

    /** For redrawing the stats widget once a listen is in. */
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        outboxFile = File(context.filesDir, OUTBOX_FILE)
        sessionFile = File(context.filesDir, SESSION_FILE)
        // A stretch left paused by a process that has since died will never be
        // resumed, so it is finished now. Queued first on [sessionScope], so it
        // is read before anything this process saves.
        sessionScope.launch {
            readSession()?.let { recovered -> synchronized(this@GatewayListening) { finish(recovered) } }
        }
        scope.launch {
            outboxLock.withLock { _pending.value = readOutbox() }
            drain()
        }
    }

    private val ready: Boolean get() = this::outboxFile.isInitialized

    /** Called on every playback sample, while audio is actually playing. */
    @Synchronized
    fun onSample(song: Song, durationMs: Long) {
        if (!ready || !Gateway.signedIn) return
        val now = System.currentTimeMillis()
        val current = session
        if (current == null || current.videoId != song.videoId) {
            current?.let(::finish)
            // A new stretch anchors the clock and contributes nothing yet, as
            // ListeningRecorder does: the time before the first tick is unknown.
            session = Session(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName.orEmpty(),
                cover = song.thumbnailUrl.orEmpty(),
                trackDurationMs = durationMs.coerceAtLeast(0L),
                lastSampleAt = now,
            )
            return
        }
        session = if (current.paused) {
            // Resuming the same track carries on the same stretch, as PixelPlayer
            // did; the time spent paused is not listening, so the clock re-anchors.
            current.copy(paused = false, lastSampleAt = now)
        } else {
            val step = (now - current.lastSampleAt).coerceIn(0L, MAX_STEP_MS)
            current.copy(
                listenedMs = current.listenedMs + step,
                lastSampleAt = now,
                // The album often arrives after the track starts playing.
                album = current.album.ifEmpty { song.albumName.orEmpty() },
            )
        }
    }

    /**
     * Playback paused or stopped. Not the end of the stretch — a pause and resume
     * is one listen — but it is saved, so a process killed while paused does not
     * lose it; [init] finishes it on the next launch.
     */
    @Synchronized
    fun onStopped() {
        val current = session?.takeIf { !it.paused } ?: return
        val paused = current.copy(paused = true)
        session = paused
        sessionScope.launch { writeSession(paused) }
    }

    private fun finish(finished: Session) {
        if (session === finished) session = null
        sessionScope.launch { sessionFile.delete() }
        if (finished.listenedMs < MIN_LISTEN_MS) return
        val end = finished.lastSampleAt
        val event = ListeningEvent(
            eventId = UUID.randomUUID().toString(),
            videoId = finished.videoId,
            title = finished.title,
            artist = finished.artist,
            album = finished.album,
            cover = finished.cover,
            durationMs = finished.listenedMs,
            startTime = end - finished.listenedMs,
            endTime = end,
            trackDurationMs = finished.trackDurationMs,
        )
        scope.launch {
            outboxLock.withLock { writeOutbox(readOutbox() + event) }
            ListeningStatsWidget.refresh(appContext)
            drain()
        }
    }

    /**
     * Sends whatever is waiting. A recent listen goes out live, which is what
     * feeds the DJ its skip signal; a backlog from a long time offline goes in
     * bulk, which the gateway deliberately keeps away from the DJ because the
     * session it would steer is long over. If the gateway cannot be reached the
     * rest stay queued for the next attempt rather than being retried in a loop.
     */
    fun drain() {
        if (!ready) return
        scope.launch {
            if (!Gateway.signedIn || !drainLock.tryLock()) return@launch
            try {
                val queued = outboxLock.withLock { readOutbox() }
                val staleBefore = System.currentTimeMillis() - LIVE_WINDOW_MS
                val (stale, recent) = queued.partition { it.endTime < staleBefore }
                val sent = mutableSetOf<String>()
                for (batch in stale.chunked(IMPORT_BATCH)) {
                    if (import(batch)) sent += batch.map { it.eventId } else break
                }
                for (event in recent) {
                    if (report(event)) sent += event.eventId else break
                }
                if (sent.isNotEmpty()) {
                    outboxLock.withLock { writeOutbox(readOutbox().filterNot { it.eventId in sent }) }
                    GatewayStats.invalidate()
                    ListeningStatsWidget.refresh(appContext)
                }
            } finally {
                drainLock.unlock()
            }
        }
    }

    private suspend fun report(event: ListeningEvent): Boolean =
        Gateway.call(
            "reportListeningEvent",
            listOf(
                "eventId" to event.eventId,
                "songId" to Gateway.songId(event.videoId),
                "title" to event.title,
                "artist" to event.artist,
                "album" to event.album,
                "cover" to event.cover,
                "durationMs" to event.durationMs.toString(),
                "startTime" to event.startTime.toString(),
                "endTime" to event.endTime.toString(),
                "trackDurationMs" to event.trackDurationMs.toString(),
            ),
        ).onFailure { Log.w(TAG, "Listening event not sent yet: ${it.message}") }.isSuccess

    /** Bulk upload for a backlog: `importListeningEvents` takes a JSON array body. */
    private suspend fun import(events: List<ListeningEvent>): Boolean {
        val body = Gateway.json.encodeToString(
            ListSerializer(ImportEvent.serializer()),
            events.map { ImportEvent(it.eventId, Gateway.songId(it.videoId), it) },
        ).toRequestBody("application/json".toMediaType())
        return Gateway.call("importListeningEvents", body = body).isSuccess
    }

    @Serializable
    private data class ImportEvent(
        val eventId: String,
        val songId: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val durationMs: Long,
        val startTime: Long,
        val endTime: Long,
    ) {
        constructor(id: String, songId: String, e: ListeningEvent) :
            this(id, songId, e.title, e.artist, e.album, e.cover, e.durationMs, e.startTime, e.endTime)
    }

    private fun readOutbox(): List<ListeningEvent> {
        if (!outboxFile.exists()) return emptyList()
        return runCatching {
            Gateway.json.decodeFromString(ListSerializer(ListeningEvent.serializer()), outboxFile.readText())
        }.onFailure { Log.w(TAG, "Discarding unreadable listening outbox", it) }.getOrDefault(emptyList())
    }

    /** Written aside and renamed, so a kill mid-write keeps the previous outbox. */
    private fun writeOutbox(events: List<ListeningEvent>) {
        // An outage long enough to fill this should not grow it forever; the
        // oldest go first, as in PixelPlayer's outbox.
        val kept = events.takeLast(MAX_QUEUED)
        runCatching {
            val temporary = File(outboxFile.parentFile, "$OUTBOX_FILE.tmp")
            temporary.writeText(Gateway.json.encodeToString(ListSerializer(ListeningEvent.serializer()), kept))
            if (!temporary.renameTo(outboxFile)) {
                outboxFile.delete()
                temporary.renameTo(outboxFile)
            }
        }.onFailure { Log.w(TAG, "Could not write listening outbox", it) }
        _pending.value = kept
    }

    private fun readSession(): Session? =
        if (!sessionFile.exists()) null
        else runCatching { Gateway.json.decodeFromString(Session.serializer(), sessionFile.readText()) }.getOrNull()

    private fun writeSession(value: Session) {
        runCatching { sessionFile.writeText(Gateway.json.encodeToString(Session.serializer(), value)) }
            .onFailure { Log.w(TAG, "Could not save the paused listen", it) }
    }

    private const val TAG = "GatewayListening"
    private const val OUTBOX_FILE = "gateway_listening_outbox.json"
    private const val SESSION_FILE = "gateway_listening_session.json"

    /** PixelPlayer's threshold for a stretch worth recording. */
    private const val MIN_LISTEN_MS = 5_000L

    /** Same cap as ListeningRecorder: a gap longer than this between ticks was not listening. */
    private const val MAX_STEP_MS = 8_000L

    private const val MAX_QUEUED = 2_000

    /** Listens older than this are history to the DJ, not a reaction to steer by. */
    private const val LIVE_WINDOW_MS = 30 * 60 * 1000L

    /** The gateway's per-call cap on `importListeningEvents`. */
    private const val IMPORT_BATCH = 500
}
