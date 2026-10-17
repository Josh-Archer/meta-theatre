package casa.archer.metatheatre

import android.os.SystemClock
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.isdk.IsdkPanelGrabHandle
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.isdk.ResizeMode
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.SessionState
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.HandMicrogestureLocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.ZoneId
import java.io.File

class TheatreActivity : AppSystemActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screenPlayer by lazy { ScreenPlayer(this) }
    private val model by lazy { TheatreModel(this, screenPlayer) }
    private val quietCinema by lazy {
        QuietCinema(model.state) { active ->
            solarRenderer?.setQuietCinema(active)
            spaceVisitorRenderer?.setPaused(active)
        }
    }
    private var ticker: Job? = null
    private var earthRenderer: EarthRenderer? = null
    private var solarRenderer: SolarRenderer? = null
    private var spaceVisitorRenderer: SpaceVisitorRenderer? = null
    private val solarUpdateSchedule = EarthUpdateSchedule()
    private var earthLoad: Job? = null
    private var screen: Entity? = null
    private var grip: ScreenGripSystem? = null
    private var nativeHands: NativeHands? = null
    private var handLocomotion: HandMicrogestureLocomotionSystem? = null
    private var locomotionTargets: LocomotionTargets? = null
    private var immersiveFocused = false
    private val earthExploration = EarthExploration()
    private var isdkSystem: IsdkSystem? = null
    private var videoReady = false
    private var videoWidth = EarthOrbit.screenWidth
    private var videoHeight = EarthOrbit.screenHeight
    private val playerWidth = Stage.PLAYER_WIDTH
    private val playerHeight = Stage.PLAYER_HEIGHT

    override fun registerFeatures(): List<SpatialFeature> {
        return listOf(VRFeature(this, shouldConsumeLeftRightInput = false, inputSystemType = VrInputSystemType.INTERACTION_SDK, handMicrogestureLocomotion = true), ComposeFeature())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.onPrepareScreen = { ensureVideo() }
        model.onWatching = { watching -> applyWatching(watching) }
        model.onScreenHidden = { hidden ->
            grip?.screenHidden = hidden
            applyWatching(!model.libraryOpen.value)
        }
        model.onQuietCinemaPreference = quietCinema::setPreferenceEnabled
        model.onViewTurn = ::turnView
        model.onVisitorsReturn = { spaceVisitorRenderer?.returnModels() }
        model.onEarthControl = ::onEarthControl
        model.onScreenResize = { factor -> grip?.resize(factor) }
        model.onPlayerInteraction = { grip?.playerInteraction() }
        model.start()
        ticker = scope.launch {
            screenPlayer.transport.map { it.playing }.distinctUntilChanged().collectLatest { playing ->
                if (playing) {
                    while (isActive) {
                        delay(250)
                        screenPlayer.publish()
                    }
                }
            }
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.setViewOrigin(0f, 0f, 0f, 0f)
        nativeHands?.close()
        nativeHands = NativeHands(scene)
        // The distant Sun is projected inside the sky; keep close chair/panel depth precision.
        scene.setDepthParams(SolarSystem.NEAR_CLIP, SolarSystem.FAR_CLIP)
        scene.enableHolePunching(true)
        scene.setLightingEnvironment(
            ambientColor = Vector3(0.06f, 0.07f, 0.09f),
            sunColor = Vector3(1.4f, 1.32f, 1.15f),
            sunDirection = (SolarSystem.sunPosition(Instant.now(), ZoneId.systemDefault()) - EarthRenderer.SEAT_EYE).normalize(),
            environmentIntensity = 0.18f,
        )
        // Controller sticks still browse/seek; hand microgestures have their own SDK system.
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        handLocomotion = systemManager.findSystem<HandMicrogestureLocomotionSystem>().also {
            it.enableHandLocomotion(immersiveFocused)
        }
        locomotionTargets = LocomotionTargets()

        buildLocation(TheatreLocation.EARTH_ORBIT)
        spaceVisitorRenderer = SpaceVisitorRenderer().also {
            it.setWorldFrame(earthExploration.worldFrame)
            it.create()
            it.setPaused(quietCinema.active)
        }
        earthLoad = scope.launch {
            val earth = EarthRenderer(this@TheatreActivity)
            earthRenderer = earth
            earth.setExploration(earthExploration.worldFrame, earthExploration.spin)
            try {
                earth.create()
                val solar = SolarRenderer(this@TheatreActivity)
                solarRenderer = solar
                solar.setWorldFrame(earthExploration.worldFrame)
                solar.create()
                solar.setQuietCinema(quietCinema.active)
                updateSolarEnvironment(force = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Solar environment could not load", error)
            }
        }
        // Mesh, so the Link Plex view is on the quad. Corners resize.
        // Grip-to-move is ScreenGripSystem. The video panel waits until playback.
        val panel = Entity.create(
            Panel(R.id.library_panel, MeshCollision.LineTest),
            Transform(EarthOrbit.screenPose),
            IsdkPanelDimensions(Vector2(EarthOrbit.screenWidth, EarthOrbit.screenHeight)),
            IsdkPanelResize(
                enabled = true,
                resizeMode = ResizeMode.Relayout,
                minDimensions = Vector2(1.2f, 1.2f * 9f / 16f),
                maxDimensions = Vector2(8f, 8f * 9f / 16f),
                preserveAspectRatio = true,
            ),
            IsdkGrabbable(enabled = true),
            IsdkPanelGrabHandle(
                grabHandleCollisionWidths = Vector4(0.10f, 0.10f, 0.10f, 0.10f),
                resizeCornerCollisionSizes = Vector4(0.22f, 0.22f, 0.22f, 0.22f),
                outset = Vector4(0.04f, 0.04f, 0.04f, 0.04f),
                zOffset = 0.08f,
                color = Vector4(0.84f, 0.71f, 0.43f, 1f),
            ),
        )
        screen = panel
        if (grip == null) {
            val system = ScreenGripSystem(
                panel, ::onScreenPress, ::onShelfScroll,
                onPlayerVisibility = { model.playerControlsOpen.value = it },
                playerTargets = model.playerHitTargets,
                onPlayerControl = { control -> scope.launch { model.playerControl(control) } },
                onSeek = { delta -> scope.launch { model.seekBy(delta) } },
                onSeekPreview = { model.seekPreview.value = it },
                onSeekFraction = { fraction -> scope.launch { model.seekToFraction(fraction) } },
                onSizeSaved = { model.state.saveSize(it) },
                readOpenHands = { nativeHands?.read().orEmpty() },
                onScreenHiddenChange = { hidden -> scope.launch { model.setScreenHidden(hidden) } },
                handsNavigating = { handLocomotion?.areHandsInUse() == true },
                viewerPose = { scene.getViewerPose() },
                visitors = spaceVisitorRenderer,
                onViewTurn = ::turnView,
                earthTarget = { earthRenderer?.target() },
                onEarthSpin = ::spinEarth,
                onPause = { scope.launch { model.pausePlayback() } },
                mediaActive = { screenPlayer.transport.value.title.isNotBlank() },
                hasSubtitleCues = { screenPlayer.subtitleCues.value.isNotEmpty() },
            )
            grip = system
            system.screenHidden = model.screenHidden.value
            system.restoreSize(model.state.screenSize())
            systemManager.registerLateSystem(system)
        }
        if (isdkSystem == null) {
            isdkSystem = systemManager.findSystem<IsdkSystem>().also {
                it.setScenePointerDistance(15f)
            }
        }
        Log.i(TAG, "theatre screen spawned")
        model.sceneReady()
        runVideoSurfaceTest()
    }

    /** ADB-only local fixture for checking the real compositor and UHD decoder without Plex credentials. */
    private fun runVideoSurfaceTest() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            !intent.getBooleanExtra("metatheatre.test_video", false)) return
        val fixture = File(cacheDir, "video_surface_test.mp4")
        if (!fixture.isFile) return
        ensureVideo()
        val uri = fixture.toURI().toString()
        screenPlayer.play(Playable(
            "4K display test", uri, uri, emptyMap(), "Local test pattern",
            sourceWidth = 3840, sourceHeight = 2160,
        ))
        model.libraryOpen.value = false
        applyWatching(true)
        Log.i(TAG, "local UHD display test started")
    }

    private fun ensureVideo() {
        val parent = screen ?: return
        if (videoReady) return
        videoReady = true
        val size = grip?.screenSize() ?: ScreenSize(EarthOrbit.screenWidth, EarthOrbit.screenHeight)
        videoWidth = size.width
        videoHeight = size.height
        val parked = Pose(Vector3(0f, -8f, 0f))
        val video = Entity.create(
            Panel(R.id.screen_panel, MeshCollision.NoCollision),
            Transform(parked),
        )
        val player = Entity.create(
            Panel(R.id.player_panel, MeshCollision.LineTest),
            Transform(parked),
            IsdkPanelDimensions(Vector2(playerWidth, playerHeight)),
            Visible(false),
        )
        val subtitles = Entity.create(
            Panel(R.id.subtitle_panel, MeshCollision.NoCollision),
            Transform(parked),
            Visible(false),
        )
        grip?.overlays = ScreenOverlays(
            video = video,
            player = player,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            subtitles = subtitles,
        )
        grip?.syncOverlays()
        Log.i(TAG, "video panel spawned")
    }

    private fun applyWatching(watching: Boolean) {
        val hidden = model.screenHidden.value
        quietCinema.setWatching(watching && !hidden)
        screenPlayer.setScreenHidden(watching && hidden)
        val panel = screen ?: return
        Log.i(TAG, if (watching) "show movie" else "show library")
        // Keep the transparent panel carrier alive: ISDK grab/resize handles belong to it.
        runCatching { panel.setComponent(Visible(!hidden)) }
        panel.setComponent(
            IsdkPanelResize(
                enabled = !hidden,
                resizeMode = ResizeMode.Relayout,
                minDimensions = Vector2(1.2f, 1.2f * 9f / 16f),
                maxDimensions = Vector2(8f, 8f * 9f / 16f),
                preserveAspectRatio = true,
            ),
        )
        panel.setComponent(IsdkGrabbable(enabled = !hidden))
        grip?.watching = watching
        grip?.syncOverlays()
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            screenRegistration(),
            playerRegistration(),
            subtitleRegistration(),
            libraryRegistration(R.id.library_panel),
        )
    }

    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        immersiveFocused = state == SessionState.FOCUSED
        handLocomotion?.enableHandLocomotion(immersiveFocused)
        if (!immersiveFocused) {
            locomotionTargets?.show(false)
            spaceVisitorRenderer?.cancelGrab()
            grip?.cancelEarthGrab()
        }
        // Resample civil time immediately after focus/visibility changes, including headset wake.
        updateSolarEnvironment(force = true)
        when (state) {
            SessionState.VISIBLE -> { screenPlayer.onMenuOpened(); model.saveCheckpoint(); grip?.saveScreenSize() }
            SessionState.FOCUSED -> screenPlayer.onMenuClosed()
            else -> Unit
        }
    }

    override fun onPause() {
        immersiveFocused = false
        spaceVisitorRenderer?.cancelGrab()
        grip?.cancelEarthGrab()
        handLocomotion?.enableHandLocomotion(false)
        locomotionTargets?.show(false)
        model.saveCheckpoint()
        grip?.saveScreenSize()
        super.onPause()
    }

    override fun onDestroy() {
        grip?.saveScreenSize()
        nativeHands?.close()
        nativeHands = null
        handLocomotion = null
        locomotionTargets = null
        isdkSystem = null
        earthLoad?.cancel()
        ticker?.cancel()
        scope.cancel()
        model.release()
        screenPlayer.release()
        earthRenderer?.unregister()
        solarRenderer?.unregister()
        super.onDestroy()
        earthRenderer?.release()
        solarRenderer?.release()
        spaceVisitorRenderer?.release()
    }

    override fun onSceneTick() {
        super.onSceneTick()
        locomotionTargets?.show(handLocomotion?.areHandsInUse() == true)
        // A solar cycle changes by only 0.0042 degrees per second; no frame-rate updates needed.
        updateSolarEnvironment()
        spaceVisitorRenderer?.update()
    }

    private fun updateSolarEnvironment(force: Boolean = false) {
        if (!solarUpdateSchedule.shouldUpdate(SystemClock.uptimeMillis(), force)) return
        // Sample once so the visible Sun, Earth, planets, Moon, rings and chair share the same instant.
        val instant = Instant.now()
        val zone = ZoneId.systemDefault()
        earthRenderer?.update(instant, zone)
        solarRenderer?.update(instant, zone)
    }

    private fun screenRegistration(): PanelRegistration {
        return VideoSurfacePanelRegistration(
            R.id.screen_panel,
            surfaceConsumer = { _, surface ->
                Log.i(TAG, "video surface attached")
                screenPlayer.attach(surface)
            },
            settingsCreator = {
                MediaPanelSettings(
                    shape = QuadShapeOptions(width = videoWidth, height = videoHeight),
                    display = PixelDisplayOptions(width = MovieQuality.UHD.width, height = MovieQuality.UHD.height),
                    rendering = MediaPanelRenderOptions(
                        stereoMode = StereoMode.None,
                        zIndex = -1,
                    ),
                    style = object : PanelStyleOptions() {
                        override fun applyTo(options: PanelConfigOptions) {
                            super.applyTo(options)
                            // Clear the scene at the video quad so its full-resolution underlay is visible.
                            // Mesh controls and the pointer can still draw in front of it.
                            options.alphaMode = AlphaMode.HOLE_PUNCH
                            options.panelShader = SceneMaterial.HOLE_PUNCH_PANEL_SHADER
                        }
                    },
                )
            },
            panelSetup = { panel, _ ->
                panel.materials?.forEach { it.setSidedness(MaterialSidedness.DOUBLE_SIDED) }
                scope.launch {
                    // Panel creation is asynchronous: reapply after the SDK registers its scene object.
                    yield()
                    grip?.syncOverlays(force = true)
                    Log.i(TAG, "video panel ready; transform reapplied")
                }
            },
        )
    }

    private fun playerRegistration(): PanelRegistration {
        return ComposeViewPanelRegistration(
            R.id.player_panel,
            composeViewCreator = { _, ctx ->
                ComposeView(ctx).apply {
                    setContent { PlayerBar(model, screenPlayer) }
                }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = playerWidth, height = playerHeight),
                    style = object : PanelStyleOptions(themeResourceId = R.style.PlayerPanelTheme) {
                        override fun applyTo(options: PanelConfigOptions) {
                            super.applyTo(options)
                            options.alphaMode = AlphaMode.TRANSLUCENT
                        }
                    },
                    display = DpDisplayOptions(
                        width = 560f,
                        height = 1640f,
                        dpi = 160,
                    ),
                    rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
                )
            },
            panelSetupWithComposeView = { _, _, _ ->
                scope.launch {
                    yield()
                    grip?.syncOverlays(force = true)
                    Log.i(TAG, "player panel ready; transform reapplied")
                }
            },
        )
    }

    private fun subtitleRegistration(): PanelRegistration {
        return ComposeViewPanelRegistration(
            R.id.subtitle_panel,
            composeViewCreator = { _, ctx ->
                ComposeView(ctx).apply { setContent { SubtitlePanel(screenPlayer) } }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = videoWidth, height = videoHeight),
                    style = object : PanelStyleOptions(themeResourceId = R.style.PlayerPanelTheme) {
                        override fun applyTo(options: PanelConfigOptions) {
                            super.applyTo(options)
                            options.alphaMode = AlphaMode.TRANSLUCENT
                        }
                    },
                    display = DpDisplayOptions(width = 1600f, height = 900f, dpi = 160),
                    rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
                )
            },
            panelSetupWithComposeView = { _, _, _ ->
                scope.launch { yield(); grip?.syncOverlays(force = true) }
            },
        )
    }

    private fun libraryRegistration(panelId: Int): PanelRegistration {
        return ComposeViewPanelRegistration(
            panelId,
            composeViewCreator = { _, ctx ->
                ComposeView(ctx).apply {
                    setContent { LibraryPanel(model, screenPlayer) }
                }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(
                        width = EarthOrbit.screenWidth,
                        height = EarthOrbit.screenHeight,
                    ),
                    style = object : PanelStyleOptions(themeResourceId = R.style.PanelTheme) {
                        override fun applyTo(options: PanelConfigOptions) {
                            super.applyTo(options)
                            options.alphaMode = AlphaMode.TRANSLUCENT
                        }
                    },
                    display = DpDisplayOptions(width = 1600f, height = 900f, dpi = 160),
                    rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
                )
            },
        )
    }

    private fun turnView(degrees: Float) {
        if (!immersiveFocused) return
        spaceVisitorRenderer?.cancelGrab()
        grip?.cancelEarthGrab()
        scene.updateViewOrigin(0f, degrees)
    }

    private fun spinEarth(degrees: Float) {
        if (!immersiveFocused) return
        earthExploration.spinBy(degrees)
        earthRenderer?.setExploration(earthExploration.worldFrame, earthExploration.spin)
    }

    private fun onEarthControl(control: EarthControl) {
        if (!immersiveFocused) return
        grip?.cancelEarthGrab()
        spaceVisitorRenderer?.cancelGrab()
        if (control == EarthControl.HOME) {
            scene.setViewOrigin(0f, 0f, 0f, 0f)
            grip?.bringScreenToView()
        }
        earthExploration.apply(control, scene.getViewerPose())
        model.earthViewLabel.value = earthExploration.label()
        earthRenderer?.setExploration(earthExploration.worldFrame, earthExploration.spin)
        solarRenderer?.setWorldFrame(earthExploration.worldFrame)
        spaceVisitorRenderer?.setWorldFrame(earthExploration.worldFrame)
        updateSolarEnvironment(force = true)
    }

    private fun onScreenPress(xNorm: Float, yNorm: Float) {
        if (model.screenHidden.value || grip?.interactive == false) return
        val x = xNorm.coerceIn(0f, 1f)
        val y = yNorm.coerceIn(0f, 1f)
        if (model.signOutConfirmationPress(x, y)) return
        val watching = !model.libraryOpen.value && screenPlayer.transport.value.title.isNotBlank()
        if (watching) {
            onMoviePress()
            return
        }
        if (model.earthControlPress(x, y)) return
        if (model.sceneControlPress(x, y)) return
        if (model.librarySearchPress(x, y)) return
        val browse = model.browse.value
        if (browse is Browse.SignedOut) {
            model.link()
            return
        }
        if (y < PosterGrid.HEADER_END) {
            onHeaderPress(x)
            return
        }
        if (y >= PosterGrid.FOOTER_START) {
            onShelfFooter(x, browse)
            return
        }
        if (model.loading.value) return
        onShelfPress(browse, x, y)
    }

    private fun onMoviePress() {
        Log.i(TAG, "video toggle")
        model.togglePlayback()
    }

    private fun onHeaderPress(x: Float) {
        when {
            x < PosterGrid.BACK_X && model.canGoBack.value -> {
                Log.i(TAG, "back")
                model.back()
            }
            x >= PosterGrid.PLAYER_X && x < PosterGrid.SIGN_OUT_X ->
                if (model.browse.value is Browse.Home) model.openLibraries() else model.showPlayer()
            x >= PosterGrid.SIGN_OUT_X -> {
                model.requestSignOut()
            }
        }
    }

    private fun onShelfFooter(x: Float, browse: Browse) {
        val total = shelfSize(browse)
        val start = PosterGrid.window(total, model.page.value).start
        when {
            x < PosterGrid.PREV_X && start > 0 -> model.setPage(start - 1)
            x >= PosterGrid.NEXT_X && start < PosterGrid.maxStart(total) -> model.setPage(start + 1)
        }
    }

    private fun onShelfPress(browse: Browse, x: Float, y: Float) {
        val total = shelfSize(browse)
        val index = PosterGrid.indexAt(x, y, total, model.page.value) ?: return
        Log.i(TAG, "shelf $index of $total")
        when (browse) {
            is Browse.Home -> browse.videos.getOrNull(index)?.let(browse.onOpen)
            is Browse.Servers -> browse.servers.getOrNull(index)?.let { server ->
                Log.i(TAG, "select server ${index + 1} of ${browse.servers.size}")
                model.openServer(server)
            }
            is Browse.Sections -> browse.sections.getOrNull(index)?.let { section ->
                model.openSection(browse.server, section)
            }
            is Browse.Items -> browse.items.getOrNull(index)?.let { item -> browse.onOpen(item) }
            else -> Unit
        }
    }

    private fun onShelfScroll(delta: Int) {
        if (model.earthOrbitScroll(delta)) return
        if (!model.libraryOpen.value) return
        val total = shelfSize(model.browse.value)
        val start = PosterGrid.window(total, model.page.value).start
        val next = (start + delta).coerceIn(0, PosterGrid.maxStart(total))
        if (next != start) model.setPage(next)
    }

    private companion object {
        const val TAG = "Theatre"
    }
}
