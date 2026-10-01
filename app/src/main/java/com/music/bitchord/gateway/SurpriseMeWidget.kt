package com.music.bitchord.gateway

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.playSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Surprise Me home-screen widget: one capsule that starts the gateway DJ's
 * mix without opening the app.
 *
 * Playback is reached the way the player widget reaches it — by binding a
 * [MediaController] to [PlaybackService] rather than starting the service, which
 * a background tap is not allowed to do (see
 * [com.music.bitchord.widget.MediaWidgetActions]). The batch is fetched first,
 * so a gateway that can't answer leaves whatever was playing alone, and the
 * capsule's second line says what is happening while it works.
 */
class SurpriseMeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, manager, id, R.string.widget_surprise_idle))
    }

    /** Below Android 12 the layout is chosen here, from the size it was resized to. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        manager.updateAppWidget(id, views(context, manager, id, R.string.widget_surprise_idle))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START) return super.onReceive(context, intent)
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                show(app, R.string.widget_surprise_starting)
                // Boxed, because start's own null means "playing" and a timeout's means "gave up".
                val outcome = withTimeoutOrNull(START_TIMEOUT_MS) { Outcome(start(app)) }
                val problem = if (outcome == null) R.string.widget_surprise_failed else outcome.problem
                if (problem != null) {
                    show(app, problem)
                    delay(MESSAGE_MS)
                }
                show(app, R.string.widget_surprise_idle)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    /** Starts the mix. Returns a message to show if it couldn't, or null once it is playing. */
    private suspend fun start(context: Context): Int? {
        if (!Gateway.signedIn) return R.string.widget_surprise_sign_in
        // A party's queue belongs to everyone in it; the app asks before
        // replacing it, and a widget has nowhere to ask.
        if (ListenTogether.state.value.inParty) return R.string.widget_surprise_in_party
        val songs = SurpriseMe.batch().getOrNull().orEmpty()
        if (songs.isEmpty()) return R.string.widget_surprise_failed
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        try {
            val controller = future.await()
            controller.playSongs(songs.map { it.asQueueEntry(QueueTier.CONTEXT) }, 0)
            controller.play()
            // Held a moment so the service can resolve the first stream and go
            // foreground before the last binding drops — the same settle the
            // player widget's buttons wait out.
            delay(SETTLE_MS)
        } finally {
            MediaController.releaseFuture(future)
        }
        return null
    }

    /** What [start] reported: a message to show, or null once the mix is playing. */
    private class Outcome(val problem: Int?)

    companion object {
        private const val ACTION_START = "com.music.bitchord.gateway.SURPRISE_ME"

        private const val START_TIMEOUT_MS = 30_000L
        private const val SETTLE_MS = 3_000L
        private const val MESSAGE_MS = 3_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private fun show(context: Context, status: Int) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, SurpriseMeWidget::class.java))
            }.getOrNull() ?: return
            for (id in ids) manager.updateAppWidget(id, views(context, manager, id, status))
        }

        /**
         * Both shapes of the widget: the capsule with its status line, and — at one
         * cell — the sparkle alone. Android 12 and up is handed both and picks by the
         * size the widget is actually at, resizing included; below that, the size is
         * read from the widget's options.
         */
        private fun views(context: Context, manager: AppWidgetManager, id: Int, status: Int): RemoteViews {
            val wide = capsule(context, status)
            val small = tile(context, status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return RemoteViews(mapOf(SizeF(40f, 40f) to small, SizeF(CAPSULE_MIN_DP, 40f) to wide))
            }
            val widthDp = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 }
            return if (widthDp != null && widthDp < CAPSULE_MIN_DP) small else wide
        }

        private fun capsule(context: Context, status: Int) =
            RemoteViews(context.packageName, R.layout.widget_surprise_me).apply {
                setTextViewText(R.id.widget_surprise_status, context.getString(status))
                setOnClickPendingIntent(R.id.widget_surprise, start(context))
            }

        /** No line to say what is happening in, so the sparkle dims while a mix starts. */
        private fun tile(context: Context, status: Int) =
            RemoteViews(context.packageName, R.layout.widget_surprise_me_small).apply {
                val busy = status == R.string.widget_surprise_starting
                setInt(R.id.widget_surprise_icon, "setImageAlpha", if (busy) 110 else 255)
                setContentDescription(R.id.widget_surprise, context.getString(status))
                setOnClickPendingIntent(R.id.widget_surprise, start(context))
            }

        private fun start(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SurpriseMeWidget::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Narrower than this, the capsule's two lines don't fit and the tile takes over. */
        private const val CAPSULE_MIN_DP = 110f
    }
}
