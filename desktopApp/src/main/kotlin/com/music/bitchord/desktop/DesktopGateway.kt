package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Fork: the family gateway (api.tinotenda.co), as the desktop signs in to it.
 *
 * The same account and the same scheme as the phone's `Gateway`: Subsonic token
 * auth, `t = md5(password + salt)` with a fresh salt per request, so the
 * password itself has to be kept. On Windows it is sealed with DPAPI to this
 * Windows user before it is written down; elsewhere it goes to the system's
 * secret store, and is not kept at all where there is none.
 *
 * Only Connect uses it for now: a Connect party is the gateway account's, and
 * the jam server checks the token with the gateway before letting a device in.
 */
internal object DesktopGateway {

    const val BASE_URL = "https://api.tinotenda.co"

    private const val API_VERSION = "1.16.1"
    private const val CLIENT_ID = "BitChord"
    private const val KEY_USERNAME = "gateway_username"
    private const val KEY_PASSWORD_SEALED = "gateway_password_sealed"
    private val SECRET_ATTRIBUTES = mapOf("application" to "bitchord-gateway")

    private val persistence by lazy { DesktopPersistence() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }

    private val _username = MutableStateFlow(persistence.string(KEY_USERNAME))

    /** The gateway user signed in on this computer, or empty when nobody is. */
    val username: StateFlow<String> = _username.asStateFlow()

    val signedIn: Boolean get() = _username.value.isNotEmpty() && password() != null

    /**
     * Checks the credentials against the gateway and keeps them only if it
     * accepts them, so a typo never leaves the app half signed in.
     */
    suspend fun signIn(username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter your gateway username and password."))
        }
        ping(user, password).mapCatching {
            check(savePassword(password)) { "This computer has nowhere safe to keep the password." }
            persistence.saveString(KEY_USERNAME, user)
            _username.value = user
        }
    }

    fun signOut() {
        persistence.saveString(KEY_USERNAME, "")
        persistence.saveString(KEY_PASSWORD_SEALED, "")
        if (!DesktopPlatform.isWindows) runCatching { DesktopSecretStore.remove(SECRET_ATTRIBUTES) }
        _username.value = ""
    }

    /** (user, token, salt) for one request, or null when signed out. */
    fun tokenLogin(): Triple<String, String, String>? {
        val user = _username.value.takeIf { it.isNotEmpty() } ?: return null
        val password = password() ?: return null
        val salt = salt()
        return Triple(user, md5(password + salt), salt)
    }

    private suspend fun ping(user: String, password: String): Result<Unit> = runCatching {
        val salt = salt()
        val body = http.get("$BASE_URL/rest/ping.view") {
            parameter("u", user)
            parameter("t", md5(password + salt))
            parameter("s", salt)
            parameter("v", API_VERSION)
            parameter("c", CLIENT_ID)
            parameter("f", "json")
        }.bodyAsText()
        val response = json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
            ?: error("The gateway sent something unexpected.")
        if (response["status"]?.jsonPrimitive?.content != "ok") {
            val message = response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            error(message ?: "Wrong username or password.")
        }
    }

    private fun savePassword(password: String): Boolean {
        val bytes = password.toByteArray(StandardCharsets.UTF_8)
        return if (DesktopPlatform.isWindows) {
            val sealed = DesktopWindowsCrypto.protect(bytes) ?: return false
            persistence.saveString(KEY_PASSWORD_SEALED, Base64.getEncoder().encodeToString(sealed))
            true
        } else {
            DesktopSecretStore.store("BitChord gateway", SECRET_ATTRIBUTES, bytes)
        }
    }

    private fun password(): String? = runCatching {
        if (DesktopPlatform.isWindows) {
            val sealed = persistence.string(KEY_PASSWORD_SEALED).takeIf { it.isNotEmpty() } ?: return null
            DesktopWindowsCrypto.unprotect(Base64.getDecoder().decode(sealed))?.toString(StandardCharsets.UTF_8)
        } else {
            DesktopSecretStore.lookup(SECRET_ATTRIBUTES)?.toString(StandardCharsets.UTF_8)
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun salt(): String {
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
