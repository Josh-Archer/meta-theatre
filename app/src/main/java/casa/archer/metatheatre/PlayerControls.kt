package casa.archer.metatheatre

internal enum class PlayerControl(val label: String) {
    BACK("−10s"), TOGGLE("Play"), FORWARD("+10s"), FORWARD_MORE("+30s"), LIBRARY("Library"),
    SMALLER("Smaller"), LARGER("Larger"), ORIGINAL("Original"), UHD("4K"), HD("1080p"),
    AUDIO("Audio"), SUBTITLES("Subtitles"), SETTINGS("Settings"), QUIET("Quiet cinema"), EARTH("Explore Earth"), RECONNECT("Reconnect"),
}

internal sealed interface PlayerTarget {
    data class Button(val control: PlayerControl) : PlayerTarget
    data object Seek : PlayerTarget
}

internal data class PlayerHitRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun fraction(x: Float) = ((x - left) / (right - left)).coerceIn(0f, 1f)
}

/** Bounds come from the rendered Compose layout, so ray selection follows the visible buttons. */
internal class PlayerHitTargets {
    private val bounds = mutableMapOf<PlayerTarget, PlayerHitRect>()
    private var seekTrack: PlayerHitRect? = null
    @Synchronized fun put(target: PlayerTarget, rect: PlayerHitRect, horizontalInset: Float = 0f) {
        bounds[target] = rect
        if (target == PlayerTarget.Seek) seekTrack = rect.copy(left = rect.left + horizontalInset, right = rect.right - horizontalInset)
    }
    @Synchronized fun at(x: Float, y: Float): PlayerTarget? = bounds.entries.firstOrNull { it.value.contains(x, y) }?.key
    @Synchronized fun seekFraction(x: Float): Float? = seekTrack?.fraction(x)
    @Synchronized fun clear() { bounds.clear(); seekTrack = null }
    @Synchronized fun remove(target: PlayerTarget) { bounds.remove(target); if (target == PlayerTarget.Seek) seekTrack = null }
}

/** Full transparent panel with controls anchored inside the movie's lower edge. */
internal data class PlayerPlacement(val width: Float, val height: Float, val up: Float) {
    fun contentHeight(expanded: Boolean) = height * if (expanded) 1f else COMPACT_DP / HEIGHT_DP
    fun contentUp(expanded: Boolean) = up - (height - contentHeight(expanded)) * .5f
    fun contains(y: Float, expanded: Boolean) = y in (if (expanded) 0f else 1f - COMPACT_DP / HEIGHT_DP)..1f
    companion object {
        const val WIDTH_DP = 960f
        const val HEIGHT_DP = 640f
        const val COMPACT_DP = 240f
        fun inside(screen: ScreenSize): PlayerPlacement {
            val width = minOf(screen.width * .9f, 2.8f, screen.height * .84f * WIDTH_DP / HEIGHT_DP)
            val height = width * HEIGHT_DP / WIDTH_DP
            return PlayerPlacement(width, height, -screen.height * .46f + height * .5f)
        }
    }
}

/** Inactivity uses a monotonic clock. Hover without movement does not postpone hiding. */
internal class PlayerAutoHide {
    var visible = false
        private set
    private var enabled = false
    private var lastInteractionMs = 0L
    private val selecting = mutableSetOf<Long>()

    fun setWatching(watching: Boolean, nowMs: Long) {
        if (enabled == watching) return
        enabled = watching
        selecting.clear()
        visible = watching
        lastInteractionMs = nowMs
    }

    fun interact(nowMs: Long) {
        if (!enabled) return
        lastInteractionMs = nowMs
        visible = true
    }

    fun select(source: Long, down: Boolean, nowMs: Long) {
        if (!enabled) return
        if (down) selecting.add(source) else if (!selecting.remove(source)) return
        interact(nowMs)
    }

    fun tick(nowMs: Long, needsAttention: Boolean = false) {
        // A failed stream can leave the video black. Keep its explanation and exit visible.
        if (needsAttention) interact(nowMs)
        if (enabled && selecting.isEmpty() && nowMs - lastInteractionMs >= IDLE_MS) visible = false
    }

    companion object {
        const val IDLE_MS = 5_000L
        const val SLIDE_MS = 280L
    }
}

internal data class ScreenSize(val width: Float, val height: Float) {
    fun scaled(x: Float, y: Float) = ScreenSize(width * x, height * y)

    fun resized(factor: Float): ScreenSize {
        if (!factor.isFinite() || factor <= 0f || width <= 0f || height <= 0f) return this
        val nextWidth = (width * factor).coerceIn(MIN_WIDTH, MAX_WIDTH)
        return ScreenSize(nextWidth, height * (nextWidth / width))
    }

    companion object {
        const val MIN_WIDTH = 1.2f
        const val MAX_WIDTH = 8f
        const val GROW = 1.2f
        const val SHRINK = 1f / GROW
    }
}
