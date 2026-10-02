package com.music.bitchord.data.listentogether

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.music.bitchord.BuildConfig
import java.security.MessageDigest

/**
 * Which phone this is, and which build of the app on it, for Connect.
 *
 * PixelPlayer named a device with an id made up fresh per process, so every
 * restart, reinstall and reconnect arrived as a new device and the picker filled
 * up with copies of the same phone. This is the opposite: [key] is derived from
 * Android's `ANDROID_ID`, which stays put across restarts and reinstalls and is
 * shared by every app signed with the same key on the same phone and user. The
 * stable and dev builds are both signed with the family key, so they agree on
 * [key] and differ only in [app]: one phone, two builds of the app on it, and
 * never two phones. A factory reset is a new phone, which is fair.
 *
 * `ANDROID_ID` itself never leaves the phone. What is sent is a hash of it,
 * salted with this app's name so it matches nothing any other app could send.
 */
object DeviceIdentity {

    @Volatile
    private var cachedKey: String? = null

    /** The phone. The same for every build of this app on it. */
    fun key(context: Context): String = cachedKey ?: derive(context).also { cachedKey = it }

    /** The build: `prod` or `dev`. Two builds on one phone are two members of it. */
    val app: String get() = BuildConfig.FLAVOR.ifBlank { "prod" }

    /** What the picker calls this phone: the name its owner gave it, else its model. */
    fun name(context: Context): String {
        val given = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()
        return given?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .let { parts ->
                    // "Google Pixel 9", but not "samsung SM-S911B SM-S911B".
                    if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) parts[1]
                    else parts.joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                }
                .ifBlank { "Android device" }
    }

    @SuppressLint("HardwareIds")
    private fun derive(context: Context): String {
        // Only ever null on a broken ROM. Falling back to the model would merge
        // every phone of that model into one, which is the worse of the two
        // mistakes, so a stand-in that is at least unique to this install is used.
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: return fallback(context)
        return hash("bitchord-device:$androidId")
    }

    private fun fallback(context: Context): String {
        val prefs = context.getSharedPreferences("bitchord_device_identity", Context.MODE_PRIVATE)
        prefs.getString("key", null)?.let { return it }
        val made = hash("bitchord-device:" + java.util.UUID.randomUUID())
        prefs.edit().putString("key", made).apply()
        return made
    }

    private fun hash(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
}
