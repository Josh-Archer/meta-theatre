package casa.archer.metatheatre

internal enum class SceneControl(val label: String) {
    TURN_LEFT("Turn left"), TURN_RIGHT("Turn right"), RETURN_MODELS("Return models"), EARTH("Earth"), SCENES("Scenes")
}

internal object SceneControls {
    const val TURN_DEGREES = 45f
    val widths = listOf(.10f, .10f, .09f, .075f, .075f)
    fun center(control: SceneControl): Float = .28f + widths.take(control.ordinal).sum() + widths[control.ordinal] / 2
    fun at(x: Float, y: Float): SceneControl? {
        if (!x.isFinite() || !y.isFinite() || y < PosterGrid.FOOTER_START || y > 1f || x < .28f || x >= .72f) return null
        var edge = .28f
        return SceneControl.entries.firstOrNull { edge += widths[it.ordinal]; x < edge }
    }
}

internal sealed interface ScenePickerAction {
    data class SelectLocation(val location: TheatreLocation) : ScenePickerAction
    data object ResetDefault : ScenePickerAction
    data object Done : ScenePickerAction
}

internal object ScenePickerControls {
    const val HEADER = .22f
    const val ROW_HEIGHT = .18f
    const val ENV_ROW_TOP = HEADER
    const val ENV_ROW_BOTTOM = ENV_ROW_TOP + ROW_HEIGHT
    const val ACTION_ROW_TOP = ENV_ROW_BOTTOM + .04f
    const val ACTION_ROW_BOTTOM = ACTION_ROW_TOP + ROW_HEIGHT

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
        return null
    }
}
