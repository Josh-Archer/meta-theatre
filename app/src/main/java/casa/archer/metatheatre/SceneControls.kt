package casa.archer.metatheatre

internal enum class SceneControl(val label: String) {
    TURN_LEFT("Turn left"), TURN_RIGHT("Turn right"), RETURN_MODELS("Return models"), EARTH("Explore Earth")
}

internal object SceneControls {
    const val TURN_DEGREES = 45f
    val widths = listOf(.13f, .13f, .16f, .22f)
    fun center(control: SceneControl): Float = PosterGrid.PREV_X + widths.take(control.ordinal).sum() + widths[control.ordinal] / 2
    fun at(x: Float, y: Float): SceneControl? {
        if (!x.isFinite() || !y.isFinite() || y < PosterGrid.FOOTER_START || y > 1f || x < PosterGrid.PREV_X || x >= PosterGrid.NEXT_X) return null
        var edge = PosterGrid.PREV_X
        return SceneControl.entries.firstOrNull { edge += widths[it.ordinal]; x < edge }
    }
}
