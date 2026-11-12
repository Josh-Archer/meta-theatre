package casa.archer.metatheatre

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jellyfin sign-in, browsing, and playback, ported from questkit's
 * `jellyfin-client` Rust crate (the API contract used by quest-jellyfin-3d
 * PR #38). The crate is not bound via JNI/UniFFI: this Gradle build has no
 * Rust toolchain, cargo-ndk step, or `.so` packaging, so the same endpoints,
 * `Authorization: MediaBrowser` header, URL shapes, and session reporting are
 * implemented here instead. Passwords are never stored or logged; playback
 * and artwork URLs carry the access token, so no method logs a URL or body.
 */

enum class JellyfinFailure { AUTH, CONNECTION, SERVER, OTHER }
class JellyfinException(message: String, val failure: JellyfinFailure = JellyfinFailure.OTHER) :
    Exception(message)

data class JellyfinSession(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val serverId: String,
    val deviceId: String,
) {
    override fun toString(): String =
        "JellyfinSession(server=$serverUrl user=$userName token=<redacted>)"
}

data class JellyfinLibrary(
    val id: String,
    val title: String,
    val collectionType: String = "",
    val poster: String = "",
) {
    /** Plex-style section kind so both sources share shelf labels. */
    val kind: String get() = if (collectionType.equals("tvshows", ignoreCase = true)) "show" else "movie"
    override fun toString(): String = "JellyfinLibrary($title)"
}

data class JellyfinMediaStream(
    val index: Int = 0,
    val type: String = "",
    val codec: String = "",
    val profile: String = "",
    val language: String = "",
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val isDefault: Boolean = false,
)

data class JellyfinItem(
    val id: String,
    val title: String,
    val type: String = "",
    val isFolder: Boolean = false,
    val overview: String = "",
    val productionYear: Int = 0,
    val runTimeTicks: Long = 0L,
    val resumeTicks: Long = 0L,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val video3dFormat: String? = null,
    val path: String? = null,
    val mediaStreams: List<JellyfinMediaStream> = emptyList(),
    val seriesName: String = "",
    val seasonName: String = "",
    val indexNumber: Int = 0,
    val parentIndexNumber: Int = 0,
    val childCount: Int = 0,
    val poster: String = "",
    val primaryImageTag: String? = null,
) {
    val durationMs: Long get() = jellyfinTicksToMs(runTimeTicks)
    val resumeMs: Long get() = jellyfinTicksToMs(resumeTicks)
    val videoStream: JellyfinMediaStream?
        get() = mediaStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
    fun detectedStereo(): DetectedStereo = detectStereo(
        video3dFormat,
        path,
        mediaStreams.mapNotNull { stream ->
            listOf(stream.codec, stream.profile).filter { it.isNotBlank() }.joinToString(" ")
                .takeIf { it.isNotBlank() }
        },
    )

    override fun toString(): String = "JellyfinItem($type $title)"
}

data class JellyfinPlaybackSource(
    val id: String,
    val container: String = "",
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = false,
    val directStreamUrl: String? = null,
    val transcodingUrl: String? = null,
)

data class JellyfinPlaybackInfo(
    val playSessionId: String? = null,
    val mediaSources: List<JellyfinPlaybackSource> = emptyList(),
    val errorCode: String? = null,
)

enum class JellyfinPlayMethod(val wire: String) {
    DIRECT_PLAY("DirectPlay"), DIRECT_STREAM("DirectStream"), TRANSCODE("Transcode"),
}

data class ResolvedJellyfinPlayback(
    val url: String,
    val sourceId: String,
    val method: JellyfinPlayMethod,
)

data class QuickConnectChallenge(val code: String, val secret: String) {
    override fun toString(): String = "QuickConnectChallenge(code=$code secret=<redacted>)"
}

/** One HTTP call. Fakes implement this in tests; no real server is needed. */
data class JellyfinHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

data class JellyfinHttpResponse(val code: Int, val body: String)

fun interface JellyfinTransport {
    fun execute(request: JellyfinHttpRequest): JellyfinHttpResponse
}

class HttpJellyfinTransport : JellyfinTransport {
    override fun execute(request: JellyfinHttpRequest): JellyfinHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            if (request.body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JellyfinHttpResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }
}

interface JellyfinSessionStore {
    fun load(): JellyfinSession?
    fun save(session: JellyfinSession)
    fun clear()
}

class MemoryJellyfinSessionStore : JellyfinSessionStore {
    private var session: JellyfinSession? = null
    override fun load(): JellyfinSession? = session
    override fun save(session: JellyfinSession) { this.session = session }
    override fun clear() { session = null }
}

/** Same SharedPreferences file and `commit()` discipline as [PlexClient]. */
internal class PrefsJellyfinSessionStore(context: Context) : JellyfinSessionStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString(KEY_DEVICE, it).commit()) { "Couldn't save the Jellyfin device identity" }
    }

    override fun load(): JellyfinSession? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val server = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        return JellyfinSession(
            serverUrl = server,
            accessToken = token,
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
            userName = prefs.getString(KEY_USER_NAME, "").orEmpty(),
            serverId = prefs.getString(KEY_SERVER_ID, "").orEmpty(),
            deviceId = prefs.getString(KEY_SESSION_DEVICE, deviceId).orEmpty(),
        )
    }

    override fun save(session: JellyfinSession) {
        check(
            prefs.edit()
                .putString(KEY_SERVER, session.serverUrl)
                .putString(KEY_TOKEN, session.accessToken)
                .putString(KEY_USER_ID, session.userId)
                .putString(KEY_USER_NAME, session.userName)
                .putString(KEY_SERVER_ID, session.serverId)
                .putString(KEY_SESSION_DEVICE, session.deviceId)
                .commit(),
        ) { "Couldn't save the Jellyfin sign-in" }
    }

    override fun clear() {
        check(
            prefs.edit()
                .remove(KEY_SERVER).remove(KEY_TOKEN).remove(KEY_USER_ID)
                .remove(KEY_USER_NAME).remove(KEY_SERVER_ID).remove(KEY_SESSION_DEVICE)
                .commit(),
        ) { "Couldn't clear the Jellyfin sign-in" }
    }

    private companion object {
        const val PREFS = "theatre"
        const val KEY_DEVICE = "jellyfin_device_id"
        const val KEY_SERVER = "jellyfin_server_url"
        const val KEY_TOKEN = "jellyfin_token"
        const val KEY_USER_ID = "jellyfin_user_id"
        const val KEY_USER_NAME = "jellyfin_user_name"
        const val KEY_SERVER_ID = "jellyfin_server_id"
        const val KEY_SESSION_DEVICE = "jellyfin_session_device"
    }
}

class JellyfinClient internal constructor(
    private val store: JellyfinSessionStore,
    private val transport: JellyfinTransport,
    val deviceId: String,
) {
    constructor(context: Context, transport: JellyfinTransport = HttpJellyfinTransport()) :
        this(PrefsJellyfinSessionStore(context.applicationContext), transport) 

    internal constructor(store: PrefsJellyfinSessionStore, transport: JellyfinTransport) :
        this(store as JellyfinSessionStore, transport, store.deviceId)

    /** Session restored from storage, only when it matches this device. */
    var session: JellyfinSession? = store.load()?.takeIf { it.deviceId == deviceId }
        private set

    val isSignedIn: Boolean get() = session != null

    fun clearSession() {
        store.clear()
        session = null
    }

    // -- Authentication ------------------------------------------------------

    /** `POST /Users/AuthenticateByName`. The password is sent once, never kept. */
    fun authenticateByName(serverUrl: String, username: String, password: String): JellyfinSession {
        val base = jellyfinNormalizeBaseUrl(serverUrl)
        if (username.isBlank()) throw JellyfinException("Enter your Jellyfin username.", JellyfinFailure.AUTH)
        val body = JSONObject()
            .put("Username", username)
            .put("Pw", password)
            .toString()
        val response = execute(
            JellyfinHttpRequest(
                "POST", "$base/Users/AuthenticateByName",
                jellyfinHeaders(deviceId, token = null), body,
            ),
            label = "/Users/AuthenticateByName",
        )
        return finishAuthentication(base, response)
    }

    /** `POST /QuickConnect/Initiate`. Show [QuickConnectChallenge.code] to the user. */
    fun initiateQuickConnect(serverUrl: String): QuickConnectChallenge {
        val base = jellyfinNormalizeBaseUrl(serverUrl)
        val response = execute(
            JellyfinHttpRequest(
                "POST", "$base/QuickConnect/Initiate",
                jellyfinHeaders(deviceId, token = null),
            ),
            label = "/QuickConnect/Initiate",
        )
        val json = parseObject(response, "/QuickConnect/Initiate")
        val secret = json.optString("Secret")
        val code = json.optString("Code")
        if (secret.isBlank()) throw JellyfinException("Jellyfin did not return a Quick Connect secret.")
        if (code.isBlank()) throw JellyfinException("Jellyfin did not return a Quick Connect code.")
        return QuickConnectChallenge(code = code, secret = secret)
    }

    /**
     * One `GET /QuickConnect/Connect` poll. Returns null while nobody has
     * approved the code; on approval exchanges the secret for a token
     * (`POST /Users/AuthenticateWithQuickConnect`, Jellyfin 10.8+) and saves it.
     */
    fun pollQuickConnect(serverUrl: String, secret: String): JellyfinSession? {
        val base = jellyfinNormalizeBaseUrl(serverUrl)
        if (secret.isBlank()) throw JellyfinException("Jellyfin did not return a Quick Connect secret.")
        val query = "secret=" + URLEncoder.encode(secret, "UTF-8")
        val response = execute(
            JellyfinHttpRequest(
                "GET", "$base/QuickConnect/Connect?$query",
                jellyfinHeaders(deviceId, token = null),
            ),
            label = "/QuickConnect/Connect",
        )
        val json = parseObject(response, "/QuickConnect/Connect")
        if (!json.optBoolean("Authenticated")) return null
        val exchangeBody = JSONObject().put("Secret", secret).toString()
        val exchanged = execute(
            JellyfinHttpRequest(
                "POST", "$base/Users/AuthenticateWithQuickConnect",
                jellyfinHeaders(deviceId, token = null), exchangeBody,
            ),
            label = "/Users/AuthenticateWithQuickConnect",
        )
        return finishAuthentication(base, exchanged)
    }

    private fun finishAuthentication(base: String, body: String): JellyfinSession {
        val json = parseObject(body, "/Users/Authenticate")
        val token = json.optString("AccessToken")
        if (token.isBlank()) throw JellyfinException("Jellyfin did not return an access token.", JellyfinFailure.AUTH)
        val user = json.optJSONObject("User")
        val created = JellyfinSession(
            serverUrl = base,
            accessToken = token,
            userId = user?.optString("Id").orEmpty(),
            userName = user?.optString("Name").orEmpty(),
            serverId = json.optString("ServerId").ifBlank { user?.optString("ServerId").orEmpty() },
            deviceId = deviceId,
        )
        store.save(created)
        session = created
        return created
    }

    // -- Library browsing ------------------------------------------------------

    /** `GET /Users/{id}/Views`: libraries visible to the signed-in user. */
    fun libraryViews(): List<JellyfinLibrary> {
        val active = requireSession()
        val path = "/Users/${jellyfinEncodeComponent(active.userId)}/Views"
        val json = parseObject(
            execute(authed("GET", active, path), label = "/Users/{id}/Views"),
            "/Users/{id}/Views",
        )
        return jellyfinArray(json, "Items").map { entry ->
            val id = entry.optString("Id")
            JellyfinLibrary(
                id = id,
                title = entry.optString("Name").ifBlank { "Library" },
                collectionType = entry.optString("CollectionType"),
                poster = if (id.isNotBlank()) imageUrl(id) else "",
            )
        }
    }

    /**
     * `GET /Items` with the crate's fields (`Video3DFormat,MediaStreams,Path`)
     * and `StartIndex`/`Limit` paging. An empty page stops the walk even when
     * the server still reports a larger total.
     */
    fun items(
        parentId: String? = null,
        includeItemTypes: List<String> = emptyList(),
        recursive: Boolean = false,
        searchTerm: String? = null,
    ): List<JellyfinItem> {
        val active = requireSession()
        val out = mutableListOf<JellyfinItem>()
        var start = 0
        var pages = 0
        while (true) {
            if (++pages > MAX_ITEM_PAGES) throw JellyfinException("Jellyfin library is too large to load safely")
            val query = linkedMapOf(
                "StartIndex" to start.toString(),
                "Limit" to PAGE_SIZE.toString(),
                "Fields" to ITEM_FIELDS,
                "Recursive" to recursive.toString(),
                "EnableImages" to "true",
                "EnableUserData" to "true",
                "EnableImageTypes" to "Primary,Backdrop",
                "UserId" to active.userId,
            )
            if (!parentId.isNullOrBlank()) query["ParentId"] = parentId
            if (includeItemTypes.isNotEmpty()) query["IncludeItemTypes"] = includeItemTypes.joinToString(",")
            if (!searchTerm.isNullOrBlank()) query["SearchTerm"] = searchTerm
            val encoded = query.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            val json = parseObject(
                execute(authed("GET", active, "/Items?$encoded"), label = "/Items"),
                "/Items",
            )
            val pageItems = jellyfinArray(json, "Items").map { entry -> withPoster(parseJellyfinItem(entry)) }
            if (pageItems.isEmpty()) break
            out.addAll(pageItems)
            start += pageItems.size
            val total = json.optLong("TotalRecordCount", -1L)
            if (total >= 0 && start >= total) break
            if (total < 0 && pageItems.size < PAGE_SIZE) break
        }
        return out
    }

    /** Seasons of a series (`GET /Items?ParentId=<series>` keeps one code path). */
    fun seasons(seriesId: String): List<JellyfinItem> =
        items(parentId = seriesId, includeItemTypes = listOf("Season"))

    /** Episodes of a season. */
    fun episodes(seasonId: String): List<JellyfinItem> =
        items(parentId = seasonId, includeItemTypes = listOf("Episode"))

    /** Children of any folder item (series -> seasons, season -> episodes). */
    fun children(itemId: String): List<JellyfinItem> = items(parentId = itemId)

    /** `GET /Users/{id}/Items/Resume`: Continue Watching. */
    fun resumeItems(): List<JellyfinItem> {
        val active = requireSession()
        val path = "/Users/${jellyfinEncodeComponent(active.userId)}/Items/Resume" +
            "?Limit=20&MediaTypes=Video&Fields=Overview,PrimaryImageAspectRatio&EnableUserData=true"
        val json = parseObject(
            execute(authed("GET", active, path), label = "/Users/{id}/Items/Resume"),
            "/Users/{id}/Items/Resume",
        )
        return jellyfinArray(json, "Items").map { entry -> withPoster(parseJellyfinItem(entry)) }
    }

    /** `GET /Users/{id}/Items/{itemId}` for one item with resume and streams. */
    fun item(itemId: String): JellyfinItem {
        val active = requireSession()
        val path = "/Users/${jellyfinEncodeComponent(active.userId)}/Items/${jellyfinEncodeComponent(itemId)}"
        val json = parseObject(
            execute(authed("GET", active, path), label = "/Users/{id}/Items/{itemId}"),
            "/Users/{id}/Items/{itemId}",
        )
        return withPoster(parseJellyfinItem(json))
    }

    /** Primary artwork URL (token in query; never logged, same as Plex posters). */
    fun imageUrl(itemId: String, imageType: String = "Primary", tag: String? = null): String {
        val active = session ?: return ""
        return jellyfinImageUrl(active.serverUrl, itemId, imageType, active.accessToken, tag = tag)
    }

    private fun withPoster(item: JellyfinItem): JellyfinItem =
        if (item.poster.isNotBlank() || item.id.isBlank()) item
        else item.copy(poster = imageUrl(item.id, tag = item.primaryImageTag))

    // -- Playback ---------------------------------------------------------------

    /** `POST /Items/{id}/PlaybackInfo` with a direct-play-first Quest profile. */
    fun playbackInfo(itemId: String): JellyfinPlaybackInfo {
        val active = requireSession()
        val request = JSONObject()
            .put("UserId", active.userId)
            .put("MaxStreamingBitrate", MAX_STREAMING_BITRATE)
            .put("EnableDirectPlay", true)
            .put("EnableDirectStream", true)
            .put("EnableTranscoding", true)
            .put("DeviceProfile", jellyfinDeviceProfile())
        val path = "/Items/${jellyfinEncodeComponent(itemId)}/PlaybackInfo"
        val json = parseObject(
            execute(authed("POST", active, path, request.toString()), label = "/Items/{id}/PlaybackInfo"),
            "/Items/{id}/PlaybackInfo",
        )
        return JellyfinPlaybackInfo(
            playSessionId = json.optString("PlaySessionId").takeIf { it.isNotBlank() },
            mediaSources = jellyfinArray(json, "MediaSources").map { source ->
                JellyfinPlaybackSource(
                    id = source.optString("Id"),
                    container = source.optString("Container"),
                    supportsDirectPlay = source.optBoolean("SupportsDirectPlay"),
                    supportsDirectStream = source.optBoolean("SupportsDirectStream"),
                    supportsTranscoding = source.optBoolean("SupportsTranscoding"),
                    directStreamUrl = source.optString("DirectStreamUrl").takeIf { it.isNotBlank() },
                    transcodingUrl = source.optString("TranscodingUrl").takeIf { it.isNotBlank() },
                )
            },
            errorCode = json.optString("ErrorCode").takeIf { it.isNotBlank() },
        )
    }

    /** Direct play URL (`/Videos/{id}/stream?static=true&api_key=..`). */
    fun directPlayUrl(itemId: String, mediaSourceId: String? = null): String {
        val active = requireSession()
        return jellyfinDirectPlayUrl(active.serverUrl, itemId, active.accessToken, mediaSourceId)
    }

    /** Negotiate playback and build the [Playable] the existing player consumes. */
    fun playable(item: JellyfinItem): Playable {
        val active = requireSession()
        val info = playbackInfo(item.id)
        if (info.errorCode != null) throw JellyfinException("Jellyfin refused playback (${info.errorCode})")
        val resolved = jellyfinResolvePlayback(active.serverUrl, item.id, info, active.accessToken)
        val detailed = runCatching { item(item.id) }.getOrDefault(item)
        val video = detailed.videoStream
        return Playable(
            title = detailed.title,
            directUrl = resolved.url,
            hlsUrl = resolved.url,
            headers = jellyfinHeaders(deviceId, active.accessToken),
            subtitle = jellyfinItemSubtitle(detailed),
            sourceWidth = video?.width ?: 0,
            sourceHeight = video?.height ?: 0,
            audioStreams = playbackStreams(detailed, "Audio"),
            subtitleStreams = playbackStreams(detailed, "Subtitle"),
            stereo = detailed.detectedStereo(),
            sourceKind = SourceKind.JELLYFIN,
            sourceItemId = detailed.id,
        )
    }

    private fun playbackStreams(item: JellyfinItem, type: String): List<PlexPlaybackStream> =
        item.mediaStreams.filter { it.type.equals(type, ignoreCase = true) }.map { stream ->
            PlexPlaybackStream(
                id = stream.index.toString(),
                language = stream.language,
                languageCode = stream.language,
                title = stream.title,
                codec = stream.codec,
                selected = stream.isDefault,
            )
        }

    // -- Session reporting --------------------------------------------------------

    /** `POST /Sessions/Playing`. */
    fun reportPlaying(itemId: String, playSessionId: String?, positionMs: Long, mediaSourceId: String? = null) {
        val active = requireSession()
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("PlayMethod", JellyfinPlayMethod.DIRECT_PLAY.wire)
            .put("CanSeek", true)
            .put("IsPaused", false)
            .put("PositionTicks", jellyfinMsToTicks(positionMs))
        playSessionId?.let { body.put("PlaySessionId", it) }
        mediaSourceId?.let { body.put("MediaSourceId", it) }
        execute(authed("POST", active, "/Sessions/Playing", body.toString()), label = "/Sessions/Playing")
    }

    /** `POST /Sessions/Playing/Progress`. */
    fun reportProgress(
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        isPaused: Boolean,
        eventName: String = "TimeUpdate",
    ) {
        val active = requireSession()
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("PlayMethod", JellyfinPlayMethod.DIRECT_PLAY.wire)
            .put("CanSeek", true)
            .put("IsPaused", isPaused)
            .put("PositionTicks", jellyfinMsToTicks(positionMs))
            .put("EventName", eventName)
        playSessionId?.let { body.put("PlaySessionId", it) }
        execute(authed("POST", active, "/Sessions/Playing/Progress", body.toString()), label = "/Sessions/Playing/Progress")
    }

    /** `POST /Sessions/Playing/Stopped`. */
    fun reportStopped(itemId: String, playSessionId: String?, positionMs: Long) {
        val active = requireSession()
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", jellyfinMsToTicks(positionMs))
        playSessionId?.let { body.put("PlaySessionId", it) }
        execute(authed("POST", active, "/Sessions/Playing/Stopped", body.toString()), label = "/Sessions/Playing/Stopped")
    }

    // -- Transport plumbing -------------------------------------------------------

    private fun requireSession(): JellyfinSession =
        session ?: throw JellyfinException("Connect to Jellyfin to continue.", JellyfinFailure.AUTH)

    private fun authed(method: String, active: JellyfinSession, path: String, body: String? = null) =
        JellyfinHttpRequest(method, active.serverUrl + path, jellyfinHeaders(deviceId, active.accessToken), body)

    private fun execute(request: JellyfinHttpRequest, label: String): String {
        val response = try {
            transport.execute(request)
        } catch (error: Exception) {
            throw JellyfinException("Couldn't connect to Jellyfin. Your sign-in is still saved.", JellyfinFailure.CONNECTION)
        }
        if (response.code !in 200..299) {
            throw when {
                response.code == 401 && label == "/QuickConnect/Initiate" ->
                    JellyfinException("Quick Connect is disabled on this server.", JellyfinFailure.AUTH)
                response.code == 404 && (label == "/QuickConnect/Connect" ||
                    label == "/Users/AuthenticateWithQuickConnect") ->
                    JellyfinException("That Quick Connect code is unknown or expired.", JellyfinFailure.AUTH)
                response.code in listOf(401, 403) ->
                    JellyfinException("Jellyfin access changed. Sign in again.", JellyfinFailure.AUTH)
                response.code == 429 || response.code >= 500 ->
                    JellyfinException("Jellyfin is temporarily unavailable (HTTP ${response.code}).", JellyfinFailure.SERVER)
                else -> JellyfinException("Jellyfin returned HTTP ${response.code}.")
            }
        }
        return response.body
    }

    private fun parseObject(body: String, label: String): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        throw JellyfinException("Jellyfin returned a malformed response for $label.")
    }

    private companion object {
        const val ITEM_FIELDS = "Video3DFormat,MediaStreams,Path"
        const val PAGE_SIZE = 100
        const val MAX_ITEM_PAGES = 100
        const val MAX_STREAMING_BITRATE = 120_000_000
    }
}

// -- Pure helpers (unit-tested without a Context or a server) ------------------

internal const val JELLYFIN_CLIENT_NAME = "Meta Theatre"
internal const val JELLYFIN_DEVICE_NAME = "Quest 3"
internal const val JELLYFIN_VERSION = "1.0.0"
internal const val JELLYFIN_TICKS_PER_MS = 10_000L

internal fun jellyfinTicksToMs(ticks: Long): Long = if (ticks <= 0L) 0L else ticks / JELLYFIN_TICKS_PER_MS
internal fun jellyfinMsToTicks(ms: Long): Long = if (ms <= 0L) 0L else ms * JELLYFIN_TICKS_PER_MS

/** Port of the crate's `normalize_base_url`: HTTP(S), host, no credentials/query/fragment. */
internal fun jellyfinNormalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    val parsed = try {
        java.net.URI(trimmed)
    } catch (_: Exception) {
        throw JellyfinException("Enter a valid Jellyfin server URL.", JellyfinFailure.AUTH)
    }
    val scheme = parsed.scheme?.lowercase()
    if ((scheme != "http" && scheme != "https") || parsed.host.isNullOrBlank() ||
        parsed.userInfo != null || parsed.query != null || parsed.fragment != null
    ) {
        throw JellyfinException(
            "Jellyfin server URL must be http(s) without credentials, query, or fragment.",
            JellyfinFailure.AUTH,
        )
    }
    return trimmed.trimEnd('/')
}

/** `encodeURIComponent`-compatible percent-encoding (the crate's `encode_component`). */
internal fun jellyfinEncodeComponent(value: String): String {
    val hex = "0123456789ABCDEF"
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c in charArrayOf('-', '_', '.', '!', '~', '*', '\'', '(', ')')
        ) {
            out.append(c)
        } else {
            out.append('%').append(hex[b shr 4]).append(hex[b and 0x0F])
        }
    }
    return out.toString()
}

/** `Authorization: MediaBrowser` header, values percent-encoded like Jellyfin's clients. */
internal fun jellyfinAuthorizationHeader(deviceId: String, token: String?): String {
    val base = "MediaBrowser Client=\"${jellyfinEncodeComponent(JELLYFIN_CLIENT_NAME)}\", " +
        "Device=\"${jellyfinEncodeComponent(JELLYFIN_DEVICE_NAME)}\", " +
        "DeviceId=\"${jellyfinEncodeComponent(deviceId)}\", " +
        "Version=\"${jellyfinEncodeComponent(JELLYFIN_VERSION)}\""
    return if (token.isNullOrBlank()) base else base + ", Token=\"${jellyfinEncodeComponent(token)}\""
}

internal fun jellyfinHeaders(deviceId: String, token: String?): Map<String, String> = linkedMapOf(
    "Accept" to "application/json",
    "Authorization" to jellyfinAuthorizationHeader(deviceId, token),
)

/** Port of `build_direct_play_url`: `{base}/Videos/{id}/stream?static=true[&MediaSourceId=..][&api_key=..]`. */
internal fun jellyfinDirectPlayUrl(
    baseUrl: String,
    itemId: String,
    token: String?,
    mediaSourceId: String? = null,
): String {
    val base = baseUrl.trimEnd('/')
    val query = mutableListOf("static=true")
    if (!mediaSourceId.isNullOrBlank()) {
        query.add("MediaSourceId=" + URLEncoder.encode(mediaSourceId, "UTF-8"))
    }
    if (!token.isNullOrBlank()) query.add("api_key=" + URLEncoder.encode(token, "UTF-8"))
    return "$base/Videos/${jellyfinEncodeComponent(itemId)}/stream?${query.joinToString("&")}"
}

internal fun jellyfinImageUrl(
    baseUrl: String,
    itemId: String,
    imageType: String = "Primary",
    token: String?,
    maxWidth: Int = 480,
    tag: String? = null,
): String {
    val base = baseUrl.trimEnd('/')
    val query = mutableListOf("maxWidth=$maxWidth", "format=Jpg")
    if (!tag.isNullOrBlank()) query.add("tag=" + URLEncoder.encode(tag, "UTF-8"))
    if (!token.isNullOrBlank()) query.add("api_key=" + URLEncoder.encode(token, "UTF-8"))
    return "$base/Items/${jellyfinEncodeComponent(itemId)}/Images/" +
        "${jellyfinEncodeComponent(imageType)}?${query.joinToString("&")}"
}

/** Parse one `BaseItemDto`; unknown fields are ignored, nulls become defaults. */
internal fun parseJellyfinItem(json: JSONObject): JellyfinItem {
    val streams = jellyfinArray(json, "MediaStreams").map { stream ->
        JellyfinMediaStream(
            index = stream.optInt("Index"),
            type = stream.optString("Type"),
            codec = stream.optString("Codec"),
            profile = stream.optString("Profile"),
            language = stream.optString("Language"),
            title = stream.optString("Title"),
            width = stream.optInt("Width"),
            height = stream.optInt("Height"),
            isDefault = stream.optBoolean("IsDefault"),
        )
    }
    val userData = json.optJSONObject("UserData")
    val imageTags = json.optJSONObject("ImageTags")
    return JellyfinItem(
        id = json.optString("Id"),
        title = json.optString("Name").ifBlank { "Untitled" },
        type = json.optString("Type"),
        isFolder = json.optBoolean("IsFolder"),
        overview = json.optString("Overview"),
        productionYear = json.optInt("ProductionYear"),
        runTimeTicks = json.optLong("RunTimeTicks"),
        resumeTicks = userData?.optLong("PlaybackPositionTicks") ?: 0L,
        played = userData?.optBoolean("Played") ?: false,
        isFavorite = userData?.optBoolean("IsFavorite") ?: false,
        video3dFormat = json.optString("Video3DFormat").takeIf { it.isNotBlank() },
        path = json.optString("Path").takeIf { it.isNotBlank() },
        mediaStreams = streams,
        seriesName = json.optString("SeriesName"),
        seasonName = json.optString("SeasonName"),
        indexNumber = json.optInt("IndexNumber"),
        parentIndexNumber = json.optInt("ParentIndexNumber"),
        childCount = json.optInt("ChildCount"),
        primaryImageTag = imageTags?.optString("Primary")?.takeIf { it.isNotBlank() },
    )
}

internal fun jellyfinItemSubtitle(item: JellyfinItem): String = when {
    item.type.equals("Series", ignoreCase = true) -> when {
        item.childCount == 1 -> "1 season"
        item.childCount > 1 -> "${item.childCount} seasons"
        item.productionYear > 0 -> item.productionYear.toString()
        else -> ""
    }
    item.type.equals("Season", ignoreCase = true) ->
        listOf(item.seriesName, "Season ${item.indexNumber}".takeIf { item.indexNumber > 0 }.orEmpty())
            .filter { it.isNotBlank() }.joinToString("  ·  ")
    item.type.equals("Episode", ignoreCase = true) -> {
        val label = if (item.parentIndexNumber > 0 || item.indexNumber > 0) {
            "S${item.parentIndexNumber.toString().padStart(2, '0')}E${item.indexNumber.toString().padStart(2, '0')}"
        } else ""
        listOf(item.seriesName, label).filter { it.isNotBlank() }.joinToString("  ·  ")
    }
    else -> listOfNotNull(
        item.productionYear.takeIf { it > 0 }?.toString(),
        jellyfinRuntimeLabel(item.durationMs).takeIf { it.isNotBlank() },
        item.detectedStereo().label.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
}

private fun jellyfinRuntimeLabel(ms: Long): String {
    if (ms <= 0L) return ""
    val minutes = (ms / 60_000L).toInt()
    if (minutes <= 0) return ""
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}

internal fun jellyfinArray(parent: JSONObject, key: String): List<JSONObject> {
    if (!parent.has(key) || parent.isNull(key)) return emptyList()
    return when (val value = parent.opt(key)) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }
}

/**
 * Port of the crate's `resolve_playback`: direct play first, then a
 * server-provided direct-stream/transcoding URL. Cross-origin stream URLs
 * are refused so an `Authorization` header never leaks to another host, and
 * any `api_key` on a server URL is dropped (auth travels in the header).
 */
internal fun jellyfinResolvePlayback(
    baseUrl: String,
    itemId: String,
    info: JellyfinPlaybackInfo,
    token: String?,
): ResolvedJellyfinPlayback {
    info.mediaSources.firstOrNull { it.supportsDirectPlay }?.let { source ->
        return ResolvedJellyfinPlayback(
            url = jellyfinDirectPlayUrl(baseUrl, itemId, token, source.id),
            sourceId = source.id,
            method = JellyfinPlayMethod.DIRECT_PLAY,
        )
    }
    for (method in listOf(JellyfinPlayMethod.DIRECT_STREAM, JellyfinPlayMethod.TRANSCODE)) {
        for (source in info.mediaSources) {
            val raw = when (method) {
                JellyfinPlayMethod.DIRECT_STREAM -> source.directStreamUrl.takeIf { source.supportsDirectStream }
                JellyfinPlayMethod.TRANSCODE -> source.transcodingUrl.takeIf { source.supportsTranscoding }
                else -> null
            } ?: continue
            return ResolvedJellyfinPlayback(
                url = jellyfinResolveServerUrl(baseUrl, raw),
                sourceId = source.id,
                method = method,
            )
        }
    }
    throw JellyfinException("Jellyfin offered no playable stream for this title.")
}

private fun jellyfinResolveServerUrl(baseUrl: String, raw: String): String {
    val base = try {
        java.net.URI(baseUrl.trimEnd('/'))
    } catch (_: Exception) {
        throw JellyfinException("Invalid Jellyfin server URL.")
    }
    val rawUri = try {
        java.net.URI(raw)
    } catch (_: Exception) {
        throw JellyfinException("Jellyfin returned an invalid stream URL.")
    }
    val basePath = base.path?.trimEnd('/').orEmpty()
    val resolvedPath = try {
        when {
            rawUri.isAbsolute -> rawUri.path.orEmpty()
            raw.startsWith("/") && !raw.startsWith("//") ->
                if (basePath.isNotEmpty() && !rawUri.path.orEmpty().startsWith("$basePath/")) {
                    basePath + rawUri.path.orEmpty()
                } else {
                    rawUri.path.orEmpty()
                }
            else -> base.resolve(rawUri).path.orEmpty()
        }
    } catch (_: Exception) {
        throw JellyfinException("Jellyfin returned an invalid stream URL.")
    }
    val inServerPath = basePath.isEmpty() || resolvedPath == basePath ||
        resolvedPath.startsWith("$basePath/")
    if ((rawUri.isAbsolute && (rawUri.scheme != base.scheme || rawUri.authority != base.authority)) ||
        !inServerPath || rawUri.userInfo != null || rawUri.fragment != null
    ) {
        throw JellyfinException("Jellyfin stream URL must stay on the Jellyfin server.")
    }
    val kept = rawUri.rawQuery?.split('&')?.filter { part ->
        part.isNotBlank() && !part.substringBefore('=').equals("api_key", ignoreCase = true)
    }.orEmpty()
    val clean = java.net.URI(
        base.scheme, base.authority, resolvedPath,
        kept.joinToString("&").ifBlank { null }, null,
    )
    return clean.toString()
}

/** Compact direct-play-first device profile (the crate's Quest profile, trimmed). */
internal fun jellyfinDeviceProfile(): JSONObject {
    val directPlay = JSONObject()
        .put("Container", "mkv,mp4,m4v,webm,avi,mov,ts,m2ts,wmv")
        .put("Type", "Video")
        .put("VideoCodec", "h264,hevc,vp9,av1,mpeg4,mpeg2video")
        .put("AudioCodec", "aac,mp3,ac3,eac3,flac,opus,vorbis,dts,truehd,pcm")
    val transcode = JSONObject()
        .put("Container", "ts")
        .put("Type", "Video")
        .put("Protocol", "hls")
        .put("VideoCodec", "h264")
        .put("AudioCodec", "aac")
        .put("Context", "Streaming")
    return JSONObject()
        .put("Name", "Meta Theatre Quest")
        .put("MaxStreamingBitrate", 120_000_000)
        .put("DirectPlayProfiles", JSONArray().put(directPlay))
        .put("TranscodingProfiles", JSONArray().put(transcode))
        .put("CodecProfiles", JSONArray())
        .put("SubtitleProfiles", JSONArray())
}
