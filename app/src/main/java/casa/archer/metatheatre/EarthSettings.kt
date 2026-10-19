package casa.archer.metatheatre

internal enum class EarthSetting(val label: String) {
    LOCATION("Use my location"), ABOVE_ME("Above me"),
    ZOOM_IN("Closer to Earth"), ZOOM_OUT("Farther from Earth"), ZOOM_RESET("Reset zoom"),
    ROTATION("Real-time rotation"), DONE("Back to Earth"),
}

internal object EarthSettingsControls {
    const val HEADER = .2f
    const val ROW = .15f
    val rows = listOf(listOf(EarthSetting.LOCATION, EarthSetting.ABOVE_ME),
        listOf(EarthSetting.ZOOM_IN, EarthSetting.ZOOM_OUT, EarthSetting.ZOOM_RESET),
        listOf(EarthSetting.ROTATION, EarthSetting.DONE))
    fun opener(x: Float, y: Float) = x.isFinite() && y.isFinite() && x in 0f..1f && y >= .9f && y <= 1f
    fun at(x: Float, y: Float): EarthSetting? {
        if (!x.isFinite() || !y.isFinite() || x < 0f || x >= 1f || y < HEADER || y >= HEADER + ROW * rows.size) return null
        val row = rows[((y-HEADER)/ROW).toInt()]
        return row[(x*row.size).toInt()]
    }
}
