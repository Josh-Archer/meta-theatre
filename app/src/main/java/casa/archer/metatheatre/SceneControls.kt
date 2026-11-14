package casa.archer.metatheatre

internal enum class SceneControl(val label: String) {
    TURN_LEFT("Turn left"), TURN_RIGHT("Turn right"), RETURN_MODELS("Return models"), EARTH("Explore Earth"), SCENES("Scenes")
}

internal object SceneControls {
    const val TURN_DEGREES = 45f
    // Five controls fill the footer span between Previous and Next (0.64).
    val widths = listOf(.11f, .11f, .15f, .16f, .11f)
    fun center(control: SceneControl): Float = PosterGrid.PREV_X + widths.take(control.ordinal).sum() + widths[control.ordinal] / 2
    fun at(x: Float, y: Float): SceneControl? {
        if (!x.isFinite() || !y.isFinite() || y < PosterGrid.FOOTER_START || y > 1f || x < PosterGrid.PREV_X || x >= PosterGrid.NEXT_X) return null
        var edge = PosterGrid.PREV_X
        return SceneControl.entries.firstOrNull { edge += widths[it.ordinal]; x < edge }
    }
}

internal sealed interface ScenePickerAction {
    data class SelectLocation(val location: TheatreLocation) : ScenePickerAction
    data object ResetDefault : ScenePickerAction
    data object Done : ScenePickerAction
    data object LaunchStarship : ScenePickerAction
    data object StopStarship : ScenePickerAction
}

internal object ScenePickerControls {
    const val HEADER = .22f
    const val ROW_HEIGHT = .18f
    const val ENV_ROW_TOP = HEADER
    const val ENV_ROW_BOTTOM = ENV_ROW_TOP + ROW_HEIGHT
    const val ACTION_ROW_TOP = ENV_ROW_BOTTOM + .04f
    const val ACTION_ROW_BOTTOM = ACTION_ROW_TOP + ROW_HEIGHT
    const val FLIGHT_ROW_TOP = ACTION_ROW_BOTTOM + .02f
    const val FLIGHT_ROW_BOTTOM = FLIGHT_ROW_TOP + ROW_HEIGHT

    fun at(x: Float, y: Float): ScenePickerAction? {
        if (!x.isFinite() || !y.isFinite() || x < 0f || x >= 1f) return null
        if (y in ENV_ROW_TOP..ENV_ROW_BOTTOM) {
            val entries = TheatreLocation.entries
            val index = (x * entries.size).toInt().coerceIn(0, entries.lastIndex)
            return ScenePickerAction.SelectLocation(entries[index])
        }
        if (y in ACTION_ROW_TOP..ACTION_ROW_BOTTOM) {
            return if (x < 0.5f) ScenePickerAction.ResetDefault else ScenePickerAction.Done
        }
        if (y >= FLIGHT_ROW_TOP && y < FLIGHT_ROW_BOTTOM) {
            return if (x < .7f) ScenePickerAction.LaunchStarship else ScenePickerAction.StopStarship
        }
        return null
    }
}
