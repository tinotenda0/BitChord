package com.music.bitchord.gateway

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.RemoteViews
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.stats.ReplayPeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The listening stats home-screen widget: this month's Replay card — see
 * [drawStatsCard] for what is on it.
 *
 * Drawn from the same summary the Replay page is — [GatewayStats.replaySummary],
 * so the gateway's history when signed in to it and this device's own otherwise —
 * and so it always agrees with the page. Redrawn whenever a listen is finished or
 * reported (see [GatewayListening]), when the unit changes, when it is resized,
 * and on the system's 30-minute backstop.
 */
class ListeningStatsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = renderAsync(context, ids)

    /** A resize changes the card's shape, and it is drawn to size. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) =
        renderAsync(context, intArrayOf(id))

    private fun renderAsync(context: Context, ids: IntArray) {
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

        /**
         * The name on the card. The widget can't see the signed-in account, so the
         * app tells it whenever the account changes, as the Library's cards are
         * embossed with the same name.
         */
        fun setHolder(context: Context, name: String?) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_HOLDER, null) == name) return
            prefs.edit().putString(KEY_HOLDER, name).apply()
            refresh(context)
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val summary = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                runCatching { GatewayStats.replaySummary(ReplayPeriod.THIS_MONTH) }.getOrNull()
            }
            val memberSince = runCatching { GatewayStats.replayFirstMonth() }.getOrNull()
                ?.let { "%02d/%02d".format(Locale.ROOT, it.monthValue, it.year % 100) }
            val urls = summary?.songs.orEmpty().take(CHART_ROWS).mapNotNull { it.song.thumbnailUrl }.distinct()
            val covers = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                urls.map { url -> scope.async(Dispatchers.IO) { url to loadBitmap(context, url) } }.awaitAll().toMap()
            }.orEmpty()
            val holder = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOLDER, null)
                ?.takeIf { it.isNotBlank() } ?: context.getString(R.string.default_replay_holder)

            for (id in ids) {
                val (w, h) = sizePx(context, manager, id)
                val card = runCatching { drawStatsCard(context, summary, covers, holder, memberSince, w, h) }.getOrNull()
                manager.updateAppWidget(id, views(context, card))
            }
        }

        private fun views(context: Context, card: Bitmap?): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_stats).apply {
                card?.let { setImageViewBitmap(R.id.widget_stats_image, it) }
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

        /**
         * The widget's size in pixels for the orientation in force, as the player
         * widget measures its own: a portrait launcher reports the cell as min-width
         * by max-height, a landscape one as max-width by min-height. Capped, because
         * a widget's picture travels to the launcher in one parcel.
         */
        private fun sizePx(context: Context, manager: AppWidgetManager, id: Int): Pair<Int, Int> {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val wDp = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            )?.takeIf { it > 0 } ?: FALLBACK_W_DP
            val hDp = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            )?.takeIf { it > 0 } ?: FALLBACK_H_DP
            val density = context.resources.displayMetrics.density
            var w = (wDp * density).roundToInt()
            var h = (hDp * density).roundToInt()
            val scale = minOf(1f, MAX_SIDE_PX.toFloat() / maxOf(w, h))
            w = (w * scale).roundToInt().coerceAtLeast(1)
            h = (h * scale).roundToInt().coerceAtLeast(1)
            return w to h
        }

        private suspend fun loadBitmap(context: Context, url: String): Bitmap? = runCatching {
            val request = ImageRequest.Builder(context)
                .data(url.artworkAt(COVER_PX))
                // Drawn into a software canvas, which a hardware bitmap can't be.
                .allowHardware(false)
                .build()
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }.getOrNull()

        private const val PREFS = "stats_widget"
        private const val KEY_HOLDER = "holder"
        private const val CHART_ROWS = 3
        private const val COVER_PX = 240
        private const val FALLBACK_W_DP = 320
        private const val FALLBACK_H_DP = 180
        private const val MAX_SIDE_PX = 1200
        private const val REFRESH_DEBOUNCE_MS = 1_500L
        private const val RENDER_TIMEOUT_MS = 20_000L
    }
}
