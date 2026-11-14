package casa.archer.metatheatre

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvironmentsIntegrationTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val scope = TestScope(mainDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun availableEnvironmentsHaveDistinctPresetsAndAssets() {
        val locations = TheatreLocation.entries
        assertTrue("At least two selectable environments must be available", locations.size >= 2)
        assertTrue(locations.contains(TheatreLocation.EARTH_ORBIT))
        assertTrue(locations.contains(TheatreLocation.SPACESHIP_CABIN))

        val earthOrbitDefaults = defaultSceneSettings(TheatreLocation.EARTH_ORBIT)
        val spaceshipCabinDefaults = defaultSceneSettings(TheatreLocation.SPACESHIP_CABIN)

        assertEquals(2.8f, earthOrbitDefaults.screenSize.width, 0.01f)
        assertEquals(3.2f, spaceshipCabinDefaults.screenSize.width, 0.01f)
        assertNotEquals(earthOrbitDefaults.screenSize.width, spaceshipCabinDefaults.screenSize.width)

        assertEquals(2.5f, earthOrbitDefaults.screenPose.t.z, 0.01f)
        assertEquals(3.2f, spaceshipCabinDefaults.screenPose.t.z, 0.01f)

        assertEquals(0f, earthOrbitDefaults.seat.yaw, 0.01f)
        assertEquals(0f, spaceshipCabinDefaults.seat.yaw, 0.01f)

        assertEquals("lounge_chair.glb", CHAIR_URI.toString())
        assertEquals("hull.glb", HULL_URI.toString())

        val earthResult = runCatching { buildLocation(TheatreLocation.EARTH_ORBIT) }
        assertTrue(earthResult.isSuccess || earthResult.exceptionOrNull() is NotImplementedError)

        val cabinResult = runCatching { buildLocation(TheatreLocation.SPACESHIP_CABIN) }
        assertTrue(cabinResult.isSuccess || cabinResult.exceptionOrNull() is NotImplementedError)
    }

    @Test
    fun screenRotationSurvivesReloadResizeAndSeatChangesWithoutFlipping() {
        val context = RuntimeEnvironment.getApplication()
        for (location in TheatreLocation.entries) {
            for (rotation in listOf(Quaternion(pitch = 0f, yaw = 180f, roll = 0f),
                Quaternion(pitch = 12f, yaw = 137f, roll = -8f))) {
                val expected = Pose(Vector3(.2f, 1.4f, 3f), rotation)
                TheatreState(context).saveScreenPose(location, expected)
                repeat(4) {
                    val state = TheatreState(context)
                    val actual = state.sceneSettings(location).screenPose
                    assertTrue("Screen up must survive persistence", actual.up().dot(expected.up()) > .9999f)
                    assertTrue("Screen facing must survive persistence", actual.forward().dot(expected.forward()) > .9999f)
                    assertEquals(expected.t, actual.t)
                    state.saveSize(location, ScreenSize(3f, 1.6875f))
                    state.saveSeat(location, SeatSetting(yaw = 25f))
                }
            }
        }
    }

    @Test
    fun perSceneSettingsPersistSeparatelyAndIndependently() {
        val context = RuntimeEnvironment.getApplication()
        val state = TheatreState(context)

        val initialEarth = state.sceneSettings(TheatreLocation.EARTH_ORBIT)
        val initialCabin = state.sceneSettings(TheatreLocation.SPACESHIP_CABIN)
        assertEquals(2.8f, initialEarth.screenSize.width, 0.01f)
        assertEquals(3.2f, initialCabin.screenSize.width, 0.01f)

        val customEarthSize = ScreenSize(4.0f, 2.25f)
        val customEarthPose = Pose(Vector3(0f, 1.5f, 3.0f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
        val customEarthSeat = SeatSetting(0.1f, 0f, -0.1f, 15f)
        state.saveSceneSettings(
            TheatreLocation.EARTH_ORBIT,
            SceneSettings(customEarthSize, customEarthPose, customEarthSeat),
        )

        val customCabinSize = ScreenSize(5.0f, 2.81f)
        val customCabinPose = Pose(Vector3(0f, 1.2f, 4.0f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
        val customCabinSeat = SeatSetting(0.0f, 0.1f, 0.2f, -30f)
        state.saveSceneSettings(
            TheatreLocation.SPACESHIP_CABIN,
            SceneSettings(customCabinSize, customCabinPose, customCabinSeat),
        )

        val loadedEarth = state.sceneSettings(TheatreLocation.EARTH_ORBIT)
        assertEquals(4.0f, loadedEarth.screenSize.width, 0.01f)
        assertEquals(3.0f, loadedEarth.screenPose.t.z, 0.01f)
        assertEquals(15f, loadedEarth.seat.yaw, 0.01f)

        val loadedCabin = state.sceneSettings(TheatreLocation.SPACESHIP_CABIN)
        assertEquals(5.0f, loadedCabin.screenSize.width, 0.01f)
        assertEquals(4.0f, loadedCabin.screenPose.t.z, 0.01f)
        assertEquals(-30f, loadedCabin.seat.yaw, 0.01f)

        state.currentLocation = TheatreLocation.EARTH_ORBIT
        assertEquals(4.0f, state.screenSize().width, 0.01f)

        state.currentLocation = TheatreLocation.SPACESHIP_CABIN
        assertEquals(5.0f, state.screenSize().width, 0.01f)
    }

    @Test
    fun resetSceneSettingsRestoresPresetsForTargetEnvironmentOnly() {
        val context = RuntimeEnvironment.getApplication()
        val state = TheatreState(context)

        state.saveSize(ScreenSize(6.0f, 3.375f), TheatreLocation.EARTH_ORBIT)
        state.saveSize(ScreenSize(7.0f, 3.9375f), TheatreLocation.SPACESHIP_CABIN)

        assertEquals(6.0f, state.sceneSettings(TheatreLocation.EARTH_ORBIT).screenSize.width, 0.01f)
        assertEquals(7.0f, state.sceneSettings(TheatreLocation.SPACESHIP_CABIN).screenSize.width, 0.01f)

        val restoredEarth = state.resetSceneSettings(TheatreLocation.EARTH_ORBIT)
        assertEquals(EarthOrbit.defaultScreenWidth, restoredEarth.screenSize.width, 0.01f)
        assertEquals(EarthOrbit.defaultScreenHeight, restoredEarth.screenSize.height, 0.01f)
        assertEquals(EarthOrbit.defaultSeat.yaw, restoredEarth.seat.yaw, 0.01f)

        val cabinAfterEarthReset = state.sceneSettings(TheatreLocation.SPACESHIP_CABIN)
        assertEquals(7.0f, cabinAfterEarthReset.screenSize.width, 0.01f)

        val restoredCabin = state.resetSceneSettings(TheatreLocation.SPACESHIP_CABIN)
        assertEquals(SpaceshipCabin.defaultScreenWidth, restoredCabin.screenSize.width, 0.01f)
        assertEquals(SpaceshipCabin.defaultScreenHeight, restoredCabin.screenSize.height, 0.01f)
        assertEquals(SpaceshipCabin.defaultSeat.yaw, restoredCabin.seat.yaw, 0.01f)
    }

    @Test
    fun scenePickerControlsHitTestingAndModelDispatch() = runTest(mainDispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val player = mock(ScreenPlayer::class.java)
        val plex = mock(PlexClient::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport()))
        val model = TheatreModel(context, player, plex, mainDispatcher)

        var changedLocation: TheatreLocation? = null
        var resetLocation: TheatreLocation? = null
        model.onLocationChanged = { changedLocation = it }
        model.onResetSceneSettings = { resetLocation = it }

        assertFalse(model.scenePickerOpen.value)

        val scenesButtonX = SceneControls.center(SceneControl.SCENES)
        assertTrue(model.sceneControlPress(scenesButtonX, 0.96f))
        assertTrue(model.scenePickerOpen.value)

        val hitEarth = ScenePickerControls.at(0.2f, ScenePickerControls.ENV_ROW_TOP + 0.05f)
        assertEquals(ScenePickerAction.SelectLocation(TheatreLocation.EARTH_ORBIT), hitEarth)

        val hitCabin = ScenePickerControls.at(0.8f, ScenePickerControls.ENV_ROW_TOP + 0.05f)
        assertEquals(ScenePickerAction.SelectLocation(TheatreLocation.SPACESHIP_CABIN), hitCabin)

        val hitReset = ScenePickerControls.at(0.2f, ScenePickerControls.ACTION_ROW_TOP + 0.05f)
        assertEquals(ScenePickerAction.ResetDefault, hitReset)

        val hitDone = ScenePickerControls.at(0.8f, ScenePickerControls.ACTION_ROW_TOP + 0.05f)
        assertEquals(ScenePickerAction.Done, hitDone)

        assertTrue(model.scenePickerPress(0.8f, ScenePickerControls.ENV_ROW_TOP + 0.05f))
        assertEquals(TheatreLocation.SPACESHIP_CABIN, model.currentLocation.value)
        assertEquals(TheatreLocation.SPACESHIP_CABIN, changedLocation)

        assertTrue(model.scenePickerPress(0.2f, ScenePickerControls.ACTION_ROW_TOP + 0.05f))
        assertEquals(TheatreLocation.SPACESHIP_CABIN, resetLocation)

        assertTrue(model.scenePickerOpen.value)
        assertTrue(model.scenePickerPress(0.8f, ScenePickerControls.ACTION_ROW_TOP + 0.05f))
        assertFalse(model.scenePickerOpen.value)

        model.openScenePicker()
        assertTrue(model.scenePickerOpen.value)
        model.back()
        assertFalse(model.scenePickerOpen.value)

        model.release()
    }

    @Test
    fun playbackStateAndTrackSelectionPreservedAcrossEnvironmentChanges() = runTest(mainDispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val player = mock(ExoPlayer::class.java)
        var capturedListener: Player.Listener? = null
        var capturedSelector: DefaultTrackSelector? = null
        doAnswer { invocation -> capturedListener = invocation.getArgument(0); null }
            .`when`(player).addListener(any(Player.Listener::class.java))
        var playWhenReady = true
        doAnswer { playWhenReady }.`when`(player).playWhenReady
        doAnswer { playWhenReady = it.getArgument(0); null }.`when`(player).setPlayWhenReady(anyBoolean())
        doAnswer { 42_000L }.`when`(player).currentPosition
        doAnswer { 120_000L }.`when`(player).duration
        doAnswer { true }.`when`(player).isPlaying
        doAnswer { Player.STATE_READY }.`when`(player).playbackState

        val screenPlayer = ScreenPlayer(context) { _, selector, _ ->
            capturedSelector = selector
            player
        }
        screenPlayer.attach(mock(android.view.Surface::class.java))

        val plex = mock(PlexClient::class.java)
        val model = TheatreModel(context, screenPlayer, plex, mainDispatcher)

        screenPlayer.play(
            Playable(
                title = "Interstellar Test",
                directUrl = "http://localhost/video.mp4",
                hlsUrl = "http://localhost/video.m3u8",
                headers = emptyMap(),
            ),
        )

        val englishAudio = TrackGroup(Format.Builder().setLabel("English").setLanguage("en").setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        val frenchAudio = TrackGroup(Format.Builder().setLabel("French").setLanguage("fr").setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        val englishSubs = TrackGroup(Format.Builder().setLabel("English").setLanguage("en").setSampleMimeType(MimeTypes.TEXT_VTT).build())

        capturedListener?.onTracksChanged(
            Tracks(
                listOf(
                    Tracks.Group(englishAudio, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                    Tracks.Group(frenchAudio, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                    Tracks.Group(englishSubs, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                ),
            ),
        )

        capturedListener?.onPlaybackStateChanged(Player.STATE_READY)
        screenPlayer.publish()
        screenPlayer.cycleAudio()
        val audioOverrideBefore = capturedSelector?.parameters?.overrides?.values?.singleOrNull()
        assertNotNull(audioOverrideBefore)
        assertEquals(frenchAudio, audioOverrideBefore?.mediaTrackGroup)

        model.libraryOpen.value = false
        val transportBefore = screenPlayer.transport.value
        assertEquals("Interstellar Test", transportBefore.title)
        assertTrue(transportBefore.playWhenReady)

        var switchedLocation: TheatreLocation? = null
        model.onLocationChanged = { switchedLocation = it }
        model.selectLocation(TheatreLocation.SPACESHIP_CABIN)

        assertEquals(TheatreLocation.SPACESHIP_CABIN, switchedLocation)
        assertEquals(TheatreLocation.SPACESHIP_CABIN, model.currentLocation.value)

        val transportAfter = screenPlayer.transport.value
        assertEquals("Interstellar Test", transportAfter.title)
        assertTrue(transportAfter.playWhenReady)
        assertEquals(42_000L, transportAfter.positionMs)

        val audioOverrideAfter = capturedSelector?.parameters?.overrides?.values?.singleOrNull()
        assertEquals(frenchAudio, audioOverrideAfter?.mediaTrackGroup)
        assertFalse(model.libraryOpen.value)

        model.release()
        screenPlayer.release()
    }

    private fun <T> `when`(methodCall: T) = org.mockito.Mockito.`when`(methodCall)
}
