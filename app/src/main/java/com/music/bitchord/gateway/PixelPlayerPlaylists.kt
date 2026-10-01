package com.music.bitchord.gateway

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * The one-time move of PixelPlayer's playlists into YouTube Music.
 *
 * Playlists live in YouTube Music from now on — PixelPlayer kept some on the
 * gateway and some in YouTube Music, and the two drifted apart. Only the
 * gateway's own playlists (`pl-…` ids) are moved: the gateway's listing also
 * holds the account's real YouTube Music playlists, which are already there,
 * and its generated mixes, which are not anybody's playlist.
 *
 * Safe to run again, and never makes a duplicate:
 *  - each playlist moved is remembered, per gateway account, and skipped next time;
 *  - a playlist whose name the YouTube Music library already has is merged
 *    into that one — only the songs it is missing are added — rather than
 *    created a second time.
 */
object PixelPlayerPlaylists {

    sealed interface State {
        data object Idle : State
        data class Moving(val done: Int, val total: Int) : State
        /** [moved] were created in YouTube Music, [merged] topped up a playlist of the same name. */
        data class Finished(val moved: Int, val merged: Int, val failed: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var prefs: SharedPreferences

    /** Its own scope, so leaving the settings screen doesn't stop a move halfway. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("gateway_playlist_moves", Context.MODE_PRIVATE)
    }

    fun start() {
        if (_state.value is State.Moving) return
        _state.value = State.Moving(0, 0)
        scope.launch {
            _state.value = runCatching { moveAll() }.getOrElse {
                Log.w(TAG, "Playlist move failed", it)
                State.Failed(it.message ?: "Couldn't reach the gateway")
            }
        }
    }

    private suspend fun moveAll(): State {
        val user = Gateway.username.value
        val listing = Gateway.call("getPlaylists").getOrThrow()
        val gatewayPlaylists = (listing["playlists"]?.jsonObject?.get("playlist") as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .mapNotNull { obj ->
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                (id to name).takeIf { id.startsWith(GATEWAY_PLAYLIST_PREFIX) }
            }
            .filterNot { (id, _) -> prefs.contains(key(user, id)) }
        if (gatewayPlaylists.isEmpty()) return State.Finished(0, 0, 0)

        val library = YtMusicRepository.userPlaylists().getOrThrow()
            .associateByTo(HashMap()) { it.title.trim().lowercase(Locale.ROOT) }
        var moved = 0
        var merged = 0
        var failed = 0
        gatewayPlaylists.forEachIndexed { index, (id, name) ->
            _state.value = State.Moving(index, gatewayPlaylists.size)
            val outcome = runCatching {
                val songs = songsOf(id)
                val existing = library[name.trim().lowercase(Locale.ROOT)]
                val target = if (existing != null) {
                    val present = YtMusicRepository.allSongs(existing.browseId).getOrNull()
                        .orEmpty().mapTo(HashSet()) { it.videoId }
                    add(existing.playlistId, songs.filterNot { it in present })
                    merged++
                    existing.playlistId
                } else {
                    val created = YtMusicRepository.createPlaylist(name.ifBlank { "Playlist" }, PlaylistPrivacy.PRIVATE)
                        .getOrThrow()
                    add(created, songs)
                    // So a second gateway playlist of the same name merges into this one.
                    library[name.trim().lowercase(Locale.ROOT)] = UserPlaylist(created, name, "", null)
                    moved++
                    created
                }
                prefs.edit().putString(key(user, id), target).apply()
            }
            if (outcome.isFailure) {
                failed++
                Log.w(TAG, "Couldn't move playlist $id", outcome.exceptionOrNull())
            }
        }
        return State.Finished(moved, merged, failed)
    }

    /** The playlist's songs as video ids, in order, once each. */
    private suspend fun songsOf(playlistId: String): List<String> {
        val root = Gateway.call("getPlaylist", listOf("id" to playlistId)).getOrThrow()
        val entries = root["playlist"]?.jsonObject?.get("entry") as? JsonArray
        return entries.orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.let(Gateway::videoId) }
            .distinct()
    }

    /** In small batches: one edit per song is slow, one huge edit is refused. */
    private suspend fun add(playlistId: String, videoIds: List<String>) {
        videoIds.chunked(ADD_BATCH).forEach { batch ->
            YtMusicRepository.addToPlaylist(playlistId, batch).getOrThrow()
        }
    }

    private fun key(user: String, gatewayPlaylistId: String) = "$user:$gatewayPlaylistId"

    private const val TAG = "PixelPlayerPlaylists"
    private const val GATEWAY_PLAYLIST_PREFIX = "pl-"
    private const val ADD_BATCH = 50
}
