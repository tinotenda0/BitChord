package com.music.bitchord.gateway

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.NameEntry
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * The Replay, drawn from the gateway's listening log instead of this device's
 * own monthly files — so it shows everything played in PixelPlayer as well as
 * here, under whichever gateway account is signed in.
 *
 * The log is fetched once in full and then topped up: each refresh asks only
 * for events that ended in the last day, which covers anything another device
 * reported late. It is kept in a file so the page opens on what was last known
 * rather than on a spinner. Events still waiting in [GatewayListening]'s outbox
 * are counted too, so a play shows up before the gateway has confirmed it.
 *
 * The events are folded into the same [StoredBucket] months the local Replay is
 * built from, and handed to [ListeningStats.summaryOf], so every chart, story
 * and share card is computed exactly as upstream computes it. Two definitions
 * follow PixelPlayer rather than BitChord, because that is what the history was
 * recorded under: every event is one play, and its listened time is its minutes.
 */
object GatewayStats {

    @Serializable
    private data class Cache(val user: String, val events: List<ListeningEvent>)

    private lateinit var cacheFile: File
    private val lock = Mutex()
    private var events: List<ListeningEvent>? = null
    private var cachedUser: String = ""
    private var fetchedAt = 0L

    /** Bumped whenever the event set changes, so a computed summary knows it is stale. */
    @Volatile private var version = 0L

    private class Memo(
        val period: ReplayPeriod,
        val version: Long,
        val pending: Int,
        val facts: Int,
        val day: LocalDate,
        val summary: ReplaySummary,
    )

    private var memo: Memo? = null

    fun init(context: Context) {
        cacheFile = File(context.filesDir, CACHE_FILE)
    }

    /** Forces the next read to ask the gateway, e.g. after this device reported a play. */
    fun invalidate() {
        fetchedAt = 0L
    }

    /** The Replay for [period]: from the gateway when signed in, from this device otherwise. */
    suspend fun replaySummary(period: ReplayPeriod): ReplaySummary =
        if (Gateway.signedIn) summary(period) else ListeningStats.summary(period)

    /**
     * The Replay's "member since": when signed in, the month the gateway account was made;
     * failing that, the first month anything was listened to.
     */
    suspend fun replayFirstMonth(): YearMonth? =
        if (Gateway.signedIn) {
            val since = runCatching { Gateway.memberSince() }.getOrNull()
                ?: allEvents().minOfOrNull { it.startTime }
            since?.let { YearMonth.from(local(it)) }
        } else {
            withContext(Dispatchers.IO) { ListeningStats.months().firstOrNull() }
        }

    private suspend fun summary(period: ReplayPeriod): ReplaySummary = withContext(Dispatchers.Default) {
        val all = allEvents()
        val pending = GatewayListening.pending.value.size
        val today = LocalDate.now()
        val facts = ArtistFacts.revision.value
        memo?.takeIf {
            it.period == period && it.version == version && it.pending == pending &&
                it.facts == facts && it.day == today
        }?.let { return@withContext it.summary }

        val zone = ZoneId.systemDefault()
        val buckets = gatewayBuckets(
            all.filter { period.covers(YearMonth.from(Instant.ofEpochMilli(it.startTime).atZone(zone)), today) },
            zone,
        )
        ListeningStats.summaryOf(buckets, period, today).also {
            memo = Memo(period, version, pending, facts, today, it)
        }
    }

    /** Confirmed events plus anything still queued on this device, once each. */
    private suspend fun allEvents(): List<ListeningEvent> {
        val confirmed = refreshed()
        val pending = GatewayListening.pending.value
        if (pending.isEmpty()) return confirmed
        val ids = confirmed.mapTo(HashSet()) { it.eventId }
        return confirmed + pending.filterNot { it.eventId in ids }
    }

    private suspend fun refreshed(): List<ListeningEvent> = lock.withLock {
        withContext(Dispatchers.IO) {
            val user = Gateway.username.value
            if (events == null || cachedUser != user) {
                events = readCache(user).orEmpty()
                cachedUser = user
                fetchedAt = 0L
                version++
            }
            val now = SystemClock.elapsedRealtime()
            if (fetchedAt != 0L && now - fetchedAt < REFRESH_MS) return@withContext events!!
            val known = events!!
            val fetched = if (known.isEmpty()) fetchAll() else fetchSince(known.maxOf { it.endTime } - OVERLAP_MS)
            if (fetched != null) {
                fetchedAt = now
                val merged = LinkedHashMap<String, ListeningEvent>()
                known.forEach { merged[it.eventId] = it }
                fetched.forEach { merged[it.eventId] = it }
                if (merged.size != known.size) {
                    events = merged.values.toList()
                    version++
                    writeCache(user, events!!)
                }
            }
            events!!
        }
    }

    /**
     * The whole log, newest first in pages of [PAGE]. The gateway filters a page's
     * upper bound on start time, so each page asks for everything that started no
     * later than the oldest event already seen; the overlap is dropped by id.
     */
    private suspend fun fetchAll(): List<ListeningEvent>? {
        val all = LinkedHashMap<String, ListeningEvent>()
        var before: Long? = null
        while (true) {
            val params = buildList {
                add("limit" to PAGE.toString())
                before?.let { add("endTime" to it.toString()) }
            }
            val (page, truncated) = fetch(params) ?: return null
            val fresh = page.filter { all.putIfAbsent(it.eventId, it) == null }
            if (!truncated || fresh.isEmpty()) break
            before = page.minOf { it.startTime }
        }
        return all.values.toList()
    }

    private suspend fun fetchSince(since: Long): List<ListeningEvent>? =
        fetch(listOf("startTime" to since.toString(), "limit" to PAGE.toString()))?.first

    private suspend fun fetch(params: List<Pair<String, String>>): Pair<List<ListeningEvent>, Boolean>? {
        val root = Gateway.call("getListeningEvents", params)
            .onFailure { Log.w(TAG, "Could not fetch listening history: ${it.message}") }
            .getOrNull() ?: return null
        val payload = root["listeningEvents"]?.jsonObject ?: return emptyList<ListeningEvent>() to false
        val list = (payload["event"] as? JsonArray).orEmpty().mapNotNull { parse(it.jsonObject) }
        val truncated = payload["truncated"]?.jsonPrimitive?.booleanOrNull == true
        return list to truncated
    }

    private fun parse(obj: JsonObject): ListeningEvent? {
        fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun number(key: String) = obj[key]?.jsonPrimitive?.longOrNull ?: 0L
        val videoId = Gateway.videoId(text("songId")) ?: return null
        val eventId = text("eventId").ifEmpty { return null }
        return ListeningEvent(
            eventId = eventId,
            videoId = videoId,
            title = text("title"),
            artist = text("artist"),
            album = text("album"),
            cover = text("cover"),
            durationMs = number("durationMs"),
            startTime = number("startTime"),
            endTime = number("endTime"),
        )
    }

    private fun local(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())

    private fun readCache(user: String): List<ListeningEvent>? {
        if (!this::cacheFile.isInitialized || !cacheFile.exists()) return null
        return runCatching { Gateway.json.decodeFromString(Cache.serializer(), cacheFile.readText()) }
            .getOrNull()?.takeIf { it.user == user }?.events
    }

    private fun writeCache(user: String, events: List<ListeningEvent>) {
        if (!this::cacheFile.isInitialized) return
        runCatching {
            val temporary = File(cacheFile.parentFile, "$CACHE_FILE.tmp")
            temporary.writeText(Gateway.json.encodeToString(Cache.serializer(), Cache(user, events)))
            if (!temporary.renameTo(cacheFile)) {
                cacheFile.delete()
                temporary.renameTo(cacheFile)
            }
        }.onFailure { Log.w(TAG, "Could not cache listening history", it) }
    }

    private const val TAG = "GatewayStats"
    private const val CACHE_FILE = "gateway_listening_history.json"

    /** The gateway's own per-response cap. */
    private const val PAGE = 20_000

    /** How often opening the Replay asks the gateway for anything new. */
    private const val REFRESH_MS = 60_000L

    /** Re-asks for a day back, to catch events another device reported late. */
    private const val OVERLAP_MS = 24 * 60 * 60 * 1000L
}

/**
 * Gateway listening events folded into the calendar months, in [zone], that the
 * local Replay stores — one [StoredBucket] per month that has any.
 */
internal fun gatewayBuckets(events: List<ListeningEvent>, zone: ZoneId): List<StoredBucket> =
    events.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startTime).atZone(zone)) }
        .map { (month, monthEvents) -> bucket(month, monthEvents, zone) }

/** One month of events, in the shape the local Replay stores a month. */
private fun bucket(month: YearMonth, monthEvents: List<ListeningEvent>, zone: ZoneId): StoredBucket {
    val tracks = LinkedHashMap<String, TrackEntry>()
    val artists = LinkedHashMap<String, NameEntry>()
    val albums = LinkedHashMap<String, NameEntry>()
    val hours = LongArray(24)
    val days = HashMap<Int, Long>()
    for (event in monthEvents) {
        val ms = event.durationMs.coerceAtLeast(0L)
        val art = artwork(event)
        val album = event.album.trim().takeIf { it.isNotEmpty() && it != GATEWAY_PLACEHOLDER_ALBUM }
        val track = tracks.getOrPut(event.videoId) {
            TrackEntry(id = event.videoId, title = event.title, artist = event.artist, album = album, art = art)
        }
        track.ms += ms
        track.plays++
        track.last = maxOf(track.last, event.endTime)
        if (track.album == null) track.album = album

        // Not passed to ArtistFacts.noticed here: years of history name
        // hundreds of artists, and the summary already asks about the top
        // of the chart, which is all the page shows.
        ListeningStats.primaryArtist(event.artist)?.let { name ->
            val entry = artists.getOrPut(name.lowercase(Locale.ROOT)) { NameEntry(name = name, art = art) }
            entry.ms += ms
            entry.plays++
        }
        album?.let { name ->
            val entry = albums.getOrPut(name.lowercase(Locale.ROOT) + "\u001f" + event.artist.lowercase(Locale.ROOT)) {
                NameEntry(name = name, sub = event.artist, art = art)
            }
            entry.ms += ms
            entry.plays++
        }

        val at = Instant.ofEpochMilli(event.startTime).atZone(zone)
        hours[at.hour] += ms
        days[at.dayOfMonth] = (days[at.dayOfMonth] ?: 0L) + ms
    }
    return StoredBucket(
        month = month.toString(),
        tracks = tracks.values.toList(),
        artists = artists.values.toList(),
        albums = albums.values.toList(),
        hours = hours.toList(),
        days = days,
    )
}

/**
 * The event's own cover when it is a web address. PixelPlayer sometimes sent
 * something only it could open, and YouTube serves a thumbnail for every
 * video id, so that is the fallback.
 */
private fun artwork(event: ListeningEvent): String =
    event.cover.takeIf { it.startsWith("http") } ?: "https://i.ytimg.com/vi/${event.videoId}/hqdefault.jpg"

/** What the gateway files a track under when YouTube gave it no album. */
private const val GATEWAY_PLACEHOLDER_ALBUM = "YouTube"
