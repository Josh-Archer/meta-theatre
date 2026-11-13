package casa.archer.metatheatre

import com.meta.spatial.core.Vector3
import kotlin.math.*

/** Keep the distant planet exhibits outside Earth's growing silhouette when zooming in. */
internal object EarthClearance {
    /** Physical collision guard for inspection and return flights, which need not stay outside the silhouette. */
    fun outside(center: Vector3, bound: Float, earth: Vector3, radius: Float = EarthRenderer.RADIUS): Vector3 {
        val offset = center-earth
        val minimum = radius*EarthExploration.SOLID_RADIUS + bound + .5f
        if (offset.length() >= minimum) return center
        val normal = if (offset.length() > .001f) offset.normalize() else Vector3(0f,1f,0f)
        return earth + normal * minimum
    }

    fun body(body: SolarSystem.Body, earth: Vector3, viewer: Vector3, radius: Float = EarthRenderer.RADIUS): SolarSystem.Body {
        val bound = body.radius * if (body.name == "saturn") SolarSystem.RING_OUTER else 1f
        return body.copy(center = position(body.center, bound, earth, viewer, radius))
    }

    fun position(center: Vector3, bound: Float, earth: Vector3, viewer: Vector3, radius: Float = EarthRenderer.RADIUS): Vector3 {
        val globe = earth - viewer
        val offset = center - viewer
        val distance = offset.length()
        if (globe.length() <= radius * EarthExploration.SOLID_RADIUS || distance <= bound) return center
        val axis = globe.normalize()
        val direction = offset.normalize()
        val limit = asin((radius * EarthExploration.SOLID_RADIUS / globe.length()).toDouble()) +
            asin((bound / distance).toDouble().coerceIn(0.0,1.0))
        val dot = axis.dot(direction).toDouble().coerceIn(-1.0,1.0)
        if (acos(dot) >= limit) return center
        val projected = direction - axis * dot.toFloat()
        val tangent = if (projected.length() > .001f) projected.normalize() else
            axis.cross(if (abs(axis.y) < .9f) Vector3(0f,1f,0f) else Vector3(1f,0f,0f)).normalize()
        val angle = limit + Math.toRadians(2.0)
        return viewer + (axis * cos(angle).toFloat() + tangent * sin(angle).toFloat()) * distance
    }
}
