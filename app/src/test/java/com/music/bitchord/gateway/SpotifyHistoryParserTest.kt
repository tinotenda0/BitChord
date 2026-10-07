package com.music.bitchord.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpotifyHistoryParserTest {

    private val extended = """
        [
          {"ts": "2024-03-01T18:00:42Z", "ms_played": 214000,
           "master_metadata_track_name": "Pompeii",
           "master_metadata_album_artist_name": "Bastille",
           "master_metadata_album_album_name": "Bad Blood",
           "spotify_track_uri": "spotify:track:abc"},
          {"ts": "2024-03-01T18:04:00Z", "ms_played": 9000,
           "master_metadata_track_name": "Skipped", "master_metadata_album_artist_name": "Someone"},
          {"ts": "2024-03-01T19:00:00Z", "ms_played": 1800000,
           "master_metadata_track_name": null, "episode_name": "A podcast"}
        ]
    """.trimIndent()

    private val basic = """
        [
          {"endTime": "2024-03-01 18:00", "artistName": "Bastille", "trackName": "Pompeii", "msPlayed": 214000},
          {"endTime": "2024-03-01 18:04", "artistName": "Someone", "trackName": "Skipped", "msPlayed": 9000}
        ]
    """.trimIndent()

    @Test
    fun theExtendedExportKeepsMusicStreamsOnly() {
        val streams = SpotifyHistoryParser.parse(extended)
        assertEquals(1, streams.size)
        val s = streams.single()
        assertEquals(1_709_316_042_000L, s.endMs)
        assertEquals(214_000L, s.playedMs)
        assertEquals("Pompeii", s.track)
        assertEquals("Bastille", s.artist)
        assertEquals("Bad Blood", s.album)
    }

    @Test
    fun theBasicExportIsReadToTheMinuteInUtc() {
        val s = SpotifyHistoryParser.parse(basic).single()
        assertEquals(1_709_316_000_000L, s.endMs)
        assertEquals("Pompeii", s.track)
    }

    @Test
    fun aZipIsReadForItsMusicFilesAlone() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json"))
                z.write(extended.toByteArray())
                z.putNextEntry(ZipEntry("Spotify Extended Streaming History/Streaming_History_Video_2024.json"))
                z.write(extended.toByteArray())
                z.putNextEntry(ZipEntry("Spotify Account Data/StreamingHistory_music_0.json"))
                z.write(basic.toByteArray())
                z.putNextEntry(ZipEntry("Spotify Account Data/StreamingHistory_podcast_0.json"))
                z.write(basic.toByteArray())
            }
        }.toByteArray()
        val streams = SpotifyHistoryParser.read("my_spotify_data.zip", zip.inputStream())
        assertEquals(2, streams.size)
    }

    @Test
    fun aLooseJsonFileIsReadToo() {
        assertEquals(1, SpotifyHistoryParser.read("x.json", basic.byteInputStream()).size)
    }

    @Test
    fun anythingElseReadsAsNothing() {
        assertEquals(0, SpotifyHistoryParser.parse("{\"not\": \"an array\"}").size)
        assertEquals(0, SpotifyHistoryParser.parse("garbage").size)
    }

    @Test
    fun onlyMusicHistoryFilesAreTaken() {
        assertTrue(SpotifyHistoryParser.isMusicHistory("a/Streaming_History_Audio_2019-2020_0.json"))
        assertTrue(SpotifyHistoryParser.isMusicHistory("StreamingHistory0.json"))
        assertTrue(SpotifyHistoryParser.isMusicHistory("StreamingHistory_music_1.json"))
        assertFalse(SpotifyHistoryParser.isMusicHistory("StreamingHistory_podcast_0.json"))
        assertFalse(SpotifyHistoryParser.isMusicHistory("Streaming_History_Video_2024.json"))
        assertFalse(SpotifyHistoryParser.isMusicHistory("Userdata.json"))
    }

    @Test
    fun aSongIsKeyedOnItsNamesWhateverTheirCaseAndSpacing() {
        assertEquals(SpotifyImport.keyOf("Pompeii", "Bastille"), SpotifyImport.keyOf(" POMPEII ", "bastille"))
    }
}
