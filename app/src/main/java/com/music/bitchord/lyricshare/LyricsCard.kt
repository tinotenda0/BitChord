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
        canvas.drawBitmap(blurredBackdrop(artwork), null, full, Paint(Paint.FILTER_BITMAP_FLAG))
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

/**
 * The cover cropped to the card's 9:16, softened and scaled back up.
 *
 * Worked at a fifth of the card's size, which keeps the cover's shapes and colours
 * recognisable once stretched, and blurred there with three box passes — close to
 * a Gaussian, and smooth where stretching a tiny thumbnail on its own goes blocky.
 */
private fun blurredBackdrop(artwork: Bitmap): Bitmap {
    // The middle of the square, as wide as a 9:16 slice of it can be.
    val cropW = artwork.height * CARD_W / CARD_H
    val left = ((artwork.width - cropW) / 2).coerceAtLeast(0)
    val slice = Bitmap.createBitmap(artwork, left, 0, cropW.coerceAtMost(artwork.width), artwork.height)
    val small = Bitmap.createScaledBitmap(slice, BACKDROP_W, BACKDROP_H, true)
    val pixels = IntArray(BACKDROP_W * BACKDROP_H)
    small.getPixels(pixels, 0, BACKDROP_W, 0, 0, BACKDROP_W, BACKDROP_H)
    val scratch = IntArray(pixels.size)
    repeat(BLUR_PASSES) {
        boxBlur(pixels, scratch, BACKDROP_W, BACKDROP_H, BLUR_RADIUS, horizontal = true)
        boxBlur(scratch, pixels, BACKDROP_W, BACKDROP_H, BLUR_RADIUS, horizontal = false)
    }
    return Bitmap.createBitmap(pixels, BACKDROP_W, BACKDROP_H, Bitmap.Config.ARGB_8888)
}

/** One running-sum box blur along rows or columns, from [src] into [dst], edges clamped. */
private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) line * w + c else c * w + line
        }
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = src[at(i)]
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until length) {
            dst[at(i)] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = src[at(i - radius)]
            val incoming = src[at(i + radius + 1)]
            r += ((incoming shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((incoming shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (incoming and 0xFF) - (out and 0xFF)
        }
    }
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
private const val ART_PX = 1200

/** The backdrop is softened at a fifth of the card's size, then stretched to fit it. */
private const val BACKDROP_W = CARD_W / 5
private const val BACKDROP_H = CARD_H / 5
private const val BLUR_RADIUS = 5
private const val BLUR_PASSES = 3

private const val LYRIC_MAX = 72f
private const val LYRIC_MIN = 44f
private const val LYRIC_SPACING = 1.12f
private const val MAX_LYRICS_HEIGHT = 1100
