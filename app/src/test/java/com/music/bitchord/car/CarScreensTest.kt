package com.music.bitchord.car

import android.os.Looper
import androidx.car.app.Screen
import androidx.car.app.model.Template
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.data.listentogether.KIND_CONNECT
import com.music.bitchord.data.listentogether.KIND_JAM
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyMember
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Every Android Auto screen, built the way the car asks for it. The Car App
 * Library checks a template's rules when it is built (which buttons may float,
 * which need a colour, which must be parked-only, how many), and a breach
 * throws, which in a car is the app crashing as it opens. Here it fails a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarScreensTest {

    private lateinit var car: TestCarContext
    private val media = FakeSource()

    @Before
    fun setUp() {
        car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        ListenTogether.setStateForTest(ListenTogether.State())
    }

    @After
    fun tearDown() {
        ListenTogether.setStateForTest(ListenTogether.State())
    }

    private fun build(screen: Screen): Template {
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()
        return screen.onGetTemplate().also { assertNotNull(it) }
    }

    private fun homeTabs(): List<Template> {
        val home = CarHomeScreen(car, media)
        val templates = mutableListOf(build(home))
        listOf("library", "jam", "home").forEach { tab ->
            val field = CarHomeScreen::class.java.getDeclaredField("tab").apply { isAccessible = true }
            field.set(home, tab)
            templates += home.onGetTemplate()
        }
        return templates
    }

    @Test
    fun `home, library and the jam tab build with music in them`() {
        homeTabs()
    }

    @Test
    fun `a playlist with Play and Shuffle builds`() {
        build(CarCollectionScreen(car, media, folder("playlist:PL1", "Sunday drive")))
    }

    @Test
    fun `now playing and the queue build`() {
        build(CarNowPlayingScreen(car, media))
        build(CarQueueScreen(car, media))
    }

    @Test
    fun `in a jam, the jam tab, the jam screen and the QR screen build`() {
        ListenTogether.setStateForTest(
            ListenTogether.State(
                code = "K7QM2X",
                kind = KIND_JAM,
                you = member("me", host = true),
                members = listOf(member("me", host = true), member("r", name = "Rumbi")),
            ),
        )
        homeTabs()
        build(CarJamScreen(car, media))
        build(CarJamCodeScreen(car, media))
    }

    @Test
    fun `not in a jam, the jam screen and the QR screen build`() {
        build(CarJamScreen(car, media))
        build(CarJamCodeScreen(car, media))
    }

    @Test
    fun `playing on another device, now playing offers Play here, and the jam tab lists devices`() {
        val me = member("me", remote = true)
        ListenTogether.setStateForTest(
            ListenTogether.State(
                code = "~account",
                kind = KIND_CONNECT,
                you = me,
                members = listOf(me, member("tv", host = true, name = "Bedroom TV 2")),
            ),
        )
        build(CarNowPlayingScreen(car, media))
        homeTabs()
    }

    private fun member(id: String, host: Boolean = false, remote: Boolean = false, name: String = id) =
        PartyMember(
            memberId = id,
            displayName = name,
            isHost = host,
            connected = true,
            role = if (remote) PartyMember.ROLE_REMOTE else PartyMember.ROLE_SPEAKER,
            deviceKey = "key-$id",
            app = "prod",
            deviceName = name,
        )

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build(),
    ).build()

    private fun song(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist("Artist").setIsBrowsable(false).setIsPlayable(true).build(),
    ).build()

    /** The music service, with a little of everything in it. */
    private inner class FakeSource : CarSource {
        override val connected = true
        override val controller: Player? = null
        override fun listen(onChange: () -> Unit) = Unit
        override fun unlisten(onChange: () -> Unit) = Unit
        override suspend fun children(parentId: String, pageSize: Int): List<MediaItem> = when (parentId) {
            "playlists" -> (1..6).map { folder("playlist:PL$it", "Playlist $it") }
            else -> (1..12).map { song("song$it", "Song $it") } +
                // What the old browser tree also returns, which the car screens leave out.
                MediaItem.Builder().setMediaId("shuffle:$parentId").build()
        }
        override fun play(item: MediaItem) = Unit
        override fun playAll(folderId: String, shuffle: Boolean) = Unit
        override fun artwork(item: MediaItem) = null
    }
}
