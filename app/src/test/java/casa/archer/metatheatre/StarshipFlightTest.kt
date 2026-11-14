package casa.archer.metatheatre

import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class StarshipFlightTest {
    private val center = EarthRenderer.CENTER
    private val eye = EarthRenderer.SEAT_EYE
    private fun frame(t: Double) = StarshipFlight.frame(t, center, EarthRenderer.RADIUS, eye)

    @Test fun everyFlightPhaseKeepsTheVehicleOutsideEarthAndTheViewerClear() {
        val phases = mutableSetOf<StarshipFlight.Phase>()
        val rows = mutableListOf<String>()
        for (i in 0..232) {
            val t = i * .5
            val f = frame(t); phases.add(f.phase)
            val up = f.ship.q * Vector3(0f, 1f, 0f)
            val tip = f.ship.t + up * (50f * StarshipFlight.SCALE)
            for (point in listOf(f.ship.t, tip)) {
                assertTrue((point - center).length() > EarthRenderer.RADIUS)
                assertTrue((point - eye).length() > 8f)
                assertTrue(point.x.isFinite() && point.y.isFinite() && point.z.isFinite())
            }
            assertEquals(1f, up.length(), .0001f)
            assertTrue(f.shipThrust in 0f..1f && f.boosterThrust in 0f..1f)
            fun pose(p: com.meta.spatial.core.Pose?) = if (p == null) "null" else
                "{\"p\":[${p.t.x},${p.t.y},${p.t.z}],\"q\":[${p.q.w},${p.q.x},${p.q.y},${p.q.z}]}"
            rows.add("{\"t\":$t,\"phase\":\"${f.phase}\",\"ship\":${pose(f.ship)},\"booster\":${pose(f.booster)},\"pad\":${pose(f.pad)},\"shipThrust\":${f.shipThrust},\"boosterThrust\":${f.boosterThrust}}")
        }
        assertEquals(StarshipFlight.Phase.entries.toSet(), phases)
        // Optional authoring export evaluates the actual native trajectory, not a second implementation.
        System.getenv("META_THEATRE_FLIGHT_PREVIEW")?.let { File(it).writeText(rows.joinToString(",", "[", "]")) }
    }

    @Test fun phaseBoundariesDoNotTeleportOrSnapOrientation() {
        for (t in listOf(5.0,22.0,28.0,70.0,90.0,96.0,112.0)) {
            val a=frame(t-.0001);val b=frame(t+.0001)
            assertTrue("Position continuity at $t", (a.ship.t-b.ship.t).length() < .01f)
            val ua=a.ship.q*Vector3(0f,1f,0f);val ub=b.ship.q*Vector3(0f,1f,0f)
            assertTrue("Orientation continuity at $t", (ua-ub).length() < .001f)
        }
        val before=frame(21.9999);val after=frame(22.0001)
        assertTrue((before.booster!!.t-after.booster!!.t).length()<.01f)
    }

    @Test fun orbitClosesAndCoastsWithoutExhaustThenReturnsToTheTower() {
        val start=frame(28.0);val end=frame(70.0)
        assertTrue((start.ship.t-end.ship.t).length()<.001f)
        for(t in listOf(30.0,40.0,55.0,69.0)) assertEquals(0f,frame(t).shipThrust,0f)
        assertTrue((frame(0.0).ship.t-frame(116.0).ship.t).length()<.001f)
        assertEquals(0f,frame(116.0).shipThrust,0f)
        assertNull(frame(116.0).booster)
        assertEquals(frame(116.0).ship,frame(500.0).ship)
    }

    @Test fun verticalEarthViewsRemainFiniteAndControlsDoNotStealOtherRows() {
        for (eye in listOf(Vector3(0f,200f,0f),Vector3(0f,-200f,0f))) {
            val f=StarshipFlight.frame(40.0,Vector3(0f,0f,0f),90f,eye)
            assertTrue(f.ship.q.w.isFinite())
            assertEquals(1f,(f.ship.q*Vector3(0f,1f,0f)).length(),.0001f)
        }
        val y=(ScenePickerControls.FLIGHT_ROW_TOP+ScenePickerControls.FLIGHT_ROW_BOTTOM)/2
        assertEquals(ScenePickerAction.LaunchStarship,ScenePickerControls.at(.3f,y))
        assertEquals(ScenePickerAction.StopStarship,ScenePickerControls.at(.8f,y))
        assertNull(ScenePickerControls.at(.3f,ScenePickerControls.FLIGHT_ROW_TOP-.01f))
        assertEquals(ScenePickerAction.ResetDefault,ScenePickerControls.at(.2f,ScenePickerControls.ACTION_ROW_TOP+.01f))
    }
}
