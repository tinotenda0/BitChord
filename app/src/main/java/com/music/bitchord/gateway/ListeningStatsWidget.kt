package com.music.bitchord.gateway

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.music.bitchord.R
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.ui.replay.formatMinutes
import com.music.bitchord.ui.replay.unitLabel
import com.music.bitchord.widget.MediaWidgetArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The listening stats home-screen widget: this month's listening in the Replay's
 * chosen unit, the top artist and the top song, with that song's cover.
 *
 * Drawn from the same summary the Replay page is — [GatewayStats.replaySummary],
 * so the gateway's history when signed in to it and this device's own otherwise —
 * and so it always agrees with the page. Redrawn whenever a listen is finished or
 * reported (see [GatewayListening]), when the unit changes, and on the system's
 * 30-minute backstop.
 */
class ListeningStatsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context.applicationContext, ids)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var pendingRefresh: Job? = null

        /**
         * Redraws every placed stats widget, a moment after the last call — a track
         * change finishes one listen and reports it in quick succession, and one
         * redraw covers both.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            pendingRefresh?.cancel()
            pendingRefresh = scope.launch {
                delay(REFRESH_DEBOUNCE_MS)
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, ListeningStatsWidget::class.java))
                }.getOrNull()
                if (ids == null || ids.isEmpty()) return@launch
                render(app, ids)
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val summary = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                runCatching { GatewayStats.replaySummary(ReplayPeriod.THIS_MONTH) }.getOrNull()
            }
            val artUrl = summary?.songs?.firstOrNull()?.song?.thumbnailUrl
            val artPx = context.resources.getDimensionPixelSize(R.dimen.widget_pill_art)
            val art = artUrl?.let {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) { MediaWidgetArt.circle(context, it, artPx, "stats|$it") }
            }
            for (id in ids) manager.updateAppWidget(id, views(context, summary, art))
        }

        private fun views(context: Context, summary: ReplaySummary?, art: Bitmap?): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_stats).apply {
                val total = summary?.totalMs ?: 0L
                setTextViewText(R.id.widget_stats_total, formatMinutes(total))
                setTextViewText(R.id.widget_stats_unit, unitLabel(context).lowercase())
                val artist = summary?.artists?.firstOrNull()?.title
                val song = summary?.songs?.firstOrNull()?.song
                if (artist == null && song == null) {
                    setTextViewText(R.id.widget_stats_artist, context.getString(R.string.widget_stats_nothing_yet))
                    setTextViewText(R.id.widget_stats_song, "")
                } else {
                    setTextViewText(
                        R.id.widget_stats_artist,
                        artist?.let { context.getString(R.string.widget_stats_top_artist, it) }.orEmpty(),
                    )
                    setTextViewText(
                        R.id.widget_stats_song,
                        song?.let { context.getString(R.string.widget_stats_top_song, it.title) }.orEmpty(),
                    )
                }
                if (art != null) setImageViewBitmap(R.id.widget_stats_art, art)
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                    setOnClickPendingIntent(
                        R.id.widget_stats,
                        PendingIntent.getActivity(
                            context,
                            0,
                            launch,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }

        private const val REFRESH_DEBOUNCE_MS = 1_500L
        private const val RENDER_TIMEOUT_MS = 20_000L
    }
}
