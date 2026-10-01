package com.music.bitchord.gateway

import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The gateway's listening log has to come out of the Replay with PixelPlayer's
 * numbers: one event is one play, and its listened time is its minutes.
 */
class GatewayBucketsTest {

    private val zone = ZoneId.of("Europe/London")

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun event(
        id: String,
        videoId: String,
        start: Long,
        listenedMs: Long,
        artist: String = "Bastille",
        album: String = "Bad Blood",
        cover: String = "https://lh3.googleusercontent.com/$videoId",
    ) = ListeningEvent(
        eventId = id,
        videoId = videoId,
        title = "Song $videoId",
        artist = artist,
        album = album,
        cover = cover,
        durationMs = listenedMs,
        startTime = start,
        endTime = start + listenedMs,
    )

    @Test
    fun eachEventIsOnePlay_andListenedTimeIsTheMinutes() {
        val events = listOf(
            event("1", "aaaaaaaaaaa", at(2026, 9, 3, 20), 180_000),
            event("2", "aaaaaaaaaaa", at(2026, 9, 4, 21), 7_000),
            event("3", "bbbbbbbbbbb", at(2026, 9, 4, 21), 200_000, artist = "Bastille, Alessia Cara"),
        )
        val buckets = gatewayBuckets(events, zone)
        assertEquals(listOf("2026-09"), buckets.map { it.month })

        val summary = ListeningStats.summaryOf(buckets, ReplayPeriod.ALL_TIME, LocalDate.of(2026, 10, 1))
        assertEquals(3, summary.totalPlays)
        assertEquals(387_000L, summary.totalMs)
        assertEquals(2, summary.songs.single { it.song.videoId == "aaaaaaaaaaa" }.plays)
        // A collaboration is filed under its lead artist, as the local Replay does.
        assertEquals(listOf("Bastille"), summary.artists.map { it.title })
        assertEquals(3, summary.artists.single().plays)
    }

    @Test
    fun monthsHoursAndDaysFollowTheGivenZone() {
        val buckets = gatewayBuckets(
            listOf(
                event("1", "aaaaaaaaaaa", at(2026, 8, 31, 23), 60_000),
                event("2", "aaaaaaaaaaa", at(2026, 9, 1, 0), 60_000),
            ),
            zone,
        ).associateBy { it.month }
        assertEquals(60_000L, buckets.getValue("2026-08").hours[23])
        assertEquals(60_000L, buckets.getValue("2026-08").days[31])
        assertEquals(60_000L, buckets.getValue("2026-09").hours[0])
        assertEquals(60_000L, buckets.getValue("2026-09").days[1])
    }

    @Test
    fun theGatewaysPlaceholderAlbumIsNotAnAlbum() {
        val bucket = gatewayBuckets(
            listOf(event("1", "aaaaaaaaaaa", at(2026, 9, 3, 20), 60_000, album = "YouTube")),
            zone,
        ).single()
        assertEquals(emptyList<Any>(), bucket.albums)
        assertNull(bucket.tracks.single().album)
    }

    @Test
    fun aCoverOnlyPixelPlayerCouldOpenFallsBackToTheVideoThumbnail() {
        val bucket = gatewayBuckets(
            listOf(event("1", "aaaaaaaaaaa", at(2026, 9, 3, 20), 60_000, cover = "content://media/42")),
            zone,
        ).single()
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", bucket.tracks.single().art)
    }
}
