package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.*

/** Time-compressed, staged flight exhibit. Vehicle dimensions are meters; not live telemetry. */
internal object StarshipFlight {
    const val SCALE = .12f
    const val DURATION = 116.0
    enum class Phase(val label: String) {
        PAD("Ready for launch"), ASCENT("Liftoff"), SEPARATION("Stage separation"),
        ORBIT("Orbiting Earth"), DESCENT("Atmospheric descent"), FLIP("Landing flip"),
        LANDING("Landing burn"), LANDED("Recovered at the tower")
    }
    data class Frame(val phase: Phase, val ship: Pose, val booster: Pose?, val pad: Pose,
                     val shipThrust: Float, val boosterThrust: Float)
    private fun smooth(t: Double) = (t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }).toFloat()
    private fun blend(a: Vector3, b: Vector3, t: Float) = a + (b - a) * t

    fun frame(seconds: Double, center: Vector3, radius: Float, viewer: Vector3): Frame {
        require(seconds.isFinite() && radius.isFinite() && radius > 0)
        val t = seconds.coerceIn(0.0, DURATION)
        val towardViewer = (viewer - center).normalize()
        val reference = if (abs(towardViewer.y) < .98f) Vector3(0f, 1f, 0f) else Vector3(1f, 0f, 0f)
        val right = reference.cross(towardViewer).normalize()
        val vertical = towardViewer.cross(right).normalize()
        // Launch above and left of the movie; the viewer/camera never moves.
        val launch = (towardViewer + right * .80f + vertical * .42f).normalize()
        val tangent = vertical.cross(launch).normalize()
        val planeNormal = launch.cross(tangent).normalize()
        fun orientation(axis: Vector3) = Quaternion.fromDirection(planeNormal, axis)
        val upright = orientation(launch)
        val pad = Pose(center + launch * radius, upright)
        val catchCenter = radius + (7f + 71f + 25f) * SCALE
        val orbitRadius = catchCenter + 30f
        var radial = launch
        var distance = catchCenter
        var axis = launch
        var thrust = 0f
        val phase = when {
            t < 5 -> Phase.PAD
            t < 22 -> Phase.ASCENT
            t < 28 -> Phase.SEPARATION
            t < 70 -> Phase.ORBIT
            t < 90 -> Phase.DESCENT
            t < 96 -> Phase.FLIP
            t < 112 -> Phase.LANDING
            else -> Phase.LANDED
        }
        when (phase) {
            Phase.PAD, Phase.LANDED -> Unit
            Phase.ASCENT -> distance += 24f * smooth((t - 5) / 17)
            Phase.SEPARATION -> {
                distance += 24f + 6f * smooth((t - 22) / 6)
                axis = blend(launch, tangent, smooth((t - 22) / 6)).normalize()
                thrust = 1f
            }
            Phase.ORBIT -> {
                val angle = (t - 28) / 42 * 2 * PI
                radial = launch * cos(angle).toFloat() + tangent * sin(angle).toFloat()
                axis = tangent * cos(angle).toFloat() - launch * sin(angle).toFloat()
                distance = orbitRadius
            }
            Phase.DESCENT -> {
                distance = orbitRadius - 16f * smooth((t - 70) / 20)
                axis = tangent
            }
            Phase.FLIP -> {
                distance = orbitRadius - 16f - 4f * smooth((t - 90) / 6)
                axis = blend(tangent, launch, smooth((t - 90) / 6)).normalize()
                thrust = smooth((t - 90) / 3)
            }
            Phase.LANDING -> {
                distance = catchCenter + 10f * (1 - smooth((t - 96) / 16))
                thrust = if (t < 109) .75f else .75f * (1 - smooth((t - 109) / 3))
            }
        }
        val ship = Pose(center + radial * distance - axis * (25f * SCALE), orientation(axis))
        var booster: Pose? = null
        var boosterThrust = 0f
        if (t < 22) {
            booster = Pose(ship.t - launch * (71f * SCALE), upright)
            boosterThrust = if (t < 4) 0f else smooth((t - 4) / 1)
        } else if (t < 44) {
            // The booster separates continuously and returns beside the launch mount.
            val u = smooth((t - 22) / 22)
            val height = radius + 7f * SCALE + 24f * (1 - u)
            val offset = tangent * (sin(PI * u).toFloat() * 7f)
            booster = Pose(center + launch * height + offset, upright)
            boosterThrust = if (t > 35) sin(((t - 35) / 9 * PI).coerceIn(0.0, PI)).toFloat() else 0f
        }
        return Frame(phase, ship, booster, pad, thrust, boosterThrust)
    }
}
