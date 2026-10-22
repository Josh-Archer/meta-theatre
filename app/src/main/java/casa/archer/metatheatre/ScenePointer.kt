package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import kotlin.math.sqrt

internal object ScenePointer {
    fun valid(pose: Pose) = pose.t.x.isFinite() && pose.t.y.isFinite() && pose.t.z.isFinite() &&
        pose.q.w.isFinite() && pose.q.x.isFinite() && pose.q.y.isFinite() && pose.q.z.isFinite()
    fun hit(pointer: VisitorPointer, target: VisitorTarget): VisitorHit? {
        if (!valid(pointer.pose)) return null
        val direction = pointer.pose.forward().normalize()
        val offset = pointer.pose.t - target.pose.t
        val radius = target.radius * target.scale
        val b = offset.dot(direction)
        val discriminant = b * b - offset.dot(offset) + radius * radius
        if (discriminant < 0f || !discriminant.isFinite()) return null
        val root = sqrt(discriminant)
        val distance = (-b - root).let { if (it >= .05f) it else -b + root }
        if (distance < .05f || distance >= pointer.maxDistance) return null
        val point = pointer.pose.t + direction * distance
        return VisitorHit(target.asset, point, (point - target.pose.t).normalize(), distance)
    }
}
