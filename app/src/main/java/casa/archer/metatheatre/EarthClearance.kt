package casa.archer.metatheatre

import com.meta.spatial.core.Vector3
import kotlin.math.*

/** Keep the distant planet exhibits outside Earth's growing silhouette when zooming in. */
internal object EarthClearance {
    fun body(body: SolarSystem.Body, earth: Vector3, viewer: Vector3): SolarSystem.Body {
        val bound = body.radius * if (body.name == "saturn") SolarSystem.RING_OUTER else 1f
        return body.copy(center = position(body.center, bound, earth, viewer))
    }

    fun position(center: Vector3, bound: Float, earth: Vector3, viewer: Vector3): Vector3 {
        val globe = earth - viewer
        val offset = center - viewer
        val distance = offset.length()
        if (globe.length() <= EarthRenderer.RADIUS * 1.012f || distance <= bound) return center
        val axis = globe.normalize()
        val direction = offset.normalize()
        val limit = asin((EarthRenderer.RADIUS * 1.012f / globe.length()).toDouble()) +
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
