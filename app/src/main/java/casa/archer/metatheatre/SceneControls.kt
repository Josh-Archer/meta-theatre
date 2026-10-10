package casa.archer.metatheatre

internal enum class SceneControl(val label: String) {
    TURN_LEFT("Turn left"), TURN_RIGHT("Turn right"), RETURN_MODELS("Return models"), EARTH("Earth")
}

internal object SceneControls {
    const val TURN_DEGREES = 45f
    val widths = listOf(.13f, .13f, .10f, .08f)
    fun center(control: SceneControl): Float = .28f + widths.take(control.ordinal).sum() + widths[control.ordinal] / 2
    fun at(x: Float, y: Float): SceneControl? {
        if (!x.isFinite() || !y.isFinite() || y < PosterGrid.FOOTER_START || y > 1f || x < .28f || x >= .72f) return null
        var edge = .28f
        return SceneControl.entries.firstOrNull { edge += widths[it.ordinal]; x < edge }
    }
}
