package com.music.bitchord.car

import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.signin.QRCodeSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.JamInviteLink
import com.music.bitchord.data.listentogether.ListenTogether
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Fork: BitChord's Android Auto screens, on the Car App Library templates.
 * The car draws every template in its own style; these choose the structure:
 * which sections, rows, grids and buttons, and what they do.
 */

private fun CarContext.icon(res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(this, res)).build()

private fun CarContext.action(title: Int, icon: Int, primary: Boolean = false, onClick: () -> Unit): Action =
    Action.Builder()
        .setTitle(getString(title))
        .setIcon(icon(icon))
        .apply { if (primary) setFlags(Action.FLAG_PRIMARY).setBackgroundColor(CarColor.PRIMARY) }
        .setOnClickListener(onClick)
        .build()

/** Media items a car list can show: songs and folders, not the old browser's Shuffle and message rows. */
private fun List<MediaItem>.shown(): List<MediaItem> =
    filterNot { it.mediaId.startsWith("shuffle:") || it.mediaId.startsWith("msg:") }

/** Shared by the screens: a screen that redraws when the music service or Connect/jam state changes. */
abstract class CarScreen(carContext: CarContext, protected val media: CarMedia) : Screen(carContext) {
    private val redraw: () -> Unit = { onMediaChanged() }

    init {
        media.listen(redraw)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = media.unlisten(redraw)
        })
        lifecycleScope.launch {
            ListenTogether.state
                .map { s -> listOf(s.inJam, s.code, s.isConnect, s.isRemote, s.output?.memberId, s.members.map { it.memberId to it.connected }, s.devices.map { it.deviceId to it.connected }) }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    protected open fun onMediaChanged() = invalidate()

    /** The minimised player, as our own action: the standard one is not routed back to this app. */
    protected fun nowPlayingAction(): Action =
        Action.Builder()
            .setIcon(carContext.icon(R.drawable.ic_car_now_playing))
            .setOnClickListener { openNowPlaying() }
            .build()

    protected fun openNowPlaying() {
        if (screenManager.top !is CarNowPlayingScreen) screenManager.push(CarNowPlayingScreen(carContext, media))
    }

    protected fun songRow(item: MediaItem, onClick: () -> Unit): Row =
        Row.Builder()
            .setTitle(item.mediaMetadata.title ?: "")
            .apply { (item.mediaMetadata.artist ?: item.mediaMetadata.subtitle)?.let { addText(it) } }
            .setImage(media.artwork(item) ?: carContext.icon(R.drawable.ic_car_music), Row.IMAGE_TYPE_LARGE)
            .setOnClickListener(onClick)
            .build()

    protected fun tile(item: MediaItem, onClick: () -> Unit): GridItem =
        GridItem.Builder()
            .setTitle(item.mediaMetadata.title ?: "")
            .apply { (item.mediaMetadata.artist ?: item.mediaMetadata.subtitle)?.let { setText(it) } }
            .setImage(media.artwork(item) ?: carContext.icon(R.drawable.ic_car_music))
            .setOnClickListener(onClick)
            .build()

    /** Opens a folder (a playlist, an album) on its own screen, or plays a song and shows it. */
    protected fun open(item: MediaItem) {
        if (item.mediaMetadata.isBrowsable == true) {
            screenManager.push(CarCollectionScreen(carContext, media, item))
        } else {
            media.play(item)
            openNowPlaying()
        }
    }
}

/** Home, Library and Jam, as tabs. */
class CarHomeScreen(carContext: CarContext, media: CarMedia) : CarScreen(carContext, media) {

    private var tab = TAB_HOME
    private var recents: List<MediaItem>? = null
    private var quickPicks: List<MediaItem>? = null
    private var playlists: List<MediaItem>? = null

    init {
        if (media.connected) load()
    }

    override fun onMediaChanged() {
        if (quickPicks == null && media.connected) load()
        invalidate()
    }

    private fun load() {
        lifecycleScope.launch {
            recents = media.children(FOLDER_RECENTS).shown()
            quickPicks = media.children(FOLDER_QUICK_PICKS).shown()
            playlists = media.children(FOLDER_PLAYLISTS).shown()
            invalidate()
        }
    }

    override fun onGetTemplate(): Template =
        TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                tab = tabContentId
                invalidate()
            }
        })
            .setHeaderAction(Action.APP_ICON)
            .addTab(tabOf(TAB_HOME, R.string.car_tab_home, R.drawable.ic_car_home))
            .addTab(tabOf(TAB_LIBRARY, R.string.car_tab_library, R.drawable.ic_car_library))
            .addTab(tabOf(TAB_JAM, jamTitle(), R.drawable.ic_car_jam))
            .setTabContents(TabContents.Builder(contentFor(tab)).build())
            .setActiveTabContentId(tab)
            .build()

    private fun jamTitle(): String {
        val s = ListenTogether.state.value
        val people = s.members.count { it.connected }
        return if (s.inJam && people > 1) carContext.getString(R.string.car_tab_jam_count, people) else carContext.getString(R.string.auto_jam)
    }

    private fun tabOf(id: String, title: Int, icon: Int) = tabOf(id, carContext.getString(title), icon)

    private fun tabOf(id: String, title: String, icon: Int): Tab =
        Tab.Builder().setContentId(id).setTitle(title).setIcon(carContext.icon(icon)).build()

    private fun contentFor(id: String): Template = when (id) {
        TAB_LIBRARY -> library()
        TAB_JAM -> jam()
        else -> home()
    }

    private fun home(): Template {
        val recent = recents
        val picks = quickPicks
        if (recent == null || picks == null) return SectionedItemTemplate.Builder().setLoading(true).build()
        val template = SectionedItemTemplate.Builder()
        if (recent.isNotEmpty()) {
            template.addSection(
                GridSection.Builder()
                    .setTitle(carContext.getString(R.string.car_jump_back_in))
                    .setItemSize(GridSection.ITEM_SIZE_LARGE)
                    .setItems(recent.take(10).map { item -> tile(item) { open(item) } })
                    .build(),
            )
        }
        template.addSection(
            RowSection.Builder()
                .setTitle(carContext.getString(R.string.auto_quick_picks))
                .setNoItemsMessage(carContext.getString(R.string.car_nothing_here))
                .setItems(picks.take(20).map { item -> songRow(item) { open(item) } })
                .build(),
        )
        return template.addAction(nowPlayingAction()).build()
    }

    private fun library(): Template {
        val lists = playlists ?: return SectionedItemTemplate.Builder().setLoading(true).build()
        return SectionedItemTemplate.Builder()
            .addSection(
                GridSection.Builder()
                    .setTitle(carContext.getString(R.string.playlists))
                    .setItemSize(GridSection.ITEM_SIZE_LARGE)
                    .setNoItemsMessage(carContext.getString(R.string.car_nothing_here))
                    .setItems(lists.take(40).map { item -> tile(item) { open(item) } })
                    .build(),
            )
            .addSection(
                RowSection.Builder()
                    .addItem(
                        Row.Builder()
                            .setTitle(carContext.getString(R.string.auto_liked))
                            .setImage(carContext.icon(R.drawable.ic_car_heart), Row.IMAGE_TYPE_ICON)
                            .setBrowsable(true)
                            .setOnClickListener { open(folder(FOLDER_LIKED, carContext.getString(R.string.auto_liked))) }
                            .build(),
                    )
                    .addItem(
                        Row.Builder()
                            .setTitle(carContext.getString(R.string.downloads))
                            .setImage(carContext.icon(R.drawable.ic_car_download), Row.IMAGE_TYPE_ICON)
                            .setBrowsable(true)
                            .setOnClickListener { open(folder(FOLDER_DOWNLOADS, carContext.getString(R.string.downloads))) }
                            .build(),
                    )
                    .build(),
            )
            .addAction(nowPlayingAction())
            .build()
    }

    private fun jam(): Template {
        val s = ListenTogether.state.value
        val code = s.code
        if (s.inJam && code != null) {
            val people = s.members.filter { it.connected }
            return SectionedItemTemplate.Builder()
                .addSection(
                    RowSection.Builder()
                        .addItem(
                            Row.Builder()
                                .setTitle(carContext.getString(R.string.auto_jam_code, code))
                                .addText(carContext.getString(R.string.car_jam_join_at))
                                .setImage(carContext.icon(R.drawable.ic_car_qr), Row.IMAGE_TYPE_ICON)
                                .setOnClickListener { screenManager.push(CarJamCodeScreen(carContext, media)) }
                                .build(),
                        )
                        .build(),
                )
                .addSection(
                    RowSection.Builder()
                        .setTitle(carContext.getString(R.string.car_in_the_jam))
                        .setItems(
                            people.map { m ->
                                Row.Builder()
                                    .setTitle(m.displayName.ifBlank { "?" })
                                    .addText(carContext.getString(if (m.isHost) R.string.car_jam_host else if (m.isRemote) R.string.car_jam_remote else R.string.car_jam_listening))
                                    .setImage(carContext.icon(R.drawable.ic_car_person), Row.IMAGE_TYPE_ICON)
                                    .build()
                            },
                        )
                        .build(),
                )
                .addAction(carContext.action(R.string.car_show_qr, R.drawable.ic_car_qr, primary = true) { screenManager.push(CarJamCodeScreen(carContext, media)) })
                .addAction(carContext.action(R.string.auto_jam_end, R.drawable.ic_car_close) { endJam() })
                .build()
        }
        val template = SectionedItemTemplate.Builder()
            .addSection(
                RowSection.Builder()
                    .addItem(
                        Row.Builder()
                            .setTitle(carContext.getString(R.string.car_jam_pitch_title))
                            .addText(carContext.getString(R.string.car_jam_pitch))
                            .setImage(carContext.icon(R.drawable.ic_car_jam), Row.IMAGE_TYPE_ICON)
                            .build(),
                    )
                    .build(),
            )
        val devices = connectDevices()
        if (devices.isNotEmpty()) {
            template.addSection(
                RowSection.Builder().setTitle(carContext.getString(R.string.car_or_your_devices)).setItems(devices).build(),
            )
        }
        return template
            .addAction(carContext.action(R.string.auto_jam_start, R.drawable.ic_car_jam, primary = true) { startJam() })
            .build()
    }

    /** The account's other devices, to play on: Connect's picker, as rows. */
    private fun connectDevices(): List<Row> {
        val s = ListenTogether.state.value
        if (!s.isConnect) return emptyList()
        val me = s.you
        val outputId = s.output?.memberId
        val rows = mutableListOf<Row>()
        s.members.filter { it.memberId != me?.memberId }.forEach { m ->
            val name = m.deviceName.ifBlank { m.displayName }
            val playing = m.memberId == outputId
            rows += Row.Builder()
                .setTitle(name)
                .addText(carContext.getString(if (playing) R.string.car_device_playing else if (m.connected) R.string.car_device_online else R.string.car_device_asleep))
                .setImage(carContext.icon(R.drawable.ic_car_devices), Row.IMAGE_TYPE_ICON)
                .apply { if (!playing && m.connected) setOnClickListener { ListenTogether.transfer(m.memberId) } }
                .build()
        }
        s.devices
            .filter { d -> s.members.none { it.deviceKey == d.deviceKey && it.app == d.app } }
            .filterNot { it.deviceKey == me?.deviceKey && it.app == me?.app }
            .forEach { d ->
                rows += Row.Builder()
                    .setTitle(d.deviceName)
                    .addText(carContext.getString(if (d.wakeable) R.string.car_device_asleep else R.string.car_device_unreachable))
                    .setImage(carContext.icon(R.drawable.ic_car_devices), Row.IMAGE_TYPE_ICON)
                    .apply { if (d.wakeable && d.status != "jam") setOnClickListener { ListenTogether.wake(d.deviceId) } }
                    .build()
            }
        return rows.take(6)
    }

    private fun startJam() {
        lifecycleScope.launch {
            ListenTogether.createParty().onSuccess { screenManager.push(CarJamCodeScreen(carContext, media)) }
        }
    }

    private fun endJam() {
        lifecycleScope.launch { ListenTogether.leaveParty() }
    }

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build(),
        ).build()

    private companion object {
        const val TAB_HOME = "home"
        const val TAB_LIBRARY = "library"
        const val TAB_JAM = "jam"
        const val FOLDER_RECENTS = "recents"
        const val FOLDER_QUICK_PICKS = "quick_picks"
        const val FOLDER_PLAYLISTS = "playlists"
        const val FOLDER_LIKED = "liked"
        const val FOLDER_DOWNLOADS = "downloads"
    }
}

/** A playlist or other folder of songs: its name, Play and Shuffle, then the songs. */
class CarCollectionScreen(carContext: CarContext, media: CarMedia, private val folder: MediaItem) : CarScreen(carContext, media) {

    private var songs: List<MediaItem>? = null

    init {
        lifecycleScope.launch {
            songs = media.children(folder.mediaId, pageSize = 100).shown().filter { it.mediaMetadata.isPlayable == true }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle(folder.mediaMetadata.title ?: "")
            .build()
        val list = songs ?: return SectionedItemTemplate.Builder().setHeader(header).setLoading(true).build()
        return SectionedItemTemplate.Builder()
            .setHeader(header)
            .addSection(
                RowSection.Builder()
                    .setNoItemsMessage(carContext.getString(R.string.car_nothing_here))
                    .setItems(list.take(100).map { item -> songRow(item) { media.play(item); openNowPlaying() } })
                    .build(),
            )
            .addAction(carContext.action(R.string.car_play, R.drawable.ic_car_play, primary = true) { media.playAll(folder.mediaId, shuffle = false); openNowPlaying() })
            .addAction(carContext.action(R.string.auto_shuffle, R.drawable.ic_car_shuffle) { media.playAll(folder.mediaId, shuffle = true); openNowPlaying() })
            .build()
    }
}

/** Now playing. The car draws the artwork, controls and progress from the session; the header is ours. */
class CarNowPlayingScreen(carContext: CarContext, media: CarMedia) : CarScreen(carContext, media) {
    override fun onGetTemplate(): Template {
        val s = ListenTogether.state.value
        val header = Header.Builder().setStartHeaderAction(Action.BACK)
        if (s.isConnect && s.isRemote) {
            val where = s.output?.let { it.deviceName.ifBlank { it.displayName } }.orEmpty()
            header.setTitle(carContext.getString(R.string.auto_playing_on, where))
            header.addEndHeaderAction(carContext.action(R.string.auto_play_here, R.drawable.ic_play_here, primary = true) { playHere() })
        } else {
            header.setTitle(carContext.getString(R.string.car_now_playing))
            header.addEndHeaderAction(carContext.action(R.string.auto_jam, R.drawable.ic_car_jam) { screenManager.push(CarJamScreen(carContext, media)) })
        }
        header.addEndHeaderAction(carContext.action(R.string.car_queue, R.drawable.ic_car_queue) { screenManager.push(CarQueueScreen(carContext, media)) })
        return MediaPlaybackTemplate.Builder().setHeader(header.build()).build()
    }

    private fun playHere() {
        val s = ListenTogether.state.value
        val me = s.you?.memberId ?: return
        if (s.isConnect && s.isRemote) ListenTogether.transfer(me)
    }
}

/** The jam from the player: the same as the Jam tab, on its own screen. */
class CarJamScreen(carContext: CarContext, media: CarMedia) : CarScreen(carContext, media) {
    override fun onGetTemplate(): Template {
        val s = ListenTogether.state.value
        val code = s.code
        val header = Header.Builder().setStartHeaderAction(Action.BACK).setTitle(carContext.getString(R.string.auto_jam)).build()
        if (s.inJam && code != null) {
            return SectionedItemTemplate.Builder()
                .setHeader(header)
                .addSection(
                    RowSection.Builder()
                        .setItems(listOf(Row.Builder().setTitle(carContext.getString(R.string.auto_jam_code, code)).addText(carContext.getString(R.string.car_jam_join_at)).build()) +
                            s.members.filter { it.connected }.map { Row.Builder().setTitle(it.displayName.ifBlank { "?" }).setImage(carContext.icon(R.drawable.ic_car_person), Row.IMAGE_TYPE_ICON).build() })
                        .build(),
                )
                .addAction(carContext.action(R.string.car_show_qr, R.drawable.ic_car_qr, primary = true) { screenManager.push(CarJamCodeScreen(carContext, media)) })
                .addAction(carContext.action(R.string.auto_jam_end, R.drawable.ic_car_close) { lifecycleScope.launch { ListenTogether.leaveParty() } })
                .build()
        }
        return SectionedItemTemplate.Builder()
            .setHeader(header)
            .addSection(
                RowSection.Builder()
                    .addItem(Row.Builder().setTitle(carContext.getString(R.string.car_jam_pitch_title)).addText(carContext.getString(R.string.car_jam_pitch)).build())
                    .build(),
            )
            .addAction(carContext.action(R.string.auto_jam_start, R.drawable.ic_car_jam, primary = true) {
                lifecycleScope.launch { ListenTogether.createParty().onSuccess { screenManager.push(CarJamCodeScreen(carContext, media)) } }
            })
            .build()
    }
}

/**
 * The jam invite, big enough to scan: the car's QR code screen (the sign-in
 * template's QR method, which Android Auto shows while parked).
 */
class CarJamCodeScreen(carContext: CarContext, media: CarMedia) : CarScreen(carContext, media) {
    override fun onGetTemplate(): Template {
        val s = ListenTogether.state.value
        val code = s.code
        if (!s.inJam || code == null) {
            return SectionedItemTemplate.Builder()
                .setHeader(Header.Builder().setStartHeaderAction(Action.BACK).setTitle(carContext.getString(R.string.auto_jam)).build())
                .addSection(RowSection.Builder().addItem(Row.Builder().setTitle(carContext.getString(R.string.auto_jam_ended)).build()).build())
                .build()
        }
        val names = s.members.filter { it.connected }.map { it.displayName }.filter { it.isNotBlank() }
        return SignInTemplate.Builder(QRCodeSignInMethod(Uri.parse(JamInviteLink.url(code))))
            .setHeaderAction(Action.BACK)
            .setTitle(carContext.getString(R.string.car_join_the_jam))
            .setInstructions(carContext.getString(R.string.car_jam_scan_instructions, code))
            .apply { if (names.isNotEmpty()) setAdditionalText(carContext.getString(R.string.car_jam_whos_in, names.joinToString(", "))) }
            .addAction(carContext.action(R.string.car_done, R.drawable.ic_car_check, primary = true) { screenManager.pop() })
            .addAction(carContext.action(R.string.auto_jam_end, R.drawable.ic_car_close) {
                lifecycleScope.launch { ListenTogether.leaveParty(); screenManager.pop() }
            })
            .build()
    }
}

/** What plays next, from the session's queue; tap to jump there. */
class CarQueueScreen(carContext: CarContext, media: CarMedia) : CarScreen(carContext, media) {
    override fun onGetTemplate(): Template {
        val c = media.controller
        val header = Header.Builder().setStartHeaderAction(Action.BACK).setTitle(carContext.getString(R.string.car_queue)).build()
        if (c == null) return SectionedItemTemplate.Builder().setHeader(header).setLoading(true).build()
        val now = c.currentMediaItemIndex
        val upcoming = (now + 1 until c.mediaItemCount).take(50).map { it to c.getMediaItemAt(it) }
        return SectionedItemTemplate.Builder()
            .setHeader(header)
            .addSection(
                RowSection.Builder()
                    .setTitle(carContext.getString(R.string.car_up_next))
                    .setNoItemsMessage(carContext.getString(R.string.car_nothing_here))
                    .setItems(upcoming.map { (index, item) -> songRow(item) { c.seekToDefaultPosition(index); c.play(); screenManager.pop() } })
                    .build(),
            )
            .build()
    }
}
