package com.music.bitchord.gateway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.music.bitchord.R
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.ui.replay.formatMinutes
import com.music.bitchord.ui.replay.listenedLabel
import com.music.bitchord.ui.replay.paletteOf
import com.music.bitchord.ui.replay.replayCount
import java.util.Locale

/**
 * The listening stats widget, drawn: a Replay card on the home screen.
 *
 * The same anatomy as the card on the Library page — the sleeve's colours as a
 * soft mesh deepened towards the foot, "your listening experience" opposite the
 * logo, the figure in raised polished type with its label under it, and the
 * cardholder line along the bottom — plus, when the widget is wide enough, the
 * month's top songs in the Replay chart's own form: a rank (the first in the
 * card's accent), the cover, the title over the artist.
 *
 * Drawn on a canvas rather than built from widget views because a widget's views
 * can't do a mesh, a gradient fill on type, an emboss or the app's typeface, and
 * without those it looks like a different app — which is the problem this
 * replaces.
 */
internal fun drawStatsCard(
    context: Context,
    summary: ReplaySummary?,
    covers: Map<String, Bitmap?>,
    holder: String,
    memberSince: String?,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val d = context.resources.displayMetrics.density
    val w = widthPx.toFloat()
    val h = heightPx.toFloat()
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val type = Type(context)

    val corner = 20 * d
    canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), corner, corner, Path.Direction.CW) })

    val leadUrl = summary?.songs?.firstOrNull()?.song?.thumbnailUrl
    val palette = paletteOf(leadUrl?.let { covers[it] })
    drawMesh(canvas, palette, w, h)

    val pad = 16 * d
    // ── Head: what it is on the left, the logo on the right ──
    // Stops short of the logo, which owns the head's right corner.
    val headWidth = w - 2 * pad - LOGO_W * d - 8 * d
    val headPaint = type.label(9.5f * d, 0xB8FFFFFF.toInt(), tracking = 0.16f)
    val head = context.getString(R.string.your_listening_experience).uppercase(Locale.getDefault())
    canvas.drawText(ellipsised(head, headPaint, headWidth), pad, pad + 9 * d, headPaint)
    // A short card keeps the figure and the name, and lets the month and play count go.
    val compact = h < 140 * d
    summary?.label?.takeIf { !compact }?.let { label ->
        val paint = type.body(11 * d, 0x8CFFFFFF.toInt())
        canvas.drawText(ellipsised(label, paint, headWidth), pad, pad + 23 * d, paint)
    }
    drawLogo(context, canvas, right = w - pad, top = pad - 2 * d, width = LOGO_W * d, height = LOGO_H * d)

    // ── Foot: the cardholder line, and member since ──
    val footBaseline = h - pad - 2 * d
    val wide = w >= 290 * d
    val tall = h >= 150 * d
    val detail = summary?.takeIf { !it.isEmpty && !compact }?.let {
        context.replayCount(it.totalPlays, R.plurals.replay_play_count)
    }
    val holderBaseline = if (detail != null) footBaseline - 13 * d else footBaseline
    // Member since takes the right corner only where it can't run into the name.
    val since = memberSince?.takeIf { w >= 220 * d }
    val holderWidth = if (since != null) w - 2 * pad - 78 * d else w - 2 * pad
    embossed(
        canvas, holder.uppercase(Locale.getDefault()), pad, holderBaseline, 12 * d, d, type.mono,
        tracking = 0.12f, maxWidth = holderWidth,
    )
    detail?.let { canvas.drawText(it, pad, footBaseline, type.body(10 * d, 0x99FFFFFF.toInt())) }
    if (since != null) {
        val label = type.label(6.5f * d, 0x8CFFFFFF.toInt(), tracking = 0.12f).apply { textAlign = Paint.Align.RIGHT }
        val caption = context.getString(R.string.member_since).uppercase(Locale.getDefault())
        canvas.drawText(caption, w - pad, footBaseline - 15 * d, label)
        embossed(canvas, since, w - pad, footBaseline, 11.5f * d, d, type.mono, tracking = 0.12f, alignRight = true)
    }

    // ── The figure, across the middle of the left half (or of the whole card) ──
    val columnRight = if (wide) w * 0.47f else w - pad
    val figureSize = when {
        tall -> 34 * d
        compact -> 24 * d
        else -> 28 * d
    }
    val bodyTop = pad + (if (compact) 14 else 30) * d
    val bodyBottom = holderBaseline - 18 * d
    val figureBaseline = bodyTop + (bodyBottom - bodyTop) / 2 + figureSize * 0.32f
    embossed(
        canvas, formatMinutes(summary?.totalMs ?: 0L), pad, figureBaseline, figureSize, d, type.heavy,
        maxWidth = columnRight - pad,
    )
    canvas.drawText(
        listenedLabel(context).uppercase(Locale.ROOT),
        pad,
        figureBaseline + 15 * d,
        type.label(9 * d, 0xA6FFFFFF.toInt(), tracking = 0.18f),
    )

    if (!wide) {
        // Only where it clears the name below it; a short card is the figure alone.
        summary?.artists?.firstOrNull()?.title?.takeIf { figureBaseline + 31 * d < holderBaseline - 16 * d }?.let { artist ->
            val paint = type.body(11 * d, 0xD9FFFFFF.toInt())
            val line = context.getString(R.string.widget_stats_top_artist, artist)
            canvas.drawText(ellipsised(line, paint, columnRight - pad), pad, figureBaseline + 31 * d, paint)
        }
        return bitmap
    }

    // ── The chart: the month's top songs ──
    val left = w * 0.52f
    val right = w - pad
    val chartTop = pad + 34 * d
    // Clear of "member since" in the corner below; as many rows as there is room for.
    val chartBottom = footBaseline - 26 * d
    val rows = ((chartBottom - (chartTop + 8 * d)) / MIN_ROW_DP / d).toInt().coerceIn(1, 3)
    val songs = summary?.songs.orEmpty().take(rows)
    canvas.drawText(
        context.getString(R.string.top_songs).uppercase(Locale.getDefault()),
        left,
        chartTop,
        type.label(8.5f * d, 0x99FFFFFF.toInt(), tracking = 0.16f),
    )
    if (songs.isEmpty()) {
        val paint = type.body(11 * d, 0xB3FFFFFF.toInt())
        val line = ellipsised(context.getString(R.string.widget_stats_nothing_yet), paint, right - left)
        canvas.drawText(line, left, chartTop + 22 * d, paint)
        return bitmap
    }
    val rowHeight = (chartBottom - (chartTop + 8 * d)) / songs.size
    val cover = (rowHeight - 6 * d).coerceAtMost(30 * d)
    val accent = accentOf(palette)
    songs.forEachIndexed { index, ranked ->
        val rowTop = chartTop + 8 * d + index * rowHeight
        val centre = rowTop + rowHeight / 2
        val rank = type.heading(if (index == 0) 15 * d else 13 * d, if (index == 0) accent else 0x73FFFFFF)
        canvas.drawText("${index + 1}", left, centre + 5 * d, rank)
        val coverLeft = left + 16 * d
        val coverRect = RectF(coverLeft, centre - cover / 2, coverLeft + cover, centre + cover / 2)
        val art = ranked.song.thumbnailUrl?.let { covers[it] }
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(coverRect, 6 * d, 6 * d, Path.Direction.CW) })
        if (art != null) {
            canvas.drawBitmap(art, centreSquare(art), coverRect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawColor(0x33FFFFFF)
        }
        canvas.restore()
        val textLeft = coverRect.right + 9 * d
        val textWidth = right - textLeft
        val title = type.body(12 * d, 0xFFFFFFFF.toInt(), bold = true)
        val artist = type.body(10.5f * d, 0x99FFFFFF.toInt())
        canvas.drawText(ellipsised(ranked.song.title, title, textWidth), textLeft, centre - 1 * d, title)
        canvas.drawText(ellipsised(ranked.song.artist, artist, textWidth), textLeft, centre + 12 * d, artist)
    }
    return bitmap
}

/** The poster's mesh recipe at the widget's size: a dark base, four soft lights, ink to the foot. */
private fun drawMesh(canvas: Canvas, colors: List<Int>, w: Float, h: Float) {
    canvas.drawColor(withLightness(colors.first(), 0.10f))
    val anchors = listOf(0.12f to 0.10f, 0.88f to 0.18f, 0.80f to 0.86f, 0.18f to 0.92f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = maxOf(w, h) * 0.85f
    colors.take(anchors.size).forEachIndexed { index, color ->
        val (fx, fy) = anchors[index]
        paint.shader = RadialGradient(
            w * fx, h * fy, radius,
            intArrayOf(ColorUtils.setAlphaComponent(color, 215), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }
    // The card's own scrim: deepened towards the foot, where the small type is.
    paint.shader = LinearGradient(
        0f, 0f, 0f, h,
        intArrayOf(0x2E000000, 0x4D000000, 0x8C000000.toInt()),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w, h, paint)
}

/**
 * Raised type, as the card's embossed lines are: a fill brightest along the top
 * edge over a dark shadow offset down. [heavy] is the figure; otherwise it is the
 * card's monospaced embosser face.
 */
private fun embossed(
    canvas: Canvas,
    text: String,
    x: Float,
    baseline: Float,
    size: Float,
    d: Float,
    face: Typeface,
    /** The embosser's fixed-pitch lines are widely tracked; the figure is not. */
    tracking: Float = 0f,
    alignRight: Boolean = false,
    maxWidth: Float = Float.MAX_VALUE,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        letterSpacing = tracking
        textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
        shader = LinearGradient(
            0f, baseline - size, 0f, baseline,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFF3F4F8.toInt(), 0xFFC9CCD6.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        setShadowLayer(2f * d, 0f, 1.2f * d, 0x99000000.toInt())
    }
    canvas.drawText(ellipsised(text, paint, maxWidth), x, baseline, paint)
}

/** The rank-one accent: the card's second colour, lifted so it reads on the mesh. */
private fun accentOf(colors: List<Int>): Int = withLightness(colors.getOrElse(1) { colors.first() }, 0.72f)

private fun withLightness(color: Int, lightness: Float): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[2] = lightness
    return ColorUtils.HSLToColor(hsl)
}

private const val LOGO_W = 34f
private const val LOGO_H = 22f

/** A chart row needs this much height for its title over its artist. */
private const val MIN_ROW_DP = 30f

private fun drawLogo(context: Context, canvas: Canvas, right: Float, top: Float, width: Float, height: Float) {
    val logo = runCatching { ResourcesCompat.getDrawable(context.resources, R.drawable.ic_logo, null) }.getOrNull() ?: return
    logo.setTint(0xFFFFFFFF.toInt())
    logo.setBounds((right - width).toInt(), top.toInt(), right.toInt(), (top + height).toInt())
    logo.draw(canvas)
}

private fun centreSquare(bitmap: Bitmap): android.graphics.Rect {
    val side = minOf(bitmap.width, bitmap.height)
    val left = (bitmap.width - side) / 2
    val top = (bitmap.height - side) / 2
    return android.graphics.Rect(left, top, left + side, top + side)
}

private fun ellipsised(text: String, paint: Paint, width: Float): String {
    if (paint.measureText(text) <= width) return text
    var end = text.length
    while (end > 1 && paint.measureText(text.take(end) + "…") > width) end--
    return text.take(end).trimEnd() + "…"
}

private class Type(context: Context) {
    val heavy: Typeface = font(context, R.font.sf_pro_display_heavy) ?: Typeface.DEFAULT_BOLD
    /** The card embosser's face: fixed-pitch, like the dies on a real one. */
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val semibold = font(context, R.font.sf_pro_display_semibold) ?: Typeface.DEFAULT_BOLD
    private val regular = font(context, R.font.sf_pro_display_regular) ?: Typeface.DEFAULT

    fun heading(size: Float, color: Int) = paint(heavy, size, color)
    fun body(size: Float, color: Int, bold: Boolean = false) = paint(if (bold) semibold else regular, size, color)
    fun label(size: Float, color: Int, tracking: Float) = paint(semibold, size, color).apply { letterSpacing = tracking }

    private fun paint(face: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        this.color = color
    }

    private fun font(context: Context, id: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()
}
