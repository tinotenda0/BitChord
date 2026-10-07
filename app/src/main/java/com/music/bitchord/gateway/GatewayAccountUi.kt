package com.music.bitchord.gateway

import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.Instant
import com.music.bitchord.ui.replay.grouped
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.History
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.ui.components.AlertAction
import com.music.bitchord.ui.components.AlertRule
import com.music.bitchord.ui.components.AlertScaffold
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.screens.DestructiveRow
import com.music.bitchord.ui.screens.RowDivider
import com.music.bitchord.ui.screens.SettingsGroup
import com.music.bitchord.ui.screens.SettingsRow
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/** The gateway's rows on the Account & integrations screen, in that screen's own style. */
@Composable
internal fun GatewaySettingsGroup(youtubeSignedIn: Boolean, onSignIn: () -> Unit) {
    val username by Gateway.username.collectAsStateWithLifecycle()
    val move by PixelPlayerPlaylists.state.collectAsStateWithLifecycle()
    SettingsGroup(
        header = stringResource(R.string.gateway_header),
        footer = stringResource(R.string.gateway_footer),
    ) {
        SettingsRow(
            icon = Icons.Rounded.Dns,
            title = stringResource(R.string.gateway),
            subtitle = if (username.isNotEmpty()) {
                stringResource(R.string.gateway_signed_in_as, username)
            } else {
                stringResource(R.string.gateway_tap_to_sign_in)
            },
            onClick = if (username.isEmpty()) onSignIn else null,
        )
        if (username.isNotEmpty()) {
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                title = stringResource(R.string.gateway_move_playlists),
                subtitle = if (!youtubeSignedIn) {
                    stringResource(R.string.gateway_move_needs_youtube)
                } else when (val state = move) {
                    is PixelPlayerPlaylists.State.Moving -> if (state.total == 0) {
                        stringResource(R.string.gateway_move_starting)
                    } else {
                        stringResource(R.string.gateway_move_progress, state.done + 1, state.total)
                    }
                    is PixelPlayerPlaylists.State.Finished -> finishedText(state)
                    is PixelPlayerPlaylists.State.Failed -> state.message
                    PixelPlayerPlaylists.State.Idle -> stringResource(R.string.gateway_move_playlists_subtitle)
                },
                enabled = youtubeSignedIn && move !is PixelPlayerPlaylists.State.Moving,
                onClick = PixelPlayerPlaylists::start,
            )
            RowDivider()
            SpotifyImportRow()
        }
    }
    if (username.isNotEmpty()) {
        SpotifyRemoveGroup()
        SettingsGroup {
            DestructiveRow(label = stringResource(R.string.gateway_sign_out), onClick = Gateway::signOut)
        }
    }
}

/** Picks a Spotify export and imports it — see [SpotifyImport]. */
@Composable
private fun SpotifyImportRow() {
    val context = LocalContext.current
    val state by SpotifyImport.state.collectAsStateWithLifecycle()
    val imported by SpotifyImport.imported.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { SpotifyImport.refreshImported() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { SpotifyImport.start(context, it) }
    }
    SettingsRow(
        icon = Icons.Rounded.History,
        title = stringResource(R.string.spotify_import),
        subtitle = when (val s = state) {
            SpotifyImport.State.Reading -> stringResource(R.string.spotify_import_reading)
            is SpotifyImport.State.Matching -> stringResource(R.string.spotify_import_matching, s.done, s.total)
            is SpotifyImport.State.Uploading -> stringResource(R.string.spotify_import_uploading, s.done, s.total)
            is SpotifyImport.State.Finished -> finishedText(s)
            is SpotifyImport.State.Failed -> s.message
            SpotifyImport.State.Idle -> imported?.takeIf { it.plays > 0 }?.let { importedText(it) }
                ?: stringResource(R.string.spotify_import_subtitle)
        },
        enabled = state.let { it !is SpotifyImport.State.Reading && it !is SpotifyImport.State.Matching &&
            it !is SpotifyImport.State.Uploading },
        // A zip as Spotify sends it, or the JSON files out of it; some file managers
        // report either as a generic binary.
        onClick = { pick.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) },
    )
}

/**
 * Undoing an import. Two taps, the first turning the row into the question, since
 * it takes away years of listening from the Replay in one go.
 */
@Composable
private fun SpotifyRemoveGroup() {
    val context = LocalContext.current
    val state by SpotifyImport.state.collectAsStateWithLifecycle()
    val imported by SpotifyImport.imported.collectAsStateWithLifecycle()
    val plays = imported?.plays ?: 0
    if (plays == 0 || state.let { it is SpotifyImport.State.Matching || it is SpotifyImport.State.Uploading }) return
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(CONFIRM_WINDOW_MS)
            armed = false
        }
    }
    SettingsGroup {
        DestructiveRow(
            label = if (armed) {
                pluralStringResource(R.plurals.spotify_remove_confirm, plays, grouped(plays.toLong()))
            } else {
                stringResource(R.string.spotify_remove)
            },
            onClick = {
                if (armed) {
                    armed = false
                    SpotifyImport.remove(context)
                } else {
                    armed = true
                }
            },
        )
    }
}

@Composable
private fun finishedText(state: SpotifyImport.State.Finished): String {
    val parts = buildList {
        add(pluralStringResource(R.plurals.spotify_import_added, state.added, grouped(state.added.toLong())))
        if (state.already > 0) {
            add(pluralStringResource(R.plurals.spotify_import_already, state.already, grouped(state.already.toLong())))
        }
        if (state.unmatched > 0) {
            add(pluralStringResource(R.plurals.spotify_import_unmatched, state.unmatched, grouped(state.unmatched.toLong())))
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun importedText(imported: SpotifyImport.Imported): String {
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochMilli(imported.fromMs).atZone(zone).year
    val to = Instant.ofEpochMilli(imported.toMs).atZone(zone).year
    val span = if (from == to) "$from" else "$from–$to"
    return pluralStringResource(R.plurals.spotify_imported, imported.plays, grouped(imported.plays.toLong()), span)
}

private const val CONFIRM_WINDOW_MS = 4_000L

@Composable
private fun finishedText(state: PixelPlayerPlaylists.State.Finished): String = when {
    state.moved == 0 && state.merged == 0 && state.failed == 0 -> stringResource(R.string.gateway_move_nothing)
    state.failed > 0 -> stringResource(R.string.gateway_move_done_with_failures, state.moved + state.merged, state.failed)
    else -> stringResource(R.string.gateway_move_done, state.moved + state.merged)
}

/** Username and password, checked against the gateway before anything is kept. Modelled on the Last.fm alert. */
@Composable
internal fun GatewayLoginAlert(hazeState: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(Gateway.username.value) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val canSubmit = !loading && username.isNotBlank() && password.isNotBlank()

    fun submit() {
        if (!canSubmit) return
        loading = true
        error = null
        scope.launch {
            Gateway.signIn(username, password)
                .onSuccess {
                    // Anything played while signed out, or queued offline, goes now.
                    GatewayListening.drain()
                    GatewayStats.invalidate()
                    onDismiss()
                }
                .onFailure { error = it.message ?: context.getString(R.string.login_failed) }
            loading = false
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!loading) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.gateway_login),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = error ?: stringResource(R.string.gateway_login_description),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.username),
                enabled = !loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(8.dp))
            PillTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.password),
                enabled = !loading,
                isPassword = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        AlertRule()
        AlertAction(
            label = if (loading) stringResource(R.string.signing_in) else stringResource(R.string.sign_in),
            emphasised = true,
            onClick = ::submit,
            enabled = canSubmit,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !loading,
        )
    }
}
