package com.music.bitchord.gateway

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import java.security.MessageDigest
import java.util.UUID

/**
 * The family gateway (api.tinotenda.co): the server PixelPlayer was a client of.
 *
 * BitChord does its own YouTube Music work on the device, so the gateway is only
 * asked for what lives there and nowhere else — listening history, Surprise Me,
 * and the PixelPlayer-era playlists. Everything here speaks its Subsonic-shaped
 * API: `/rest/<endpoint>.view` with token auth (`t = md5(password + salt)`), the
 * same scheme PixelPlayer used, so the same account works unchanged.
 *
 * The password itself has to be kept, not a session: Subsonic token auth is
 * re-derived per request from a fresh salt. It lives in its own encrypted store
 * rather than in [com.music.bitchord.auth.AuthStore], so this fork's additions
 * stay out of upstream's files.
 */
object Gateway {

    const val BASE_URL = "https://api.tinotenda.co"

    private const val API_VERSION = "1.16.1"
    private const val CLIENT_ID = "BitChord"

    private lateinit var prefs: SharedPreferences

    private val _username = MutableStateFlow("")

    /** The gateway user signed in on this device, or empty when nobody is. */
    val username: StateFlow<String> = _username.asStateFlow()

    val signedIn: Boolean get() = _username.value.isNotEmpty() && password() != null

    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Opened on the main thread during startup, after [com.music.bitchord.auth.AuthStore]:
     * every encrypted store shares one keystore master key, and a first launch
     * must not have two threads racing to create it.
     */
    fun init(context: Context) {
        prefs = EncryptedPrefs.open(context, "bitchord_gateway", "bitchord_gateway_plain")
        _username.value = prefs.getString(KEY_USERNAME, null).orEmpty()
    }

    private fun password(): String? =
        if (this::prefs.isInitialized) prefs.getString(KEY_PASSWORD, null) else null

    /**
     * Checks the credentials against the gateway and keeps them only if it
     * accepts them, so a typo never leaves the app half signed in.
     */
    suspend fun signIn(username: String, password: String): Result<Unit> {
        val user = username.trim()
        return call("ping", credentials = user to password).map {
            prefs.edit().putString(KEY_USERNAME, user).putString(KEY_PASSWORD, password).apply()
            _username.value = user
        }
    }

    fun signOut() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        _username.value = ""
    }

    /**
     * One gateway call. Succeeds with the `subsonic-response` object when the
     * gateway says `ok`, and fails with its own error message otherwise.
     *
     * [params] is a list rather than a map because Subsonic repeats keys
     * (`songId=…&songId=…`). [body], when given, is POSTed; auth always stays in
     * the query string, which is where the gateway reads it.
     */
    suspend fun call(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
        body: RequestBody? = null,
        credentials: Pair<String, String>? = null,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val (user, pass) = credentials
                ?: (_username.value to (password() ?: error("Not signed in to the gateway")))
            val request = Request.Builder()
                .url(url(endpoint, user, pass, params))
                .header("Accept", "application/json")
                .apply { if (body != null) post(body) }
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) error("Gateway answered HTTP ${response.code}")
                val root = json.parseToJsonElement(text).jsonObject["subsonic-response"]?.jsonObject
                    ?: error("Unexpected gateway response")
                if (root["status"]?.jsonPrimitive?.content != "ok") {
                    val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    error(message ?: "Gateway request failed")
                }
                root
            }
        }
    }

    private fun url(
        endpoint: String,
        user: String,
        pass: String,
        params: List<Pair<String, String>>,
    ): HttpUrl {
        val salt = UUID.randomUUID().toString().take(6)
        return "$BASE_URL/rest/$endpoint.view".toHttpUrl().newBuilder()
            .addQueryParameter("u", user)
            .addQueryParameter("t", md5(pass + salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_ID)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * When the signed-in gateway account was made (epoch ms), for "member since". Asked
     * once per account and kept, since it never changes; null when the gateway has no
     * date for it, or is too old to say.
     */
    suspend fun memberSince(): Long? {
        val user = _username.value.ifEmpty { return null }
        val key = KEY_MEMBER_SINCE + user.lowercase()
        prefs.getLong(key, 0L).takeIf { it > 0 }?.let { return it }
        val since = call("getAccount").getOrNull()
            ?.get("account")?.jsonObject?.get("memberSince")?.jsonPrimitive?.longOrNull
            ?.takeIf { it > 0 } ?: return null
        prefs.edit().putLong(key, since).apply()
        return since
    }

    /** Gateway song ids are `yt-<videoId>`; BitChord's are the bare video id. */
    const val SONG_PREFIX = "yt-"

    fun songId(videoId: String): String = SONG_PREFIX + videoId

    /**
     * The video id behind a gateway song id, or null for anything else — artist
     * and album ids share the `yt-` prefix (`yt-artist-…`), so the remainder has
     * to be a real 11-character video id, the same test the gateway applies.
     */
    fun videoId(songId: String): String? =
        songId.takeIf { it.startsWith(SONG_PREFIX) }?.removePrefix(SONG_PREFIX)?.takeIf { VIDEO_ID.matches(it) }

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_MEMBER_SINCE = "member_since:"
}
