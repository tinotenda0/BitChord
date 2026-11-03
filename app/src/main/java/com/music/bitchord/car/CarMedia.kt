package com.music.bitchord.car

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Fork: the music service, as the car's templated screens see it.
 *
 * The screens are a client of the same MediaLibrarySession the phone's own
 * surfaces and the old Android Auto browser use: the same folders (Quick
 * picks, playlists, a playlist's songs) and the same "play this" handling, so
 * nothing about where songs come from is duplicated here. It also loads the
 * artwork the templates draw, and tells the screens when some arrives.
 */
class CarMedia(private val context: CarContext, lifecycle: Lifecycle) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var browser: MediaBrowser? = null
    private val icons = HashMap<String, CarIcon>()
    private val loading = HashSet<String>()
    private val listeners = LinkedHashSet<() -> Unit>()

    /** The session's platform token, once the service is up, for the car's media integration. */
    var onToken: ((android.media.session.MediaSession.Token) -> Unit)? = null

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaBrowser.Builder(context, token).buildAsync()
        future.addListener({
            browser = runCatching { future.get() }.getOrNull()
            CarPlayback.platformToken?.let { onToken?.invoke(it) }
            changed()
        }, ContextCompat.getMainExecutor(context))
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    scope.cancel()
                    browser?.release()
                    browser = null
                }
            },
        )
    }

    val connected: Boolean get() = browser != null
    val controller: MediaBrowser? get() = browser

    /** Called back on the main thread when the connection or some artwork arrives. */
    fun listen(onChange: () -> Unit) {
        listeners += onChange
    }

    fun unlisten(onChange: () -> Unit) {
        listeners -= onChange
    }

    private fun changed() = listeners.toList().forEach { it() }

    /** A folder of the music service's tree; empty when it has nothing or can't be reached. */
    suspend fun children(parentId: String, pageSize: Int = 60): List<MediaItem> {
        val b = browser ?: return emptyList()
        return runCatching { b.getChildren(parentId, 0, pageSize, null).await().value.orEmpty() }
            .getOrDefault(emptyList())
    }

    /** Plays a song or a whole folder (a playlist, a Shuffle row), as the car asked. */
    fun play(item: MediaItem) {
        val b = browser ?: return
        b.setMediaItem(MediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(item.mediaMetadata).build())
        b.prepare()
        b.play()
    }

    /** Plays a whole folder (a playlist, Liked songs), in order or shuffled. */
    fun playAll(folderId: String, shuffle: Boolean) {
        val b = browser ?: return
        // The service resolves both: the folder's songs, or "shuffle:" and them shuffled.
        b.setMediaItem(MediaItem.Builder().setMediaId(if (shuffle) "shuffle:$folderId" else folderId).build())
        b.prepare()
        b.play()
    }

    /** [item]'s artwork for a template, or null until it has loaded (the screen redraws then). */
    fun artwork(item: MediaItem): CarIcon? {
        val key = item.mediaId
        icons[key]?.let { return it }
        item.mediaMetadata.artworkData?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return icon(bitmap).also { icons[key] = it }
        }
        val uri = item.mediaMetadata.artworkUri ?: return null
        if (loading.add(key)) {
            scope.launch {
                val bitmap = runCatching {
                    val request = ImageRequest.Builder(context)
                        .data(uri.toString())
                        .size(ARTWORK_PX)
                        .allowHardware(false)
                        .build()
                    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                }.getOrNull()
                loading.remove(key)
                if (bitmap != null) {
                    icons[key] = icon(bitmap)
                    changed()
                }
            }
        }
        return null
    }

    private fun icon(bitmap: Bitmap): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()

    private companion object {
        const val ARTWORK_PX = 384
    }
}

/** Fork: what the car app needs from the music service that it can't ask the session for. */
object CarPlayback {
    /** Set by PlaybackService once its session exists. */
    @Volatile var platformToken: android.media.session.MediaSession.Token? = null
}
