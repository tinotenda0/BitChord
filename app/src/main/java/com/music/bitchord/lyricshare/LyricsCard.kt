package com.music.bitchord.lyricshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lyrics as a picture to send: the cover blurred full-bleed behind a frosted
 * card holding the song and the chosen lines — the shape a story or a status
 * expects (9:16), drawn on a plain canvas the same way the Replay poster is,
 * in the same typeface, so the two look like they came from one app.
 *
 * Fork: brought over from PixelPlayer's lyric sharing, redrawn in BitChord's idiom.
 */
suspend fun renderLyricsCard(context: Context, song: Song, lines: List<String>): Bitmap =
    withContext(Dispatchers.Default) {
        val artwork = song.thumbnailUrl?.let { loadBitmap(context, it) }
        val bitmap = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val type = Type(context)

        drawBackdrop(canvas, artwork)

        // The text is measured first, so the card can be exactly as tall as it needs.
        val inner = (CARD_W - 2 * MARGIN - 2 * PADDING).toInt()
        var size = LYRIC_MAX
        var lyrics: StaticLayout
        do {
            lyrics = layout(lines.joinToString("\n"), type.heavy, size, inner, Integer.MAX_VALUE, LYRIC_SPACING)
            size -= 4f
        } while (lyrics.height > MAX_LYRICS_HEIGHT && size >= LYRIC_MIN)
        val textLeft = MARGIN + PADDING + COVER + 28f
        val title = layout(song.title, type.semibold, 40f, (CARD_W - MARGIN - PADDING - textLeft).toInt(), 1)
        val artist = layout(song.artist, type.regular, 34f, (CARD_W - MARGIN - PADDING - textLeft).toInt(), 1, alpha = 0.75f)

        val cardHeight = PADDING + COVER + 44f + lyrics.height + PADDING
        val top = (CARD_H - cardHeight) / 2f
        val card = RectF(MARGIN, top, CARD_W - MARGIN, top + cardHeight)
        canvas.drawRoundRect(card, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x52000000 })

        // The song, as a row: cover, then title over artist.
        val coverRect = RectF(card.left + PADDING, card.top + PADDING, card.left + PADDING + COVER, card.top + PADDING + COVER)
        if (artwork != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(coverRect, 18f, 18f, Path.Direction.CW) })
            canvas.drawBitmap(artwork, centreSquare(artwork), coverRect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            canvas.drawRoundRect(coverRect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        }
        val identityTop = coverRect.centerY() - (title.height + 6f + artist.height) / 2f
        draw(canvas, title, textLeft, identityTop)
        draw(canvas, artist, textLeft, identityTop + title.height + 6f)

        draw(canvas, lyrics, card.left + PADDING, coverRect.bottom + 44f)

        // Signed small at the foot, the way the Replay poster signs itself.
        val mark = layout(context.getString(R.string.app_name), type.semibold, 32f, CARD_W, 1, align = Layout.Alignment.ALIGN_CENTER, alpha = 0.7f)
        draw(canvas, mark, 0f, CARD_H - 120f)
        bitmap
    }

/** The cover, blurred by shrinking and stretching it back, under a darkening scrim. */
private fun drawBackdrop(canvas: Canvas, artwork: Bitmap?) {
    val full = Rect(0, 0, CARD_W, CARD_H)
    if (artwork != null) {
        val tiny = Bitmap.createScaledBitmap(artwork, BLUR_SIZE, BLUR_SIZE, true)
        // Cropped to the card's shape from the square, then stretched: the
        // scaling's own filtering is the blur.
        val cropW = BLUR_SIZE * CARD_W / CARD_H
        val source = Rect((BLUR_SIZE - cropW) / 2, 0, (BLUR_SIZE + cropW) / 2, BLUR_SIZE)
        canvas.drawBitmap(tiny, source, full, Paint(Paint.FILTER_BITMAP_FLAG))
    } else {
        canvas.drawColor(0xFF1C1C22.toInt())
    }
    canvas.drawRect(
        full.toRectF(),
        Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, CARD_H.toFloat(),
                intArrayOf(0x59000000, 0x26000000, 0x8C000000.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        },
    )
}

private fun Rect.toRectF() = RectF(this)

private fun centreSquare(bitmap: Bitmap): Rect {
    val side = minOf(bitmap.width, bitmap.height)
    val left = (bitmap.width - side) / 2
    val top = (bitmap.height - side) / 2
    return Rect(left, top, left + side, top + side)
}

private fun layout(
    text: String,
    face: Typeface,
    size: Float,
    width: Int,
    maxLines: Int,
    spacing: Float = 1f,
    align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    alpha: Float = 1f,
): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255)
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(align)
        .setLineSpacing(0f, spacing)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .build()
}

private fun draw(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
    canvas.save()
    canvas.translate(x, y)
    layout.draw(canvas)
    canvas.restore()
}

private class Type(context: Context) {
    val heavy = font(context, R.font.sf_pro_display_heavy) ?: Typeface.DEFAULT_BOLD
    val semibold = font(context, R.font.sf_pro_display_semibold) ?: Typeface.DEFAULT_BOLD
    val regular = font(context, R.font.sf_pro_display_regular) ?: Typeface.DEFAULT

    private fun font(context: Context, id: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()
}

private suspend fun loadBitmap(context: Context, url: String): Bitmap? = runCatching {
    val request = ImageRequest.Builder(context)
        .data(url.artworkAt(ART_PX))
        // Drawn into a software canvas, which a hardware bitmap can't be.
        .allowHardware(false)
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
}.getOrNull()

private const val CARD_W = 1080
private const val CARD_H = 1920
private const val MARGIN = 64f
private const val PADDING = 52f
private const val COVER = 132f
private const val ART_PX = 600
private const val BLUR_SIZE = 24

private const val LYRIC_MAX = 72f
private const val LYRIC_MIN = 44f
private const val LYRIC_SPACING = 1.12f
private const val MAX_LYRICS_HEIGHT = 1100
