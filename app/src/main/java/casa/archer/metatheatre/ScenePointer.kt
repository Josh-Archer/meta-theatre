package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import kotlin.math.sqrt

internal object ScenePointer {
    fun valid(pose: Pose) = listOf(pose.t.x, pose.t.y, pose.t.z, pose.q.w, pose.q.x, pose.q.y, pose.q.z).all { it.isFinite() }
    fun hit(pointer: VisitorPointer, target: VisitorTarget): VisitorHit? {
        if (!valid(pointer.pose)) return null
        val direction = pointer.pose.forward().normalize()
        val offset = pointer.pose.t - target.pose.t
        val radius = target.radius * target.scale
        val b = offset.dot(direction)
        val discriminant = b * b - offset.dot(offset) + radius * radius
        if (discriminant < 0f || !discriminant.isFinite()) return null
        val distance = (-b - sqrt(discriminant)).let { if (it >= .05f) it else -b + sqrt(discriminant) }
        if (distance < .05f || distance >= pointer.maxDistance) return null
        val point = pointer.pose.t + direction * distance
        return VisitorHit(target.asset, point, (point - target.pose.t).normalize(), distance)
    }
}
