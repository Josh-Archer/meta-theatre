package casa.archer.metatheatre

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PlexServer(val name: String, val uri: String, val token: String, val id: String = uri) {
    override fun toString(): String = "PlexServer($name)"
}

data class PlexSection(
    val key: String,
    val title: String,
    val type: String,
    val poster: String = "",
) {
    override fun toString(): String = "PlexSection($type $title)"
}

data class PlexItem(
    val ratingKey: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val poster: String = "",
    val posterKey: String = "",
    val viewOffsetMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastViewedAtMs: Long = 0L,
) {
    override fun toString(): String = "PlexItem($type $title)"
}

data class Playable(
    val title: String,
    val directUrl: String,
    val hlsUrl: String,
    val headers: Map<String, String>,
    val subtitle: String = "",
    val hdHlsUrl: String = hlsUrl,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val audioStreams: List<PlexPlaybackStream> = emptyList(),
    val subtitleStreams: List<PlexPlaybackStream> = emptyList(),
    val stereo: DetectedStereo = DetectedStereo.MONO,
    val sourceKind: SourceKind = SourceKind.PLEX,
    val sourceItemId: String = "",
) {
    override fun toString(): String = "Playable($title)"
}

data class PlexPlaybackStream(
    val id: String,
    val language: String = "",
    val languageCode: String = "",
    val title: String = "",
    val codec: String = "",
    val selected: Boolean = false,
)

enum class PlexFailure { ACCOUNT_AUTH, SERVER_AUTH, CONNECTION, SERVER, MISSING, OTHER }
class PlexException(message: String, val failure: PlexFailure = PlexFailure.OTHER) : Exception(message)

/**
 * Plex pin login and library reads. Playback URLs carry the token, so nothing
 * in this class logs a URL or a token.
 */
class PlexClient(context: Context, private val accountEndpoint: String = PLEX) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val clientId: String = prefs.getString(KEY_CLIENT, null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString(KEY_CLIENT, it).commit()) { "Couldn't save the Plex device identity" }
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            check(prefs.edit().putString(KEY_TOKEN, value).commit()) { "Couldn't save the Plex sign-in" }
        }

    fun clearToken() {
        check(prefs.edit().remove(KEY_TOKEN).commit()) { "Couldn't clear the Plex sign-in" }
    }

    fun requestPin(): Pin {
        // strong=true is the long code for an auth URL. plex.tv/link wants the short pin.
        val body = request("POST", "$accountEndpoint/api/v2/pins", token = null)
        val json = JSONObject(body)
        val code = json.getString("code")
        Log.i(TAG, "pin code length ${code.length}")
        return Pin(id = json.getLong("id").toString(), code = code)
    }

    fun pollPin(pin: Pin): String? {
        val code = URLEncoder.encode(pin.code, "UTF-8")
        val body = request("GET", "$accountEndpoint/api/v2/pins/${pin.id}?code=$code", token = null)
        val json = JSONObject(body)
        if (json.isNull("authToken")) return null
        return json.optString("authToken").takeIf { it.isNotBlank() }
    }

    fun servers(): List<PlexServer> {
        val account = token ?: throw PlexException("Link your Plex account to continue.", PlexFailure.ACCOUNT_AUTH)
        val body = request(
            "GET",
            "$accountEndpoint/api/v2/resources?includeHttps=1&includeRelay=1",
            account,
        )
        val devices = if (body.trimStart().startsWith("[")) {
            JSONArray(body)
        } else {
            array(JSONObject(body), "Device")
        }
        val found = mutableListOf<PlexServer>()
        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            val provides = device.optString("provides")
            if (!provides.contains("server")) continue
            val name = device.optString("name").ifBlank { "Plex" }
            val access = device.optString("accessToken").ifBlank { account }
            val connections = array(device, "connections")
                .let { array ->
                    (0 until array.length()).map { array.getJSONObject(it) }
                        .sortedBy { connectionScore(it) }
                }
            val uri = firstReachable(connections, access)
            if (uri != null) found.add(PlexServer(name, uri, access, device.optString("clientIdentifier").ifBlank { uri }))
        }
        return found.sortedBy { it.name.lowercase() }
    }

    fun sections(server: PlexServer): List<PlexSection> {
        val body = request("GET", "${server.uri.trimEnd('/')}/library/sections", server.token)
        val directory = array(JSONObject(body).getJSONObject("MediaContainer"), "Directory")
        return (0 until directory.length()).mapNotNull { index ->
            val item = directory.getJSONObject(index)
            val type = item.optString("type")
            if (type != "show" && type != "movie") return@mapNotNull null
            PlexSection(
                item.getString("key"),
                item.optString("title").ifBlank { type },
                type,
                posterUrl(server, item),
            )
        }
    }

    fun library(server: PlexServer, section: PlexSection): List<PlexItem> {
        val typeFilter = if (section.type == "show") "2" else "1"
        return pages(server, "/library/sections/${section.key}/all?type=$typeFilter")
            .sortedWith(
                compareByDescending<JSONObject> { it.optLong("addedAt") }
                    .thenBy { it.optString("title").lowercase() },
            )
            .map { meta ->
                PlexItem(
                    ratingKey = meta.getString("ratingKey"),
                    title = meta.optString("title").ifBlank { "Untitled" },
                    subtitle = shelfSubtitle(meta),
                    type = meta.optString("type"),
                    poster = posterUrl(server, meta),
                    posterKey = posterKey(meta),
                    viewOffsetMs = meta.optLong("viewOffset"),
                    durationMs = meta.optLong("duration"),
                    lastViewedAtMs = meta.optLong("lastViewedAt") * 1000L,
                )
            }
    }

    fun children(server: PlexServer, ratingKey: String): List<PlexItem> {
        return pages(server, "/library/metadata/$ratingKey/children")
            .sortedWith(compareBy({ it.optInt("parentIndex") }, { it.optInt("index") }))
            .map { meta ->
                PlexItem(
                    ratingKey = meta.getString("ratingKey"),
                    title = meta.optString("title").ifBlank { "Untitled" },
                    subtitle = shelfSubtitle(meta),
                    type = meta.optString("type"),
                    poster = posterUrl(server, meta),
                    posterKey = posterKey(meta),
                    viewOffsetMs = meta.optLong("viewOffset"),
                    durationMs = meta.optLong("duration"),
                    lastViewedAtMs = meta.optLong("lastViewedAt") * 1000L,
                )
            }
    }

    fun recentlyViewed(server: PlexServer): List<PlexItem> {
        val items = mutableListOf<PlexItem>()
        for (type in listOf(1, 4)) {
            val body = request("GET", "${server.uri.trimEnd('/')}/library/all?type=$type&sort=lastViewedAt:desc&X-Plex-Container-Size=50", server.token)
            val array = array(JSONObject(body).getJSONObject("MediaContainer"), "Metadata")
            for (index in 0 until array.length()) {
                val meta = array.getJSONObject(index)
                if (meta.optLong("lastViewedAt") <= 0) continue
                items.add(PlexItem(meta.getString("ratingKey"), meta.optString("title"), shelfSubtitle(meta),
                    meta.optString("type"), posterUrl(server, meta), posterKey(meta),
                    meta.optLong("viewOffset"), meta.optLong("duration"), meta.optLong("lastViewedAt") * 1000L))
            }
        }
        return items.sortedByDescending { it.lastViewedAtMs }.take(50)
    }

    fun poster(server: PlexServer, key: String): String {
        if (!key.startsWith("/") || key.contains("X-Plex-Token", ignoreCase = true)) return ""
        return posterUrl(server, JSONObject().put("thumb", key))
    }

    fun playable(server: PlexServer, item: PlexItem): Playable {
        val body = request(
            "GET",
            "${server.uri.trimEnd('/')}/library/metadata/${item.ratingKey}?checkFiles=1",
            server.token,
        )
        val metadata = array(JSONObject(body).getJSONObject("MediaContainer"), "Metadata")
        if (metadata.length() == 0) throw PlexException("Plex did not return ${item.title}")
        val meta = metadata.getJSONObject(0)
        val media = array(meta, "Media")
        if (media.length() == 0) throw PlexException("${item.title} has no file")
        val versions = (0 until media.length()).mapNotNull { index ->
            val version = media.getJSONObject(index)
            val parts = array(version, "Part")
            if (parts.length() == 0 || parts.getJSONObject(0).optString("key").isBlank()) return@mapNotNull null
            PlexMediaVersion(index, version.optInt("width"), version.optInt("height"), version.optInt("bitrate"))
        }
        val version = preferredMovieVersion(versions) ?: throw PlexException("${item.title} has no file")
        val part = array(media.getJSONObject(version.index), "Part")
        if (part.length() == 0) throw PlexException("${item.title} has no file")
        val selectedPart = part.getJSONObject(0)
        if (listOf("exists", "accessible").any { selectedPart.has(it) && !selectedPart.isNull(it) &&
                !selectedPart.optBoolean(it, true) }) {
            throw PlexException("File unavailable on ${server.name}. Choose another library.", PlexFailure.MISSING)
        }
        val key = part.getJSONObject(0).optString("key")
        if (key.isBlank()) throw PlexException("${item.title} has no file")
        val streams = array(part.getJSONObject(0), "Stream")
        val audioStreams = playbackStreams(streams, 2)
        val subtitleStreams = playbackStreams(streams, 3)
        val base = server.uri.trimEnd('/')
        val headers = headers(server.token)
        // Only numeric stream metadata: playback URLs and credentials stay private.
        Log.i(TAG, "source video ${version.width}x${version.height} bitrate ${version.bitrate} versions ${versions.size} selected ${version.index}")
        return Playable(
            title = item.title,
            directUrl = withToken(base + key, server.token),
            hlsUrl = plexMovieHlsUrl(server, item.ratingKey, clientId, version.index, MovieQuality.UHD),
            hdHlsUrl = plexMovieHlsUrl(server, item.ratingKey, clientId, version.index, MovieQuality.HD),
            headers = headers,
            subtitle = item.subtitle,
            sourceWidth = version.width,
            sourceHeight = version.height,
            audioStreams = audioStreams,
            subtitleStreams = subtitleStreams,
        )
    }

    private fun playbackStreams(streams: JSONArray, streamType: Int): List<PlexPlaybackStream> =
        (0 until streams.length()).mapNotNull { index ->
            val stream = streams.optJSONObject(index) ?: return@mapNotNull null
            if (stream.optInt("streamType") != streamType) return@mapNotNull null
            val id = stream.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlexPlaybackStream(
                id = id,
                language = stream.optString("language"),
                languageCode = stream.optString("languageCode"),
                title = stream.optString("title"),
                codec = stream.optString("codec"),
                selected = stream.optString("selected") == "1" || stream.optBoolean("selected"),
            )
        }

    private fun pages(server: PlexServer, path: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val seenRatingKeys = mutableSetOf<String>()
        var start = 0L
        var requests = 0
        var declaredTotal: Long? = null
        while (true) {
            if (requests >= MAX_PAGE_REQUESTS) {
                throw PlexException("Plex library is too large to load safely")
            }
            requests++
            val join = if (path.contains("?")) "&" else "?"
            val body = request(
                "GET",
                "${server.uri.trimEnd('/')}$path${join}X-Plex-Container-Start=$start&X-Plex-Container-Size=$PAGE_SIZE",
                server.token,
            )
            val container = try {
                JSONObject(body).getJSONObject("MediaContainer")
            } catch (_: Exception) {
                throw PlexException("Plex returned a malformed library page")
            }
            val items = pageMetadata(container)
            val total = container.opt("totalSize")?.let { value ->
                when (value) {
                    is Number -> value.toLong().takeIf { it >= 0L }
                    is String -> value.toLongOrNull()?.takeIf { it >= 0L }
                    else -> null
                }
            }
            if (total != null) declaredTotal = total

            if (items.isEmpty()) {
                if (declaredTotal != null && start < declaredTotal) {
                    throw PlexException("Plex stopped returning library items before the reported total")
                }
                break
            }

            val pageKeys = items.map { it.getString("ratingKey") }
            if (pageKeys.none { it !in seenRatingKeys }) {
                throw PlexException("Plex repeated a library page; pagination stopped before all items were loaded")
            }
            seenRatingKeys.addAll(pageKeys)
            out.addAll(items)
            start += items.size

            if (declaredTotal != null && start >= declaredTotal) break
            if (items.size < PAGE_SIZE && declaredTotal == null) break
        }
        return out
    }

    private fun pageMetadata(container: JSONObject): List<JSONObject> {
        if (!container.has("Metadata") || container.isNull("Metadata")) return emptyList()
        val value = container.opt("Metadata")
        val items = when (value) {
            is JSONArray -> value
            is JSONObject -> JSONArray().put(value)
            else -> throw PlexException("Plex returned malformed library metadata")
        }
        return try {
            (0 until items.length()).map { index ->
                items.getJSONObject(index).also { item ->
                    if (item.optString("ratingKey").isBlank()) {
                        throw PlexException("Plex returned library metadata without a rating key")
                    }
                }
            }
        } catch (error: PlexException) {
            throw error
        } catch (_: Exception) {
            throw PlexException("Plex returned malformed library metadata")
        }
    }

    private fun firstReachable(connections: List<JSONObject>, token: String): String? {
        var lastError: String? = null
        for (connection in connections.take(4)) {
            val uri = connection.optString("uri")
            if (uri.isBlank()) continue
            try {
                val code = requestCode("GET", "${uri.trimEnd('/')}/identity", token, timeoutMs = 5000)
                if (code in 200..299) return uri.trimEnd('/')
                lastError = "HTTP $code"
            } catch (error: Exception) {
                lastError = "connection failed"
            }
        }
        if (lastError != null) Log.i(TAG, "No reachable connection ($lastError)")
        return null
    }

    private fun connectionScore(connection: JSONObject): Int {
        val local = connection.optBoolean("local")
        val relay = connection.optBoolean("relay")
        val direct = connection.optString("uri").contains("plex.direct")
        return when {
            local && direct -> 0
            local -> 1
            !relay && direct -> 2
            !relay -> 3
            else -> 4
        }
    }

    private fun request(method: String, url: String, token: String?): String {
        val connection = open(method, url, token, 20_000)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val accountRequest = url.startsWith(accountEndpoint.trimEnd('/') + "/")
                throw when {
                    code in listOf(401, 403) && accountRequest -> PlexException(
                        "Your Plex account needs to be linked again. Your saved progress is kept.", PlexFailure.ACCOUNT_AUTH)
                    code in listOf(401, 403) -> PlexException(
                        "Plex server access changed. Reconnect to refresh access.", PlexFailure.SERVER_AUTH)
                    code == 429 || code >= 500 -> PlexException(
                        "Plex is temporarily unavailable (HTTP $code). Reconnect to try again.", PlexFailure.SERVER)
                    else -> PlexException("Plex returned HTTP $code.")
                }
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: java.io.IOException) {
            throw PlexException("Couldn't connect to Plex. Your sign-in is still saved.", PlexFailure.CONNECTION)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestCode(method: String, url: String, token: String?, timeoutMs: Int): Int {
        val connection = open(method, url, token, timeoutMs)
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, url: String, token: String?, timeoutMs: Int): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
        }
        headers(token).forEach { (key, value) -> connection.setRequestProperty(key, value) }
        return connection
    }

    private fun headers(token: String?): Map<String, String> {
        val values = linkedMapOf(
            "Accept" to "application/json",
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Version" to "1.0.0",
            "X-Plex-Platform" to "Android",
            "X-Plex-Platform-Version" to "14",
            "X-Plex-Device" to "Quest 3",
            "X-Plex-Device-Name" to "Meta Theatre",
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Provides" to "player",
        )
        if (!token.isNullOrBlank()) values["X-Plex-Token"] = token
        return values
    }

    private fun withToken(url: String, token: String): String {
        val join = if (url.contains("?")) "&" else "?"
        return url + join + "X-Plex-Token=" + URLEncoder.encode(token, "UTF-8")
    }

    private fun posterKey(meta: JSONObject): String = listOf("thumb", "parentThumb", "grandparentThumb", "art", "composite")
        .firstNotNullOfOrNull { key -> meta.optString(key).takeIf { it.isNotBlank() } }.orEmpty()

    private fun posterUrl(server: PlexServer, meta: JSONObject): String {
        val path = posterKey(meta).ifBlank { return "" }
        val absolute = if (path.startsWith("http")) path else server.uri.trimEnd('/') + path
        val sized = if (absolute.contains("?")) "$absolute&width=480" else "$absolute?width=480"
        return withToken(sized, server.token)
    }

    private fun shelfSubtitle(meta: JSONObject): String {
        return when (meta.optString("type")) {
            "show" -> {
                val seasons = meta.optInt("childCount")
                when {
                    seasons == 1 -> "1 season"
                    seasons > 1 -> "$seasons seasons"
                    else -> meta.optInt("year").takeIf { it > 0 }?.toString().orEmpty()
                }
            }
            "season" -> {
                val count = meta.optInt("leafCount")
                when {
                    count == 1 -> "1 episode"
                    count > 1 -> "$count episodes"
                    else -> ""
                }
            }
            "episode" -> listOf(meta.optString("grandparentTitle"), episodeLabel(meta))
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
            else -> listOfNotNull(
                meta.optInt("year").takeIf { it > 0 }?.toString(),
                runtimeLabel(meta.optLong("duration")).takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
        }
    }

    private fun runtimeLabel(ms: Long): String {
        if (ms <= 0L) return ""
        val minutes = (ms / 60000L).toInt()
        if (minutes <= 0) return ""
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
    }

    private fun episodeLabel(meta: JSONObject): String {
        val season = meta.optInt("parentIndex")
        val episode = meta.optInt("index")
        if (season <= 0 && episode <= 0) return ""
        return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    }

    data class Pin(val id: String, val code: String)

    private companion object {
        const val PREFS = "theatre"
        const val KEY_CLIENT = "client_id"
        const val KEY_TOKEN = "auth_token"
        const val PLEX = "https://plex.tv"
        const val PRODUCT = "Meta Theatre"
        const val TAG = "Theatre"
        const val PAGE_SIZE = 100
        const val MAX_PAGE_REQUESTS = 10_000
    }
}

private fun array(parent: JSONObject, key: String): JSONArray {
    if (!parent.has(key) || parent.isNull(key)) return JSONArray()
    return when (val value = parent.get(key)) {
        is JSONArray -> value
        is JSONObject -> JSONArray().put(value)
        else -> JSONArray()
    }
}

private fun redact(text: String): String {
    return text.replace(Regex("(?i)(X-Plex-Token=)[^&\\s\"]+"), "$1***")
}
