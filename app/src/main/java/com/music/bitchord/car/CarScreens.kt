package com.music.bitchord.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
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
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import com.music.bitchord.R
import kotlinx.coroutines.launch

/**
 * Fork: the car's home, as tabs: Home (Quick picks), Library (playlists) and Jam.
 * A first cut on the templates; see the design canvas for where it is going.
 */
class CarHomeScreen(carContext: CarContext, private val media: CarMedia) : Screen(carContext) {

    private var tab = TAB_HOME
    private var quickPicks: List<MediaItem>? = null
    private var playlists: List<MediaItem>? = null
    private val redraw: () -> Unit = {
        if (quickPicks == null && media.connected) load()
        invalidate()
    }

    init {
        media.listen(redraw)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = media.unlisten(redraw)
        })
        if (media.connected) load()
    }

    private fun load() {
        lifecycleScope.launch {
            quickPicks = media.children(FOLDER_QUICK_PICKS).filter { it.mediaMetadata.isPlayable == true }
            playlists = media.children(FOLDER_PLAYLISTS).filter { it.mediaMetadata.isBrowsable == true }
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
            .addTab(tabOf(TAB_JAM, R.string.auto_jam, R.drawable.ic_car_jam))
            .setTabContents(TabContents.Builder(contentFor(tab)).build())
            .setActiveTabContentId(tab)
            .build()

    private fun tabOf(id: String, title: Int, icon: Int): Tab =
        Tab.Builder()
            .setContentId(id)
            .setTitle(carContext.getString(title))
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .build()

    private fun contentFor(id: String): Template = when (id) {
        TAB_LIBRARY -> grid(playlists, R.string.playlists)
        TAB_JAM -> SectionedItemTemplate.Builder()
            .addSection(
                RowSection.Builder()
                    .addItem(Row.Builder().setTitle(carContext.getString(R.string.car_jam_coming)).build())
                    .build(),
            )
            .build()
        else -> grid(quickPicks, R.string.auto_quick_picks)
    }

    private fun grid(items: List<MediaItem>?, title: Int): Template {
        if (items == null) return SectionedItemTemplate.Builder().setLoading(true).build()
        val section = GridSection.Builder()
            .setTitle(carContext.getString(title))
            .setItemSize(GridSection.ITEM_SIZE_LARGE)
            .setNoItemsMessage(carContext.getString(R.string.car_nothing_here))
        items.take(MAX_GRID).forEach { item ->
            section.addItem(
                GridItem.Builder()
                    .setTitle(item.mediaMetadata.title ?: "")
                    .apply { item.mediaMetadata.artist?.let { setText(it) } ?: item.mediaMetadata.subtitle?.let { setText(it) } }
                    .setImage(
                        media.artwork(item)
                            ?: CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_library)).build(),
                    )
                    .setOnClickListener {
                        media.play(item)
                        screenManager.push(CarNowPlayingScreen(carContext, media))
                    }
                    .build(),
            )
        }
        return SectionedItemTemplate.Builder()
            .addSection(section.build())
            // The minimised player, to get back to what is playing from anywhere.
            .addAction(Action.MEDIA_PLAYBACK)
            .build()
    }

    private companion object {
        const val TAB_HOME = "home"
        const val TAB_LIBRARY = "library"
        const val TAB_JAM = "jam"
        const val FOLDER_QUICK_PICKS = "quick_picks"
        const val FOLDER_PLAYLISTS = "playlists"
        const val MAX_GRID = 24
    }
}

/** Fork: now playing. The car draws the artwork, controls and progress from the session. */
class CarNowPlayingScreen(carContext: CarContext, private val media: CarMedia) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MediaPlaybackTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(carContext.getString(R.string.car_now_playing))
                    .build(),
            )
            .build()
}
