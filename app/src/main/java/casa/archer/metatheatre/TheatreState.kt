package casa.archer.metatheatre

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Device-local settings and bookmarks. Credentials and playback/poster URLs are never stored here. */
internal class TheatreState(context: Context) {
    private val prefs = context.getSharedPreferences("theatre_state", Context.MODE_PRIVATE)
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

    fun screenSize(): ScreenSize = runCatching {
        val size = ScreenSize(prefs.getFloat("width", EarthOrbit.screenWidth), prefs.getFloat("height", EarthOrbit.screenHeight))
        size.takeIf { it.width.isFinite() && it.height.isFinite() && it.width in ScreenSize.MIN_WIDTH..ScreenSize.MAX_WIDTH &&
            it.height > 0f && it.width / it.height in 0.5f..4f } ?: ScreenSize(EarthOrbit.screenWidth, EarthOrbit.screenHeight)
    }.getOrElse { ScreenSize(EarthOrbit.screenWidth, EarthOrbit.screenHeight) }

    fun saveSize(size: ScreenSize) { prefs.edit().putFloat("width", size.width).putFloat("height", size.height).apply() }
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
