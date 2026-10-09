package casa.archer.metatheatre

internal enum class SceneControl(val label: String) {
    TURN_LEFT("Turn left"), TURN_RIGHT("Turn right"), RETURN_MODELS("Return models")
}

internal object SceneControls {
    const val TURN_DEGREES = 45f
    val widths = listOf(.16f, .16f, .12f)
    fun at(x: Float, y: Float): SceneControl? {
        if (!x.isFinite() || !y.isFinite() || y < PosterGrid.FOOTER_START || y > 1f || x < .28f || x >= .72f) return null
        return when { x < .44f -> SceneControl.TURN_LEFT; x < .60f -> SceneControl.TURN_RIGHT; else -> SceneControl.RETURN_MODELS }
    }
}
