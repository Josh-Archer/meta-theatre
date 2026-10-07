package casa.archer.metatheatre

import androidx.core.net.toUri
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform

/**
 * Chair in open space. The theatre screen is one panel the viewer can grab.
 *
 * Spatial SDK is left-handed: +X right, +Y up, +Z in front of the seat.
 * A panel's hittable face is local +Z, so the screen sits in front of the
 * seat with yaw 180.
 */
enum class TheatreLocation(val title: String) {
    EARTH_ORBIT("Earth orbit"),
}

object EarthOrbit {
    val screenWidth = 2.8f
    val screenHeight = 2.8f * 9f / 16f
    val screenPose = Pose(Vector3(0f, 1.28f, 2.5f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
}

fun buildLocation(location: TheatreLocation) {
    when (location) {
        TheatreLocation.EARTH_ORBIT -> buildEarthOrbit()
    }
}

private fun buildEarthOrbit() {
    chair()
}

private fun chair() {
    // Mesh is already chair-sized. Bottom at y=-0.39, backrest toward -Z.
    Entity.create(
        Mesh("lounge_chair.glb".toUri(), hittable = MeshCollision.NoCollision),
        Scale(Vector3(1f, 1f, 1f)),
        Transform(Pose(Vector3(0f, 0.39f, 0.12f))),
    )
}
