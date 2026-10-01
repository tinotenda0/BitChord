package com.music.bitchord.ui.replay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork: whether the Replay counts listening in minutes or in hours.
 *
 * One choice for every place Replay shows time — the page, the cards on Library,
 * the stories, the share poster and the stats widget — so they never disagree.
 * Kept in its own small prefs file rather than in AppSettings, to stay out of
 * upstream's settings.
 */
object ReplayUnits {

    enum class Unit { MINUTES, HOURS }

    private val _unit = MutableStateFlow(Unit.MINUTES)
    val unit: StateFlow<Unit> = _unit.asStateFlow()

    /** Read by the formatting helpers, which are plain functions rather than composables. */
    val current: Unit get() = _unit.value

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("replay_units", Context.MODE_PRIVATE).also { p ->
            _unit.value = runCatching { Unit.valueOf(p.getString(KEY, null) ?: "") }.getOrDefault(Unit.MINUTES)
        }
    }

    fun set(unit: Unit) {
        _unit.value = unit
        prefs?.edit()?.putString(KEY, unit.name)?.apply()
        appContext?.let(com.music.bitchord.gateway.ListeningStatsWidget::refresh)
    }

    private const val KEY = "unit"
}
