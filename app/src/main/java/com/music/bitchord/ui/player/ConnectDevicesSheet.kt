package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState

/**
 * This account's devices, and which one the music is coming out of.
 *
 * Grouped by phone, not listed by connection: one phone running both the
 * stable and the dev build shows once, with a row for each build, and two
 * phones never merge — see [com.music.bitchord.data.listentogether.DeviceIdentity].
 * Tapping a row moves playback there, this phone included; the last row is
 * this phone's own outputs (speaker, headphones, Bluetooth), which is what the
 * button that opens this used to go straight to.
 */
@Composable
internal fun ConnectDevicesSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onThisPhoneOutput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ListenTogether.state.collectAsStateWithLifecycle()
    val you = state.you
    val outputId = state.output?.memberId
    // This phone first, then wherever the music is, then everything else by name.
    val groups = state.members
        .groupBy { it.deviceKey.ifBlank { it.memberId } }
        .values
        .sortedWith(
            compareByDescending<List<PartyMember>> { group -> group.any { it.memberId == you?.memberId } }
                .thenByDescending { group -> group.any { it.memberId == outputId } }
                .thenBy { group -> group.first().deviceName.lowercase() },
        )

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(R.string.connect_devices_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            groups.forEach { group ->
                val isThisPhone = group.any { it.deviceKey.isNotBlank() && it.deviceKey == you?.deviceKey }
                val phoneName = group.first().deviceName.ifBlank { group.first().displayName }
                val title = if (isThisPhone) stringResource(R.string.connect_this_phone, phoneName) else phoneName
                if (group.size == 1) {
                    val member = group.single()
                    DeviceRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        title = title,
                        subtitle = subtitleFor(member, member.memberId == outputId),
                        lit = member.memberId == outputId,
                        onClick = transferTo(member, outputId, onDismiss),
                    )
                } else {
                    // Two builds of the app on one phone: the phone once, each
                    // build under it.
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                    )
                    group.sortedBy { it.app != "prod" }.forEach { member ->
                        DeviceRow(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = appLabel(member.app),
                            subtitle = statusFor(member, member.memberId == outputId),
                            lit = member.memberId == outputId,
                            onClick = transferTo(member, outputId, onDismiss),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        DeviceRow(
            icon = Icons.Rounded.Headphones,
            title = stringResource(R.string.connect_this_phone_output),
            subtitle = null,
            lit = false,
            onClick = onThisPhoneOutput,
        )
    }
}

/** Null when there is nothing to do: already playing there, or not reachable. */
private fun transferTo(member: PartyMember, outputId: String?, onDismiss: () -> Unit): (() -> Unit)? {
    if (member.memberId == outputId || !member.connected) return null
    return {
        ListenTogether.transfer(member.memberId)
        onDismiss()
    }
}

@Composable
private fun appLabel(app: String): String = when (app) {
    "prod" -> stringResource(R.string.connect_app_prod)
    "dev" -> stringResource(R.string.connect_app_dev)
    else -> app
}

@Composable
private fun statusFor(member: PartyMember, playing: Boolean): String? = when {
    playing -> stringResource(R.string.connect_playing)
    !member.connected -> stringResource(R.string.listen_together_away)
    else -> null
}

@Composable
private fun subtitleFor(member: PartyMember, playing: Boolean): String {
    val app = appLabel(member.app)
    return statusFor(member, playing)?.let { "$app · $it" } ?: app
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    lit: Boolean,
    onClick: (() -> Unit)?,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (lit) 0.12f else 0.05f))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.play(Haptic.Select)
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (lit) 0.18f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (lit) Icons.Rounded.Speaker else icon,
                contentDescription = null,
                tint = if (lit) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (lit) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
