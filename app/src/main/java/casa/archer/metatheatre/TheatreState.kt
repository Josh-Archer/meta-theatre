package casa.archer.metatheatre

import android.content.Context
import androidx.core.content.edit
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.json.JSONArray
import org.json.JSONObject

/** Device-local settings and bookmarks. Credentials and playback/poster URLs are never stored here. */
internal class TheatreState(context: Context) {
    private val prefs = context.getSharedPreferences("theatre_state", Context.MODE_PRIVATE)
    var preferredPlexServerId: String?
        get() = prefs.getString("preferred_plex_server", null)
        set(value) { prefs.edit { putString("preferred_plex_server", value) } }
    val clearEarth: Boolean get() = prefs.getBoolean("clear_earth", false)
    fun saveClearEarth(value: Boolean) { prefs.edit { putBoolean("clear_earth", value) } }
    val liveWeatherEnabled: Boolean
        get() = prefs.getBoolean("live_weather_enabled", false)
    fun saveLiveWeatherEnabled(enabled: Boolean) { prefs.edit { putBoolean("live_weather_enabled", enabled) } }
    val useCurrentLocation: Boolean get() = prefs.getBoolean("use_current_location", false)
    val realEarthRotationEnabled: Boolean get() = prefs.getBoolean("real_earth_rotation", true)
    fun saveUseCurrentLocation(enabled: Boolean) { prefs.edit { putBoolean("use_current_location", enabled) } }
    fun saveRealEarthRotation(enabled: Boolean) { prefs.edit { putBoolean("real_earth_rotation", enabled) } }
    val quietCinemaEnabled: Boolean
        get() = prefs.getBoolean("quiet_cinema_enabled", false)
    val history: WatchHistory = runCatching {
        val json = JSONObject(prefs.getString("history", "{}") ?: "{}")
        val array = json.optJSONArray("recent") ?: JSONArray()
        val records = (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                Bookmark(MovieKey(item.getString("server"), item.getString("key")), item.getString("title"),
                    item.optString("subtitle"), item.optString("type"), item.optString("poster"),
                    item.optLong("position"), item.optLong("duration"), item.optLong("played"), item.optBoolean("completed"))
            }.getOrNull()
        }
        val active = json.optJSONObject("active")?.let { MovieKey(it.getString("server"), it.getString("key")) }
        WatchHistory(records, active)
    }.getOrElse { WatchHistory() }

    var currentLocation: TheatreLocation
        get() = runCatching {
            val id = prefs.getString("selected_location", TheatreLocation.EARTH_ORBIT.id) ?: TheatreLocation.EARTH_ORBIT.id
            TheatreLocation.fromId(id)
        }.getOrDefault(TheatreLocation.EARTH_ORBIT)
        set(value) {
            prefs.edit().putString("selected_location", value.id).apply()
        }

    fun saveLocation(location: TheatreLocation) {
        currentLocation = location
    }

    fun sceneSettings(location: TheatreLocation): SceneSettings {
        val default = defaultSceneSettings(location)
        val key = "scene_settings_${location.id}"
        val raw = prefs.getString(key, null)
        if (raw != null) {
            return runCatching {
                SceneSettings.fromJson(JSONObject(raw), default)
            }.getOrDefault(default)
        }
        if (location == TheatreLocation.EARTH_ORBIT && prefs.contains("width") && prefs.contains("height")) {
            val legacySize = runCatching {
                val size = ScreenSize(prefs.getFloat("width", default.screenSize.width), prefs.getFloat("height", default.screenSize.height))
                size.takeIf { it.width.isFinite() && it.height.isFinite() && it.width in ScreenSize.MIN_WIDTH..ScreenSize.MAX_WIDTH &&
                    it.height > 0f && it.width / it.height in 0.5f..4f } ?: default.screenSize
            }.getOrDefault(default.screenSize)
            return default.copy(screenSize = legacySize)
        }
        return default
    }

    fun saveSceneSettings(location: TheatreLocation, settings: SceneSettings) {
        val key = "scene_settings_${location.id}"
        prefs.edit().putString(key, settings.toJson().toString()).apply()
        if (location == TheatreLocation.EARTH_ORBIT) {
            prefs.edit().putFloat("width", settings.screenSize.width).putFloat("height", settings.screenSize.height).apply()
        }
    }

    fun resetSceneSettings(location: TheatreLocation): SceneSettings {
        val key = "scene_settings_${location.id}"
        prefs.edit().remove(key).apply()
        if (location == TheatreLocation.EARTH_ORBIT) {
            prefs.edit().remove("width").remove("height").apply()
        }
        return defaultSceneSettings(location)
    }

    fun screenSize(location: TheatreLocation = currentLocation): ScreenSize {
        return sceneSettings(location).screenSize
    }

    fun saveSize(size: ScreenSize, location: TheatreLocation = currentLocation) {
        val current = sceneSettings(location)
        saveSceneSettings(location, current.copy(screenSize = size))
    }

    fun saveSize(location: TheatreLocation, size: ScreenSize) = saveSize(size, location)

    fun saveScreenPose(pose: Pose, location: TheatreLocation = currentLocation) {
        val current = sceneSettings(location)
        saveSceneSettings(location, current.copy(screenPose = pose))
    }

    fun saveScreenPose(location: TheatreLocation, pose: Pose) = saveScreenPose(pose, location)

    fun saveSeat(seat: SeatSetting, location: TheatreLocation = currentLocation) {
        val current = sceneSettings(location)
        saveSceneSettings(location, current.copy(seat = seat))
    }

    fun saveSeat(location: TheatreLocation, seat: SeatSetting) = saveSeat(seat, location)
    fun viewingPresets(): List<ViewingPreset?> = (0 until ViewingPreset.SLOTS).map { slot ->
        runCatching { prefs.getString("viewing_preset_$slot", null)?.let { ViewingPreset.parse(JSONObject(it)) } }.getOrNull()
    }
    fun saveViewingPreset(slot: Int, preset: ViewingPreset?) {
        require(slot in 0 until ViewingPreset.SLOTS && (preset == null || preset.valid()))
        prefs.edit {
            if (preset == null) remove("viewing_preset_$slot") else putString("viewing_preset_$slot", preset.json().toString())
        }
    }
    fun saveQuietCinemaEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("quiet_cinema_enabled", enabled).apply()
    }
    fun saveHistory() {
        val entries = JSONArray()
        for (entry in history.recent()) entries.put(JSONObject().apply {
            put("server", entry.key.serverId); put("key", entry.key.ratingKey); put("title", entry.title)
            put("subtitle", entry.subtitle); put("type", entry.type); put("poster", entry.posterKey)
            put("position", entry.positionMs); put("duration", entry.durationMs); put("played", entry.lastPlayedAtMs)
            put("completed", entry.completed)
        })
        val json = JSONObject().put("recent", entries)
        history.active?.let { json.put("active", JSONObject().put("server", it.serverId).put("key", it.ratingKey)) }
        prefs.edit().putString("history", json.toString()).apply()
    }
    fun clearHistory() { history.clear(); prefs.edit().remove("history").apply() }
}

internal data class SeatSetting(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val yaw: Float = 0f) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("z", z.toDouble())
        put("yaw", yaw.toDouble())
    }
    companion object {
        fun fromJson(json: JSONObject, default: SeatSetting = SeatSetting()): SeatSetting = runCatching {
            val x = json.optDouble("x", default.x.toDouble()).toFloat()
            val y = json.optDouble("y", default.y.toDouble()).toFloat()
            val z = json.optDouble("z", default.z.toDouble()).toFloat()
            val yaw = json.optDouble("yaw", default.yaw.toDouble()).toFloat()
            if (x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite()) {
                SeatSetting(x, y, z, yaw)
            } else default
        }.getOrDefault(default)
    }
}

internal data class SceneSettings(
    val screenSize: ScreenSize,
    val screenPose: Pose,
    val seat: SeatSetting,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("width", screenSize.width.toDouble())
        put("height", screenSize.height.toDouble())
        put("screenPose", poseToJson(screenPose))
        put("seat", seat.toJson())
    }
    companion object {
        fun fromJson(json: JSONObject, default: SceneSettings): SceneSettings = runCatching {
            val width = json.optDouble("width", default.screenSize.width.toDouble()).toFloat()
            val height = json.optDouble("height", default.screenSize.height.toDouble()).toFloat()
            val size = ScreenSize(width, height).takeIf {
                it.width.isFinite() && it.height.isFinite() &&
                    it.width in ScreenSize.MIN_WIDTH..ScreenSize.MAX_WIDTH &&
                    it.height > 0f && it.width / it.height in 0.5f..4f
            } ?: default.screenSize
            val screenPose = json.optJSONObject("screenPose")?.let { poseFromJson(it, default.screenPose) } ?: default.screenPose
            val seat = json.optJSONObject("seat")?.let { SeatSetting.fromJson(it, default.seat) } ?: default.seat
            SceneSettings(size, screenPose, seat)
        }.getOrDefault(default)
    }
}

private fun poseToJson(pose: Pose): JSONObject = JSONObject().apply {
    put("tx", pose.t.x.toDouble())
    put("ty", pose.t.y.toDouble())
    put("tz", pose.t.z.toDouble())
    put("qx", pose.q.x.toDouble())
    put("qy", pose.q.y.toDouble())
    put("qz", pose.q.z.toDouble())
    put("qw", pose.q.w.toDouble())
}

private fun poseFromJson(json: JSONObject, default: Pose): Pose = runCatching {
    val tx = json.optDouble("tx", default.t.x.toDouble()).toFloat()
    val ty = json.optDouble("ty", default.t.y.toDouble()).toFloat()
    val tz = json.optDouble("tz", default.t.z.toDouble()).toFloat()
    val qx = json.optDouble("qx", default.q.x.toDouble()).toFloat()
    val qy = json.optDouble("qy", default.q.y.toDouble()).toFloat()
    val qz = json.optDouble("qz", default.q.z.toDouble()).toFloat()
    val qw = json.optDouble("qw", default.q.w.toDouble()).toFloat()
    if (tx.isFinite() && ty.isFinite() && tz.isFinite() &&
        qx.isFinite() && qy.isFinite() && qz.isFinite() && qw.isFinite()) {
        Pose(Vector3(tx, ty, tz), Quaternion(w = qw, x = qx, y = qy, z = qz))
    } else default
}.getOrDefault(default)
