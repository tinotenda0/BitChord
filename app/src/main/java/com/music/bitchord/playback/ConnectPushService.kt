package com.music.bitchord.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.listentogether.ListenTogether
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Connect's wake-up call: another of this account's devices pressed "play here"
 * on this one while its app was closed.
 *
 * The push comes through UnifiedPush, delivered by ntfy, because this app has no
 * Google services to receive FCM with. ntfy raises this app to foreground
 * importance for a few seconds before handing the message over (UnifiedPush's
 * AND_3 "raise to foreground", whose service the connector declares), which is
 * what allows the playback service to start from here at all on Android 12 and
 * later. Those seconds are the budget: sign in, be handed playback, load and
 * start. So nothing here waits on anything it does not have to.
 */
class ConnectPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "push endpoint ready")
        ListenTogether.setPushEndpoint(endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val text = runCatching { message.content.decodeToString() }.getOrDefault("")
        if (!text.contains("\"takeover\"")) return
        Log.i(TAG, "woken to take playback over")
        ConnectWake.wake(applicationContext)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "push registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "push endpoint withdrawn")
        ListenTogether.setPushEndpoint(null)
    }

    private companion object {
        const val TAG = "ConnectPush"
    }
}

/**
 * Brings this device into its account's Connect party, with the playback
 * service running, so that it can be handed playback.
 *
 * Two things, started together. Signing in is [ListenTogether]'s and needs no
 * service; playing needs [PlaybackService] and its [PartySync], which start when
 * something binds to the session, so a controller is connected and held for a
 * while. PartySync then does what it does for any output: loads the party's
 * track and starts it at the instant the server scheduled.
 */
object ConnectWake {

    private const val HOLD_MS = 60_000L

    private var pending: ListenableFuture<MediaController>? = null
    private val main = Handler(Looper.getMainLooper())

    fun wake(context: Context) {
        main.post {
            ListenTogether.wakeForTakeover()
            if (pending != null) return@post
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            pending = future
            // Held long enough for playback to start and the service to put
            // itself in the foreground, after which it keeps itself alive the
            // way it does whenever music is playing; then let go.
            main.postDelayed({
                MediaController.releaseFuture(future)
                if (pending === future) pending = null
            }, HOLD_MS)
        }
    }
}
