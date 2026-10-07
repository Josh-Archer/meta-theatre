package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

internal object HandTrackingSpace {
    fun toScene(palm: OpenPalm, trackingToScene: Pose): OpenPalm {
        val position = trackingToScene * Vector3(palm.position.x, palm.position.y, palm.position.z)
        val normal = trackingToScene.q * Vector3(palm.normal.x, palm.normal.y, palm.normal.z)
        return palm.copy(position = HandPoint(position.x, position.y, position.z),
            normal = HandPoint(normal.x, normal.y, normal.z))
    }
}
