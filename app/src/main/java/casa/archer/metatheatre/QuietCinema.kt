package casa.archer.metatheatre

/** Preference and watch-state gate for the optional quiet cinema behavior. */
internal class QuietCinema(
    private val state: TheatreState,
    private val onActiveChanged: (Boolean) -> Unit,
) {
    var enabled: Boolean = state.quietCinemaEnabled
        private set

    var watching: Boolean = false
        private set

    val active: Boolean get() = enabled && watching

    fun setPreferenceEnabled(enabled: Boolean) {
        state.saveQuietCinemaEnabled(enabled)
        this.enabled = enabled
        publishIfChanged()
    }

    fun setWatching(watching: Boolean) {
        this.watching = watching
        publishIfChanged()
    }

    private fun publishIfChanged() {
        val next = active
        if (next != publishedActive) {
            publishedActive = next
            onActiveChanged(next)
        }
    }

    private var publishedActive = false
}

/** Relative strengths applied to SDK PBR lighting; custom planet shaders keep their own lighting. */
internal data class SceneLightLevels(
    val ambient: Float,
    val sun: Float,
    val environment: Float,
)

internal object QuietCinemaLighting {
    private const val QUIET_SCALE = 0.32f

    fun levels(quiet: Boolean): SceneLightLevels = if (quiet) {
        SceneLightLevels(QUIET_SCALE, QUIET_SCALE, QUIET_SCALE)
    } else {
        SceneLightLevels(1f, 1f, 1f)
    }
}

/** Monotonic elapsed time that freezes while paused and resumes without shifting its phase. */
internal class PausableElapsedClock(private val nowMillis: () -> Long) {
    private var startedAtMillis = nowMillis()
    private var pausedAtMillis: Long? = null
    private var totalPausedMillis = 0L

    val isPaused: Boolean get() = pausedAtMillis != null

    fun reset() {
        val now = nowMillis()
        startedAtMillis = now
        totalPausedMillis = 0L
        pausedAtMillis = if (isPaused) now else null
    }

    fun setPaused(paused: Boolean) {
        if (paused == isPaused) return
        val now = nowMillis()
        if (paused) {
            pausedAtMillis = now
        } else {
            totalPausedMillis += now - checkNotNull(pausedAtMillis)
            pausedAtMillis = null
        }
    }

    fun elapsedMillis(): Long {
        val effectiveNow = pausedAtMillis ?: nowMillis()
        return (effectiveNow - startedAtMillis - totalPausedMillis).coerceAtLeast(0L)
    }

    fun elapsedSeconds(): Double = elapsedMillis() / 1000.0
}
