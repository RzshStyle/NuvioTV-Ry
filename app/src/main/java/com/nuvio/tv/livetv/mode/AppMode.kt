package com.nuvio.tv.livetv.mode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ry fork: the app is split into two halves, picked at the top of the side menu.
 *
 * - [Mode.NUVIO]: Home, Discover, Search (your addons' catalogs, as in Nuvio), Library, Calendar.
 * - [Mode.IPTV]: Live TV (guide), Recordings, Sports, On Demand (the provider's movies and
 *   series) and its own Search over channels, programmes and On Demand.
 *
 * The mode follows the page you're on: opening a Nuvio page (from anywhere, a deep link, the
 * start page) makes it Nuvio, an IPTV page makes it IPTV. Search and Settings belong to both and
 * keep the mode you were in.
 */
object AppMode {
    enum class Mode { NUVIO, IPTV }

    /** The menu entry that switches to the other half (not a page). */
    const val SWITCH_ROUTE = "app_mode_switch"

    private val _mode = MutableStateFlow(Mode.NUVIO)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    val isIptv: Boolean get() = _mode.value == Mode.IPTV

    fun set(mode: Mode) {
        _mode.value = mode
    }

    /** Side-menu page ids (SideMenuPages) that belong to each half. Search is in both. */
    val NUVIO_PAGES = setOf("home", "discover", "search", "library", "calendar")
    val IPTV_PAGES = setOf("livetv", "recordings", "sports", "ondemand", "search")

    private val nuvioRoutePrefixes = listOf("home", "discover", "library", "calendar")
    private val iptvRoutePrefixes = listOf(
        "live_tv", "recordings", "recording_settings", "sports", "on_demand", "sports_multiview"
    )

    /** The half a destination route belongs to, or null when it belongs to both (or neither). */
    fun modeForRoute(route: String?): Mode? {
        if (route == null) return null
        val base = route.substringBefore('?').substringBefore('/')
        return when {
            nuvioRoutePrefixes.any { base == it } -> Mode.NUVIO
            iptvRoutePrefixes.any { base == it } -> Mode.IPTV
            else -> null
        }
    }

    /** Follows the page: called whenever the screen changes. */
    fun follow(route: String?) {
        modeForRoute(route)?.let { _mode.value = it }
    }
}

/** Ry fork: how strictly the two halves are kept apart. */
object AppModeSplit {
    /** Home (Nuvio's) shows no Live TV, Sports or On Demand rows. */
    const val HOME_KEEPS_IPTV_ROWS = false
}
