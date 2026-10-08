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
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.listentogether.ConnectDevice
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
    val context = LocalContext.current
    val you = state.you
    val outputId = state.output?.memberId
    // Connected devices are members; the rest of what the server remembers are
    // asleep, and are listed too so playback can be sent to them. A device that
    // is both (still a member, just away) is listed once, as the member.
    val entries = state.members.map { member ->
        DeviceEntry(
            deviceKey = member.deviceKey.ifBlank { member.memberId },
            app = member.app,
            deviceName = member.deviceName.ifBlank { member.displayName },
            member = member,
            known = state.devices.firstOrNull { it.deviceKey == member.deviceKey && it.app == member.app },
        )
    } + state.devices
        .filter { known -> state.members.none { it.deviceKey == known.deviceKey && it.app == known.app } }
        .map { DeviceEntry(it.deviceKey, it.app, it.deviceName, member = null, known = it) }
    // This phone first, then wherever the music is, then everything else by name.
    val groups = entries
        .groupBy { it.deviceKey }
        .values
        .sortedWith(
            compareByDescending<List<DeviceEntry>> { group -> group.any { it.member?.memberId == you?.memberId } }
                .thenByDescending { group -> group.any { it.member?.memberId == outputId } }
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
                val phoneName = group.first().deviceName
                val title = if (isThisPhone) stringResource(R.string.connect_this_phone, phoneName) else phoneName
                val waking = stringResource(R.string.connect_waking, phoneName)
                val act: (DeviceEntry) -> (() -> Unit)? = { entry ->
                    entry.action(outputId)?.let { run ->
                        {
                            if (entry.sleeping) Toast.makeText(context, waking, Toast.LENGTH_SHORT).show()
                            run()
                            onDismiss()
                        }
                    }
                }
                if (group.size == 1) {
                    val entry = group.single()
                    val status = statusFor(entry, outputId)
                    DeviceRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        title = title,
                        subtitle = status?.let { "${appLabel(entry.app)} · $it" } ?: appLabel(entry.app),
                        lit = entry.isOutput(outputId),
                        dim = entry.sleeping,
                        onClick = act(entry),
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
                    group.sortedBy { it.app != "prod" }.forEach { entry ->
                        DeviceRow(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = appLabel(entry.app),
                            subtitle = statusFor(entry, outputId),
                            lit = entry.isOutput(outputId),
                            dim = entry.sleeping,
                            onClick = act(entry),
                        )
                    }
                }
            }
        }

        // The device playing, turned up or down from here. Only when this is
        // not that device (its own buttons do that) and it allows it.
        val playback = state.playback
        val volume = playback.volume
        if (state.isRemote && volume != null && playback.volumeControl) {
            Spacer(Modifier.height(10.dp))
            RemoteVolume(volume = volume, steps = playback.volumeSteps)
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

/** One build on one phone: connected (a member), asleep (only remembered), or both. */
private data class DeviceEntry(
    val deviceKey: String,
    val app: String,
    val deviceName: String,
    val member: PartyMember?,
    val known: ConnectDevice?,
) {
    /** Not connected at all, so reaching it means waking it. */
    val sleeping: Boolean get() = member == null || !member.connected

    /** Away from Connect because it is in a jam, not because it is asleep. */
    val inJam: Boolean get() = sleeping && known?.status == "jam"

    fun isOutput(outputId: String?): Boolean = member != null && member.memberId == outputId

    /**
     * What tapping it does: move playback there, wake it to do so, or nothing
     * (already playing there, or asleep with no way to wake it).
     */
    fun action(outputId: String?): (() -> Unit)? = when {
        isOutput(outputId) -> null
        member != null && member.connected -> { { ListenTogether.transfer(member.memberId) } }
        // Busy in a jam: waking it would drag it out of that, which is its
        // owner's call to make on that device, not this one's.
        inJam -> null
        known != null && known.wakeable -> { { ListenTogether.wake(known.deviceId) } }
        else -> null
    }
}

@Composable
private fun appLabel(app: String): String = when (app) {
    "prod" -> stringResource(R.string.connect_app_prod)
    "dev" -> stringResource(R.string.connect_app_dev)
    else -> app
}

@Composable
private fun statusFor(entry: DeviceEntry, outputId: String?): String? = when {
    entry.isOutput(outputId) -> stringResource(R.string.connect_playing)
    !entry.sleeping -> null
    entry.inJam -> stringResource(R.string.connect_in_a_jam)
    entry.known?.wakeable == true -> stringResource(R.string.connect_asleep)
    else -> stringResource(R.string.connect_cant_wake)
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    lit: Boolean,
    onClick: (() -> Unit)?,
    dim: Boolean = false,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CONNECT_ROW_SHAPE)
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
                color = if (lit) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (dim) 0.6f else 0.85f),
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

/**
 * A slider for the output's volume. Follows the output while untouched, and
 * the finger while dragged: sending every position would be dozens of controls
 * a second, so a change goes out at most every [VOLUME_SEND_MS] and once more
 * where the finger lets go.
 */
@Composable
private fun RemoteVolume(volume: Double, steps: Int) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var lastSentAt by remember { mutableStateOf(0L) }
    // Where the finger let go, held until the output reports it has got there
    // (or a moment passes), so the thumb does not jump back to the old level
    // for the round trip.
    var released by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(released) {
        if (released != null) {
            delay(VOLUME_SETTLE_MS)
            released = null
        }
    }
    LaunchedEffect(volume) {
        val held = released ?: return@LaunchedEffect
        if (kotlin.math.abs(volume - held) <= 0.5 / steps.coerceAtLeast(1)) released = null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CONNECT_ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.VolumeDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = dragging ?: released ?: volume.toFloat(),
            onValueChange = { value ->
                dragging = value
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSentAt >= VOLUME_SEND_MS) {
                    lastSentAt = now
                    ListenTogether.setVolume(value.toDouble())
                }
            },
            onValueChangeFinished = {
                dragging?.let {
                    ListenTogether.setVolume(it.toDouble())
                    released = it
                }
                dragging = null
            },
            steps = (steps - 1).coerceIn(0, 100),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Icon(
            imageVector = Icons.Rounded.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private const val VOLUME_SEND_MS = 120L
private const val VOLUME_SETTLE_MS = 1_500L

/** The player drawer's row shape (PlayerDrawer's ROW_SHAPE, internal to sharedUi). */
private val CONNECT_ROW_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
