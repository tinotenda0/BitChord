package com.music.bitchord.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.listentogether.ListenTogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Connect output's volume, shared with the account's other devices.
 *
 * Only on the device that is playing: it says what its music volume is
 * whenever that changes (its own buttons, a remote, anything), and turns it to
 * whatever another device asks for. It reports the volume it actually ended up
 * at rather than echoing the request, so a remote's slider settles on one of
 * this device's real steps and every device agrees about where it is.
 *
 * Whether other devices may change it is this device's own setting
 * ([ListenTogether.remoteVolumeAllowed]). Off, it still reports, so a remote can
 * show the level, but the server refuses changes from anyone else.
 */
class ConnectVolume(private val context: Context, private val scope: CoroutineScope) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val stream = AudioManager.STREAM_MUSIC
    private var job: Job? = null
    private var observer: ContentObserver? = null

    /** What was last said, so the observer's many unrelated wake-ups say nothing. */
    private var lastReported: Triple<Int, Boolean, Int>? = null

    private val steps: Int get() = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    private val lowest: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(stream) else 0

    private fun isOutput(state: ListenTogether.State) = state.isConnect && !state.isRemote

    fun start() {
        // Settings.System changes whenever a stream volume does, which is the
        // one signal that covers every way it can be changed.
        val watch = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (isOutput(ListenTogether.state.value)) report()
            }
        }
        observer = watch
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, watch)

        job = scope.launch {
            combine(
                ListenTogether.state
                    .map { Triple(isOutput(it), it.playback.volume, it.connection == ListenTogether.Connection.LIVE) }
                    .distinctUntilChanged(),
                ListenTogether.remoteVolumeAllowed,
            ) { party, allowed -> party to allowed }
                .collect { (party, allowed) ->
                    val (output, wanted, live) = party
                    if (!output || !live) {
                        lastReported = null
                        return@collect
                    }
                    val shared = ListenTogether.state.value.playback
                    // Not said yet (a new output, or a server that has just
                    // restarted), or said differently from how it is now.
                    if (wanted == null || shared.volumeControl != allowed || shared.volumeSteps != steps) {
                        lastReported = null
                        report()
                        return@collect
                    }
                    // Another device asked for a level this device is not at.
                    if (allowed && abs(wanted - current()) > 0.5 / steps) apply(wanted)
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        observer?.let(context.contentResolver::unregisterContentObserver)
        observer = null
    }

    private fun current(): Double = audio.getStreamVolume(stream).toDouble() / steps

    private fun apply(volume: Double) {
        val index = (volume * steps).roundToInt().coerceIn(lowest, steps)
        if (index == audio.getStreamVolume(stream)) return
        // Refused in Do Not Disturb on some devices; the report that follows
        // then puts every remote back where this device really is.
        runCatching { audio.setStreamVolume(stream, index, 0) }
            .onFailure { Log.w(TAG, "could not set the volume: ${it.message}") }
        report()
    }

    private fun report() {
        val allowed = ListenTogether.remoteVolumeAllowed.value
        val now = Triple(audio.getStreamVolume(stream), allowed, steps)
        if (now == lastReported) return
        lastReported = now
        ListenTogether.reportVolume(now.first.toDouble() / now.third, allowed, now.third)
    }

    private companion object {
        const val TAG = "ConnectVolume"
    }
}
