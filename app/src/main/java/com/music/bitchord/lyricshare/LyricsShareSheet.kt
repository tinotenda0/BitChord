package com.music.bitchord.lyricshare

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Pick lines, see the card, send it — or send the words as text.
 *
 * Opened by holding a lyric line, which arrives already picked. The preview is
 * redrawn as the selection changes, a moment after the last tap, so ticking
 * through several lines doesn't draw a card per tap. Laid out like the Replay's
 * share sheet: the picture first, the choices under it, the two actions last.
 *
 * Fork: PixelPlayer's lyric sharing, in BitChord's own sheet.
 */
@Composable
fun LyricsShareSheet(song: Song, lines: List<LyricLine>, initialIndex: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Instrumental breaks and blank lines are not something anyone quotes.
    val choices = remember(lines) {
        lines.withIndex().filter { it.value.text.isNotBlank() }.map { it.index to it.value.text.trim() }
    }
    var picked by remember(initialIndex) {
        mutableStateOf(setOfNotNull(initialIndex.takeIf { index -> choices.any { it.first == index } }))
    }
    val chosen = choices.filter { it.first in picked }.map { it.second }
    var card by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(chosen) {
        if (chosen.isEmpty()) {
            card = null
            return@LaunchedEffect
        }
        delay(REDRAW_DELAY_MS)
        card = runCatching { renderLyricsCard(context, song, chosen) }.getOrNull()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.share_lyrics),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.share_lyrics_description, MAX_LINES),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth(0.36f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val image = card
            when {
                image != null -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
                chosen.isEmpty() -> Text(
                    text = stringResource(R.string.share_lyrics_pick),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
                else -> CircularProgressIndicator()
            }
        }

        Spacer(Modifier.height(16.dp))

        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = choices.indexOfFirst { it.first == initialIndex }.coerceAtLeast(0),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            itemsIndexed(choices, key = { _, choice -> choice.first }) { _, (index, text) ->
                val selected = index in picked
                val full = picked.size >= MAX_LINES
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = selected || !full) {
                            picked = if (selected) picked - index else picked + index
                        }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (full) 0.35f else 1f)
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected || !full) 1f else 0.45f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShareAction(
                label = stringResource(R.string.share_lyrics_as_text),
                icon = Icons.Rounded.TextFields,
                accent = false,
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                val text = "“${chosen.joinToString("\n")}”\n— ${song.title}, ${song.artist}"
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                        context.getString(R.string.share_lyrics),
                    ),
                )
                onDismiss()
            }
            ShareAction(
                label = stringResource(R.string.share),
                icon = Icons.Rounded.IosShare,
                accent = true,
                enabled = card != null,
                modifier = Modifier.weight(1f),
            ) {
                val image = card ?: return@ShareAction
                scope.launch {
                    val uri = cacheForSharing(context, image) ?: return@launch
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("image/png")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            context.getString(R.string.share_lyrics),
                        ),
                    )
                    onDismiss()
                }
            }
        }
    }
}

/** [LyricsShareSheet] in the app's bottom sheet, opened straight to full height as the Replay's is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsShareBottomSheet(song: Song, lines: List<LyricLine>, initialIndex: Int, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        LyricsShareSheet(song = song, lines = lines, initialIndex = initialIndex, onDismiss = onDismiss)
    }
}

/** The Replay share sheet's button, which is private to it. */
@Composable
private fun ShareAction(
    label: String,
    icon: ImageVector,
    accent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = foreground, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W700,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Into the cache folder the FileProvider already shares (cache/shared, which
 * the Replay poster uses too), under one name that each share overwrites.
 */
private suspend fun cacheForSharing(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val folder = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(folder, "lyrics.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

/** Enough for a verse; past it the card's type would have to shrink below legible. */
private const val MAX_LINES = 6

private const val REDRAW_DELAY_MS = 250L
