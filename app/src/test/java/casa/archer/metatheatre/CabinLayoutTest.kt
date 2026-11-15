package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CabinLayoutTest {
    private fun near(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, .001f)
        assertEquals(expected.y, actual.y, .001f)
        assertEquals(expected.z, actual.z, .001f)
    }

    @Test fun shipTurnPreservesViewerScreenRelationshipAndRoundTrips() {
        val viewer = Pose(Vector3(2f, 1.45f, -.3f), Quaternion(0f, 152f, 0f))
        val screen = SpaceshipCabin.defaultScreenPose
        val before = viewer.inverse() * screen
        val after = CabinLayout.rotate(viewer, 75f).inverse() * CabinLayout.rotate(screen, 75f)
        near(before.t, after.t)
        near(before.q * Vector3(0f, 0f, 1f), after.q * Vector3(0f, 0f, 1f))
        near(screen.t, CabinLayout.rotate(CabinLayout.rotate(screen, 75f), -75f).t)
        val seat = CabinLayout.rotate(SeatSetting(2f, -.3f, 1f, 350f), 30f)
        assertEquals(20f, seat.yaw, .001f)
        assertEquals(-.3f, seat.y, .001f)
    }

    @Test fun couchTeleportPlacesEyesAboveCushionForStandingOrSeatedWearer() {
        for (seat in CabinSeat.entries) for (height in listOf(1.1f, 1.8f)) for (shipYaw in listOf(0f, 90f, 240f)) {
            val origin = SeatSetting(.8f, .1f, -.2f, 30f)
            val viewer = Pose(Vector3(1.1f, height, .2f), Quaternion(0f, 55f, 0f))
            val result = CabinLayout.seatedOrigin(seat, shipYaw, origin, viewer)
            val newEye = Vector3(result.x, result.y + viewer.t.y - origin.y, result.z)
            near(CabinLayout.rotate(Pose(seat.eye), shipYaw).t, newEye)
            val forward = Quaternion(0f, result.yaw, 0f) * Vector3(0f, 0f, 1f)
            val wall = CabinLayout.rotate(SpaceshipCabin.defaultScreenPose, shipYaw).t
            val direction = Vector3(wall.x - newEye.x, 0f, wall.z - newEye.z).normalize()
            near(direction, forward)
        }
    }

    @Test fun cabinControlsAreDistinctFromOrbitAndRejectInvalidHits() {
        val cabin = TheatreLocation.SPACESHIP_CABIN
        val seatY = (ScenePickerControls.FLIGHT_ROW_TOP + ScenePickerControls.FLIGHT_ROW_BOTTOM) / 2
        val turnY = (ScenePickerControls.FLIGHT_ROW_BOTTOM + ScenePickerControls.ROADSTER_ROW_BOTTOM) / 2
        CabinSeat.entries.forEachIndexed { i, seat ->
            assertEquals(ScenePickerAction.Sit(seat), ScenePickerControls.at((i + .5f) / 3, seatY, cabin))
        }
        assertEquals(ScenePickerAction.TurnShip(-15f), ScenePickerControls.at(.1f, turnY, cabin))
        assertEquals(ScenePickerAction.PinScreen, ScenePickerControls.at(.5f, turnY, cabin))
        assertEquals(ScenePickerAction.TurnShip(15f), ScenePickerControls.at(.9f, turnY, cabin))
        assertEquals(ScenePickerAction.LaunchStarship, ScenePickerControls.at(.1f, seatY))
        assertEquals(ScenePickerAction.ReplayRoadster, ScenePickerControls.at(.5f, turnY))
        assertNull(ScenePickerControls.at(Float.NaN, seatY, cabin))
        assertNull(ScenePickerControls.at(1f, seatY, cabin))
    }

    @Test fun headingSurvivesOtherSettingsUpdatesAndLegacyCustomPlacementsRemain() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("theatre_state", 0)
        prefs.edit().clear().commit()
        val state = TheatreState(context)
        val loc = TheatreLocation.SPACESHIP_CABIN
        val default = defaultSceneSettings(loc)
        state.saveSceneSettings(loc, default.copy(shipYaw = 75f))
        state.saveSeat(loc, SeatSetting(1f, 0f, 2f, 45f))
        state.saveSize(loc, ScreenSize(4f, 2.25f))
        assertEquals(75f, state.sceneSettings(loc).shipYaw, .001f)
        val old = default.copy(screenPose = Pose(Vector3(0f, 1.35f, 3.2f), Quaternion(0f, 180f, 0f)), seat = SeatSetting()).toJson().apply { remove("shipYaw") }
        prefs.edit().putString("scene_settings_spaceship_cabin", old.toString()).commit()
        near(default.screenPose.t, state.sceneSettings(loc).screenPose.t)
        assertEquals(180f, state.sceneSettings(loc).seat.yaw, .001f)
        val custom = default.copy(screenPose = Pose(Vector3(1f, 2f, 3f)), seat = SeatSetting(1f, 0f, 0f, 45f)).toJson().apply { remove("shipYaw") }
        prefs.edit().putString("scene_settings_spaceship_cabin", custom.toString()).commit()
        near(Vector3(1f, 2f, 3f), state.sceneSettings(loc).screenPose.t)
        assertEquals(45f, state.sceneSettings(loc).seat.yaw, .001f)
        assertEquals(0f, SceneSettings.fromJson(JSONObject().put("shipYaw", "NaN"), default).shipYaw, .001f)
        prefs.edit().clear().commit()
    }
}
