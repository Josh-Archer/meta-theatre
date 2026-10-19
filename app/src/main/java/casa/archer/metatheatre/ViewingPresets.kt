package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.json.JSONArray
import org.json.JSONObject

/** Relative to an upright viewer, so a saved setup never teleports the user's physical position. */
internal data class ViewingPreset(
    val screen: Pose,
    val size: ScreenSize,
    val chairYaw: Float,
    val latitude: Float,
    val longitude: Float,
    val spin: Float,
    val zoom: Float = 1f,
) {
    fun valid() = ScenePointer.valid(screen) && screen.q.norm() in .99f..1.01f &&
        screen.t.length() in .5f..10f && size.width.isFinite() && size.height.isFinite() &&
        size.width in ScreenSize.MIN_WIDTH..ScreenSize.MAX_WIDTH && size.height > 0f &&
        size.width / size.height in .5f..4f && chairYaw.isFinite() &&
        latitude.isFinite() && latitude in -90f..90f && longitude.isFinite() && longitude in -180f..180f &&
        spin.isFinite() && spin in -180f..180f && zoom.isFinite() && zoom in EarthExploration.MIN_ZOOM..EarthExploration.MAX_ZOOM

    fun screenAt(viewer: Pose): Pose = frame(viewer) * screen

    fun json() = JSONObject().apply {
        put("screen", JSONArray(listOf(screen.t.x, screen.t.y, screen.t.z, screen.q.w, screen.q.x, screen.q.y, screen.q.z)))
        put("width", size.width); put("height", size.height); put("chairYaw", chairYaw)
        put("latitude", latitude); put("longitude", longitude); put("spin", spin)
        put("zoom", zoom)
    }

    companion object {
        const val SLOTS = 3
        fun frame(viewer: Pose) = Pose(viewer.t.copy(), viewer.q.removePitchAndRoll())
        fun capture(viewer: Pose, screen: Pose, size: ScreenSize, yaw: Float, earth: EarthExploration) =
            ViewingPreset(frame(viewer).inverse() * screen, size, yaw, earth.latitude, earth.longitude, earth.spinAt(), earth.zoom)
                .takeIf { it.valid() }

        fun parse(json: JSONObject): ViewingPreset? = runCatching {
            val p = json.getJSONArray("screen")
            require(p.length() == 7)
            fun value(i: Int) = p.getDouble(i).toFloat()
            ViewingPreset(Pose(Vector3(value(0), value(1), value(2)), Quaternion(value(3), value(4), value(5), value(6))),
                ScreenSize(json.getDouble("width").toFloat(), json.getDouble("height").toFloat()),
                json.getDouble("chairYaw").toFloat(), json.getDouble("latitude").toFloat(),
                json.getDouble("longitude").toFloat(), json.getDouble("spin").toFloat(), json.optDouble("zoom", 1.0).toFloat()).takeIf { it.valid() }
        }.getOrNull()
    }
}

internal enum class PresetAction { RESTORE, SAVE, CLEAR, RESET, DONE }
internal data class PresetPress(val action: PresetAction, val slot: Int = 0)

internal object PresetControls {
    const val HEADER = .2f
    const val ROW = .2f
    fun opener(x: Float, y: Float) = x.isFinite() && y.isFinite() && x in 0f..1f && y >= .8f && y < .9f
    fun at(x: Float, y: Float): PresetPress? {
        if (!x.isFinite() || !y.isFinite() || x < 0f || x >= 1f || y < HEADER || y >= 1f) return null
        if (y >= .8f) return PresetPress(if (x < .5f) PresetAction.RESET else PresetAction.DONE)
        val action = when {
            x < .25f -> return null
            x < .5f -> PresetAction.RESTORE
            x < .75f -> PresetAction.SAVE
            else -> PresetAction.CLEAR
        }
        return PresetPress(action, ((y - HEADER) / ROW).toInt().coerceIn(0, ViewingPreset.SLOTS - 1))
    }
}
