package casa.archer.metatheatre

internal enum class WeatherControl(val label: String) { TOGGLE("Live weather"), CLEAR("Clear Earth"), REFRESH("Refresh"), DONE("Back to Earth") }
internal object WeatherControls {
    const val HEADER = .2f
    const val ROW = .2f
    fun at(x: Float, y: Float): WeatherControl? {
        if (!x.isFinite() || !y.isFinite() || x < 0f || x >= 1f || y < HEADER || y >= HEADER+ROW) return null
        return WeatherControl.entries[(x*WeatherControl.entries.size).toInt()]
    }
    fun opener(x: Float, y: Float) = x.isFinite() && y.isFinite() && x >= .72f && x <= 1f && y >= 0f && y < HEADER
}
