package com.music.bitchord.car

import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator

/**
 * Fork: BitChord in Android Auto, on the Car App Library's templates.
 *
 * Android Auto shows these screens instead of the generic media browser when it
 * supports templated media apps; where it doesn't, the MediaLibrarySession's
 * browse tree in PlaybackService is still there and still used.
 */
class BitChordCarAppService : CarAppService() {

    // This is a household app installed outside the Play Store, which Android
    // Auto only shows with its developer setting for unknown sources on; the
    // host check adds nothing on top of that.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = BitChordCarSession()
}

class BitChordCarSession : Session() {

    private lateinit var media: CarMedia

    override fun onCreateScreen(intent: Intent): Screen {
        media = CarMedia(carContext, lifecycle)
        // The car shows what plays (its media card, the playback screen) from the
        // session; without this registration the playback template is refused.
        media.onToken = { token ->
            runCatching {
                val manager = carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE) as MediaPlaybackManager
                manager.registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(token))
            }
        }
        val home = CarHomeScreen(carContext, media)
        if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK) {
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(home)
            return CarNowPlayingScreen(carContext, media)
        }
        return home
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action != ACTION_SHOW_MEDIA_PLAYBACK) return
        val screens = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        if (screens.top !is CarNowPlayingScreen) screens.push(CarNowPlayingScreen(carContext, media))
    }

    private companion object {
        const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}
