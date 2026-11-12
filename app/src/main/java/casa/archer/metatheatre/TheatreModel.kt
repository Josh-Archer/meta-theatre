package casa.archer.metatheatre

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecentVideo(val server: PlexServer?, val item: PlexItem)

sealed interface Browse {
    data class Home(val videos: List<RecentVideo>, val onOpen: (RecentVideo) -> Unit) : Browse
    data object SignedOut : Browse
    data class Linking(val code: String) : Browse
    data class Servers(val servers: List<PlexServer>) : Browse
    data class Sections(val server: PlexServer, val sections: List<PlexSection>) : Browse
    data class Items(
        val title: String,
        val items: List<PlexItem>,
        val onOpen: (PlexItem) -> Unit,
    ) : Browse
    data object JellyfinSignIn : Browse
    data class JellyfinLinking(val code: String) : Browse
    data class JellyfinLibraries(
        val libraries: List<JellyfinLibrary>,
        val onOpen: (JellyfinLibrary) -> Unit,
    ) : Browse
    data class JellyfinItems(
        val title: String,
        val items: List<JellyfinItem>,
        val onOpen: (JellyfinItem) -> Unit,
    ) : Browse
}

enum class JellyfinField { SERVER, USERNAME, PASSWORD }

data class JellyfinForm(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val field: JellyfinField = JellyfinField.SERVER,
) {
    fun value(forField: JellyfinField): String = when (forField) {
        JellyfinField.SERVER -> serverUrl
        JellyfinField.USERNAME -> username
        JellyfinField.PASSWORD -> password
    }

    fun with(forField: JellyfinField, value: String): JellyfinForm = when (forField) {
        JellyfinField.SERVER -> copy(serverUrl = value)
        JellyfinField.USERNAME -> copy(username = value)
        JellyfinField.PASSWORD -> copy(password = value)
    }
}

/** Panel fractions of the "Use Jellyfin" button on the signed-out screen. */
internal object SignedOutLayout {
    const val JELLYFIN_TOP = 0.33f
    const val JELLYFIN_BOTTOM = 0.46f
}

/** Full-panel fractions for the Jellyfin sign-in screen (shared by Compose and ray presses). */
internal object JellyfinSignInLayout {
    const val FIELD_TOP = 0.16f
    const val FIELD_HEIGHT = 0.09f
    const val ACTION_TOP = 0.44f
    const val ACTION_BOTTOM = 0.52f
    fun fieldAt(y: Float): JellyfinField? {
        val index = ((y - FIELD_TOP) / FIELD_HEIGHT).toInt()
        return JellyfinField.entries.getOrNull(index)?.takeIf { y >= FIELD_TOP }
    }
}

class TheatreModel(
    context: Context,
    private val player: ScreenPlayer,
    private val plex: PlexClient = PlexClient(context.applicationContext),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val jellyfin: JellyfinClient = JellyfinClient(context.applicationContext),
) {
    internal val state = TheatreState(context.applicationContext)
    private val history = state.history
    private var servers = emptyList<PlexServer>()
    private var activeMovie: MovieKey? = null
    private var lastCheckpointMs = 0L
    private var reconnectJob: Job? = null
    private var browseJob: Job? = null
    private var workGeneration = 0
    private val stack = ArrayDeque<Browse>()
    private var pinJob: Job? = null
    private var generation = 0

    val browse = MutableStateFlow<Browse>(Browse.SignedOut)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val plexReconnectAvailable = MutableStateFlow(false)
    val plexLinkRequired = MutableStateFlow(false)
    val canGoBack = MutableStateFlow(false)
    val libraryOpen = MutableStateFlow(true)
    val screenHidden = MutableStateFlow(false)
    val signOutConfirmation = MutableStateFlow(false)
    var onScreenHidden: ((Boolean) -> Unit)? = null
    val jellyfinForm = MutableStateFlow(JellyfinForm())
    private var jellyfinSecret: String? = null
    private var jellyfinJob: Job? = null
    private data class ActiveJellyfin(val itemId: String, val playSessionId: String?, val sourceId: String?)
    private var activeJellyfin: ActiveJellyfin? = null
    val page = MutableStateFlow(0)
    val playerControlsOpen = MutableStateFlow(false)
    val librarySearchQuery = MutableStateFlow("")
    val librarySearchFilter = MutableStateFlow(LibrarySearchFilter.ALL)
    val librarySearchKeyboardOpen = MutableStateFlow(false)

    /** Creates the video surface the first time something is about to play. */
    var onPrepareScreen: (() -> Unit)? = null

    /** True puts the movie in front of the shelf. False brings the shelf back. */
    var onWatching: ((Boolean) -> Unit)? = null

    var onViewTurn: ((Float) -> Unit)? = null
    var onVisitorsReturn: (() -> Unit)? = null
    internal var onEarthControl: ((EarthControl) -> Unit)? = null
    val earthControlsOpen = MutableStateFlow(false)
    val earthViewLabel = MutableStateFlow("Equator")
    val scenePickerOpen = MutableStateFlow(false)
    val currentLocation = MutableStateFlow(state.currentLocation)
    var onLocationChanged: ((TheatreLocation) -> Unit)? = null
    var onResetSceneSettings: ((TheatreLocation) -> Unit)? = null
    internal val weatherUi = MutableStateFlow(WeatherUi(enabled = state.liveWeatherEnabled))
    val weatherSettingsOpen = MutableStateFlow(false)
    val earthSettingsOpen = MutableStateFlow(false)
    val useCurrentLocation = MutableStateFlow(state.useCurrentLocation)
    val realEarthRotation = MutableStateFlow(state.realEarthRotationEnabled)
    val earthZoom = MutableStateFlow(1f)
    val locationMessage = MutableStateFlow(if (state.useCurrentLocation) "Waiting for headset location." else "Current location is off.")
    internal var onLocationEnabled: ((Boolean) -> Unit)? = null
    internal var onLocationRequest: (() -> Unit)? = null
    internal var onRealRotation: ((Boolean) -> Unit)? = null
    internal var onEarthZoom: ((EarthSetting) -> Unit)? = null
    internal val viewingPresets = MutableStateFlow(state.viewingPresets())
    val viewingPresetsOpen = MutableStateFlow(false)
    val presetMessage = MutableStateFlow("Save a setup, then restore it around your current seat.")
    internal var onCapturePreset: (() -> ViewingPreset?)? = null
    internal var onRestorePreset: ((ViewingPreset) -> Unit)? = null
    internal var onResetView: (() -> Unit)? = null
    internal var onWeatherEnabled: ((Boolean) -> Unit)? = null
    internal var onWeatherRefresh: (() -> Unit)? = null
    var onScreenResize: ((Float) -> Unit)? = null
    var onQuietCinemaPreference: ((Boolean) -> Unit)? = null
    val quietCinemaEnabled = MutableStateFlow(state.quietCinemaEnabled)
    var onPlayerInteraction: (() -> Unit)? = null
    internal val playerHitTargets = PlayerHitTargets()
    internal val panelCursor = MutableStateFlow<PanelCursor?>(null)
    internal val seekPreview = MutableStateFlow<Float?>(null)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "browse failed (${error.javaClass.simpleName})")
            loading.value = false
            this.error.value = (error as? PlexException)?.message ?: "Couldn't connect. Try again."
        },
    )

    init {
        player.onProgress = { position, duration, ended -> checkpoint(position, duration, ended) }
        player.onReconnect = ::refreshPlaybackConnection
    }

    fun start() {
        if (stack.isNotEmpty()) return
        when {
            !plex.token.isNullOrBlank() -> {
                showHome()
                loadServers(restoreActive = true)
            }
            jellyfin.isSignedIn -> loadJellyfinLibraries()
            else -> show(Browse.SignedOut, replace = true)
        }
    }

    fun release() {
        saveCheckpoint()
        jellyfinJob?.cancel()
        jellyfinSecret = null
        cancelBrowseWork()
        player.onProgress = null
        player.onReconnect = null
        scope.cancel()
        panelCursor.value = null
        onPrepareScreen = null
        onWatching = null
        onViewTurn = null
        onVisitorsReturn = null
        onEarthControl = null
        onLocationChanged = null
        onResetSceneSettings = null
        onWeatherEnabled = null
        onWeatherRefresh = null
        onScreenResize = null
        onQuietCinemaPreference = null
        onPlayerInteraction = null
        onScreenHidden = null
        onCapturePreset = null
        onRestorePreset = null
        onResetView = null
        onLocationEnabled = null
        onLocationRequest = null
        onRealRotation = null
        onEarthZoom = null
    }

    fun link() {
        Log.i(TAG, "link pressed")
        pinJob?.cancel()
        val generationAtStart = ++generation
        error.value = null
        scope.launch {
            val pin = withContext(Dispatchers.IO) { plex.requestPin() }
            if (generationAtStart != generation) return@launch
            show(Browse.Linking(pin.code), replace = stack.lastOrNull() is Browse.SignedOut || stack.isEmpty())
            pinJob = launch {
                val deadline = System.currentTimeMillis() + 5 * 60 * 1000
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(2000)
                    val auth = withContext(Dispatchers.IO) { plex.pollPin(pin) } ?: continue
                    if (generationAtStart != generation) return@launch
                    plex.token = auth
                    loadServers(restoreActive = false)
                    return@launch
                }
                error.value = "The Plex code expired. Link again."
                show(Browse.SignedOut, replace = true)
            }
        }
    }

    fun signOut() {
        signOutConfirmation.value = false
        pinJob?.cancel()
        generation++
        closeToHome()
        servers = emptyList()
        state.clearHistory()
        plex.clearToken()
        error.value = null
        libraryOpen.value = true
        show(Browse.SignedOut, replace = true)
    }

    // -- Jellyfin ---------------------------------------------------------------

    /** Signed-out presses: the Jellyfin zone opens its form; anything else links Plex as before. */
    fun signedOutPress(x: Float, y: Float): Boolean {
        if (browse.value != Browse.SignedOut) return false
        if (y in SignedOutLayout.JELLYFIN_TOP..SignedOutLayout.JELLYFIN_BOTTOM) openJellyfinSignIn() else link()
        return true
    }

    fun openJellyfinSignIn() {
        error.value = null
        jellyfinForm.value = jellyfinForm.value.copy(
            serverUrl = jellyfin.session?.serverUrl ?: jellyfinForm.value.serverUrl,
            password = "",
        )
        show(Browse.JellyfinSignIn)
    }

    /** Ray presses on the full-panel Jellyfin sign-in screen. */
    fun jellyfinPress(x: Float, y: Float): Boolean {
        if (browse.value == Browse.JellyfinSignIn) {
            JellyfinSignInLayout.fieldAt(y)?.let { field ->
                jellyfinForm.value = jellyfinForm.value.copy(field = field)
                return true
            }
            if (y in JellyfinSignInLayout.ACTION_TOP..JellyfinSignInLayout.ACTION_BOTTOM) {
                if (x < 0.5f) submitJellyfinSignIn() else startJellyfinQuickConnect()
                return true
            }
            if (y >= PosterGrid.KEYBOARD_START && y < PosterGrid.FOOTER_START) {
                when (val key = PosterGrid.libraryKeyboardKeyAt(x, y)) {
                    is LibraryKeyboardKey.Character -> jellyfinAppend(key.value)
                    LibraryKeyboardKey.Backspace -> jellyfinForm.value =
                        jellyfinForm.value.let { it.with(it.field, it.value(it.field).dropLast(1)) }
                    LibraryKeyboardKey.Space -> jellyfinAppend(' ')
                    LibraryKeyboardKey.Done -> submitJellyfinSignIn()
                    null -> Unit
                }
                return true
            }
            return y >= PosterGrid.HEADER_END
        }
        if (browse.value is Browse.JellyfinLinking) {
            // Any press cancels the wait and returns to the form.
            jellyfinJob?.cancel()
            jellyfinSecret = null
            show(Browse.JellyfinSignIn, replace = true)
            return true
        }
        return false
    }

    internal fun jellyfinKey(key: LibraryKeyboardKey) {
        when (key) {
            is LibraryKeyboardKey.Character -> jellyfinAppend(key.value)
            LibraryKeyboardKey.Backspace -> jellyfinForm.value =
                jellyfinForm.value.let { it.with(it.field, it.value(it.field).dropLast(1)) }
            LibraryKeyboardKey.Space -> jellyfinAppend(' ')
            LibraryKeyboardKey.Done -> submitJellyfinSignIn()
        }
    }

    internal fun jellyfinSelectField(field: JellyfinField) {
        jellyfinForm.value = jellyfinForm.value.copy(field = field)
    }

    private fun jellyfinAppend(character: Char) {
        val form = jellyfinForm.value
        if (form.value(form.field).length >= 200) return
        jellyfinForm.value = form.with(form.field, form.value(form.field) + character)
    }

    fun submitJellyfinSignIn() {
        val form = jellyfinForm.value
        jellyfinJob?.cancel()
        error.value = null
        loading.value = true
        jellyfinJob = scope.launch {
            try {
                withContext(ioDispatcher) {
                    jellyfin.authenticateByName(form.serverUrl, form.username, form.password)
                }
                jellyfinForm.value = jellyfinForm.value.copy(password = "")
                loadJellyfinLibraries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                error.value = (problem as? JellyfinException)?.message ?: "Couldn't sign in to Jellyfin."
            } finally {
                loading.value = false
            }
        }
    }

    fun startJellyfinQuickConnect() {
        val serverUrl = jellyfinForm.value.serverUrl
        jellyfinJob?.cancel()
        error.value = null
        loading.value = true
        jellyfinJob = scope.launch {
            try {
                val challenge = withContext(ioDispatcher) { jellyfin.initiateQuickConnect(serverUrl) }
                jellyfinSecret = challenge.secret
                show(Browse.JellyfinLinking(challenge.code), replace = true)
                loading.value = false
                val deadline = System.currentTimeMillis() + 5 * 60 * 1000
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(2000)
                    val session = withContext(ioDispatcher) {
                        jellyfin.pollQuickConnect(serverUrl, challenge.secret)
                    } ?: continue
                    jellyfinSecret = null
                    loadJellyfinLibraries()
                    return@launch
                }
                error.value = "The Jellyfin code expired. Try again."
                show(Browse.JellyfinSignIn, replace = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                error.value = (problem as? JellyfinException)?.message ?: "Couldn't start Quick Connect."
                if (browse.value is Browse.JellyfinLinking) show(Browse.JellyfinSignIn, replace = true)
            } finally {
                loading.value = false
            }
        }
    }

    fun signOutJellyfin() {
        signOutConfirmation.value = false
        jellyfinJob?.cancel()
        jellyfinSecret = null
        reportJellyfinStopped(force = true)
        activeJellyfin = null
        closeToHome()
        jellyfin.clearSession()
        jellyfinForm.value = JellyfinForm()
        error.value = null
        libraryOpen.value = true
        show(Browse.SignedOut, replace = true)
    }

    private fun loadJellyfinLibraries() {
        launch("Couldn't reach Jellyfin") { generation ->
            val views = withContext(ioDispatcher) { jellyfin.libraryViews() }
            if (!isCurrentWork(generation)) return@launch
            if (views.isEmpty()) throw JellyfinException("This Jellyfin user has no libraries")
            show(
                Browse.JellyfinLibraries(views) { library -> openJellyfinLibrary(library) },
                replace = stack.lastOrNull() is Browse.JellyfinSignIn ||
                    stack.lastOrNull() is Browse.JellyfinLinking || stack.isEmpty(),
            )
        }
    }

    fun openJellyfinLibrary(library: JellyfinLibrary) {
        launch("Couldn't open ${library.title}") { generation ->
            val found = withContext(ioDispatcher) {
                jellyfin.items(
                    parentId = library.id,
                    includeItemTypes = if (library.kind == "show") listOf("Series") else listOf("Movie"),
                )
            }
            if (!isCurrentWork(generation)) return@launch
            show(
                Browse.JellyfinItems(library.title, found) { item ->
                    openJellyfinItem(item, library.title)
                },
            )
        }
    }

    private fun openJellyfinItem(item: JellyfinItem, parentTitle: String) {
        when {
            item.type.equals("Movie", ignoreCase = true) ||
                item.type.equals("Episode", ignoreCase = true) ||
                item.type.equals("Video", ignoreCase = true) -> playJellyfin(item)
            else -> launch("Couldn't open ${item.title}") { generation ->
                val children = withContext(ioDispatcher) { jellyfin.children(item.id) }
                if (!isCurrentWork(generation)) return@launch
                val title = if (parentTitle.isBlank()) item.title else "$parentTitle  /  ${item.title}"
                show(
                    Browse.JellyfinItems(title, children) { child ->
                        openJellyfinItem(child, item.title)
                    },
                )
            }
        }
    }

    private fun playJellyfin(item: JellyfinItem) {
        reconnectJob?.cancel()
        launch("Couldn't play ${item.title}") { generation ->
            val playable = withContext(ioDispatcher) { jellyfin.playable(item) }
            if (!isCurrentWork(generation)) return@launch
            val info = withContext(ioDispatcher) {
                runCatching { jellyfin.playbackInfo(item.id) }.getOrNull()
            }
            if (!isCurrentWork(generation)) return@launch
            saveCheckpoint()
            val resume = item.resumeMs.coerceAtLeast(0L)
            activeJellyfin = ActiveJellyfin(
                itemId = item.id,
                playSessionId = info?.playSessionId,
                sourceId = info?.mediaSources?.firstOrNull()?.id,
            )
            activeMovie = null
            // Set the transport (including stereo) before the panel is created,
            // so its registration can pick the matching stereo mode.
            player.play(playable, resume)
            onPrepareScreen?.invoke()
            libraryOpen.value = false
            onWatching?.invoke(true)
            val started = activeJellyfin
            if (started != null) {
                scope.launch {
                    runCatching {
                        withContext(ioDispatcher) {
                            jellyfin.reportPlaying(started.itemId, started.playSessionId, resume, started.sourceId)
                        }
                    }
                }
            }
            Log.i(TAG, "jellyfin resume position=$resume")
        }
    }

    private fun reportJellyfinProgress(positionMs: Long, ended: Boolean) {
        val active = activeJellyfin ?: return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    if (ended) {
                        jellyfin.reportStopped(active.itemId, active.playSessionId, positionMs)
                    } else {
                        jellyfin.reportProgress(
                            active.itemId, active.playSessionId, positionMs,
                            isPaused = !player.transport.value.playing,
                        )
                    }
                }
            }
        }
    }

    private fun reportJellyfinStopped(force: Boolean = false) {
        val active = activeJellyfin ?: return
        val position = player.transport.value.positionMs
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    jellyfin.reportStopped(active.itemId, active.playSessionId, position)
                }
            }
        }
        if (force) activeJellyfin = null
    }

    fun back() {
        if (signOutConfirmation.value) { cancelSignOut(); return }
        if (scenePickerOpen.value) { closeScenePicker(); return }
        if (weatherSettingsOpen.value) { weatherSettingsOpen.value = false; return }
        if (earthControlsOpen.value) { earthControl(EarthControl.DONE); return }
        if (stack.size <= 1) return
        cancelBrowseWork()
        stack.removeLast()
        resetLibrarySearch()
        browse.value = filterBrowse(stack.last())
        canGoBack.value = stack.size > 1
        error.value = null
        page.value = 0
    }

    fun setPage(value: Int) {
        page.value = value.coerceAtLeast(0)
    }

    /** Handles ray presses in the search row and its on-panel keyboard. */
    fun librarySearchPress(xNorm: Float, yNorm: Float): Boolean {
        val x = xNorm.coerceIn(0f, 0.9999f)
        val y = yNorm.coerceIn(0f, 0.9999f)
        if (!supportsLibrarySearch(browse.value)) return false
        if (y >= PosterGrid.HEADER_END && y < PosterGrid.SEARCH_END) {
            when {
                x < PosterGrid.SEARCH_QUERY_END -> librarySearchKeyboardOpen.value = true
                x < PosterGrid.SEARCH_CLEAR_END -> clearLibrarySearch()
                else -> {
                    val filterIndex = ((x - PosterGrid.SEARCH_FILTER_START) / PosterGrid.SEARCH_FILTER_WIDTH)
                        .toInt().coerceIn(0, LibrarySearchFilter.entries.lastIndex)
                    val selected = LibrarySearchFilter.entries[filterIndex]
                    if (canUseFilter(selected, browse.value)) {
                        setLibrarySearchFilter(selected)
                    }
                }
            }
            return true
        }
        if (librarySearchKeyboardOpen.value && y >= PosterGrid.SEARCH_END && y < PosterGrid.FOOTER_START) {
            when (val key = PosterGrid.libraryKeyboardKeyAt(x, y)) {
                is LibraryKeyboardKey.Character -> appendLibrarySearchCharacter(key.value)
                LibraryKeyboardKey.Backspace -> librarySearchBackspace()
                LibraryKeyboardKey.Space -> appendLibrarySearchCharacter(' ')
                LibraryKeyboardKey.Done -> librarySearchKeyboardOpen.value = false
                null -> Unit
            }
            return true
        }
        return false
    }

    internal fun librarySearchPressForKey(character: Char) = appendLibrarySearchCharacter(character)

    internal fun librarySearchBackspace() {
        librarySearchQuery.value = librarySearchQuery.value.dropLast(1)
        page.value = 0
        refreshFilteredBrowse()
    }

    internal fun librarySearchSpace() = appendLibrarySearchCharacter(' ')

    internal fun librarySearchDone() { librarySearchKeyboardOpen.value = false }

    fun setLibrarySearchQuery(query: String) {
        librarySearchQuery.value = query.take(MAX_LIBRARY_QUERY_LENGTH)
        page.value = 0
        refreshFilteredBrowse()
    }

    fun setLibrarySearchFilter(filter: LibrarySearchFilter) {
        if (!canUseFilter(filter, browse.value)) return
        librarySearchFilter.value = filter
        page.value = 0
        refreshFilteredBrowse()
    }

    fun clearLibrarySearch() {
        librarySearchQuery.value = ""
        librarySearchFilter.value = LibrarySearchFilter.ALL
        librarySearchKeyboardOpen.value = false
        page.value = 0
        refreshFilteredBrowse()
    }

    fun openServer(server: PlexServer) {
        Log.i(TAG, "open server")
        launch("Couldn't open ${server.name}") { generation ->
            val sections = withContext(ioDispatcher) { plex.sections(server) }
            if (!isCurrentWork(generation)) return@launch
            if (sections.isEmpty()) throw PlexException("${server.name} has no TV or movie libraries")
            show(Browse.Sections(server, sections))
        }
    }

    fun openSection(server: PlexServer, section: PlexSection) {
        launch("Couldn't open ${section.title}") { generation ->
            val loadedItems = withContext(ioDispatcher) { plex.library(server, section) }
            if (!isCurrentWork(generation)) return@launch
            val items = loadedItems
                .sortedByDescending { maxOf(it.lastViewedAtMs, history.find(MovieKey(server.id, it.ratingKey))?.lastPlayedAtMs ?: 0L) }
            show(
                Browse.Items(
                    title = section.title,
                    items = items,
                    onOpen = { item -> openChild(server, item, section.title) },
                ),
            )
        }
    }

    fun openLibrary() = closeToHome()

    fun sceneControlPress(x: Float, y: Float): Boolean {
        when (SceneControls.at(x, y) ?: return false) {
            SceneControl.TURN_LEFT -> onViewTurn?.invoke(-SceneControls.TURN_DEGREES)
            SceneControl.TURN_RIGHT -> onViewTurn?.invoke(SceneControls.TURN_DEGREES)
            SceneControl.RETURN_MODELS -> onVisitorsReturn?.invoke()
            SceneControl.EARTH -> openEarthControls()
            SceneControl.SCENES -> openScenePicker()
        }
        return true
    }

    fun openScenePicker() {
        earthControlsOpen.value = false
        weatherSettingsOpen.value = false
        earthReturnToPlayer = false
        scenePickerOpen.value = true
    }

    fun closeScenePicker() {
        scenePickerOpen.value = false
    }

    fun selectLocation(location: TheatreLocation) {
        currentLocation.value = location
        state.saveLocation(location)
        onLocationChanged?.invoke(location)
    }

    fun resetCurrentLocationToDefault() {
        val loc = currentLocation.value
        state.resetSceneSettings(loc)
        onResetSceneSettings?.invoke(loc)
    }

    fun scenePickerPress(x: Float, y: Float): Boolean {
        if (!scenePickerOpen.value) return false
        when (val action = ScenePickerControls.at(x, y)) {
            is ScenePickerAction.SelectLocation -> selectLocation(action.location)
            ScenePickerAction.ResetDefault -> resetCurrentLocationToDefault()
            ScenePickerAction.Done -> closeScenePicker()
            null -> Unit
        }
        return true
    }

    private var earthReturnToPlayer = false
    internal fun earthControl(control: EarthControl) {
        if (control == EarthControl.DONE) {
            earthControlsOpen.value = false
            weatherSettingsOpen.value = false
            earthSettingsOpen.value = false
            viewingPresetsOpen.value = false
            if (earthReturnToPlayer) showPlayer()
            earthReturnToPlayer = false
        } else onEarthControl?.invoke(control)
    }

    fun openEarthControls() {
        scenePickerOpen.value = false
        if (!earthControlsOpen.value) earthReturnToPlayer = !libraryOpen.value
        earthControlsOpen.value = true
        libraryOpen.value = true
        onWatching?.invoke(false)
    }

    internal fun weatherControl(control: WeatherControl) {
        when (control) {
            WeatherControl.DONE -> weatherSettingsOpen.value = false
            WeatherControl.REFRESH -> onWeatherRefresh?.invoke()
            WeatherControl.TOGGLE -> {
                val enabled = !weatherUi.value.enabled
                state.saveLiveWeatherEnabled(enabled)
                weatherUi.value = WeatherUi(enabled, weatherUi.value.place, if (enabled) "Waiting for Earth view" else "Off · original cloud map")
                onWeatherEnabled?.invoke(enabled)
            }
        }
    }

    fun weatherControlPress(x: Float, y: Float): Boolean {
        if (!earthControlsOpen.value) return false
        if (viewingPresetsOpen.value || earthSettingsOpen.value) return false
        if (weatherSettingsOpen.value) {
            WeatherControls.at(x, y)?.let(::weatherControl)
            return true
        }
        if (WeatherControls.opener(x, y)) {
            weatherSettingsOpen.value = true
            return true
        }
        return false
    }

    fun earthControlPress(x: Float, y: Float): Boolean {
        if (!earthControlsOpen.value) return false
        if (earthSettingsOpen.value) {
            EarthSettingsControls.at(x,y)?.let(::earthSetting)
            return true
        }
        if (viewingPresetsOpen.value) {
            PresetControls.at(x, y)?.let { presetControl(it.action, it.slot) }
            return true
        }
        if (PresetControls.opener(x, y)) { viewingPresetsOpen.value = true; return true }
        if (EarthSettingsControls.opener(x,y)) { earthSettingsOpen.value = true; return true }
        EarthControls.at(x, y)?.let(::earthControl)
        return true
    }

    internal fun earthSetting(setting: EarthSetting) {
        when (setting) {
            EarthSetting.LOCATION -> {
                val enabled = !useCurrentLocation.value
                useCurrentLocation.value = enabled
                state.saveUseCurrentLocation(enabled)
                onLocationEnabled?.invoke(enabled)
            }
            EarthSetting.ABOVE_ME -> {
                if (!useCurrentLocation.value) {
                    useCurrentLocation.value = true
                    state.saveUseCurrentLocation(true)
                    onLocationEnabled?.invoke(true)
                } else onLocationRequest?.invoke()
            }
            EarthSetting.ROTATION -> {
                val enabled = !realEarthRotation.value
                realEarthRotation.value = enabled
                state.saveRealEarthRotation(enabled)
                onRealRotation?.invoke(enabled)
            }
            EarthSetting.DONE -> earthSettingsOpen.value = false
            else -> onEarthZoom?.invoke(setting)
        }
    }

    internal fun presetControl(action: PresetAction, slot: Int = 0) {
        if (slot !in 0 until ViewingPreset.SLOTS) return
        when (action) {
            PresetAction.SAVE -> {
                val preset = onCapturePreset?.invoke()
                if (preset == null) { presetMessage.value = "The scene is not ready to save yet."; return }
                state.saveViewingPreset(slot, preset)
                viewingPresets.value = state.viewingPresets()
                presetMessage.value = "Saved view ${slot + 1}."
            }
            PresetAction.RESTORE -> {
                val preset = viewingPresets.value[slot] ?: return
                onRestorePreset?.invoke(preset)
                presetMessage.value = "Restored view ${slot + 1}."
            }
            PresetAction.CLEAR -> {
                state.saveViewingPreset(slot, null)
                viewingPresets.value = state.viewingPresets()
                presetMessage.value = "Cleared view ${slot + 1}."
            }
            PresetAction.RESET -> { onResetView?.invoke(); presetMessage.value = "Reset to the home view." }
            PresetAction.DONE -> viewingPresetsOpen.value = false
        }
    }

    fun earthOrbitScroll(direction: Int): Boolean {
        if (!earthControlsOpen.value) return false
        if (weatherSettingsOpen.value || viewingPresetsOpen.value || earthSettingsOpen.value) return true
        if (direction != 0) earthControl(if (direction < 0) EarthControl.ORBIT_LEFT else EarthControl.ORBIT_RIGHT)
        return true
    }

    fun setScreenHidden(hidden: Boolean) {
        if (screenHidden.value == hidden) return
        screenHidden.value = hidden
        player.setScreenHidden(hidden && !libraryOpen.value)
        onScreenHidden?.invoke(hidden)
    }

    fun requestSignOut() {
        if (screenHidden.value) return
        if (browse.value is Browse.Linking) {
            pinJob?.cancel()
            generation++
            show(Browse.SignedOut, replace = true)
        } else if (browse.value !is Browse.SignedOut) {
            signOutConfirmation.value = true
        }
    }

    fun cancelSignOut() { signOutConfirmation.value = false }

    fun confirmSignOut() {
        if (!signOutConfirmation.value) return
        if (browse.value is Browse.JellyfinLibraries || browse.value is Browse.JellyfinItems ||
            activeJellyfin != null
        ) {
            signOutJellyfin()
        } else {
            signOut()
        }
    }

    /** Same normalized button row as the Compose prompt; all other presses are consumed. */
    fun signOutConfirmationPress(x: Float, y: Float): Boolean {
        if (!signOutConfirmation.value) return false
        if (y in PosterGrid.SIGN_OUT_CONFIRM_TOP..PosterGrid.SIGN_OUT_CONFIRM_BOTTOM) {
            if (x < 0.5f) cancelSignOut() else confirmSignOut()
        }
        return true
    }

    fun closeToHome() {
        reconnectJob?.cancel()
        saveCheckpoint()
        if (activeJellyfin != null) {
            reportJellyfinStopped(force = true)
        }
        activeMovie = null
        history.leavePlayer()
        state.saveHistory()
        cancelBrowseWork()
        player.close()
        libraryOpen.value = true
        setScreenHidden(false)
        signOutConfirmation.value = false
        onWatching?.invoke(false)
        showHome()
        Log.i(TAG, "movie closed to recently watched")
    }

    fun openLibraries() {
        if (servers.isEmpty()) { loadServers(restoreActive = false, browseLibraries = true); return }
        show(Browse.Servers(servers))
        if (servers.size == 1) openServer(servers.first())
    }

    fun sceneReady() {
        if (!libraryOpen.value && player.transport.value.title.isNotBlank()) {
            onPrepareScreen?.invoke()
            onWatching?.invoke(true)
        }
    }

    fun saveCheckpoint() {
        player.publish()
        val value = player.transport.value
        checkpoint(value.positionMs, value.durationMs, false, force = true)
    }

    private fun checkpoint(position: Long, duration: Long, ended: Boolean, force: Boolean = false) {
        if (activeJellyfin != null) {
            val now = SystemClock.uptimeMillis()
            if (force || ended || now - lastCheckpointMs >= 5_000L) {
                lastCheckpointMs = now
                reportJellyfinProgress(position, ended)
                if (ended) activeJellyfin = null
            }
            return
        }
        val key = activeMovie ?: return
        val now = SystemClock.uptimeMillis()
        history.progress(key, position, duration, ended)
        if (ended) activeMovie = null
        if (force || ended || now - lastCheckpointMs >= 5_000L) {
            lastCheckpointMs = now
            state.saveHistory()
            Log.i(TAG, "bookmark saved position=$position ended=$ended")
        }
    }

    private fun showHome() {
        val videos = history.recent().map { record ->
            val server = servers.firstOrNull { it.id == record.key.serverId }
            RecentVideo(server, PlexItem(record.key.ratingKey, record.title, record.subtitle, record.type,
                server?.let { plex.poster(it, record.posterKey) }.orEmpty(), record.posterKey,
                record.resumeMs, record.durationMs, record.lastPlayedAtMs))
        }
        show(Browse.Home(videos) { video ->
            val server = video.server
            if (server == null) error.value = "This Plex server is unavailable" else play(server, video.item)
        }, replace = true)
    }

    fun showPlayer() {
        if (player.transport.value.title.isBlank()) return
        libraryOpen.value = false
        onWatching?.invoke(true)
    }

    fun togglePlayback() = player.toggle()
    fun pausePlayback() = player.pause()

    fun reconnectPlex() {
        if (activeMovie != null) player.retry() else loadServers(restoreActive = false)
    }

    private fun refreshPlaybackConnection() {
        val key = activeMovie ?: return
        saveCheckpoint()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            try {
                val refreshed = withContext(ioDispatcher) { plex.servers() }
                if (activeMovie != key) return@launch
                val server = refreshed.firstOrNull { it.id == key.serverId }
                    ?: throw PlexException("This Plex server is unavailable. Your progress is saved.", PlexFailure.CONNECTION)
                val record = history.find(key) ?: return@launch
                val playable = withContext(ioDispatcher) {
                    plex.playable(server, PlexItem(key.ratingKey, record.title, record.subtitle, record.type))
                }
                if (activeMovie != key) return@launch
                servers = refreshed
                plexLinkRequired.value = false
                player.reconnect(playable)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                if (activeMovie != key) return@launch
                plexLinkRequired.value = (problem as? PlexException)?.failure == PlexFailure.ACCOUNT_AUTH
                player.reconnectFailed((problem as? PlexException)?.message ?: "Couldn't reconnect. Your progress is saved.")
            }
        }
    }

    fun seekBy(deltaMs: Long) = player.seekBy(deltaMs)

    fun seekToFraction(fraction: Float) {
        playerInteraction()
        player.seekToFraction(fraction)
    }

    fun playerInteraction() = onPlayerInteraction?.invoke()

    fun toggleQuietCinema() {
        val enabled = !quietCinemaEnabled.value
        quietCinemaEnabled.value = enabled
        state.saveQuietCinemaEnabled(enabled)
        onQuietCinemaPreference?.invoke(enabled)
    }

    internal fun playerControl(control: PlayerControl) {
        playerInteraction()
        Log.i(TAG, "player action ${control.name}")
        when (control) {
            PlayerControl.BACK -> seekBy(-10_000)
            PlayerControl.TOGGLE -> togglePlayback()
            PlayerControl.FORWARD -> seekBy(10_000)
            PlayerControl.FORWARD_MORE -> seekBy(30_000)
            PlayerControl.LIBRARY -> openLibrary()
            PlayerControl.SMALLER -> onScreenResize?.invoke(ScreenSize.SHRINK)
            PlayerControl.LARGER -> onScreenResize?.invoke(ScreenSize.GROW)
            PlayerControl.ORIGINAL -> player.setQuality(PlaybackQuality.ORIGINAL)
            PlayerControl.UHD -> player.setQuality(PlaybackQuality.UHD)
            PlayerControl.HD -> player.setQuality(PlaybackQuality.HD)
            PlayerControl.AUDIO -> player.cycleAudio()
            PlayerControl.SUBTITLES -> player.cycleSubtitles()
            PlayerControl.QUIET -> toggleQuietCinema()
            PlayerControl.EARTH -> openEarthControls()
            PlayerControl.RECONNECT -> reconnectPlex()
        }
    }

    private fun openChild(server: PlexServer, item: PlexItem, parentTitle: String) {
        when (item.type) {
            "movie", "episode" -> play(server, item)
            else -> launch("Couldn't open ${item.title}") { generation ->
                val children = withContext(ioDispatcher) { plex.children(server, item.ratingKey) }
                if (!isCurrentWork(generation)) return@launch
                val title = if (item.subtitle.isBlank()) item.title else "${item.title}  ${item.subtitle}"
                show(
                    Browse.Items(
                        title = if (parentTitle.isBlank()) title else "$parentTitle  /  $title",
                        items = children,
                        onOpen = { child -> openChild(server, child, item.title) },
                    ),
                )
            }
        }
    }

    private fun play(server: PlexServer, item: PlexItem) {
        reconnectJob?.cancel()
        launch("Couldn't play ${item.title}") { generation ->
            val playable = withContext(ioDispatcher) { plex.playable(server, item) }
            if (!isCurrentWork(generation)) return@launch
            saveCheckpoint()
            val key = MovieKey(server.id, item.ratingKey)
            val resume = history.find(key)?.resumeMs ?: item.viewOffsetMs.coerceAtLeast(0L)
            val posterKey = item.posterKey.takeIf { it.startsWith("/") && !it.contains("?") }.orEmpty()
            history.begin(Bookmark(key, item.title, item.subtitle, item.type, posterKey,
                resume, item.durationMs, System.currentTimeMillis()))
            state.saveHistory()
            activeMovie = key
            // Set the transport (including stereo) before the panel is created,
            // so its registration can pick the matching stereo mode.
            player.play(playable, resume)
            onPrepareScreen?.invoke()
            libraryOpen.value = false
            onWatching?.invoke(true)
            Log.i(TAG, "movie resume position=$resume")
        }
    }

    private fun loadServers(restoreActive: Boolean, browseLibraries: Boolean = false) {
        launch("Couldn't reach Plex") { generation ->
            val loadedServers = withContext(ioDispatcher) { plex.servers() }
            if (!isCurrentWork(generation)) return@launch
            servers = loadedServers
            if (servers.isEmpty()) throw PlexException("No Plex server answered. Check that the server is on.")
            val activeAtStart = history.active
            val imported = withContext(ioDispatcher) {
                servers.flatMap { server ->
                    runCatching { plex.recentlyViewed(server) }.getOrDefault(emptyList()).map { item ->
                        Bookmark(MovieKey(server.id, item.ratingKey), item.title, item.subtitle, item.type,
                            item.posterKey.takeIf { it.startsWith("/") && !it.contains("?") }.orEmpty(),
                            item.viewOffsetMs, item.durationMs, item.lastViewedAtMs, completed = item.viewOffsetMs == 0L)
                    }
                }
            }
            if (!isCurrentWork(generation)) return@launch
            history.importRecent(imported)
            state.saveHistory()
            showHome()
            Log.i(TAG, "recently watched count=${history.recent().size}")
            if (browseLibraries) { openLibraries(); return@launch }
            if (restoreActive && activeAtStart != null) {
                val record = history.find(activeAtStart)
                val server = servers.firstOrNull { it.id == activeAtStart.serverId }
                if (record != null && server != null && !record.completed) {
                    play(server, PlexItem(record.key.ratingKey, record.title, record.subtitle, record.type,
                        posterKey = record.posterKey, viewOffsetMs = record.resumeMs, durationMs = record.durationMs))
                }
            }
        }
    }

    private fun launch(fallback: String, block: suspend (Int) -> Unit) {
        browseJob?.cancel()
        val generation = ++workGeneration
        error.value = null
        plexReconnectAvailable.value = false
        plexLinkRequired.value = false
        loading.value = true
        browseJob = scope.launch {
            try {
                block(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "$fallback (${error.javaClass.simpleName})")
                if (generation == workGeneration) {
                    this@TheatreModel.error.value = (error as? PlexException)?.message
                        ?: (error as? JellyfinException)?.message ?: fallback
                    plexReconnectAvailable.value = true
                    plexLinkRequired.value = (error as? PlexException)?.failure == PlexFailure.ACCOUNT_AUTH
                }
            } finally {
                if (generation == workGeneration) loading.value = false
            }
        }
    }

    private fun cancelBrowseWork() {
        browseJob?.cancel()
        browseJob = null
        workGeneration++
        loading.value = false
    }

    private fun isCurrentWork(generation: Int): Boolean = generation == workGeneration

    private fun show(screen: Browse, replace: Boolean = false) {
        if (replace || stack.isEmpty()) {
            stack.clear()
        }
        stack.addLast(screen)
        resetLibrarySearch()
        browse.value = filterBrowse(screen)
        canGoBack.value = stack.size > 1
        page.value = 0
    }

    private fun resetLibrarySearch() {
        librarySearchQuery.value = ""
        librarySearchFilter.value = LibrarySearchFilter.ALL
        librarySearchKeyboardOpen.value = false
    }

    private fun supportsLibrarySearch(screen: Browse): Boolean = when (screen) {
        is Browse.Home, is Browse.Servers, is Browse.Sections, is Browse.Items,
        is Browse.JellyfinLibraries, is Browse.JellyfinItems -> true
        else -> false
    }

    private fun canUseFilter(filter: LibrarySearchFilter, screen: Browse): Boolean = when (filter) {
        LibrarySearchFilter.ALL -> true
        LibrarySearchFilter.MOVIES, LibrarySearchFilter.TV ->
            screen is Browse.Home || screen is Browse.Sections || screen is Browse.Items ||
                screen is Browse.JellyfinLibraries || screen is Browse.JellyfinItems
        LibrarySearchFilter.CONTINUE -> screen is Browse.Home || screen is Browse.Items ||
            screen is Browse.JellyfinItems
    }

    private fun refreshFilteredBrowse() {
        val source = stack.lastOrNull() ?: browse.value
        browse.value = filterBrowse(source)
    }

    private fun filterBrowse(source: Browse): Browse {
        val query = librarySearchQuery.value.trim()
        val filter = librarySearchFilter.value
        fun matches(title: String, type: String, viewOffsetMs: Long = 0L): Boolean {
            if (query.isNotEmpty() && !title.contains(query, ignoreCase = true)) return false
            return when (filter) {
                LibrarySearchFilter.ALL -> true
                LibrarySearchFilter.MOVIES -> type.equals("movie", ignoreCase = true)
                LibrarySearchFilter.TV -> type.equals("show", ignoreCase = true) ||
                    type.equals("series", ignoreCase = true) ||
                    type.equals("episode", ignoreCase = true) || type.equals("season", ignoreCase = true)
                LibrarySearchFilter.CONTINUE -> viewOffsetMs > 0L
            }
        }
        return when (source) {
            is Browse.Home -> source.copy(videos = source.videos.filter { video ->
                matches(video.item.title, video.item.type, video.item.viewOffsetMs)
            })
            is Browse.Servers -> source.copy(servers = source.servers.filter { server ->
                query.isEmpty() || server.name.contains(query, ignoreCase = true)
            })
            is Browse.Sections -> source.copy(sections = source.sections.filter { section ->
                matches(section.title, section.type)
            })
            is Browse.Items -> source.copy(items = source.items.filter { item ->
                matches(item.title, item.type, item.viewOffsetMs)
            })
            is Browse.JellyfinLibraries -> source.copy(libraries = source.libraries.filter { library ->
                query.isEmpty() || library.title.contains(query, ignoreCase = true)
            })
            is Browse.JellyfinItems -> source.copy(items = source.items.filter { item ->
                matches(item.title, item.type, item.resumeMs)
            })
            else -> source
        }
    }

    private fun appendLibrarySearchCharacter(character: Char) {
        if (librarySearchQuery.value.length >= MAX_LIBRARY_QUERY_LENGTH) return
        librarySearchQuery.value += character
        page.value = 0
        refreshFilteredBrowse()
    }

    private fun redact(text: String): String {
        return text.replace(Regex("(?i)(X-Plex-Token=)[^&\\s\"]+"), "$1***")
    }

    private companion object {
        const val TAG = "Theatre"
        const val MAX_LIBRARY_QUERY_LENGTH = 80
    }
}

enum class LibrarySearchFilter(val label: String) {
    ALL("All"), MOVIES("Movie"), TV("TV"), CONTINUE("Continue"),
}
