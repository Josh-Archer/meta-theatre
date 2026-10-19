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
enum class TheatreLocation(val id: String, val title: String) {
    EARTH_ORBIT("earth_orbit", "Earth orbit"),
    SPACESHIP_CABIN("spaceship_cabin", "Spaceship cabin");

    companion object {
        fun fromId(id: String): TheatreLocation = entries.firstOrNull { it.id == id } ?: EARTH_ORBIT
    }
}

internal object EarthOrbit {
    const val defaultScreenWidth = 2.8f
    val defaultScreenHeight = 2.8f * 9f / 16f
    val defaultScreenPose = Pose(Vector3(0f, 1.28f, 2.5f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
    val defaultSeat = SeatSetting(0f, 0f, 0f, 0f)

    val screenWidth get() = defaultScreenWidth
    val screenHeight get() = defaultScreenHeight
    val screenPose get() = defaultScreenPose
}

internal object SpaceshipCabin {
    const val defaultScreenWidth = 3.2f
    val defaultScreenHeight = 3.2f * 9f / 16f
    val defaultScreenPose = Pose(Vector3(0f, 1.35f, 3.2f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
    val defaultSeat = SeatSetting(0f, 0f, 0f, 0f)

    val screenWidth get() = defaultScreenWidth
    val screenHeight get() = defaultScreenHeight
    val screenPose get() = defaultScreenPose
}

internal fun defaultScreenSize(location: TheatreLocation): ScreenSize = when (location) {
    TheatreLocation.EARTH_ORBIT -> ScreenSize(EarthOrbit.defaultScreenWidth, EarthOrbit.defaultScreenHeight)
    TheatreLocation.SPACESHIP_CABIN -> ScreenSize(SpaceshipCabin.defaultScreenWidth, SpaceshipCabin.defaultScreenHeight)
}

internal fun defaultScreenPose(location: TheatreLocation): Pose = when (location) {
    TheatreLocation.EARTH_ORBIT -> EarthOrbit.defaultScreenPose
    TheatreLocation.SPACESHIP_CABIN -> SpaceshipCabin.defaultScreenPose
}

internal fun defaultSeatSetting(location: TheatreLocation): SeatSetting = when (location) {
    TheatreLocation.EARTH_ORBIT -> EarthOrbit.defaultSeat
    TheatreLocation.SPACESHIP_CABIN -> SpaceshipCabin.defaultSeat
}

internal fun defaultSceneSettings(location: TheatreLocation): SceneSettings = SceneSettings(
    screenSize = defaultScreenSize(location),
    screenPose = defaultScreenPose(location),
    seat = defaultSeatSetting(location),
)

fun buildLocation(location: TheatreLocation): List<Entity> {
    return when (location) {
        TheatreLocation.EARTH_ORBIT -> buildEarthOrbit()
        TheatreLocation.SPACESHIP_CABIN -> buildSpaceshipCabin()
    }
}

private fun buildEarthOrbit(): List<Entity> {
    return listOf(chair())
}

private fun buildSpaceshipCabin(): List<Entity> {
    return listOf(chair(), hull())
}

internal val CHAIR_URI = "lounge_chair.glb".toUri()
internal val HULL_URI = "hull.glb".toUri()

private fun chair(): Entity {
    // Mesh is already chair-sized. Bottom at y=-0.39, backrest toward -Z.
    return Entity.create(
        Mesh(CHAIR_URI, hittable = MeshCollision.NoCollision),
        Scale(Vector3(1f, 1f, 1f)),
        Transform(Pose(Vector3(0f, 0.39f, 0.12f))),
    )
}

private fun hull(): Entity {
    return Entity.create(
        Mesh(HULL_URI, hittable = MeshCollision.NoCollision),
        Scale(Vector3(1f, 1f, 1f)),
        Transform(Pose(Vector3(0f, 0f, 0f))),
    )
}
